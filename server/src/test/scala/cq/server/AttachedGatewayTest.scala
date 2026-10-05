package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.DomainFailure
import cq.host.{OwnerLiveness, PeerLimits, ServerApi, StdioPeer}
import io.circe.{Json, parser}
import java.io.{BufferedReader, InputStreamReader, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import zio.{Runtime, Task, Unsafe}

final class AttachedGatewayLocal extends AnyWordSpec {
  private val LongInterval = Duration.ofSeconds(30)
  private val Context = BaboonCodecContext.Default
  private val schemas = new McpSchemas()
  private val project = ProjectId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

  private final class Api(answer: Command => Result) extends ServerApi {
    override def call(command: Command): Result = answer(command)
    override def usage(input: HostUsageInput): HostUsageResult = throw new IllegalStateException("Not a gateway operation")
    override def artifact(input: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Not a gateway operation")
    override def grant(input: GrantRequest): AccessToken = throw new IllegalStateException("Not a gateway operation")
    override def admit(input: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Not a gateway operation")
    override def integrate(input: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Not a gateway operation")
  }

  /** An initialized gateway on a real peer. Only the governing API is live: the tools exercised here reach nothing else. */
  private final class Session(frameBytes: Int, answer: Command => Result) extends AutoCloseable {
    private def uuid: UUID = UUID.randomUUID()
    private val assignment = Assignment(AssignmentId(uuid), project, Set.empty, Attribution.Unattributed, None, None)
    private val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Claude,
      "fixture-provider", "fixture-model", "fixture", 0, UsagePhase.Govern)
    private val settings = ProjectConfig(project, "http://localhost", "Attached gateway")
    private val config = SupervisorConfig(null, settings, null, null,
      SupervisorRun(settings, assignment, attempt, "fixture", "/nonexistent", GitCommit("0" * 40), SessionOwnership.Attached), Path.of("/nonexistent"), "", None, Map.empty)
    private val api = new Api(answer)
    private val gateway = new AttachedGateway(config, SupervisorAuthority(api, api, api, AccessToken("governor", 0)), schemas, null, null, null, null, null,
      new SessionClaims(config.owner, api, logstage.IzLogger.NullLogger))
    private val input = new PipedInputStream(8192)
    private val client = new PipedOutputStream(input)
    private val response = new PipedInputStream(8192)
    private val output = new PipedOutputStream(response)
    private val lines = new BufferedReader(new InputStreamReader(response, UTF_8))
    val peer = new StdioPeer(input, output, new OwnerLiveness { override def alive: Boolean = true },
      PeerLimits(LongInterval, LongInterval, LongInterval, LongInterval, frameBytes, 8), () => ())
    private var sequence = 0
    def run[A](task: Task[A]): A = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    /** What the host loop does with one request: handle it, send the answer, and return the frame the owner received. */
    def exchange(method: String, params: Json): Json = {
      sequence += 1
      val answer = run(gateway.handle(peer, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(sequence),
        "method" -> Json.fromString(method), "params" -> params))).get
      peer.send(answer)
      val received = parser.parse(lines.readLine()).fold(throw _, identity)
      assert(received == answer && received.hcursor.get[Int]("id") == Right(sequence) && peer.reason.isEmpty)
      received
    }
    def tool(name: String, arguments: Json): Json = exchange("tools/call", Json.obj("name" -> Json.fromString(name), "arguments" -> arguments))
      .hcursor.downField("result").focus.get
    exchange("initialize", Json.obj("protocolVersion" -> Json.fromString("2025-06-18")))
    override def close(): Unit = { peer.close(); client.close(); response.close(); input.close(); output.close() }
  }

  private def read(selection: String): Json = parser.parse(s"""{"project":{"value":"${project.value}"},"selection":$selection}""").fold(throw _, identity)
  private def fault(result: Json): Json = {
    assert(result.hcursor.get[Boolean]("isError") == Right(true), result.noSpaces.take(300))
    val structured = result.hcursor.downField("structuredContent").focus.get
    assert(result.hcursor.downField("content").downArray.get[String]("text") == Right(structured.noSpaces))
    structured.hcursor.downField("Failed").downField("fault").focus.get
  }

  "Attached gateway (Behavioral Active Blackbox Group)" should {
    "answer a response above the frame bound with one tool error and keep serving" in {
      val FrameBytes = 16384
      var description = "x" * (2 * FrameBytes)
      def catalog = HelpCatalog(List(CatalogCommand("fixture", "Fixture", description, Nil, CatalogPrompt("fixture.md", "text"), Nil, Nil)), Nil)
      val session = new Session(FrameBytes, _ => Result.Catalog(catalog))
      try {
        val message = fault(session.tool("read", read("""{"Catalog":{"part":{"All":{}}}}"""))).hcursor.downField("Limit").get[String]("message").fold(throw _, identity)
        assert(message.contains(s"$FrameBytes-byte") && message.contains("narrow"), message)
        // The same answer follows a tool that changed something, so it must not say that nothing happened.
        assert(message.contains("The operation itself was performed") && message.contains("read the state back") && !message.contains("Nothing was returned"), message)
        val actual = "is (\\d+) bytes".r.findFirstMatchIn(message).map(_.group(1).toInt)
        assert(actual.exists(_ > 4 * FrameBytes), message)
        description = "small"
        val served = session.tool("read", read("""{"Catalog":{"part":{"All":{}}}}"""))
        assert(served.hcursor.get[Boolean]("isError") == Right(false))
        assert(served.hcursor.downField("structuredContent").focus.exists(_.noSpaces.contains("small")))
      } finally session.close()
    }

    "serve every help catalog command and agent within the production frame bound and refuse the whole catalog with the way to narrow it" in {
      val catalog = new CatalogRead(schemas)
      val session = new Session(AttachedGateway.FrameBytes, {
        case Command.Read(ReadInput(_, ReadSelection.Catalog(part))) =>
          try Result.Catalog(catalog.select(part)) catch { case DomainFailure(fault) => Result.Failed(fault) }
        case other => fail(s"Unexpected command $other")
      })
      def selected(part: CatalogSelection): Json =
        session.tool("read", read(s"""{"Catalog":{"part":${CatalogSelection_JsonCodec.encode(Context, part).noSpaces}}}"""))
      def served(part: CatalogSelection): HelpCatalog = {
        val result = selected(part)
        assert(result.hcursor.get[Boolean]("isError") == Right(false), part.toString)
        Result_JsonCodec.decode(Context, result.hcursor.downField("structuredContent").focus.get) match {
          case Right(Result.Catalog(value)) => value
          case other => fail(s"Unexpected catalog result $other")
        }
      }
      try {
        catalog.value.commands.foreach(command => assert(served(CatalogSelection.OfCommand(command.command)) == HelpCatalog(List(command), Nil)))
        catalog.value.agents.foreach(agent => assert(served(CatalogSelection.OfAgent(agent.work)) == HelpCatalog(Nil, List(agent))))
        val whole = fault(selected(CatalogSelection.All())).hcursor.downField("Limit").get[String]("message").fold(throw _, identity)
        assert(whole.contains(s"${AttachedGateway.FrameBytes}-byte") && whole.contains("narrow"), whole)
        val missing = fault(selected(CatalogSelection.OfCommand("absent"))).hcursor.downField("Missing").get[String]("message").fold(throw _, identity)
        assert(catalog.value.commands.forall(command => missing.contains(command.command)), missing)
      } finally session.close()
    }

    "tell the caller of a tool whose arguments cannot be decoded which tool it was and what its input schema expects" in {
      val session = new Session(AttachedGateway.FrameBytes, other => fail(s"Unexpected command $other"))
      def refused(tool: String, arguments: String): String =
        fault(session.tool(tool, parser.parse(arguments).fold(throw _, identity))).hcursor.downField("Invalid").get[String]("message").fold(throw _, identity)
      def tags(input: String): List[String] = schemas.schema(input).hcursor.get[List[Json]]("oneOf").fold(throw _, identity)
        .flatMap(_.hcursor.get[List[String]]("required").fold(throw _, identity))
      try {
        // The shape a weaker model sent three times (D149): the alternative's value as a string holding JSON.
        val context = refused("session", """{"Context":"{}"}""")
        assert(context.contains("\"session\"") && context.contains("do not match its input schema") && context.contains("object expected"), context)
        assert(tags("SessionCommand") == List("Context", "Workflow", "Bind", "Driver") && context.contains("Context, Workflow, Bind, Driver"), context)
        val dispatch = refused("dispatch", """{"Status":"{}"}""")
        assert(dispatch.contains("\"dispatch\"") && dispatch.contains(tags("DispatchCommand").mkString(", ")), dispatch)
        val domain = refused("read", """{"project":"p"}""")
        assert(domain.contains("\"read\"") && domain.contains("do not match its input schema") && domain.contains("project, selection"), domain)
        val (body, failed) = session.run(new LocalControl(null, null, null, null, null, null, schemas, null, null)
          .call(LocalCapability(AttemptId(UUID.randomUUID()), Role.Worker), "workspace", parser.parse("""{"Read":"{}"}""").fold(throw _, identity)))
        val workspace = body.hcursor.downField("Failed").downField("fault").downField("Invalid").get[String]("message").fold(throw _, identity)
        assert(failed && workspace.contains("\"workspace\"") && workspace.contains("Entries, Read, MergeReport"), workspace)
        // Every caller of the fault text, the HTTP transport included, gets the codec's detail bounded.
        val long = schemas.mismatch("read", schemas.schema("ReadInput"), "#" * 1000)
        assert(long.count(_ == '#') == 300 && long.contains("project, selection"), long)
      } finally session.close()
    }
  }
}
