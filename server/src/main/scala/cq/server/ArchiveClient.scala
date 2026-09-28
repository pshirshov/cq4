package cq.server

import cq.api.*
import cq.core.DomainFailure
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.zip.ZipInputStream
import scala.util.Using

private[server] object ArchiveFiles {
  def sha256(path: Path): String = Using.resource(Files.newInputStream(path)) { input =>
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](ArchiveLimits.BufferBytes)
    var count = input.read(buffer)
    while (count != -1) { digest.update(buffer, 0, count); count = input.read(buffer) }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }
  def manifest(path: Path): BackupManifest = Using.resource(new ZipInputStream(Files.newInputStream(path))) { zip =>
    require(Option(zip.getNextEntry).exists(_.getName == "manifest.json"), "Server returned an invalid archive")
    val bytes = zip.readNBytes(ArchiveLimits.ManifestBytes + 1)
    require(bytes.length <= ArchiveLimits.ManifestBytes, "Archive manifest exceeds its limit")
    Wire.decode(BackupManifest_JsonCodec, new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
  }
}

final class ArchiveClient(endpoint: URI, token: String, session: SessionId) {
  private val Deadline = Duration.ofMinutes(5)
  private def send(path: String, source: Option[Path], destination: Path, maximum: Long): HttpResponse[Path] = {
    val builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Deadline)
      .header("Authorization", "Bearer " + token).header("CQ-Session", session.value.toString)
      .header("CQ-Protocol-Version", Command.baboonDomainVersion)
    val request = source.fold(builder.GET())(file => builder.header("Content-Type", "application/zip").POST(HttpRequest.BodyPublishers.ofFile(file))).build()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    try {
      val pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(destination), maximum))
      val response = try pending.get(Deadline.toMillis, TimeUnit.MILLISECONDS) catch {
        case failure: TimeoutException => pending.cancel(true); throw new IllegalStateException("Archive transfer deadline exceeded; verify the project before retrying restore", failure)
        case failure: InterruptedException => pending.cancel(true); Thread.currentThread().interrupt(); throw failure
      }
      if (response.statusCode() != 200) {
        require(Files.size(destination) <= ArchiveLimits.ManifestBytes, s"HTTP ${response.statusCode()}: oversized archive error response")
        throw DomainFailure(Wire.decode(Fault_JsonCodec, Files.readString(destination)))
      }
      response
    } finally client.shutdownNow()
  }
  def backup(project: ProjectId, destination: Path): BackupManifest = {
    require(!Files.exists(destination), "Backup destination already exists")
    val temporary = Files.createTempFile(destination.getParent, ".cq-backup-", ".zip")
    try {
      val response = send(s"/api/backup/${project.value}", None, temporary, ArchiveLimits.MaxBytes)
      require(response.headers().firstValue("CQ-Archive-SHA256").orElse("") == ArchiveFiles.sha256(temporary), "Archive transfer checksum mismatch")
      val manifest = ArchiveFiles.manifest(temporary)
      require(manifest.project == project, "Server returned another project's archive")
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
      // Linking the completed file is atomic and refuses even a concurrently created destination.
      Files.createLink(destination, temporary)
      Using.resource(FileChannel.open(destination.getParent, StandardOpenOption.READ))(_.force(true))
      manifest
    } finally Files.deleteIfExists(temporary)
  }
  def restore(source: Path): BackupManifest = {
    require(Files.isRegularFile(source), "Restore requires a project archive file")
    ArchiveLimits.bounded(Files.size(source))
    val temporary = Files.createTempFile("cq-restore-reply-", ".json")
    try {
      send("/api/restore", Some(source), temporary, ArchiveLimits.ManifestBytes)
      Wire.decode(BackupManifest_JsonCodec, Files.readString(temporary))
    } finally Files.deleteIfExists(temporary)
  }
}
