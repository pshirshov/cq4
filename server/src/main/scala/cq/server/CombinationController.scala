package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import distage.Lifecycle
import java.time.Clock
import zio.{Promise, Task, ZIO}

private[server] final class CombinationExecution(val ticket: CombinationTicket, val ready: Promise[Throwable, Unit],
  var done: Promise[Nothing, Unit], var view: CombinationStatus, var frozen: Boolean)

final class CombinationController(config: SupervisorConfig, authority: SupervisorAuthority, candidates: CandidateWorkspace, clock: Clock) {
  private val MaxWaitMillis = 20000
  private val AcknowledgementMillis = 1000L
  private var entries = Map.empty[RequestId, CombinationExecution]
  private var closing = false
  private var disabled = false
  private def target: String = config.settings.integrationTarget.getOrElse(throw DomainFailure(Fault.Invalid("No integration target is configured")))
  private def publication = new CombinationPublication(config.directory.resolve("combinations"), config.owner, config.run.attempt.id, config.run.repository, target)
  private def found(id: RequestId): CombinationExecution = entries.getOrElse(id,
    throw DomainFailure(Fault.Missing("Combination is not owned by this governing session")))
  private def available(): Unit = {
    target
    require(!closing && !disabled, "Combination admission is closed")
    require(!entries.values.exists(_.view.phase == CombinationPhase.Preparing), "A combination is active; poll it before starting another")
  }
  private def snapshot(entry: CombinationExecution): CombinationStatus = synchronized(entry.view)

  def prepare(ticket: CombinationTicket): Task[CombinationStatus] = ZIO.uninterruptibleMask { restore => for {
    ready <- Promise.make[Throwable, Unit]
    done <- Promise.make[Nothing, Unit]
    registered <- ZIO.attempt(synchronized {
      entries.get(ticket.id) match {
        case Some(entry) =>
          require(entry.ticket == ticket, "Combination request identity changed")
          if (entry.view.phase != CombinationPhase.PublicationPending) (entry, false)
          else {
            available()
            entry.done = done
            entry.view = entry.view.copy(phase = CombinationPhase.Preparing, blocker = None)
            (entry, true)
          }
        case None =>
          available()
          require(entries.size < CombinationPlans.MaxOperations, "Session combination limit reached")
          val entry = new CombinationExecution(ticket, ready, done, CombinationStatus(ticket.id, CombinationPhase.Preparing, None, None), false)
          entries = entries.updated(ticket.id, entry)
          (entry, true)
      }
    })
    (entry, execute) = registered
    _ <- if (!execute) ZIO.unit else {
      val operation = for {
        _ <- ZIO.attemptBlocking(publication.retain(ticket))
        _ <- entry.ready.succeed(())
        _ <- ZIO.attemptBlocking(publication.freeze(ticket) {
          new CombinationPreparation(authority.governor, config.owner, config.run.attempt.id, config.run.repository, target, clock)
            .prepare(ticket, candidates.observeTarget)
        })
        _ <- ZIO.succeed(synchronized { entry.frozen = true })
        preview <- ZIO.attemptBlocking(publication.publish(ticket.id, authority.collector))
        _ <- ZIO.succeed(synchronized { entry.view = CombinationStatus(ticket.id, CombinationPhase.Ready, Some(preview), None) })
      } yield ()
      operation.catchAll { error =>
        ZIO.succeed(synchronized {
          entry.view = CombinationStatus(ticket.id, if (entry.frozen) CombinationPhase.PublicationPending else CombinationPhase.Failed, None,
            Some(DispatchProjection.concise("Combination failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))))
        }) *> entry.ready.fail(error).unit
      }.ensuring(done.succeed(()).unit).forkDaemon.unit
    }
    _ <- restore(entry.ready.await).timeoutFail(new IllegalStateException("Combination ticket acknowledgement deadline exceeded; admission disabled"))(
      zio.Duration.fromMillis(AcknowledgementMillis)).tapError(_ => ZIO.succeed(synchronized { disabled = true }))
  } yield snapshot(entry) }

  def status(id: RequestId, waitMillis: Int): Task[CombinationStatus] = for {
    current <- ZIO.attempt(synchronized {
      require(waitMillis >= 0 && waitMillis <= MaxWaitMillis, "Combination wait must be 0–20000 ms")
      val entry = found(id)
      (entry, entry.done)
    })
    (entry, done) = current
    _ <- if (waitMillis == 0) ZIO.unit else done.await.timeout(zio.Duration.fromMillis(waitMillis)).unit
  } yield snapshot(entry)

  def quiescent: Boolean = synchronized(entries.values.forall(value =>
    Set(CombinationPhase.Ready, CombinationPhase.Failed)(value.view.phase)))

  def shutdown: Task[Unit] = for {
    pending <- ZIO.succeed(synchronized { closing = true; entries.values.map(_.done).toList })
    _ <- ZIO.foreachDiscard(pending)(_.await)
  } yield ()
}

object CombinationController {
  final class Resource(config: SupervisorConfig, authority: SupervisorAuthority, candidates: CandidateWorkspace, clock: Clock, watchdog: SupervisorWatchdog)
    extends Lifecycle.Of[Task, CombinationController](Lifecycle.make(ZIO.succeed(new CombinationController(config, authority, candidates, clock)))(
      value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown))
}
