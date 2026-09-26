package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.ProbeService
import io.circe.{Json, parser}
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString
import zio.{IO, Task, ZIO}
import zio.interop.catz.*

final case class AccessConfig(token: String, project: ProjectId, origin: String)

final class Transport(service: ProbeService[IO], access: AccessConfig, schemas: McpSchemas) extends Http4sDsl[Task] {
  private val context = BaboonCodecContext.Default
  private val protocolVersions = List("2025-03-26", "2025-06-18", "2025-11-25")
  private val apiVersion = Probe.baboonDomainVersion

  private def jsonResponse(status: Status, json: Json): Task[Response[Task]] =
    ZIO.succeed(Response[Task](status).withEntity(json.noSpaces).withContentType(`Content-Type`(MediaType.application.json)))

  private def error(status: Status, value: ApiError): Task[Response[Task]] =
    jsonResponse(status, ApiError_JsonCodec.encode(context, value))

  private def header(request: Request[Task], name: String): Option[String] =
    request.headers.get(CIString(name)).map(_.head.value)

  private def decodeProbe(json: Json): Either[Throwable, Probe] =
    Probe_JsonCodec.decode(context, json).flatMap { value =>
      Either.cond(value.project == access.project, value, new IllegalArgumentException("Project is outside credential scope"))
    }

  private def exchange(json: Json): Task[Either[Throwable, Json]] = decodeProbe(json) match {
    case Left(failure) => ZIO.succeed(Left(failure))
    case Right(value) => service.exchange(value).map(value => Right(Probe_JsonCodec.encode(context, value)))
  }

  private def readJson(request: Request[Task]): Task[Either[Throwable, Json]] =
    request.body.take(MaxRequestBytes + 1L).compile.toVector.map { bytes =>
      if (bytes.size > MaxRequestBytes) Left(new IllegalArgumentException("Request exceeds byte limit"))
      else parser.parse(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
    }

  private val MaxRequestBytes = 65536

  def routes(ws: WebSocketBuilder2[Task]): HttpRoutes[Task] = HttpRoutes.of[Task] {
    case request if header(request, "Origin").exists(_ != access.origin) =>
      error(Status.Forbidden, ApiError.Unauthorized("Origin rejected"))
    case request if !header(request, "Authorization").contains(s"Bearer ${access.token}") =>
      error(Status.Unauthorized, ApiError.Unauthorized("Bearer credential required"))
    case GET -> Root / "api" / "hello" =>
      jsonResponse(Status.Ok, ProtocolHello_JsonCodec.encode(context, ProtocolHello(apiVersion, List(apiVersion))))
    case request @ POST -> Root / "api" / "probe" =>
      if (!header(request, "CQ-Protocol-Version").contains(apiVersion)) {
        error(Status.BadRequest, ApiError.UnsupportedVersion(header(request, "CQ-Protocol-Version").getOrElse(""), List(apiVersion)))
      } else readJson(request).flatMap {
        case Left(failure) => error(Status.BadRequest, ApiError.InvalidInput(failure.getMessage))
        case Right(json) => exchange(json).flatMap {
          case Left(failure) => error(Status.BadRequest, ApiError.InvalidInput(failure.getMessage))
          case Right(value) => jsonResponse(Status.Ok, value)
        }
      }
    case GET -> Root / "ws" =>
      ws.build(_.evalMap {
        case WebSocketFrame.Text(text, _) =>
          parser.parse(text) match {
            case Left(failure) => ZIO.succeed(WebSocketFrame.Text(ApiError_JsonCodec.encode(context, ApiError.InvalidInput(failure.message)).noSpaces))
            case Right(json) => exchange(json).map {
              case Left(failure) => WebSocketFrame.Text(ApiError_JsonCodec.encode(context, ApiError.InvalidInput(failure.getMessage)).noSpaces)
              case Right(value) => WebSocketFrame.Text(value.noSpaces)
            }
          }
        case WebSocketFrame.Ping(data) => ZIO.succeed(WebSocketFrame.Pong(data))
        case _ => ZIO.succeed(WebSocketFrame.Close())
      })
    case request @ POST -> Root / "mcp" =>
      if (header(request, "MCP-Protocol-Version").exists(version => !protocolVersions.contains(version)))
        error(Status.BadRequest, ApiError.UnsupportedVersion(header(request, "MCP-Protocol-Version").get, protocolVersions))
      else readJson(request).flatMap {
        case Left(_) => rpcError(Json.Null, -32700, "Parse error")
        case Right(json) => mcp(json)
      }
    case GET -> Root / "mcp" => ZIO.succeed(Response[Task](Status.MethodNotAllowed))
    case DELETE -> Root / "mcp" => ZIO.succeed(Response[Task](Status.MethodNotAllowed))
  }

  private def rpcResult(id: Json, result: Json): Task[Response[Task]] =
    jsonResponse(Status.Ok, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "result" -> result))

  private def rpcError(id: Json, code: Int, message: String): Task[Response[Task]] =
    jsonResponse(Status.Ok, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
      "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> Json.fromString(message))))

  private def mcp(json: Json): Task[Response[Task]] = {
    val cursor = json.hcursor
    val id = cursor.get[Json]("id").getOrElse(Json.Null)
    val method = cursor.get[String]("method")
    if (!cursor.get[String]("jsonrpc").contains("2.0") || method.isLeft) rpcError(id, -32600, "Invalid request")
    else method.toOption.get match {
      case "notifications/initialized" if id.isNull => ZIO.succeed(Response[Task](Status.Accepted))
      case "initialize" =>
        val requested = cursor.downField("params").get[String]("protocolVersion").getOrElse("")
        val selected = if (protocolVersions.contains(requested)) requested else protocolVersions.last
        rpcResult(id, Json.obj(
          "protocolVersion" -> Json.fromString(selected),
          "capabilities" -> Json.obj("tools" -> Json.obj()),
          "serverInfo" -> Json.obj("name" -> Json.fromString("cq"), "version" -> Json.fromString("0.1.0")),
        ))
      case "ping" => rpcResult(id, Json.obj())
      case "tools/list" => rpcResult(id, Json.obj("tools" -> Json.arr(schemas.probe)))
      case "tools/call" if cursor.downField("params").get[String]("name").contains("probe") =>
        cursor.downField("params").get[Json]("arguments") match {
          case Left(_) => rpcError(id, -32602, "Missing arguments")
          case Right(arguments) => exchange(arguments).flatMap { result =>
            val (failed, body) = result match {
              case Left(failure) => (true, ApiError_JsonCodec.encode(context, ApiError.InvalidInput(failure.getMessage)))
              case Right(value) => (false, value)
            }
            rpcResult(id, Json.obj(
              "isError" -> Json.fromBoolean(failed),
              "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(body.noSpaces))),
              "structuredContent" -> body,
            ))
          }
        }
      case "tools/call" => rpcError(id, -32602, "Unknown tool")
      case _ => rpcError(id, -32601, "Method not found")
    }
  }

}

final class McpSchemas {
  val probe: Json = Json.obj(
    "name" -> Json.fromString("probe"),
    "description" -> Json.fromString("Persist and read the stack proof value in the authenticated project."),
    "inputSchema" -> objectSchema(List("project", "revision", "text"), Json.obj(
      "project" -> objectSchema(List("value"), Json.obj("value" -> Json.obj("type" -> Json.fromString("string"), "format" -> Json.fromString("uuid")))),
      "revision" -> objectSchema(List("value"), Json.obj("value" -> Json.obj("type" -> Json.fromString("string"), "pattern" -> Json.fromString("^-?[0-9]+$")))),
      "text" -> Json.obj("type" -> Json.fromString("string")),
    )),
  )

  private def objectSchema(required: List[String], properties: Json): Json = Json.obj(
    "type" -> Json.fromString("object"), "required" -> Json.arr(required.map(Json.fromString)*),
    "properties" -> properties, "additionalProperties" -> Json.False,
  )
}
