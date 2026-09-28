package cq.server

import cq.host.{OwnerLiveness, PeerLimits, StdioPeer}
import io.circe.{Json, parser}
import java.io.{BufferedReader, InputStreamReader, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.wordspec.AnyWordSpec

final class StdioPeerLocal extends AnyWordSpec {
  private final class Connection(heartbeat: Duration, reply: Duration, frameBytes: Int) extends AutoCloseable {
    val alive = new AtomicBoolean(true)
    val closed = new CountDownLatch(1)
    val input = new PipedInputStream(8192)
    val client = new PipedOutputStream(input)
    val response = new PipedInputStream(8192)
    val output = new PipedOutputStream(response)
    val peer = new StdioPeer(input, output, new OwnerLiveness { override def alive: Boolean = Connection.this.alive.get() },
      PeerLimits(Duration.ofSeconds(3), heartbeat, reply, Duration.ofMillis(200), frameBytes, 8), () => closed.countDown())
    def send(value: Json): Unit = { client.write((value.noSpaces + "\n").getBytes(UTF_8)); client.flush() }
    def stopped(part: String): Unit = {
      assert(closed.await(4, TimeUnit.SECONDS), "Peer did not terminate within its deadline")
      assert(peer.reason.exists(_.contains(part)), peer.reason.toString)
      assert(peer.receive().isEmpty)
    }
    override def close(): Unit = { peer.close(); client.close(); response.close(); input.close(); output.close() }
  }
  private val LongInterval = Duration.ofSeconds(10)
  private def connection = new Connection(LongInterval, LongInterval, 4096)

  "Attached transport (Behavioral Active Blackbox Group)" should {
    "exchange complete frames while independently answering heartbeat acknowledgements" in {
      val c = new Connection(Duration.ofMillis(100), Duration.ofSeconds(2), 4096)
      try {
        c.peer.initialize()
        val ping = parser.parse(new BufferedReader(new InputStreamReader(c.response, UTF_8)).readLine()).fold(throw _, identity)
        assert(ping.hcursor.get[String]("method") == Right("ping"))
        c.send(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> ping.hcursor.downField("id").focus.get, "result" -> Json.obj()))
        val request = Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1), "method" -> Json.fromString("tools/list"))
        c.send(request)
        assert(c.peer.receive().contains(request) && c.peer.reason.isEmpty)
      } finally c.close()
    }
    "close on EOF even while the owner remains alive" in {
      val c = connection
      try { c.client.close(); c.stopped("closed the MCP input") } finally c.close()
    }
    "close on owner death even while inherited pipes remain open" in {
      val c = connection
      try { c.alive.set(false); c.stopped("process exited") } finally c.close()
    }
    "close when a live but unresponsive owner does not acknowledge its heartbeat" in {
      val c = new Connection(Duration.ofMillis(100), Duration.ofMillis(200), 4096)
      try { c.peer.initialize(); c.stopped("heartbeat expired") } finally c.close()
    }
    "bound framing and reject invalid UTF-8 rather than replacing bytes" in {
      val oversized = new Connection(LongInterval, LongInterval, 16)
      try { oversized.client.write(Array.fill[Byte](17)('x'.toByte)); oversized.client.flush(); oversized.stopped("frame bound") }
      finally oversized.close()
      val invalid = connection
      try { invalid.client.write(Array[Byte](-1, 10)); invalid.client.flush(); invalid.stopped("cq-attached-input") }
      finally invalid.close()
    }
    "reject uncorrelated responses rather than treating them as requests" in {
      val c = connection
      try {
        c.send(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromString("foreign"), "result" -> Json.obj()))
        c.stopped("Unexpected MCP heartbeat response")
      } finally c.close()
    }
    "bound an in-flight operation independently of the operation's thread" in {
      val c = connection
      try { c.peer.initialize(); c.peer.beginOperation(); c.stopped("operation deadline") } finally c.close()
    }
  }
}
