package cq.host

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.DomainFailure
import io.circe.parser.parse
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}

trait ServerApi {
  def call(command: Command): Result
  def usage(input: HostUsageInput): HostUsageResult
  def artifact(input: ArtifactUpload): ArtifactMetadata
  def grant(input: GrantRequest): AccessToken
  def admit(input: HostAdmissionInput): ResultAdmission
}

final class HttpServerApi(endpoint: URI, token: String, session: SessionId, timeout: Duration) extends ServerApi {
  private val MaxBytes = 2 * 1024 * 1024
  require(Set("http", "https").contains(endpoint.getScheme) && endpoint.getHost != null && endpoint.getUserInfo == null &&
    endpoint.getRawQuery == null && endpoint.getRawFragment == null && (endpoint.getPath.isEmpty || endpoint.getPath == "/"), "Endpoint must be an HTTP(S) origin")
  require(token.nonEmpty && !timeout.isZero && !timeout.isNegative, "Credential and positive HTTP deadline required")

  private def post[A, B](path: String, inputCodec: BaboonJsonCodec[A], input: A, outputCodec: BaboonJsonCodec[B]): B = {
    val body = inputCodec.encode(BaboonCodecContext.Default, input).noSpaces.getBytes(UTF_8)
    require(body.length <= MaxBytes, "HTTP request exceeds 2 MiB")
    val request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout)
      .header("Authorization", "Bearer " + token).header("CQ-Session", session.value.toString)
      .header("CQ-Protocol-Version", Command.baboonDomainVersion).header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
    val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    try {
      val pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MaxBytes.toLong))
      val response = try pending.get(timeout.toMillis, TimeUnit.MILLISECONDS) catch {
        case failure: TimeoutException =>
          pending.cancel(true)
          throw new IllegalStateException("HTTP response deadline exceeded", failure)
        case failure: InterruptedException =>
          pending.cancel(true)
          Thread.currentThread().interrupt()
          throw failure
        case failure: ExecutionException if failure.getCause.isInstanceOf[HttpTimeoutException] =>
          throw new IllegalStateException("HTTP response deadline exceeded", failure)
      }
      val text = new String(response.body(), UTF_8)
      val json = parse(text).fold(throw _, identity)
      if (response.statusCode() != 200) {
        Fault_JsonCodec.decode(BaboonCodecContext.Default, json) match {
          case Right(fault) => throw DomainFailure(fault)
          case Left(_) => throw new IllegalStateException(s"HTTP ${response.statusCode()}: unexpected error envelope")
        }
      }
      outputCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    } finally client.shutdownNow()
  }

  override def call(command: Command): Result = post("/api/call", Command_JsonCodec, command, Result_JsonCodec)
  override def usage(input: HostUsageInput): HostUsageResult = post("/api/usage", HostUsageInput_JsonCodec, input, HostUsageResult_JsonCodec)
  override def artifact(input: ArtifactUpload): ArtifactMetadata = post("/api/artifact", ArtifactUpload_JsonCodec, input, ArtifactMetadata_JsonCodec)
  override def grant(input: GrantRequest): AccessToken = post("/api/grant", GrantRequest_JsonCodec, input, AccessToken_JsonCodec)
  override def admit(input: HostAdmissionInput): ResultAdmission = post("/api/admission", HostAdmissionInput_JsonCodec, input, ResultAdmission_JsonCodec)
}
