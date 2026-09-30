package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, JsonRoundtrip}
import cq.host.{AttachedCodexUsage, AttachedUsage, DispatchProjection, StdioPeer}
import io.circe.Json
import zio.{Task, ZIO}

final class AttachedGateway(config: SupervisorConfig, authority: SupervisorAuthority, schemas: McpSchemas,
  local: LocalControl, workflow: AttachedWorkflow, accounting: AttachedUsage, codex: AttachedCodexUsage) {
  private val Versions = List("2025-03-26", "2025-06-18", "2025-11-25")
  private val CodecContext = BaboonCodecContext.Default
  private val MaxLocalBytes = 65536
  private val capability = LocalCapability(config.run.attempt.id, Role.Governor)
  private var initialized = false
  private def success(id: Json, body: Json): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "result" -> body)
  private def failure(id: Json, code: Int, message: String): Json = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
    "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> Json.fromString(message)))
  private def result(body: Json, failed: Boolean): Json = Json.obj("isError" -> Json.fromBoolean(failed),
    "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(body.noSpaces))), "structuredContent" -> body)
  private def context: AttachedContext = AttachedContext(config.run.attempt.session, config.run.attempt.id, config.directory.toString,
    config.project, config.settings.harnesses.map(value => HarnessRoute(value.harness, value.model, value.provider)), config.settings.checks.map(_.name),
    config.settings.limits, config.settings.integrationTarget, schemas.attachedInstructions(config.run.attempt.harness), workflow.current,
    if (config.run.attempt.harness == Harness.Pi)
      "Interactive Pi finalized assistant usage is collected by the extension; compaction, auxiliary calls and unreported/interrupted responses remain unobserved. Managed child usage is collected independently."
    else if (config.run.attempt.harness == Harness.Codex) codex.status
    else "Outer interactive model/provider and token usage are unobserved by this host. Managed child usage is collected independently; missing is not zero.")
  private def tool(name: String, arguments: Json): Task[(Json, Boolean)] = name match {
    case "session" =>
      ZIO.attempt {
        require(arguments.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Session request exceeds its bound")
        val command = SessionCommand_JsonCodec.decode(CodecContext, arguments).fold(throw _, identity)
        require(JsonRoundtrip.lossless(arguments, SessionCommand_JsonCodec.encode(CodecContext, command)), "Noncanonical session request")
        command
      }.flatMap {
        case _: SessionCommand.Context => ZIO.succeed(SessionReply.Context(context))
        case SessionCommand.Workflow(id, request, operatorRequirements) => workflow.activate(id, request, operatorRequirements).map(SessionReply.Workflow.apply)
      }.map(value => SessionReply_JsonCodec.encode(CodecContext, value) -> false)
    case "dispatch" =>
      ZIO.attempt {
        require(arguments.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxLocalBytes, "Dispatch request exceeds its bound")
        val command = DispatchCommand_JsonCodec.decode(CodecContext, arguments).fold(throw _, identity)
        require(JsonRoundtrip.lossless(arguments, DispatchCommand_JsonCodec.encode(CodecContext, command)), "Noncanonical dispatch request")
        workflow.authorize(command)
      } *> local.call(capability, name, arguments)
    case _ => ZIO.attemptBlocking {
      val definition = schemas.tools.find(_.name == name).getOrElse(throw DomainFailure(Fault.Denied("Unavailable attached tool")))
      val command = definition.decode(arguments).fold(throw _, identity)
      val canonical = Command_JsonCodec.encode(CodecContext, command).asObject.get.values.head.hcursor.downField("input").focus.get
      require(JsonRoundtrip.lossless(arguments, canonical), "Noncanonical domain request")
      val value = authority.governor.call(command)
      Result_JsonCodec.encode(CodecContext, value) -> value.isInstanceOf[Result.Failed]
    }
  }
  def handle(peer: StdioPeer, json: Json): Task[Option[Json]] = {
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
