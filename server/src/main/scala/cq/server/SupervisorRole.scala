package cq.server

import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.*
import distage.{Lifecycle, ModuleDef}
import izumi.distage.plugins.PluginDef
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.fundamentals.platform.cli.model.{EntrypointArgs, RoleAppArgs}
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.util.Try
import zio.{IO, Task, ZEnvironment, ZIO}

final class HarnessRegistry(adapters: Set[HarnessAdapter]) {
  require(adapters.map(_.harness) == Harness.all.toSet && adapters.size == Harness.all.size, "Exactly one adapter per supported harness required")
  def apply(harness: Harness): HarnessAdapter = adapters.find(_.harness == harness).get
}

final case class SupervisorConfig(settings: SupervisorSettings, project: ProjectConfig, profile: HarnessProfile,
  limits: ExecutionLimits, run: SupervisorRun, directory: Path, input: String, environment: Map[String, String]) {
  val owner: Scope = Scope(project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
  val endpoint: URI = URI.create(project.endpoint)
}

object SupervisorConfig {
  private val MaxConfigBytes = 64 * 1024
  private val MaxInputBytes = 192 * 1024
  private val MaxOutputBytes = 32 * 1024 * 1024
  private val CredentialMargin = Duration.ofMinutes(10)
  private val MaxCredentialLifetime = Duration.ofHours(24)
  def profile(value: HarnessSetting): HarnessProfile = HarnessProfile(value.harness, Path.of(value.executable), value.model, value.provider, value.version,
    value.providerExtensions.map(Path.of(_)), value.providerEnvironment)
  def limits(value: HostLimits): ExecutionLimits = ExecutionLimits(Duration.ofMillis(value.startupMillis), Duration.ofMillis(value.executionMillis),
    Duration.ofMillis(value.heartbeatMillis), Duration.ofMillis(value.graceMillis), Duration.ofMillis(value.killMillis), value.outputBytes)
  def within(value: HostLimits, ceiling: HostLimits): Unit = {
    limits(value)
    require(List(value.startupMillis -> ceiling.startupMillis, value.executionMillis -> ceiling.executionMillis,
      value.heartbeatMillis -> ceiling.heartbeatMillis, value.graceMillis -> ceiling.graceMillis, value.killMillis -> ceiling.killMillis,
      value.outputBytes.toLong -> ceiling.outputBytes.toLong).forall((actual, maximum) => actual <= maximum), "Child limits exceed the governing session's configured bounds")
  }
  def verifyProfile(config: SupervisorConfig, value: HarnessProfile): Unit = {
    val version = new BoundedHostCommand(HarnessEnvironment.isolated(value, config.environment), Duration.ofSeconds(10), 4096)
      .run(Path.of(config.run.repository), List(value.executable.toString, "--version"))
    require(version.exit == 0 && version.text.split("[\\s()]+").contains(value.version), "Installed harness version differs from its configured verified route")
  }
  def credentialLifetime(limits: ExecutionLimits): Duration = {
    val lifetime = limits.startup.plus(limits.execution).plus(limits.grace).plus(limits.kill).plus(CredentialMargin)
    require(lifetime.compareTo(MaxCredentialLifetime) <= 0, "Process deadline budget exceeds scoped credential lifetime")
    lifetime
  }
  def load(arguments: RoleAppArgs, context: CliContext, location: ProjectLocation, clock: Clock): Task[SupervisorConfig] = ZIO.attemptBlocking {
    val raw = arguments.roles.find(_.role == SupervisorRole.id).getOrElse(throw new IllegalArgumentException("Supervisor role arguments missing")).roleParameters.raw.toList
    val args = if (raw.headOption.contains("--")) raw.tail else raw
    require(args.size == 5, "cq run HARNESS --settings FILE --input FILE")
    val harness = Harness.all.find(_.toString.equalsIgnoreCase(args.head)).getOrElse(throw new IllegalArgumentException("Unknown governing harness"))
    val pairs = args.tail.grouped(2).map(values => values.head -> values(1)).toList
    require(pairs.map(_._1).toSet == Set("--settings", "--input"), "cq run requires exactly --settings FILE and --input FILE")
    val options = pairs.toMap
    val settings = HostFiles.read(context.directory.resolve(options("--settings")).normalize(), SupervisorSettings_JsonCodec, MaxConfigBytes)
    val profiles = settings.harnesses.map(SupervisorConfig.profile)
    require(profiles.nonEmpty && profiles.map(_.harness).distinct.size == profiles.size, "Harness settings must have unique routes")
    val profile = profiles.find(_.harness == harness).getOrElse(throw new IllegalArgumentException("Governing harness route is not configured"))
    val limits = SupervisorConfig.limits(settings.limits)
    require(limits.outputBytes <= MaxOutputBytes, "Native output retention exceeds 32 MiB per stream")
    credentialLifetime(limits)
    require(settings.checks.size <= 8 && settings.checks.map(_.name).distinct.size == settings.checks.size, "At most eight uniquely named validation checks are supported")
    settings.checks.foreach { check =>
      require(check.name.matches("[a-z][a-z0-9-]{0,49}") && check.command.nonEmpty && check.command.size <= 32 &&
        check.command.forall(value => value.nonEmpty && value.length <= 4096 && !value.contains('\u0000')) &&
        check.executionMillis > 0 && check.executionMillis <= settings.limits.executionMillis && check.outputBytes > 0 && check.outputBytes <= 1024 * 1024,
        "Invalid configured validation check")
    }
    require(settings.checks.map(value => HostFiles.encode(ValidationCheck_JsonCodec, value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum <= 16384,
      "Configured validation arguments exceed 16 KiB")
    val guardian = Path.of(settings.guardian)
    require(guardian.isAbsolute && guardian.normalize() == guardian && Files.isExecutable(guardian), "Explicit executable guardian required")
    val project = HostFiles.read(location.directory.resolve("project.json"), ProjectConfig_JsonCodec, MaxConfigBytes)
    val git = new BoundedHostCommand(GitEnvironment.isolated(context.environment), Duration.ofSeconds(10), MaxConfigBytes)
    def command(arguments: String*): String = {
      val value = git.run(context.directory, List("git") ++ arguments)
      require(value.exit == 0, "Supervisor requires a Git checkout with a committed base")
      value.text.trim
    }
    val repository = Path.of(command("rev-parse", "--show-toplevel")).toRealPath()
    val base = GitCommit(command("rev-parse", "--verify", "HEAD"))
    val stateRoot = Path.of(settings.stateRoot)
    HostFiles.directory(stateRoot)
    require(!stateRoot.toRealPath().startsWith(repository), "Supervisor state root must be outside the source checkout")
    val session = SessionId(UUID.randomUUID())
    val directory = stateRoot.resolve(session.value.toString)
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Governor, harness,
      profile.provider, profile.model, "CQ native collector 0.1.0", clock.millis())
    val run = SupervisorRun(project, assignment, attempt, profile.version, repository.toString, base)
    val input = HostFiles.text(context.directory.resolve(options("--input")).normalize(), MaxInputBytes)
    require(input.trim.nonEmpty, "Governing input cannot be empty")
    val version = new BoundedHostCommand(HarnessEnvironment.isolated(profile, context.environment), Duration.ofSeconds(10), 4096)
      .run(repository, List(profile.executable.toString, "--version"))
    require(version.exit == 0 && version.text.split("[\\s()]+").contains(profile.version), "Installed harness version differs from its configured verified route")
    SupervisorConfig(settings, project, profile, limits, run, directory, input, context.environment)
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

final class SupervisorProgram(config: SupervisorConfig, registry: HarnessRegistry, jobs: JobSupervisor, authority: SupervisorAuthority,
  local: LocalControlServer, access: LocalAccess, dispatch: DispatchController, schemas: McpSchemas, output: HarnessOutput, clock: Clock, context: CliContext) {
  private val MaxOutputBytes = 32 * 1024 * 1024
  private val MaxRecordBytes = 64 * 1024
  private val MaxSummaryCharacters = 8192
  private val MaxGaps = 32
  private val MaxGapCharacters = 300
  private val DeliveryEntriesPerBatch = 32
  private val Instructions = "You govern CQ work. Use CQ tools for domain state and only the capabilities exposed to this session. " +
    "Your input names the attached project, configured routes, process limits, validation checks and human request. Create or select the required task records and claim their exact member set. " +
    "Use the local dispatch tool to start a Worker using item revisions and handles only. The host owns prompt assembly, candidate capture and validation. Never read or compose child prompts or copy full results between children. " +
    "Poll Status with waitMillis 20000. When a worker result is ready, pass its handle as previous to a Reviewer request with the same member revisions and current fence. Prefer another configured harness for independent review. " +
    "Dispatch is sequential in this slice. Keep assignments distinct and use the compact status counts, next action and blockers. Explicit bounded CQ artifact reads are for necessary semantic drill-down. " +
    "Children cannot write CQ ledgers or integrate candidates. You own those decisions. Candidate integration is not exposed yet; report retained reviewed candidates and that remaining step accurately. " +
    "Do not claim a process or validation ran unless its host evidence exists. A completed child or accepted review does not establish final task acceptance. " +
    "Return exactly a JSON object with one string field, summary, describing the observed outcome and remaining work."

  def run: Task[Unit] = {
    val attempt = config.run.attempt
    val project = config.project.project
    val assets = config.directory.resolve("assets").resolve(attempt.id.value.toString)
    val payload = config.directory.resolve("payload").resolve(attempt.id.value.toString)
    for {
      prepared <- ZIO.attemptBlocking {
        HostFiles.directory(config.directory)
        HostFiles.immutable(config.directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, config.run), MaxRecordBytes)
        HostFiles.immutable(config.directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, config.settings), MaxRecordBytes)
        val collector = authority.collector
        val input = HostFiles.encode(GoverningInput_JsonCodec, GoverningInput(config.project,
          config.settings.harnesses.map(value => HarnessRoute(value.harness, value.model, value.provider)), config.settings.checks.map(_.name), config.settings.limits, config.input))
        val queue = new DeliveryQueue(config.directory.resolve("delivery"))
        val initial = DeliveryBatch(List(
          HostDelivery.Usage(HostUsageInput(project, HostUsage.Assign(config.run.assignment))),
          HostDelivery.Usage(HostUsageInput(project, HostUsage.Start(attempt))),
          HostDelivery.Artifact(ArtifactUpload(project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input, "application/json", input)),
          HostDelivery.Artifact(ArtifactUpload(project, NativeArtifacts.id(attempt.id, "prompt"), attempt.id, ArtifactKind.Prompt, "text/plain", Instructions)),
        ))
        queue.enqueue(0, initial)
        queue.flush(collector)
        val invocation = HarnessInvocation(Role.Governor, attempt.id, Instructions, schemas.schema("GoverningReport"),
          List(HarnessMcp(McpTarget.Domain, config.endpoint.resolve("/mcp"), authority.governorToken),
            HarnessMcp(McpTarget.Local, local.endpoint, access.issue(attempt.id, Role.Governor))), assets)
        val launch = registry(attempt.harness).launch(config.profile, invocation, config.environment)
        launch.install(assets)
        (collector, queue, JobCommand(launch.arguments, launch.environment, input, config.limits))
      }
      (collector, queue, command) = prepared
      _ <- jobs.start(config.owner, WorkspaceSpec(project, attempt.session, attempt.id, config.run.repository, config.run.base), command)
      record <- jobs.await(config.owner, attempt.id)
      _ <- dispatch.shutdown
      receipt <- ZIO.attemptBlocking {
        val stdout = if (Files.exists(payload.resolve("stdout"))) HostFiles.bytes(payload.resolve("stdout"), MaxOutputBytes) else Array.emptyByteArray
        val stderr = if (Files.exists(payload.resolve("stderr"))) HostFiles.bytes(payload.resolve("stderr"), MaxOutputBytes) else Array.emptyByteArray
        val (nativeId, stdoutArtifacts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
        val (_, stderrArtifacts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
        val collectedAt = math.max(attempt.startedAt, clock.millis())
        val usage = new HarnessUsage().collect(new ByteArrayInputStream(stdout),
          UsageCollectionRequest(attempt.id, attempt.harness, config.profile.version, UsageOrigin.Fresh, collectedAt, nativeId))
        val observed = JobOutcome.observed(record)
        val succeeded = observed.succeeded
        val report = Try {
          require(succeeded, observed.problem.getOrElse("Governing process did not complete successfully"))
          require(usage.terminalSeen && !usage.nativeFailure, "Governing native output did not complete successfully")
          val json = output.result(attempt.harness, stdout, assets)
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
        entries.grouped(DeliveryEntriesPerBatch).zipWithIndex.foreach { case (batch, index) => queue.enqueue(index + 1, DeliveryBatch(batch)) }
        val delivered = Try(queue.flush(collector)).toEither
        val pending = delivered.left.toOption.map(_ => "Operational usage/artifact delivery is pending; retain the session directory and retry cq job upload")
        val receipt = SupervisorReceipt(attempt.session, attempt.id, config.directory.toString, record.phase, succeeded,
          report.toOption.map(_.id), delivered.isRight, pending.orElse(problem))
        HostFiles.immutable(config.directory.resolve("receipt.json"), HostFiles.encode(SupervisorReceipt_JsonCodec, receipt), MaxRecordBytes)
        context.output.println(HostFiles.encode(SupervisorReceipt_JsonCodec, receipt))
        receipt
      }
      _ <- ZIO.attempt(require(receipt.problem.isEmpty, receipt.problem.getOrElse("Governing run failed")))
    } yield ()
  }
}

final class SupervisorRole(program: SupervisorProgram) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = program.run
}
object SupervisorRole extends RoleDescriptor {
  override val id = "supervisor"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("Own a local governing harness and its isolated jobs"), Some("cq run HARNESS --settings FILE --input FILE"), freeArgsAllowed = true)
}

object SupervisorPlugin extends PluginDef {
  include(new ModuleDef {
    include(new RoleModuleDef { makeRole[SupervisorRole] })
    make[SupervisorConfig].fromEffect(SupervisorConfig.load _)
    many[HarnessAdapter].add[ClaudeAdapter].add[CodexAdapter].add[PiAdapter]
    make[HarnessRegistry]
    make[HarnessOutput]
    make[ChildInstructions]
    make[WorkspaceReader]
    make[CandidateWorkspace]
    make[SupervisorAuthority].fromEffect(SupervisorAuthority.acquire _)
    make[LocalAccess]
    make[ChildRunner]
    make[DispatchController].fromResource[DispatchController.Resource]
    make[LocalControl]
    make[LocalControlServer].fromResource[LocalControlServer.Resource]
    make[SupervisorProgram]
    make[SupervisorWatchdog].fromResource[SupervisorWatchdog.Resource]
    make[ExecutionDriver].from[SupervisorDriver]
    make[WorkspaceService[IO]].from { (config: SupervisorConfig, clock: Clock) =>
      new WorkspaceService.Impl[IO](new GitWorkspaceRepository(config.directory.resolve("workspaces"),
        new BoundedHostCommand(GitEnvironment.isolated(config.environment), Duration.ofSeconds(10), 65536), clock))
    }
    make[JobSupervisor].fromResource[SupervisorJobs]
  })
}
