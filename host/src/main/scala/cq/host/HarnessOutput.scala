package cq.host

import cq.api.*
import io.circe.{Json, parser}
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.{Base64, HexFormat, UUID}

object NativeArtifacts {
  private val PartBytes = 128 * 1024
  private val MaxBytes = 32 * 1024 * 1024
  def id(attempt: AttemptId, name: String): ArtifactId =
    ArtifactId(UUID.nameUUIDFromBytes((attempt.value.toString + ":" + name).getBytes(UTF_8)))
  def binary(project: ProjectId, attempt: AttemptId, name: String, mediaType: String, bytes: Array[Byte]): (ArtifactId, List[ArtifactUpload]) = {
    require(bytes.length <= MaxBytes && name.matches("[a-z][a-z0-9-]{0,50}"), "Invalid native evidence bounds or name")
    val parts = bytes.grouped(PartBytes).zipWithIndex.map { case (part, index) =>
      ArtifactUpload(project, id(attempt, s"$name-part-$index"), attempt, ArtifactKind.Transcript, "text/plain", Base64.getEncoder.encodeToString(part))
    }.toList
    val manifestId = id(attempt, name)
    val manifest = NativeManifest("base64", mediaType, bytes.length.toLong,
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), parts.map(_.id))
    val upload = ArtifactUpload(project, manifestId, attempt, ArtifactKind.Transcript, "application/json", HostFiles.encode(NativeManifest_JsonCodec, manifest))
    (manifestId, parts :+ upload)
  }
}

/** Extracts the final structured result from a completed native output stream, read line by line whatever its length. */
final class HarnessOutput {
  private val MaxLineBytes = 1024 * 1024
  private val MaxResultBytes = 256 * 1024
  private val ReadBytes = 8192
  private val LineFeed = '\n'.toByte

  def result(harness: Harness, stdout: InputStream, assets: Path): Json = {
    var lastType = Option.empty[String]
    var claudeResult = Option.empty[Json]
    var piMessage = Option.empty[Json]
    def accept(line: Array[Byte]): Unit = if (line.nonEmpty) {
      val event = parser.parse(UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(line)).toString).fold(throw _, identity)
      require(event.isObject, "Native event must be an object")
      val cursor = event.hcursor
      lastType = cursor.get[String]("type").toOption
      if (harness == Harness.Claude && lastType.contains("result")) {
        require(claudeResult.isEmpty, "Claude emitted multiple terminal results")
        claudeResult = Some(event)
      }
      if (harness == Harness.Pi && lastType.contains("message_end") && cursor.downField("message").get[String]("role").contains("assistant"))
        piMessage = cursor.downField("message").focus
    }
    val bytes = new Array[Byte](ReadBytes)
    val line = new ByteArrayOutputStream()
    var oversized = false
    var last = Option.empty[Byte]
    var size = stdout.read(bytes)
    while (size != -1) {
      var index = 0
      while (index < size) {
        val value = bytes(index)
        if (value == LineFeed) {
          // An event beyond the line bound cannot be a result (results are bounded far below it); it is skipped as an event of unknown type,
          // which also withdraws an earlier Pi assistant message it may have superseded.
          if (oversized) { lastType = None; piMessage = None } else accept(line.toByteArray)
          line.reset()
          oversized = false
        } else if (!oversized) {
          if (line.size() == MaxLineBytes) { oversized = true; line.reset() } else line.write(value)
        }
        last = Some(value)
        index += 1
      }
      size = stdout.read(bytes)
    }
    require(last.contains(LineFeed), "Native output has an incomplete final event")
    val output = harness match {
      case Harness.Claude =>
        val result = claudeResult.getOrElse(throw new IllegalArgumentException("Claude terminal result is missing")).hcursor
        require(result.get[String]("subtype").contains("success") && result.get[Boolean]("is_error").contains(false), "Claude did not produce a successful result")
        result.downField("structured_output").focus.getOrElse(throw new IllegalArgumentException("Claude structured result is missing"))
      case Harness.Codex =>
        require(lastType.contains("turn.completed"), "Codex terminal event is missing")
        parser.parse(HostFiles.text(assets.resolve("last-message.json"), MaxResultBytes)).fold(throw _, identity)
      case Harness.Pi =>
        val message = piMessage.getOrElse(throw new IllegalArgumentException("Pi assistant result is missing")).hcursor
        require(message.get[String]("stopReason").contains("stop"), "Pi final assistant did not stop successfully")
        val content = message.get[List[Json]]("content").fold(throw _, identity)
        require(!content.exists(_.hcursor.get[String]("type").contains("toolCall")), "Pi final result contains an unfinished tool call")
        val body = content.filter(_.hcursor.get[String]("type").contains("text")).map(_.hcursor.get[String]("text").fold(throw _, identity)).mkString
        parser.parse(body).fold(throw _, identity)
    }
    require(output.noSpaces.getBytes(UTF_8).length <= MaxResultBytes, "Native result exceeds its byte bound")
    output
  }
}
