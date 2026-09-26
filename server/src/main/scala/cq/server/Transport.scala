package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.DomainFailure
import io.circe.{Json, parser}
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString
import zio.{Task, ZIO}
import zio.interop.catz.*
import java.nio.charset.StandardCharsets.UTF_8

final class Transport(application: Application, authorization: Authorization, access: AccessConfig, schemas: McpSchemas, live: LiveSession, assets: StaticAssets)
    extends Http4sDsl[Task] {
  private val context = BaboonCodecContext.Default
  private val protocolVersions = List("2025-03-26", "2025-06-18", "2025-11-25")
  private val apiVersion = Command.baboonDomainVersion
  private val MaxRequestBytes = 2 * 1024 * 1024
  private val CookieName = "cq_session"

  private def jsonResponse(status: Status, json: Json): Task[Response[Task]] =
    ZIO.succeed(Response[Task](status).withEntity(json.noSpaces).withContentType(`Content-Type`(MediaType.application.json)))
  private def encoded[A](status: Status, codec: BaboonJsonCodec[A], value: A): Task[Response[Task]] =
    jsonResponse(status, codec.encode(context, value))
  private def header(request: Request[Task], name: String): Option[String] =
    request.headers.get(CIString(name)).map(_.head.value)
  private def bearer(request: Request[Task]): Option[String] =
    header(request, "Authorization").filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer "))
  private def authenticate(request: Request[Task]): Task[Authority] = ZIO.attempt {
    val token = bearer(request).orElse(request.cookies.find(_.name == CookieName).map(_.content))
      .getOrElse(throw DomainFailure(Fault.Denied("Credential required")))
    if (bearer(request).isEmpty && (request.method != Method.GET || request.uri.path.renderString == "/ws") &&
      !header(request, "Origin").contains(access.origin)) throw DomainFailure(Fault.Denied("Same-origin browser request required"))
    authorization.authenticate(token, header(request, "CQ-Session"))
  }
  private def readJson(request: Request[Task]): Task[Json] = request.body.take(MaxRequestBytes + 1L).compile.toVector.flatMap { bytes =>
    if (bytes.size > MaxRequestBytes) ZIO.fail(DomainFailure(Fault.Limit("Request exceeds 2 MiB")))
    else ZIO.fromEither(parser.parse(new String(bytes.toArray, UTF_8))).mapError(_ => DomainFailure(Fault.Invalid("Malformed JSON")))
  }
  private def decode[A](codec: BaboonJsonCodec[A], json: Json): Task[A] =
    ZIO.fromEither(codec.decode(context, json)).mapError(_ => DomainFailure(Fault.Invalid("Payload does not match CQ schema")))
  private def version(request: Request[Task]): Task[Unit] =
    if (header(request, "CQ-Protocol-Version").contains(apiVersion)) ZIO.unit
    else ZIO.fail(DomainFailure(Fault.Invalid(s"CQ-Protocol-Version must be $apiVersion")))
  private def guarded(effect: Task[Response[Task]]): Task[Response[Task]] = effect.catchSome {
    case DomainFailure(fault) => encoded(fault match {
      case _: Fault.Denied => Status.Unauthorized
      case _: Fault.Limit => Status.PayloadTooLarge
      case _ => Status.BadRequest
    }, Fault_JsonCodec, fault)
  }
  private def cookie(value: String, maxAge: Long): Header.Raw = Header.Raw(CIString("Set-Cookie"),
    s"$CookieName=$value; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge" + (if (access.origin.startsWith("https:")) "; Secure" else ""))

  def routes(ws: WebSocketBuilder2[Task]): HttpRoutes[Task] = HttpRoutes.of[Task] {
    case request if header(request, "Origin").exists(_ != access.origin) => encoded(Status.Forbidden, Fault_JsonCodec, Fault.Denied("Origin rejected"))
    case GET -> Root => assets.page
    case GET -> Root / "app.js" => assets.javascript
    case GET -> Root / "style.css" => assets.stylesheet
    case request @ POST -> Root / "api" / "login" => guarded {
      ZIO.attempt(authorization.login(bearer(request).getOrElse(""), header(request, "CQ-Session").getOrElse(""))).flatMap { token =>
        encoded(Status.Ok, AccessToken_JsonCodec, token).map(_.putHeaders(cookie(token.value, 12L * 60 * 60)))
      }
    }
    case request @ POST -> Root / "api" / "logout" => guarded {
      authenticate(request).as(Response[Task](Status.NoContent).putHeaders(cookie("", 0L)))
    }
    case request @ GET -> Root / "api" / "hello" => guarded {
      authenticate(request) *> encoded(Status.Ok, ProtocolHello_JsonCodec, ProtocolHello(apiVersion, List(apiVersion)))
    }
    case request @ POST -> Root / "api" / "grant" => guarded {
      for {
        authority <- authenticate(request)
        _ <- version(request)
        json <- readJson(request)
        grant <- decode(GrantRequest_JsonCodec, json)
        token <- ZIO.attempt(authorization.grant(authority, grant))
        response <- encoded(Status.Ok, AccessToken_JsonCodec, token)
      } yield response
    }
    case request @ POST -> Root / "api" / "call" => guarded {
      for {
        authority <- authenticate(request)
        _ <- version(request)
        json <- readJson(request)
        command <- decode(Command_JsonCodec, json)
        result <- application.execute(authority, command)
        response <- encoded(Status.Ok, Result_JsonCodec, result)
      } yield response
    }
    case request @ POST -> Root / "api" / "artifact" => guarded {
      for {
        authority <- authenticate(request)
        _ <- version(request)
        json <- readJson(request)
        input <- decode(ArtifactUpload_JsonCodec, json)
        result <- application.upload(authority, input)
        response <- encoded(Status.Ok, ArtifactMetadata_JsonCodec, result)
      } yield response
    }
    case request @ POST -> Root / "api" / "usage" => guarded {
      for {
        authority <- authenticate(request)
        _ <- version(request)
        json <- readJson(request)
        input <- decode(HostUsageInput_JsonCodec, json)
        result <- application.ingest(authority, input)
        response <- encoded(Status.Ok, HostUsageResult_JsonCodec, result)
      } yield response
    }
    case request @ GET -> Root / "ws" => guarded(authenticate(request).flatMap(live.open(ws, _)))
    case request @ POST -> Root / "mcp" => guarded {
      authenticate(request).flatMap { authority =>
        if (header(request, "MCP-Protocol-Version").exists(v => !protocolVersions.contains(v)))
          encoded(Status.BadRequest, Fault_JsonCodec, Fault.Invalid("Unsupported MCP protocol version"))
        else readJson(request).foldZIO(_ => rpcError(Json.Null, -32700, "Parse error"), mcp(authority, _))
      }
    }
    case request @ GET -> Root / "mcp" => guarded(authenticate(request).as(Response[Task](Status.MethodNotAllowed)))
    case request @ DELETE -> Root / "mcp" => guarded(authenticate(request).as(Response[Task](Status.MethodNotAllowed)))
  }

  private def rpcResult(id: Json, result: Json): Task[Response[Task]] =
    jsonResponse(Status.Ok, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "result" -> result))
  private def rpcError(id: Json, code: Int, message: String): Task[Response[Task]] =
    jsonResponse(Status.Ok, Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
      "error" -> Json.obj("code" -> Json.fromInt(code), "message" -> Json.fromString(message))))

  private def mcp(authority: Authority, json: Json): Task[Response[Task]] = {
    val cursor = json.hcursor
    val id = cursor.get[Json]("id").getOrElse(Json.Null)
    val method = cursor.get[String]("method")
    if (!cursor.get[String]("jsonrpc").contains("2.0") || method.isLeft || !(id.isNull || id.isString || id.isNumber)) rpcError(id, -32600, "Invalid request")
    else method.toOption.get match {
      case "notifications/initialized" if id.isNull => ZIO.succeed(Response[Task](Status.Accepted))
      case "initialize" =>
        val requested = cursor.downField("params").get[String]("protocolVersion").getOrElse("")
        val selected = if (protocolVersions.contains(requested)) requested else protocolVersions.last
        rpcResult(id, Json.obj(
          "protocolVersion" -> Json.fromString(selected), "capabilities" -> Json.obj("tools" -> Json.obj()),
          "serverInfo" -> Json.obj("name" -> Json.fromString("cq"), "version" -> Json.fromString(apiVersion)),
        ))
      case "ping" => rpcResult(id, Json.obj())
      case "tools/list" => rpcResult(id, Json.obj("tools" -> Json.arr(schemas.visible(authority).map(schemas.advertised)*)))
      case "tools/call" =>
        val name = cursor.downField("params").get[String]("name").toOption
        val tool = schemas.visible(authority).find(t => name.contains(t.name))
        (tool, cursor.downField("params").get[Json]("arguments")) match {
          case (Some(selected), Right(arguments)) => selected.decode(arguments) match {
            case Left(_) => rpcError(id, -32602, "Arguments do not match tool schema")
            case Right(command) => application.execute(authority, command).flatMap { result =>
              val body = Result_JsonCodec.encode(context, result)
              rpcResult(id, Json.obj("isError" -> Json.fromBoolean(result.isInstanceOf[Result.Failed]),
                "content" -> Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(body.noSpaces))), "structuredContent" -> body))
            }
          }
          case _ => rpcError(id, -32602, "Unavailable tool or missing arguments")
        }
      case _ => rpcError(id, -32601, "Method not found")
    }
  }
}
