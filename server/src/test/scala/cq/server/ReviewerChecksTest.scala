package cq.server

import cq.api.*
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.io.IOException
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Clock
import java.util.{HexFormat, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import zio.{Promise, Task, ZIO}

final class ReviewerChecksProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin, WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def uuid: UUID = UUID.randomUUID()
  private final class Receiver(session: SessionId, hook: ArtifactUpload => Unit) extends ServerApi {
    private var values = Map.empty[ArtifactId, ArtifactUpload]
    def uploaded: List[ArtifactUpload] = synchronized(values.values.toList)
    override def artifact(value: ArtifactUpload): ArtifactMetadata = {
      hook(value)
      synchronized {
        require(values.get(value.id).forall(_ == value), "Immutable upload changed")
        values = values.updated(value.id, value)
      }
      val bytes = value.body.getBytes(UTF_8)
      ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length, value.body.codePointCount(0, value.body.length),
        Actor("check collector", session, Role.Collector), 1000)
    }
    override def call(value: Command): Result = throw new AssertionError("Check cannot invoke domain commands")
    override def usage(value: HostUsageInput): HostUsageResult = throw new AssertionError("Check is not a new model attempt")
    override def grant(value: GrantRequest): AccessToken = throw new AssertionError("Check cannot grant authority")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new AssertionError("Check cannot admit a review")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new AssertionError("Check cannot integrate")
  }
  private final case class Fixture(config: SupervisorConfig, entry: DispatchExecution, checks: ReviewerChecks, jobs: JobSupervisor,
    receiver: Receiver, journal: JobRepository, failCancellation: AtomicBoolean)
  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, scripts: List[(String, String)], hook: ArtifactUpload => Unit)
    (test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Reviewer checks")
    val assignment = Assignment(AssignmentId(uuid), project.project, Set.empty, Attribution.Unattributed, None, None)
    val governor = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000)
    val limits = HostLimits(3000, 30000, 900, 100, 1000, 65536)
    val profile = HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty)
    for {
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "reviewer-checks-"))
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits,
        scripts.map { case (name, script) => ValidationCheck(name, List("python3", "-c", script), 10000, 65536) }, None, None)
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      failCancellation = new AtomicBoolean(false)
      journal <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val delegate = FileJobRepository.open(directory.resolve("journal"), project.project, governor.session)
        new JobRepository {
          override def records = delegate.records
          override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long) = delegate.reserve(spec, fingerprint, now)
          override def replace(expected: JobRecord, next: JobRecord): Unit = {
            if (next.target == JobTarget.Stop && failCancellation.get()) throw new IOException("Injected cancellation persistence failure")
            delegate.replace(expected, next)
          }
          override def close(): Unit = delegate.close()
        }
      })(value => ZIO.attemptBlocking(value.close()).orDie)
      jobs <- JobSupervisor.acquire(config.owner, ZIO.succeed(journal), local.fixture.service, new GuardianDriver(guardian.binary), directory.resolve("payload"), Clock.systemUTC())
      ready <- Promise.make[Throwable, Unit]
      done <- Promise.make[Nothing, Unit]
      item = ItemRevision(ItemId(project.project, Ledger.Tasks, 1), Revision(1))
      childAssignment = Assignment(AssignmentId(uuid), project.project, Set(item.id), Attribution.Direct, None, None)
      attempt = governor.copy(id = AttemptId(uuid), assignment = childAssignment.id, parent = Some(governor.id), role = Role.Reviewer)
      request = DispatchRequest(RequestId(uuid), DispatchWork.Reviewer(ReviewerMode.Candidate), Harness.Codex, List(item), Nil, Nil,
        Some(ArtifactId(uuid)), Fence(ClaimId(uuid), 1), limits)
      ticket = DispatchTicket(request, childAssignment, attempt, profile, None)
      entry = new DispatchExecution(ticket, directory.resolve("children").resolve(attempt.id.value.toString), ready, done)
      receiver = new Receiver(governor.session, hook)
      checks = new ReviewerChecks(entry, local.base, config, receiver, jobs)
      _ <- ZIO.attemptBlocking {
        HostFiles.directory(entry.directory)
        HostFiles.immutable(entry.directory.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, ticket), 65536)
        HostFiles.immutable(directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, settings), 65536)
        entry.installChecks(checks)
        entry.active(attempt.id)
      }
      _ <- jobs.start(config.owner, WorkspaceSpec(project.project, governor.session, attempt.id, local.source.toString, local.base),
        JobCommand(List("python3", "-c", "import time; time.sleep(20)"), guardian.environment, "", config.limits))
      _ <- test(Fixture(config, entry, checks, jobs, receiver, journal, failCancellation)).ensuring(ZIO.succeed(failCancellation.set(false)))
    } yield ()
  }
  private def terminal(checks: ReviewerChecks, name: String): Task[DeclaredCheckStatus] =
    checks.request(name, 1000).repeatUntil(value => Set(DeclaredCheckPhase.Completed, DeclaredCheckPhase.Failed, DeclaredCheckPhase.Unknown)(value.phase))
      .timeoutFail(new IllegalStateException("Declared check did not terminate"))(zio.Duration.fromSeconds(8))

  "Reviewer declared checks (Behavioral Active Blackbox; real Git and supervised processes Communication)" should {
    "report uncertain cleanup after cancellation persistence failure instead of abandoning the check owner" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val verified = new AtomicBoolean(false)
      for {
        result <- fixture(local, guardian, List("verify" -> "import time; time.sleep(30)"), _ => ()) { f => for {
        started <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> f.checks.request("verify", 0)).repeatUntil(_.phase == DeclaredCheckPhase.Running)
          .timeoutFail(new IllegalStateException("Check was not registered"))(zio.Duration.fromSeconds(5))
        _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> f.jobs.status(f.config.owner, started.job)).repeatUntil(_.phase == JobPhase.Running)
          .timeoutFail(new IllegalStateException("Check did not run"))(zio.Duration.fromSeconds(5))
        _ <- ZIO.succeed(f.failCancellation.set(true))
        closed <- f.checks.close.either.timeoutFail(new IllegalStateException("Check owner did not finish cleanup"))(zio.Duration.fromSeconds(5))
        _ <- ZIO.attempt(assert(closed.exists(_.uncertain), closed.toString))
        _ <- ZIO.succeed(verified.set(true))
      } yield () }.exit
        _ <- assertIO(verified.get() && result.isFailure)
      } yield ()
    }

    "stop the native reviewer when check publication loses acknowledgement" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      fixture(local, guardian, List("verify" -> "pass"), _ => throw new IOException("Lost check publication acknowledgement")) { f => for {
        unknown <- terminal(f.checks, "verify")
        _ <- assertIO(unknown.phase == DeclaredCheckPhase.Unknown && f.entry.stopReason.nonEmpty)
        native <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> f.jobs.status(f.config.owner, f.entry.ticket.attempt.id)).repeatUntil(_.target == JobTarget.Stop)
          .timeoutFail(new IllegalStateException("Failed check left native reviewer running"))(zio.Duration.fromSeconds(3))
        _ <- assertIO(native.target == JobTarget.Stop)
      } yield () }
    }

    "join duplicates after a waiting caller disconnects and isolate the check workspace" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val gate = local.directory.resolve("release-" + uuid)
      val script = "from pathlib import Path; import time; gate=Path('" + gate + "');\nwhile not gate.exists(): time.sleep(0.01)\nPath('check-only').write_text('check')"
      fixture(local, guardian, List("verify" -> script, "other" -> "pass"), _ => ()) { f => for {
        waiting <- f.checks.request("verify", 20000).fork
        observed <- f.checks.request("verify", 0).repeatUntil(_.phase == DeclaredCheckPhase.Running)
          .timeoutFail(new IllegalStateException("Check did not start"))(zio.Duration.fromSeconds(5))
        _ <- waiting.interrupt
        duplicates <- ZIO.collectAllPar(List.fill(6)(f.checks.request("verify", 0)))
        conflict <- f.checks.request("other", 0).either
        unknown <- f.checks.request("unconfigured", 0).either
        _ <- assertIO(duplicates.forall(_.job == observed.job) && conflict.isLeft && unknown.isLeft && f.entry.ownedJobs.size == 2)
        _ <- ZIO.attemptBlocking(Files.writeString(gate, "release"))
        complete <- terminal(f.checks, "verify")
        _ <- assertIO(complete.phase == DeclaredCheckPhase.Completed && complete.evidence.exists(_.state == ValidationState.Passed))
        closed <- f.checks.close
        late <- f.checks.request("verify", 0).either
        _ <- assertIO(!closed.pending && !closed.uncertain && closed.evidence == complete.evidence.toList && late.isLeft)
        released <- local.fixture.service.get(f.config.owner, observed.job)
        _ <- assertIO(released.admission == WorkspaceAdmission.Removed && !Files.exists(Path.of(released.directory)))
        _ <- ZIO.attemptBlocking {
          assert(f.journal.records.size == 2)
          val observation = f.receiver.uploaded.find(_.kind == ArtifactKind.Validation).get
          val record = io.circe.parser.parse(observation.body).flatMap(ValidationObservation_JsonCodec.decode(baboon.runtime.shared.BaboonCodecContext.Default, _)).toOption.get
          assert(record.candidate == local.base && record.job.workspace.attempt == observed.job && observation.attempt == f.entry.ticket.attempt.id)
          assert(!Files.exists(local.source.resolve("check-only")))
        }
      } yield () }
    }

    "close admission while a process is running and settle it before returning" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      fixture(local, guardian, List("verify" -> "import time; time.sleep(30)"), _ => ()) { f => for {
        started <- f.checks.request("verify", 0).repeatUntil(_.phase == DeclaredCheckPhase.Running)
          .timeoutFail(new IllegalStateException("Check did not start"))(zio.Duration.fromSeconds(5))
        _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> f.jobs.status(f.config.owner, started.job)).repeatUntil(_.phase == JobPhase.Running)
          .timeoutFail(new IllegalStateException("Check did not run"))(zio.Duration.fromSeconds(5))
        closed <- f.checks.close.timeoutFail(new IllegalStateException("Closing failed to settle check"))(zio.Duration.fromSeconds(5))
        record <- f.jobs.status(f.config.owner, started.job)
        late <- f.checks.request("verify", 0).either
        _ <- assertIO(closed.pending && !closed.uncertain && record.phase == JobPhase.Settled && record.target == JobTarget.Stop &&
          closed.evidence.exists(_.state == ValidationState.Failed) && late.isLeft)
      } yield () }
    }

    "freeze pending publication even when upload completes after reviewer exit" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      fixture(local, guardian, List("verify" -> "print('verified')"), _ => {
        entered.countDown()
        require(release.await(10, TimeUnit.SECONDS), "Publication fixture was not released")
      }) { f => (for {
        _ <- f.checks.request("verify", 0)
        _ <- ZIO.attemptBlocking(assert(entered.await(5, TimeUnit.SECONDS)))
        closing <- f.checks.close.fork
        _ <- f.checks.request("verify", 0).either.repeatUntil(_.isLeft)
          .timeoutFail(new IllegalStateException("Check admission did not close"))(zio.Duration.fromSeconds(3))
        _ <- ZIO.succeed(release.countDown())
        closed <- closing.join
        _ <- assertIO(closed.pending && !closed.uncertain && closed.evidence.exists(_.state == ValidationState.Passed))
      } yield ()).ensuring(ZIO.succeed(release.countDown())) }
    }
  }
}
