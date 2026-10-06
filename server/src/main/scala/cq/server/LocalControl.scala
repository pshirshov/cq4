package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import com.comcast.ip4s.{Host, Port}
import cq.api.*
import cq.core.JsonRoundtrip
import cq.core.DomainFailure
import cq.host.{DispatchProjection, DispatchWaits, WorkflowExecution}
import distage.Lifecycle
import io.circe.{Json, parser}
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import zio.{Task, ZIO}
import zio.interop.catz.*

final class LocalControl(units: DispatchUnits, cohorts: CohortController, integrations: IntegrationController, combinations: CombinationController,
  revalidations: RevalidationController, access: LocalAccess, schemas: McpSchemas, config: SupervisorConfig, workflow: WorkflowExecution) extends Http4sDsl[Task] {
  private val Versions = List("2025-03-26", "2025-06-18", "2025-11-25")
  private val MaxRequestBytes = 65536
  private val Context = BaboonCodecContext.Default
  private def header(request: Request[Task], name: String): Option[String] = request.headers.get(CIString(name)).map(_.head.value)
  private def response(status: Status, json: Json): Task[Response[Task]] =
    ZIO.succeed(Response[Task](status).withEntity(json.noSpaces).withContentType(`Content-Type`(MediaType.application.json)))
  private def success(id: Json, result: Json): Task[Response[Task]] = response(Status.Ok,
    Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "result" -> result))
  private def failure(id: Json, code: Int, message: String): Task[Response[Task]] = response(Status.Ok,
    Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> Json.fromString(message))))
  private def tool(name: String, input: Json, output: String, description: String, readOnly: Boolean): Json = Json.obj(
    "name" -> Json.fromString(name), "description" -> Json.fromString(description), "inputSchema" -> input, "outputSchema" -> schemas.schema(output),
    "annotations" -> Json.obj("readOnlyHint" -> Json.fromBoolean(readOnly), "openWorldHint" -> Json.False))
  private[server] def advertised(capability: LocalCapability): Json = if (capability.role == Role.Governor)
    tool("dispatch", schemas.localInput("DispatchCommand"), "DispatchReply", s"Select bounded cohorts, claim one complete choice, then StartChoice by ID and fence: the host starts the models the project's agent configuration assigns to the role, as one unit named by one attempt ID. Workflow runs require choices; direct Start supports explicitly assigned non-workflow runs. Status reads the state or the result of an attempt and, with waitMillis up to ${DispatchWaits.MaxMillis}, first waits for the attempt to end; Cancel stops the whole unit of an attempt; Seats lists the models the host tried for it and how each seat ended. Prepare/apply reviewed integration, or DiscardIntegration a prepared one that will not be applied; Combine a NotApplied integration; IntegrationStatus and CombinationStatus read and wait the same way. Forward handles directly; full prompts/results stay outside your context." + McpSchemas.Revalidation, false)
  else tool("workspace", schemas.workspace(capability.role), "WorkspaceReply", "List or read bounded pages in your assigned workspace. A prepared resolver may read MergeReport. A candidate reviewer may request a configured Check by name and poll the same operation; wait for Completed evidence before returning. Relative paths only; Git metadata and symlink traversal are denied.", capability.role != Role.Reviewer)
  /** `input` is the tool's input schema, which is assembled only to describe a decode fault. */
  private def decode[A](name: String, input: => Json, codec: BaboonJsonCodec[A], json: Json): Task[A] = ZIO.attempt {
    val value = codec.decode(Context, json).fold(error =>
      throw DomainFailure(Fault.Invalid(schemas.mismatch(name, input, String.valueOf(error.getMessage)))), identity)
    require(JsonRoundtrip.lossless(json, codec.encode(Context, value)), "Local command contains undeclared or noncanonical fields")
    value
  }
  private def fault(error: Throwable): Fault = error match {
    case DomainFailure(value) => value
    case _: IllegalArgumentException => Fault.Invalid(DispatchProjection.concise(Option(error.getMessage).getOrElse("Invalid local operation")))
    case _ => Fault.Conflict(DispatchProjection.concise("Local operation failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
  }
  private[server] def call(capability: LocalCapability, name: String, arguments: Json): Task[(Json, Boolean)] = {
    if (capability.role == Role.Governor && name == "dispatch") {
      val operation = decode(name, schemas.schema("DispatchCommand"), DispatchCommand_JsonCodec, arguments).tap(command => ZIO.attemptBlocking(workflow.authorize(command))).flatMap {
        case DispatchCommand.Select(request) => cohorts.select(request).map(value => DispatchReply.Selection(DispatchProjection.offer(value)))
        case DispatchCommand.StartChoice(choice, fence) => cohorts.start(choice, fence).map(DispatchReply.Status.apply)
        case DispatchCommand.Start(work) => ZIO.attempt {
          require(config.run.ownership == SessionOwnership.Managed && config.workflow.isEmpty, "Workflow execution requires a retained cohort choice")
        } *> units.start(work, None).map(DispatchReply.Status.apply)
        case DispatchCommand.Status(attempt, wait) => units.status(attempt, wait).map(DispatchReply.Status.apply)
        case DispatchCommand.Cancel(attempt) => units.cancel(attempt).map(DispatchReply.Status.apply)
        case DispatchCommand.Seats(attempt) => units.seats(attempt).map(DispatchReply.Seats.apply)
        case DispatchCommand.PrepareIntegration(id, reviewer) => integrations.prepare(IntegrationTicket(id, reviewer)).map(DispatchReply.Integration.apply)
        case DispatchCommand.Integrate(id) => integrations(id).map(DispatchReply.Integration.apply)
        case DispatchCommand.IntegrationStatus(id, wait) => integrations.status(id, wait).map(DispatchReply.Integration.apply)
        case DispatchCommand.DiscardIntegration(id) => integrations.discard(id).map(DispatchReply.Integration.apply)
        case DispatchCommand.Combine(id, source, fence) => combinations.prepare(CombinationTicket(id, source, fence)).map(DispatchReply.Combination.apply)
        case DispatchCommand.CombinationStatus(id, wait) => combinations.status(id, wait).map(DispatchReply.Combination.apply)
        case DispatchCommand.Revalidate(id, result, fence) => revalidations.request(id, result, fence).map(DispatchReply.Revalidation.apply)
      }
      operation.map(value => (DispatchReply_JsonCodec.encode(Context, value), false))
        .catchAll(error => ZIO.succeed((DispatchReply_JsonCodec.encode(Context, DispatchReply.Failed(fault(error))), true)))
    } else if (Set(Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer)(capability.role) && name == "workspace") {
      decode(name, schemas.workspace(capability.role), WorkspaceCommand_JsonCodec, arguments).flatMap(units.workspace(capability.attempt, _))
        .map(value => (WorkspaceReply_JsonCodec.encode(Context, value), false))
        .catchAll(error => ZIO.succeed((WorkspaceReply_JsonCodec.encode(Context, WorkspaceReply.Failed(fault(error))), true)))
    } else ZIO.fail(DomainFailure(Fault.Denied("Local capability does not authorize this tool")))
  }
  private def rpc(capability: LocalCapability, json: Json): Task[Response[Task]] = {
    val cursor = json.hcursor
    val id = cursor.get[Json]("id").getOrElse(Json.Null)
    val method = cursor.get[String]("method").toOption
    if (!cursor.get[String]("jsonrpc").contains("2.0") || method.isEmpty || !(id.isNull || id.isString || id.isNumber)) failure(id, -32600, "Invalid request")
    else method.get match {
      case "initialize" =>
        val requested = cursor.downField("params").get[String]("protocolVersion").getOrElse("")
        success(id, Json.obj("protocolVersion" -> Json.fromString(if (Versions.contains(requested)) requested else Versions.last),
          "capabilities" -> Json.obj("tools" -> Json.obj()), "serverInfo" -> Json.obj("name" -> Json.fromString("cq_host"), "version" -> Json.fromString("0.1.0"))))
      case "notifications/initialized" if id.isNull => ZIO.succeed(Response[Task](Status.Accepted))
      case "ping" => success(id, Json.obj())
      case "tools/list" => success(id, Json.obj("tools" -> Json.arr(advertised(capability))))
      case "tools/call" =>
        (cursor.downField("params").get[String]("name"), cursor.downField("params").get[Json]("arguments")) match {
          case (Right(name), Right(arguments)) => call(capability, name, arguments).flatMap { case (body, failed) =>
            success(id, Json.obj("isError" -> Json.fromBoolean(failed), "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(body.noSpaces))),
              "structuredContent" -> body))
          }.catchAll(_ => failure(id, -32602, "Unavailable local tool or invalid arguments"))
          case _ => failure(id, -32602, "Tool name and typed arguments required")
        }
      case _ => failure(id, -32601, "Method not found")
    }
  }
  val routes: HttpRoutes[Task] = HttpRoutes.of[Task] {
    case request @ POST -> Root / "mcp" =>
      (for {
        capability <- ZIO.attempt {
          require(header(request, "Origin").isEmpty, "Local control does not accept browser origins")
          require(header(request, "MCP-Protocol-Version").forall(Versions.contains), "Unsupported MCP version")
          access.authenticate(header(request, "Authorization").filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer ")).getOrElse(""))
        }
        bytes <- request.body.take(MaxRequestBytes + 1L).compile.toVector.timeoutFail(new IllegalArgumentException("Local request body deadline exceeded"))(zio.Duration.fromSeconds(2))
        json <- ZIO.attempt {
          require(bytes.length <= MaxRequestBytes, "Local request exceeds 64 KiB")
          parser.parse(UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toArray)).toString).fold(throw _, identity)
        }
        result <- rpc(capability, json)
      } yield result).catchAll { error =>
        val value = fault(error)
        response(if (value.isInstanceOf[Fault.Denied]) Status.Unauthorized else Status.BadRequest, Fault_JsonCodec.encode(Context, value))
      }
    case GET -> Root / "mcp" => ZIO.succeed(Response[Task](Status.MethodNotAllowed))
    case DELETE -> Root / "mcp" => ZIO.succeed(Response[Task](Status.MethodNotAllowed))
  }
}

final case class LocalControlServer(endpoint: URI)
object LocalControlServer {
  final class Resource(control: LocalControl, access: LocalAccess) extends Lifecycle.Of[Task, LocalControlServer](Lifecycle.fromCats(
    EmberServerBuilder.default[Task].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
      .withHttpApp(control.routes.orNotFound).build.evalMap { server => ZIO.attempt {
        val origin = URI.create("http://127.0.0.1:" + server.address.getPort)
        access.bind(origin)
        LocalControlServer(origin.resolve("/mcp"))
      }}
  ))
}
