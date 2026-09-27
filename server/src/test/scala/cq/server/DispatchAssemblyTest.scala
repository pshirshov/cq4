package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.UUID
import zio.{IO, Runtime, Unsafe, ZIO}

abstract class DispatchAssemblyTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]]),
  )
  private final class ApplicationApi(application: Application, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    override def call(command: Command): Result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(application.execute(authority, command)).getOrThrowFiberFailure() }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Assembly cannot publish usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Assembly cannot publish artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Assembly cannot admit results")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Assembly cannot issue credentials")
  }
  private def requestId: RequestId = RequestId(UUID.randomUUID())
  private def draft(body: String): ItemDraft = ItemDraft("Consumer task", body, Set.empty, false,
    Content.Task(TaskStatus.Ready, List("Behavior verified"), None, Nil), Nil)

  "Reference assembly (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "resolve paginated Unicode and chain an exact candidate by handle while enforcing revisions and claim ownership" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) =>
        val clock = Clock.systemUTC()
        val scope = Scope(ProjectId(UUID.randomUUID()), Actor("assembly governor", SessionId(UUID.randomUUID()), Role.Governor))
        val collector = scope.copy(actor = scope.actor.copy(subject = "assembly collector", role = Role.Collector))
        val authorization = new Authorization(AccessConfig("assembly-contract-test-root", "http://localhost"), clock)
        val root = authorization.authenticate("assembly-contract-test-root", Some(scope.actor.session.value.toString))
        val authority = authorization.authenticate(authorization.grant(root, GrantRequest(scope.project, scope.actor, clock.millis() + 60000)).value, None)
        val application = new Application(ledger, repository, usage, artifacts, admissions, authorization)
        val narrative = "Consumer specification λ😀" * 1000
        val largeBody = "evidence λ😀\n" * 5000
        for {
          runtime <- ZIO.runtime[Any]
          _ <- ledger.initialize(scope, "Assembly consumer")
          ack <- ledger.change(scope, ChangeRequest(requestId, List(Mutation.Create(draft(narrative)), Mutation.Create(draft("Shared guidance"))), Nil, "Fixture"))
          member = ack.items.head
          guidance = ack.items(1)
          claim <- ledger.acquire(scope, ClaimId(UUID.randomUUID()), Set(member.id), 60000)
          assignment <- usage.assign(collector, Assignment(AssignmentId(UUID.randomUUID()), scope.project, Set(member.id), Attribution.Direct, None, None))
          governing <- usage.assign(collector, Assignment(AssignmentId(UUID.randomUUID()), scope.project, Set.empty, Attribution.Unattributed, None, None))
          parent <- usage.start(collector, Attempt(AttemptId(UUID.randomUUID()), governing.id, None, scope.actor.session, Role.Governor,
            Harness.Codex, "fixture", "fixture", "fixture", clock.millis()))
          attempt <- usage.start(collector, Attempt(AttemptId(UUID.randomUUID()), assignment.id, Some(parent.id), scope.actor.session, Role.Worker,
            Harness.Codex, "fixture", "fixture", "fixture", clock.millis()))
          small <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Input, "text/plain", "short"))
          large <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Input, "text/plain", largeBody))
          limits = HostLimits(3000, 10000, 1000, 300, 2000, 262144)
          request = DispatchRequest(requestId, DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, List(member), List(guidance), List(small.id), None, claim.fence, limits)
          stored = ChildResult(attempt.id, request, GitCommit("a" * 40), Some(GitCommit("b" * 40)),
            ChildReport.Work(List(WorkMember(member.id, WorkDisposition.CandidateReady, "Candidate produced; validation is model-declared"))), Nil)
          previous <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Result,
            "application/json", Wire.encode(ChildResult_JsonCodec, stored)))
          _ <- ZIO.attemptBlocking {
            val unadmitted = request.copy(work = DispatchWork.Reviewer(), artifacts = Nil, previous = Some(previous.id))
            intercept[DomainFailure](new InputAssembler(new ApplicationApi(application, authority, runtime), scope, clock).assemble(unadmitted))
          }
          _ <- admissions.admit(collector, HostAdmissionInput(scope.project, previous.id, scope.actor))
          unbound <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Result,
            "application/json", Wire.encode(ChildResult_JsonCodec, stored.copy(candidate = Some(GitCommit("c" * 40))))))
          _ <- ZIO.attemptBlocking {
            val api = new ApplicationApi(application, authority, runtime)
            val assembler = new InputAssembler(api, scope, clock)
            val one = assembler.assemble(request)
            val largeRequest = request.copy(artifacts = List(large.id))
            val two = assembler.assemble(largeRequest)
            assert(one.members.head.item.draft.body == narrative && one.guidance.head.item.draft.body == "Shared guidance")
            assert(one.artifacts.head.body == "short" && two.artifacts.head.body == largeBody)
            assert(assembler.assemble(largeRequest) == two)
            assert(Wire.encode(DispatchRequest_JsonCodec, request).getBytes(UTF_8).length == Wire.encode(DispatchRequest_JsonCodec, largeRequest).getBytes(UTF_8).length)
            assert(Wire.encode(ChildInput_JsonCodec, two).getBytes(UTF_8).length > Wire.encode(ChildInput_JsonCodec, one).getBytes(UTF_8).length + 50000)
            val review = request.copy(work = DispatchWork.Reviewer(), harness = Harness.Pi, artifacts = Nil, previous = Some(previous.id))
            val resolved = assembler.assemble(review)
            assert(resolved.previous.contains(stored) && resolved.previous.get.candidate == stored.candidate)
            intercept[IllegalArgumentException](assembler.assemble(review.copy(previous = Some(unbound.id))))
            intercept[IllegalArgumentException](assembler.assemble(request.copy(members = List(member.copy(revision = Revision(member.revision.value + 1))))))
            intercept[IllegalArgumentException](assembler.assemble(request.copy(members = List(member, guidance), guidance = Nil)))
            intercept[IllegalArgumentException](assembler.assemble(request.copy(guidance = List(guidance.copy(id = guidance.id.copy(project = ProjectId(UUID.randomUUID())))))))
            intercept[IllegalArgumentException](assembler.assemble(review.copy(previous = Some(small.id))))
            intercept[IllegalArgumentException](new InputAssembler(api, scope.copy(actor = scope.actor.copy(subject = "different governor")), clock).assemble(request))
          }
          _ <- ledger.release(scope, claim.fence)
          late <- usage.start(collector, attempt.copy(id = AttemptId(UUID.randomUUID())))
          rejected <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), late.id, ArtifactKind.Result,
            "application/json", Wire.encode(ChildResult_JsonCodec, stored.copy(attempt = late.id))))
          decision <- admissions.admit(collector, HostAdmissionInput(scope.project, rejected.id, scope.actor))
          _ <- assertIO(decision.decision == AdmissionDecision.Rejected(AdmissionRejection.ClaimLost))
          replacement <- ledger.acquire(scope, ClaimId(UUID.randomUUID()), Set(member.id), 60000)
          _ <- ZIO.attemptBlocking {
            val review = request.copy(work = DispatchWork.Reviewer(), artifacts = Nil, previous = Some(rejected.id), fence = replacement.fence)
            intercept[IllegalArgumentException](new InputAssembler(new ApplicationApi(application, authority, runtime), scope, clock).assemble(review))
          }
          _ <- ZIO.attemptBlocking {
            intercept[DomainFailure](new InputAssembler(new ApplicationApi(application, authority, runtime), scope, clock).assemble(request))
          }
        } yield ()
    }
  }
}

final class DispatchAssemblyDummy extends DispatchAssemblyTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class DispatchAssemblyPostgres extends DispatchAssemblyTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
