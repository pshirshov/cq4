package cq.host

import cq.api.StopReason
import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.{CompletableFuture, FutureTask, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters.*

/**
 * `execution` is the wall-clock deadline of a host command (a configured check, the Git job); a harness job has none and runs until it exits,
 * is cancelled or loses its owner. `retainedOutputBytes` bounds what the host publishes of each output stream; it never stops the process
 * (see `OutputCeilingBytes`).
 */
final case class ExecutionLimits(startup: Duration, execution: Option[Duration], heartbeat: Duration, grace: Duration, kill: Duration, retainedOutputBytes: Int) {
  require((List(startup, heartbeat, grace, kill) ++ execution).forall(d => d.toMillis > 0 && d.toMillis <= ExecutionLimits.MaximumMillis), "Invalid process deadline")
  require(heartbeat.toMillis >= 300, "Driver heartbeat deadline must be at least 300 ms")
  require(retainedOutputBytes > 0 && retainedOutputBytes <= 64 * 1024 * 1024, "Invalid retained output bound")
}

object ExecutionLimits {
  val MaximumMillis: Long = Duration.ofHours(24).toMillis
  /**
   * Disk-safety ceiling of one output stream on disk, fixed for every job: a process that writes more is stopped with
   * `StopReason.OutputLimit`. It exists only so that a runaway writer cannot fill the state volume.
   */
  val OutputCeilingBytes: Long = 1024L * 1024 * 1024
}

final case class ExecutionSpec(directory: Path, arguments: List[String], environment: Map[String, String], input: Path,
  stdout: Path, stderr: Path, limits: ExecutionLimits)

enum ProcessPhase { case Starting, Running, Stopping, Settled, Uncertain }
final case class ProcessObservation(phase: ProcessPhase, cancellationRequested: Boolean, helperPid: Option[Long],
  rootPid: Option[Long], result: Option[GuardianExit], problem: Option[String])

trait ManagedExecution extends AutoCloseable {
  def status: ProcessObservation
  def cancel(): ProcessObservation
  def await(timeout: Duration): ProcessObservation
}

trait ExecutionDriver {
  def start(spec: ExecutionSpec): ManagedExecution
}

final class GuardianDriver(binary: Path) extends ExecutionDriver {
  override def start(spec: ExecutionSpec): ManagedExecution = {
    require(binary.isAbsolute && spec.directory.isAbsolute && List(spec.input, spec.stdout, spec.stderr).forall(_.isAbsolute), "Guardian paths must be absolute")
    require(spec.arguments.nonEmpty && spec.arguments.forall(v => !v.contains('\u0000')), "Invalid command arguments")
    new Execution(binary, spec)
  }

  private final class Execution(binary: Path, spec: ExecutionSpec) extends ManagedExecution {
    private val PollMillis = 20L
    private val DrainMillis = 2000L
    private val MaxProtocolBytes = 4096
    private val MaxLineBytes = 512
    private val NoExecutionDeadline = 0L
    private val observation = new AtomicReference(ProcessObservation(ProcessPhase.Starting, false, None, None, None, None))
    private val active = new AtomicBoolean(true)
    private val process = new AtomicReference[Option[Process]](None)
    private val launch = new CompletableFuture[Process]()
    private val completed = new CompletableFuture[ProcessObservation]()
    private val transcript = new AtomicReference(GuardianTranscript(None, None, None))
    private val began = System.nanoTime()

    Thread.ofVirtual().name("cq-guardian-launch").start(() => {
      try {
        val limits = spec.limits
        val arguments = List(binary.toString, limits.startup.toMillis.toString, limits.execution.fold(NoExecutionDeadline)(_.toMillis).toString,
          limits.heartbeat.toMillis.toString, limits.grace.toMillis.toString, limits.kill.toMillis.toString,
          ExecutionLimits.OutputCeilingBytes.toString, spec.input.toString, spec.stdout.toString, spec.stderr.toString, "--") ++ spec.arguments
        val builder = new ProcessBuilder(arguments.asJava).directory(spec.directory.toFile)
        builder.environment().clear()
        builder.environment().putAll(spec.environment.asJava)
        val child = builder.start()
        process.set(Some(child))
        launch.complete(child)
        if (!active.get()) { cleanup(child); child.getOutputStream.close() }
      } catch { case failure: Exception => launch.completeExceptionally(failure) }
    })
    Thread.ofVirtual().name("cq-guardian-monitor").start(() => monitor())

    override def status: ProcessObservation = observation.get()
    override def cancel(): ProcessObservation = observation.updateAndGet { previous =>
      previous.phase match {
        case ProcessPhase.Settled | ProcessPhase.Uncertain => previous
        case _ => previous.copy(cancellationRequested = true, phase = ProcessPhase.Stopping)
      }
    }
    override def await(timeout: Duration): ProcessObservation = completed.get(timeout.toMillis, TimeUnit.MILLISECONDS)
    override def close(): Unit = {
      cancel()
      await(spec.limits.startup.plus(spec.limits.heartbeat).plus(spec.limits.grace).plus(spec.limits.kill).plusMillis(DrainMillis * 2))
      ()
    }

    private def uncertain(message: String): Unit = {
      observation.updateAndGet(_.copy(phase = ProcessPhase.Uncertain, cancellationRequested = true, problem = Some(message.take(300))))
      active.set(false)
      process.get().foreach(cleanup)
    }

    private def cleanup(child: Process): Unit = {
      Thread.ofVirtual().name("cq-guardian-uncertain-cleanup").start(() => {
        val deadline = spec.limits.heartbeat.plus(spec.limits.grace).plus(spec.limits.kill).plusMillis(DrainMillis)
        if (!child.waitFor(deadline.toMillis, TimeUnit.MILLISECONDS)) child.destroyForcibly()
      })
      ()
    }

    private def drain(input: InputStream, protocol: Boolean): FutureTask[Unit] = {
      val task = new FutureTask[Unit](() => {
        val bytes = new BufferedInputStream(input)
        val line = new ByteArrayOutputStream()
        var total = 0
        try {
          var value = bytes.read()
          while (value != -1) {
            total += 1
            require(total <= MaxProtocolBytes, "Guardian diagnostic/protocol output exceeds bound")
            if (protocol) {
              require(value <= 127, "Guardian protocol must be ASCII")
              if (value == '\n') {
                val next = transcript.updateAndGet(_.append(line.toString(US_ASCII)))
                line.reset()
                observation.updateAndGet { previous =>
                  val phase = if (next.stop.nonEmpty || previous.cancellationRequested) ProcessPhase.Stopping else ProcessPhase.Running
                  previous.copy(phase = if (previous.phase == ProcessPhase.Uncertain) previous.phase else phase, rootPid = next.root)
                }
              } else {
                require(line.size() < MaxLineBytes, "Guardian lifecycle line exceeds bound")
                line.write(value)
              }
            }
            value = bytes.read()
          }
          require(!protocol || line.size() == 0, "Unterminated guardian lifecycle line")
        } finally bytes.close()
      })
      Thread.ofVirtual().name("cq-guardian-drain").start(task)
      task
    }

    private def monitor(): Unit = {
      try {
        val child = launch.get(spec.limits.startup.toMillis, TimeUnit.MILLISECONDS)
        observation.updateAndGet(_.copy(helperPid = Some(child.pid())))
        val stdout = drain(child.getInputStream, true)
        val stderr = drain(child.getErrorStream, false)
        val writerResult = new FutureTask[Unit](() => {
          try {
            var sentCancel = false
            while (active.get() && child.isAlive && !sentCancel) {
              val stopping = status.cancellationRequested
              child.getOutputStream.write(if (stopping) 'C' else 'H')
              child.getOutputStream.flush()
              if (stopping) sentCancel = true
              else Thread.sleep(spec.limits.heartbeat.toMillis / 3)
            }
          } finally child.getOutputStream.close()
        })
        val writer = Thread.ofVirtual().name("cq-guardian-heartbeat").start(writerResult)
        try {
          val maximum = spec.limits.execution.map(spec.limits.startup.plus(_).plus(spec.limits.grace).plus(spec.limits.kill).plusMillis(DrainMillis).toNanos)
          var cancelledAt = Option.empty[Long]
          var stoppedAt = Option.empty[Long]
          while (child.isAlive && active.get()) {
            if (stdout.isDone) {
              stdout.get()
              require(transcript.get().result.nonEmpty, "Guardian lifecycle ended without a terminal record")
            }
            if (stderr.isDone) stderr.get()
            if (writerResult.isDone) writerResult.get()
            val now = System.nanoTime()
            val current = transcript.get()
            if (current.stop.nonEmpty && stoppedAt.isEmpty) stoppedAt = Some(now)
            if (status.cancellationRequested && cancelledAt.isEmpty) cancelledAt = Some(now)
            val cancelLimit = spec.limits.heartbeat.plus(spec.limits.grace).plus(spec.limits.kill).plusMillis(DrainMillis).toNanos
            val stopLimit = spec.limits.grace.plus(spec.limits.kill).plusMillis(DrainMillis).toNanos
            if (current.root.isEmpty && current.stop.isEmpty && now - began >= spec.limits.startup.toNanos)
              uncertain("Guardian start acknowledgement deadline exceeded")
            // A guardian that reported the end of its job is waited for while it is alive: how long its exit takes says nothing of the job (D155).
            else if (current.result.isEmpty && stoppedAt.exists(now - _ >= stopLimit)) uncertain("Guardian stopping deadline exceeded; process termination is unconfirmed")
            else if (maximum.exists(now - began >= _) || cancelledAt.exists(now - _ >= cancelLimit)) uncertain("Guardian cleanup deadline exceeded; process termination is unconfirmed")
            else Thread.sleep(PollMillis)
          }
          if (active.get()) {
            stdout.get(DrainMillis, TimeUnit.MILLISECONDS)
            stderr.get(DrainMillis, TimeUnit.MILLISECONDS)
            val result = transcript.get().complete(child.exitValue())
            observation.updateAndGet(_.copy(phase = if (result.settled && !result.hostFailure) ProcessPhase.Settled else ProcessPhase.Uncertain,
              result = Some(result), problem = if (result.settled && !result.hostFailure) None else Some("Guardian could not confirm complete cleanup and output retention")))
          }
        } finally writer.interrupt()
      } catch {
        case failure: Exception => uncertain(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))
      } finally {
        active.set(false)
        completed.complete(status)
      }
    }

  }
}
