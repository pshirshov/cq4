package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import com.comcast.ip4s.{Host, Port}
import cq.api.*
import cq.core.DomainFailure
import cq.host.{ChildContracts, DispatchProjection}
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

final class LocalControl(dispatch: DispatchController, integrations: IntegrationController, access: LocalAccess, schemas: McpSchemas, config: SupervisorConfig) extends Http4sDsl[Task] {
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
  private def tool(name: String, input: String, output: String, description: String, readOnly: Boolean): Json = Json.obj(
    "name" -> Json.fromString(name), "description" -> Json.fromString(description), "inputSchema" -> schemas.schema(input), "outputSchema" -> schemas.schema(output),
    "annotations" -> Json.obj("readOnlyHint" -> Json.fromBoolean(readOnly), "openWorldHint" -> Json.False))
  private def advertised(capability: LocalCapability): Json = if (capability.role == Role.Governor)
    tool("dispatch", "DispatchCommand", "DispatchReply", "Start one child using references only, poll its compact status with a bounded wait, or cancel it. Prepare integration from an accepted reviewer handle, apply its frozen preview, and poll its compact outcome. Forward result handles directly to the next child; full prompts and results stay outside your context.", false)
  else tool("workspace", "WorkspaceCommand", "WorkspaceReply", "List a bounded directory page or read a bounded Unicode text page in your assigned workspace. Relative paths only; Git metadata and symbolic-link traversal are denied.", true)
  private def decode[A](codec: BaboonJsonCodec[A], json: Json): Task[A] = ZIO.attempt {
    val value = codec.decode(Context, json).fold(throw _, identity)
    require(codec.encode(Context, value) == json, "Local command contains undeclared or noncanonical fields")
    value
  }
  private def fault(error: Throwable): Fault = error match {
    case DomainFailure(value) => value
    case _: IllegalArgumentException => Fault.Invalid(DispatchProjection.concise(Option(error.getMessage).getOrElse("Invalid local operation")))
    case _ => Fault.Conflict(DispatchProjection.concise("Local operation failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
  }
  private def call(capability: LocalCapability, name: String, arguments: Json): Task[(Json, Boolean)] = {
    if (capability.role == Role.Governor && name == "dispatch") {
      val operation = decode(DispatchCommand_JsonCodec, arguments).flatMap {
        case DispatchCommand.Start(request) => ZIO.attempt(ChildContracts.request(config.project.project, request)) *> dispatch.start(request).map(DispatchReply.Status.apply)
        case DispatchCommand.Status(attempt, wait) => dispatch.status(attempt, wait).map(DispatchReply.Status.apply)
        case DispatchCommand.Cancel(attempt) => dispatch.cancel(attempt).map(DispatchReply.Status.apply)
        case DispatchCommand.PrepareIntegration(id, reviewer) => integrations.prepare(IntegrationTicket(id, reviewer)).map(DispatchReply.Integration.apply)
        case DispatchCommand.Integrate(id) => integrations(id).map(DispatchReply.Integration.apply)
        case DispatchCommand.IntegrationStatus(id, wait) => integrations.status(id, wait).map(DispatchReply.Integration.apply)
      }
      operation.map(value => (DispatchReply_JsonCodec.encode(Context, value), false))
        .catchAll(error => ZIO.succeed((DispatchReply_JsonCodec.encode(Context, DispatchReply.Failed(fault(error))), true)))
    } else if (Set(Role.Worker, Role.Reviewer)(capability.role) && name == "workspace") {
      decode(WorkspaceCommand_JsonCodec, arguments).flatMap(dispatch.workspace(capability.attempt, _))
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
