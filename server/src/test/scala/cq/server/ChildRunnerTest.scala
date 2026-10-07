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
import io.circe.{Json, parser}
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
  // The version whose provider refusals the host classifies; its native usage format is that of the earlier verified versions.
  private val Version = AbstentionClassifier.captured(Harness.Codex).last
  private val Probe = s"""#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
if sys.argv[1:] == ["--version"]:
    print("codex-cli $Version")
    sys.exit(0)
"""
  private val Header = Probe + """target = Path(sys.argv[sys.argv.index("--output-last-message") + 1])
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
  /** A worker whose provider refuses it for quota after it changed files: the last events and exit status of the retained Codex transcript. */
  private val QuotaMessage = "Quota exceeded. Check your plan and billing details."
  private val Refused = Partial + s"""emit({"type": "error", "message": "$QuotaMessage"})
emit({"type": "turn.failed", "error": {"message": "$QuotaMessage"}})
sys.exit(1)
"""
  /** A worker that ends as `Refused` does, with an error the host has no class for. */
  private val Unclassified = Partial + """emit({"type": "turn.failed", "error": {"message": "unexpected status 400 Bad Request: Invalid schema"}})
sys.exit(1)
"""
  /**
   * A harness whose behaviour is that of the model it is launched with, named `kind-label`: `refuse` is refused for quota, `fail`
   * exits without a report, `slow` runs until it is stopped, `change` requests changes as a reviewer, and any other kind delivers:
   * a candidate as a worker, an accepting review as a reviewer. Each launch leaves `MODEL.started` in `marks`. While `marks/together`
   * lists models, a launch that delivers first waits until all of them have started and records in `MODEL.together` whether they had.
   */
  private def routed(marks: Path): String = Header + s"""marks = Path("$marks")
model = sys.argv[sys.argv.index("--model") + 1]
kind = model.split("-")[0]
(marks / (model + ".started")).write_text("started")
if kind == "refuse":
    emit({"type": "error", "message": "$QuotaMessage"})
    emit({"type": "turn.failed", "error": {"message": "$QuotaMessage"}})
    sys.exit(1)
if kind == "fail":
    sys.exit(3)
if kind == "slow":
    time.sleep(60)
together = marks / "together"
if together.exists():
    deadline = time.time() + 20
    def all_started():
        return all((marks / (name + ".started")).exists() for name in together.read_text().split())
    while not all_started() and time.time() < deadline:
        time.sleep(0.05)
    (marks / (model + ".together")).write_text(str(all_started()))
if "Reviewer" in data["input"]["request"]["work"]:
    verdict = "ChangesRequested" if kind == "change" else "Accepted"
    target.write_text(json.dumps({"Review": {"members": [{"item": item, "verdict": verdict,
        "findings": [] if verdict == "Accepted" else ["Finding of " + model]} for item in members], "proposal": None}}))
else:
    Path("tracked.txt").write_text("candidate from " + model + "\\n")
    target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "CandidateReady", "summary": "Implemented by " + model, "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** A harness that answers the version probe and is gone when the guardian launches it. */
  private val Vanishing = Probe.replace("    sys.exit(0)\n", "    os.remove(sys.argv[0])\n    sys.exit(0)\n")

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
    renewing: (SupervisorAuthority, ClaimRenewal.Policy) => ChildRunner, access: LocalAccess, reservations: java.util.concurrent.atomic.AtomicInteger,
    failingQuarantine: ChildRunner, own: GovernorWork) {
    val limits: HostLimits = HostLimits(3000, 900, 100, 1000, 262144)
    /** The units of this session over `runner`, and the attempts under them. */
    def units(runner: ChildRunner): (DispatchUnits, DispatchController) = {
      val children = new DispatchController(config, runner, own, jobs, clock)
      new DispatchUnits(config, authority, children, own, logstage.IzLogger.NullLogger) -> children
    }
    def units: DispatchUnits = units(runner)._1
    /** The cohort selection of this session over `units`, as its governing session uses it outside a workflow. */
    def cohorts(units: DispatchUnits): CohortController = new CohortController(config, authority,
      new WorkflowExecution(authority.governor, owner.project, owner.actor.session, None), units, new CandidateWorkspace(config), new OperatorRequirements(""), clock, logstage.IzLogger.NullLogger)
    /** A role value of the agent configuration for each role named; the other roles are unassigned. */
    def assign(roles: (String, String)*): Task[Unit] = configure("defaults:\n  roles:\n" + roles.map((role, value) => s"    $role: $value\n").mkString)
    def marks: Path = Files.createDirectory(config.directory.resolve("marks-" + UUID.randomUUID()))
    /** Every Attempt-kind event the host wrote for waiters, in order. */
    def unitEvents: List[SessionUnitEvent] = SessionUnits.read(config.directory).filter {
      case SessionUnitEvent.Started(value) => value.kind == SessionUnitKind.Attempt
      case SessionUnitEvent.Ended(value) => value.unit.kind == SessionUnitKind.Attempt
    }
    def review(worker: ArtifactId): DispatchRequest =
      DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Reviewer(ReviewerMode.Candidate), Harness.Codex, members, Nil, Nil, Some(worker), fence, limits)
    /** Makes `text` the project's agent configuration: the models the next unit starts. */
    def configure(text: String): Task[Unit] = ZIO.attemptBlocking(UnitFixture.configure(authority.root, owner.project, text))
    /** Runs one unit of `controller` to its terminal status. */
    def child(controller: DispatchUnits, script: String, request: DispatchRequest): Task[DispatchStatus] = for {
      _ <- ZIO.attemptBlocking(install(script))
      started <- controller.start(UnitFixture.work(request), None)
      // One status call waits for the child: the longest wait is accepted and ends when the child does, not when the wait has passed.
      settled <- controller.status(started.attempt, 120000).repeatUntil(status => DispatchController.terminal(status.phase))
        .timeoutFail(new IllegalStateException("Child did not finish"))(zio.Duration.fromSeconds(60))
      refused <- controller.status(started.attempt, 120001).either
      _ <- ZIO.attempt(require(refused.left.exists(_.getMessage == "requirement failed: Status wait must be 0–120000 ms"), s"Unbounded status wait: $refused"))
      // What `cq wait` reads: the host wrote that it started on the unit and, once the end was visible to the session, how it ended.
      unit = SessionUnit(SessionUnitKind.Attempt, started.attempt.value, request.members.map(_.id))
      events <- ZIO.attemptBlocking(SessionUnits.read(config.directory).filter {
        case SessionUnitEvent.Started(value) => value.id == unit.id
        case SessionUnitEvent.Ended(value) => value.unit.id == unit.id
      }).repeatUntil(_.size == 2).timeoutFail(new IllegalStateException("The child's end was not written for waiters"))(zio.Duration.fromSeconds(30))
      _ <- ZIO.attempt(require(events == List(SessionUnitEvent.Started(unit),
        SessionUnitEvent.Ended(UnitEnd(unit, settled.phase.toString, Some(settled.next.toString), settled.blocker))), s"Unexpected unit events: $events"))
    } yield settled
    def revalidations(controller: DispatchUnits): ZIO[zio.Scope, Throwable, RevalidationController] = for {
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
    def dispatch(script: String, limits: HostLimits): Task[DispatchExecution] = routed(script, limits, None, Some(profile))
    /** A child with the effort of its route and the settings entry its ticket froze, which a session's settings need not hold. */
    def routed(script: String, limits: HostLimits, effort: Option[Effort], setting: Option[HarnessSetting]): Task[DispatchExecution] = for {
      ready <- Promise.make[Throwable, Unit]
      done <- Promise.make[Nothing, Unit]
      entry <- ZIO.attemptBlocking {
        install(script)
        val request = this.request(limits)
        val assignment = Assignment(AssignmentId(uuid), owner.project, members.map(_.id).toSet, Attribution.Direct, None, None)
        val attempt = Attempt(AttemptId(uuid), assignment.id, Some(governor.id), owner.actor.session, Role.Worker, Harness.Codex,
          profile.provider, profile.model, "fixture", clock.millis(), UsagePhase.Work, effort)
        val ticket = DispatchTicket(request, assignment, attempt, setting, None)
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
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
    val limits = HostLimits(3000, 900, 100, 1000, 262144)
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      members <- MilestoneFixture.assigned(ledger, owner, List(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil)))
      claim <- ledger.acquire(owner, ClaimId(uuid), members.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis(), UsagePhase.Govern, None))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "child-runner-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", Version, Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, checks, None, target)
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime), new Receiver(application, auth, root, collectorAuthority, runtime),
        new Receiver(application, auth, root, governorAuthority, runtime), AccessToken("governor", expires))
      workspaces = local.fixture.service
      // The journal refuses a reservation with an injected `Limit` fault once `reservations` further ones have been made.
      reservations = new java.util.concurrent.atomic.AtomicInteger(Int.MaxValue)
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking {
        val journal = FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)
        new JobRepository {
          override def records: List[JobRecord] = journal.records
          override def reserve(workspace: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean) = {
            if (reservations.getAndDecrement() <= 0) throw DomainFailure(Fault.Limit("Injected job reservation refusal"))
            journal.reserve(workspace, fingerprint, now)
          }
          override def replace(expected: JobRecord, next: JobRecord): Unit = journal.replace(expected, next)
          override def close(): Unit = journal.close()
        }
      }, workspaces, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      access = new LocalAccess
      _ <- ZIO.succeed(access.bind(URI.create("http://127.0.0.1:1")))
      agents = new AgentCatalog(new McpSchemas, new ChildInstructions)
      runner = (authority: SupervisorAuthority, policy: ClaimRenewal.Policy, workspaces: WorkspaceService[IO]) => new ChildRunner(config, authority,
        new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, workspaces, agents, new HarnessOutput, new CandidateWorkspace(config),
        new WorkspaceReader, access, new OperatorRequirements(""), new ClaimRenewal(policy, logstage.IzLogger.NullLogger), clock)
      unquarantinable = new WorkspaceService[IO] {
        override def prepare(scope: Scope, spec: WorkspaceSpec): IO[Throwable, WorkspaceRecord] = workspaces.prepare(scope, spec)
        override def get(scope: Scope, attempt: AttemptId): IO[Throwable, WorkspaceRecord] = workspaces.get(scope, attempt)
        override def quarantine(scope: Scope, attempt: AttemptId, reason: String): IO[Throwable, WorkspaceRecord] = ZIO.fail(new java.io.IOException("Injected quarantine failure"))
        override def remove(scope: Scope, attempt: AttemptId): IO[Throwable, WorkspaceRecord] = workspaces.remove(scope, attempt)
        override def prune(scope: Scope, repository: String): IO[Throwable, Int] = workspaces.prune(scope, repository)
      }
      // Every role of this project runs the settings model of the governing harness.
      _ <- ZIO.attemptBlocking(UnitFixture.starting(authority.root, owner.project, settings))
      _ <- test(Fixture(owner, collector, config, authority, runner(authority, ClaimRenewal.Default, workspaces), agents, jobs, members, claim.fence, governor, profile, clock,
        runner(_, _, workspaces), access, reservations, runner(authority, ClaimRenewal.Default, unquarantinable),
        new GovernorWork(config, authority, jobs, workspaces, new CandidateWorkspace(config), new OperatorRequirements(""), renewal, clock)))
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

    "revoke a child's local capability when the run fails after the child has ended" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f => for {
        entry <- f.dispatch(Failing, f.limits)
        // The runner is issued the capability this call creates.
        token <- ZIO.succeed(f.access.issue(entry.ticket.attempt.id, Role.Worker))
        outcome <- f.failingQuarantine.run(entry).either
        _ <- ZIO.attempt {
          val retained = scala.util.Try(f.access.authenticate(token.value))
          println(s"Capability after a failed run: run=${outcome.left.map(_.getMessage)} capability=$retained")
          assert(outcome.left.exists(_.getMessage == "Injected quarantine failure"), outcome.toString)
          assert(retained.failed.toOption.exists { case DomainFailure(_: Fault.Denied) => true; case _ => false }, s"The capability outlived its attempt: $retained")
        }
      } yield () }
    }

    "publish a worker's result with the check Unknown and a named blocker when the journal refuses the check's job with a Limit fault" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val check = ValidationCheck("unit", List("true"), 10000, 65536, 1, 0)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, List(check)) { f => for {
        entry <- f.dispatch(Completing, f.limits)
        // The child's own job is the last the journal admits.
        _ <- ZIO.succeed(f.reservations.set(1))
        _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Worker did not finish"))(zio.Duration.fromSeconds(60))
        status = entry.status
        _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
        result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
        reason <- text(artifacts, f.owner, result.validation.head.artifact)
        _ <- ZIO.attempt {
          val blocker = "Host check unit was not run: Injected job reservation refusal"
          println(s"Unreserved check: ${status.phase} next=${status.next} blocker=${status.blocker} validation=${result.validation} artifact=$reason")
          assert(result.candidate.nonEmpty && result.validation.map(value => (value.check, value.state, value.failures)) == List(("unit", ValidationState.Unknown, Nil)))
          assert(status.blocker.contains(blocker) && status.next == ChildNext.InspectEvidence && status.counts.validationUnknown == 1 && reason == blocker, status.toString)
        }
      } yield () }
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
        controller <- ZIO.succeed(f.units)
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        _ <- ZIO.attempt(assert(worked.phase == DispatchPhase.Completed && worked.counts.validationFailed == 1 && worked.next == ChildNext.Revise, worked.toString))
        handle = worked.result.get
        before <- artifacts.metadata(f.owner, handle)
        id = RequestId(uuid)
        status <- revalidations.request(id, handle, f.fence).repeatUntil(_.phase != RevalidationPhase.Running)
          .timeoutFail(new IllegalStateException("Revalidation did not finish"))(zio.Duration.fromSeconds(60))
        replay <- revalidations.request(id, handle, f.fence)
        // What `cq wait` reads about the round: its start and how it ended, once each, whatever was asked again.
        rounds <- ZIO.attemptBlocking(SessionUnits.read(f.config.directory).collect {
          case SessionUnitEvent.Started(unit) if unit.id == id.value => (unit.kind, "Started")
          case SessionUnitEvent.Ended(end) if end.unit.id == id.value => (end.unit.kind, end.phase)
        }).repeatUntil(_.size == 2).timeoutFail(new IllegalStateException("The round's end was not written for waiters"))(zio.Duration.fromSeconds(30))
        _ <- ZIO.attempt(require(rounds == List((SessionUnitKind.Revalidation, "Started"), (SessionUnitKind.Revalidation, status.phase.toString)), rounds.toString))
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
        controller <- ZIO.succeed(f.units)
        revalidations <- f.revalidations(controller)
        worked <- f.child(controller, Completing, f.request(f.limits))
        handle = worked.result.get
        foreign <- fault(revalidations.request(RequestId(uuid), handle, Fence(ClaimId(uuid), 1)))
        missing <- fault(revalidations.request(RequestId(uuid), ArtifactId(uuid), f.fence))
        _ <- ZIO.attemptBlocking(f.install(Stalling))
        running <- controller.start(UnitFixture.work(f.request(f.limits)), None)
        occupied <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        _ <- controller.cancel(running.attempt)
        stopped <- controller.status(running.attempt, 120000).repeatUntil(status => DispatchController.terminal(status.phase))
          .timeoutFail(new IllegalStateException("Cancelled child did not settle"))(zio.Duration.fromSeconds(60))
        first <- settled(revalidations, handle, f.fence)
        second <- settled(revalidations, handle, f.fence)
        third <- fault(revalidations.request(RequestId(uuid), handle, f.fence))
        _ <- ZIO.attemptBlocking {
          println(s"Bounded revalidation: first=$first second=$second third=$third runs=${Files.readString(counter).trim}")
          assert(foreign.contains(Fault.StaleFence("Revalidation requires the claim fence its result was admitted under")), foreign.toString)
          assert(missing.exists(_.isInstanceOf[Fault.Missing]), missing.toString)
          assert(occupied.contains(Fault.Conflict("An active child covers T1; wait for it to end before revalidating")), occupied.toString)
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
        controller <- ZIO.succeed(f.units)
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
        controller <- ZIO.succeed(f.units)
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
        val controller = f.units
        def observe(attempt: AttemptId, seen: List[DispatchStatus]): Task[List[DispatchStatus]] = controller.status(attempt, 0).flatMap { status =>
          if (DispatchController.terminal(status.phase)) ZIO.succeed((status :: seen).reverse)
          else ZIO.sleep(zio.Duration.fromMillis(100)) *> observe(attempt, status :: seen)
        }
        for {
          _ <- ZIO.attemptBlocking(f.install(Intermittent))
          started <- controller.start(UnitFixture.work(f.request(HostLimits(3000, 900, 100, 1000, 262144))), None)
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

    "D148: keep the claim of a child whose result still awaits admission when the session ends, so that the replayed admission is accepted" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val unreachable = new java.util.concurrent.atomic.AtomicBoolean(true)
        val delegate = f.authority.collector
        // The server answers everything but the admission of the result, as when it becomes unreachable while the child finishes.
        val collector = new ServerApi {
          override def call(command: Command): Result = delegate.call(command)
          override def artifact(value: ArtifactUpload): ArtifactMetadata = delegate.artifact(value)
          override def usage(value: HostUsageInput): HostUsageResult = delegate.usage(value)
          override def admit(value: HostAdmissionInput): ResultAdmission =
            if (unreachable.get()) throw new ServerUnavailable("HTTP request failed: fixture", null) else delegate.admit(value)
          override def integrate(value: HostIntegrationInput): IntegrationRecord = delegate.integrate(value)
          override def grant(value: GrantRequest): AccessToken = delegate.grant(value)
        }
        val (controller, children) = f.units(f.renewing(f.authority.copy(collector = collector), ClaimRenewal.Default))
        val claims = new SessionClaims(f.owner, f.authority.governor, logstage.IzLogger.NullLogger)
        val members = f.members.map(_.id).toSet
        for {
          // The session holds the claim as its Governor's claim command established it.
          held <- ZIO.attemptBlocking(claims.call(Command.ClaimWork(ClaimInput(f.owner.project, ClaimAction.Renew(f.fence, 300000)))))
          pending <- f.child(controller, Completing, f.request(f.limits))
          _ <- controller.shutdown
          retained = children.undelivered
          _ <- claims.release(retained)
          kept <- ledger.claimPreview(f.owner, members).map(_.claims.map(_.fence))
          _ <- ZIO.succeed(unreachable.set(false))
          receipt <- ZIO.attemptBlocking {
            val directory = f.config.directory.resolve("children").resolve(pending.attempt.value.toString)
            new ChildPublicationDelivery(directory, HostFiles.read(directory.resolve("ticket.json"), DispatchTicket_JsonCodec, 65536)).finish(collector)
          }
          admission <- admissions.get(f.owner, pending.attempt)
          _ <- claims.release(Set.empty)
          after <- ledger.claimPreview(f.owner, members).map(_.claims.map(_.fence))
          _ <- ZIO.attempt {
            println(s"Undelivered result at session end: pending=${pending.phase} retained=$retained kept=$kept decision=${admission.decision} replayed=${receipt.status.phase} after=$after")
            assert(held.isInstanceOf[Result.Claimed], held.toString)
            assert(pending.phase == DispatchPhase.PublicationPending && pending.result.isEmpty && retained == Set(f.fence), pending.toString)
            assert(kept == List(f.fence), kept.toString)
            assert(admission.decision == AdmissionDecision.Accepted() && receipt.status.phase == DispatchPhase.Completed && receipt.status.result.nonEmpty,
              s"${admission.decision} ${receipt.status}")
            assert(after.isEmpty, after.toString)
          }
        } yield ()
      }
    }

    "abstain when the provider refuses a worker for quota, keeping its partial work, and fail on a refusal it has no class for" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val concluded = new java.util.concurrent.atomic.AtomicReference(Option.empty[ChildOutcome])
        val selection = SelectedDispatch(None, ArtifactId(uuid), () => (), { status =>
          val outcome = CohortFailure.outcome(status, Some("input"), CohortFailure.fault(status).map(_ => true))
          concluded.set(Some(outcome))
          outcome
        })
        val controller = f.units
        // The attempt records its own refusal; the unit, whose one candidate it was, names every candidate and reason.
        val refusal = s"Abstained (Quota): $QuotaMessage"
        val blocker = s"No configured model could run this work: codex:fixture-provider/fixture-model Quota ($QuotaMessage)"
        for {
          _ <- ZIO.attemptBlocking(f.install(Refused))
          started <- controller.start(UnitFixture.work(f.request(f.limits)), Some(selection))
          status <- controller.status(started.attempt, 120000)
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          record <- local.fixture.service.get(f.owner, started.attempt)
          partial <- text(artifacts, f.owner, status.partial.get).map(Wire.decode(PartialWork_JsonCodec, _))
          diff <- text(artifacts, f.owner, partial.diff.get)
          ended <- controller.concluded(started.attempt, 20000)
          _ <- ZIO.attempt {
            println(s"Abstained worker: phase=${status.phase} next=${status.next} blocker=${status.blocker} outcome=${ended.map(value => (value.end, value.fault))}")
            assert(status.phase == DispatchPhase.Abstained && status.next == ChildNext.ResolveBlocker && status.blocker.contains(blocker) && status.result.isEmpty &&
              status.usageDelivered && DispatchController.terminal(status.phase) && controller.quiescent, status.toString)
            // Nothing of D145 reads an abstention as a failure to retry: there is no fault to publish, and the outcome says so.
            assert(CohortFailure.fault(status).isEmpty && ended == concluded.get && ended.contains(ChildOutcome(started.attempt, status.members, ChildEnd.Abstained, Some("input"), Some(blocker))))
            cq.core.DriverPolicy.outcome(ended.get)
            val view = attempts.entries.find(_.attempt.id == started.attempt).get
            assert(view.outcome.map(_.value.state).contains(AttemptState.Abstained) && view.outcome.get.value.gaps.headOption.contains(refusal), view.toString)
            assert(partial.state == AttemptState.Abstained && diff.contains("-committed\n+partial change\n"), partial.toString)
            assert(record.admission == WorkspaceAdmission.Quarantined && status.workspace.contains(WorkspaceState(WorkspaceAdmission.Quarantined, Some(record.directory))), record.toString)
            assert(HostFiles.read(f.config.directory.resolve("children").resolve(started.attempt.value.toString).resolve("receipt.json"), DispatchStatus_JsonCodec, 65536).phase == DispatchPhase.Abstained)
          }
          direct <- f.dispatch(Refused, f.limits)
          _ <- f.runner.run(direct).timeoutFail(new IllegalStateException("Refused worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt(assert(direct.abstention.contains(Abstention(AbstentionReason.Quota, QuotaMessage)) && direct.status.phase == DispatchPhase.Abstained, direct.status.toString))
          failed <- f.dispatch(Unclassified, f.limits)
          _ <- f.runner.run(failed).timeoutFail(new IllegalStateException("Failed worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt {
            val status = failed.status
            assert(status.phase == DispatchPhase.Failed && status.next == ChildNext.Retry && failed.abstention.isEmpty && CohortFailure.fault(status).nonEmpty &&
              !status.blocker.exists(_.startsWith("Abstained")), status.toString)
          }
          // A stopped child is judged by how it was stopped, whatever its output says.
          cancelled <- f.dispatch(Refused.replace("sys.exit(1)", "time.sleep(30)"), f.limits)
          running <- f.runner.run(cancelled).fork
          _ <- ZIO.attemptBlocking {
            val stdout = f.config.directory.resolve("payload").resolve(cancelled.ticket.attempt.id.value.toString).resolve("stdout")
            val deadline = System.nanoTime() + zio.Duration.fromSeconds(20).toNanos
            def refused: Boolean = Files.exists(stdout) && Files.readString(stdout).contains("turn.failed")
            while (!refused && System.nanoTime() < deadline) Thread.sleep(20)
            assert(refused, "The child did not write its refusal before the deadline of this wait")
            assert(cancelled.requestStop("Operator cancelled the attempt"))
          }
          _ <- f.jobs.cancel(f.config.owner, cancelled.ticket.attempt.id)
          _ <- running.join.timeoutFail(new IllegalStateException("Cancelled worker did not settle"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt(assert(cancelled.status.phase == DispatchPhase.Cancelled && cancelled.abstention.isEmpty, cancelled.status.toString))
        } yield ()
      }
    }

    "abstain before any launch when the route cannot be launched, and still register the attempt" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        def abstained(name: String, script: String, effort: Option[Effort], setting: Option[HarnessSetting], reason: AbstentionReason, detail: String): Task[Unit] = for {
          entry <- f.routed(script, f.limits, effort, setting)
          _ <- f.runner.run(entry).timeoutFail(new IllegalStateException(s"$name did not settle"))(zio.Duration.fromSeconds(60))
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          workspace <- local.fixture.service.get(f.owner, entry.ticket.attempt.id).either
          _ <- ZIO.attempt {
            val status = entry.status
            println(s"$name: phase=${status.phase} next=${status.next} blocker=${status.blocker}")
            assert(entry.abstention.contains(Abstention(reason, detail)), s"$name: ${entry.abstention} $status")
            assert(status.phase == DispatchPhase.Abstained && status.next == ChildNext.ResolveBlocker && status.blocker.contains(s"Abstained ($reason): $detail") &&
              status.result.isEmpty && status.partial.isEmpty && status.usageDelivered, s"$name: $status")
            val view = attempts.entries.find(_.attempt.id == entry.ticket.attempt.id).getOrElse(throw new IllegalStateException(s"$name: the attempt is not in usage"))
            assert(view.attempt == entry.ticket.attempt && view.outcome.map(_.value.state).contains(AttemptState.Abstained), s"$name: $view")
            reason match {
              // Nothing was prepared for a route refused before the guardian was asked.
              case AbstentionReason.Unconfigured => assert(workspace.left.exists { case DomainFailure(_: Fault.Missing) => true; case _ => false }, s"$name: $workspace")
              case _ => ()
            }
          }
        } yield ()
        for {
          _ <- abstained("No settings entry", Completing, None, None, AbstentionReason.Unconfigured, "The session settings have no entry for Codex")
          _ <- abstained("Unverified version", Completing, None, Some(f.profile.copy(version = "0.0.1")), AbstentionReason.Unconfigured, HarnessProfile.Unverified)
          _ <- abstained("Version mismatch", Completing, None, Some(f.profile.copy(version = "0.159.2")), AbstentionReason.Launch, SupervisorConfig.VersionMismatch)
          _ <- abstained("Provider environment", Completing, None, Some(f.profile.copy(providerEnvironment = Set("ABSENT_PROVIDER_KEY"))), AbstentionReason.Launch,
            "Configured provider environment is unavailable")
          _ <- abstained("Unsupported effort", Completing, Some(Effort.Off), Some(f.profile), AbstentionReason.Launch, HarnessAdapter.EffortUnsupported)
          missing = f.profile.copy(executable = f.profile.executable + "-absent")
          entry <- f.routed(Completing, f.limits, None, Some(missing))
          _ <- f.runner.run(entry).timeoutFail(new IllegalStateException("Missing executable did not settle"))(zio.Duration.fromSeconds(60))
          _ <- ZIO.attempt(assert(entry.abstention.exists(_.reason == AbstentionReason.Launch) && entry.status.phase == DispatchPhase.Abstained, entry.status.toString))
          // The guardian reports a process it could not start; the host reads that as the same abstention.
          vanished <- f.routed(Vanishing, f.limits, None, Some(f.profile))
          _ <- f.runner.run(vanished).timeoutFail(new IllegalStateException("Vanished harness did not settle"))(zio.Duration.fromSeconds(60))
          job <- f.jobs.status(f.config.owner, vanished.ticket.attempt.id)
          _ <- ZIO.attempt {
            println(s"Vanished harness: job=${job.exit.map(_.reason)} problem=${job.problem} status=${vanished.status.phase} blocker=${vanished.status.blocker}")
            assert(job.exit.exists(_.reason == StopReason.LaunchFailed) && vanished.abstention.exists(_.reason == AbstentionReason.Launch) &&
              vanished.status.phase == DispatchPhase.Abstained && vanished.status.next == ChildNext.ResolveBlocker, vanished.status.toString)
          }
        } yield ()
      }
    }

    "start the model the agent configuration assigns to the role, on its harness, provider and effort, and record them on the attempt" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        // `worker` is the role value of the configuration; none keeps the fixture's: the settings model as the standard tier.
        def launched(worker: Option[String]): Task[(Attempt, List[String], Json)] = for {
          _ <- ZIO.foreachDiscard(worker)(value => f.configure(s"defaults:\n  roles:\n    worker: $value\n"))
          request = f.request(f.limits)
          status <- f.child(controller, Recording, request)
          _ <- ZIO.attempt(assert(status.phase == DispatchPhase.Completed && status.result.nonEmpty, status.toString))
          result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
          recorded <- text(artifacts, f.owner, result.evidence.files.find(_.path == ".work/evidence/argv.json").get.artifact)
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          frozen <- ZIO.attemptBlocking(parser.parse(Files.readString(f.config.directory.resolve("units").resolve(request.request.value.toString + ".json"))).fold(throw _, identity))
        } yield (attempts.entries.find(_.attempt.id == status.attempt).get.attempt, parser.parse(recorded).flatMap(_.as[List[String]]).fold(throw _, identity), frozen)
        for {
          entry <- launched(None)
          routed <- launched(Some("codex:route-provider/route-model?effort=xhigh"))
          inherited <- launched(Some("$harness:other-model"))
          // A role no layer assigns starts nothing, and the refusal says what to set.
          _ <- f.configure("defaults:\n  roles:\n    reviewer: codex:other-model\n")
          unassigned <- fault(controller.start(UnitFixture.work(f.request(f.limits)), None))
          usageBefore <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          // A model of a harness the session settings do not hold cannot be launched: its attempt is recorded and abstains.
          _ <- f.configure("defaults:\n  roles:\n    worker: pi:zai/glm\n")
          foreign <- f.child(controller, Recording, f.request(f.limits))
          seats <- controller.seats(foreign.attempt)
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          _ <- ZIO.attempt {
            println(s"Configured routes: settings=${(entry._1.provider, entry._1.model, entry._1.effort)} exact=${(routed._1.provider, routed._1.model, routed._1.effort)} " +
              s"inherited=${(inherited._1.provider, inherited._1.model)} unassigned=$unassigned foreign=${foreign.phase} ${foreign.blocker}")
            // The starting configuration: the settings model, and no effort stated.
            assert((entry._1.harness, entry._1.provider, entry._1.model, entry._1.effort) == (Harness.Codex, "fixture-provider", "fixture-model", None))
            assert(entry._2.containsSlice(List("--model", "fixture-model")) && entry._2.contains("model_provider=\"fixture-provider\"") &&
              !entry._2.exists(_.startsWith("model_reasoning_effort")), entry._2.toString)
            assert((routed._1.provider, routed._1.model, routed._1.effort) == ("route-provider", "route-model", Some(Effort.XHigh)))
            assert(routed._2.containsSlice(List("--model", "route-model")) &&
              routed._2.containsSlice(List("-c", "model_provider=\"route-provider\"", "-c", "model_reasoning_effort=\"xhigh\"")), routed._2.toString)
            // A route that names no provider runs on the provider of the settings entry of its harness, and the frozen plan says so.
            assert((inherited._1.provider, inherited._1.model, inherited._1.effort) == ("fixture-provider", "other-model", None))
            assert(inherited._2.containsSlice(List("--model", "other-model")) && inherited._2.contains("model_provider=\"fixture-provider\""), inherited._2.toString)
            assert(Wire.decode(ResolvedAssignment_JsonCodec, inherited._3.noSpaces) == ResolvedAssignment(Harness.Codex, AgentRole.Worker, RoleResolution.Resolved(
              ResolvedRole(PanelMode.All, 1, List(ResolvedSeat(SeatStrategy.Fallback, List(ModelRoute(Harness.Codex, Some("fixture-provider"), "other-model", None)))),
                RoleOrigin(AgentLayer.Project, RoleSource.DefaultRoles), Nil))), inherited._3.noSpaces)
            assert(unassigned.contains(Fault.Invalid("no model is assigned to the worker role for governing harness codex: set defaults.roles.worker or harnesses.codex.roles.worker " +
              "in the agent configuration (the server's default or this project's); cq agents init --settings FILE writes a starting configuration from a settings file")), unassigned.toString)
            assert(usageBefore.entries.size == 4, s"A refused start registered an attempt: ${usageBefore.entries.map(_.attempt.id)}")
            assert(foreign.phase == DispatchPhase.Abstained && foreign.next == ChildNext.ResolveBlocker && foreign.result.isEmpty &&
              foreign.blocker.contains("No configured model could run this work: pi:zai/glm Unconfigured (The session settings have no entry for Pi)"), foreign.toString)
            assert(seats.seats.map(seat => (seat.end, seat.attempts.map(value => (value.attempt, value.route.harness, value.abstained)))) ==
              List((SeatEnd.Abstained(), List((foreign.attempt, Harness.Pi, Some(AbstentionReason.Unconfigured))))), seats.toString)
            val recorded = attempts.entries.find(_.attempt.id == foreign.attempt).get
            assert((recorded.attempt.harness, recorded.attempt.provider, recorded.attempt.model) == (Harness.Pi, "zai", "glm") &&
              recorded.outcome.map(_.value.state).contains(AttemptState.Abstained), recorded.toString)
          }
        } yield ()
      }
    }

    "I17: go on to a seat's next candidate when one abstains, as one unit with one status, one pair of events and every attempt in usage" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        val request = f.request(f.limits)
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- f.assign("worker" -> "{ fallback: [codex:refuse-a, codex:work-b?effort=low] }")
          status <- f.child(controller, routed(marks), request)
          seats <- controller.seats(status.attempt)
          lineage <- controller.lineage(status.attempt, 0, 0)
          outcomes <- ZIO.foreach(lineage._1)(controller.concluded(_, 20000))
          byCandidate <- controller.status(lineage._1.last, 0)
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          result <- text(artifacts, f.owner, status.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
          panel <- artifacts.metadata(f.owner, DispatchUnits.panel(f.governor.id, request.request))
          published <- text(artifacts, f.owner, panel.id).map(Wire.decode(UnitSeats_JsonCodec, _))
          events <- ZIO.attemptBlocking(f.unitEvents)
          _ <- ZIO.attempt {
            val (first, second) = (lineage._1.head, lineage._1.last)
            println(s"Fallback unit: handle=${status.attempt.value} attempts=${lineage._1.map(_.value)} status=${(status.phase, status.next)} " +
              s"seats=${seats.seats.map(seat => (seat.end, seat.attempts.map(value => (value.route.model, value.abstained))))} outcomes=${outcomes.map(_.map(value => (value.end, value.fault)))} events=${events.size}")
            assert(lineage == (List(first, second), true) && first != second, lineage.toString)
            // One status for the unit: under its first attempt's id, with the result of the candidate that ran; any attempt of the unit reads it.
            assert(status.attempt == first && status.phase == DispatchPhase.Completed && status.next == ChildNext.Review && result.attempt == second && byCandidate == status, status.toString)
            assert(result.request.request == request.request && result.request.harness == Harness.Codex)
            assert(seats == UnitSeats(request.request, PanelMode.All, 1, List(SeatStatus(0, List(
              SeatAttempt(first, ModelRoute(Harness.Codex, Some("fixture-provider"), "refuse-a", None), Some(AbstentionReason.Quota), Some(QuotaMessage)),
              SeatAttempt(second, ModelRoute(Harness.Codex, Some("fixture-provider"), "work-b", Some(Effort.Low)), None, None)),
              SeatEnd.Delivered(status.result.get, ChildNext.Review)))), seats.toString)
            // The same record is retained as an artifact of the governing attempt.
            assert(published == seats && panel.kind == ArtifactKind.Panel && panel.attempt == f.governor.id && panel.mediaType == "application/json", panel.toString)
            // One pair of events for waiters, although two attempts ran.
            val unit = SessionUnit(SessionUnitKind.Attempt, first.value, request.members.map(_.id))
            assert(events == List(SessionUnitEvent.Started(unit), SessionUnitEvent.Ended(UnitEnd(unit, "Completed", Some("Review"), None))), events.toString)
            val recorded = List(first, second).map(id => attempts.entries.find(_.attempt.id == id).get)
            assert(recorded.map(view => (view.attempt.model, view.attempt.effort, view.outcome.map(_.value.state))) ==
              List(("refuse-a", None, Some(AttemptState.Abstained)), ("work-b", Some(Effort.Low), Some(AttemptState.Completed))), recorded.toString)
            assert(outcomes.map(_.map(value => (value.attempt, value.end, value.fault))) ==
              List(Some((first, ChildEnd.Abstained, Some(s"Abstained (Quota): $QuotaMessage"))), Some((second, ChildEnd.Admitted, None))), outcomes.toString)
            assert(Files.exists(marks.resolve("refuse-a.started")) && Files.exists(marks.resolve("work-b.started")) && controller.quiescent)
          }
        } yield ()
      }
    }

    "I17: release the input of a unit no model could run without a fault, offer it again, and apply the retry rule once to a unit whose last candidate failed" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        val cohorts = f.cohorts(controller)
        val roots = f.members.map(_.id).toSet
        def selected: Task[CohortDecision] = cohorts.select(CohortRequest(RequestId(uuid), roots, DispatchWork.Worker(WorkerMode.Implement), Nil, Nil, None, f.limits))
        def run(choice: CohortChoice): Task[DispatchStatus] = cohorts.start(choice.id, f.fence).flatMap(started => controller.status(started.attempt, 120000)
          .repeatUntil(status => DispatchController.terminal(status.phase)).timeoutFail(new IllegalStateException("The unit did not end"))(zio.Duration.fromSeconds(90)))
        def failure(handle: AttemptId): ArtifactId = NativeArtifacts.id(f.governor.id, "failure-" + handle.value)
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- ZIO.attemptBlocking(f.install(routed(marks)))
          // `first` tries its first candidate only.
          _ <- f.assign("worker" -> "{ first: [codex:refuse-a, codex:work-b] }")
          one <- selected
          alone <- run(one.choices.head)
          aloneSeats <- controller.seats(alone.attempt)
          _ <- f.assign("worker" -> "{ fallback: [codex:refuse-a, codex:refuse-b] }")
          two <- selected
          both <- run(two.choices.head)
          bothLineage <- controller.lineage(both.attempt, 0, 0)
          bothOutcomes <- ZIO.foreach(bothLineage._1)(controller.concluded(_, 20000))
          unpublished <- artifacts.metadata(f.owner, failure(both.attempt)).either
          // The second candidate fails: the unit fails with that seat's fault, which is published for the next unit on the input.
          _ <- f.assign("worker" -> "{ fallback: [codex:refuse-a, codex:fail-b] }")
          three <- selected
          failed <- run(three.choices.head)
          failedLineage <- controller.lineage(failed.attempt, 0, 0)
          failedOutcomes <- ZIO.foreach(failedLineage._1)(controller.concluded(_, 20000))
          fault <- text(artifacts, f.owner, failure(failed.attempt))
          four <- selected
          again <- run(four.choices.head)
          againOutcomes <- controller.lineage(again.attempt, 0, 0).flatMap(value => ZIO.foreach(value._1)(controller.concluded(_, 20000)))
          five <- selected
          events <- ZIO.attemptBlocking(f.unitEvents)
          _ <- ZIO.attempt {
            println(s"Abstained and failed units: first=${(alone.phase, alone.blocker)} both=${(both.phase, both.next, both.blocker)} failed=${(failed.phase, failed.next, failed.blocker)} " +
              s"choices=${List(one, two, three, four, five).map(_.choices.map(choice => (choice.reason, choice.artifacts.size)))} " +
              s"outcomes=${failedOutcomes.map(_.map(_.end))} then ${againOutcomes.map(_.map(_.end))}")
            assert(one.choices.map(_.work) == List(DispatchWork.Worker(WorkerMode.Implement)) && one.choices.head.artifacts.isEmpty, one.toString)
            assert(alone.phase == DispatchPhase.Abstained && aloneSeats.seats.map(_.attempts.map(_.route.model)) == List(List("refuse-a")) && !Files.exists(marks.resolve("work-b.started")), alone.toString)
            assert(both.phase == DispatchPhase.Abstained && both.next == ChildNext.ResolveBlocker && both.result.isEmpty && both.attempt == bothLineage._1.head && bothLineage._1.size == 2 &&
              both.blocker.contains(s"No configured model could run this work: codex:fixture-provider/refuse-a Quota ($QuotaMessage); codex:fixture-provider/refuse-b Quota ($QuotaMessage)"), both.toString)
            // Every abstention of the unit states all of them, with the input a drive knows it by.
            assert(bothOutcomes.flatten.map(value => (value.end, value.fault, value.input.nonEmpty)) == List.fill(2)((ChildEnd.Abstained, both.blocker, true)), bothOutcomes.toString)
            bothOutcomes.flatten.foreach(cq.core.DriverPolicy.outcome)
            // No fault was published for a unit that ran no model, and its input is offered again as it was.
            assert(unpublished.left.exists { case DomainFailure(_: Fault.Missing) => true; case _ => false }, unpublished.toString)
            assert(List(two, three).forall(decision => decision.choices.map(choice => (choice.work, choice.members, choice.artifacts)) ==
              List((DispatchWork.Worker(WorkerMode.Implement), f.members, Nil))), s"$two $three")
            // The failed unit: the status of the seat that failed, under the handle of the candidate that abstained before it.
            assert(failed.phase == DispatchPhase.Failed && failed.next == ChildNext.Retry && failed.result.isEmpty && failed.attempt == failedLineage._1.head && failedLineage._1.size == 2, failed.toString)
            assert(fault.contains(failed.attempt.value.toString) && fault.contains(failed.blocker.get), fault)
            assert(failedOutcomes.flatten.map(_.end) == List(ChildEnd.Abstained, ChildEnd.Retryable), failedOutcomes.toString)
            assert(four.choices.map(_.artifacts) == List(List(failure(failed.attempt))), four.toString)
            // The same fault again on the same input: the input stays deferred, and the outcome is the repetition that ends a drive.
            assert(again.phase == DispatchPhase.Failed && again.blocker == failed.blocker && againOutcomes.flatten.map(_.end) == List(ChildEnd.Abstained, ChildEnd.Repeated), again.toString)
            assert(five.choices.isEmpty, five.toString)
            // One pair of events for each of the four units.
            assert(events.size == 8 && events.collect { case SessionUnitEvent.Ended(end) => end.phase } == List("Abstained", "Abstained", "Failed", "Failed"), events.toString)
          }
        } yield ()
      }
    }

    "I17: start the seat of an `rr` strategy with the next candidate in each unit" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- f.assign("worker" -> "{ rr: [codex:work-a, codex:work-b] }")
          units <- ZIO.foreach(List(1, 2, 3))(_ => f.child(controller, routed(marks), f.request(f.limits)))
          seats <- ZIO.foreach(units)(unit => controller.seats(unit.attempt))
          _ <- ZIO.attempt {
            val models = seats.map(_.seats.flatMap(_.attempts.map(_.route.model)))
            val first = Math.floorMod(SeatRotation.offset(f.owner.actor.session), 2L).toInt
            println(s"Round robin over three units: $models, starting at $first")
            assert(units.forall(_.phase == DispatchPhase.Completed) && models == List(first, first + 1, first + 2).map(position => List(List("work-a", "work-b")(position % 2))), models.toString)
          }
        } yield ()
      }
    }

    "I17: run the seats of a reviewer panel side by side on the same members, decide when they agree and ask for arbitration when they do not" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        val cohorts = f.cohorts(controller)
        def reviewers(value: String): Task[Unit] = f.assign("worker" -> "codex:work-a", "reviewer" -> value)
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- reviewers("{ all: [codex:accept-a, codex:accept-b], min: 2 }")
          worked <- f.child(controller, routed(marks), f.request(f.limits))
          // Each reviewer delivers only once both have started: neither waits for the other to end.
          _ <- ZIO.attemptBlocking(Files.writeString(marks.resolve("together"), "accept-a accept-b"))
          first = f.review(worked.result.get)
          agreed <- f.child(controller, routed(marks), first)
          agreedSeats <- controller.seats(agreed.attempt)
          bySecond <- controller.status(agreedSeats.seats(1).attempts.head.attempt, 0)
          accepted <- text(artifacts, f.owner, agreed.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
          admission <- admissions.get(f.owner, accepted.attempt)
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          _ <- ZIO.attemptBlocking {
            val together = List("accept-a", "accept-b").map(name => Files.readString(marks.resolve(name + ".together")))
            println(s"Agreeing panel: ${(agreed.phase, agreed.next, agreed.counts.accepted)} seats=${agreedSeats.seats.map(seat => (seat.seat, seat.end))} together=$together")
            assert(together == List("True", "True"), s"The seats did not run side by side: $together")
            assert(agreed.phase == DispatchPhase.Completed && agreed.next == ChildNext.ConsiderAcceptance && agreed.counts.accepted == 1 && agreed.blocker.isEmpty &&
              agreed.attempt == agreedSeats.seats.head.attempts.head.attempt && bySecond == agreed, agreed.toString)
            val results = agreedSeats.seats.map(_.end).collect { case SeatEnd.Delivered(result, ChildNext.ConsiderAcceptance) => result }
            assert(agreedSeats.mode == PanelMode.All && agreedSeats.min == 2 && results.size == 2 && results.distinct.size == 2 && results.contains(agreed.result.get), agreedSeats.toString)
            // The unit's result is one admitted review of the worker's result that accepts every member: what PrepareIntegration takes.
            assert(accepted.request.previous == worked.result && accepted.request.request == first.request && admission.decision == AdmissionDecision.Accepted() &&
              accepted.report == ChildReport.Review(f.members.map(member => ReviewMember(member.id, ReviewVerdict.Accepted, Nil)), None), accepted.toString)
            val seats = agreedSeats.seats.flatMap(_.attempts.map(_.attempt)).map(id => attempts.entries.find(_.attempt.id == id).get)
            assert(seats.map(view => (view.attempt.role, view.attempt.model, view.outcome.map(_.value.state))) ==
              List((Role.Reviewer, "accept-a", Some(AttemptState.Completed)), (Role.Reviewer, "accept-b", Some(AttemptState.Completed))) &&
              seats.map(_.assignment.id).distinct.size == 2 && seats.forall(_.assignment.members == f.members.map(_.id).toSet), seats.toString)
            Files.delete(marks.resolve("together"))
          }
          _ <- reviewers("{ all: [codex:accept-a, codex:change-b], min: 2 }")
          mixed <- f.child(controller, routed(marks), f.review(worked.result.get))
          mixedSeats <- controller.seats(mixed.attempt)
          dissent <- text(artifacts, f.owner, mixed.result.get).map(Wire.decode(ChildResult_JsonCodec, _))
          // The default answer to a disagreement: one Worker corrects the candidate from the dissenting review.
          correction <- cohorts.select(CohortRequest(RequestId(uuid), f.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement), Nil, Nil, mixed.result, f.limits))
          events <- ZIO.attemptBlocking(f.unitEvents)
          _ <- ZIO.attempt {
            println(s"Disagreeing panel: ${(mixed.phase, mixed.next, mixed.blocker, mixed.counts.accepted, mixed.counts.changesRequested)} " +
              s"seats=${mixedSeats.seats.map(seat => (seat.seat, seat.end))} correction=${correction.choices.map(choice => (choice.work, choice.reason, choice.previous == mixed.result))}")
            assert(mixed.phase == DispatchPhase.Completed && mixed.next == ChildNext.Arbitrate && mixed.blocker.contains("Reviewers disagree on T1: read Seats") &&
              (mixed.counts.accepted, mixed.counts.changesRequested) == (0, 1), mixed.toString)
            assert(dissent.attempt == mixedSeats.seats(1).attempts.head.attempt &&
              dissent.report == ChildReport.Review(f.members.map(member => ReviewMember(member.id, ReviewVerdict.ChangesRequested, List("Finding of change-b"))), None), dissent.toString)
            assert(mixedSeats.seats.map(_.end).collect { case SeatEnd.Delivered(_, next) => next } == List(ChildNext.ConsiderAcceptance, ChildNext.Revise), mixedSeats.toString)
            assert(correction.choices.map(choice => (choice.work, choice.members, choice.previous)) ==
              List((DispatchWork.Worker(WorkerMode.Implement), f.members, mixed.result)), correction.toString)
            // Three units ran five attempts: three pairs of events.
            assert(events.size == 6 && events.collect { case SessionUnitEvent.Ended(end) => end.next } == List(Some("Review"), Some("ConsiderAcceptance"), Some("Arbitrate")), events.toString)
          }
        } yield ()
      }
    }

    "I17: start only the seats an `any` panel needs, tolerate a failed seat the others make up for, and refuse to save a panel whose seats could never fit" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        def reviewers(value: String): Task[Unit] = f.assign("worker" -> "codex:work-a", "reviewer" -> value)
        def count: Task[Int] = usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100).map(_.entries.size)
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- reviewers("{ any: [codex:refuse-a, codex:accept-b, codex:accept-c], min: 1 }")
          worked <- f.child(controller, routed(marks), f.request(f.limits))
          any <- f.child(controller, routed(marks), f.review(worked.result.get))
          anySeats <- controller.seats(any.attempt)
          _ <- reviewers("{ all: [codex:fail-d, codex:accept-e], min: 1 }")
          tolerated <- f.child(controller, routed(marks), f.review(worked.result.get))
          toleratedSeats <- controller.seats(tolerated.attempt)
          outcomes <- controller.lineage(tolerated.attempt, 0, 0).flatMap(value => ZIO.foreach(value._1)(controller.concluded(_, 20000)))
          before <- count
          refused <- fault(reviewers("{ all: [codex:accept-f, codex:accept-g, codex:accept-h, codex:accept-i, codex:accept-j], min: 1 }"))
          after <- count
          events <- ZIO.attemptBlocking(f.unitEvents)
          _ <- ZIO.attemptBlocking {
            println(s"Any panel: ${(any.phase, any.next)} seats=${anySeats.seats.map(seat => (seat.end, seat.attempts.map(_.route.model)))}; " +
              s"tolerated: ${(tolerated.phase, tolerated.next, tolerated.blocker)} outcomes=${outcomes.map(_.map(_.end))}; refused=$refused")
            // The first seat abstained, the second took its place and delivered, and the third was never needed.
            assert(any.phase == DispatchPhase.Completed && any.next == ChildNext.ConsiderAcceptance && any.attempt == anySeats.seats.head.attempts.head.attempt, any.toString)
            assert(anySeats.seats.map(seat => (seat.end.getClass.getSimpleName, seat.attempts.map(value => (value.route.model, value.abstained)))) == List(
              ("Abstained", List(("refuse-a", Some(AbstentionReason.Quota)))), ("Delivered", List(("accept-b", None))), ("Pending", Nil)), anySeats.toString)
            assert(!Files.exists(marks.resolve("accept-c.started")))
            // Q69: the seat that failed is listed with the unit, which the other seat decided.
            assert(tolerated.phase == DispatchPhase.Completed && tolerated.next == ChildNext.ConsiderAcceptance && tolerated.result.nonEmpty &&
              tolerated.blocker.exists(_.startsWith("seat 0 failed and the other seats decided: ")), tolerated.toString)
            assert(toleratedSeats.seats.map(_.end.getClass.getSimpleName) == List("Failed", "Delivered") && outcomes.flatten.map(_.end) == List(ChildEnd.Failed, ChildEnd.Admitted), toleratedSeats.toString)
            // Five seats that start together exceed the session's bound, so no unit of the role could ever start: the configuration is
            // refused when it is saved, and the one before it stays.
            assert(refused.contains(Fault.Invalid(s"Agent configuration has problems: 4:15: defaults.roles.reviewer starts 5 seats together, " +
              s"and a session runs at most ${DispatchController.MaxActiveChildren} children at once")), refused.toString)
            assert(before == after && List("f", "g", "h", "i", "j").forall(label => !Files.exists(marks.resolve(s"accept-$label.started"))) && events.size == 6 && controller.quiescent)
          }
        } yield ()
      }
    }

    "I17: cancel a whole unit by any of its attempts, start nothing after it, and never read the cancellation as an abstention" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        def started(marks: Path, names: String*): Task[Unit] = ZIO.attemptBlocking(names.forall(name => Files.exists(marks.resolve(name + ".started"))))
          .repeatUntil(identity).timeoutFail(new IllegalStateException(s"${names.mkString(", ")} did not start"))(zio.Duration.fromSeconds(60)).unit
        def ended(attempt: AttemptId): Task[DispatchStatus] = controller.status(attempt, 120000).repeatUntil(status => DispatchController.terminal(status.phase))
          .timeoutFail(new IllegalStateException("The cancelled unit did not end"))(zio.Duration.fromSeconds(60))
        for {
          marks <- ZIO.attemptBlocking(f.marks)
          _ <- ZIO.attemptBlocking(f.install(routed(marks)))
          _ <- f.assign("worker" -> "{ fallback: [codex:slow-a, codex:work-b] }", "reviewer" -> "{ all: [codex:slow-c, codex:slow-d], min: 2 }")
          single <- controller.start(UnitFixture.work(f.request(f.limits)), None)
          _ <- started(marks, "slow-a")
          running <- controller.status(single.attempt, 0)
          unsettled = controller.unsettled
          _ <- controller.cancel(single.attempt)
          stopped <- ended(single.attempt)
          stoppedSeats <- controller.seats(single.attempt)
          // A panel is cancelled as a whole by the id of any of its attempts.
          worker <- f.assign("worker" -> "codex:work-b", "reviewer" -> "{ all: [codex:slow-c, codex:slow-d], min: 2 }") *> f.child(controller, routed(marks), f.request(f.limits))
          panel <- controller.start(UnitFixture.work(f.review(worker.result.get)), None)
          _ <- started(marks, "slow-c", "slow-d")
          second <- controller.seats(panel.attempt).map(_.seats(1).attempts.head.attempt)
          _ <- controller.cancel(second)
          cancelled <- ended(second)
          cancelledSeats <- controller.seats(panel.attempt)
          outcomes <- controller.lineage(panel.attempt, 0, 0).flatMap(value => ZIO.foreach(value._1)(controller.concluded(_, 20000)))
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          events <- ZIO.attemptBlocking(f.unitEvents)
          _ <- ZIO.attemptBlocking {
            println(s"Cancelled units: running=${(running.phase, running.process)} unsettled=$unsettled single=${(stopped.phase, stopped.blocker)} seats=${stoppedSeats.seats.map(seat => (seat.end, seat.attempts.size))} " +
              s"panel=${(cancelled.phase, cancelled.attempt == panel.attempt)} seats=${cancelledSeats.seats.map(_.end)} outcomes=${outcomes.map(_.map(_.end))}")
            assert(running.attempt == single.attempt && !DispatchController.terminal(running.phase) && unsettled.size == 1 && unsettled.head.startsWith(s"child attempt ${single.attempt.value} ("), s"$running $unsettled")
            assert(stopped.phase == DispatchPhase.Cancelled && stopped.attempt == single.attempt && stopped.result.isEmpty && stopped.blocker.contains(DispatchUnits.Cancelled), stopped.toString)
            // The next candidate was never started, and the seat is cancelled, not abstained.
            assert(stoppedSeats.seats.map(seat => (seat.end, seat.attempts.map(value => (value.route.model, value.abstained)))) == List((SeatEnd.Cancelled(), List(("slow-a", None)))), stoppedSeats.toString)
            assert(cancelled.phase == DispatchPhase.Cancelled && cancelled.attempt == panel.attempt && cancelled.attempt != second, cancelled.toString)
            assert(cancelledSeats.seats.map(_.end) == List(SeatEnd.Cancelled(), SeatEnd.Cancelled()) && outcomes.flatten.map(_.end) == List(ChildEnd.Cancelled, ChildEnd.Cancelled), cancelledSeats.toString)
            val states = cancelledSeats.seats.flatMap(_.attempts.map(_.attempt)).map(id => attempts.entries.find(_.attempt.id == id).get.outcome.map(_.value.state))
            assert(states == List(Some(AttemptState.Cancelled), Some(AttemptState.Cancelled)), states.toString)
            // work-b ran once, as the worker of the second unit: the cancelled unit did not go on to it.
            assert(attempts.entries.count(_.attempt.model == "work-b") == 1)
            assert(events.collect { case SessionUnitEvent.Ended(end) => end.phase } == List("Cancelled", "Completed", "Cancelled") && events.size == 6 && controller.quiescent, events.toString)
          }
        } yield ()
      }
    }

    "D145: show a selected unit as still publishing until the release of its input has run, and as terminal from then on" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val entered = new java.util.concurrent.CountDownLatch(1)
        val proceed = new java.util.concurrent.CountDownLatch(1)
        val concluding = new java.util.concurrent.atomic.AtomicReference(Option.empty[DispatchStatus])
        // The conclusion of a selected child stands for the release of its input, which publishes the fault over HTTP.
        val selection = SelectedDispatch(None, ArtifactId(uuid), () => (), { status =>
          concluding.set(Some(status))
          entered.countDown()
          proceed.await(60, java.util.concurrent.TimeUnit.SECONDS)
          CohortFailure.outcome(status, None, None)
        })
        val controller = f.units
        (for {
          _ <- ZIO.attemptBlocking(f.install(Failing))
          started <- controller.start(UnitFixture.work(f.request(f.limits)), Some(selection))
          _ <- ZIO.attemptBlocking(assert(entered.await(60, java.util.concurrent.TimeUnit.SECONDS), "The child's conclusion never began"))
          during <- controller.status(started.attempt, 0)
          unsettled = controller.unsettled
          _ <- ZIO.succeed(proceed.countDown())
          after <- controller.status(started.attempt, 120000)
          _ <- ZIO.attempt {
            println(s"Selected child while its input is released: concluding=${concluding.get.map(value => (value.phase, value.next))} during=${during.phase} unsettled=$unsettled after=${(after.phase, after.next)}")
            assert(concluding.get.exists(value => value.phase == DispatchPhase.Failed && value.next == ChildNext.Retry && value.result.isEmpty), concluding.get.toString)
            assert(during.phase == DispatchPhase.Publishing && during.next != ChildNext.Retry && unsettled.size == 1, s"$during $unsettled")
            assert(after == concluding.get.get && controller.quiescent, after.toString)
          }
        } yield ()).ensuring(ZIO.succeed(proceed.countDown()))
      }
    }

    "D108: admit and run a 33rd child of one governing session and attribute its result to its assignment" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, None, Nil) { f =>
        val controller = f.units
        for {
          settled <- ZIO.foreach((1 to 33).toList)(_ => f.child(controller, Recording, f.request(f.limits)))
          last = settled.last
          assignment <- ZIO.attemptBlocking(PhaseSpans.producer(f.config.directory, last.result.get))
          attempts <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
          _ <- ZIO.attempt {
            println(s"Children of one session: ${settled.map(_.phase).groupBy(identity).view.mapValues(_.size).toMap} last=$last")
            assert(settled.forall(status => status.phase == DispatchPhase.Completed && status.result.nonEmpty), settled.map(_.phase).toString)
            assert(settled.map(_.attempt).distinct.size == 33 && controller.quiescent)
            assert(attempts.entries.find(_.attempt.id == last.attempt).exists(_.attempt.assignment == assignment), attempts.toString)
          }
        } yield ()
      }
    }

    "D91: start a child while the operator checkout has staged, unstaged and untracked work, which the host preserves" in {
    (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
      artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
    fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Some("refs/heads/integration"), Nil) { f =>
      val limits = HostLimits(3000, 900, 100, 1000, 262144)
      val controller = f.units
      for {
        _ <- ZIO.attemptBlocking {
          local.git(local.source, "branch", "integration", local.base.value)
          f.install(Completing)
          Files.writeString(local.source.resolve("tracked.txt"), "operator edit in progress\n")
          Files.writeString(local.source.resolve("staged.txt"), "operator staged work\n")
          local.git(local.source, "add", "staged.txt")
          Files.writeString(local.source.resolve("untracked.log"), "operator notes\n")
        }
        started <- controller.start(UnitFixture.work(f.request(limits)), None)
        settled <- controller.status(started.attempt, 120000).repeatUntil(status => DispatchController.terminal(status.phase))
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
