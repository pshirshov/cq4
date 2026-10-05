package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, JsonRoundtrip}
import cq.host.{AttachedCodexUsage, AttachedUsage, DispatchProjection, OperatorRequirements, StdioPeer}
import io.circe.Json
import zio.{Task, ZIO}

object AttachedGateway {
  val FrameBytes: Int = 2 * 1024 * 1024
}

final class AttachedGateway(config: SupervisorConfig, authority: SupervisorAuthority, schemas: McpSchemas,
  local: LocalControl, workflow: AttachedWorkflow, accounting: AttachedUsage, codex: AttachedCodexUsage, driver: AttachedDriver, claims: SessionClaims) {
  private val Versions = List("2025-03-26", "2025-06-18", "2025-11-25")
  private val CodecContext = BaboonCodecContext.Default
  private val MaxLocalBytes = 65536
  private val capability = LocalCapability(config.run.attempt.id, Role.Governor)
  private var initialized = false
  // Each distinct workflow instruction text this session was sent, by the activation whose reply first carried it.
  private var delivered = Map.empty[String, RequestId]
  /** The activation as its Workflow reply carries it: the instruction text once per attached session, afterwards the activation that carried it. */
  private def receipt(value: WorkflowActivation): WorkflowReceipt = synchronized {
    val text = value.context.instructions
    val instructions = delivered.get(text).fold[WorkflowInstructions] { delivered += text -> value.id; WorkflowInstructions.Text(text) }(WorkflowInstructions.Unchanged.apply)
    WorkflowReceipt(value.id, value.context.request, instructions, value.context.subject, value.cycle)
  }
  private def success(id: Json, body: Json): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "result" -> body)
  private def failure(id: Json, code: Int, message: String): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
    "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> Json.fromString(message)))
  private def result(body: Json, failed: Boolean): Json = Json.obj("isError" -> Json.fromBoolean(failed),
    "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(body.noSpaces))), "structuredContent" -> body)
  private def context: AttachedContext = AttachedContext(config.run.attempt.session, config.run.attempt.id, config.directory.toString,
    config.project, config.settings.harnesses.map(value => HarnessRoute(value.harness, value.model, value.provider)), config.settings.checks.map(_.name),
    config.settings.limits, config.settings.integrationTarget, OperatorRequirements.governing(schemas.attachedInstructions(config.run.attempt.harness),
      OperatorRequirements.standing(authority.governor.call, config.project.project)),
    workflow.current.map(value => ActiveWorkflow(value.id, value.context.request, value.cycle)),
    if (config.run.attempt.harness == Harness.Pi)
      "Interactive Pi finalized assistant usage is collected by the extension; compaction, auxiliary calls and unreported/interrupted responses remain unobserved. Managed child usage is collected independently."
    else if (config.run.attempt.harness == Harness.Codex) codex.status
    else "Outer interactive model/provider and token usage are unobserved by this host. Managed child usage is collected independently; missing is not zero.")
  private def decoded[A](name: String, input: String, arguments: Json)(decode: Json => Either[Throwable, A]): A = decode(arguments).fold(error =>
    throw DomainFailure(Fault.Invalid(schemas.mismatch(name, schemas.schema(input), String.valueOf(error.getMessage)))), identity)
  private def tool(name: String, arguments: Json): Task[(Json, Boolean)] = name match {
    case "session" =>
      ZIO.attempt {
        require(arguments.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Session request exceeds its bound")
        val command = decoded(name, "SessionCommand", arguments)(SessionCommand_JsonCodec.decode(CodecContext, _))
        require(JsonRoundtrip.lossless(arguments, SessionCommand_JsonCodec.encode(CodecContext, command)), "Noncanonical session request")
        command
      }.flatMap {
        case _: SessionCommand.Context => ZIO.attemptBlocking(SessionReply.Context(context))
        case SessionCommand.Workflow(id, request, operatorRequirements, token) => workflow.activate(id, request, operatorRequirements, token).map(value => SessionReply.Workflow(receipt(value)))
        case _: SessionCommand.Instructions => ZIO.attempt(SessionReply.Instructions(workflow.current.getOrElse(
          throw DomainFailure(Fault.Missing("No workflow is active in this session: activate one with session Workflow")))))
        // The model-facing driver surface: a bind gated by the hook-minted token and a read-only status. Neither starts nor parks a driver.
        case SessionCommand.Bind(token) => ZIO.attemptBlocking(SessionReply.Driver(driver.session.bind(token)))
        case _: SessionCommand.Driver => ZIO.attemptBlocking(SessionReply.Driver(driver.session.status))
      }.map(value => SessionReply_JsonCodec.encode(CodecContext, value) -> false)
    case "dispatch" =>
      ZIO.attempt {
        require(arguments.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Dispatch request exceeds its bound")
        val command = decoded(name, "DispatchCommand", arguments)(DispatchCommand_JsonCodec.decode(CodecContext, _))
        require(JsonRoundtrip.lossless(arguments, DispatchCommand_JsonCodec.encode(CodecContext, command)), "Noncanonical dispatch request")
        workflow.authorize(command)
        command
      }.flatMap { command => local.call(capability, name, arguments).tap { case (body, _) =>
        ZIO.fromEither(DispatchReply_JsonCodec.decode(CodecContext, body)).flatMap(driver.observe(workflow.current, command, _))
      }}
    case _ => ZIO.attemptBlocking {
      val definition = schemas.tools.find(_.name == name).getOrElse(throw DomainFailure(Fault.Denied("Unavailable attached tool")))
      val command = definition.decode(arguments).fold(error => throw DomainFailure(schemas.rejected(definition, error)), identity)
      val canonical = Command_JsonCodec.encode(CodecContext, command).asObject.get.values.head.hcursor.downField("input").focus.get
      require(JsonRoundtrip.lossless(arguments, canonical), "Noncanonical domain request")
      val value = claims.call(command)
      Result_JsonCodec.encode(CodecContext, value) -> value.isInstanceOf[Result.Failed]
    }
  }
  /** A response the peer cannot frame fails its own request only; sending it would end the host for every later request.
    * The request was executed before its response was measured, so a change it made stands. */
  def handle(peer: StdioPeer, json: Json): Task[Option[Json]] = respond(peer, json).map(_.map { response =>
    val size = StdioPeer.frame(response).length
    if (size <= peer.frameBytes) response
    else {
      val id = json.hcursor.downField("id").focus.getOrElse(Json.Null)
      val message = s"The response is $size bytes and exceeds the ${peer.frameBytes}-byte MCP frame bound of this host. " +
        "The operation itself was performed, and a change it made stands; only its response could not be returned. " +
        "Do not repeat a change: read the state back with a narrower request (a smaller limit or page, or a narrower selection)."
      if (json.hcursor.get[String]("method") != Right("tools/call")) failure(id, -32603, message)
      else success(id, result(SessionReply_JsonCodec.encode(CodecContext, SessionReply.Failed(Fault.Limit(message))), true))
    }
  })
  private def respond(peer: StdioPeer, json: Json): Task[Option[Json]] = {
    val cursor = json.hcursor
    val id = cursor.downField("id").focus.getOrElse(Json.Null)
    val method = cursor.get[String]("method").getOrElse("")
    if (cursor.get[String]("jsonrpc") != Right("2.0") || !(id.isNull || id.isString || id.isNumber) || method.isEmpty)
      ZIO.some(failure(id, -32600, "Invalid JSON-RPC request"))
    else if (method == "initialize") ZIO.attempt {
      require(!initialized && !id.isNull, "MCP connection must initialize exactly once")
      val requested = cursor.downField("params").get[String]("protocolVersion").fold(throw _, identity)
      initialized = true; peer.initialize()
      Some(success(id, Json.obj("protocolVersion" -> Json.fromString(if (Versions.contains(requested)) requested else Versions.last),
        "capabilities" -> Json.obj("tools" -> Json.obj()), "serverInfo" -> Json.obj("name" -> Json.fromString("cq"), "version" -> Json.fromString("0.1.0")),
        "instructions" -> Json.fromString("This session is the CQ Governor. Call session Context before CQ work, then activate a typed workflow. Use dispatch handles; never compose child prompts or copy full results. CQ does not launch another Governor."))))
    }
    else if (!initialized) ZIO.some(failure(id, -32002, "Initialize the MCP connection first"))
    else if (method == "notifications/initialized" && id.isNull) ZIO.none
    else if (id.isNull) ZIO.none
    else method match {
      case "ping" => ZIO.some(success(id, Json.obj()))
      case "tools/list" => ZIO.some(success(id, Json.obj("tools" -> Json.arr(schemas.attachedTools*))))
      case "cq/piUsage" => ZIO.attemptBlocking {
        val body = cursor.downField("params").focus.getOrElse(throw new IllegalArgumentException("Missing native Pi usage"))
        require(body.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Native Pi usage exceeds its bound")
        val event = AttachedPiEvent_JsonCodec.decode(CodecContext, body).fold(throw _, identity)
        require(JsonRoundtrip.lossless(body, AttachedPiEvent_JsonCodec.encode(CodecContext, event)), "Noncanonical native Pi usage")
        accounting.accept(event, authority.collector)
        Some(success(id, Json.obj()))
      }
      case "cq/driver" if config.run.attempt.harness == Harness.Pi => ZIO.attemptBlocking {
        val body = cursor.downField("params").focus.getOrElse(throw new IllegalArgumentException("Missing driver request"))
        require(body.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Driver request exceeds its bound")
        val command = ExtensionDriver_JsonCodec.decode(CodecContext, body).fold(throw _, identity)
        require(JsonRoundtrip.lossless(body, ExtensionDriver_JsonCodec.encode(CodecContext, command)), "Noncanonical driver request")
        Some(success(id, DriverReply_JsonCodec.encode(CodecContext, driver.extension(command))))
      }.catchAll { error =>
        val fault = error match {
          case DomainFailure(value) => value
          case _ => Fault.Invalid(DispatchProjection.concise(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
        }
        ZIO.some(success(id, Json.obj("Failed" -> Json.obj("fault" -> Fault_JsonCodec.encode(CodecContext, fault)))))
      }
      case "tools/call" =>
        (for {
          _ <- ZIO.attemptBlocking {
            if (config.run.attempt.harness == Harness.Codex) {
              val home = config.environment.get("CODEX_HOME").map(java.nio.file.Path.of(_))
                .getOrElse(java.nio.file.Path.of(config.environment("HOME"), ".codex"))
              codex.observe(cursor.downField("params").downField("_meta").focus, home.resolve("sessions"))
            }
          }
          name <- ZIO.fromEither(cursor.downField("params").get[String]("name"))
          arguments <- ZIO.fromEither(cursor.downField("params").get[Json]("arguments"))
          value <- tool(name, arguments)
        } yield Some(success(id, result(value._1, value._2)))).catchAll { error =>
          val fault = error match {
            case DomainFailure(value) => value
            case _ => Fault.Invalid(DispatchProjection.concise(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
          }
          ZIO.some(success(id, result(SessionReply_JsonCodec.encode(CodecContext, SessionReply.Failed(fault)), true)))
        }
      case _ => ZIO.some(failure(id, -32601, "Method not found"))
    }
  }
}
