package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import cq.host.HttpServerApi
import java.io.PrintStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Duration
import java.util.UUID
import scala.util.Using
import zio.{Task, ZIO}

final case class CliContext(environment: Map[String, String], directory: Path, output: PrintStream)

final class Cli(context: CliContext, location: ProjectLocation, upload: SessionUpload) {
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
    val token = environment.getOrElse("CQ_TOKEN", throw new IllegalArgumentException("CQ_TOKEN is required"))
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

  def run(args: List[String]): Task[Unit] = args match {
    case List("job", "upload", "--session", value) => upload.run(directory.resolve(value).normalize())
    case _ => ZIO.attemptBlocking(runSynchronous(args))
  }

  private def runSynchronous(args: List[String]): Unit = args match {
    case "init" :: rest =>
      val opts = options(rest, Set("--endpoint", "--project-id", "--name"))
      val location = configDirectory
      val initialized = locked(location) {
        val file = location.resolve("project.json")
        val existing = if (Files.exists(file)) Some(configuration(location)) else None
        val project = opts.get("--project-id").map(v => ProjectId(UUID.fromString(v))).orElse(existing.map(_.project)).getOrElse(ProjectId(UUID.randomUUID()))
        require(existing.forall(_.project == project), "Project identity differs; use a separate checkout to initialize a different project")
        val endpoint = validateEndpoint(opts.get("--endpoint").orElse(existing.map(_.endpoint)).orElse(environment.get("CQ_ENDPOINT"))
          .getOrElse(throw new IllegalArgumentException("First init requires --endpoint or CQ_ENDPOINT")))
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
      output.println(Wire.encode(Result_JsonCodec, initialized))
      output.println(s"Configuration: ${location.resolve("project.json")}")
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
      output.println(Wire.encode(Result_JsonCodec, request(config, actorSession, command)))
    case "proposal" :: action :: result :: Nil if Set("preview", "apply")(action) =>
      val location = configDirectory
      val (config, actorSession) = locked(location)((configuration(location), session(location)))
      val handle = ArtifactId(UUID.fromString(result))
      val command = if (action == "preview") Command.Read(ReadInput(config.project, ReadSelection.Proposal(handle)))
        else Command.ApplyProposal(ProposalApplyInput(config.project, handle))
      output.println(Wire.encode(Result_JsonCodec, request(config, actorSession, command)))
    case "status" :: rest =>
      val mode = rest.headOption.filter(Set("audit", "costs", "attempts", "outcomes")).getOrElse("summary")
      val scopes = Set("--task", "--cohort", "--session")
      val allowed = mode match {
        case "summary" => scopes
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
        case "audit" => UsageSelection.Audit(filter, opts.get("--after").map(_.toLong).getOrElse(0L), limit)
        case "costs" => UsageSelection.Costs(filter, opts.get("--after").map(v => Wire.decode(CostGroup_JsonCodec, v)), opts.get("--snapshot").map(_.toLong), limit)
        case "attempts" => UsageSelection.Attempts(filter, opts.get("--after").map(v => AttemptId(UUID.fromString(v))), opts.get("--snapshot").map(_.toLong), limit)
        case "outcomes" => UsageSelection.Outcomes(AttemptId(UUID.fromString(opts.getOrElse("--attempt", throw new IllegalArgumentException("status outcomes requires --attempt UUID")))), opts.get("--after").map(_.toLong).getOrElse(0L), limit)
      }
      output.println(Wire.encode(Result_JsonCodec, request(config, actorSession, Command.Usage(UsageInput(config.project, selection)))))
    case List("web") => output.println(configuration(configDirectory).endpoint)
    case Nil | List("--help") => output.println("cq serve | init [--endpoint URL] [--project-id UUID] [--name TEXT] | web | run HARNESS --settings FILE --input FILE | job upload --session DIR | query [--query TEXT] [--complete UTF16_OFFSET] [--roots T1,M1] [--after T1 --snapshot CURSOR] [--limit N] | proposal preview|apply RESULT_UUID | status [audit|costs|attempts|outcomes] [--task T1|--cohort UUID|--session UUID] [--attempt UUID] [--after CURSOR] [--snapshot N] [--limit N]")
    case _ => throw new IllegalArgumentException("Unknown command; use cq --help")
  }
}
