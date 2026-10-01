package cq.server

import cq.host.{ExecutionDriver, ExecutionSpec, GuardianDriver, ManagedExecution, ProcessPhase}
import distage.Lifecycle
import java.nio.file.Path
import java.time.Duration
import zio.{Task, ZIO}

final class SupervisorWatchdog(config: SupervisorConfig, nanoTime: () => Long, halt: Int => Unit) extends AutoCloseable {
  private val PollMillis = 20L
  private val HostDrain = Duration.ofSeconds(10)
  private val UnresolvedExit = 75
  private val drain = config.limits.grace.plus(config.limits.kill).plus(HostDrain).toNanos
  /** Armed only once shutdown begins: a session runs for as long as its owner does. */
  private var deadline = Option.empty[Long]
  private var governor = Option.empty[ManagedExecution]
  @volatile private var draining = false
  private var closed = false
  private val monitor = Thread.ofPlatform().daemon().name("cq-supervisor-deadline").start(() => {
    var running = true
    while (running) {
      synchronized {
        if (closed) running = false
        else {
          if (governor.exists(value => Set(ProcessPhase.Settled, ProcessPhase.Uncertain)(value.status.phase))) beginShutdown()
          if (deadline.exists(nanoTime() - _ >= 0)) {
            // Forced process exit also fences retained I/O continuations; no settlement is inferred.
            halt(UnresolvedExit)
          }
        }
      }
      if (running) Thread.sleep(PollMillis)
    }
  })
  def observe(value: ManagedExecution): Unit = synchronized {
    require(governor.isEmpty && !closed, "Governing process observer is already bound or closed")
    governor = Some(value)
  }
  def beginShutdown(): Unit = synchronized {
    if (!draining) {
      deadline = Some(nanoTime() + drain)
      draining = true
    }
  }
  def stopping: Boolean = draining
  /** What is left of the drain once shutdown has begun, and the whole drain before. */
  def remaining: Duration = synchronized(Duration.ofNanos(deadline.fold(drain)(value => math.max(0L, value - nanoTime()))))
  override def close(): Unit = {
    synchronized { closed = true }
    monitor.join()
  }
}

object SupervisorWatchdog {
  final class Resource(config: SupervisorConfig) extends Lifecycle.Of[Task, SupervisorWatchdog](
    Lifecycle.make(ZIO.succeed(new SupervisorWatchdog(config, () => System.nanoTime(), Runtime.getRuntime.halt(_))))(value => ZIO.attemptBlocking(value.close()).orDie)
  )
}

final class SupervisorDriver(config: SupervisorConfig, watchdog: SupervisorWatchdog) extends ExecutionDriver {
  private val delegate = new GuardianDriver(Path.of(config.settings.guardian))
  private val governingInput = config.directory.resolve("payload").resolve(config.run.attempt.id.value.toString).resolve("input")
  override def start(spec: ExecutionSpec): ManagedExecution = {
    require(!watchdog.stopping, "Owning CQ session is stopping; process admission is closed")
    val execution = delegate.start(spec)
    if (spec.input == governingInput) watchdog.observe(execution)
    execution
  }
}
