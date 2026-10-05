package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import distage.Lifecycle
import java.time.{Clock, Duration}
import zio.{Promise, Semaphore, Task, ZIO}

private[server] final class RevalidationExecution(val result: ArtifactId, val fence: Fence, val done: Promise[Nothing, Unit], var view: RevalidationStatus)

/** Reruns the failed configured checks of an admitted worker result on its exact candidate and publishes the round as a
  * `ValidationAmendment` under the governing attempt. The admitted result is never changed. */
final class RevalidationController(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor, dispatch: DispatchController,
  renewal: ClaimRenewal, clock: Clock, requests: Semaphore, admission: Semaphore) {
  private val WaitMillis = 20000L
  private val units = new SessionUnits(config.directory)
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val AdmissionNanos = Duration.ofSeconds(60).toNanos
  private val validation = new HostValidation(config)
  private val execution = new GovernedIntegrationJobs(config.owner, jobs, admission)
  private val spans = new SessionSpans(config, authority)
  private var entries = Map.empty[RequestId, RevalidationExecution]
  private var closing = false
  /** `obtained` is when the admission that renewed the claim began (the ZIO clock's `nanoTime`, which `ClaimRenewal` reads). */
  private final case class Round(result: ChildResult, number: Int, failing: List[EffectiveCheck], effective: EffectiveValidation, obtained: Long)

  private def bounded[A](operation: (Command => Result) => A): A = {
    val began = System.nanoTime()
    operation { command =>
      require(System.nanoTime() - began < AdmissionNanos, "Revalidation admission deadline exceeded")
      authority.governor.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case value => value
      }
    }
  }
  private def renew(call: Command => Result, request: DispatchRequest): Unit =
    call(Command.ClaimWork(ClaimInput(config.owner.project, ClaimAction.Renew(request.fence, ClaimMillis)))) match {
      case Result.Claimed(claim) => require(claim.owner == config.owner.actor && claim.fence == request.fence && !claim.released &&
        claim.members == request.members.map(_.id).toSet && claim.expiresAt > clock.millis(), "Revalidation claim no longer covers this assignment")
      case _ => throw new IllegalStateException("Revalidation claim renewal returned an unexpected result")
    }

  private def admit(result: ArtifactId, fence: Fence, obtained: Long): Round = bounded { call =>
    val reader = new ArtifactReader(call, config.owner.project)
    val admitted = reader.result(result)
    val value = admitted.value
    require(admitted.admission.owner == config.owner.actor && admitted.metadata.actor.session == config.owner.actor.session &&
      admitted.metadata.actor.role == Role.Collector, "Revalidation requires a result of this governing session")
    require(value.request.work.isInstanceOf[DispatchWork.Worker] && value.request.work != DispatchWork.Worker(WorkerMode.Probe) && value.candidate.nonEmpty,
      "Revalidation requires an admitted worker result with a candidate")
    if (fence != value.request.fence) throw DomainFailure(Fault.StaleFence("Revalidation requires the claim fence its result was admitted under"))
    renew(call, value.request)
    dispatch.revalidatable(value)
    val effective = IntegrationValidation.effective(config.owner.project, config.owner.actor.session, result, value, config.settings.checks, reader.amendments(result))
    Round(value, effective.amendments.size + 1, IntegrationValidation.revalidated(effective), effective, obtained)
  }

  private def register(id: RequestId, result: ArtifactId, fence: Fence, done: Promise[Nothing, Unit], began: Long): (RevalidationExecution, Option[Round]) =
    synchronized(entries.get(id)) match {
      case Some(entry) =>
        if (entry.result != result || entry.fence != fence) throw DomainFailure(Fault.Conflict("Revalidation request identity changed"))
        (entry, None)
      case None =>
        synchronized {
          require(!closing, "Revalidation admission is closed")
          if (entries.values.exists(_.view.phase == RevalidationPhase.Running))
            throw DomainFailure(Fault.Conflict("A revalidation is running; wait for it to end before starting another"))
        }
        val round = admit(result, fence, began)
        val entry = new RevalidationExecution(result, fence, done,
          RevalidationStatus(id, result, RevalidationPhase.Running, None, round.effective.current, None))
        synchronized { entries = entries.updated(id, entry) }
        (entry, Some(round))
    }

  private def run(id: RequestId, result: ArtifactId, round: Round): Task[RevalidationStatus] = {
    val author = config.run.attempt.id
    val candidate = round.result.candidate.get
    val label = "reval-" + id.value.toString.replace("-", "")
    // The claim is renewed while the checks run; a renewal that fails for good stops them and no round is recorded.
    val renewed = renewal.maintain(round.obtained, ZIO.attemptBlocking(bounded(renew(_, round.result.request))))
    for {
      assignment <- ZIO.attemptBlocking(PhaseSpans.producer(config.directory, result))
      results <- ZIO.interruptible(ZIO.foreach(round.failing.zipWithIndex) { case (failing, index) =>
        validation(author, s"$label-$index", candidate, failing.original.declaration, spans.check(jobs, execution, assignment))
      }.raceFirst(renewed))
      evidence = results.map(_.evidence)
      // A check the session could not start ran nothing: no round is recorded for it.
      _ <- ZIO.attempt(results.flatMap(_.unrun).headOption.foreach(reason => throw new IllegalStateException(reason)))
      // Shutdown cancels a running check, which then reads as failed; that outcome is discarded rather than recorded as a round.
      _ <- ZIO.attempt(synchronized(require(!closing || evidence.forall(_.state == ValidationState.Passed), "Revalidation admission closed while checks ran")))
      amendment = ArtifactUpload(config.owner.project, IntegrationValidation.amendmentId(result, round.number), author, ArtifactKind.Amendment,
        "application/json", HostFiles.encode(ValidationAmendment_JsonCodec, ValidationAmendment(result, candidate, author, round.number, evidence)))
      _ <- ZIO.attemptBlocking((results.flatMap(_.artifacts) :+ amendment).foreach(authority.collector.artifact))
      current = round.effective.current.map(previous => evidence.find(_.check == previous.check).getOrElse(previous))
    } yield RevalidationStatus(id, result, RevalidationPhase.Completed, Some(amendment.id), current,
      current.find(_.state != ValidationState.Passed).map(value => s"Host check ${value.check}: ${value.state}"))
  }

  /** The first call with an identity admits and starts the round; later calls with it observe the same round.
    * Each call waits for the round at most as long as a status call may. */
  def request(id: RequestId, result: ArtifactId, fence: Fence): Task[RevalidationStatus] = ZIO.uninterruptibleMask { restore => for {
    done <- Promise.make[Nothing, Unit]
    registered <- requests.withPermit(zio.Clock.nanoTime.flatMap(began => ZIO.attemptBlocking(register(id, result, fence, done, began))))
    (entry, fresh) = registered
    // The start is written before the call returns; an event that cannot be written fails the round as any other fault of it does.
    _ <- fresh.fold(ZIO.unit)(round => ZIO.attemptBlocking(units.started(SessionUnits.revalidation(synchronized(entry.view)))).either.flatMap(written => (ZIO.fromEither(written) *> run(id, result, round)).catchAll { error =>
      ZIO.succeed(synchronized(entry.view).copy(phase = RevalidationPhase.Failed, blocker = Some(DispatchProjection.concise("Revalidation failed: " +
        Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))))
    }.flatMap(value => ZIO.succeed(synchronized { entry.view = value }))
      .ensuring(done.succeed(()).unit *> ZIO.attemptBlocking(units.ended(SessionUnits.ended(synchronized(entry.view)))).orDie).forkDaemon.unit))
    _ <- restore(entry.done.await.timeout(zio.Duration.fromMillis(WaitMillis)))
  } yield synchronized(entry.view) }

  def unsettled: List[String] = synchronized(entries.toList.collect {
    case (id, entry) if entry.view.phase == RevalidationPhase.Running => s"check revalidation ${id.value} (${entry.view.phase})"
  })
  def quiescent: Boolean = unsettled.isEmpty

  def shutdown: Task[Unit] = for {
    pending <- ZIO.succeed(synchronized { closing = true; entries.values.map(_.done).toList })
    stopped <- execution.shutdown.exit
    _ <- ZIO.foreachDiscard(pending)(_.await)
    _ <- ZIO.done(stopped)
  } yield ()
}

object RevalidationController {
  final class Resource(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor, dispatch: DispatchController,
    renewal: ClaimRenewal, clock: Clock, watchdog: SupervisorWatchdog) extends Lifecycle.Of[Task, RevalidationController](Lifecycle.make(
      Semaphore.make(1).zip(Semaphore.make(1)).map((requests, admission) => new RevalidationController(config, authority, jobs, dispatch, renewal, clock, requests, admission)))(
      value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown))
}
