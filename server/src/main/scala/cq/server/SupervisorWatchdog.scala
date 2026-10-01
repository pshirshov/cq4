package cq.server

import cq.host.{ExecutionDriver, ExecutionSpec, GuardianDriver, ManagedExecution, ProcessPhase}
import distage.Lifecycle
import java.nio.file.Path
import java.time.Duration
import zio.{Task, ZIO}

final class SupervisorWatchdog(config: SupervisorConfig) extends AutoCloseable {
  private val PollMillis = 20L
  private val HostDrain = Duration.ofSeconds(10)
  private val UnresolvedExit = 75
  private val drain = config.limits.grace.plus(config.limits.kill).plus(HostDrain).toNanos
  private var deadline = System.nanoTime() + (if (config.run.ownership == cq.api.SessionOwnership.Attached) SupervisorConfig.AttachedLifetime else config.limits.startup.plus(config.limits.execution)).toNanos + drain
  private var sweepBudget = Long.MaxValue
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
          if (System.nanoTime() - deadline >= 0) {
            // Forced process exit also fences retained I/O continuations; no settlement is inferred.
            Runtime.getRuntime.halt(UnresolvedExit)
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
      deadline = math.min(deadline, System.nanoTime() + drain)
      sweepBudget = deadline + WorkspaceCleanup.Budget.toNanos
      draining = true
    }
  }
  /** A completed workspace removal or quarantine restarts the drain window, never beyond the sweep budget; a stalled sweep still halts at the current deadline. */
  def progress(): Unit = synchronized {
    if (draining) deadline = math.min(sweepBudget, System.nanoTime() + drain)
  }
  def stopping: Boolean = draining
  override def close(): Unit = {
    synchronized { closed = true }
    monitor.join()
  }
}

object SupervisorWatchdog {
  final class Resource(config: SupervisorConfig) extends Lifecycle.Of[Task, SupervisorWatchdog](
    Lifecycle.make(ZIO.succeed(new SupervisorWatchdog(config)))(value => ZIO.attemptBlocking(value.close()).orDie)
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
