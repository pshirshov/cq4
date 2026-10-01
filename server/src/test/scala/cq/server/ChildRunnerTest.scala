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
  private val Recording = Header + """Path(".work/evidence").mkdir(parents=True)
Path(".work/evidence/argv.json").write_text(json.dumps(sys.argv))
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "Blocked", "summary": "Recorded the launch", "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** A healthy child whose native stream is several times its retained bound before it completes with a valid result. */
  private val Verbose = Header + """for index in range(400):
    emit({"type": "item.completed", "item": {"id": "item_%d" % index, "type": "agent_message", "text": "x" * 1000}})
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "Blocked", "summary": "Reported at length", "evidence": []} for item in members]}}))
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

  private final case class Fixture(owner: Scope, config: SupervisorConfig, runner: ChildRunner, agents: AgentCatalog, jobs: JobSupervisor, members: List[ItemRevision], fence: Fence,
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
      agents = new AgentCatalog(new McpSchemas, new ChildInstructions)
      runner = new ChildRunner(config, authority, new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, workspaces,
        agents, new HarnessOutput, new CandidateWorkspace(config), new WorkspaceReader, access, new OperatorRequirements(""), clock)
      _ <- test(Fixture(owner, config, runner, agents, jobs, created.items, claim.fence, governor, profile, clock))
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
        record <- local.fixture.service.get(f.owner, entry.ticket.attempt.id)
        _ <- ZIO.attempt(assert(record.admission == WorkspaceAdmission.Removed && !Files.exists(Path.of(record.directory)) &&
          status.workspace.contains(WorkspaceState(WorkspaceAdmission.Removed, None)),
          s"The completed attempt's workspace outlived the capture of its candidate and evidence: $record $status"))
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

    "D95: complete a healthy child whose native output exceeds its retained bound and retain a bounded head-and-tail transcript" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None) { f =>
        val bound = 65536
        for {
          entry <- f.dispatch(Verbose, HostLimits(3000, 30000, 900, 100, 1000, bound))
          _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
          status = entry.status
          _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty && status.usageDelivered,
            s"A healthy child was not completed after exceeding its output bound: $status"))
          job <- f.jobs.status(f.config.owner, entry.ticket.attempt.id)
          manifest <- text(artifacts, f.owner, NativeArtifacts.id(entry.ticket.attempt.id, "stdout")).map(Wire.decode(NativeManifest_JsonCodec, _))
          parts <- ZIO.foreach(manifest.parts)(part => text(artifacts, f.owner, part))
          _ <- ZIO.attemptBlocking {
            val exit = job.exit.get
            val stream = f.config.directory.resolve("payload").resolve(entry.ticket.attempt.id.value.toString).resolve("stdout")
            assert(exit.reason == StopReason.Exited && exit.code.contains(0) && exit.stdoutBytes > 4L * bound && Files.size(stream) == exit.stdoutBytes, job.toString)
            val retained = parts.flatMap(part => java.util.Base64.getDecoder.decode(part)).toArray
            assert(manifest.bytes == retained.length && retained.length <= bound, manifest.toString)
            val lines = new String(retained, java.nio.charset.StandardCharsets.UTF_8).linesIterator.map(line => io.circe.parser.parse(line).fold(throw _, identity)).toList
            val kinds = lines.map(_.hcursor.get[String]("type").fold(throw _, identity))
            assert(kinds.head == "thread.started" && kinds.last == "turn.completed" && kinds.count(_ == "cq.truncated") == 1, kinds.distinct.toString)
            val marker = lines(kinds.indexOf("cq.truncated")).hcursor
            val markerBytes = lines(kinds.indexOf("cq.truncated")).noSpaces.length + 1
            assert(marker.get[Long]("totalBytes") == Right(exit.stdoutBytes) &&
              marker.get[Long]("omittedBytes") == Right(exit.stdoutBytes - (retained.length - markerBytes)), marker.focus.toString)
          }
        } yield ()
      }
    }

    "launch the child with the agent catalog's effective prompt, output schema and tool configuration" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None) { f => for {
        entry <- f.dispatch(Recording, HostLimits(3000, 30000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        status = entry.status
        _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
        result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        recorded <- text(artifacts, f.owner, result.evidence.files.find(_.path == ".work/evidence/argv.json").get.artifact)
        prompt <- text(artifacts, f.owner, NativeArtifacts.id(entry.ticket.attempt.id, "prompt"))
        delivered <- text(artifacts, f.owner, NativeArtifacts.id(entry.ticket.attempt.id, "input"))
        _ <- ZIO.attemptBlocking {
          val agent = f.agents.entry(entry.ticket.request.work)
          val view = agent.on(entry.ticket.attempt.harness)
          val arguments = io.circe.parser.parse(recorded).flatMap(_.as[List[String]]).fold(throw _, identity)
          val settings = arguments.sliding(2).collect { case List("-c", value) => value }.toList.map { value =>
            val (key, json) = value.splitAt(value.indexOf('='))
            key -> io.circe.parser.parse(json.drop(1)).fold(throw _, identity)
          }.toMap
          assert(agent.work == DispatchWork.Worker(WorkerMode.Implement) && view.harness == Harness.Codex)
          assert(settings("developer_instructions").asString.contains(view.prompt) && prompt == view.prompt)
          val schema = Path.of(arguments(arguments.indexOf("--output-schema") + 1))
          assert(schema == entry.directory.resolve("assets/result-schema.json"))
          assert(io.circe.parser.parse(Files.readString(schema)) == Right(view.outputSchema))
          McpTarget.values.foreach { target =>
            assert(settings(s"mcp_servers.${target.server}.enabled_tools").as[List[String]] == Right(view.tools.enabledMcp(target)))
          }
          view.tools.builtin.foreach { tool =>
            assert(settings(tool.name) == (if (tool.name == "web_search") io.circe.Json.fromString(if (tool.enabled) "live" else "disabled")
              else io.circe.Json.fromBoolean(tool.enabled)), tool.name)
          }
          assert(io.circe.parser.parse(delivered).fold(throw _, identity).hcursor.downField("input").downField("request").get[io.circe.Json]("work")
            == Right(DispatchWork_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, agent.work)))
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
          record <- local.fixture.service.get(f.owner, entry.ticket.attempt.id)
          _ <- ZIO.attempt(assert(record.admission == WorkspaceAdmission.Quarantined, record.toString))
          _ <- ZIO.attempt(assert(status.workspace.contains(WorkspaceState(WorkspaceAdmission.Quarantined, Some(record.directory))),
            s"Status does not report the quarantined workspace of the $phase attempt: $status"))
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

    "D91: start a child while the operator checkout has staged, unstaged and untracked work, which the host preserves" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Some("refs/heads/integration")) { f =>
        val limits = HostLimits(3000, 30000, 900, 100, 1000, 262144)
        val controller = new DispatchController(f.config, f.runner, f.jobs, f.clock)
        for {
          _ <- ZIO.attemptBlocking {
            local.git(local.source, "branch", "integration", local.base.value)
            f.install(Completing)
            Files.writeString(local.source.resolve("tracked.txt"), "operator edit in progress\n")
            Files.writeString(local.source.resolve("staged.txt"), "operator staged work\n")
            local.git(local.source, "add", "staged.txt")
            Files.writeString(local.source.resolve("untracked.log"), "operator notes\n")
          }
          started <- controller.start(f.request(limits))
          settled <- controller.status(started.attempt, 20000).repeatUntil(status => DispatchController.terminal(status.phase))
            .timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt {
            assert(settled.phase == DispatchPhase.Completed && settled.result.nonEmpty, settled.toString)
            assert(Files.readString(local.source.resolve("tracked.txt")) == "operator edit in progress\n")
            assert(local.git(local.source, "show", ":staged.txt") == "operator staged work")
            assert(Files.readString(local.source.resolve("untracked.log")) == "operator notes\n")
          }
        } yield ()
      }
    }
  }
}
