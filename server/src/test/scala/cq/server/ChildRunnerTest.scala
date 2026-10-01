package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.net.URI
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import zio.{IO, Promise, Runtime, Task, Unsafe, ZIO}

/** Runs the real ChildRunner against an in-process server, a scripted Codex-shaped harness and the native guardian. */
final class ChildRunnerProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, GuardianTestPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource] },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Header = """#!/usr/bin/env python3
import json, sys, time
from pathlib import Path
if sys.argv[1:] == ["--version"]:
    print("codex-cli 0.156.1")
    sys.exit(0)
target = Path(sys.argv[sys.argv.index("--output-last-message") + 1])
data = json.load(sys.stdin)
members = [view["item"]["id"] for view in data["input"]["members"]]
def emit(event):
    print(json.dumps(event), flush=True)
emit({"type": "thread.started", "thread_id": "fixture-thread"})
emit({"type": "turn.started"})
"""
  private val Completing = Header + """Path("tracked.txt").write_text("candidate from worker\n")
Path(".work/evidence").mkdir(parents=True)
Path(".work/evidence/run.log").write_text("first run: FAILED\nsecond run: PASSED\n")
Path(".work/evidence/binary.bin").write_bytes(bytes([0xc3, 0x28, 0, 255]))
Path(".work/evidence/large.log").write_text("x" * 300000)
Path("notes").mkdir()
Path("notes/extra.log").write_text("named evidence\n")
Path("unnamed.log").write_text("not named\n")
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "CandidateReady", "summary": "Implemented",
    "evidence": ["notes/extra.log", "../outside.log", "missing.log"]} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  private val Stalling = Header + """Path("tracked.txt").write_text("partial change\n")
Path("new.txt").write_text("untracked partial file\n")
Path(".work/evidence").mkdir(parents=True)
Path(".work/evidence/partial.log").write_text("still running\n")
sys.stderr.write("worker diagnostic\n")
sys.stderr.flush()
time.sleep(30)
"""

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrowFiberFailure() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = execute(application.ingest(authority, value))
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final case class Fixture(owner: Scope, config: SupervisorConfig, runner: ChildRunner, jobs: JobSupervisor, members: List[ItemRevision], fence: Fence,
    governor: Attempt, profile: HarnessSetting, clock: Clock) {
    def install(script: String): Unit = {
      val executable = Path.of(profile.executable)
      Files.writeString(executable, script)
      Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }
    def request(limits: HostLimits): DispatchRequest =
      DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, members, Nil, Nil, None, fence, limits)
    def dispatch(script: String, limits: HostLimits): Task[DispatchExecution] = for {
      ready <- Promise.make[Throwable, Unit]
      done <- Promise.make[Nothing, Unit]
      entry <- ZIO.attemptBlocking {
        install(script)
        val request = this.request(limits)
        val assignment = Assignment(AssignmentId(uuid), owner.project, members.map(_.id).toSet, Attribution.Direct, None, None)
        val attempt = Attempt(AttemptId(uuid), assignment.id, Some(governor.id), owner.actor.session, Role.Worker, Harness.Codex,
          profile.provider, profile.model, "fixture", clock.millis())
        val ticket = DispatchTicket(request, assignment, attempt, profile, None)
        val entry = new DispatchExecution(ticket, config.directory.resolve("children").resolve(attempt.id.value.toString), ready, done)
        HostFiles.directory(entry.directory)
        entry
      }
    } yield entry
  }

  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], target: Option[String])(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Child runner")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "child-runner-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
    val limits = HostLimits(3000, 30000, 900, 100, 1000, 262144)
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      created <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil))), Nil, "Fixture"))
      claim <- ledger.acquire(owner, ClaimId(uuid), created.items.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis()))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "child-runner-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", "0.156.1", Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, Nil, None, target)
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime), new Receiver(application, auth, root, collectorAuthority, runtime),
        new Receiver(application, auth, root, governorAuthority, runtime), AccessToken("governor", expires), expires)
      workspaces = local.fixture.service
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        workspaces, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      access = new LocalAccess(authority, clock)
      _ <- ZIO.succeed(access.bind(URI.create("http://127.0.0.1:1")))
      runner = new ChildRunner(config, authority, new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, workspaces,
        new McpSchemas, new HarnessOutput, new ChildInstructions, new CandidateWorkspace(config), new WorkspaceReader, access, new OperatorRequirements(""), clock)
      _ <- test(Fixture(owner, config, runner, jobs, created.items, claim.fence, governor, profile, clock))
    } yield ()
  }

  private def text(artifacts: ArtifactService[IO], owner: Scope, id: ArtifactId): Task[String] = artifacts.metadata(owner, id).flatMap { metadata =>
    def loop(offset: Int, body: StringBuilder): Task[String] =
      if (offset >= metadata.codePoints) ZIO.succeed(body.toString)
      else artifacts.page(owner, id, offset, ArtifactService.MaxPageCodePoints).flatMap(page => loop(page.next, body.append(page.text)))
    loop(0, new StringBuilder)
  }

  "Child result collection (Behavioral Active Blackbox; real Git, supervised processes and in-process server Communication)" should {
    "retain the worker's evidence directory and named files as readable result artifacts" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None) { f => for {
        entry <- f.dispatch(Completing, HostLimits(3000, 30000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        status = entry.status
        _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
        stored <- text(artifacts, f.owner, status.result.get)
        result = Wire.decode(ChildResult_JsonCodec, stored)
        retained = result.evidence.files.map(file => file.path -> file).toMap
        _ <- ZIO.attempt(assert(retained.contains(".work/evidence/run.log"), s"Evidence directory was not retained: ${result.evidence}"))
        log <- text(artifacts, f.owner, retained(".work/evidence/run.log").artifact)
        named <- text(artifacts, f.owner, retained("notes/extra.log").artifact)
        large <- artifacts.metadata(f.owner, retained(".work/evidence/large.log").artifact)
        _ <- ZIO.attempt {
          assert(log == "first run: FAILED\nsecond run: PASSED\n" && named == "named evidence\n")
          assert(!retained(".work/evidence/run.log").truncated && retained(".work/evidence/run.log").bytes == log.length)
          assert(retained(".work/evidence/large.log").truncated && retained(".work/evidence/large.log").bytes == 300000 && large.bytes == 256 * 1024)
          assert(large.kind == ArtifactKind.Evidence && large.mediaType == "text/plain" && large.attempt == entry.ticket.attempt.id)
          assert(result.evidence.omitted.toSet == Set(".work/evidence/binary.bin", "../outside.log", "missing.log"), result.evidence.omitted.toString)
          assert(!retained.contains("unnamed.log") && !retained.contains("tracked.txt"))
        }
      } yield () }
    }

    "collect partial work when the worker is killed at its execution deadline or cancelled" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None) { f =>
        def verify(entry: DispatchExecution, phase: DispatchPhase, state: AttemptState): Task[Unit] = for {
          status <- ZIO.succeed(entry.status)
          _ <- ZIO.attempt(assert(status.phase == phase && status.result.isEmpty, status.toString))
          _ <- ZIO.attempt(assert(status.partial.nonEmpty, s"No partial work was attached to the $phase attempt: $status"))
          partial <- text(artifacts, f.owner, status.partial.get).map(Wire.decode(PartialWork_JsonCodec, _))
          gitStatus <- text(artifacts, f.owner, partial.status.get)
          diff <- text(artifacts, f.owner, partial.diff.get)
          stdout <- text(artifacts, f.owner, partial.stdout)
          stderr <- text(artifacts, f.owner, partial.stderr)
          retained = partial.evidence.files.map(file => file.path -> file).toMap
          log <- text(artifacts, f.owner, retained(".work/evidence/partial.log").artifact)
          untracked <- text(artifacts, f.owner, retained("new.txt").artifact)
          _ <- ZIO.attempt {
            assert(partial.attempt == entry.ticket.attempt.id && partial.state == state && !partial.diffTruncated, partial.toString)
            assert(gitStatus.linesIterator.toList.contains(" M tracked.txt") && gitStatus.linesIterator.toList.contains("?? new.txt"), gitStatus)
            assert(diff.contains("-committed\n+partial change\n"), diff)
            assert(stdout.contains("\"thread.started\"") && stderr == "worker diagnostic\n", stdout + stderr)
            assert(log == "still running\n" && untracked == "untracked partial file\n")
          }
        } yield ()
        for {
          killed <- f.dispatch(Stalling, HostLimits(3000, 1500, 900, 100, 1000, 262144))
          _ <- f.runner.run(killed).timeoutFail(new IllegalStateException("Killed worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- verify(killed, DispatchPhase.Failed, AttemptState.Failed)
          cancelled <- f.dispatch(Stalling, HostLimits(3000, 30000, 900, 100, 1000, 262144))
          running <- f.runner.run(cancelled).fork
          _ <- (ZIO.sleep(zio.Duration.fromMillis(50)) *> f.jobs.status(f.config.owner, cancelled.ticket.attempt.id).either)
            .repeatUntil(_.exists(_.phase == JobPhase.Running)).timeoutFail(new IllegalStateException("Worker did not start"))(zio.Duration.fromSeconds(20))
          _ <- ZIO.attemptBlocking {
            val tree = local.workspaces.resolve(cancelled.ticket.attempt.id.value.toString).resolve("tree")
            val deadline = System.nanoTime() + zio.Duration.fromSeconds(10).toNanos
            while (!Files.exists(tree.resolve("new.txt")) && System.nanoTime() < deadline) Thread.sleep(20)
            assert(cancelled.requestStop("Operator cancelled the attempt"))
          }
          _ <- f.jobs.cancel(f.config.owner, cancelled.ticket.attempt.id)
          _ <- running.join.timeoutFail(new IllegalStateException("Cancelled worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- verify(cancelled, DispatchPhase.Cancelled, AttemptState.Cancelled)
        } yield ()
      }
    }

    "D91: refuse to start a child while the integration target checkout has uncommitted tracked changes, then start once it is clean" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Some("refs/heads/integration")) { f =>
        val limits = HostLimits(3000, 30000, 900, 100, 1000, 262144)
        val controller = new DispatchController(f.config, f.runner, f.jobs, new CandidateWorkspace(f.config), f.clock)
        for {
          _ <- ZIO.attemptBlocking {
            local.git(local.source, "branch", "integration", local.base.value)
            f.install(Completing)
            Files.writeString(local.source.resolve("tracked.txt"), "governor edit in the operator checkout\n")
            Files.writeString(local.source.resolve("untracked.log"), "worker evidence\n")
          }
          refused <- controller.start(f.request(limits)).either
          _ <- ZIO.attempt {
            val message = refused match {
              case Left(DomainFailure(Fault.Conflict(message))) => message
              case other => fail(s"Expected a dirty-target conflict, observed $other")
            }
            println(s"Dirty target start refusal: $message")
            assert(message == "Integration target checkout has uncommitted changes: tracked.txt", message)
          }
          _ <- ZIO.attemptBlocking { local.git(local.source, "checkout", "--", "tracked.txt"); () }
          started <- controller.start(f.request(limits))
          settled <- controller.status(started.attempt, 20000).repeatUntil(status => DispatchController.terminal(status.phase))
            .timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt(assert(settled.phase == DispatchPhase.Completed && settled.result.nonEmpty, settled.toString))
        } yield ()
      }
    }
  }
}
