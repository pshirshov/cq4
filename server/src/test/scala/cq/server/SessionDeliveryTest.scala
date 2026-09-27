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
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Recovery cannot grant authority")
  }

  "Interrupted publication (Behavioral Active Blackbox; dummy Group / PostgreSQL and Git Good Communication)" should {
    "recover pre-job tickets and partial output once while replaying committed results without readmission" in {
      (ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], fixture: WorkspaceFixture) =>
        val clock = Clock.systemUTC()
        val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
        val collector = owner.copy(actor = owner.actor.copy(role = Role.Collector))
        val auth = new Authorization(AccessConfig("recovery-contract-root-token", "http://localhost"), clock)
        val rootAuthority = auth.authenticate("recovery-contract-root-token", Some(owner.actor.session.value.toString))
        val authority = auth.authenticate(auth.grant(rootAuthority, GrantRequest(owner.project, collector.actor, clock.millis() + 60000)).value, None)
        val application = new Application(ledger, ledgerRepository, usage, artifacts, auth)
        val assignment = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None)
        val governor = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Governor,
          Harness.Codex, "fixture", "fixture", "fixture", clock.millis())
        val run = SupervisorRun(ProjectConfig(owner.project, "http://localhost", "Recovery"), assignment, governor, "0.156.1", fixture.source.toString, fixture.base)
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
                val value = DispatchTicket(request, assigned, attempt, profile)
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
              queue.commit(List(HostDelivery.Artifact(artifact), HostDelivery.Usage(HostUsageInput(owner.project,
                HostUsage.Finish(AttemptOutcome(RequestId(uuid), committed.attempt.id, AttemptState.Completed, clock.millis(), Nil, None))))))
              val payload = directory.resolve("payload").resolve(governor.id.value.toString)
              HostFiles.directory(payload)
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
            _ <- assertIO(before.unattributed.total.known == 131 && before.attempts.unknown == 1 && before.incompleteMeters == 1)
            _ <- ZIO.attemptBlocking {
              Files.writeString(directory.resolve("payload").resolve(governor.id.value.toString).resolve("stdout"), native(900), StandardOpenOption.TRUNCATE_EXISTING)
            }
            count <- delivery.flush(directory, run, receiver)
            _ <- assertIO(count.acknowledged >= 3 && count.incompleteTickets.size == 1)
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
