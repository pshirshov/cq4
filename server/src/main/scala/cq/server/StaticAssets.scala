package cq.server

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using
import org.http4s.{MediaType, Response, Status}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import zio.{Task, ZIO}
import zio.interop.catz.*

final class StaticAssets {
  private def load(name: String): String = Using.resource(Option(getClass.getResourceAsStream(s"/web/$name"))
    .getOrElse(throw new IllegalStateException(s"Missing browser asset $name; run npm run build")))(stream => new String(stream.readAllBytes(), UTF_8))
  private val index = load("index.html")
  private val script = load("app.js")
  private val style = load("style.css")

  private def response(body: String, media: MediaType): Task[Response[Task]] = ZIO.succeed(
    Response[Task](Status.Ok).withEntity(body).withContentType(`Content-Type`(media)).putHeaders(
      org.http4s.Header.Raw(CIString("Cache-Control"), "no-store"),
      org.http4s.Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
      org.http4s.Header.Raw(CIString("Content-Security-Policy"), "default-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"),
    )
  )
  def page: Task[Response[Task]] = response(index, MediaType.text.html)
  def javascript: Task[Response[Task]] = response(script, MediaType.application.javascript)
  def stylesheet: Task[Response[Task]] = response(style, MediaType.text.css)
}
