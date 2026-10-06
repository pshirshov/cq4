package cq.server

import cq.api.*
import cq.core.{AgentConfigText, DomainFailure, LedgerPolicy}
import cq.host.*
import distage.Lifecycle
import java.util.UUID
import logstage.IzLogger
import scala.util.Try
import zio.{Promise, Semaphore, Task, UIO, Unsafe, ZIO}

/** One attempt of a unit: the candidate it runs and, once the unit has seen it end, its final status. `outcome` is how a drive reads
  * its end, which is known when its unit ends: whether a failed attempt's input is offered again, and which models of the unit
  * abstained, are decided for the unit. */
private[server] final class UnitAttempt(val at: SeatCandidate, val entry: DispatchExecution, val outcome: Promise[Nothing, ChildOutcome]) {
  val id: AttemptId = entry.ticket.attempt.id
  var ended = Option.empty[DispatchStatus]
}

/** Who works a unit. */
private[server] sealed trait UnitWorker
private[server] object UnitWorker {
  /** The models the agent configuration assigns to the role of the work; `selection` is the cohort choice that started the unit. */
  final case class Models(plan: ResolvedRole, selection: Option[SelectedDispatch]) extends UnitWorker
  /** The governing session itself: one seat, which the host starts no model for and which holds no child slot. */
  final case class Governing(own: OwnWork) extends UnitWorker
}

/** One started choice and the attempts the host made for it. Its fields are read and written under the lock of its `DispatchUnits`. */
private[server] final class DispatchUnit(val work: AssignedWork, val worker: UnitWorker,
  val cohort: Option[UUID], var progress: UnitProgress, val done: Promise[Nothing, Unit]) {
  def governing: Boolean = worker.isInstanceOf[UnitWorker.Governing]
  def selection: Option[SelectedDispatch] = worker match {
    case UnitWorker.Models(_, value) => value
    case _: UnitWorker.Governing => None
  }
  // Whether `units.jsonl` says that the host works on the unit. A unit of the agent configuration is announced when it starts; one
  // the governing session works itself when the session hands it back or it is stopped, and never when it ends before that.
  var announced = false
  var attempts = Vector.empty[UnitAttempt]
  // The candidates the unit has been told to launch and has not seen end. Each holds one of the session's child slots, and the
  // candidate that follows one that ended takes over its slot in the same step.
  var open = 0
  var stopping = Option.empty[String]
  var refusal = Option.empty[String]
  var concluding = false
  var terminal = Option.empty[DispatchStatus]
  // Completed whenever the unit gains an attempt; a reader that waits for the next one takes the promise that stands then.
  var grown: Promise[Nothing, Unit] = Unsafe.unsafe { implicit unsafe => Promise.unsafe.make[Nothing, Unit](zio.FiberId.None) }
  def handle: Option[AttemptId] = attempts.headOption.map(_.id)
}

/**
 * The units of one governing session. A unit is one started choice: the host resolves the models the agent configuration assigns to
 * the role of its work, starts an attempt for each seat, goes on to a seat's next candidate when one abstains, and ends the unit when
 * enough seats delivered or no more can. The governing session sees one status for the unit, under the id of its first attempt, and
 * one pair of events in `units.jsonl`.
 */
final class DispatchUnits(config: SupervisorConfig, authority: SupervisorAuthority, dispatch: DispatchController, governor: GovernorWork, logger: IzLogger) {
  private val MaxPlanBytes = 65536
  private val events = new SessionUnits(config.directory)
  private val rotation = new SeatRotation(config.run.attempt.session)
  private val governing = config.run.attempt.harness
  private val project = config.project.project
  // One start at a time: a repeated StartChoice finds the unit its first call made.
  private val starting = Unsafe.unsafe { implicit unsafe => Semaphore.unsafe.make(1) }
  private var closing = false
  private var units = Vector.empty[DispatchUnit]

  private def found(attempt: AttemptId): DispatchUnit = synchronized {
    units.find(_.attempts.exists(_.id == attempt)).getOrElse(throw DomainFailure(Fault.Missing("Child attempt is not owned by this governing session")))
  }

  // Read when the unit starts and never kept: a configuration saved during a session applies to the next unit.
  private def resolve(role: AgentRole): ResolvedRole =
    authority.governor.call(Command.Agents(AgentsInput(project, AgentsAction.Resolve(governing, role)))) match {
      case Result.AgentRoute(ResolvedAssignment(_, _, RoleResolution.Resolved(plan))) =>
        // A route that names no provider runs on the provider of the settings entry of its harness.
        plan.copy(seats = plan.seats.map(seat => seat.copy(candidates = seat.candidates.map(route =>
          route.copy(provider = route.provider.orElse(config.settings.harnesses.find(_.harness == route.harness).map(_.provider)))))))
      case Result.AgentRoute(ResolvedAssignment(_, _, RoleResolution.Unresolved(origin, problems))) =>
        throw DomainFailure(Fault.Invalid(DispatchUnits.unresolved(governing, role, origin, problems)))
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Agent route resolution returned an unexpected result")
    }

  private def pending(unit: DispatchUnit, handle: AttemptId, phase: DispatchPhase): DispatchStatus = DispatchStatus(unit.work.request, handle, phase, None,
    unit.work.members.map(_.id), DispatchProjection.EmptyCounts, ChildNext.Wait, None, None, None, false, false, None, None)

  /** What the governing session sees. An ended attempt of a unit that has not ended shows nothing of how it ended: the unit may go on
    * to another candidate, and its input is released, or its fault published, before the unit's end is visible. */
  private def status(unit: DispatchUnit): Task[DispatchStatus] = ZIO.attempt(synchronized {
    val handle = unit.handle.getOrElse(throw new IllegalStateException("A unit without an attempt has no status"))
    (handle, unit.terminal, unit.attempts.find(_.ended.isEmpty).map(_.id), unit.concluding)
  }).flatMap {
    case (_, Some(value), _, _) => ZIO.succeed(value)
    case (handle, None, Some(running), _) => dispatch.status(running).map(value =>
      if (DispatchController.terminal(value.phase)) pending(unit, handle, DispatchPhase.Publishing) else DispatchProjection.bounded(value.copy(attempt = handle)))
    case (handle, None, None, concluding) => ZIO.succeed(pending(unit, handle, if (concluding) DispatchPhase.Publishing else DispatchPhase.Preparing))
  }

  private def template(work: AssignedWork): DispatchRequest = DispatchUnits.request(work, governing)

  /** Starts the unit of `work`, or returns the status of the unit an earlier call with the same request started. */
  def start(work: AssignedWork, selection: Option[SelectedDispatch]): Task[DispatchStatus] = started(work, _.worker.isInstanceOf[UnitWorker.Models], ZIO.attemptBlocking {
    ChildContracts.request(project, template(work))
    SupervisorConfig.within(work.limits, config.settings.limits)
    UnitWorker.Models(resolve(DispatchUnits.role(work.work)), selection)
  }).flatMap(status)

  private def own(request: RequestId, members: List[ItemRevision], work: DispatchWork, previous: Option[ArtifactId], fence: Fence): AssignedWork =
    AssignedWork(request, work, members, Nil, Nil, previous, fence, config.settings.limits)

  /** Opens a workspace in which the governing session itself implements `members`, starting from the candidate of `previous` when
    * given, and returns once it is open: the status then names its directory. A repetition returns how that unit stands. */
  def open(request: RequestId, members: List[ItemRevision], previous: Option[ArtifactId], fence: Fence): Task[DispatchStatus] = {
    val work = own(request, members, DispatchWork.Worker(WorkerMode.Implement), previous, fence)
    for {
      unit <- started(work, _.worker match { case UnitWorker.Governing(value) => value.review.isEmpty; case _ => false },
        governor.prepare(template(work), None).map(UnitWorker.Governing.apply))
      // Preparing a worktree of a large repository takes its time: the call waits as long as a status call may, and its repetition waits again.
      open <- ZIO.succeed(synchronized(unit.handle)).flatMap(ZIO.foreach(_)(governor.opened)).timeout(zio.Duration.fromMillis(DispatchWaits.MaxMillis))
      _ <- ZIO.fromOption(open).orElseFail(DomainFailure(Fault.Conflict(DispatchUnits.Opening)))
      result <- status(unit)
    } yield result
  }

  /** Records the governing session's own review of the candidate of the admitted worker result `result`, and returns once the
    * review is published and admitted, as the result of a reviewer child would be. */
  def selfReview(request: RequestId, result: ArtifactId, verdicts: List[ReviewMember], fence: Fence): Task[DispatchStatus] = {
    def same(unit: DispatchUnit): Boolean = unit.work.previous.contains(result) && unit.work.fence == fence && (unit.worker match {
      case UnitWorker.Governing(value) => value.review.contains(verdicts)
      case _ => false
    })
    for {
      existing <- ZIO.succeed(synchronized(units.find(_.work.request == request)))
      unit <- existing match {
        case Some(value) => if (same(value)) ZIO.succeed(value) else ZIO.fail(DomainFailure(Fault.Conflict("Dispatch request identity changed")))
        case None => ZIO.attemptBlocking { governor.admissible(); governor.subject(result) }.flatMap { members =>
          val work = own(request, members, DispatchWork.Reviewer(ReviewerMode.Candidate), Some(result), fence)
          started(work, same, governor.prepare(template(work), Some(verdicts)).map(UnitWorker.Governing.apply))
        }
      }
      _ <- unit.done.await.timeout(zio.Duration.fromMillis(DispatchWaits.MaxMillis))
      reviewed <- status(unit)
    } yield reviewed
  }

  // The unit of `work`: the one an earlier call with the same request started when it is `same`, else a new one worked by `worker`.
  private def started(work: AssignedWork, same: DispatchUnit => Boolean, worker: Task[UnitWorker]): Task[DispatchUnit] = starting.withPermit {
    ZIO.attemptBlocking(synchronized(units.find(_.work.request == work.request))).flatMap {
      case Some(existing) =>
        if (existing.work != work || !same(existing)) ZIO.fail(DomainFailure(Fault.Conflict("Dispatch request identity changed"))) else ZIO.succeed(existing)
      case None => for {
        worker <- worker
        done <- Promise.make[Nothing, Unit]
        admitted <- ZIO.attemptBlocking(synchronized {
          require(!closing, DispatchController.Closed)
          val active = units.filter(_.terminal.isEmpty).toList
          val standing = active.map(unit => DispatchUnits.Standing(template(unit.work), if (unit.governing) 0 else unit.open))
          val (progress, step, together) = worker match {
            case UnitWorker.Models(plan, _) =>
              // The seats a unit starts together fit together or the unit is not started: every seat of an `all` panel, `min` seats of an `any` one.
              val together = if (plan.mode == PanelMode.All) plan.seats.size else plan.min
              DispatchUnits.admissible(standing, template(work), together)
              val role = DispatchUnits.role(work.work)
              val (progress, step) = UnitProgress.begin(work.request, plan, seat => rotation.next(role, seat, plan.seats(seat).candidates))
              (progress, step, together)
            case _: UnitWorker.Governing =>
              DispatchUnits.admissible(standing, template(work), 0)
              val own = config.run.attempt
              val (progress, step) = UnitProgress.single(work.request, ModelRoute(own.harness, Some(own.provider), own.model, own.effort))
              (progress, step, 1)
          }
          val initial = step match {
            case UnitStep.Launch(candidates) if candidates.size == together => candidates
            case other => throw new IllegalStateException(s"A unit of $together first seats began with $other")
          }
          val members = work.members.map(_.id).toSet
          val selection = worker match { case UnitWorker.Models(_, value) => value; case _ => None }
          val unit = new DispatchUnit(work, worker, selection.fold(Option.when(members.size > 1)(UUID.randomUUID()))(_.cohort), progress, done)
          unit.open = initial.size
          units = units :+ unit
          unit -> initial
        })
        (unit, initial) = admitted
        first <- launch(unit, initial.head, true).either
        // A start that registered nothing made no unit; one that registered an attempt has a unit, whatever its launch then said.
        _ <- if (first.isLeft && synchronized(unit.attempts.isEmpty)) ZIO.succeed(synchronized { units = units.filterNot(_ eq unit) }) *> done.succeed(()) *> ZIO.fail(first.left.toOption.get)
          else ZIO.foreachDiscard(initial.tail)(launch(unit, _, false)) *> ZIO.fromEither(first)
      } yield unit
    }
  }

  // The host works on a unit of the governing session's own work from the moment the session hands it back or it is stopped, and
  // says so then. Written under the lock, so that the end of a unit is written if and only if its start was.
  private def announce(unit: DispatchUnit): Task[Unit] = ZIO.attemptBlocking(synchronized {
    if (unit.governing && !unit.announced && unit.terminal.isEmpty) unit.handle.foreach { handle =>
      events.started(SessionUnit(SessionUnitKind.Attempt, handle.value, unit.work.members.map(_.id)))
      unit.announced = true
    }
  })

  private def workspace(attempt: AttemptId): DispatchUnit = {
    val unit = found(attempt)
    unit.worker match {
      case UnitWorker.Governing(value) if value.review.isEmpty => unit
      case _ => throw DomainFailure(Fault.Invalid(s"Attempt ${attempt.value} is not a workspace opened with OpenWorkspace: only such a workspace is submitted"))
    }
  }

  /** Hands the workspace of `attempt` back with the report a Worker makes of its work, and returns at once: the host then captures
    * the content of the workspace as the candidate and runs the configured checks on it. */
  def submit(attempt: AttemptId, members: List[WorkMember]): Task[DispatchStatus] = for {
    found <- ZIO.attempt { val unit = workspace(attempt); synchronized((unit, unit.terminal.nonEmpty, unit.attempts.head.entry)) }
    (unit, ended, entry) = found
    _ <- if (ended) ZIO.unit else ZIO.attemptBlocking(governor.report(entry, members)).flatMap(report => announce(unit) *> governor.submit(entry, report))
    result <- status(unit)
  } yield result

  private def retained(unit: DispatchUnit, plan: ResolvedRole, handle: AttemptId): Unit = {
    val directory = config.directory.resolve("units")
    HostFiles.directory(directory)
    HostFiles.immutable(directory.resolve(unit.work.request.value.toString + ".json"), HostFiles.encode(ResolvedAssignment_JsonCodec,
      ResolvedAssignment(governing, DispatchUnits.role(unit.work.work), RoleResolution.Resolved(plan))), MaxPlanBytes)
    events.started(SessionUnit(SessionUnitKind.Attempt, handle.value, unit.work.members.map(_.id)))
    synchronized { unit.announced = true }
  }

  // `first` is the unit's first candidate: its registration admits the unit, and a refusal of it is the refusal of the start.
  private def launch(unit: DispatchUnit, at: SeatCandidate, first: Boolean): Task[Unit] = {
    val (registered, prepared) = unit.worker match {
      case UnitWorker.Models(plan, selection) =>
        val route = plan.seats(at.seat).candidates(at.candidate)
        val origin = AttemptOrigin(unit.cohort, selection.map(_.evidence), if (first) selection.fold(() => ())(_.admit) else () => ())
        (dispatch.register(DispatchUnits.request(unit.work, route.harness), route, origin), (handle: AttemptId) => if (first) retained(unit, plan, handle))
      // No model was resolved for the unit and nothing announces it before the session hands its work back.
      case UnitWorker.Governing(own) => (dispatch.own(template(unit.work), unit.cohort).tap(governor.assign(_, own)), (_: AttemptId) => ())
    }
    ZIO.succeed(synchronized(unit.stopping)).flatMap {
      // A unit that is being stopped starts nothing more.
      case Some(_) => if (first) ZIO.fail(new IllegalArgumentException(DispatchController.Closed)) else abandon(unit, at)
      case None => registered.foldZIO(
        error => if (first) ZIO.fail(error) else refuse(unit, at, error),
        entry => for {
          outcome <- Promise.make[Nothing, ChildOutcome]
          attempt = new UnitAttempt(at, entry, outcome)
          grown <- Promise.make[Nothing, Unit]
          before <- ZIO.attempt(synchronized {
            unit.progress = unit.progress(UnitEvent.Started(at, attempt.id))._1
            unit.attempts = unit.attempts :+ attempt
            val reached = unit.grown
            unit.grown = grown
            (unit.stopping, reached)
          })
          (stopping, reached) = before
          _ <- reached.succeed(())
          launched <- dispatch.launch(entry, () => prepared(attempt.id), ended(unit, attempt)).either
          _ <- stopping.fold(ZIO.unit)(reason => dispatch.stop(attempt.id, reason).unit)
          // The failure of a launch stands in the attempt's status; only the start of the unit also replies with it.
          _ <- if (first) ZIO.fromEither(launched) else ZIO.unit
        } yield ())
    }
  }

  // The host cannot start the candidate: the unit ends as cancelled, with the refusal as its blocker.
  private def refuse(unit: DispatchUnit, at: SeatCandidate, error: Throwable): Task[Unit] = {
    val reason = DispatchProjection.concise("The host could not start the next model of this work: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    ZIO.succeed(synchronized { unit.refusal = unit.refusal.orElse(Some(reason)) }) *> stop(unit, reason) *> abandon(unit, at)
  }

  private def abandon(unit: DispatchUnit, at: SeatCandidate): Task[Unit] =
    ZIO.attempt(synchronized { unit.progress = unit.progress.cancel._1 }) *> advance(unit, UnitEvent.Cancelled(at))

  private def advance(unit: DispatchUnit, event: UnitEvent): Task[Unit] = ZIO.attempt(synchronized {
    val (next, step) = unit.progress(event)
    unit.progress = next
    unit.open -= 1
    step match {
      case UnitStep.Launch(candidates) => unit.open += candidates.size
      case _: UnitStep.Ended => unit.concluding = true
      case UnitStep.Wait => ()
    }
    step
  }).flatMap {
    case UnitStep.Wait => ZIO.unit
    case UnitStep.Launch(candidates) => ZIO.foreachDiscard(candidates)(launch(unit, _, false))
    case UnitStep.Ended(outcome) => conclude(unit, outcome)
  }

  private def ended(unit: DispatchUnit, attempt: UnitAttempt): UIO[Unit] = ZIO.attemptBlocking {
    val status = attempt.entry.status
    synchronized { attempt.ended = Some(status) }
    DispatchUnits.event(attempt.at, status, attempt.entry.abstention)
  }.flatMap(advance(unit, _)).catchAllCause { cause =>
    // The unit cannot go on from an end it could not take in: it ends, and says so.
    val problem = DispatchProjection.concise("The host could not account for the end of a child attempt: " + cause.squash.getMessage)
    ZIO.succeed(logger.error(s"$problem")) *> ZIO.succeed(synchronized {
      val fresh = !unit.concluding && unit.terminal.isEmpty
      unit.concluding = true
      fresh
    }).flatMap(fresh => if (fresh) finish(unit, unknown(unit, problem), _ => None, None) else ZIO.unit)
  }

  private def unknown(unit: DispatchUnit, problem: String): DispatchStatus =
    pending(unit, synchronized(unit.handle.get), DispatchPhase.Unknown).copy(next = ChildNext.InspectEvidence, blocker = Some(problem), detailsOmitted = true)

  private def review(attempt: UnitAttempt, status: DispatchStatus): Option[List[ReviewMember]] =
    Option.when(status.phase == DispatchPhase.Completed && attempt.entry.ticket.attempt.role == Role.Reviewer) {
      new ChildPublicationDelivery(attempt.entry.directory, attempt.entry.ticket).published.result.map(_.report) match {
        case Some(ChildReport.Review(members, _)) => members
        case other => throw new IllegalStateException(s"A completed review attempt published ${other.fold("no result")(_.getClass.getSimpleName)}")
      }
    }

  // The input is released, and its fault published, while the unit still reads as publishing: a status that showed it failed before
  // that would let the session select the same work and find it deferred.
  private def conclude(unit: DispatchUnit, outcome: UnitOutcome): Task[Unit] = ZIO.attemptBlocking {
    val (attempts, refusal, seats) = synchronized((unit.attempts.toList, unit.refusal, unit.progress.snapshot))
    val all = attempts.map { attempt =>
      val status = attempt.ended.getOrElse(throw new IllegalStateException(s"Attempt ${attempt.id.value} of an ended unit has not ended"))
      EndedAttempt(attempt.id, status, attempt.entry.stopReason.nonEmpty, review(attempt, status))
    }
    val decided = DispatchUnits.status(unit.work.request, attempts.head.id, outcome, all, refusal)
    val reply = unit.selection.fold(CohortFailure.outcome(decided, None, None))(_.finished(decided))
    (decided, DispatchUnits.outcomes(outcome, reply, all), Some(seats))
  }.catchAll { error =>
    val problem = DispatchProjection.concise("The host could not conclude this work: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    ZIO.succeed(logger.error(s"$problem")).as((unknown(unit, problem), Map.empty[AttemptId, ChildOutcome], Option.empty[UnitSeats]))
  }.flatMap((decided, outcomes, seats) => finish(unit, decided, outcomes.get, seats))

  private def finish(unit: DispatchUnit, decided: DispatchStatus, outcome: AttemptId => Option[ChildOutcome], seats: Option[UnitSeats]): UIO[Unit] =
    ZIO.succeed(synchronized(unit.terminal.isEmpty)).flatMap(fresh => if (fresh) settle(unit, decided, outcome, seats) else ZIO.unit)

  private def settle(unit: DispatchUnit, decided: DispatchStatus, outcome: AttemptId => Option[ChildOutcome], seats: Option[UnitSeats]): UIO[Unit] = for {
    // The durable record of the unit's seats. Its attempts are retained with or without it, so a refused upload does not hold the end back.
    _ <- ZIO.attemptBlocking(seats.filterNot(_ => unit.governing).foreach { value =>
      val upload = ArtifactUpload(project, DispatchUnits.panel(config.run.attempt.id, unit.work.request), config.run.attempt.id, ArtifactKind.Panel,
        "application/json", HostFiles.encode(UnitSeats_JsonCodec, value))
      Try(authority.collector.artifact(upload)).failed.foreach { error =>
        val message = s"The seats of the unit of attempt ${decided.attempt.value} were not published: ${error.getMessage}"
        logger.warn(s"$message")
      }
    }).ignore
    ended <- ZIO.succeed(synchronized { unit.terminal = Some(decided); (unit.attempts.toList, !unit.governing || unit.announced) })
    (attempts, announced) = ended
    _ <- ZIO.foreachDiscard(attempts)(attempt => attempt.outcome.succeed(outcome(attempt.id).getOrElse(
      CohortFailure.outcome(attempt.ended.getOrElse(decided).copy(attempt = attempt.id), None, None))))
    _ <- unit.done.succeed(())
    // What a waiter outside the host reads; written after the end is visible to the session, whose status call may already wait on it.
    _ <- if (announced) ZIO.attemptBlocking(events.ended(SessionUnits.ended(decided))).orDie else ZIO.unit
  } yield ()

  def status(attempt: AttemptId, waitMillis: Int): Task[DispatchStatus] = for {
    unit <- ZIO.attempt { DispatchWaits.admitted(waitMillis, "Status"); found(attempt) }
    _ <- if (waitMillis == 0) ZIO.unit else unit.done.await.timeout(zio.Duration.fromMillis(waitMillis)).unit
    result <- status(unit)
  } yield result

  /** The seats of the unit of `attempt` as they stand: every candidate the host tried, and how each seat ended. */
  def seats(attempt: AttemptId): Task[UnitSeats] = ZIO.attempt { val unit = found(attempt); synchronized(unit.progress.snapshot) }

  /** Empty while the unit of the attempt runs; then how the attempt ended. */
  def concluded(attempt: AttemptId, waitMillis: Int): Task[Option[ChildOutcome]] = for {
    promise <- ZIO.attempt {
      DispatchWaits.admitted(waitMillis, "Status")
      val unit = found(attempt)
      synchronized(unit.attempts.find(_.id == attempt).get.outcome)
    }
    result <- promise.await.timeout(zio.Duration.fromMillis(waitMillis))
  } yield result

  /** The attempts of the unit of `attempt` in the order the host started them, and whether the unit has ended. Waits up to `waitMillis`
    * for the unit to end or to hold more than `known` attempts. */
  def lineage(attempt: AttemptId, known: Int, waitMillis: Int): Task[(List[AttemptId], Boolean)] = {
    def read(unit: DispatchUnit) = synchronized((unit.attempts.map(_.id).toList, unit.terminal.nonEmpty, unit.grown))
    for {
      unit <- ZIO.attempt { DispatchWaits.admitted(waitMillis, "Status"); found(attempt) }
      first <- ZIO.succeed(read(unit))
      _ <- if (first._2 || first._1.size > known) ZIO.unit else unit.done.await.raceFirst(first._3.await).timeout(zio.Duration.fromMillis(waitMillis)).unit
      last <- ZIO.succeed(read(unit))
    } yield (last._1, last._2)
  }

  // A stop reaches the attempts that can still be stopped. The unit is cancelled when one of them takes it or none is running; one
  // that is already publishing ends as it ends, and the unit with it, but nothing follows it.
  private def stop(unit: DispatchUnit, reason: String): Task[Unit] = announce(unit).ignore *> ZIO.succeed(synchronized {
    if (unit.concluding || unit.terminal.nonEmpty) None
    else {
      unit.stopping = unit.stopping.orElse(Some(reason))
      Some(unit.attempts.filter(_.ended.isEmpty).map(_.id).toList)
    }
  }).flatMap {
    case None => ZIO.unit
    case Some(running) => ZIO.foreach(running)(dispatch.stop(_, reason)).flatMap(taken => ZIO.attempt(synchronized {
      if (!unit.concluding && (taken.contains(true) || running.isEmpty)) unit.progress = unit.progress.cancel._1
    }))
  }

  /** Cancels the whole unit of `attempt`: its running attempts are stopped and no further candidate or seat is started. */
  def cancel(attempt: AttemptId): Task[DispatchStatus] = for {
    unit <- ZIO.attempt(found(attempt))
    _ <- stop(unit, DispatchUnits.Cancelled)
    result <- status(unit)
  } yield result

  def workspace(attempt: AttemptId, command: WorkspaceCommand): Task[WorkspaceReply] = dispatch.workspace(attempt, command)

  /** The units that have not ended. A unit between two candidates is one of them. */
  def unsettled: List[String] = synchronized(units.toList.filter(_.terminal.isEmpty).flatMap(unit => unit.handle.map { handle =>
    val phase = unit.attempts.find(_.ended.isEmpty).map(_.entry.status.phase).filterNot(DispatchController.terminal)
      .getOrElse(if (unit.concluding) DispatchPhase.Publishing else DispatchPhase.Preparing)
    val kind = unit.worker match {
      case UnitWorker.Governing(own) => if (own.review.isEmpty) "governor workspace" else "governor review"
      case _: UnitWorker.Models => "child attempt"
    }
    s"$kind ${handle.value} ($phase)"
  }))
  def quiescent: Boolean = synchronized(units.forall(_.terminal.nonEmpty))

  /** A result's checks are rerun only while no unit covers its members and no later worker result for them exists. */
  def revalidatable(result: ChildResult): Unit = {
    val members = result.request.members.map(_.id).toSet
    val active = synchronized(units.toList.filter(_.terminal.isEmpty).flatMap(_.work.members.map(_.id))).filter(members).distinct.sortBy(LedgerPolicy.key)
    if (active.nonEmpty)
      throw DomainFailure(Fault.Conflict(s"An active child covers ${active.map(id => LedgerPolicy.prefix(id.ledger) + id.number).mkString(", ")}; wait for it to end before revalidating"))
    dispatch.superseded(result)
  }

  def shutdown: Task[Unit] = for {
    owned <- ZIO.succeed(synchronized { closing = true; units.toList })
    _ <- ZIO.foreachDiscard(owned)(stop(_, DispatchController.Ending))
    _ <- dispatch.shutdown
    _ <- ZIO.foreachDiscard(owned)(_.done.await)
  } yield ()
}

/** An attempt of an ended unit: its own final status, whether the host had asked it to stop, and the verdicts of a delivered review. */
final case class EndedAttempt(attempt: AttemptId, status: DispatchStatus, stopped: Boolean, review: Option[List[ReviewMember]])

object DispatchUnits {
  val Cancelled = "Cancelled by the governing session"
  val Opening = "The workspace is still being prepared: repeat OpenWorkspace with the same request, members, previous and fence to wait for it"

  def role(work: DispatchWork): AgentRole = work match {
    case _: DispatchWork.Explorer => AgentRole.Explorer
    case _: DispatchWork.Planner => AgentRole.Planner
    case _: DispatchWork.Worker => AgentRole.Worker
    case _: DispatchWork.Reviewer => AgentRole.Reviewer
  }

  /** A unit that has not ended, as the admission of another reads it: its request and the child slots it holds. */
  final case class Standing(request: DispatchRequest, slots: Int)

  /** `request` is to start beside the `active` units, holding `slots` child slots. The members of running units are disjoint (D83).
    * A unit the governing session works itself holds its members as any unit does and no slot: no process runs for it. */
  def admissible(active: List[Standing], request: DispatchRequest, slots: Int): Unit = {
    DispatchController.disjoint(active.map(_.request), request)
    DispatchController.capacity(active.map(_.slots).sum, slots)
  }

  /** The request of one attempt of the unit of `work`: the same work on the harness of the attempt's route. */
  def request(work: AssignedWork, harness: Harness): DispatchRequest =
    DispatchRequest(work.request, work.work, harness, work.members, work.guidance, work.artifacts, work.previous, work.fence, work.limits)

  /** The artifact of the governing attempt that records the seats of the unit of `request`. */
  def panel(governing: AttemptId, request: RequestId): ArtifactId = NativeArtifacts.id(governing, "unit-" + request.value)

  private def name(value: Any): String = value.toString.toLowerCase(java.util.Locale.ROOT)

  /** Why a unit cannot start, in the words of the configuration its operator edits, and what to set. */
  def unresolved(governing: Harness, role: AgentRole, origin: Option[RoleOrigin], problems: List[AgentProblem]): String = {
    val (harness, assigned) = (name(governing), name(role))
    val key = origin.map(_.source) match {
      case Some(RoleSource.HarnessRoles) => s"harnesses.$harness.roles.$assigned"
      case _ => s"defaults.roles.$assigned"
    }
    val layer = origin.map(_.layer) match {
      case Some(AgentLayer.Project) => "this project's agent configuration"
      case Some(AgentLayer.Installation) => "the server's default agent configuration"
      case None => "the agent configuration"
    }
    problems.map {
      case _: AgentProblem.RoleUnassigned =>
        s"no model is assigned to the $assigned role for governing harness $harness: set defaults.roles.$assigned or harnesses.$harness.roles.$assigned in the agent configuration " +
          "(the server's default or this project's); cq agents init --settings FILE writes a starting configuration from a settings file"
      case AgentProblem.TierUndefined(of, tier, _) =>
        s"the $assigned role for governing harness $harness ($key of $layer) refers to the ${name(tier)} tier of ${name(of)}, which no layer defines: " +
          s"set harnesses.${name(of)}.tiers.${name(tier)} in the agent configuration"
      case other => s"the $assigned role for governing harness $harness cannot run as $key of $layer assigns it: ${AgentConfigText.describe(other)}"
    }.distinct.mkString("; ")
  }

  def event(at: SeatCandidate, status: DispatchStatus, abstention: Option[Abstention]): UnitEvent = status.phase match {
    case DispatchPhase.Completed if status.result.nonEmpty => UnitEvent.Delivered(at, status.result.get, status.next)
    case DispatchPhase.Abstained =>
      val value = abstention.getOrElse(throw new IllegalStateException(s"Abstained attempt ${status.attempt.value} states no abstention"))
      UnitEvent.Abstained(at, value.reason, value.detail)
    case DispatchPhase.Cancelled => UnitEvent.Cancelled(at)
    case phase =>
      require(DispatchController.terminal(phase), s"Attempt ${status.attempt.value} ended in phase $phase")
      UnitEvent.Failed(at, status.blocker.filter(_.trim.nonEmpty).getOrElse(CohortFailure.Unstated))
  }

  /**
   * The status of an ended unit, under its handle.
   *  - Decided: the status of the seat that delivered. Several delivered reviews are aggregated: when they agree, the first stands for
   *    them; when they do not, the dissenting one does, with `next` Arbitrate. A failed seat the others made up for is named in the blocker.
   *  - Failed: the status of the first seat that failed, as that attempt ended.
   *  - Abstained: phase Abstained, with every candidate and its reason as the blocker.
   *  - Cancelled: the status of the attempt that was cancelled; when the unit was stopped between two candidates, a cancelled status.
   */
  def status(request: RequestId, handle: AttemptId, outcome: UnitOutcome, ended: List[EndedAttempt], refusal: Option[String]): DispatchStatus = {
    def own(attempt: AttemptId): EndedAttempt = ended.find(_.attempt == attempt).getOrElse(throw new IllegalStateException(s"Attempt ${attempt.value} is not of this unit"))
    val last = ended.lastOption.getOrElse(throw new IllegalStateException("An ended unit has an attempt")).status
    val decided = outcome match {
      case UnitOutcome.Decided(delivered, failed) =>
        val standing = delivered match {
          case List(only) => own(only.attempt).status
          case several => ReviewAggregate(several.map(seat => ReviewSeat(seat.seat, own(seat.attempt).status, own(seat.attempt).review
            .getOrElse(throw new IllegalStateException("Only reviews are delivered by several seats of one unit"))))).status(handle)
        }
        if (failed.isEmpty) standing else standing.copy(blocker = Some(DispatchProjection.concise((standing.blocker.toList ++
          failed.map(seat => s"seat ${seat.seat} failed and the other seats decided: ${seat.fault}")).mkString("; "))))
      case UnitOutcome.Failed(failed, _) => own(failed.head.attempt).status
      case UnitOutcome.Abstained(candidates, _) => last.copy(phase = DispatchPhase.Abstained, next = ChildNext.ResolveBlocker,
        blocker = Some(DispatchProjection.concise(UnitProgress.abstention(candidates))), result = None)
      case UnitOutcome.Cancelled => ended.find(_.status.phase == DispatchPhase.Cancelled).orElse(ended.find(_.stopped)).map(_.status)
        .getOrElse(last.copy(phase = DispatchPhase.Cancelled, next = ChildNext.Retry, blocker = Some(refusal.getOrElse(Cancelled)), result = None))
    }
    DispatchProjection.bounded(decided.copy(request = request, attempt = handle))
  }

  /**
   * How each attempt of an ended unit ended, as a drive reads it. `reply` is the end of the unit: whether its input is offered again
   * and under which fingerprint.
   *  - A failed seat of a unit that failed is retryable when the input is offered again. Only the first failed seat, whose fault was
   *    compared with the one before it, can be the repetition that ends a drive.
   *  - A failed seat the other seats made up for failed and nothing else: its input was executed.
   *  - The abstentions of a unit no model could run all state every candidate and reason, so that the last of them does.
   */
  def outcomes(outcome: UnitOutcome, reply: ChildOutcome, ended: List[EndedAttempt]): Map[AttemptId, ChildOutcome] = {
    val offered = reply.end match {
      case ChildEnd.Retryable => Some(true)
      case ChildEnd.Repeated => Some(false)
      case _ => None
    }
    val (failing, first) = outcome match {
      case UnitOutcome.Failed(failed, _) => (failed.map(_.attempt).toSet, failed.headOption.map(_.attempt))
      case _ => (Set.empty[AttemptId], None)
    }
    ended.map { value =>
      val judged = if (first.contains(value.attempt)) offered else if (failing(value.attempt)) offered.filter(identity) else None
      val own = CohortFailure.outcome(value.status.copy(attempt = value.attempt), reply.input, judged)
      value.attempt -> (outcome match {
        case _: UnitOutcome.Abstained if own.end == ChildEnd.Abstained => own.copy(fault = reply.fault)
        case _ => own
      })
    }.toMap
  }

  final class Resource(config: SupervisorConfig, authority: SupervisorAuthority, dispatch: DispatchController, governor: GovernorWork, logger: IzLogger, watchdog: SupervisorWatchdog)
    extends Lifecycle.Of[Task, DispatchUnits](
      Lifecycle.make(ZIO.succeed(new DispatchUnits(config, authority, dispatch, governor, logger)))(value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown)
    )
}
