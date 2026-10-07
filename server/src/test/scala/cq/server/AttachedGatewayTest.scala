package cq.server

import cq.api.*
import cq.host.{AttachedCodexUsage, CodexRollout, DispatchWaits, OwnerLiveness, PeerLimits, ServerApi, StdioPeer, WorkflowAssets}
import io.circe.{Json, parser}
import java.io.{BufferedReader, InputStreamReader, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.wordspec.AnyWordSpec
import zio.{Runtime, Task, Unsafe, ZIO}

final class AttachedGatewayLocal extends AnyWordSpec {
  private val LongInterval = Duration.ofSeconds(30)
  private val InputSchemaBytes = 46200
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
  private final class Session(frameBytes: Int, harness: Harness, answer: Command => Result) extends AutoCloseable {
    def this(frameBytes: Int, answer: Command => Result) = this(frameBytes, Harness.Claude, answer)
    private def uuid: UUID = UUID.randomUUID()
    private val assignment = Assignment(AssignmentId(uuid), project, Set.empty, Attribution.Unattributed, None, None)
    private val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, harness,
      "fixture-provider", "fixture-model", "fixture", 0, UsagePhase.Govern, None)
    private val settings = ProjectConfig(project, "http://localhost", "Attached gateway")
    private val config = SupervisorConfig(null, settings, null, null,
      SupervisorRun(settings, assignment, attempt, "fixture", "/nonexistent", GitCommit("0" * 40), SessionOwnership.Attached), Path.of("/nonexistent"), "", None, Map("HOME" -> "/nonexistent"))
    private val api = new Api(answer)
    private val gateway = new AttachedGateway(config, SupervisorAuthority(api, api, api, AccessToken("governor", 0)), schemas, null, null, null,
      new AttachedCodexUsage(Path.of("/nonexistent"), config.run, new CodexRollout, java.time.Clock.systemUTC()), null,
      new SessionClaims(config.owner, api, logstage.IzLogger.NullLogger), WaitCommand(Some("/opt/cq/bin/cq")))
    private val input = new PipedInputStream(8192)
    private val client = new PipedOutputStream(input)
    private val response = new PipedInputStream(8192)
    private val output = new PipedOutputStream(response)
    private val lines = new BufferedReader(new InputStreamReader(response, UTF_8))
    val peer = new StdioPeer(input, output, new OwnerLiveness { override def alive: Boolean = true },
      PeerLimits(LongInterval, LongInterval, LongInterval, frameBytes, 8), () => ())
    private var sequence = 0
    /** How often the gateway asked for the session to be recorded, and whether the next request for it fails. */
    val governing = new AtomicInteger(0)
    @volatile var refused = false
    def run[A](task: Task[A]): A = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    /** What the host loop does with one request: handle it, send the answer, and return the frame the owner received. */
    def exchange(method: String, params: Json): Json = {
      sequence += 1
      val answer = run(gateway.handle(peer, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(sequence),
        "method" -> Json.fromString(method), "params" -> params), ZIO.attempt { governing.incrementAndGet(); if (refused) { refused = false; throw new IllegalStateException("Fixture server refused the registration") } })).get
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

  "Attached request deadlines (Behavioral Active Blackbox Atomic)" should {
    def call(name: String, arguments: String): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1), "method" -> Json.fromString("tools/call"),
      "params" -> Json.obj("name" -> Json.fromString(name), "arguments" -> parser.parse(arguments).fold(throw _, identity)))
    val id = """{"value":"00000000-0000-4000-8000-000000000009"}"""
    val short = Duration.ofSeconds(DispatchWaits.RequestSeconds)
    def deadline(request: Json): Duration = AttachedGateway.deadline(request, Harness.Claude, true)
    "allow a dispatch command its wait on top of the deadline that every request without a wait keeps" in {
      assert(DispatchWaits.MaxMillis == 120000 && short == Duration.ofSeconds(30))
      for (command <- List(s"""{"Status":{"attempt":$id,"waitMillis":120000}}""", s"""{"IntegrationStatus":{"id":$id,"waitMillis":120000}}""",
        s"""{"CombinationStatus":{"id":$id,"waitMillis":120000}}""", s"""{"Revalidate":{"id":$id,"result":$id,"fence":{"claim":$id,"generation":"1"}}}""",
        // I30: these reply once the host has opened the workspace or published the review.
        s"""{"OpenWorkspace":{"request":$id,"members":[],"previous":null,"fence":{"claim":$id,"generation":"1"}}}""",
        s"""{"SelfReview":{"request":$id,"result":$id,"members":[],"fence":{"claim":$id,"generation":"1"}}}"""))
        assert(deadline(call("dispatch", command)) == Duration.ofSeconds(150), command)
      assert(deadline(call("dispatch", s"""{"Status":{"attempt":$id,"waitMillis":45000}}""")) == Duration.ofSeconds(75))
    }
    "keep the short deadline for a request that does not wait, that the host refuses or that is no dispatch command" in {
      for (command <- List(s"""{"Status":{"attempt":$id,"waitMillis":0}}""", s"""{"Status":{"attempt":$id,"waitMillis":120001}}""",
        s"""{"Status":{"attempt":$id,"waitMillis":-1}}""", s"""{"Cancel":{"attempt":$id}}""", s"""{"Integrate":{"id":$id}}""", """{"Status":"{}"}""",
        s"""{"SubmitWorkspace":{"attempt":$id,"members":[]}}"""))
        assert(deadline(call("dispatch", command)) == short, command)
      // A wait is honoured only where the host waits: the same field in another tool's arguments changes nothing.
      assert(deadline(call("session", s"""{"Status":{"attempt":$id,"waitMillis":120000}}""")) == short)
      assert(deadline(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1), "method" -> Json.fromString("ping"))) == short)
    }
    "D160: allow the first governing request of a session the two server calls of its registration on top of its own deadline" in {
      val registration = Duration.ofSeconds(20)
      assert(AttachedGateway.Registration == registration)
      def request(method: String): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1), "method" -> Json.fromString(method), "params" -> Json.obj())
      for ((method, served) <- AttachedGateway.Methods; harness <- Harness.all; recorded <- List(false, true)) {
        val registers = served.governing && served.only.forall(_ == harness) && !recorded
        assert(AttachedGateway.deadline(request(method), harness, recorded) == (if (registers) short.plus(registration) else short), s"$method $harness $recorded")
      }
      assert(AttachedGateway.deadline(call("dispatch", s"""{"Status":{"attempt":$id,"waitMillis":120000}}"""), Harness.Codex, false) == Duration.ofSeconds(170))
      assert(AttachedGateway.deadline(request("resources/list"), Harness.Pi, false) == short)
    }
  }

  "Workflow replies of an attached session (Behavioral Active Blackbox Atomic)" should {
    "I30: send the instructions again when the process mode changed between two activations, and name the earlier activation on a return to its mode" in {
      val assets = new WorkflowAssets
      val request = WorkflowRequest.Advance(Set(ItemId(project, Ledger.Tasks, 1)), WorkflowPhase.Integrate)
      def activation(mode: ProcessMode): WorkflowActivation =
        WorkflowActivation(RequestId(UUID.randomUUID()), WorkflowContext(request, assets.instructions(request, mode), None, mode), "", None)
      val receipts = new WorkflowReceipts
      val List(rigorous, same, crossCutting, repeated, back) =
        List(ProcessMode.Rigorous, ProcessMode.Rigorous, ProcessMode.CrossCutting, ProcessMode.CrossCutting, ProcessMode.Rigorous).map(activation)
      def text(value: WorkflowActivation) = WorkflowInstructions.Text(value.context.instructions)
      assert(receipts(rigorous) == WorkflowReceipt(rigorous.id, request, text(rigorous), None, None, ProcessMode.Rigorous))
      // The same mode again: the text is the one the session holds.
      assert(receipts(same) == WorkflowReceipt(same.id, request, WorkflowInstructions.Unchanged(rigorous.id), None, None, ProcessMode.Rigorous))
      // The mode changed: the session holds no text for it, so it is sent whole.
      assert(crossCutting.context.instructions != rigorous.context.instructions)
      assert(receipts(crossCutting) == WorkflowReceipt(crossCutting.id, request, text(crossCutting), None, None, ProcessMode.CrossCutting))
      assert(receipts(repeated).instructions == WorkflowInstructions.Unchanged(crossCutting.id) && receipts(repeated).mode == ProcessMode.CrossCutting)
      // Back to the first mode: its text was sent by the first activation, and the receipt's mode says which of the texts it holds applies.
      assert(receipts(back) == WorkflowReceipt(back.id, request, WorkflowInstructions.Unchanged(rigorous.id), None, None, ProcessMode.Rigorous))
      // The activation whose reply first carried a text gets the text again when its call is repeated.
      assert(receipts(rigorous).instructions == text(rigorous) && receipts(crossCutting).instructions == text(crossCutting))
    }
  }

  "Attached gateway (Behavioral Active Blackbox Group)" should {
    "answer a response above the frame bound with one tool error and keep serving" in {
      val FrameBytes = 16384
      var text = "x" * (2 * FrameBytes)
      val artifact = ArtifactId(UUID.fromString("00000000-0000-4000-8000-000000000002"))
      def page = ArtifactPage(ArtifactMetadata(project, artifact, AttemptId(UUID.fromString("00000000-0000-4000-8000-000000000003")), ArtifactKind.Result,
        "text/plain", "fixture", text.length, text.length, Actor("fixture", SessionId(UUID.fromString("00000000-0000-4000-8000-000000000004")), Role.Worker), 0L), 0, text.length, false, text)
      val session = new Session(FrameBytes, _ => Result.ArtifactText(page))
      val request = read(s"""{"ArtifactText":{"id":{"value":"${artifact.value}"},"offset":0,"limit":65536}}""")
      try {
        val message = fault(session.tool("read", request)).hcursor.downField("Limit").get[String]("message").fold(throw _, identity)
        assert(message.contains(s"$FrameBytes-byte") && message.contains("narrow"), message)
        // The same answer follows a tool that changed something, so it must not say that nothing happened.
        assert(message.contains("The operation itself was performed") && message.contains("read the state back") && !message.contains("Nothing was returned"), message)
        val actual = "is (\\d+) bytes".r.findFirstMatchIn(message).map(_.group(1).toInt)
        assert(actual.exists(_ > 4 * FrameBytes), message)
        text = "small"
        val served = session.tool("read", request)
        assert(served.hcursor.get[Boolean]("isError") == Right(false))
        assert(served.hcursor.downField("structuredContent").focus.exists(_.noSpaces.contains("small")))
      } finally session.close()
    }

    "deliver each result to the governing model once: as text where the harness passes the text on, as structured content alone to Codex (I33)" in {
      val counts = Result.Counts(LedgerCounts(Nil, ChangeCursor(7L)))
      Harness.all.foreach { harness =>
        val session = new Session(AttachedGateway.FrameBytes, harness, {
          case Command.Read(ReadInput(_, _: ReadSelection.Counts)) => counts
          case other => fail(s"Unexpected command $other")
        })
        try {
          val payload = Result_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, counts)
          val served = session.tool("read", read("""{"Counts":{}}"""))
          val refused = session.tool("read", read("""{"Catalog":{}}"""))
          List(served -> false, refused -> true).foreach { (result, failed) =>
            val structured = result.hcursor.downField("structuredContent").focus.get
            val text = result.hcursor.get[List[Json]]("content").fold(throw _, identity) match {
              case List(part) if part.hcursor.get[String]("type") == Right("text") => part.hcursor.get[String]("text").fold(throw _, identity)
              case other => fail(s"$harness: expected one text block, got $other")
            }
            assert(result.hcursor.get[Boolean]("isError") == Right(failed) && (failed || structured == payload), harness)
            // The tools declare an output schema, so the structured result is always there; what differs is the text beside it.
            if (harness == Harness.Codex) assert(text == "The result is in structuredContent.", s"$harness: $text")
            else assert(text == structured.noSpaces, s"$harness: $text")
          }
        } finally session.close()
      }
      assert(schemas.attachedTools.forall(_.hcursor.downField("outputSchema").focus.exists(_.isObject)))
    }

    "advertise the governing session's input schemas in bounded bytes: definitions under short names, no bounds on 32-bit integers, the same contract (I33)" in {
      def bytes(value: Json): Int = value.noSpaces.getBytes(UTF_8).length
      def objects(value: Json): List[io.circe.JsonObject] = value.asObject.toList.flatMap(fields => fields :: fields.values.toList.flatMap(objects)) ++
        value.asArray.toList.flatten.flatMap(objects)
      val inputs = schemas.attachedTools.map(tool => tool.hcursor.get[String]("name").fold(throw _, identity) -> tool.hcursor.downField("inputSchema").focus.get)
      val managed = new LocalControl(null, null, null, null, null, null, schemas, null, null).advertised(LocalCapability(AttemptId(UUID.randomUUID()), Role.Governor))
      (inputs :+ ("managed dispatch" -> managed.hcursor.downField("inputSchema").focus.get)).foreach { (name, input) =>
        val defined = input.hcursor.downField("$defs").keys.fold(Set.empty[String])(_.toSet)
        assert(defined.forall(_.matches("d[0-9a-z]+")) && !input.noSpaces.contains("cq_api_"), s"$name: ${defined.take(5)}")
        objects(input).foreach { fields =>
          fields("$ref").foreach(reference => assert(defined(reference.asString.get.stripPrefix("#/$defs/")), s"$name: $reference"))
          if (fields("type").contains(Json.fromString("integer"))) assert(!fields.contains("minimum") && !fields.contains("maximum"), s"$name: $fields")
        }
      }
      // The same contract: with the generated names and the bounds of a 32-bit integer restored, each schema is the generated one.
      List("session" -> "SessionCommand", "dispatch" -> "DispatchCommand").foreach { (tool, command) =>
        val generated = schemas.schema(command)
        val names = generated.hcursor.downField("$defs").keys.get.toVector
        def name(alias: String): String = names(Integer.parseInt(alias.drop(1), Character.MAX_RADIX))
        def restored(value: Json): Json = value.arrayOrObject(value, values => Json.fromValues(values.map(restored)), fields => {
          val entries = fields.toList.map { (key, child) => key -> (if (key == "$ref") Json.fromString("#/$defs/" + name(child.asString.get.stripPrefix("#/$defs/"))) else restored(child)) }
          Json.fromFields(if (!fields("type").contains(Json.fromString("integer"))) entries
            else entries ++ List("minimum" -> Json.fromInt(Int.MinValue), "maximum" -> Json.fromInt(Int.MaxValue)))
        })
        val advertised = inputs.toMap.apply(tool)
        val definitions = advertised.hcursor.downField("$defs").focus.get.asObject.get.toList.map((alias, value) => name(alias) -> restored(value))
        assert(restored(advertised.mapObject(_.remove("$defs"))).mapObject(_.add("$defs", Json.fromFields(definitions))) == generated, tool)
      }
      val sizes = inputs.map((name, input) => name -> bytes(input))
      println("I33 advertised input schema bytes: " + sizes.map((name, size) => s"$name $size").mkString(", ") + s"; all nine ${sizes.map(_._2).sum}")
      // Measured 2026-10-05: 44,267 bytes; under the generated names and with the bounds, the nine schemas of the release before took 53,917.
      // A deliberate addition to a command raises this bound: 46,107 bytes with the three commands of the governing session's own work (I30).
      assert(sizes.map(_._2).sum <= InputSchemaBytes, sizes.toString)
    }

    "D160: have the session recorded at a tool call, a Pi response's usage and a Pi driver request, and at nothing a harness sends when it only opens the connection" in {
      val session = new Session(AttachedGateway.FrameBytes, Harness.Pi, _ => Result.Counts(LedgerCounts(Nil, ChangeCursor(7L))))
      try {
        session.exchange("tools/list", Json.obj())
        session.exchange("ping", Json.obj())
        session.exchange("cq/session", Json.obj())
        for (method <- List("resources/list", "prompts/list", "resources/templates/list"))
          assert(session.exchange(method, Json.obj()).hcursor.downField("error").get[Int]("code") == Right(-32601), method)
        assert(session.governing.get() == 0)
        // A tool call counts whatever it does: one that only reads, and one the host refuses.
        session.tool("read", read("""{"Counts":{}}"""))
        assert(session.governing.get() == 1)
        fault(session.tool("unknown", Json.obj()))
        assert(session.governing.get() == 2)
        // Neither request is well-formed here: the record is asked for before the request is looked at.
        assert(scala.util.Try(session.exchange("cq/piUsage", Json.obj())).isFailure && session.governing.get() == 3)
        session.exchange("cq/driver", Json.obj())
        assert(session.governing.get() == 4)
        // Every method the host answers is classified, and no other is answered.
        assert(AttachedGateway.Methods.keySet == Set("initialize", "ping", "tools/list", "cq/session", "tools/call", "cq/piUsage", "cq/driver"))
      } finally session.close()
    }
    "D160: answer the request whose registration failed with that failure, keep serving, and ask for the registration again at the next governing request" in {
      val session = new Session(AttachedGateway.FrameBytes, Harness.Pi, _ => Result.Counts(LedgerCounts(Nil, ChangeCursor(7L))))
      try {
        def refusal(method: String, params: Json): Json = { session.refused = true; session.exchange(method, params) }
        val tool = refusal("tools/call", Json.obj("name" -> Json.fromString("read"), "arguments" -> read("""{"Counts":{}}"""))).hcursor.downField("result").focus.get
        assert(fault(tool).hcursor.downField("Invalid").get[String]("message") == Right("Fixture server refused the registration"), tool.noSpaces)
        val driver = refusal("cq/driver", Json.obj())
        assert(driver.hcursor.downField("result").downField("Failed").downField("fault").downField("Invalid").get[String]("message") == Right("Fixture server refused the registration"), driver.noSpaces)
        val usage = refusal("cq/piUsage", Json.obj())
        assert(usage.hcursor.downField("error").get[String]("message") == Right("Fixture server refused the registration"), usage.noSpaces)
        assert(session.governing.get() == 3 && !session.refused)
        assert(session.tool("read", read("""{"Counts":{}}""")).hcursor.get[Boolean]("isError") == Right(false) && session.governing.get() == 4)
      } finally session.close()
    }
    "refuse a read of the Help catalog, which is served to the browser only, without reaching the server, and keep serving" in {
      val session = new Session(AttachedGateway.FrameBytes, {
        case Command.Read(ReadInput(_, _: ReadSelection.Counts)) => Result.Counts(LedgerCounts(Nil, ChangeCursor(0L)))
        case other => fail(s"Unexpected command $other")
      })
      try {
        val refused = fault(session.tool("read", read("""{"Catalog":{}}"""))).hcursor.downField("Denied").get[String]("message").fold(throw _, identity)
        assert(refused == McpSchemas.CatalogRefusal && refused.contains("served to the browser only"), refused)
        assert(session.tool("read", read("""{"Counts":{}}""")).hcursor.get[Boolean]("isError") == Right(false))
        val tool = schemas.attachedTools.find(_.hcursor.get[String]("name") == Right("read")).get
        assert(!tool.noSpaces.contains("Catalog") && !tool.hcursor.get[String]("description").exists(_.toLowerCase.contains("catalog")))
        assert(!schemas.attachedInstructions(Harness.Codex, Some("/opt/cq/bin/cq wait")).contains("Catalog"))
      } finally session.close()
    }

    "I17: refuse a StartChoice or a Start that names a harness before anything is dispatched" in {
      val session = new Session(AttachedGateway.FrameBytes, other => fail(s"Unexpected command $other"))
      def refused(arguments: String): String =
        fault(session.tool("dispatch", parser.parse(arguments).fold(throw _, identity))).hcursor.downField("Invalid").get[String]("message").fold(throw _, identity)
      val fence = """"fence":{"claim":{"value":"00000000-0000-4000-8000-000000000002"},"generation":"1"}"""
      try {
        // The session names the choice and its claim; the host starts the models the configuration assigns.
        assert(refused(s"""{"StartChoice":{"choice":{"value":"00000000-0000-4000-8000-000000000003"},"harness":"Codex",$fence}}""").contains("Noncanonical dispatch request"))
        val work = s""""request":{"value":"00000000-0000-4000-8000-000000000003"},"work":{"Planner":{}},"members":[],"guidance":[],"artifacts":[],"previous":null,$fence,""" +
          """"limits":{"startupMillis":"3000","heartbeatMillis":"1000","graceMillis":"300","killMillis":"2000","retainedOutputBytes":262144}"""
        assert(refused(s"""{"Start":{"work":{"harness":"Codex",$work}}}""").contains("Noncanonical dispatch request"))
        val former = refused(s"""{"Start":{"request":{"harness":"Codex",$work}}}""")
        assert(former.contains("\"dispatch\"") && former.contains("do not match its input schema"), former)
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
        assert(tags("SessionCommand") == List("Context", "Workflow", "Instructions", "Bind", "Driver") && context.contains("Context, Workflow, Instructions, Bind, Driver"), context)
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
