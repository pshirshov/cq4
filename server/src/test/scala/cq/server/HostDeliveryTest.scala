package cq.server

import cq.api.*
import cq.host.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.{Base64, UUID}
import java.time.Duration
import org.scalatest.wordspec.AnyWordSpec

final class HostDeliveryLocal extends AnyWordSpec {
  private def project: ProjectId = ProjectId(UUID.randomUUID())
  private def attempt: AttemptId = AttemptId(UUID.randomUUID())
  private final class Receiver extends ServerApi {
    var values = Map.empty[ArtifactId, ArtifactUpload]
    var calls = 0
    var loseAcknowledgement = true
    override def artifact(value: ArtifactUpload): ArtifactMetadata = {
      calls += 1
      require(values.get(value.id).forall(_ == value), "Artifact replay changed its content")
      values = values.updated(value.id, value)
      if (loseAcknowledgement) { loseAcknowledgement = false; throw new IOException("Acknowledgement lost after publication") }
      ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture", value.body.getBytes(UTF_8).length,
        value.body.codePointCount(0, value.body.length), Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
    }
    override def call(value: Command): Result = throw new IllegalStateException("Not a publication operation")
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Not used in artifact replay scenario")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Publication queue cannot grant authority")
  }

  "Host delivery (Behavioral Active Blackbox; Group / filesystem Communication)" should {
    "replay the same immutable batch after lost acknowledgement and preserve receipts across reopening" in {
      val directory = Files.createTempDirectory("cq-delivery-")
      val queue = new DeliveryQueue(directory)
      val upload = ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt, ArtifactKind.Result, "text/plain", "retained λ")
      val batch = DeliveryBatch(List(HostDelivery.Artifact(upload)))
      queue.enqueue(0, batch)
      queue.enqueue(0, batch)
      intercept[IllegalArgumentException](queue.enqueue(0, DeliveryBatch(List(HostDelivery.Artifact(upload.copy(body = "changed"))))))
      val receiver = new Receiver
      intercept[IOException](queue.flush(receiver))
      assert(receiver.values == Map(upload.id -> upload) && receiver.calls == 1)
      assert(!Files.exists(directory.resolve("000000.ack")))
      assert(new DeliveryQueue(directory).flush(receiver) == 1)
      assert(receiver.values == Map(upload.id -> upload) && receiver.calls == 2)
      assert(new DeliveryQueue(directory).flush(receiver) == 0 && receiver.calls == 2)
      assert(Files.getPosixFilePermissions(directory.resolve("000000.json")) == PosixFilePermissions.fromString("rw-------"))
      Files.writeString(directory.resolve("000000.ack"), "invalid")
      intercept[IllegalArgumentException](queue.flush(receiver))
    }

    "preserve malformed and multipart native bytes behind deterministic artifact handles" in {
      val bytes = Array.tabulate[Byte](400000)(index => (index % 256).toByte)
      val p = project
      val a = attempt
      val (id, uploads) = NativeArtifacts.binary(p, a, "stdout", "application/x-ndjson", bytes)
      assert(NativeArtifacts.binary(p, a, "stdout", "application/x-ndjson", bytes) == (id, uploads))
      val manifest = Wire.decode(NativeManifest_JsonCodec, uploads.last.body)
      val parts = uploads.dropRight(1)
      assert(manifest.parts == parts.map(_.id) && manifest.bytes == bytes.length && uploads.last.id == id)
      assert(java.util.Arrays.equals(parts.flatMap(value => Base64.getDecoder.decode(value.body)).toArray, bytes))
      assert(uploads.forall(_.body.getBytes(UTF_8).length <= cq.core.ArtifactService.MaxBytes))
      val (_, empty) = NativeArtifacts.binary(p, a, "stderr", "application/octet-stream", Array.emptyByteArray)
      assert(Wire.decode(NativeManifest_JsonCodec, empty.head.body).parts.isEmpty)
    }

    "extract structured native results and reject incomplete or nonterminal output" in {
      val directory = Files.createTempDirectory("cq-native-output-")
      val parser = new HarnessOutput
      val expected = io.circe.Json.obj("summary" -> io.circe.Json.fromString("observed λ"))
      val claude = s"""{"type":"result","subtype":"success","is_error":false,"structured_output":${expected.noSpaces}}\n"""
      assert(parser.result(Harness.Claude, claude.getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Claude, claude.stripSuffix("\n").getBytes(UTF_8), directory))
      Files.writeString(directory.resolve("last-message.json"), expected.noSpaces)
      assert(parser.result(Harness.Codex, "{\"type\":\"turn.completed\"}\n".getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Codex, "{\"type\":\"turn.started\"}\n".getBytes(UTF_8), directory))
      val message = io.circe.Json.obj("type" -> io.circe.Json.fromString("message_end"), "message" -> io.circe.Json.obj(
        "role" -> io.circe.Json.fromString("assistant"), "stopReason" -> io.circe.Json.fromString("stop"), "content" -> io.circe.Json.arr(
          io.circe.Json.obj("type" -> io.circe.Json.fromString("text"), "text" -> io.circe.Json.fromString(expected.noSpaces)))))
      assert(parser.result(Harness.Pi, (message.noSpaces + "\n").getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Pi, (message.noSpaces.replace("\"stop\"", "\"error\"") + "\n").getBytes(UTF_8), directory))
    }

    "reject excessive native event counts before exhausting a 64 MiB heap" in {
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val value = new BoundedHostCommand(Map.empty, Duration.ofSeconds(15), 4096).run(Path.of("").toAbsolutePath.normalize(),
        List(java, "-Xmx64m", "-cp", System.getProperty("cq.test.classpath"), "cq.server.HarnessOutputBounds"))
      assert(value.exit == 0 && value.text.contains("BOUNDED_NATIVE_EVENT_REJECTION"), value.text)
    }
  }
}
