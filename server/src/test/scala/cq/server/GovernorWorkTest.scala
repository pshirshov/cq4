package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.core.DriverRecords.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import io.circe.{Json, parser}
import java.net.URI
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import zio.{IO, Runtime, Semaphore, Task, Unsafe, ZIO}

/**
 * The governing session's own work from the dispatch tool of a real attached host to the integration target: the host's gateway,
 * workflow, units, workspaces, capture, configured checks, publication, integration controller and lineage tracker against an
 * in-process server, a real Git repository and guardian-supervised jobs. The server's release policy is pinned to offer the YOLO mode.
 */
final class GovernorWorkProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, GuardianTestPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef {
      make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource]
      make[DriverInspector]
      make[ProcessModePolicy].fromValue(new ProcessModePolicy(true))
    },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]],
      DIKey[IntegrationService[IO]], DIKey[DriverInspector]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Context = BaboonCodecContext.Default
  private val Target = "refs/heads/integration"
  private val Version = AbstentionClassifier.captured(Harness.Codex).last
  /** A child of the fixture: a Worker writes the file the checks read and reports it ready, a Reviewer accepts every member. */
  private val Child = s"""#!/usr/bin/env python3
import json, sys
from pathlib import Path
if sys.argv[1:] == ["--version"]:
    print("codex-cli $Version")
    sys.exit(0)
target = Path(sys.argv[sys.argv.index("--output-last-message") + 1])
data = json.load(sys.stdin)
if Path(sys.argv[0]).with_name("hold").exists():
    import time
    time.sleep(120)
members = [view["item"]["id"] for view in data["input"]["members"]]
def emit(event):
    print(json.dumps(event), flush=True)
emit({"type": "thread.started", "thread_id": "fixture-thread"})
emit({"type": "turn.started"})
if "Reviewer" in data["input"]["request"]["work"]:
    target.write_text(json.dumps({"Review": {"members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members], "proposal": None}}))
else:
    Path("feature.txt").write_text("good, by a worker\\n")
    target.write_text(json.dumps({"Work": {"members": [{"item": item, "disposition": "CandidateReady", "summary": "Implemented", "evidence": []} for item in members]}}))
emit({"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 0, "cache_write_input_tokens": 0, "output_tokens": 5, "reasoning_output_tokens": 0}})
"""
  /** Passes on a candidate whose `feature.txt` says `good`. */
  private val Good = ValidationCheck("good", List("sh", "-c", "grep -q good feature.txt"), 10000, 65536, 1, 1)
  /** As `Good`, after two seconds: long enough to read how the unit stands while the host checks it. */
  private val Slow = ValidationCheck("slow", List("sh", "-c", "sleep 2; grep -q good feature.txt"), 20000, 65536, 1, 1)

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    // As the HTTP client does, a refusal is thrown as the DomainFailure itself.
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrow() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = execute(application.ingest(authority, value))
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final case class Fixture(local: LocalWorkspaceFixture, owner: Scope, config: SupervisorConfig, authority: SupervisorAuthority, ledger: LedgerService[IO],
    usage: UsageService[IO], registry: DriverInspector, controller: IntegrationController, workflow: AttachedWorkflow, driver: AttachedDriver, units: DispatchUnits,
    control: LocalControl, gateway: Json => Task[Json], task: ItemId, members: List[ItemRevision], fence: Fence, limits: HostLimits,
    others: List[(ItemRevision, Fence)], children: DispatchController) {
    val session: Path = config.directory
    val advance: WorkflowRequest = WorkflowRequest.Advance((task :: others.map(_._1.id)).toSet, WorkflowPhase.Integrate)
    val ready: List[WorkMember] = members.map(member => WorkMember(member.id, WorkDisposition.CandidateReady, "Implemented by the governing session", Nil))
    val accepted: List[ReviewMember] = members.map(member => ReviewMember(member.id, ReviewVerdict.Accepted, Nil))
    def target: GitCommit = GitCommit(local.git(local.source, "show-ref", "--verify", "--hash", Target))

    /** The operator's choice of the project's process mode. */
    def mode(value: ProcessMode, selfReviewWithoutChecks: Boolean): Task[Unit] = {
      val operator = Scope(owner.project, Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
      ledger.mode(operator).flatMap(current => ledger.replaceMode(operator, current.revision, ProjectSetting.Mode(value, selfReviewWithoutChecks))).unit
    }
    def activate: Task[WorkflowActivation] = workflow.activate(RequestId(UUID.randomUUID()), advance, "", None)
    /** A YOLO project with an advance through integration active in this session. */
    def yolo: Task[Unit] = mode(ProcessMode.Yolo, false) *> activate.flatMap(value => ZIO.attempt(assert(value.context.mode == ProcessMode.Yolo, value.context.mode.toString)).unit)

    /** One `dispatch` tool call as the session's harness makes it: the reply the gateway returns, a refusal included. */
    def dispatch(command: DispatchCommand): Task[DispatchReply] = gateway(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1),
      "method" -> Json.fromString("tools/call"), "params" -> Json.obj("name" -> Json.fromString("dispatch"), "arguments" -> DispatchCommand_JsonCodec.encode(Context, command))))
      .map(reply => DispatchReply_JsonCodec.decode(Context, reply.hcursor.downField("result").downField("structuredContent").focus.get).fold(throw _, identity))
    def status(command: DispatchCommand): Task[DispatchStatus] = dispatch(command).flatMap {
      case DispatchReply.Status(value) => ZIO.succeed(value)
      case other => ZIO.fail(new IllegalStateException(s"Expected a status for $command, received $other"))
    }
    def refused(command: DispatchCommand): Task[Fault] = dispatch(command).flatMap {
      case DispatchReply.Failed(fault) => ZIO.succeed(fault)
      case other => ZIO.fail(new IllegalStateException(s"Expected a refusal of $command, received $other"))
    }
    def open(previous: Option[ArtifactId]): Task[DispatchStatus] = status(DispatchCommand.OpenWorkspace(RequestId(UUID.randomUUID()), members, previous, fence))
    def directory(status: DispatchStatus): Path = Path.of(status.workspace.flatMap(_.directory).getOrElse(throw new IllegalStateException(s"No workspace directory in $status")))
    def write(status: DispatchStatus, text: String): Task[Unit] = ZIO.attemptBlocking(Files.writeString(directory(status).resolve("feature.txt"), text)).unit
    def submit(opened: DispatchStatus): Task[DispatchStatus] = status(DispatchCommand.SubmitWorkspace(opened.attempt, ready))
    def ended(attempt: AttemptId): Task[DispatchStatus] = status(DispatchCommand.Status(attempt, 120000)).repeatUntil(value => DispatchController.terminal(value.phase))
      .timeoutFail(new IllegalStateException("The unit did not end"))(zio.Duration.fromSeconds(90))
    /** A candidate the governing session made itself in a workspace of the host, with `text` as the file the checks read. */
    def made(text: String, previous: Option[ArtifactId]): Task[DispatchStatus] = for {
      opened <- open(previous)
      _ <- write(opened, text)
      _ <- submit(opened)
      result <- ended(opened.attempt)
    } yield result
    def selfReview(result: ArtifactId): Task[DispatchReply] = dispatch(DispatchCommand.SelfReview(RequestId(UUID.randomUUID()), result, accepted, fence))
    /** A child of the fixture harness on the session's members, to its end. */
    def child(work: DispatchWork, previous: Option[ArtifactId]): Task[DispatchStatus] = for {
      started <- units.start(AssignedWork(RequestId(UUID.randomUUID()), work, members, Nil, Nil, previous, fence, limits), None)
      result <- units.status(started.attempt, 120000).repeatUntil(value => DispatchController.terminal(value.phase))
        .timeoutFail(new IllegalStateException("The child did not end"))(zio.Duration.fromSeconds(90))
    } yield result
    private def settled(id: IntegrationId): Task[IntegrationStatus] = controller.status(id, 120000)
      .repeatUntil(status => !Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(status.phase))
      .timeoutFail(new IllegalStateException("Integration did not settle"))(zio.Duration.fromSeconds(90))
    def prepared(reviewer: ArtifactId): Task[IntegrationStatus] = {
      val id = IntegrationId(UUID.randomUUID())
      dispatch(DispatchCommand.PrepareIntegration(id, reviewer)) *> settled(id)
    }
    def integrated(reviewer: ArtifactId): Task[IntegrationStatus] = prepared(reviewer).flatMap { ready =>
      if (ready.phase != IntegrationPhase.Ready) ZIO.fail(new IllegalStateException(s"Integration was not prepared: $ready"))
      else dispatch(DispatchCommand.Integrate(ready.id)) *> settled(ready.id)
    }
    def taskContent: Task[Content.Task] = ledger.get(owner, task).map(_.item.draft.content.asInstanceOf[Content.Task])
    def record(attempt: AttemptId): Task[WorkspaceRecord] = local.fixture.service.get(owner, attempt)
    /** The attempts the server holds for the task, oldest first, each with its outcome. */
    def attempts: Task[List[AttemptView]] = usage.attempts(owner, UsageFilter.TaskOnly(task), None, None, 100).map(_.entries.sortBy(_.attempt.startedAt))
    /** What the host wrote for waiters about one unit, in order: its starts and the phases it ended in. */
    def unitEvents(id: UUID): List[String] = SessionUnits.read(session).collect {
      case SessionUnitEvent.Started(unit) if unit.id == id => "Started"
      case SessionUnitEvent.Ended(end) if end.unit.id == id => end.phase
    }

    /** The events of a unit once its end is written, which the host does after the end is visible to the session. */
    def written(id: UUID): Task[List[String]] = ZIO.attemptBlocking(unitEvents(id)).repeatUntil(_.size == 2)
      .timeoutFail(new IllegalStateException(s"The end of unit $id was not written for waiters"))(zio.Duration.fromSeconds(30))

    val key: DriverKey = DriverKey(Harness.Codex, "governor-work-" + UUID.randomUUID())
    private def control(origin: DriverOrigin, action: DriverControl): Task[DriverReply] = ZIO.attemptBlocking {
      authority.root.call(Command.Driver(DriverInput(owner.project, DriverRequest.Control(key, origin, action)))) match {
        case Result.Driver(reply) => reply
        case Result.Failed(fault) => throw DomainFailure(fault)
        case other => throw new IllegalStateException("Unexpected driver result " + other)
      }
    }
    /** The continuation query the Stop hook makes when a turn ends; `waiting` says that the session is woken when its work ends. */
    def continuation(waiting: Boolean): Task[DriverReply] = control(DriverOrigin.Stop, DriverControl.Continue(waiting))
    def parked: Task[Unit] = control(DriverOrigin.UserPromptSubmit, DriverControl.Park()).unit
    /** A drive of the task whose first cycle has started in this session. */
    def driven: Task[Unit] = for {
      started <- control(DriverOrigin.UserPromptSubmit, DriverControl.Start(WorksetTarget.Inline(Set(task), WorkflowPhase.Integrate), None))
      _ <- started match {
        case DriverReply.Started(_, _, Some(token), _) => ZIO.attemptBlocking(driver.session.bind(token)).unit
        case other => ZIO.fail(new IllegalStateException("Expected a binding driver: " + other))
      }
      directive <- continuation(false).flatMap {
        case DriverReply.Continue(value, _, _) => ZIO.succeed(value)
        case other => ZIO.fail(new IllegalStateException("Expected a start directive: " + other))
      }
      _ <- workflow.activate(RequestId(UUID.randomUUID()), advance, "", Some(directive.token))
    } yield ()
    def cycle: Option[CycleRecord] = registry.get(owner.project, key).flatMap(_.cycle)
    def lineage: String = cycle.fold("no cycle")(value => s"cycle ${value.number} ${value.state}: in flight ${value.inFlight.map(entry => DriverPolicy.member(entry.member))}, " +
      s"resting ${value.held.toList.map(DriverPolicy.member)}, settled ${value.lineage.filter(_.settled).map(entry => DriverPolicy.member(entry.member))}, outcomes ${value.outcomes}")
    def eventually(what: String)(holds: => Boolean): Task[Unit] = (ZIO.sleep(zio.Duration.fromMillis(50)) *> ZIO.attempt(holds)).repeatUntil(identity)
      .timeoutFail(new IllegalStateException(s"Not reached: $what; $lineage"))(zio.Duration.fromSeconds(30)).unit
  }

  /** One Ready Task under an Open milestone, claimed by an interactive governing session whose host has an integration target and `checks`. */
  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], registry: DriverInspector, checks: List[ValidationCheck])(test: Fixture => Task[Unit]): Task[Unit] =
    configured(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, checks, ClaimRenewal.Default, 0)(test)

  /** As `fixture`, with the policy by which the host renews the claims of its work and `further` Tasks, each claimed on its own. */
  private def configured(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], registry: DriverInspector, checks: List[ValidationCheck], renewing: ClaimRenewal.Policy, further: Int)(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Governor work")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "governor-work-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val schemas = new McpSchemas()
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(schemas))
    val limits = HostLimits(5000, 900, 100, 1000, 262144)
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      all <- MilestoneFixture.assigned(ledger, owner, List.tabulate(1 + further)(index => ItemDraft(s"Task $index", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil)))
      created = all.take(1)
      claim <- ledger.acquire(owner, ClaimId(uuid), created.map(_.id).toSet, 300000)
      others <- ZIO.foreach(all.drop(1))(member => ledger.acquire(owner, ClaimId(uuid), Set(member.id), 300000).map(member -> _.fence))
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      // The governing attempt of an interactive session, as the attached host registers it.
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "unobserved-interactive-provider", "unobserved-interactive-model", SupervisorConfig.AttachedGovernorCollector, clock.millis(), UsagePhase.Govern, None))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "governor-work-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", Version, Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, checks, None, Some(Target))
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Attached)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime), new Receiver(application, auth, root, collectorAuthority, runtime),
        new Receiver(application, auth, root, governorAuthority, runtime), AccessToken("governor", expires))
      _ <- ZIO.attemptBlocking {
        local.git(local.source, "branch", "integration", local.base.value)
        val executable = Path.of(profile.executable)
        Files.writeString(executable, Child)
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        UnitFixture.starting(authority.root, owner.project, settings)
      }
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        local.fixture.service, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      renewal = new ClaimRenewal(renewing, logstage.IzLogger.NullLogger)
      candidates = new CandidateWorkspace(config)
      admission <- Semaphore.make(1)
      controller <- ZIO.acquireRelease(ZIO.succeed(new IntegrationController(config, authority, jobs, candidates, renewal, clock, admission)))(_.shutdown.orDie)
      access = new LocalAccess
      _ <- ZIO.succeed(access.bind(URI.create("http://127.0.0.1:1")))
      requirements = new OperatorRequirements("")
      runner = new ChildRunner(config, authority, new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, local.fixture.service,
        new AgentCatalog(schemas, new ChildInstructions), new HarnessOutput, candidates, new WorkspaceReader, access, requirements, renewal, clock)
      own = new GovernorWork(config, authority, jobs, local.fixture.service, candidates, requirements, renewal, clock)
      attempts = new DispatchController(config, runner, own, jobs, clock)
      units <- ZIO.acquireRelease(ZIO.succeed(new DispatchUnits(config, authority, attempts, own, logstage.IzLogger.NullLogger)))(_.shutdown.orDie)
      combinations <- ZIO.acquireRelease(ZIO.succeed(new CombinationController(config, authority, candidates, clock)))(_.shutdown.orDie)
      requests <- Semaphore.make(1)
      revalidating <- Semaphore.make(1)
      revalidations <- ZIO.acquireRelease(ZIO.succeed(new RevalidationController(config, authority, jobs, units, renewal, clock, requests, revalidating)))(_.shutdown.orDie)
      driver = new AttachedDriver(config, authority, units, controller, combinations, logstage.IzLogger.NullLogger)
      execution = new WorkflowExecution(authority.governor, owner.project, owner.actor.session, None)
      workflow = new AttachedWorkflow(config, authority, new WorkflowAssets, execution, requirements, units, controller, combinations, revalidations, driver)
      cohorts = new CohortController(config, authority, execution, units, candidates, requirements, clock, logstage.IzLogger.NullLogger)
      control = new LocalControl(units, cohorts, controller, combinations, revalidations, access, schemas, config, execution)
      // The gateway of a Claude Code session: its Context reads no native Codex usage.
      attached = config.copy(run = run.copy(attempt = governor.copy(harness = Harness.Claude)))
      served = new AttachedGateway(attached, authority, schemas, control, workflow, null, null, driver, new SessionClaims(config.owner, authority.governor, logstage.IzLogger.NullLogger),
        WaitCommand(Some("/opt/cq/bin/cq")))
      idle = java.time.Duration.ofMinutes(10)
      peer <- ZIO.acquireRelease(ZIO.attempt(new StdioPeer(new java.io.PipedInputStream(new java.io.PipedOutputStream()), java.io.OutputStream.nullOutputStream(),
        new OwnerLiveness { override def alive: Boolean = true }, PeerLimits(idle, idle, idle, AttachedGateway.FrameBytes, 8), () => ())))(peer => ZIO.succeed(peer.close()))
      gateway = (request: Json) => served.handle(peer, request).map(_.get)
      _ <- gateway(parser.parse("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").fold(throw _, identity))
      _ <- test(Fixture(local, owner, config, authority, ledger, usage, registry, controller, workflow, driver, units, control, gateway, created.head.id, created, claim.fence, limits, others, attempts))
    } yield ()
  }

  private def denied(fault: Fault): String = fault match {
    case Fault.Denied(message) => message
    case other => s"not a denial: $other"
  }
  private def conflict(fault: Fault): String = fault match {
    case Fault.Conflict(message) => message
    case other => s"not a conflict: $other"
  }
  private def evidence(task: Content.Task): List[String] = task.validation.map(_.description)

  "The governing session's own work in the YOLO mode (Behavioral Active Blackbox; real Git, supervised checks and in-process server Communication)" should {
    "I30: open a workspace outside the checkout, capture what the session wrote there, check it, admit its self-review and integrate it as made and reviewed by the governing session" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        opened <- f.open(None)
        tree = f.directory(opened)
        _ <- ZIO.attemptBlocking {
          // The session is told where to work: an absolute directory that is no part of the operator's checkout, at the target head.
          assert(opened.phase == DispatchPhase.Editing && opened.next == ChildNext.Submit && opened.workspace.exists(_.admission == WorkspaceAdmission.Open), opened.toString)
          assert(tree.isAbsolute && Files.isDirectory(tree) && !tree.toRealPath().startsWith(local.source.toRealPath()), tree.toString)
          assert(local.git(tree, "rev-parse", "HEAD") == f.target.value && Files.readString(tree.resolve("tracked.txt")) == "committed\n")
          // Nothing tells a waiter to wait for the session's own editing, and the open workspace keeps the workflow unsettled.
          assert(f.unitEvents(opened.attempt.value).isEmpty && f.units.unsettled == List(s"governor workspace ${opened.attempt.value} (Editing)"), f.units.unsettled.toString)
        }
        // The item is marked as work in progress of the governing session itself for as long as the workspace is open.
        marked <- f.usage.working(f.owner, Map(f.task -> f.owner.actor.session))
        _ <- assertIO(marked.running.get(f.task).exists(work => work.role == Role.Governor && work.harness == Harness.Codex))
        // One work at a time on the same members (D83), whoever does it.
        second <- f.refused(DispatchCommand.OpenWorkspace(RequestId(uuid), f.members, None, f.fence))
        worker <- f.units.start(AssignedWork(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), f.members, Nil, Nil, None, f.fence, f.limits), None).either
        activation <- f.activate.either
        _ <- ZIO.attempt {
          // What covers the members is the caller's own workspace, and only the caller ends it.
          val own = "The governing session's own open workspace already covers T1; submit or cancel it before starting other work on the same members"
          assert(conflict(second) == own, second.toString)
          assert(worker.left.exists { case DomainFailure(Fault.Conflict(message)) => message == own; case _ => false }, worker.toString)
          assert(activation.left.exists(_.getMessage.contains(s"governor workspace ${opened.attempt.value} (Editing)")), activation.toString)
        }
        // A repetition of the call returns how the same unit stands.
        again <- f.status(DispatchCommand.OpenWorkspace(opened.request, f.members, None, f.fence))
        _ <- assertIO(again.attempt == opened.attempt && again.phase == DispatchPhase.Editing && again.workspace == opened.workspace)
        _ <- f.write(opened, "good, by the governing session\n")
        submitted <- f.submit(opened)
        _ <- ZIO.attempt {
          assert(submitted.attempt == opened.attempt && submitted.next == ChildNext.Wait && !DispatchController.terminal(submitted.phase) && submitted.phase != DispatchPhase.Editing, submitted.toString)
          // From its submission the host works on the unit, and says so to whoever waits.
          assert(f.unitEvents(opened.attempt.value).headOption.contains("Started"), f.unitEvents(opened.attempt.value).toString)
        }
        made <- f.ended(opened.attempt)
        candidate = GitCommit(local.git(local.source, "rev-parse", "refs/cq/candidates/" + opened.attempt.value))
        workspace <- f.record(opened.attempt)
        events <- f.written(opened.attempt.value)
        _ <- ZIO.attemptBlocking {
          assert(made.phase == DispatchPhase.Completed && made.result.nonEmpty && made.next == ChildNext.Review && made.counts.ready == 1 && made.counts.validationFailed == 0, made.toString)
          assert(local.git(local.source, "show", candidate.value + ":feature.txt") == "good, by the governing session" &&
            local.git(local.source, "rev-parse", candidate.value + "^") == local.base.value)
          // The candidate is a commit of the host, so the workspace is released as a Worker's is.
          assert(workspace.admission == WorkspaceAdmission.Removed && !Files.exists(tree) && made.workspace.contains(WorkspaceState(WorkspaceAdmission.Removed, None)), workspace.toString)
          assert(events == List("Started", "Completed") && f.units.unsettled.isEmpty, events.toString)
          assert(Files.readString(local.source.resolve("tracked.txt")) == "committed\n" && !Files.exists(local.source.resolve("feature.txt")))
        }
        unmarked <- f.usage.working(f.owner, Map(f.task -> f.owner.actor.session))
        _ <- assertIO(unmarked.running.isEmpty)
        reviewed <- f.selfReview(made.result.get)
        review = reviewed match { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        _ <- ZIO.attempt {
          // The reply is what the result of a reviewer child would be, with the handle the integration takes.
          assert(review.phase == DispatchPhase.Completed && review.result.nonEmpty && review.next == ChildNext.ConsiderAcceptance && review.counts.accepted == 1, review.toString)
          assert(review.attempt != opened.attempt && f.unitEvents(review.attempt.value).isEmpty)
        }
        recorded <- f.integrated(review.result.get)
        task <- f.taskContent
        attempts <- f.attempts
        _ <- ZIO.attemptBlocking {
          assert(recorded.phase == IntegrationPhase.Recorded && f.target == candidate && task.status == TaskStatus.Done, s"$recorded ${f.target}")
          assert(evidence(task).exists(_.endsWith(s"into $Target; candidate made and reviewed by the governing session (self-review, YOLO mode)")), evidence(task).toString)
          // Both attempts are the governing session's own, in the phases of the work they did, ended and without a meter of their own.
          val own = attempts.filter(_.attempt.role == Role.Governor)
          assert(own.map(view => (view.attempt.id, view.attempt.phase)) == List(opened.attempt -> UsagePhase.Work, review.attempt -> UsagePhase.Review), own.toString)
          assert(own.forall(view => view.attempt.parent.contains(f.config.run.attempt.id) && view.attempt.harness == Harness.Codex &&
            view.attempt.model == "unobserved-interactive-model" && view.attempt.collector == DispatchController.OwnWorkCollector &&
            view.outcome.exists(outcome => outcome.value.state == AttemptState.Completed && outcome.value.gaps == List(AttemptSettlement.UnmeteredGap))), own.toString)
        }
      } yield () }
    }

    "I30 D83: refuse a child on the members of an open workspace before the unit draws its turn from a round-robin seat" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f =>
        val turns = List("turn-a", "turn-b")
        val worker = AssignedWork(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), f.members, Nil, Nil, None, f.fence, f.limits)
        for {
          _ <- f.yolo
          _ <- ZIO.attemptBlocking(UnitFixture.configure(f.authority.root, f.owner.project, s"defaults: { roles: { worker: { rr: [${turns.map("codex:" + _).mkString(", ")}] } } }\n"))
          opened <- f.open(None)
          refused <- f.units.start(worker, None).either
          _ <- ZIO.attempt(assert(refused.left.exists { case DomainFailure(Fault.Conflict(message)) => message.contains("The governing session's own open workspace already covers T"); case _ => false }, refused.toString))
          _ <- f.status(DispatchCommand.Cancel(opened.attempt)) *> f.ended(opened.attempt)
          _ <- f.child(DispatchWork.Worker(WorkerMode.Implement), None)
          attempts <- f.attempts
          // The refused start was no unit of the seat: the first unit that starts takes the session's first turn, not its second.
          first = turns(new cq.host.SeatRotation(f.owner.actor.session).next(RoleKey.Plain(AgentRole.Worker), 0, turns.map(ModelRoute(Harness.Codex, None, _, None))))
          _ <- ZIO.attempt(assert(attempts.filter(_.attempt.role == Role.Worker).map(_.attempt.model) == List(first), attempts.map(view => view.attempt.role -> view.attempt.model).toString))
        } yield ()
      }
    }

    "I30: integrate a candidate the governing session made and an independent Reviewer accepted as made by the governing session" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        made <- f.made("good, by the governing session\n", None)
        _ <- assertIO(made.phase == DispatchPhase.Completed && made.next == ChildNext.Review)
        review <- f.child(DispatchWork.Reviewer(ReviewerMode.Candidate), made.result)
        _ <- assertIO(review.phase == DispatchPhase.Completed && review.next == ChildNext.ConsiderAcceptance)
        // As a Worker's correction does, a workspace may continue from the candidate a review names.
        continued <- f.open(review.result)
        _ <- ZIO.attemptBlocking(assert(Files.readString(f.directory(continued).resolve("feature.txt")) == "good, by the governing session\n"))
        _ <- f.status(DispatchCommand.Cancel(continued.attempt)) *> f.ended(continued.attempt)
        recorded <- f.integrated(review.result.get)
        task <- f.taskContent
        _ <- ZIO.attempt {
          assert(recorded.phase == IntegrationPhase.Recorded && task.status == TaskStatus.Done, recorded.toString)
          assert(evidence(task).exists(_.endsWith(s"into $Target; candidate made by the governing session")), evidence(task).toString)
        }
      } yield () }
    }

    "I30 Q60: integrate a Worker's candidate the governing session reviewed itself as reviewed by the governing session" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        made <- f.child(DispatchWork.Worker(WorkerMode.Implement), None)
        _ <- assertIO(made.phase == DispatchPhase.Completed && made.next == ChildNext.Review)
        review <- f.selfReview(made.result.get).map { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        recorded <- f.integrated(review.result.get)
        task <- f.taskContent
        _ <- ZIO.attempt {
          assert(recorded.phase == IntegrationPhase.Recorded && task.status == TaskStatus.Done, recorded.toString)
          assert(evidence(task).exists(_.endsWith(s"into $Target; candidate reviewed by the governing session (self-review, YOLO mode)")), evidence(task).toString)
        }
      } yield () }
    }

    "I30: refuse all three commands in the Rigorous and the Cross-cutting mode with the mode named, and to a batch Governor" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f =>
        def commands: List[(String, DispatchCommand)] = List(
          "OpenWorkspace" -> DispatchCommand.OpenWorkspace(RequestId(uuid), f.members, None, f.fence),
          "SubmitWorkspace" -> DispatchCommand.SubmitWorkspace(AttemptId(uuid), f.ready),
          "SelfReview" -> DispatchCommand.SelfReview(RequestId(uuid), ArtifactId(uuid), f.accepted, f.fence))
        for {
          // Without an activation nothing is dispatched, whatever the mode.
          inactive <- ZIO.foreach(commands)((_, command) => f.refused(command))
          _ <- assertIO(inactive.forall(fault => denied(fault) == "Activate a CQ workflow with session/Workflow before dispatch"))
          _ <- ZIO.foreachDiscard(List(ProcessMode.Rigorous -> "Rigorous", ProcessMode.CrossCutting -> "Cross-cutting")) { (mode, label) => for {
            _ <- f.mode(mode, false)
            _ <- f.activate
            refusals <- ZIO.foreach(commands)((name, command) => f.refused(command).map(name -> _))
            _ <- ZIO.attempt(refusals.foreach { (name, fault) =>
              assert(denied(fault) == s"Workflow execution: $name is the governing session's own work, which only the YOLO cross-cutting mode permits; " +
                s"this workflow activation works in the $label mode. Dispatch a Worker and an independent Reviewer", fault.toString)
            })
          } yield () }
          // The project enters the YOLO mode: the activation that started before keeps its mode, the next one takes the new one.
          _ <- f.mode(ProcessMode.Yolo, false)
          kept <- f.refused(commands.head._2)
          _ <- assertIO(denied(kept).contains("this workflow activation works in the Cross-cutting mode"))
          _ <- f.activate
          // The host reads the project's mode as it is now before it opens anything, and refuses in the words the server would use when it
          // admits; the server's own refusal is the test of a project that leaves the mode while a submission is checked.
          _ <- f.mode(ProcessMode.CrossCutting, false)
          left <- f.refused(commands.head._2)
          _ <- assertIO(denied(left) == "A result the governing session made or reviewed itself is admitted only in the YOLO cross-cutting mode; the project's process mode is Cross-cutting")
          _ <- f.mode(ProcessMode.Yolo, false)
          // The phase limit of the request bounds the session's own work as it bounds a child's.
          _ <- f.workflow.activate(RequestId(uuid), WorkflowRequest.Advance(Set(f.task), WorkflowPhase.Plan), "", None)
          limited <- ZIO.foreach(commands.take(2))((_, command) => f.refused(command))
          _ <- assertIO(limited.map(denied) == List("Workflow execution: OpenWorkspace requires advance through work", "Workflow execution: SubmitWorkspace requires advance through work"))
          _ <- assertIO(f.units.unsettled.isEmpty)
          // A batch Governor has the same tool and is refused in the words the server refuses its result with.
          batch = new LocalControl(f.units, null, null, null, null, null, new McpSchemas(), f.config.copy(run = f.config.run.copy(ownership = SessionOwnership.Managed)),
            new WorkflowExecution(f.authority.governor, f.owner.project, f.owner.actor.session, None))
          refused <- ZIO.foreach(commands) { (_, command) =>
            batch.call(LocalCapability(f.config.run.attempt.id, Role.Governor), "dispatch", DispatchCommand_JsonCodec.encode(Context, command))
              .map((body, failed) => (DispatchReply_JsonCodec.decode(Context, body).fold(throw _, identity), failed))
          }
          _ <- ZIO.attempt(refused.foreach { (reply, failed) =>
            assert(failed && reply == DispatchReply.Failed(Fault.Denied("A result the governing session made or reviewed itself is admitted only for an interactive session; " +
              "a batch run dispatches a Worker and an independent Reviewer")), reply.toString)
          })
        } yield ()
      }
    }

    "I30: publish a failed check as a Worker's is, refuse a self-review of it, and correct it in a workspace that starts from the candidate" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        failed <- f.made("defective\n", None)
        _ <- ZIO.attempt(assert(failed.phase == DispatchPhase.Completed && failed.result.nonEmpty && failed.next == ChildNext.Revise &&
          failed.counts.validationFailed == 1 && failed.blocker.contains("Host check good: Failed"), failed.toString))
        refused <- f.selfReview(failed.result.get)
        _ <- ZIO.attempt(assert(refused == DispatchReply.Failed(Fault.Conflict("A self-review requires that every configured check of the candidate has passed; check good is Failed. " +
          "Correct the candidate in a workspace opened with this result as previous, or Revalidate it when the failure is intermittent")), refused.toString))
        attempts <- f.attempts
        // The refusal registered nothing.
        _ <- assertIO(attempts.count(_.attempt.role == Role.Governor) == 1 && f.units.unsettled.isEmpty)
        // The checks of the session's own candidate are rerun as a Worker's are; a defect of the candidate fails again and still bars the self-review.
        rerun <- f.dispatch(DispatchCommand.Revalidate(RequestId(uuid), failed.result.get, f.fence))
        _ <- ZIO.attempt(rerun match {
          case DispatchReply.Revalidation(round) => assert(round.phase == RevalidationPhase.Completed && round.amendment.nonEmpty &&
            round.validation.map(value => value.check -> value.state) == List("good" -> ValidationState.Failed), round.toString)
          case other => fail(s"The revalidation of the session's own candidate was refused: $other")
        })
        still <- f.selfReview(failed.result.get)
        _ <- assertIO(still == refused)
        opened <- f.open(failed.result)
        _ <- ZIO.attemptBlocking(assert(Files.readString(f.directory(opened).resolve("feature.txt")) == "defective\n"))
        _ <- f.write(opened, "good now\n")
        _ <- f.submit(opened)
        corrected <- f.ended(opened.attempt)
        _ <- ZIO.attempt(assert(corrected.phase == DispatchPhase.Completed && corrected.next == ChildNext.Review && corrected.counts.validationFailed == 0, corrected.toString))
        // The corrected candidate is the later worker result for the members: the earlier one is no longer revalidated.
        superseded <- f.refused(DispatchCommand.Revalidate(RequestId(uuid), failed.result.get, f.fence))
        _ <- assertIO(conflict(superseded) == "Result is superseded by a later result for the same members")
        review <- f.selfReview(corrected.result.get).map { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        recorded <- f.integrated(review.result.get)
        _ <- ZIO.attemptBlocking(assert(recorded.phase == IntegrationPhase.Recorded && local.git(local.source, "show", f.target.value + ":feature.txt") == "good now", recorded.toString))
      } yield () }
    }

    "I30 Q65: refuse a self-reviewed integration of a project without checks, and accept it once the operator exempts the project" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, Nil) { f => for {
        _ <- f.yolo
        made <- f.made("unchecked\n", None)
        review <- f.selfReview(made.result.get).map { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        refused <- f.prepared(review.result.get)
        before <- f.taskContent
        _ <- ZIO.attempt(assert(refused.phase == IntegrationPhase.Failed && refused.blocker.exists(_.contains("A self-reviewed integration requires at least one configured check")) &&
          before.status == TaskStatus.Ready && f.target == local.base, refused.toString))
        _ <- f.mode(ProcessMode.Yolo, true)
        recorded <- f.integrated(review.result.get)
        task <- f.taskContent
        _ <- ZIO.attempt {
          assert(recorded.phase == IntegrationPhase.Recorded && task.status == TaskStatus.Done, recorded.toString)
          assert(evidence(task).exists(_.endsWith("; candidate made and reviewed by the governing session (self-review, YOLO mode)")), evidence(task).toString)
        }
      } yield () }
    }

    "I30: refuse the capture of a workspace in which the session committed, and keep what a cancelled or abandoned workspace holds" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        // The host captures: a commit of the session moves the workspace off its base.
        committed <- f.open(None)
        _ <- f.write(committed, "good, and committed\n")
        _ <- ZIO.attemptBlocking {
          local.git(f.directory(committed), "add", "--all")
          local.git(f.directory(committed), "-c", "user.name=Governor", "-c", "user.email=governor@example.invalid", "commit", "--quiet", "-m", "Committed by the session")
        }
        _ <- f.submit(committed)
        failed <- f.ended(committed.attempt)
        kept <- f.record(committed.attempt)
        failure <- f.written(committed.attempt.value)
        _ <- ZIO.attemptBlocking {
          assert(failed.phase == DispatchPhase.Failed && failed.result.isEmpty && failed.blocker.exists(_.contains("committed base")) && failed.partial.nonEmpty, failed.toString)
          assert(kept.admission == WorkspaceAdmission.Quarantined && kept.quarantineReason.exists(_.startsWith(GovernorWork.Quarantined)) &&
            Files.readString(f.directory(committed).resolve("feature.txt")) == "good, and committed\n", kept.toString)
          assert(failure == List("Started", "Failed"), failure.toString)
        }
        // A submission that is no Worker report is refused and leaves the workspace open.
        cancelled <- f.open(None)
        _ <- f.write(cancelled, "discarded\n")
        malformed <- f.refused(DispatchCommand.SubmitWorkspace(cancelled.attempt, Nil))
        still <- f.status(DispatchCommand.Status(cancelled.attempt, 0))
        _ <- ZIO.attempt(assert(malformed.isInstanceOf[Fault.Invalid] && still.phase == DispatchPhase.Editing && f.unitEvents(cancelled.attempt.value).isEmpty, s"$malformed $still"))
        // Cancel discards the workspace as work: its content is never a candidate, and never removed either.
        _ <- f.status(DispatchCommand.Cancel(cancelled.attempt))
        ended <- f.ended(cancelled.attempt)
        discarded <- f.record(cancelled.attempt)
        late <- f.dispatch(DispatchCommand.SubmitWorkspace(cancelled.attempt, f.ready))
        cancellation <- f.written(cancelled.attempt.value)
        _ <- ZIO.attemptBlocking {
          assert(ended.phase == DispatchPhase.Cancelled && ended.result.isEmpty && ended.partial.nonEmpty && ended.workspace.exists(_.admission == WorkspaceAdmission.Quarantined), ended.toString)
          assert(discarded.admission == WorkspaceAdmission.Quarantined && discarded.quarantineReason.contains(GovernorWork.Quarantined + DispatchUnits.Cancelled) &&
            Files.readString(f.directory(cancelled).resolve("feature.txt")) == "discarded\n", discarded.toString)
          assert(cancellation == List("Started", "Cancelled"), cancellation.toString)
          // A submission that comes after the end changes nothing: it reads how the unit ended.
          assert(late == DispatchReply.Status(ended), late.toString)
        }
        // A host that ends with a workspace open keeps it, with the reason.
        abandoned <- f.open(None)
        _ <- f.write(abandoned, "left behind\n")
        _ <- f.units.shutdown
        left <- f.record(abandoned.attempt)
        last <- f.units.status(abandoned.attempt, 0)
        attempts <- f.attempts
        _ <- ZIO.attemptBlocking {
          assert(left.admission == WorkspaceAdmission.Quarantined && left.quarantineReason.contains(GovernorWork.Quarantined + DispatchController.Ending) &&
            Files.readString(f.directory(abandoned).resolve("feature.txt")) == "left behind\n", left.toString)
          assert(last.phase == DispatchPhase.Cancelled && f.units.quiescent, last.toString)
          // Each of these attempts ended with an outcome: none is an abstention, and none stays running.
          assert(attempts.filter(_.attempt.role == Role.Governor).map(_.outcome.map(_.value.state)) ==
            List(Some(AttemptState.Failed), Some(AttemptState.Cancelled), Some(AttemptState.Cancelled)), attempts.toString)
        }
      } yield () }
    }

    "I30: refuse the submission of a workspace once the project has left the YOLO mode, and end a result the server denies as failed for good" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Slow)) { f => for {
        _ <- f.yolo
        // The operator leaves the mode while the session edits: nothing is captured, and the session is told what to do with the workspace.
        kept <- f.open(None)
        _ <- f.write(kept, "good, too late\n")
        _ <- f.mode(ProcessMode.CrossCutting, false)
        refused <- f.refused(DispatchCommand.SubmitWorkspace(kept.attempt, f.ready))
        still <- f.status(DispatchCommand.Status(kept.attempt, 0))
        _ <- ZIO.attempt {
          assert(denied(refused) == "A result the governing session made or reviewed itself is admitted only in the YOLO cross-cutting mode; the project's process mode is Cross-cutting. " +
            "The workspace was not captured: Cancel it, and its directory is kept as it is", refused.toString)
          assert(still.phase == DispatchPhase.Editing && f.unitEvents(kept.attempt.value).isEmpty, still.toString)
        }
        _ <- f.status(DispatchCommand.Cancel(kept.attempt)) *> f.ended(kept.attempt)
        record <- f.record(kept.attempt)
        _ <- ZIO.attemptBlocking(assert(record.admission == WorkspaceAdmission.Quarantined && Files.readString(f.directory(kept).resolve("feature.txt")) == "good, too late\n", record.toString))
        // The mode is left while the host checks a submitted workspace: the server denies the result, and that is its answer.
        _ <- f.mode(ProcessMode.Yolo, false)
        opened <- f.open(None)
        _ <- f.write(opened, "good, and submitted in time\n")
        _ <- f.submit(opened)
        _ <- f.mode(ProcessMode.CrossCutting, false)
        ended <- f.ended(opened.attempt)
        events <- f.written(opened.attempt.value)
        workspace <- f.record(opened.attempt)
        attempts <- f.attempts
        _ <- ZIO.attemptBlocking {
          val denial = "admitted only in the YOLO cross-cutting mode; the project's process mode is Cross-cutting"
          // A terminal status that names the denial, not a delivery to retry.
          assert(ended.phase == DispatchPhase.Failed && ended.result.isEmpty && ended.next == ChildNext.Retry && ended.usageDelivered &&
            ended.blocker.exists(text => text.startsWith("Result admission denied: ") && text.contains(denial)), ended.toString)
          assert(events == List("Started", "Failed") && f.units.unsettled.isEmpty && f.children.undelivered.isEmpty)
          // The attempt has its outcome, with the reason; nothing is left for recovery or cq job upload to deliver again.
          val outcome = attempts.find(_.attempt.id == opened.attempt).flatMap(_.outcome).map(_.value)
          assert(outcome.exists(value => value.state == AttemptState.Failed && value.gaps.exists(_.contains(denial))), outcome.toString)
          val receipt = HostFiles.read(f.session.resolve("children").resolve(opened.attempt.value.toString).resolve("receipt.json"), DispatchStatus_JsonCodec, 65536)
          assert(receipt.phase == DispatchPhase.Failed && receipt.copy(workspace = ended.workspace) == ended, receipt.toString)
          // The workspace is kept with what was written and a reason that says what happened to it.
          assert(workspace.admission == WorkspaceAdmission.Quarantined && workspace.quarantineReason.exists(reason => reason.startsWith("The server refused the result: ") && reason.contains("YOLO")) &&
            Files.readString(f.directory(opened).resolve("feature.txt")) == "good, and submitted in time\n", workspace.toString)
        }
      } yield () }
    }

    "I30: keep a workspace that is submitted without a ready candidate, whatever its report says" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        _ <- ZIO.foreachDiscard(List(WorkDisposition.Blocked, WorkDisposition.Failed)) { disposition => for {
          opened <- f.open(None)
          _ <- f.write(opened, s"unfinished and $disposition\n")
          _ <- f.status(DispatchCommand.SubmitWorkspace(opened.attempt, f.members.map(member => WorkMember(member.id, disposition, "Could not finish", Nil))))
          ended <- f.ended(opened.attempt)
          record <- f.record(opened.attempt)
          _ <- ZIO.attemptBlocking {
            // The report is a result like a Worker's; no candidate was captured, so the host removes nothing of what the session wrote.
            assert(ended.phase == DispatchPhase.Completed && ended.result.nonEmpty && ended.next != ChildNext.Review, ended.toString)
            assert(record.admission == WorkspaceAdmission.Quarantined && record.quarantineReason.contains(AttemptSettlement.Uncaptured) &&
              ended.workspace.contains(WorkspaceState(WorkspaceAdmission.Quarantined, Some(record.directory))) &&
              Files.readString(f.directory(opened).resolve("feature.txt")) == s"unfinished and $disposition\n", s"$disposition: $record")
          }
        } yield () }
      } yield () }
    }

    "I30 Q64: settle as not applied an integration prepared in the YOLO mode that the server refuses once the project has left it" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        made <- f.made("good, by the governing session\n", None)
        review <- f.selfReview(made.result.get).map { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        ready <- f.prepared(review.result.get)
        _ <- assertIO(ready.phase == IntegrationPhase.Ready)
        _ <- f.mode(ProcessMode.CrossCutting, false)
        _ <- f.dispatch(DispatchCommand.Integrate(ready.id))
        settled <- f.controller.status(ready.id, 120000).repeatUntil(status => !Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(status.phase))
          .timeoutFail(new IllegalStateException("Integration did not settle"))(zio.Duration.fromSeconds(60))
        task <- f.taskContent
        _ <- ZIO.attempt {
          // The refusal is the server's answer to this reservation: nothing was reserved or applied, and nothing is left pending.
          assert(settled.phase == IntegrationPhase.NotApplied && settled.blocker.exists(_.contains("requires the YOLO cross-cutting mode; the project's process mode is Cross-cutting")), settled.toString)
          assert(task.status == TaskStatus.Ready && f.target == local.base && f.controller.unsettled.isEmpty, f.controller.unsettled.toString)
        }
      } yield () }
    }

    "I30: answer an OpenWorkspace whose attempt cannot be launched instead of waiting for a workspace that never opens" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.yolo
        // The host cannot retain the ticket of the attempt: its directory of children is a file.
        _ <- ZIO.attemptBlocking(Files.writeString(f.session.resolve("children"), "not a directory"))
        request = DispatchCommand.OpenWorkspace(RequestId(uuid), f.members, None, f.fence)
        first <- f.dispatch(request).timeoutFail(new IllegalStateException("OpenWorkspace waited for a workspace that cannot open"))(zio.Duration.fromSeconds(20))
        again <- f.dispatch(request).timeoutFail(new IllegalStateException("A repeated OpenWorkspace waited for a workspace that cannot open"))(zio.Duration.fromSeconds(20))
        _ <- ZIO.attempt(List(first, again).foreach {
          case DispatchReply.Failed(_) => ()
          case DispatchReply.Status(status) => assert(status.phase == DispatchPhase.Unknown && status.next == ChildNext.InspectEvidence && status.workspace.isEmpty, status.toString)
          case other => fail(s"Unexpected reply $other")
        })
        _ <- ZIO.attemptBlocking(Files.delete(f.session.resolve("children")))
      } yield () }
    }

    "I30: start the full number of children beside an open workspace, which holds its members and no child slot" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      configured(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good), ClaimRenewal.Default, 5) { f =>
        def child(index: Int) = f.units.start(AssignedWork(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), List(f.others(index)._1), Nil, Nil, None, f.others(index)._2, f.limits), None)
        for {
          _ <- f.yolo
          // Children of this test run until they are cancelled.
          _ <- ZIO.attemptBlocking(Files.writeString(Path.of(f.config.settings.harnesses.head.executable).resolveSibling("hold"), ""))
          opened <- f.open(None)
          started <- ZIO.foreach(List(0, 1, 2, 3))(child)
          fifth <- child(4).either
          _ <- ZIO.attempt {
            assert(started.forall(status => !DispatchController.terminal(status.phase)) && started.map(_.attempt).distinct.size == DispatchController.MaxActiveChildren, started.toString)
            assert(fifth.left.exists { case DomainFailure(Fault.Conflict(message)) => message.contains("at most 4 active children"); case _ => false }, fifth.toString)
          }
          // With every child slot taken a second workspace is admitted as far as slots go: its refusal is the members', not the bound's.
          second <- f.refused(DispatchCommand.OpenWorkspace(RequestId(uuid), List(f.others(0)._1), None, f.others(0)._2))
          _ <- assertIO(conflict(second).contains("An active child already covers T2"))
          still <- f.status(DispatchCommand.Status(opened.attempt, 0))
          _ <- assertIO(still.phase == DispatchPhase.Editing)
          _ <- ZIO.foreachDiscard(started)(status => f.units.cancel(status.attempt))
          _ <- ZIO.foreachDiscard(started)(status => f.units.status(status.attempt, 120000).repeatUntil(value => DispatchController.terminal(value.phase)))
            .timeoutFail(new IllegalStateException("The children did not end"))(zio.Duration.fromSeconds(60))
          _ <- f.status(DispatchCommand.Cancel(opened.attempt)) *> f.ended(opened.attempt)
        } yield ()
      }
    }

    "I30: cancel an open workspace whose claim is lost, keep it and tell a waiter, and end one submission that races its cancellation in one way" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      configured(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good), ClaimRenewal.Policy(java.time.Duration.ofSeconds(60), java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(1)), 1) { f => for {
        _ <- f.yolo
        opened <- f.open(None)
        _ <- f.write(opened, "written under a claim that is released\n")
        _ <- ledger.release(f.owner, f.fence)
        lost <- f.ended(opened.attempt)
        events <- f.written(opened.attempt.value)
        record <- f.record(opened.attempt)
        _ <- ZIO.attemptBlocking {
          assert(lost.phase == DispatchPhase.Cancelled && lost.result.isEmpty && lost.blocker.exists(_.startsWith("Work claim refresh failed: ")), lost.toString)
          // Whoever waits for the session's work is told that the host took it over and how it ended.
          assert(events == List("Started", "Cancelled"), events.toString)
          assert(record.admission == WorkspaceAdmission.Quarantined && record.quarantineReason.exists(_.startsWith(GovernorWork.Quarantined + "Work claim refresh failed: ")) &&
            Files.readString(f.directory(opened).resolve("feature.txt")) == "written under a claim that is released\n", record.toString)
        }
        // A submission and a cancellation of the same workspace at once: one of them takes it, and everything agrees with that one.
        (member, fence) = f.others.head
        raced <- f.status(DispatchCommand.OpenWorkspace(RequestId(uuid), List(member), None, fence))
        _ <- ZIO.attemptBlocking(Files.writeString(f.directory(raced).resolve("feature.txt"), "good, and raced\n"))
        report = List(WorkMember(member.id, WorkDisposition.CandidateReady, "Implemented", Nil))
        _ <- f.dispatch(DispatchCommand.SubmitWorkspace(raced.attempt, report)).zipPar(f.dispatch(DispatchCommand.Cancel(raced.attempt)))
        ended <- f.ended(raced.attempt)
        pair <- f.written(raced.attempt.value)
        kept <- f.record(raced.attempt)
        _ <- ZIO.attemptBlocking {
          assert(pair == List("Started", ended.phase.toString), pair.toString)
          ended.phase match {
            case DispatchPhase.Completed => assert(ended.result.nonEmpty && kept.admission == WorkspaceAdmission.Removed, s"$ended $kept")
            case DispatchPhase.Cancelled => assert(ended.result.isEmpty && kept.admission == WorkspaceAdmission.Quarantined &&
              Files.readString(f.directory(raced).resolve("feature.txt")) == "good, and raced\n", s"$ended $kept")
            case other => fail(s"A raced workspace ended $other: $ended")
          }
        }
      } yield () }
    }
  }

  "A drive whose session works itself (Behavioral Active Blackbox; in-process server and driver core Communication)" should {
    "I30: rest an open workspace on the session, answer a stop with one resume directive and end the drive at the next stop on it" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Good)) { f => for {
        _ <- f.mode(ProcessMode.Yolo, false)
        _ <- f.driven
        opened <- f.open(None)
        resting = Set[LineageMember](LineageMember.Request(opened.request), LineageMember.Attempt(opened.attempt))
        _ <- f.eventually("the open workspace rests on the session")(f.cycle.exists(cycle => cycle.held == resting && cycle.inFlight.isEmpty))
        // The session is not waited for: a stop that claims to be woken is answered like any other.
        prompted <- f.continuation(true)
        resume = prompted match {
          case DriverReply.Continue(directive, _, _) if directive.token.isInstanceOf[CycleToken.Resume] => directive
          case other => fail(s"Expected one resume directive for the open workspace: $other")
        }
        _ <- f.workflow.activate(RequestId(uuid), f.advance, "", Some(resume.token))
        stopped <- f.continuation(false)
        _ <- ZIO.attempt(stopped match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) =>
            assert(detail.contains("a resume directive did not resolve it") && detail.contains(s"attempt ${opened.attempt.value}"), detail)
          case other => fail(s"Expected the drive to end on the workspace that still rests: $other")
        })
        // The workspace outlives the drive: nothing was captured or removed, and the session may still submit or cancel it.
        still <- f.status(DispatchCommand.Status(opened.attempt, 0))
        _ <- assertIO(still.phase == DispatchPhase.Editing && Files.isDirectory(f.directory(opened)))
        _ <- f.status(DispatchCommand.Cancel(opened.attempt)) *> f.ended(opened.attempt)
      } yield () }
    }

    "I30: hold a submitted workspace in flight until its result is published, then conclude it with its outcome" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry, List(Slow)) { f => for {
        _ <- f.mode(ProcessMode.Yolo, false)
        _ <- f.driven
        opened <- f.open(None)
        members = Set[LineageMember](LineageMember.Request(opened.request), LineageMember.Attempt(opened.attempt))
        _ <- f.eventually("the open workspace rests on the session")(f.cycle.exists(_.held == members))
        _ <- f.write(opened, "good, under a drive\n")
        _ <- f.submit(opened)
        // The submission returns with the unit in flight on the server: a stop is answered as for a running child.
        flying = f.cycle.map(cycle => (cycle.held, cycle.inFlight.map(_.member).toSet))
        waiting <- f.continuation(true)
        _ <- ZIO.attempt {
          assert(flying.contains((Set.empty[LineageMember], members)), s"$flying; ${f.lineage}")
          assert(waiting.isInstanceOf[DriverReply.Waiting], waiting.toString)
        }
        made <- f.ended(opened.attempt)
        _ <- f.eventually("the submitted workspace is concluded in its cycle")(f.cycle.exists(cycle =>
          members.forall(member => cycle.lineage.exists(entry => entry.member == member && entry.settled))))
        _ <- ZIO.attempt {
          assert(made.phase == DispatchPhase.Completed && made.result.nonEmpty, made.toString)
          assert(f.cycle.exists(_.outcomes == List(ChildOutcome(opened.attempt, f.members.map(_.id), ChildEnd.Admitted, None, None))), f.lineage)
        }
        // A self-review ends in the call that makes it: it is accounted for in the cycle like any attempt and never rests.
        review <- f.selfReview(made.result.get).map { case DispatchReply.Status(value) => value; case other => fail(s"The self-review was refused: $other") }
        _ <- f.eventually("the self-review is concluded in its cycle")(f.cycle.exists(cycle => cycle.outcomes.map(outcome => outcome.attempt -> outcome.end) ==
          List(opened.attempt -> ChildEnd.Admitted, review.attempt -> ChildEnd.Admitted) && cycle.held.isEmpty && cycle.inFlight.isEmpty))
        // A call on a unit that has ended reads how it ended and touches no cycle: neither the one that held it nor a later drive's.
        _ <- f.parked
        _ <- f.driven
        later = f.cycle.map(_.id)
        late <- f.dispatch(DispatchCommand.SubmitWorkspace(opened.attempt, f.ready))
        stopped <- f.dispatch(DispatchCommand.Cancel(opened.attempt))
        _ <- ZIO.sleep(zio.Duration.fromMillis(1500))
        _ <- ZIO.attempt {
          assert(late == DispatchReply.Status(made) && stopped == DispatchReply.Status(made), s"$late $stopped")
          assert(f.cycle.map(_.id) == later && later.nonEmpty && f.cycle.exists(cycle => cycle.outcomes.isEmpty &&
            cycle.lineage.map(_.member).forall(_.isInstanceOf[LineageMember.Run])), f.lineage)
        }
      } yield () }
    }
  }
}
