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
import java.time.{Clock, Duration}
import java.util.UUID
import zio.{IO, Promise, Runtime, Semaphore, Task, Unsafe, ZIO}

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
  /** A candidate reviewer that accepts every member and records in `record` the artifacts the host assembled for it. */
  private def reviewing(record: Path): String = Header + s"""Path("$record").write_text(
    json.dumps([{"kind": value["metadata"]["kind"], "body": value["body"]} for value in data["input"]["artifacts"]]))
""" + """target.write_text(json.dumps({"Review": {"members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members], "proposal": None}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** A child that records the payload of the domain credential it was launched with. */
  private val Credentialed = Header + """import base64, os
payload = os.environ["CQ_MCP_CQ_TOKEN"].split(".")[0]
Path(".work/evidence").mkdir(parents=True)
Path(".work/evidence/credential.json").write_bytes(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "Blocked", "summary": "Recorded the credential", "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** A child that is silent, writes one event, and is silent again before it reports. */
  private val Intermittent = Header + """time.sleep(2.5)
emit({"type": "item.completed", "item": {"id": "item_0", "type": "agent_message", "text": "still working"}})
time.sleep(2.5)
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "Blocked", "summary": "Reported after two silences", "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** A healthy child that is silent for longer than any former execution deadline of this suite before it reports. */
  private val Slow = Header + """time.sleep(3)
target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "Blocked", "summary": "Reported after a long silence", "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  private val Partial = Header + """Path("tracked.txt").write_text("partial change\n")
Path("new.txt").write_text("untracked partial file\n")
Path(".work/evidence").mkdir(parents=True)
Path(".work/evidence/partial.log").write_text("still running\n")
sys.stderr.write("worker diagnostic\n")
sys.stderr.flush()
"""
  private val Stalling = Partial + "time.sleep(30)\n"
  private val Failing = Partial + "sys.exit(3)\n"

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrowFiberFailure() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = execute(application.ingest(authority, value))
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private val renewal = new ClaimRenewal(ClaimRenewal.Default, logstage.IzLogger.NullLogger)
  /** `renewing` builds another runner of the same session with its own server authority and claim renewal policy. */
  private final case class Fixture(owner: Scope, collector: Scope, config: SupervisorConfig, authority: SupervisorAuthority, runner: ChildRunner, agents: AgentCatalog,
    jobs: JobSupervisor, members: List[ItemRevision], fence: Fence, governor: Attempt, profile: HarnessSetting, clock: Clock,
    renewing: (SupervisorAuthority, ClaimRenewal.Policy) => ChildRunner) {
    val limits: HostLimits = HostLimits(3000, 900, 100, 1000, 262144)
    /** Runs one child of `controller` to its terminal status. */
    def child(controller: DispatchController, script: String, request: DispatchRequest): Task[DispatchStatus] = for {
      _ <- ZIO.attemptBlocking(install(script))
      started <- controller.start(request)
      settled <- controller.status(started.attempt, 20000).repeatUntil(status => DispatchController.terminal(status.phase))
        .timeoutFail(new IllegalStateException("Child did not finish"))(zio.Duration.fromSeconds(60))
    } yield settled
    def revalidations(controller: DispatchController): ZIO[zio.Scope, Throwable, RevalidationController] = for {
      requests <- Semaphore.make(1)
      admission <- Semaphore.make(1)
      value <- ZIO.acquireRelease(ZIO.succeed(new RevalidationController(config, authority, jobs, controller, renewal, clock, requests, admission)))(_.shutdown.orDie)
    } yield value
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
          profile.provider, profile.model, "fixture", clock.millis(), UsagePhase.Work)
        val ticket = DispatchTicket(request, assignment, attempt, profile, None)
        val entry = new DispatchExecution(ticket, config.directory.resolve("children").resolve(attempt.id.value.toString), ready, done)
        HostFiles.directory(entry.directory)
        entry
      }
    } yield entry
  }

  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], target: Option[String], checks: List[ValidationCheck])(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Child runner")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "child-runner-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
    val limits = HostLimits(3000, 900, 100, 1000, 262144)
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      members <- MilestoneFixture.assigned(ledger, owner, List(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil)))
      claim <- ledger.acquire(owner, ClaimId(uuid), members.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis(), UsagePhase.Govern))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "child-runner-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", "0.156.1", Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, checks, None, target)
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime), new Receiver(application, auth, root, collectorAuthority, runtime),
        new Receiver(application, auth, root, governorAuthority, runtime), AccessToken("governor", expires))
      workspaces = local.fixture.service
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        workspaces, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      access = new LocalAccess
      _ <- ZIO.succeed(access.bind(URI.create("http://127.0.0.1:1")))
      agents = new AgentCatalog(new McpSchemas, new ChildInstructions)
      renewing = (authority: SupervisorAuthority, policy: ClaimRenewal.Policy) => new ChildRunner(config, authority,
        new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, workspaces, agents, new HarnessOutput, new CandidateWorkspace(config),
        new WorkspaceReader, access, new OperatorRequirements(""), new ClaimRenewal(policy, logstage.IzLogger.NullLogger), clock)
      _ <- test(Fixture(owner, collector, config, authority, renewing(authority, ClaimRenewal.Default), agents, jobs, members, claim.fence, governor, profile, clock, renewing))
    } yield ()
  }

  /** A check that counts its runs in `counter` and fails the first `failing` of them. */
  private def counting(name: String, counter: Path, failing: Int, attempts: Int): ValidationCheck = ValidationCheck(name,
    List("sh", "-c", s"n=$$(cat $counter 2>/dev/null || echo 0); echo $$((n + 1)) > $counter; test $$n -ge $failing"), 10000, 65536, attempts, 0)
  private def fault[A](operation: Task[A]): Task[Option[Fault]] = operation.either.map {
    case Left(DomainFailure(value)) => Some(value)
    case Left(error) => throw error
    case Right(_) => None
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
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f => for {
        entry <- f.dispatch(Completing, HostLimits(3000, 900, 100, 1000, 262144))
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
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val bound = 65536
        for {
          entry <- f.dispatch(Verbose, HostLimits(3000, 900, 100, 1000, bound))
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
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f => for {
        entry <- f.dispatch(Recording, HostLimits(3000, 900, 100, 1000, 262144))
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

    "I21: complete a worker that runs past the former execution deadline" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f => for {
        entry <- f.dispatch(Slow, HostLimits(3000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        job <- f.jobs.status(f.config.owner, entry.ticket.attempt.id)
        _ <- ZIO.attempt(assert(entry.status.phase == DispatchPhase.Completed && entry.status.result.nonEmpty &&
          job.exit.exists(exit => exit.reason == StopReason.Exited && exit.code.contains(0)), s"${entry.status} $job"))
      } yield () }
    }

    "keep a child running while the server leaves claim renewals unanswered, and stop it at once when the server refuses one" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val renewals = new java.util.concurrent.atomic.AtomicInteger(0)
        // Once the child runs, the server leaves three renewals unanswered and then recovers.
        val unanswered = new java.util.concurrent.atomic.AtomicInteger(0)
        val interrupted = new ServerApi {
          private val server = f.authority.governor
          override def call(command: Command): Result = command match {
            case Command.ClaimWork(ClaimInput(_, _: ClaimAction.Renew)) if { renewals.incrementAndGet(); unanswered.getAndUpdate(left => math.max(0, left - 1)) > 0 } =>
              throw new ServerUnavailable("HTTP response deadline exceeded", new java.util.concurrent.TimeoutException)
            case _ => server.call(command)
          }
          override def artifact(value: ArtifactUpload): ArtifactMetadata = server.artifact(value)
          override def usage(value: HostUsageInput): HostUsageResult = server.usage(value)
          override def admit(value: HostAdmissionInput): ResultAdmission = server.admit(value)
          override def integrate(value: HostIntegrationInput): IntegrationRecord = server.integrate(value)
          override def grant(value: GrantRequest): AccessToken = server.grant(value)
        }
        val runner = f.renewing(f.authority.copy(governor = interrupted), ClaimRenewal.Policy(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(5)))
        val waiting = Slow.replace("time.sleep(3)", "time.sleep(6)")
        for {
          entry <- f.dispatch(waiting, f.limits)
          completing <- runner.run(entry).fork
          _ <- ZIO.sleep(zio.Duration.fromMillis(100)).repeatUntil(_ => entry.status.phase == DispatchPhase.Running)
            .timeoutFail(new IllegalStateException("Worker did not start"))(zio.Duration.fromSeconds(30))
          before <- ZIO.succeed { unanswered.set(3); renewals.get() }
          _ <- completing.join.timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt {
            println(s"Unanswered renewals: ${entry.status.phase} ${entry.status.blocker} renewals=$before→${renewals.get()} left=${unanswered.get()}")
            assert(entry.status.phase == DispatchPhase.Completed && entry.status.result.nonEmpty && unanswered.get() == 0 && renewals.get() >= before + 4,
              s"${entry.status} after ${renewals.get()} renewals")
          }
          refused <- f.dispatch(waiting, f.limits)
          running <- runner.run(refused).fork
          _ <- ZIO.sleep(zio.Duration.fromMillis(200)).repeatUntil(_ => refused.status.phase == DispatchPhase.Running)
            .timeoutFail(new IllegalStateException("Worker did not start"))(zio.Duration.fromSeconds(30))
          released <- ZIO.succeed(System.nanoTime())
          _ <- ledger.release(f.owner, f.fence)
          _ <- running.join.timeoutFail(new IllegalStateException("Worker was not stopped"))(zio.Duration.fromSeconds(30))
          _ <- ZIO.attempt {
            val elapsed = Duration.ofNanos(System.nanoTime() - released)
            println(s"Refused renewal: ${refused.status.phase} ${refused.status.blocker} after $elapsed")
            assert(refused.status.phase == DispatchPhase.Cancelled && refused.status.blocker.contains("Work claim refresh failed: " + Fault.StaleFence("Claim released")) &&
              elapsed.compareTo(Duration.ofSeconds(5)) < 0, s"${refused.status} after $elapsed")
          }
        } yield ()
      }
    }

    "I21: grant a child its domain credential at its own start for the server's grant lifetime" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f => for {
        entry <- f.dispatch(Credentialed, HostLimits(3000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        _ <- ZIO.attempt(assert(entry.status.phase == DispatchPhase.Completed && entry.status.result.nonEmpty, entry.status.toString))
        result <- text(artifacts, f.owner, entry.status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        credential <- text(artifacts, f.owner, result.evidence.files.find(_.path == ".work/evidence/credential.json").get.artifact).map(Wire.decode(Credential_JsonCodec, _))
        _ <- ZIO.attempt {
          val attempt = entry.ticket.attempt
          val day = Duration.ofHours(24).toMillis
          credential match {
            case Credential.Scoped(grant) =>
              assert(grant.actor == Actor("CQ child " + attempt.id.value, attempt.session, Role.Worker) && grant.project == f.owner.project, grant.toString)
              assert(grant.expiresAt > attempt.startedAt + day - Duration.ofMinutes(15).toMillis && grant.expiresAt <= f.clock.millis() + day,
                s"Child credential expires ${grant.expiresAt - attempt.startedAt} ms after the child started")
            case other => fail(other.toString)
          }
        }
      } yield () }
    }

    "collect partial work when the worker fails or is cancelled" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
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
          failed <- f.dispatch(Failing, HostLimits(3000, 900, 100, 1000, 262144))
          _ <- f.runner.run(failed).timeoutFail(new IllegalStateException("Failed worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- verify(failed, DispatchPhase.Failed, AttemptState.Failed)
          cancelled <- f.dispatch(Stalling, HostLimits(3000, 900, 100, 1000, 262144))
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

    "I19: rerun a failing configured check on the same candidate and record a fail-then-pass as intermittent" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val counter = local.directory.resolve("flaky-" + uuid)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, List(counting("flaky", counter, 1, 2))) { f => for {
        entry <- f.dispatch(Completing, HostLimits(3000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        status = entry.status
        _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
        result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        evidence = result.validation.head
        observations <- ZIO.foreach(evidence.failures :+ evidence.artifact)(id => text(artifacts, f.owner, id).map(Wire.decode(ValidationObservation_JsonCodec, _)))
        workspaces <- ZIO.foreach(observations)(value => local.fixture.service.get(f.owner, value.job.workspace.attempt))
        _ <- ZIO.attemptBlocking {
          println(s"Intermittent check: counts=${status.counts} next=${status.next} evidence=$evidence runs=${Files.readString(counter).trim}")
          assert(status.counts.validationIntermittent == 1 && status.counts.validationFailed == 0 && status.next == ChildNext.Review && status.blocker.isEmpty, status.toString)
          assert(result.validation.map(value => (value.check, value.state, value.failures.size)) == List(("flaky", ValidationState.Passed, 1)), result.validation.toString)
          assert(Files.readString(counter).trim == "2")
          assert(observations.map(value => JobOutcome.observed(value.job).succeeded) == List(false, true))
          assert(observations.forall(value => result.candidate.contains(value.candidate) && value.job.workspace.base == value.candidate))
          assert(observations.map(_.job.workspace.attempt).distinct.size == 2 && workspaces.forall(_.admission == WorkspaceAdmission.Removed))
        }
      } yield () }
    }

    "I19: run a check that keeps failing exactly its configured attempts and a passing check once" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val failing = local.directory.resolve("failing-" + uuid)
      val steady = local.directory.resolve("steady-" + uuid)
      val single = local.directory.resolve("single-" + uuid)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None,
        List(counting("failing", failing, 100, 3), counting("steady", steady, 0, 3), counting("single", single, 100, 1))) { f => for {
        entry <- f.dispatch(Completing, HostLimits(3000, 900, 100, 1000, 262144))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        status = entry.status
        _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
        result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        _ <- ZIO.attemptBlocking {
          println(s"Persistent failure: counts=${status.counts} next=${status.next} validation=${result.validation}")
          assert(result.validation.map(value => (value.check, value.state, value.failures.size)) == List(
            ("failing", ValidationState.Failed, 2), ("steady", ValidationState.Passed, 0), ("single", ValidationState.Failed, 0)), result.validation.toString)
          assert(List(failing, steady, single).map(Files.readString(_).trim) == List("3", "1", "1"))
          assert(result.validation.flatMap(value => value.artifact :: value.failures).distinct.size == 5)
          assert(status.counts.validationFailed == 2 && status.counts.validationIntermittent == 0 && status.next == ChildNext.Revise, status.toString)
        }
      } yield () }
    }

    "I19: revalidate an admitted result whose check failed at admission, on its exact candidate, and let its reviewer inherit the round" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val counter = local.directory.resolve("revalidated-" + uuid)
      val heads = local.directory.resolve("heads-" + uuid)
      val check = ValidationCheck("flaky", List("sh", "-c",
        s"n=$$(cat $counter 2>/dev/null || echo 0); echo $$((n + 1)) > $counter; git rev-parse HEAD >> $heads; test $$n -ge 1"), 10000, 65536, 1, 2)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, List(check)) { f => ZIO.scoped { for {
        controller <- ZIO.succeed(new DispatchController(f.config, f.runner, f.jobs, f.clock))
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        _ <- ZIO.attempt(assert(worked.phase == DispatchPhase.Completed && worked.counts.validationFailed == 1 && worked.next == ChildNext.Revise, worked.toString))
        handle = worked.result.get
        before <- artifacts.metadata(f.owner, handle)
        id = RequestId(uuid)
        status <- revalidations.request(id, handle, f.fence).repeatUntil(_.phase != RevalidationPhase.Running)
          .timeoutFail(new IllegalStateException("Revalidation did not finish"))(zio.Duration.fromSeconds(60))
        replay <- revalidations.request(id, handle, f.fence)
        reused <- fault(revalidations.request(id, handle, Fence(ClaimId(uuid), 1)))
        again <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        after <- artifacts.metadata(f.owner, handle)
        result <- text(artifacts, f.owner, handle).map(Wire.decode(ChildResult_JsonCodec, _))
        metadata <- artifacts.metadata(f.owner, status.amendment.get)
        amendment <- text(artifacts, f.owner, status.amendment.get).map(Wire.decode(ValidationAmendment_JsonCodec, _))
        observation <- text(artifacts, f.owner, amendment.validation.head.artifact).map(Wire.decode(ValidationObservation_JsonCodec, _))
        workspace <- local.fixture.service.get(f.owner, observation.job.workspace.attempt)
        _ <- ZIO.attemptBlocking {
          val candidate = result.candidate.get
          println(s"Revalidation: $status amendment=$amendment heads=${Files.readAllLines(heads)} runs=${Files.readString(counter).trim}")
          assert(status.phase == RevalidationPhase.Completed && status.blocker.isEmpty && replay == status, status.toString)
          assert(status.amendment.contains(IntegrationValidation.amendmentId(handle, 1)) && status.validation == amendment.validation)
          assert(status.validation.map(value => (value.check, value.state)) == List(("flaky", ValidationState.Passed)))
          assert(metadata.kind == ArtifactKind.Amendment && metadata.attempt == f.governor.id && metadata.actor.role == Role.Collector)
          assert(amendment.result == handle && amendment.candidate == candidate && amendment.author == f.governor.id && amendment.round == 1)
          // The check ran once at admission and once in the round, each time in a workspace at the recorded candidate commit.
          assert(observation.candidate == candidate && observation.job.workspace.base == candidate && observation.job.workspace.attempt != result.attempt)
          assert(Files.readAllLines(heads) == java.util.List.of(candidate.value, candidate.value) && Files.readString(counter).trim == "2")
          assert(workspace.admission == WorkspaceAdmission.Removed)
          // The admitted result is the same artifact with the same failed evidence.
          assert(after == before && result.validation.map(_.state) == List(ValidationState.Failed))
          assert(reused.contains(Fault.Conflict("Revalidation request identity changed")), reused.toString)
          assert(again.contains(Fault.Conflict("Result has no failed check to revalidate")), again.toString)
        }
        review = DispatchRequest(RequestId(uuid), DispatchWork.Reviewer(ReviewerMode.Candidate), Harness.Codex, f.members, Nil, Nil, Some(handle), f.fence, f.limits)
        record = local.directory.resolve("review-artifacts-" + uuid)
        reviewed <- f.child(controller, reviewing(record), review)
        _ <- ZIO.attempt(assert(reviewed.phase == DispatchPhase.Completed && reviewed.result.nonEmpty, reviewed.toString))
        verdict <- text(artifacts, f.owner, reviewed.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        delivered <- ZIO.attemptBlocking(io.circe.parser.parse(Files.readString(record)).fold(throw _, identity))
        _ <- ZIO.attempt {
          println(s"Review of the revalidated result: counts=${reviewed.counts} next=${reviewed.next} validation=${verdict.validation} artifacts=$delivered")
          assert(verdict.validation == amendment.validation && reviewed.counts.accepted == 1 && reviewed.counts.validationFailed == 0 &&
            reviewed.next == ChildNext.ConsiderAcceptance, reviewed.toString)
          assert(delivered.asArray.get.map(_.hcursor.get[String]("kind").toOption.get) == Vector("Amendment"))
          assert(delivered.asArray.get.head.hcursor.get[String]("body").toOption.map(Wire.decode(ValidationAmendment_JsonCodec, _)).contains(amendment))
        }
      } yield () } }
    }

    "I19: bound revalidation rounds per check of a result and refuse a foreign fence, a running child, a superseded result, an Unknown check and a released claim" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val counter = local.directory.resolve("always-" + uuid)
      val disabled = local.directory.resolve("disabled-" + uuid)
      def settled(revalidations: RevalidationController, handle: ArtifactId, fence: Fence): Task[RevalidationStatus] = {
        val id = RequestId(uuid)
        revalidations.request(id, handle, fence).repeatUntil(_.phase != RevalidationPhase.Running)
          .timeoutFail(new IllegalStateException("Revalidation did not finish"))(zio.Duration.fromSeconds(60))
      }
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None,
        List(counting("always", counter, 100, 1).copy(revalidations = 2))) { f => ZIO.scoped { for {
        controller <- ZIO.succeed(new DispatchController(f.config, f.runner, f.jobs, f.clock))
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        handle = worked.result.get
        foreign <- fault(revalidations.request(RequestId(uuid), handle, Fence(ClaimId(uuid), 1)))
        missing <- fault(revalidations.request(RequestId(uuid), ArtifactId(uuid), f.fence))
        _ <- ZIO.attemptBlocking(f.install(Stalling))
        running <- controller.start(f.request(f.limits))
        occupied <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        _ <- controller.cancel(running.attempt)
        stopped <- controller.status(running.attempt, 20000).repeatUntil(status => DispatchController.terminal(status.phase))
          .timeoutFail(new IllegalStateException("Cancelled child did not settle"))(zio.Duration.fromSeconds(60))
        first <- settled(revalidations, handle, f.fence)
        second <- settled(revalidations, handle, f.fence)
        third <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        _ <- ZIO.attemptBlocking {
          println(s"Bounded revalidation: first=$first second=$second third=$third runs=${Files.readString(counter).trim}")
          assert(foreign.contains(Fault.StaleFence("Revalidation requires the claim fence its result was admitted under")), foreign.toString)
          assert(missing.exists(_.isInstanceOf[Fault.Missing]), missing.toString)
          assert(occupied.contains(Fault.Conflict("An active child covers T1; poll its status before revalidating")), occupied.toString)
          assert(stopped.phase == DispatchPhase.Cancelled && stopped.result.isEmpty, stopped.toString)
          assert(List(first, second).map(value => (value.phase, value.validation.map(_.state), value.blocker)) ==
            List.fill(2)((RevalidationPhase.Completed, List(ValidationState.Failed), Some("Host check always: Failed"))))
          assert(first.amendment.contains(IntegrationValidation.amendmentId(handle, 1)) && second.amendment.contains(IntegrationValidation.amendmentId(handle, 2)))
          assert(third.contains(Fault.Limit("Revalidation limit reached for check always: 2 rounds")), third.toString)
          // One run at admission and one per round; the refused requests ran nothing.
          assert(Files.readString(counter).trim == "3")
        }
        later <- f.child(controller, Completing, f.request(f.limits))
        superseded <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        current = later.result.get
        candidate <- text(artifacts, f.owner, current).map(Wire.decode(ChildResult_JsonCodec, _).candidate.get)
        _ <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, IntegrationValidation.amendmentId(current, 1), f.governor.id, ArtifactKind.Amendment,
          "application/json", Wire.encode(ValidationAmendment_JsonCodec, ValidationAmendment(current, candidate, f.governor.id, 1,
            List(ValidationEvidence("always", ValidationState.Unknown, ArtifactId(uuid), Nil))))))
        unknown <- fault(revalidations.request(RequestId(uuid), current, f.fence))
        _ <- ledger.release(f.owner, f.fence)
        released <- fault(revalidations.request(RequestId(uuid), current, f.fence))
        _ <- ZIO.attemptBlocking {
          println(s"Refused revalidation: superseded=$superseded unknown=$unknown released=$released runs=${Files.readString(counter).trim}")
          assert(superseded.contains(Fault.Conflict("Result is superseded by a later result for the same members")), superseded.toString)
          assert(unknown.contains(Fault.Conflict("Check always is Unknown; a check whose cleanup is unconfirmed is never rerun")), unknown.toString)
          assert(released.contains(Fault.StaleFence("Claim released")), released.toString)
          assert(Files.readString(counter).trim == "4")
        }
      } yield () } } *> fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None,
        List(counting("always", disabled, 100, 1))) { f => ZIO.scoped { for {
        controller <- ZIO.succeed(new DispatchController(f.config, f.runner, f.jobs, f.clock))
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        refused <- fault(revalidations.request(RequestId(uuid), worked.result.get, f.fence))
        _ <- ZIO.attemptBlocking(assert(refused.contains(Fault.Limit("Revalidation limit reached for check always: 0 rounds")) &&
          Files.readString(disabled).trim == "1", refused.toString))
      } yield () } }
    }
    "I20: end a worker attempt at its native job's settlement and record each check run and each revalidation run as one Check span on the worker's assignment" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val counter = local.directory.resolve("spanned-" + uuid)
      // Each run takes 300 ms; the first two fail.
      val check = ValidationCheck("flaky", List("sh", "-c", s"sleep 0.3; n=$$(cat $counter 2>/dev/null || echo 0); echo $$((n + 1)) > $counter; test $$n -ge 2"), 10000, 65536, 2, 1)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, List(check)) { f => ZIO.scoped { for {
        controller <- ZIO.succeed(new DispatchController(f.config, f.runner, f.jobs, f.clock))
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        _ <- ZIO.attempt(assert(worked.phase == DispatchPhase.Completed && worked.counts.validationFailed == 1, worked.toString))
        handle = worked.result.get
        task = UsageFilter.TaskOnly(f.members.head.id)
        job <- f.jobs.status(f.config.owner, worked.attempt)
        outcomes <- usage.outcomes(f.owner, worked.attempt, 0, 20)
        attempts <- usage.attempts(f.owner, task, None, None, 100)
        admitted <- usage.phases(f.owner, task)
        result <- text(artifacts, f.owner, handle).map(Wire.decode(ChildResult_JsonCodec, _))
        evidence = result.validation.head
        runs <- ZIO.foreach(evidence.failures :+ evidence.artifact)(id => text(artifacts, f.owner, id).map(Wire.decode(ValidationObservation_JsonCodec, _).job))
        _ <- ZIO.attempt {
          val attempt = attempts.entries.map(_.attempt).find(_.id == worked.attempt).get
          val work = admitted.phases.find(_.phase == UsagePhase.Work).get
          val checked = admitted.phases.find(_.phase == UsagePhase.Check)
          println(s"Worker phases: work=${work.wallMillis} ms check=${checked.map(value => (value.spans, value.wallMillis))} settled=${job.updatedAt} " +
            s"finished=${outcomes.entries.map(_.value.finishedAt)} runs=${runs.map(run => (run.createdAt, run.updatedAt))}")
          assert(job.phase == JobPhase.Settled && outcomes.entries.map(_.value.finishedAt) == List(job.updatedAt), outcomes.toString)
          assert(work.attempts == 1 && work.spans == 0 && work.wallMillis == job.updatedAt - attempt.startedAt, work.toString)
          assert(runs.size == 2 && runs.forall(_.createdAt >= job.updatedAt), s"A check began before the worker's attempt ended: $runs")
          assert(checked.exists(value => value.attempts == 0 && value.spans == 2 && value.wallMillis == runs.map(run => run.updatedAt - run.createdAt).sum &&
            value.wallMillis >= 600), checked.toString)
        }
        id = RequestId(uuid)
        status <- revalidations.request(id, handle, f.fence).repeatUntil(_.phase != RevalidationPhase.Running)
          .timeoutFail(new IllegalStateException("Revalidation did not finish"))(zio.Duration.fromSeconds(60))
        _ <- revalidations.request(id, handle, f.fence)
        round <- text(artifacts, f.owner, status.validation.head.artifact).map(Wire.decode(ValidationObservation_JsonCodec, _).job)
        revalidated <- usage.phases(f.owner, task)
        session <- usage.phases(f.owner, UsageFilter.SessionOnly(f.owner.actor.session))
        _ <- ZIO.attemptBlocking {
          val before = admitted.phases.find(_.phase == UsagePhase.Check).get
          val after = revalidated.phases.find(_.phase == UsagePhase.Check).get
          println(s"Revalidated phases: check=${(after.spans, after.wallMillis)} round=${(round.createdAt, round.updatedAt)}")
          assert(status.phase == RevalidationPhase.Completed && status.validation.map(_.state) == List(ValidationState.Passed), status.toString)
          assert(after.spans == 3 && after.wallMillis == before.wallMillis + round.updatedAt - round.createdAt, after.toString)
          assert(session.phases.find(_.phase == UsagePhase.Check).contains(after) && revalidated.phases.find(_.phase == UsagePhase.Work) == admitted.phases.find(_.phase == UsagePhase.Work))
          // The round's span was retained in the session's own queue before it was sent; the worker's check spans travelled with its publication.
          val retained = scala.util.Using.resource(Files.list(f.config.directory.resolve("spans")))(_.toList)
          assert(retained.size == 1 && Files.exists(retained.get(0).resolve("000000.ack")), retained.toString)
        }
      } yield () } }
    }

    "I21: report how long a running child has been silent, reset it when the child writes, and omit it once the child settles" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = new DispatchController(f.config, f.runner, f.jobs, f.clock)
        def observe(attempt: AttemptId, seen: List[DispatchStatus]): Task[List[DispatchStatus]] = controller.status(attempt, 0).flatMap { status =>
          if (DispatchController.terminal(status.phase)) ZIO.succeed((status :: seen).reverse)
          else ZIO.sleep(zio.Duration.fromMillis(100)) *> observe(attempt, status :: seen)
        }
        for {
          _ <- ZIO.attemptBlocking(f.install(Intermittent))
          started <- controller.start(f.request(HostLimits(3000, 900, 100, 1000, 262144)))
          seen <- observe(started.attempt, Nil).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt {
            val running = seen.filter(_.process.contains(JobPhase.Running))
            val quiet = running.map(_.quietMillis)
            assert(seen.last.phase == DispatchPhase.Completed && seen.last.quietMillis.isEmpty, seen.last.toString)
            assert(running.nonEmpty && quiet.forall(_.nonEmpty), s"A running child reported no quiet time: $quiet")
            assert(seen.filterNot(_.process.contains(JobPhase.Running)).forall(_.quietMillis.isEmpty))
            val grown = quiet.flatten.indexWhere(_ >= 1500)
            assert(grown >= 0, s"Quiet time did not grow while the child was silent: $quiet")
            assert(quiet.flatten.drop(grown).exists(_ < 1000), s"Quiet time was not reset by the child's output: $quiet")
          }
        } yield ()
      }
    }

    "D91: start a child while the operator checkout has staged, unstaged and untracked work, which the host preserves" in {
    (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
      artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
    fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Some("refs/heads/integration"), Nil) { f =>
      val limits = HostLimits(3000, 900, 100, 1000, 262144)
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
