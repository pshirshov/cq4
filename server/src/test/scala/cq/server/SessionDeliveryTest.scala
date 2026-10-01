package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.io.IOException
import java.nio.file.{Files, StandardOpenOption}
import java.time.Clock
import java.util.{Base64, UUID}
import zio.{IO, Runtime, Task, Unsafe, ZIO}

abstract class SessionDeliveryTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, WorkspaceTestPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]]),
  )
  private final class Receiver(application: Application, authority: Authority, runtime: Runtime[Any], lose: AttemptId) extends ServerApi {
    private var lost = false
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrowFiberFailure() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = {
      val result = execute(application.ingest(authority, value))
      value.operation match {
        case HostUsage.Finish(outcome) if outcome.attempt == lose && !lost =>
          lost = true
          throw new IOException("Injected acknowledgement loss after recorded outcome")
        case _ => ()
      }
      result
    }
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Recovery cannot grant authority")
  }

  "Interrupted publication (Behavioral Active Blackbox; dummy Group / PostgreSQL and Git Good Communication)" should {
    "replay attached Pi observations without inventing a governing process or counting a response twice" in {
      (ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], fixture: WorkspaceFixture) =>
      val clock = Clock.systemUTC()
      def uuid: UUID = UUID.randomUUID()
      val owner = Scope(ProjectId(uuid), Actor("CQ governor", SessionId(uuid), Role.Governor))
      val collector = owner.copy(actor = owner.actor.copy(role = Role.Collector))
      val auth = new Authorization(AccessConfig("attached-usage-recovery-root-token", "http://localhost"), clock)
      val root = auth.authenticate("attached-usage-recovery-root-token", Some(owner.actor.session.value.toString))
      val authority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, clock.millis() + 60000)).value, None)
      val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
      ZIO.scoped { for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(owner, "Attached accounting")
        assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
        attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor,
          Harness.Pi, "unobserved-interactive-provider", "unobserved-interactive-model", "fixture", clock.millis(), UsagePhase.Govern))
        run = SupervisorRun(ProjectConfig(owner.project, "http://localhost", "Attached accounting"), assignment, attempt, "0.87.1",
          fixture.source.toString, fixture.base, SessionOwnership.Attached)
        directory <- ZIO.attemptBlocking(Files.createTempDirectory("cq-attached-usage-"))
        journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), owner.project, owner.actor.session)))(value => ZIO.attemptBlocking(value.close()).orDie)
        receiver = new Receiver(application, authority, runtime, AttemptId(uuid))
        accounting = new AttachedUsage(directory, run, clock)
        first = AttachedPiEvent(1, "native-session", 1, "provider", "model", 1000, Some("response-1"), "stop",
          Some(100), Some(30), Some(20), Some(0), None, Some(150), Some(DecimalAmount("0.004")))
        second = first.copy(sequence = 2, timestamp = 1001, responseId = Some("response-2"), input = Some(2), output = Some(3), cacheRead = Some(0), totalTokens = Some(5))
        _ <- ZIO.attemptBlocking {
          accounting.accept(first, receiver)
          accounting.accept(first, receiver)
          accounting.accept(second, receiver)
          accounting.accept(second.copy(sequence = 3), receiver)
          assert(scala.util.Try(accounting.accept(first.copy(sequence = 4, input = Some(-1)), receiver)).isFailure)
          assert(!Files.exists(directory.resolve("pi-usage/0004")))
          assert(scala.util.Try(accounting.accept(first.copy(sequence = 4, session = "foreign"), receiver)).isFailure)
        }
        before <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
        _ <- assertIO(before.unattributed.total.known == 155 && before.attemptsWithoutMeters == 0 && before.incompleteMeters == 1)
        _ <- ZIO.attemptBlocking {
          Files.delete(directory.resolve("pi-usage/0002/delivery/final/000000.ack"))
          HostFiles.directory(directory.resolve("pi-usage/0004"))
          Files.writeString(directory.resolve("pi-usage/0004/.upload-interrupted.pending"), "{\"partial")
        }
        recovered <- new SessionDelivery(journal, fixture.service, clock).flush(directory, run, receiver)
        after <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
        _ <- assertIO(recovered.acknowledged == 2 && after.unattributed.total.known == 155 && after.attempts.unknown == 1 && after.attempts.running == 0)
        _ <- assertIO(recovered.incompleteTickets == List(directory.resolve("pi-usage/0004")))
        repeated <- new SessionDelivery(journal, fixture.service, clock).flush(directory, run, receiver)
        _ <- assertIO(repeated.acknowledged == 0)
        _ <- assertIO(repeated.incompleteTickets == recovered.incompleteTickets && Files.exists(directory.resolve("pi-usage/0004/.upload-interrupted.pending")))
        missing <- artifacts.metadata(owner, NativeArtifacts.id(attempt.id, "stdout")).either
        _ <- assertIO(missing.isLeft && journal.records.isEmpty)
        metadata <- artifacts.metadata(owner, NativeArtifacts.id(attempt.id, "attached-pi-1"))
        _ <- assertIO(metadata.kind == ArtifactKind.Transcript)
      } yield () }
    }

    "recover independent reviewer checks despite a lost upload acknowledgement without launching or completing the review" in {
      (ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], fixture: WorkspaceFixture) =>
      val clock = Clock.systemUTC()
      def uuid: UUID = UUID.randomUUID()
      val owner = Scope(ProjectId(uuid), Actor("CQ governor", SessionId(uuid), Role.Governor))
      val collector = owner.copy(actor = owner.actor.copy(role = Role.Collector))
      val auth = new Authorization(AccessConfig("reviewer-check-recovery-root-token", "http://localhost"), clock)
      val root = auth.authenticate("reviewer-check-recovery-root-token", Some(owner.actor.session.value.toString))
      val authority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, clock.millis() + 60000)).value, None)
      val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
      ZIO.scoped { for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(owner, "Check recovery")
        created <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(ItemDraft("Task", "Review", Set.empty, false,
          Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil))), Nil, "Fixture"))
        assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
        governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor,
          Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern))
        childAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, created.items.map(_.id).toSet, Attribution.Direct, None, None))
        reviewer <- usage.start(collector, governor.copy(id = AttemptId(uuid), assignment = childAssignment.id, parent = Some(governor.id), role = Role.Reviewer))
        limits = HostLimits(3000, 10000, 1000, 300, 2000, 65536)
        profile = HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty)
        request = DispatchRequest(RequestId(uuid), DispatchWork.Reviewer(ReviewerMode.Candidate), Harness.Codex, created.items, Nil, Nil,
          Some(ArtifactId(uuid)), Fence(ClaimId(uuid), 1), limits)
        ticket = DispatchTicket(request, childAssignment, reviewer, profile, None)
        run = SupervisorRun(ProjectConfig(owner.project, "http://localhost", "Check recovery"), assignment, governor, profile.version, fixture.source.toString, fixture.base, SessionOwnership.Managed)
        declarations = List("a-sealed", "b-interrupted", "c-unstarted").map(name => ValidationCheck(name, List("verify"), 1000, 65536))
        directory <- ZIO.attemptBlocking(Files.createTempDirectory("cq-check-recovery-"))
        journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), owner.project, owner.actor.session)))(value => ZIO.attemptBlocking(value.close()).orDie)
        _ <- ZIO.attemptBlocking {
          val child = directory.resolve("children").resolve(reviewer.id.value.toString)
          HostFiles.directory(child)
          HostFiles.immutable(child.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, ticket), 65536)
          val settings = SupervisorSettings(directory.toString, "/guardian", List(profile), limits, declarations, None, None)
          HostFiles.immutable(directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, settings), 65536)
          val native = WorkspaceSpec(owner.project, owner.actor.session, reviewer.id, fixture.source.toString, fixture.base)
          journal.reserve(native, "a" * 64, 1000)
          declarations.zipWithIndex.foreach { case (check, index) =>
            val id = AttemptId(NativeArtifacts.id(reviewer.id, "declared-check-job-" + check.name).value)
            val spec = native.copy(attempt = id)
            val retained = DeclaredCheckTicket(reviewer.id, check, spec, "b" * 64)
            val path = child.resolve("checks").resolve(check.name)
            HostFiles.directory(path)
            HostFiles.immutable(path.resolve("ticket.json"), HostFiles.encode(DeclaredCheckTicket_JsonCodec, retained), 32768)
            if (index < 2) {
              val reserved = journal.reserve(spec, retained.fingerprint, 1000)._1
              if (index == 0) {
                val settled = reserved.copy(phase = JobPhase.Settled, exit = Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), revision = 2, updatedAt = 1001)
                journal.replace(reserved, settled)
                new DeclaredCheckPublication(path, retained, directory.resolve("payload")).seal(Some(settled), None)
              }
            }
          }
        }
        receiver = new Receiver(application, authority, runtime, AttemptId(uuid))
        lostArtifact = NativeArtifacts.id(reviewer.id, "review-check-a-sealed")
        lossy = new ServerApi {
          private var lost = false
          override def artifact(value: ArtifactUpload): ArtifactMetadata = {
            val stored = receiver.artifact(value)
            if (value.id == lostArtifact && !lost) { lost = true; throw new IOException("Lost reviewer check acknowledgement") }
            stored
          }
          override def usage(value: HostUsageInput): HostUsageResult = receiver.usage(value)
          override def call(value: Command): Result = receiver.call(value)
          override def grant(value: GrantRequest): AccessToken = receiver.grant(value)
          override def admit(value: HostAdmissionInput): ResultAdmission = receiver.admit(value)
          override def integrate(value: HostIntegrationInput): IntegrationRecord = receiver.integrate(value)
        }
        delivery = new SessionDelivery(journal, fixture.service, clock)
        before = journal.records.map(_.workspace.attempt)
        failed <- delivery.flush(directory, run, lossy).either
        _ <- assertIO(failed.left.exists(_.isInstanceOf[IOException]))
        independent <- artifacts.metadata(owner, NativeArtifacts.id(reviewer.id, "review-check-b-interrupted"))
        unstarted <- artifacts.metadata(owner, NativeArtifacts.id(reviewer.id, "review-check-c-unstarted-status"))
        noObservation <- artifacts.metadata(owner, NativeArtifacts.id(reviewer.id, "review-check-c-unstarted")).either
        _ <- assertIO(independent.kind == ArtifactKind.Validation && unstarted.kind == ArtifactKind.Transcript && noObservation.isLeft)
        outcome <- usage.outcomes(owner, reviewer.id, 0, 20)
        _ <- assertIO(outcome.entries.size == 1 && outcome.entries.head.value.state == AttemptState.Unknown)
        recovered <- delivery.flush(directory, run, lossy)
        repeated <- delivery.flush(directory, run, lossy)
        _ <- assertIO(recovered.acknowledged == 1 && repeated.acknowledged == 0 && journal.records.map(_.workspace.attempt) == before)
        _ <- ZIO.attemptBlocking {
          val child = directory.resolve("children").resolve(reviewer.id.value.toString)
          val states = declarations.map(check => HostFiles.read(child.resolve("checks").resolve(check.name).resolve("result.json"), DeclaredCheckStatus_JsonCodec, 4096))
          assert(states.map(_.phase) == List(DeclaredCheckPhase.Completed, DeclaredCheckPhase.Unknown, DeclaredCheckPhase.Unknown))
          assert(states(1).evidence.exists(_.state == ValidationState.Unknown) && states(2).evidence.isEmpty)
          assert(!Files.exists(child.resolve("receipt.json")))
        }
      } yield () }
    }

    "recover sealed child publications after lost admission and outcome acknowledgements without rereading output" in {
      (ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], fixture: WorkspaceFixture) =>
        ZIO.foreachDiscard(List(false, true)) { releaseBefore =>
          val clock = Clock.systemUTC()
          val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
          val collector = owner.copy(actor = owner.actor.copy(role = Role.Collector))
          val auth = new Authorization(AccessConfig("sealed-publication-root-token", "http://localhost"), clock)
          val root = auth.authenticate("sealed-publication-root-token", Some(owner.actor.session.value.toString))
          val authority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, clock.millis() + 60000)).value, None)
          val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
          val initialUsage = """{"type":"thread.started","thread_id":"sealed-thread"}
{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":20,"cache_write_input_tokens":0,"output_tokens":31,"reasoning_output_tokens":3}}
"""
          def uuid: UUID = UUID.randomUUID()
          ZIO.scoped {
            for {
              runtime <- ZIO.runtime[Any]
              _ <- ledger.initialize(owner, "Sealed recovery")
              ack <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(ItemDraft("Task", "Body", Set.empty, false,
                Content.Task(TaskStatus.Ready, List("Outcome"), None, Nil), Nil))), Nil, "Fixture"))
              member = ack.items.head
              claim <- ledger.acquire(owner, ClaimId(uuid), Set(member.id), 300000)
              assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
              governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor,
                Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern))
              run = SupervisorRun(ProjectConfig(owner.project, "http://localhost", "Sealed"), assignment, governor, "0.156.1", fixture.source.toString, fixture.base, SessionOwnership.Managed)
              childAssignment = Assignment(AssignmentId(uuid), owner.project, Set(member.id), Attribution.Direct, None, None)
              attempt = governor.copy(id = AttemptId(uuid), assignment = childAssignment.id, parent = Some(governor.id), role = Role.Worker, startedAt = 1001)
              request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, List(member), Nil, Nil, None,
                claim.fence, HostLimits(3000, 10000, 1000, 300, 2000, 262144))
              ticket = DispatchTicket(request, childAssignment, attempt, HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty), None)
              result = ChildResult(attempt.id, request, fixture.base, Some(fixture.base),
                ChildReport.Work(List(WorkMember(member.id, WorkDisposition.CandidateReady, "Candidate", Nil))), Nil, RetainedEvidence(Nil, Nil))
              directory <- ZIO.attemptBlocking(Files.createTempDirectory("cq-sealed-recovery-"))
              journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), owner.project, owner.actor.session)))(v => ZIO.attemptBlocking(v.close()).orDie)
              child = directory.resolve("children").resolve(attempt.id.value.toString)
              _ <- ZIO.attemptBlocking {
                HostFiles.directory(child)
                HostFiles.immutable(child.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, ticket), 65536)
                val governing = new DeliveryQueue(directory.resolve("delivery"))
                governing.commit(List(HostDelivery.Usage(HostUsageInput(owner.project,
                  HostUsage.Finish(AttemptOutcome(RequestId(uuid), governor.id, AttemptState.Completed, 2000, Nil, None))))))
                val initial = new DeliveryQueue(child.resolve("delivery"))
                initial.enqueue(0, DeliveryBatch(List(HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Assign(childAssignment))),
                  HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Start(attempt))))))
                val payload = directory.resolve("payload").resolve(attempt.id.value.toString)
                HostFiles.directory(payload)
                Files.writeString(payload.resolve("stdout"), initialUsage)
                val bytes = initialUsage.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                val (native, parts) = NativeArtifacts.binary(owner.project, attempt.id, "stdout", "application/x-ndjson", bytes)
                val collected = new HarnessUsage().collect(new java.io.ByteArrayInputStream(bytes),
                  UsageCollectionRequest(attempt.id, Harness.Codex, "0.156.1", UsageOrigin.Fresh, 2000, native))
                val observations = collected.meters.flatMap(batch => HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Meter(batch.meter))) ::
                  batch.observations.map(value => HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Ingest(value)))))
                val status = DispatchProjection.pending(ticket).copy(phase = DispatchPhase.Publishing, process = Some(JobPhase.Settled))
                val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id, AttemptState.Completed, 2000, Nil, None)
                new ChildPublicationDelivery(child, ticket).seal(ChildPublication(owner.project, owner.actor, Some(result), status, outcome),
                  parts.map(HostDelivery.Artifact.apply) ++ observations)
              }
              receiver = new Receiver(application, authority, runtime, attempt.id)
              lossy = new ServerApi {
                private var first = true
                override def call(value: Command): Result = receiver.call(value)
                override def artifact(value: ArtifactUpload): ArtifactMetadata = receiver.artifact(value)
                override def usage(value: HostUsageInput): HostUsageResult = receiver.usage(value)
                override def grant(value: GrantRequest): AccessToken = receiver.grant(value)
                override def integrate(value: HostIntegrationInput): IntegrationRecord = receiver.integrate(value)
                override def admit(value: HostAdmissionInput): ResultAdmission = {
                  val record = receiver.admit(value)
                  if (first) { first = false; throw new IOException("Lost admission acknowledgement") }
                  record
                }
              }
              _ <- if (releaseBefore) ledger.release(owner, claim.fence).unit else ZIO.unit
              delivery = new SessionDelivery(journal, fixture.service, clock)
              first <- delivery.flush(directory, run, lossy).either
              _ <- assertIO(first.left.exists(_.isInstanceOf[IOException]) && !Files.exists(child.resolve("receipt.json")))
              decision <- admissions.get(owner, attempt.id)
              _ <- assertIO(decision.decision == (if (releaseBefore) AdmissionDecision.Rejected(AdmissionRejection.ClaimLost) else AdmissionDecision.Accepted()))
              before <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
              _ <- assertIO(before.direct.total.known == 131 && before.attempts.running == 1)
              _ <- if (releaseBefore) ledger.acquire(owner, ClaimId(uuid), Set(member.id), 300000).unit else ledger.release(owner, claim.fence).unit
              _ <- ZIO.attemptBlocking(Files.writeString(directory.resolve("payload").resolve(attempt.id.value.toString).resolve("stdout"), initialUsage.replace(":100", ":900")))
              second <- delivery.flush(directory, run, lossy).either
              _ <- assertIO(second.left.exists(_.isInstanceOf[IOException]) && !Files.exists(child.resolve("receipt.json")))
              _ <- delivery.flush(directory, run, lossy)
              status <- ZIO.attemptBlocking(HostFiles.read(child.resolve("receipt.json"), DispatchStatus_JsonCodec, 16384))
              _ <- assertIO(status.usageDelivered && status.phase == (if (releaseBefore) DispatchPhase.Failed else DispatchPhase.Completed) && status.result.nonEmpty == !releaseBefore)
              after <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
              _ <- assertIO(after.direct.total.known == 131 && after.attempts.running == 0)
              outcomes <- usage.outcomes(owner, attempt.id, 0, 20)
              _ <- assertIO(outcomes.entries.size == 1 && outcomes.entries.head.value.finishedAt == 2000 &&
                outcomes.entries.head.value.state == (if (releaseBefore) AttemptState.Failed else AttemptState.Completed))
              repeated <- delivery.flush(directory, run, lossy)
              stable <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
              replay <- admissions.get(owner, attempt.id)
              _ <- assertIO(repeated.acknowledged == 0 && stable == after && replay == decision)
            } yield ()
          }
        }
    }

    "recover pre-job tickets and partial output once while replaying committed results without readmission" in {
      (ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], fixture: WorkspaceFixture) =>
        val clock = Clock.systemUTC()
        val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
        val collector = owner.copy(actor = owner.actor.copy(role = Role.Collector))
        val auth = new Authorization(AccessConfig("recovery-contract-root-token", "http://localhost"), clock)
        val rootAuthority = auth.authenticate("recovery-contract-root-token", Some(owner.actor.session.value.toString))
        val authority = auth.authenticate(auth.grant(rootAuthority, GrantRequest(owner.project, collector.actor, clock.millis() + 60000)).value, None)
        val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
        val assignment = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None)
        val governor = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Governor,
          Harness.Codex, "fixture", "fixture", "fixture", clock.millis(), UsagePhase.Govern)
        val run = SupervisorRun(ProjectConfig(owner.project, "http://localhost", "Recovery"), assignment, governor, "0.156.1", fixture.source.toString, fixture.base, SessionOwnership.Managed)
        val limits = HostLimits(3000, 10000, 1000, 300, 2000, 262144)
        val profile = HarnessSetting(Harness.Codex, "/fixture/codex", "fixture", "fixture", "0.156.1", Nil, Set.empty)
        val draft = ItemDraft("Recovery consumer", "Retain interrupted evidence", Set.empty, false,
          Content.Task(TaskStatus.Ready, List("Account once"), None, Nil), Nil)
        def uuid: UUID = UUID.randomUUID()
        def native(input: Int): String = s"""{"type":"thread.started","thread_id":"recovery-thread"}
{"type":"turn.completed","usage":{"input_tokens":$input,"cached_input_tokens":20,"cache_write_input_tokens":0,"output_tokens":31,"reasoning_output_tokens":3}}
"""
        ZIO.scoped {
          for {
            runtime <- ZIO.runtime[Any]
            _ <- ledger.initialize(owner, "Recovery consumer")
            ack <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(draft)), Nil, "Fixture"))
            member = ack.items.head
            fence <- ledger.acquire(owner, ClaimId(uuid), Set(member.id), 60000)
            directory <- ZIO.attemptBlocking(Files.createTempDirectory("cq-session-recovery-"))
            journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), owner.project, owner.actor.session)))(v => ZIO.attemptBlocking(v.close()).orDie)
            tickets <- ZIO.attemptBlocking {
              def ticket(): DispatchTicket = {
                val assigned = Assignment(AssignmentId(uuid), owner.project, Set(member.id), Attribution.Direct, None, None)
                val attempt = governor.copy(id = AttemptId(uuid), assignment = assigned.id, parent = Some(governor.id), role = Role.Worker)
                val request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, List(member), Nil, Nil, None, fence.fence, limits)
                val value = DispatchTicket(request, assigned, attempt, profile, None)
                val child = directory.resolve("children").resolve(attempt.id.value.toString)
                HostFiles.directory(child)
                HostFiles.immutable(child.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, value), 65536)
                value
              }
              val interruptedTicket = directory.resolve("children").resolve(uuid.toString)
              HostFiles.directory(interruptedTicket)
              Files.writeString(interruptedTicket.resolve(".upload-ticket.pending"), "{\"request\":")
              Files.writeString(interruptedTicket.resolve("cancel.txt"), "stop\n")
              Files.writeString(interruptedTicket.resolve(".upload-cancel.pending"), "stop\n")
              val missing = ticket()
              val committed = ticket()
              val queue = new DeliveryQueue(directory.resolve("children").resolve(committed.attempt.id.value.toString).resolve("delivery"))
              queue.enqueue(0, DeliveryBatch(List(HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Assign(committed.assignment))),
                HostDelivery.Usage(HostUsageInput(owner.project, HostUsage.Start(committed.attempt))))))
              val artifact = ArtifactUpload(owner.project, NativeArtifacts.id(committed.attempt.id, "result"), committed.attempt.id, ArtifactKind.Result, "text/plain", "Already committed result")
              val partialEvidence = new DeliveryQueue(directory.resolve("children").resolve(missing.attempt.id.value.toString).resolve("publication-evidence"))
              partialEvidence.commit(List(HostDelivery.Artifact(artifact.copy(attempt = missing.attempt.id, id = NativeArtifacts.id(missing.attempt.id, "result")))))
              queue.commit(List(HostDelivery.Artifact(artifact), HostDelivery.Usage(HostUsageInput(owner.project,
                HostUsage.Finish(AttemptOutcome(RequestId(uuid), committed.attempt.id, AttemptState.Completed, clock.millis(), Nil, None))))))
              val payload = directory.resolve("payload").resolve(governor.id.value.toString)
              HostFiles.directory(payload)
              HostFiles.immutable(directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec,
                SupervisorSettings(directory.toString, "/fixture/guardian", List(profile), limits, Nil, None, None)), 65536)
              Files.writeString(payload.resolve("stdout"), native(100) + "{\"type\":")
              Files.write(payload.resolve("stderr"), Array[Byte](0, -1, 10))
              // Simulate an interrupted collection that never committed its candidate publication.
              val staging = directory.resolve("delivery/staging")
              HostFiles.directory(staging)
              HostFiles.immutable(staging.resolve("000000.json"), HostFiles.encode(DeliveryBatch_JsonCodec,
                DeliveryBatch(List(HostDelivery.Artifact(artifact.copy(attempt = governor.id, id = NativeArtifacts.id(governor.id, "result")))))), 65536)
              (missing, committed)
            }
            (missing, committed) = tickets
            spec = WorkspaceSpec(owner.project, owner.actor.session, governor.id, fixture.source.toString, fixture.base)
            extraSpec = spec.copy(attempt = AttemptId(uuid))
            _ <- fixture.service.prepare(owner, spec)
            _ <- fixture.service.prepare(owner, extraSpec)
            _ <- ZIO.attemptBlocking {
              journal.reserve(spec, "a" * 64, clock.millis())
              journal.reserve(extraSpec, "b" * 64, clock.millis())
            }
            receiver = new Receiver(application, authority, runtime, governor.id)
            delivery = new SessionDelivery(journal, fixture.service, clock)
            first <- delivery.flush(directory, run, receiver).either
            _ <- ZIO.attempt(assert(first.left.exists(_.isInstanceOf[IOException]), first.toString))
            before <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
            _ <- assertIO(before.unattributed.total.known == 131 && before.attempts.unknown == 2 && before.incompleteMeters == 1 && before.attempts.running == 0)
            _ <- ZIO.attemptBlocking {
              Files.writeString(directory.resolve("payload").resolve(governor.id.value.toString).resolve("stdout"), native(900), StandardOpenOption.TRUNCATE_EXISTING)
            }
            count <- delivery.flush(directory, run, receiver)
            _ <- assertIO(count.acknowledged == 1 && count.incompleteTickets.size == 1)
            stable <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
            _ <- assertIO(stable.unattributed.total.known == 131 && stable.attempts.unknown == 2 && stable.attempts.running == 0 && stable.attemptsWithoutMeters == 2)
            repeated <- delivery.flush(directory, run, receiver)
            after <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
            _ <- assertIO(repeated.acknowledged == 0 && repeated.incompleteTickets == count.incompleteTickets && after == stable)
            page <- usage.attempts(owner, UsageFilter.SessionOnly(owner.actor.session), None, None, 10)
            _ <- assertIO(page.entries.size == 3 && page.entries.find(_.attempt.id == missing.attempt.id).get.outcome.get.value.state == AttemptState.Unknown)
            _ <- assertIO(page.entries.find(_.attempt.id == committed.attempt.id).get.outcome.get.value.state == AttemptState.Completed)
            absent <- artifacts.metadata(owner, NativeArtifacts.id(governor.id, "result")).either
            _ <- assertIO(absent.isLeft)
            unsealed <- artifacts.metadata(owner, NativeArtifacts.id(missing.attempt.id, "result")).either
            _ <- assertIO(unsealed.isLeft)
            preserved <- artifacts.metadata(owner, NativeArtifacts.id(committed.attempt.id, "result"))
            _ <- assertIO(preserved.kind == ArtifactKind.Result)
            captured <- artifacts.metadata(owner, NativeArtifacts.id(governor.id, "stdout"))
            _ <- assertIO(captured.kind == ArtifactKind.Transcript)
            quarantined <- fixture.service.get(owner, governor.id)
            extra <- fixture.service.get(owner, extraSpec.attempt)
            _ <- assertIO(quarantined.admission == WorkspaceAdmission.Quarantined && extra.admission == WorkspaceAdmission.Quarantined)
            _ <- ZIO.attemptBlocking {
              assert(journal.records.forall(r => r.phase == JobPhase.Uncertain && r.target == JobTarget.Stop))
              assert(!journal.records.exists(_.workspace.attempt == missing.attempt.id))
              val batch = HostFiles.read(directory.resolve("delivery/final/000000.json"), DeliveryBatch_JsonCodec, 16 * 1024 * 1024)
              val bodies = batch.entries.collect { case HostDelivery.Artifact(v) => v }
              val part = bodies.find(_.id == NativeArtifacts.id(governor.id, "stdout-part-0")).get
              assert(new String(Base64.getDecoder.decode(part.body), java.nio.charset.StandardCharsets.UTF_8) == native(100) + "{\"type\":")
              val stderr = bodies.find(_.id == NativeArtifacts.id(governor.id, "stderr-part-0")).get
              assert(Base64.getDecoder.decode(stderr.body).toList == List[Byte](0, -1, 10))
              assert(!Files.exists(directory.resolve("delivery/staging")))
              assert(Files.readString(count.incompleteTickets.head.resolve(".upload-ticket.pending")) == "{\"request\":")
              Files.writeString(count.incompleteTickets.head.resolve("ticket.json"), "malformed committed ticket")
            }
            malformed <- delivery.flush(directory, run, receiver).either
            _ <- assertIO(malformed.isLeft)
          } yield ()
        }
    }
  }
}

final class SessionDeliveryDummy extends SessionDeliveryTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class SessionDeliveryPostgres extends SessionDeliveryTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
