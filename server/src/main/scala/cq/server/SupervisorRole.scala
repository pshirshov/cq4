package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import cq.host.*
import distage.{Lifecycle, ModuleDef}
import izumi.distage.plugins.PluginDef
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.functional.bio.UnsafeRun2.FailureHandler
import izumi.fundamentals.platform.cli.model.{EntrypointArgs, RoleAppArgs}
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.net.URI
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.util.{Try, Using}
import zio.{IO, Task, Unsafe, ZEnvironment, ZIO}

final class HarnessRegistry(adapters: Set[HarnessAdapter]) {
  require(adapters.map(_.harness) == Harness.all.toSet && adapters.size == Harness.all.size, "Exactly one adapter per supported harness required")
  def apply(harness: Harness): HarnessAdapter = adapters.find(_.harness == harness).get
}

final case class SupervisorConfig(settings: SupervisorSettings, project: ProjectConfig, profile: HarnessProfile,
  limits: ExecutionLimits, run: SupervisorRun, directory: Path, input: String, workflow: Option[WorkflowRequest], environment: Map[String, String]) {
  val owner: Scope = Scope(project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
  val endpoint: URI = URI.create(project.endpoint)
}

object SupervisorConfig {
  private val MaxConfigBytes = 64 * 1024
  private val MaxInputBytes = 192 * 1024
  private val MaxOutputBytes = 32 * 1024 * 1024
  val VersionMismatch = "Installed harness version differs from its configured verified route"
  def profile(value: HarnessSetting): HarnessProfile = HarnessProfile(value.harness, Path.of(value.executable), value.model, value.provider, value.version,
    value.providerExtensions.map(Path.of(_)), value.providerEnvironment)
  def limits(value: HostLimits): ExecutionLimits = ExecutionLimits(Duration.ofMillis(value.startupMillis), None,
    Duration.ofMillis(value.heartbeatMillis), Duration.ofMillis(value.graceMillis), Duration.ofMillis(value.killMillis), value.retainedOutputBytes)
  def within(value: HostLimits, ceiling: HostLimits): Unit = {
    limits(value)
    require(List(value.startupMillis -> ceiling.startupMillis,
      value.heartbeatMillis -> ceiling.heartbeatMillis, value.graceMillis -> ceiling.graceMillis, value.killMillis -> ceiling.killMillis,
      value.retainedOutputBytes.toLong -> ceiling.retainedOutputBytes.toLong).forall((actual, maximum) => actual <= maximum), "Child limits exceed the governing session's configured bounds")
  }
  def verifyProfile(config: SupervisorConfig, value: HarnessProfile): Unit = {
    val version = new BoundedHostCommand(HarnessEnvironment.isolated(value, config.environment), Duration.ofSeconds(10), 4096)
      .run(Path.of(config.run.repository), List(value.executable.toString, "--version"))
    require(version.exit == 0 && version.text.split("[\\s()]+").contains(value.version), VersionMismatch)
  }
  def load(arguments: RoleAppArgs, context: CliContext, location: ProjectLocation, clock: Clock): Task[SupervisorConfig] = ZIO.attemptBlocking {
    val raw = arguments.roles.find(value => Set(SupervisorRole.id, AttachedRole.id)(value.role)).getOrElse(throw new IllegalArgumentException("Supervisor role arguments missing")).roleParameters.raw.toList
    val args = if (raw.headOption.contains("--")) raw.tail else raw
    val attached = arguments.roles.exists(_.role == AttachedRole.id)
    require(args.nonEmpty && args.size % 2 == 1, "cq run HARNESS --settings FILE --input FILE or cq host HARNESS [--settings FILE]")
    val harness = Harness.all.find(_.toString.equalsIgnoreCase(args.head)).getOrElse(throw new IllegalArgumentException("Unknown governing harness"))
    val pairs = args.tail.grouped(2).map(values => values.head -> values(1)).toList
    val required = if (attached) Set.empty[String] else Set("--settings", "--input")
    val allowed = if (attached) Set("--settings") else required ++ WorkflowArguments.Options
    require(pairs.map(_._1).distinct.size == pairs.size && required.subsetOf(pairs.map(_._1).toSet) &&
      pairs.forall(pair => allowed(pair._1)), "Unsupported, repeated or missing execution options")
    val supplied = pairs.toMap
    val options = if (supplied.contains("--settings")) supplied else supplied.updated("--settings",
      context.environment.getOrElse("CQ_SETTINGS", throw new IllegalArgumentException("--settings FILE or CQ_SETTINGS is required")))
    val settingsFile = context.directory.resolve(options("--settings")).normalize()
    require(Files.isRegularFile(settingsFile), s"Settings file $settingsFile is missing")
    val settings = HostFiles.read(settingsFile, SupervisorSettings_JsonCodec, MaxConfigBytes)
    val profiles = settings.harnesses.map(SupervisorConfig.profile)
    require(profiles.nonEmpty && profiles.map(_.harness).distinct.size == profiles.size, "Harness settings must have unique routes")
    val profile = profiles.find(_.harness == harness).getOrElse(throw new IllegalArgumentException("Governing harness route is not configured"))
    val limits = SupervisorConfig.limits(settings.limits)
    require(limits.retainedOutputBytes <= MaxOutputBytes, "Native output retention exceeds 32 MiB per stream")
    require(settings.evaluation.forall(value => List(value.run, value.scenario).forall(text => text.trim.nonEmpty && text.length <= 300)),
      "Evaluation identity must contain a bounded run and scenario")
    require(settings.checks.size <= 8 && settings.checks.map(_.name).distinct.size == settings.checks.size, "At most eight uniquely named validation checks are supported")
    settings.checks.foreach { check =>
      require(check.name.matches("[a-z][a-z0-9-]{0,49}") && check.command.nonEmpty && check.command.size <= 32 &&
        check.command.forall(value => value.nonEmpty && value.length <= 4096 && !value.contains('\u0000')) &&
        check.executionMillis > 0 && check.executionMillis <= ExecutionLimits.MaximumMillis && check.retainedOutputBytes > 0 && check.retainedOutputBytes <= 1024 * 1024,
        "Invalid configured validation check")
    }
    require(settings.checks.map(value => HostFiles.encode(ValidationCheck_JsonCodec, value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum <= 16384,
      "Configured validation arguments exceed 16 KiB")
    val guardian = Path.of(settings.guardian)
    require(guardian.isAbsolute && guardian.normalize() == guardian && Files.isExecutable(guardian), "Explicit executable guardian required")
    val projectFile = location.directory.resolve("project.json")
    require(Files.isRegularFile(projectFile), s"Project configuration $projectFile is missing")
    val project = HostFiles.read(projectFile, ProjectConfig_JsonCodec, MaxConfigBytes)
    val workflow = WorkflowArguments.parse(project.project, options.filter((key, _) => WorkflowArguments.Options(key)))
    val git = new BoundedHostCommand(GitEnvironment.isolated(context.environment), Duration.ofSeconds(10), MaxConfigBytes)
    def command(arguments: String*): String = {
      val value = git.run(context.directory, List("git") ++ arguments)
      require(value.exit == 0, "Supervisor requires a Git checkout with a committed base")
      value.text.trim
    }
    val repository = Path.of(command("rev-parse", "--show-toplevel")).toRealPath()
    settings.integrationTarget.foreach { target =>
      require(target.length <= 4096 && target.startsWith("refs/heads/") && target.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") &&
        !target.contains("..") && target.split("/", -1).forall(part => part.nonEmpty && !part.startsWith(".") && !part.endsWith(".") && !part.endsWith(".lock")),
        "Integration target must be an explicit full branch reference")
      command("show-ref", "--verify", "--hash", target)
    }
    val base = GitCommit(command("rev-parse", "--verify", "HEAD"))
    val stateRoot = Path.of(settings.stateRoot)
    HostFiles.directory(stateRoot)
    require(!stateRoot.toRealPath().startsWith(repository), "Supervisor state root must be outside the source checkout")
    val session = SessionId(UUID.randomUUID())
    val directory = stateRoot.resolve(session.value.toString)
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project.project, Set.empty, Attribution.Unattributed, None, settings.evaluation)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Governor, harness,
      if (attached) "unobserved-interactive-provider" else profile.provider,
      if (attached) "unobserved-interactive-model" else profile.model,
      if (attached) "CQ attached session; outer usage unavailable" else "CQ native collector 0.1.0", clock.millis(), UsagePhase.Govern)
    val run = SupervisorRun(project, assignment, attempt, profile.version, repository.toString, base,
      if (attached) SessionOwnership.Attached else SessionOwnership.Managed)
    val input = if (attached) "" else HostFiles.text(context.directory.resolve(options("--input")).normalize(), MaxInputBytes)
    require(attached || input.trim.nonEmpty, "Governing input cannot be empty")
    val version = new BoundedHostCommand(HarnessEnvironment.isolated(profile, context.environment), Duration.ofSeconds(10), 4096)
      .run(repository, List(profile.executable.toString, "--version"))
    require(version.exit == 0 && version.text.split("[\\s()]+").contains(profile.version), VersionMismatch)
    SupervisorConfig(settings, project, profile, limits, run, directory, input, workflow, context.environment)
  }
}

final class SupervisorJobs(config: SupervisorConfig, workspaces: WorkspaceService[IO], driver: ExecutionDriver, clock: Clock, watchdog: SupervisorWatchdog)
  extends Lifecycle.Of[Task, JobSupervisor](
    Lifecycle.make(zio.Scope.make)(scope => ZIO.succeed(watchdog.beginShutdown()) *> scope.close(zio.Exit.unit)).flatMap { scope =>
      Lifecycle.liftF(JobSupervisor.acquire(config.owner,
        ZIO.attemptBlocking(FileJobRepository.open(config.directory.resolve("journal"), config.project.project, config.run.attempt.session)),
        workspaces, driver, config.directory.resolve("payload"), clock).provideEnvironment(ZEnvironment(scope)))
    }
  )

object SupervisorProgram {
  val Guidance = "Govern CQ through the exposed tools. Input identifies project, routes, limits, checks and human request. Discover/create work. " +
    "When workflow is present, follow its host-installed instructions and typed scope. " +
    "Before a child, dispatch Select with explicit roots, desired work, guidance/artifact handles, optional previous and limits. Claim all members of one returned choice, then StartChoice with its ID, configured harness and current fence. Choices fix membership and work; selection itself acquires no claim. Workflow runs require choices. Read excluded/unexamined/ineligible counts. " +
    "An implementation selection may return Planner for compatibility assessment. Forward that result in artifacts to a fresh Worker Implement Select. Unknown/incompatible groups split; acquire each split's exact claim. Pass larger prior results as artifacts when selecting subgroups. Unchanged executed input is deferred; obtain substantive evidence or changed conditions. " +
    "Dispatch sequentially using item revisions and handles. The host assembles prompts, captures candidates and runs checks. Never read/compose child prompts or copy full results. Poll Status with waitMillis 20000; use compact outcomes and bounded artifact reads only for necessary drill-down. " +
    "Status quietMillis is time since a running child's last output; long tool calls are silent. Report a long-quiet child to the operator; never cancel it yourself. " +
    "Use Explorer Investigate/Research for evidence, Worker Probe for experiments, Planner for typed proposals and Reviewer Plan/Audit for independent findings. Pass previous result handles with identical members and current fence. Preview read/Proposal, then apply by result handle; never reconstruct drafts. Children cannot mutate CQ or integrate. " +
    "Pass worker candidates to Reviewer Candidate; prefer another configured harness. " +
    "With integrationTarget, PrepareIntegration using a fresh ID and accepted reviewer handle, poll IntegrationStatus, inspect its frozen preview, then Integrate that ID. Only Recorded establishes domain recording; reconcile Pending and inspect NotApplied. Without a target, report the retained reviewed candidate. " +
    "PrepareIntegration rebases onto a moved target itself; after NotApplied, prepare again with a fresh ID. If Ready carries a blocker, Integrate, then Combine a fresh ID, that integration ID and current full fence; poll CombinationStatus. Dispatch Worker ResolveConflict with Ready plan in artifacts, its worker as previous and exact preview members/fence. Obtain fresh validation and Reviewer from the new worker handle; omit the plan from reviewer artifacts. Integrate with a fresh ID. For PublicationPending, replay identical Combine or cq job upload. " +
    "Claim execution only with host evidence. Child completion/review acceptance does not establish final task acceptance."
  val Instructions = Guidance + " Return exactly {\"summary\":\"observed outcome and remaining work\"}."
}

final class SupervisorProgram(config: SupervisorConfig, registry: HarnessRegistry, jobs: JobSupervisor, authority: SupervisorAuthority,
  local: LocalControlServer, access: LocalAccess, dispatch: DispatchController, integrations: IntegrationController, combinations: CombinationController,
  schemas: McpSchemas, output: HarnessOutput, workflows: WorkflowAssets, cleanup: WorkspaceCleanup, watchdog: SupervisorWatchdog, clock: Clock, context: CliContext) {
  private val MaxInputBytes = 192 * 1024
  private val MaxRecordBytes = 64 * 1024
  private val MaxSummaryCharacters = 8192
  private val MaxGaps = 32
  private val MaxGapCharacters = 300

  def run: Task[Unit] = ZIO.runtime[Any].flatMap { runtime =>
    val attempt = config.run.attempt
    val project = config.project.project
    val assets = config.directory.resolve("assets").resolve(attempt.id.value.toString)
    val payload = config.directory.resolve("payload").resolve(attempt.id.value.toString)
    // Interruption closes process admission, stops the governor and lets the usual settlement (receipt, Finish usage) run before the exit.
    val guard = new TerminationGuard("cq-supervisor-termination", () => {
      watchdog.beginShutdown()
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.fork(jobs.cancel(config.owner, attempt.id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.orDie); ()
      }
    })
    (for {
      _ <- ZIO.attempt(guard.install())
      prepared <- ZIO.attemptBlocking {
        HostFiles.directory(config.directory)
        HostFiles.immutable(config.directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, config.run), MaxRecordBytes)
        HostFiles.immutable(config.directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, config.settings), MaxRecordBytes)
        val collector = authority.collector
        val input = HostFiles.encode(GoverningInput_JsonCodec, GoverningInput(config.project,
          config.settings.harnesses.map(value => HarnessRoute(value.harness, value.model, value.provider)), config.settings.checks.map(_.name), config.settings.limits, config.settings.integrationTarget, config.input,
          config.workflow.map(new WorkflowAssembly(authority.governor, project, workflows).assemble)))
        require(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxInputBytes, "Complete governing input exceeds its byte bound")
        val invocation = schemas.nativeInvocation(attempt.harness,
          HarnessInvocation(Role.Governor, attempt.id, SupervisorProgram.Instructions, schemas.schema("GoverningReport"),
            List(HarnessMcp(McpTarget.Domain, config.endpoint.resolve("/mcp"), authority.governorToken),
              HarnessMcp(McpTarget.Local, local.endpoint, access.issue(attempt.id, Role.Governor))), assets))
        val queue = new DeliveryQueue(config.directory.resolve("delivery"))
        val initial = DeliveryBatch(List(
          HostDelivery.Usage(HostUsageInput(project, HostUsage.Assign(config.run.assignment))),
          HostDelivery.Usage(HostUsageInput(project, HostUsage.Start(attempt))),
          HostDelivery.Artifact(ArtifactUpload(project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input, "application/json", input)),
          HostDelivery.Artifact(ArtifactUpload(project, NativeArtifacts.id(attempt.id, "prompt"), attempt.id, ArtifactKind.Prompt, "text/plain", invocation.system)),
        ))
        queue.enqueue(0, initial)
        queue.flush(collector)
        val launch = registry(attempt.harness).launch(config.profile, invocation, config.environment)
        launch.install(assets)
        (collector, queue, JobCommand(launch.arguments, launch.environment, input, config.limits))
      }
      (collector, queue, command) = prepared
      _ <- cleanup.recover.forkDaemon
      _ <- jobs.start(config.owner, WorkspaceSpec(project, attempt.session, attempt.id, config.run.repository, config.run.base), command)
      record <- jobs.await(config.owner, attempt.id)
      // The governor's result is read from its retained output and assets; a failed removal is retried by the next host startup.
      _ <- jobs.release(config.owner, attempt.id).ignore
      _ <- integrations.shutdown.zipPar(combinations.shutdown).zipPar(dispatch.shutdown)
      receipt <- ZIO.attemptBlocking {
        val stdout = NativeTranscript.retained(payload.resolve("stdout"), config.limits.retainedOutputBytes)
        val stderr = NativeTranscript.retained(payload.resolve("stderr"), config.limits.retainedOutputBytes)
        val (nativeId, stdoutArtifacts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
        val (_, stderrArtifacts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
        val collectedAt = math.max(attempt.startedAt, clock.millis())
        val usage = Using.resource(NativeTranscript.stream(payload.resolve("stdout")))(new HarnessUsage().collect(_,
          UsageCollectionRequest(attempt.id, attempt.harness, config.profile.version, UsageOrigin.Fresh, collectedAt, nativeId)))
        val observed = JobOutcome.observed(record)
        val succeeded = observed.succeeded
        val report = Try {
          require(succeeded, observed.problem.getOrElse("Governing process did not complete successfully"))
          require(usage.terminalSeen && !usage.nativeFailure, "Governing native output did not complete successfully")
          val json = Using.resource(NativeTranscript.stream(payload.resolve("stdout")))(output.result(attempt.harness, _, assets))
          val result = GoverningReport_JsonCodec.decode(baboon.runtime.shared.BaboonCodecContext.Default, json).fold(throw _, identity)
          require(json.asObject.exists(_.keys.toSet == Set("summary")) && result.summary.trim.nonEmpty && result.summary.length <= MaxSummaryCharacters,
            "Governing result does not match its bounded contract")
          ArtifactUpload(project, NativeArtifacts.id(attempt.id, "result"), attempt.id, ArtifactKind.Result, "application/json", HostFiles.encode(GoverningReport_JsonCodec, result))
        }.toEither
        val problem = report.left.toOption.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(MaxGapCharacters))
        val artifacts = stdoutArtifacts ++ stderrArtifacts ++ report.toOption.toList
        val observations = usage.meters.flatMap(batch => HostDelivery.Usage(HostUsageInput(project, HostUsage.Meter(batch.meter))) ::
          batch.observations.map(value => HostDelivery.Usage(HostUsageInput(project, HostUsage.Ingest(value)))))
        val gaps = (problem.toList ++ usage.gaps).take(MaxGaps)
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id,
          observed.withResult(report.isRight), collectedAt, gaps, None)
        val entries = artifacts.map(HostDelivery.Artifact.apply) ++ observations :+
          HostDelivery.Usage(HostUsageInput(project, HostUsage.Finish(outcome)))
        queue.commit(entries)
        val delivered = Try(queue.flush(collector)).toEither
        val pending = delivered.left.toOption.map(_ => "Operational usage/artifact delivery is pending; retain the session directory and retry cq job upload")
        val receipt = SupervisorReceipt(attempt.session, attempt.id, config.directory.toString, record.phase, succeeded,
          report.toOption.map(_.id), report.toOption.map(value => Wire.decode(GoverningReport_JsonCodec, value.body)), delivered.isRight, pending.orElse(problem))
        HostFiles.immutable(config.directory.resolve("receipt.json"), HostFiles.encode(SupervisorReceipt_JsonCodec, receipt), MaxRecordBytes)
        context.output.println(HostFiles.encode(SupervisorReceipt_JsonCodec, receipt))
        receipt
      }
      _ <- ZIO.attempt(require(receipt.problem.isEmpty, receipt.problem.getOrElse("Governing run failed")))
    } yield ()).ensuring(ZIO.succeed(guard.release()))
  }
}

final class SupervisorRole(program: SupervisorProgram) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = program.run
}
object SupervisorRole extends RoleDescriptor {
  override val id = "supervisor"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("Own a local governing harness and its isolated jobs"), Some("cq run HARNESS --settings FILE --input FILE [--workflow NAME ...]"), freeArgsAllowed = true)
}

object SupervisorPlugin extends PluginDef {
  include(new ModuleDef {
    include(new RoleModuleDef { makeRole[SupervisorRole]; makeRole[AttachedRole] })
    make[SupervisorConfig].fromEffect(SupervisorConfig.load _)
    many[HarnessAdapter].add[ClaudeAdapter].add[CodexAdapter].add[PiAdapter]
    make[HarnessRegistry]
    make[HarnessOutput]
    make[ChildInstructions]
    make[AgentCatalog]
    make[WorkspaceReader]
    make[CandidateWorkspace]
    make[SupervisorAuthority].fromEffect(SupervisorAuthority.acquire _)
    modify[FailureHandler].by(_.flatAp((arguments: RoleAppArgs) => AttachedStartup.reporting(arguments)))
    make[WorkflowExecution].from { (config: SupervisorConfig, authority: SupervisorAuthority) =>
      new WorkflowExecution(authority.governor, config.project.project, config.owner.actor.session, config.workflow)
    }
    make[LocalAccess]
    make[OperatorRequirements].from((config: SupervisorConfig) => new OperatorRequirements(config.input))
    make[ChildRunner]
    make[DispatchController].fromResource[DispatchController.Resource]
    make[CohortController]
    make[IntegrationController].fromResource[IntegrationController.Resource]
    make[CombinationController].fromResource[CombinationController.Resource]
    make[LocalControl]
    make[WorkspaceCleanup]
    make[LocalControlServer].fromResource[LocalControlServer.Resource]
    make[SupervisorProgram]
    make[AttachedChannels].fromEffect(ZIO.attempt(AttachedChannels(System.in, System.out,
      new ProcessOwner(ProcessHandle.current().parent().orElseThrow(() => new IllegalArgumentException("Owning harness process is unavailable"))))))
    make[AttachedProgram]
    make[AttachedGateway]
    make[AttachedWorkflow]
    make[AttachedDriver]
    make[AttachedUsage].from { (config: SupervisorConfig, clock: Clock) => new AttachedUsage(config.directory, config.run, clock) }
    make[AttachedCodexUsage].from { (config: SupervisorConfig, clock: Clock) =>
      new AttachedCodexUsage(config.directory, config.run, new CodexRollout, clock)
    }
    make[SupervisorWatchdog].fromResource[SupervisorWatchdog.Resource]
    make[ExecutionDriver].from[SupervisorDriver]
    make[SessionWorkspaces]
    make[SessionCollectors].from[HttpSessionCollectors]
    make[WorkspaceService[IO]].from((config: SupervisorConfig, sessions: SessionWorkspaces) => sessions.at(config.directory))
    make[JobSupervisor].fromResource[SupervisorJobs]
  })
}
