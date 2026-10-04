package cq.server

import cq.api.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.util.Try

enum InstallationState { case Current, Failed, Unknown }
final case class InstallationCheck(name: String, state: InstallationState, detail: String)
final case class InstallationReport(scope: String, checks: List[InstallationCheck]) {
  def current: Boolean = checks.nonEmpty && checks.forall(_.state == InstallationState.Current)
}
final class InstallationNeedsAttention extends RuntimeException("Installation needs attention; see doctor report")

trait InstallationReader {
  def read(endpoint: URI, token: String): InstallationInfo
}
final class HttpInstallationReader extends InstallationReader {
  private val MaxBytes = 16384
  private val Deadline = Duration.ofSeconds(10)
  override def read(endpoint: URI, token: String): InstallationInfo = {
    val client = HttpClient.newBuilder().connectTimeout(Deadline).build()
    try {
      val request = HttpRequest.newBuilder(endpoint.resolve("/api/installation")).timeout(Deadline)
        .header("Authorization", "Bearer " + token).header("CQ-Session", UUID.randomUUID().toString).GET().build()
      val pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MaxBytes.toLong))
      val response = try pending.get(Deadline.toMillis, TimeUnit.MILLISECONDS) catch {
        case interrupted: InterruptedException => Thread.currentThread().interrupt(); throw interrupted
      } finally if (!pending.isDone) pending.cancel(true)
      require(response.statusCode() == 200, s"Installation endpoint returned HTTP ${response.statusCode()}")
      Wire.decode(InstallationInfo_JsonCodec, new String(response.body(), UTF_8))
    } finally client.shutdownNow()
  }
}

final class InstallationDoctor(reader: InstallationReader, schema: SchemaIdentity, source: SourceBuild) {
  def server(endpoint: URI, environment: Map[String, String], requireSettled: Boolean): InstallationReport = {
    require(Set("http", "https")(endpoint.getScheme) && endpoint.getHost != null && endpoint.getUserInfo == null &&
      endpoint.getRawQuery == null && endpoint.getRawFragment == null && (endpoint.getPath.isEmpty || endpoint.getPath == "/"), "Doctor endpoint must be an HTTP(S) origin")
    val token = Try(ServerCredentials.token(environment))
    val credential = InstallationCheck("Credential", if (token.isSuccess) InstallationState.Current else InstallationState.Failed,
      if (token.isSuccess) "Readable operator credential; server acceptance is checked separately" else "Credential is missing, unreadable or invalid; contents are not shown")
    val observed = token.flatMap(value => Try(reader.read(endpoint, value)))
    val checks = observed.toOption match {
      case None => List(InstallationCheck("Server", InstallationState.Failed, "Cannot obtain authenticated installation diagnostics; verify endpoint, operator credential and server availability"))
      case Some(value) =>
        def check(name: String, success: Boolean, detail: String): InstallationCheck =
          InstallationCheck(name, if (success) InstallationState.Current else InstallationState.Failed, detail)
        List(check("Server", true, s"Authenticated installation endpoint at $endpoint"),
          check("Model", value.version == Command.baboonDomainVersion, s"Server ${value.version}; package ${Command.baboonDomainVersion}"),
          InstallationCheck("Source", (source, value.source) match {
            case (SourceBuild.Clean(expected), SourceBuild.Clean(actual)) => if (expected == actual) InstallationState.Current else InstallationState.Failed
            case _ => InstallationState.Unknown
          }, s"Package $source; server ${value.source}. Modified or undetermined inputs cannot establish source equality."),
          check("Schema", value.schemaSha256 == schema.sha256 && value.appliedSchemaSha256 == schema.sha256,
            s"Package ${schema.sha256}; server ${value.schemaSha256}; applied ${value.appliedSchemaSha256}"),
          check("PostgreSQL", value.postgresMajor == 18, s"Server major ${value.postgresMajor}; package verified against 18"),
          check("Durability", value.fsync && value.synchronousCommit == "on" && value.fullPageWrites,
            s"fsync=${value.fsync}; synchronous_commit=${value.synchronousCommit}; full_page_writes=${value.fullPageWrites}; durable deployment requires all enabled"),
          check("Unsettled work", !requireSettled || value.activeClaims == 0 && value.managedAttempts == 0 && value.pendingIntegrations == 0,
            s"Active claims ${value.activeClaims}; managed attempts ${value.managedAttempts}; pending integrations ${value.pendingIntegrations}. Stop attached consumer harnesses before replacing a server."))
    }
    InstallationReport("server", credential :: checks)
  }
}
