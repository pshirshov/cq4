package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import cq.host.{DriverAssets, DriverEntry, DriverHook, HttpServerApi, SessionWait, WorkflowAssets}
import java.io.{InputStream, PrintStream}
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Duration
import java.util.UUID
import scala.util.Using
import zio.{Task, ZIO}

final case class CliContext(environment: Map[String, String], directory: Path, output: PrintStream, input: InputStream)

/** `cq wait` has said what it found and ends with a code other than success. */
final class WaitFinished(val exit: Int) extends RuntimeException

final class Cli(context: CliContext, location: ProjectLocation, upload: SessionUpload, workflows: WorkflowAssets, attached: AttachedAssets) {
  private val environment = context.environment
  private val directory = context.directory
  private val output = context.output
  private val RequestTimeout = Duration.ofSeconds(30)
  private val DefaultPageSize = 50

  private def options(args: List[String], allowed: Set[String]): Map[String, String] = {
    require(args.size % 2 == 0, "Options require values")
    val pairs = args.grouped(2).map { pair =>
      require(allowed.contains(pair.head), s"Unknown option ${pair.head}")
      pair.head -> pair(1)
    }.toList
    require(pairs.map(_._1).distinct.size == pairs.size, "Repeated option")
    pairs.toMap
  }
  private def nativeHarness(value: String, kind: String): Harness =
    Harness.all.find(_.toString.toLowerCase == value).getOrElse(throw new IllegalArgumentException(s"Unknown $kind harness"))
  private def configDirectory: Path = location.directory
  private def validateEndpoint(value: String): String = {
    val uri = URI.create(value)
    require(Set("http", "https").contains(uri.getScheme) && uri.getHost != null && uri.getUserInfo == null &&
      uri.getRawQuery == null && uri.getRawFragment == null && (uri.getPath.isEmpty || uri.getPath == "/"), "Endpoint must be an HTTP(S) origin")
    value.stripSuffix("/")
  }
  private def configuration(location: Path): ProjectConfig = Wire.decode(ProjectConfig_JsonCodec, Files.readString(location.resolve("project.json")))
  private def session(location: Path): SessionId = {
    val file = location.resolve("session.json")
    if (!Files.exists(file)) atomicWrite(file, Wire.encode(SessionId_JsonCodec, SessionId(UUID.randomUUID())))
    Wire.decode(SessionId_JsonCodec, Files.readString(file))
  }
  private def atomicWrite(path: Path, value: String): Unit = {
    val temporary = Files.createTempFile(path.getParent, ".cq-", ".json")
    try {
      Files.writeString(temporary, value + "\n")
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(temporary)
  }
  private def locked[A](location: Path)(operation: => A): A = {
    Files.createDirectories(location)
    Using.resource(FileChannel.open(location.resolve("identity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { channel =>
      Using.resource(channel.lock())(_ => operation)
    }
  }
  private def request(config: ProjectConfig, sessionId: SessionId, command: Command): Result = {
    val token = cq.host.HostCredential.read(environment)
    new HttpServerApi(URI.create(validateEndpoint(config.endpoint)), token, sessionId, RequestTimeout).call(command) match {
      case Result.Failed(fault) => throw new IllegalArgumentException(Wire.encode(Fault_JsonCodec, fault))
      case result => result
    }
  }
  private def item(project: ProjectId, value: String): ItemId = {
    val found = Ledger.all.toList.sortBy(l => -LedgerPolicy.prefix(l).length).find(l => value.startsWith(LedgerPolicy.prefix(l)))
      .getOrElse(throw new IllegalArgumentException("Unknown item prefix"))
    val number = value.stripPrefix(LedgerPolicy.prefix(found)).toLong
    require(number > 0, "Item number must be positive")
    ItemId(project, found, number)
  }
  private def archiveClient(opts: Map[String, String]): ArchiveClient = {
    val file = configDirectory.resolve("project.json")
    val saved = if (Files.exists(file)) Some(configuration(file.getParent).endpoint) else None
    val endpoint = validateEndpoint(opts.get("--endpoint").orElse(saved).orElse(environment.get("CQ_ORIGIN"))
      .orElse(environment.get("CQ_ENDPOINT")).getOrElse(throw new IllegalArgumentException("Archive command requires --endpoint, saved endpoint or CQ_ORIGIN")))
    new ArchiveClient(URI.create(endpoint), cq.host.HostCredential.read(environment), SessionId(UUID.randomUUID()))
  }

  def run(args: List[String]): Task[Unit] =
    if (CliHelp.requested(args)) ZIO.attempt(output.println(CliHelp.render(args)))
    else ZIO.attempt(CliArguments.parse(args)).flatMap { parsed => parsed.values match {
      case List("job", "upload", "--session", value) =>
        ZIO.attempt(require(parsed.format == CliFormat.Human, "job upload reports operator text; --json is not supported")) *> upload.run(directory.resolve(value).normalize())
      case values => ZIO.attemptBlocking(runSynchronous(values, new CliOutput(output, parsed.format, values)))
    }}

  private def runSynchronous(args: List[String], renderer: CliOutput): Unit = args match {
    case "backup" :: project :: file :: rest =>
      val client = archiveClient(options(rest, Set("--endpoint")))
      val path = directory.resolve(file).normalize()
      renderer.archive(client.backup(ProjectId(UUID.fromString(project)), path), "Saved", path)
    case "restore" :: file :: rest =>
      val client = archiveClient(options(rest, Set("--endpoint")))
      val path = directory.resolve(file).normalize()
      renderer.archive(client.restore(path), "Restored", path)
    case "configure" :: harness :: rest =>
      val flags = rest.reverse.takeWhile(Set("--replace", DriverAssets.StatusLineFlag))
      require(flags.distinct.size == flags.size, "Repeated option")
      val replace = flags.contains("--replace")
      val opts = options(rest.dropRight(flags.size), Set("--settings", "--executable", "--directory"))
      val settings = opts.get("--settings").orElse(environment.get("CQ_SETTINGS"))
        .getOrElse(throw new IllegalArgumentException("configure requires --settings FILE or CQ_SETTINGS"))
      val native = nativeHarness(harness, "attached")
      val executable = opts.get("--executable").getOrElse(ProcessHandle.current().info().command().orElseThrow())
      require(Path.of(executable).getFileName.toString != "java", "JVM configure requires --executable pointing to an installed CQ binary or exec wrapper")
      renderer.paths(attached.write(native, opts.get("--directory").fold(directory)(value => directory.resolve(value).normalize()),
        directory.resolve(settings).normalize(), directory.resolve(executable).normalize(), replace, flags.contains(DriverAssets.StatusLineFlag)))
    // The CQ hook entry point of Claude Code and Codex. It always exits 0 so that a CQ error never blocks the harness; the error is in the output.
    case List("hook", harness, event) =>
      val hook = new DriverHook(() => {
        val location = configDirectory
        val (config, actorSession) = locked(location)((configuration(location), session(location)))
        new DriverEntry(new HttpServerApi(URI.create(validateEndpoint(config.endpoint)), cq.host.HostCredential.read(environment), actorSession, RequestTimeout), config.project)
      }, new cq.host.SessionViews {
        // Located only for a Stop of a driven session: the other hook events ask nothing about hosts.
        private lazy val sessions = new cq.host.AttachedSessions(configDirectory)
        override def view(session: SessionId): cq.host.HostView = sessions.view(session)
        override def asked(session: SessionId): Option[String] = sessions.asked(session)
        override def ask(session: SessionId, units: Option[String]): Unit = sessions.ask(session, units)
        override def announce(session: SessionId): List[QuestionEnd] = sessions.announce(session)
      })
      output.print(hook.run(harness, event, context.input.readNBytes(DriverHook.MaxInputBytes + 1)))
      output.flush()
    case "commands" :: "export" :: harness :: rest =>
      val replace = rest.lastOption.contains("--replace")
      val opts = options(if (replace) rest.dropRight(1) else rest, Set("--directory"))
      require(opts.keySet == Set("--directory"), "Command export requires --directory DIR [--replace]")
      val native = nativeHarness(harness, "command")
      renderer.paths(workflows.writeCommands(native, directory.resolve(opts("--directory")).normalize(), replace))
    case "assets" :: "export" :: harness :: rest =>
      val opts = options(rest, Set("--directory", "--project-directory", "--settings", "--executable"))
      require(opts.keySet == Set("--directory", "--project-directory", "--settings", "--executable"), "Asset export requires --directory, --project-directory, --settings and --executable")
      renderer.paths(attached.exportAssets(nativeHarness(harness, "asset"), directory.resolve(opts("--directory")).normalize(),
        directory.resolve(opts("--project-directory")).normalize(), directory.resolve(opts("--settings")).normalize(), directory.resolve(opts("--executable")).normalize()))
    case "doctor" :: "commands" :: harness :: rest =>
      val opts = options(rest, Set("--directory"))
      val root = opts.get("--directory").fold(directory)(value => directory.resolve(value).normalize())
      val report = new CommandDoctor(new FileCommandAssetReader, workflows).inspect(nativeHarness(harness, "command"), root)
      renderer.doctor(report)
      if (!report.current) throw new CommandAssetsNeedAttention
    case "doctor" :: "server" :: rest =>
      val settled = rest.lastOption.contains("--require-settled")
      val opts = options(if (settled) rest.dropRight(1) else rest, Set("--endpoint"))
      val endpoint = opts.get("--endpoint").orElse(environment.get("CQ_ORIGIN")).orElse(environment.get("CQ_ENDPOINT"))
        .getOrElse(throw new IllegalArgumentException("Server doctor requires --endpoint URL or CQ_ORIGIN"))
      val report = new InstallationDoctor(new HttpInstallationReader, SchemaIdentity.current(), ProducingBuild.value).server(URI.create(validateEndpoint(endpoint)), environment, settled)
      renderer.installation(report)
      if (!report.current) throw new InstallationNeedsAttention
    case "doctor" :: "harness" :: harness :: rest =>
      val opts = options(rest, Set("--directory", "--settings", "--executable", "--readonly-home", "--harness-config", "--trust-report"))
      require(Set("--settings", "--executable", "--readonly-home").subsetOf(opts.keySet), "Harness doctor requires --settings, --executable and --readonly-home")
      def path(key: String): Path = directory.resolve(opts(key)).normalize()
      val report = new HarnessDoctor(attached, new FileCommandAssetReader).inspect(nativeHarness(harness, "doctor"),
        opts.get("--directory").fold(directory)(value => directory.resolve(value).normalize()), path("--settings"), path("--executable"), path("--readonly-home"),
        opts.get("--harness-config").map(value => directory.resolve(value).normalize()), opts.get("--trust-report").map(value => directory.resolve(value).normalize()), environment)
      renderer.installation(report)
      if (!report.current) throw new InstallationNeedsAttention
    // Reads the project file, the operator credential, the server's agent configuration and the settings file; writes nothing.
    case "doctor" :: "agents" :: harness :: rest =>
      val opts = options(rest, Set("--directory", "--settings"))
      require(opts.contains("--settings"), "Agents doctor requires --settings FILE")
      val checkout = opts.get("--directory").fold(location)(value => new ProjectLocation(context.copy(directory = directory.resolve(value).normalize())))
      val report = new AgentsDoctor(new HttpAgentsReader).inspect(nativeHarness(harness, "doctor"), checkout.directory.resolve("project.json"),
        directory.resolve(opts("--settings")).normalize(), environment)
      renderer.agents(report)
      if (!report.current) throw new InstallationNeedsAttention
    // Writes an agent configuration to start from. No session reads a settings file for a model in its place.
    case "agents" :: "init" :: rest =>
      val opts = options(rest, Set("--settings", "--save"))
      val settings = opts.get("--settings").orElse(environment.get("CQ_SETTINGS"))
        .getOrElse(throw new IllegalArgumentException("agents init requires --settings FILE or CQ_SETTINGS"))
      val (text, note) = AgentsInit.starting(directory.resolve(settings).normalize())
      renderer.starter(text, note, opts.get("--save").map { layer =>
        val location = configDirectory
        val (config, actorSession) = locked(location)((configuration(location), session(location)))
        layer -> AgentsInit.save(request(config, actorSession, _), config.project, layer, text)
      })
    case "init" :: rest =>
      val opts = options(rest, Set("--endpoint", "--project-id", "--name"))
      val location = configDirectory
      val initialized = locked(location) {
        val file = location.resolve("project.json")
        val existing = if (Files.exists(file)) Some(configuration(location)) else None
        val project = opts.get("--project-id").map(v => ProjectId(UUID.fromString(v))).orElse(existing.map(_.project)).getOrElse(ProjectId(UUID.randomUUID()))
        require(existing.forall(_.project == project), "Project identity differs; use a separate checkout to initialize a different project")
        val endpoint = validateEndpoint(opts.get("--endpoint").orElse(existing.map(_.endpoint))
          .orElse(environment.get("CQ_ORIGIN")).orElse(environment.get("CQ_ENDPOINT"))
          .getOrElse(throw new IllegalArgumentException("First init requires --endpoint, CQ_ORIGIN or CQ_ENDPOINT")))
        val name = opts.get("--name").orElse(existing.map(_.name)).getOrElse(directory.getFileName.toString)
        require(name.trim.nonEmpty && name.length <= LedgerPolicy.MaxTitle, "Invalid project name")
        val config = ProjectConfig(project, endpoint, name)
        if (existing.isEmpty) atomicWrite(file, Wire.encode(ProjectConfig_JsonCodec, config))
        val actorSession = session(location)
        val attached = request(config, actorSession, Command.Initialize(config)) match {
          case Result.Initialized(value) => value
          case other => throw new IllegalStateException(s"Unexpected initialization result: $other")
        }
        val result = opts.get("--name") match {
          case Some(value) if value != attached.name => request(config, actorSession, Command.RenameProject(project, attached.revision, value))
          case _ => Result.Initialized(attached)
        }
        val current = result match {
          case Result.Initialized(value) => value
          case other => throw new IllegalStateException(s"Unexpected rename result: $other")
        }
        atomicWrite(file, Wire.encode(ProjectConfig_JsonCodec, config.copy(name = current.name)))
        result
      }
      renderer.result(initialized)
      renderer.configuration(location.resolve("project.json"))
    case "query" :: rest =>
      val opts = options(rest, Set("--query", "--complete", "--roots", "--after", "--snapshot", "--limit"))
      val location = configDirectory
      val (config, actorSession) = locked(location)((configuration(location), session(location)))
      val query = opts.getOrElse("--query", "")
      val limit = opts.get("--limit").map(_.toInt).getOrElse(DefaultPageSize)
      val command = if (opts.contains("--roots")) {
        require(!opts.contains("--query") && !opts.contains("--complete"), "Workset roots cannot be combined with query text or completion")
        val roots = opts("--roots")
        Command.Graph(GraphInput(config.project, if (roots.isEmpty) Set.empty else roots.split(",", -1).map(item(config.project, _)).toSet,
          opts.get("--after").map(item(config.project, _)), opts.get("--snapshot").map(Wire.decode(WorksetSnapshot_JsonCodec, _)), limit))
      } else opts.get("--complete") match {
        case Some(cursor) =>
          require(!opts.contains("--after") && !opts.contains("--snapshot"), "Query completion does not accept page continuation")
          Command.Read(ReadInput(config.project, ReadSelection.QueryComplete(query, cursor.toInt, limit)))
        case None => Command.Search(SearchInput(config.project, query, opts.get("--after").map(item(config.project, _)),
          opts.get("--snapshot").map(v => ChangeCursor(v.toLong)), limit))
      }
      renderer.result(request(config, actorSession, command))
    case "proposal" :: action :: result :: Nil if Set("preview", "apply")(action) =>
      val location = configDirectory
      val (config, actorSession) = locked(location)((configuration(location), session(location)))
      val handle = ArtifactId(UUID.fromString(result))
      val command = if (action == "preview") Command.Read(ReadInput(config.project, ReadSelection.Proposal(handle)))
        else Command.ApplyProposal(ProposalApplyInput(config.project, handle))
      renderer.result(request(config, actorSession, command))
    case "status" :: rest =>
      val mode = rest.headOption.filter(Set("phases", "audit", "costs", "attempts", "outcomes")).getOrElse("summary")
      val scopes = Set("--task", "--cohort", "--session")
      val allowed = mode match {
        case "summary" | "phases" => scopes
        case "audit" => scopes ++ Set("--after", "--limit")
        case "costs" => scopes ++ Set("--after", "--snapshot", "--limit")
        case "attempts" => scopes ++ Set("--after", "--snapshot", "--limit")
        case "outcomes" => Set("--attempt", "--after", "--limit")
      }
      val opts = options(if (mode == "summary") rest else rest.tail, allowed)
      require(scopes.count(opts.contains) <= 1, "Choose one usage scope")
      val location = configDirectory
      val (config, actorSession) = locked(location)((configuration(location), session(location)))
      val filter = opts.get("--task").map(v => UsageFilter.TaskOnly(item(config.project, v)))
        .orElse(opts.get("--cohort").map(v => UsageFilter.CohortOnly(UUID.fromString(v))))
        .orElse(opts.get("--session").map(v => UsageFilter.SessionOnly(SessionId(UUID.fromString(v))))).getOrElse(UsageFilter.ProjectAll())
      val limit = opts.get("--limit").map(_.toInt).getOrElse(DefaultPageSize)
      val selection = mode match {
        case "summary" => UsageSelection.Summary(filter)
        case "phases" => UsageSelection.Phases(filter)
        case "audit" => UsageSelection.Audit(filter, opts.get("--after").map(_.toLong).getOrElse(0L), limit)
        case "costs" => UsageSelection.Costs(filter, opts.get("--after").map(v => Wire.decode(CostGroup_JsonCodec, v)), opts.get("--snapshot").map(_.toLong), limit)
        case "attempts" => UsageSelection.Attempts(filter, opts.get("--after").map(v => AttemptId(UUID.fromString(v))), opts.get("--snapshot").map(_.toLong), limit)
        case "outcomes" => UsageSelection.Outcomes(AttemptId(UUID.fromString(opts.getOrElse("--attempt", throw new IllegalArgumentException("status outcomes requires --attempt UUID")))), opts.get("--after").map(_.toLong).getOrElse(0L), limit)
      }
      renderer.result(request(config, actorSession, Command.Usage(UsageInput(config.project, selection))))
    // Reads the session directory only: no server, no credential and no project configuration.
    case "wait" :: rest =>
      require(rest.size % 2 == 0, "Options require values")
      val pairs = rest.grouped(2).map(pair => pair.head -> pair(1)).toList
      val kinds = SessionUnitKind.all.map(kind => "--" + kind.toString.toLowerCase -> kind).toMap
      require(pairs.forall((option, _) => option == "--session" || kinds.contains(option)) && pairs.count(_._1 == "--session") <= 1,
        "wait accepts --session DIR once and --attempt, --integration, --combination and --revalidation ID, each any number of times")
      // Without a directory, the session is that of the one CQ host of this checkout that runs.
      val session = pairs.collectFirst { case ("--session", value) => directory.resolve(value).normalize() }.getOrElse {
        new cq.host.AttachedSessions(configDirectory).running match {
          case List(only) => Path.of(only.directory)
          case Nil =>
            output.println("No CQ host of this checkout is running. Restart the harness session; cq job upload --session DIR recovers what a host retained")
            throw new WaitFinished(SessionWait.HostGoneExit)
          case several =>
            output.println(s"${several.size} CQ hosts of this checkout are running; name the session to wait on with --session DIR: " + several.map(_.directory).mkString(", "))
            throw new WaitFinished(SessionWait.SeveralHostsExit)
        }
      }
      val named = pairs.collect { case (option, value) if kinds.contains(option) => kinds(option) -> UUID.fromString(value) }
      val outcome = try new SessionWait(session, () => Thread.sleep(SessionWait.PollMillis)).await(named)
        catch { case error: SessionWait.NotASession => output.println(error.getMessage); throw new WaitFinished(SessionWait.NotASessionExit) }
      renderer.waited(session, outcome)
      if (outcome.isInstanceOf[WaitOutcome.HostGone]) throw new WaitFinished(SessionWait.HostGoneExit)
    case List("web") => renderer.endpoint(configuration(configDirectory).endpoint)
    case _ => throw new IllegalArgumentException("Unknown command; use cq --help")
  }
}
