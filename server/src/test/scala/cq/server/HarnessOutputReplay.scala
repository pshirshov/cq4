package cq.server

import cq.api.*
import cq.host.{HarnessOutput, HarnessUsage, HostFiles, UsageCollectionRequest, UsageOrigin}
import java.io.{BufferedInputStream, ByteArrayInputStream, InputStream, SequenceInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.util.Using

object HarnessOutputReplay {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 10, "Pass retained successful adapter-probe directories")
    args.foreach { value =>
      val root = Path.of(value)
      val fixture = io.circe.parser.parse(HostFiles.text(root.resolve("result.json"), 65536)).fold(throw _, identity).hcursor
      require(fixture.get[String]("status").contains("passed"), "Replay requires a previously successful live probe")
      val name = fixture.get[String]("harness").fold(throw _, identity)
      val role = fixture.get[String]("role").fold(throw _, identity)
      val harness = Harness.all.find(_.toString.equalsIgnoreCase(name)).get
      val directory = root.resolve(name + "-" + role.toLowerCase)
      val job = HostFiles.read(directory.resolve("job.json"), JobRecord_JsonCodec, 65536)
      val result = Using.resource(Files.newInputStream(directory.resolve("payload").resolve(job.workspace.attempt.value.toString).resolve("stdout")))(
        new HarnessOutput().result(harness, _, directory.resolve("assets")))
      require(result.hcursor.get[String]("status").contains("ok"))
      val observed = result.hcursor.get[String]("observed").fold(throw _, identity)
      require(observed.startsWith("read-from-cq-"))
      if (role == "Worker") require(Files.readString(directory.resolve("workspaces").resolve(job.workspace.attempt.value.toString).resolve("tree/observed.txt")) == observed)
      println(s"Retained live result accepted by production parser: $harness $role")
    }
  }
}

/** Reads a synthetic Codex stream far larger than the heap: a session start, filler events, then the terminal usage event. */
object HarnessOutputBounds {
  private val StreamBytes = 256L * 1024 * 1024
  private def stream(): InputStream = {
    val head = "{\"type\":\"thread.started\",\"thread_id\":\"bounds\"}\n{\"type\":\"turn.started\"}\n".getBytes(UTF_8)
    val filler = ("{\"type\":\"item.completed\",\"text\":\"" + "x" * 4000 + "\"}\n").getBytes(UTF_8)
    val tail = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":5,\"reasoning_output_tokens\":0}}\n".getBytes(UTF_8)
    val repeated = new InputStream {
      private var position = 0L
      override def read(): Int = if (position >= StreamBytes / filler.length * filler.length) -1 else {
        val value = filler((position % filler.length).toInt) & 0xff
        position += 1
        value
      }
    }
    new SequenceInputStream(java.util.Collections.enumeration(java.util.List.of[InputStream](new ByteArrayInputStream(head), new BufferedInputStream(repeated), new ByteArrayInputStream(tail))))
  }
  def main(args: Array[String]): Unit = {
    val assets = Files.createTempDirectory("cq-native-bounds-")
    Files.writeString(assets.resolve("last-message.json"), "{\"summary\":\"streamed\"}")
    val result = new HarnessOutput().result(Harness.Codex, stream(), assets)
    val usage = new HarnessUsage().collect(stream(), UsageCollectionRequest(AttemptId(UUID.randomUUID()), Harness.Codex, HarnessUsage.version(Harness.Codex),
      UsageOrigin.Fresh, 1, ArtifactId(UUID.randomUUID())))
    require(result.noSpaces == "{\"summary\":\"streamed\"}" && usage.terminalSeen && !usage.nativeFailure && usage.meters.map(_.observations.size) == List(1),
      s"Streamed output was not collected: $result $usage")
    println("STREAMED_NATIVE_OUTPUT")
  }
}
