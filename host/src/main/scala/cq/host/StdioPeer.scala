package cq.host

import io.circe.{Json, parser}
import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

trait OwnerLiveness { def alive: Boolean }
final class ProcessOwner(handle: ProcessHandle) extends OwnerLiveness {
  private val started = handle.info().startInstant().orElseThrow(() => new IllegalArgumentException("Owner start identity is unavailable"))
  override def alive: Boolean = handle.isAlive && handle.info().startInstant().filter(_ == started).isPresent
  val pid: Long = handle.pid()
  val startMillis: Long = started.toEpochMilli
}

final case class PeerLimits(startup: Duration, heartbeat: Duration, reply: Duration, operation: Duration, frameBytes: Int, queued: Int)

final class StdioPeer(input: InputStream, output: OutputStream, owner: OwnerLiveness, limits: PeerLimits,
  onClosing: () => Unit) extends AutoCloseable {
  private val PollMillis = 100L
  private val incoming = new ArrayBlockingQueue[Json](limits.queued)
  private val outgoing = new ArrayBlockingQueue[Array[Byte]](limits.queued)
  private val stopped = new AtomicReference[Option[String]](None)
  private val began = System.nanoTime()
  private var initialized = false
  private var lastPing = began
  private var sequence = 0L
  private var awaiting = Option.empty[(String, Long)]
  private var operation = Option.empty[Long]
  def reason: Option[String] = stopped.get()
  private def stop(message: String): Unit = if (stopped.compareAndSet(None, Some(message))) onClosing()
  def initialize(): Unit = synchronized { require(!initialized, "MCP connection is already initialized"); initialized = true }
  def beginOperation(): Unit = synchronized {
    require(reason.isEmpty && operation.isEmpty, "Attached MCP admission is closed or busy")
    operation = Some(System.nanoTime())
  }
  def endOperation(): Unit = synchronized { operation = None }
  def send(value: Json): Unit = {
    val bytes = (value.noSpaces + "\n").getBytes(UTF_8)
    require(bytes.length <= limits.frameBytes, "MCP response exceeds its frame bound")
    if (reason.nonEmpty || !outgoing.offer(bytes)) {
      stop("MCP output queue is full or closed")
      throw new IllegalStateException("MCP output queue is full or closed")
    }
  }
  def receive(): Option[Json] = {
    while (reason.isEmpty) {
      val value = incoming.poll(PollMillis, TimeUnit.MILLISECONDS)
      if (value != null) return Some(value)
    }
    None
  }
  private def response(value: Json): Boolean = synchronized {
    val cursor = value.hcursor
    if (cursor.downField("method").focus.nonEmpty) false
    else {
      require(cursor.get[String]("jsonrpc") == Right("2.0") && awaiting.exists(pair => cursor.get[String]("id") == Right(pair._1)) &&
        cursor.downField("result").focus.contains(Json.obj()) && cursor.downField("error").focus.isEmpty, "Unexpected MCP heartbeat response")
      awaiting = None
      true
    }
  }
  private def thread(name: String)(effect: => Unit): Thread = Thread.ofPlatform().daemon().name(name).start(() => {
    try effect catch { case error: Exception => stop(name + ": " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(300)) }
  })
  private val reader = thread("cq-attached-input") {
    val frame = new ByteArrayOutputStream()
    while (reason.isEmpty) {
      val value = input.read()
      if (value == -1) stop(if (frame.size() == 0) "Owner closed the MCP input" else "Owner closed a partial MCP frame")
      else if (value == '\n') {
        val text = UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(frame.toByteArray)).toString
        frame.reset()
        val decoded = parser.parse(text).fold(throw _, identity)
        if (!response(decoded)) require(incoming.offer(decoded), "MCP input queue is full")
      } else {
        require(frame.size() < limits.frameBytes, "MCP input exceeds its frame bound")
        frame.write(value)
      }
    }
  }
  private val writer = thread("cq-attached-output") {
    while (reason.isEmpty) {
      val bytes = outgoing.poll(PollMillis, TimeUnit.MILLISECONDS)
      if (bytes != null) { output.write(bytes); output.flush() }
    }
  }
  private val monitor = thread("cq-attached-owner") {
    while (reason.isEmpty) {
      if (!owner.alive) stop("Owning harness process exited")
      synchronized {
        val now = System.nanoTime()
        if (!initialized && now - began >= limits.startup.toNanos) stop("MCP initialization deadline exceeded")
        else if (operation.exists(started => now - started >= limits.operation.toNanos)) stop("Attached MCP operation deadline exceeded")
        else if (awaiting.exists(pair => now - pair._2 >= limits.reply.toNanos)) stop("Owning harness heartbeat expired")
        else if (initialized && awaiting.isEmpty && now - lastPing >= limits.heartbeat.toNanos) {
          sequence += 1
          val id = "cq-heartbeat-" + sequence
          awaiting = Some(id -> now); lastPing = now
          send(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromString(id), "method" -> Json.fromString("ping")))
        }
      }
      Thread.sleep(PollMillis)
    }
  }
  override def close(): Unit = stop("CQ host closed the connection")
}
