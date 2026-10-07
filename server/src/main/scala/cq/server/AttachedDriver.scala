package cq.server

import cq.api.*
import cq.core.{DomainFailure, DriverPolicy}
import cq.host.{DispatchProjection, DriverCall, DriverEntry, DriverSessionClient}
import logstage.IzLogger
import zio.{Semaphore, Task, UIO, Unsafe, ZIO}

// Where a lineage member stands on its host once the host no longer works on it: finished, or waiting for the session to act on it.
sealed trait LineageOutcome
object LineageOutcome {
  case object Settled extends LineageOutcome
  case object Resting extends LineageOutcome
  // A child attempt ended: the server settles it with its outcome and decides what that means for the drive.
  final case class Concluded(outcome: ChildOutcome) extends LineageOutcome
}

// Registers the work an attached session dispatches from a driven advance run under that run's cycle and keeps the server's view of each
// member current: in flight, resting or settled. A member the host cannot account for ends the drive.
final class LineageTracker(client: DriverSessionClient, report: String => Unit, pause: zio.Duration, backoff: zio.Duration, ceiling: zio.Duration) {
  private val Attempts = 6
  // A followed member and how often the session has resumed it.
  private var tracked = Map.empty[(CycleId, LineageMember), Long]
  // The members whose follower has ended: their end was reported, or they were given up. A unit names its attempts again at each of
  // its readings, and one that is named again after its end is not registered and reported a second time.
  private var ended = Set.empty[(CycleId, LineageMember)]
  // Orders a report that a member rests against a report that the session resumed it.
  private val reports = Unsafe.unsafe { implicit unsafe => Semaphore.unsafe.make(1) }
  private def fresh(cycle: CycleId, member: LineageMember): Boolean = synchronized {
    val added = !tracked.contains((cycle, member)) && !ended((cycle, member))
    if (added) tracked += (cycle, member) -> 0L
    added
  }
  private def finished(cycle: CycleId, member: LineageMember): Unit = synchronized { tracked -= ((cycle, member)); ended += ((cycle, member)) }
  private def resumes(cycle: CycleId, member: LineageMember): Long = synchronized(tracked.getOrElse((cycle, member), 0L))
  private def resumed(cycle: CycleId, member: LineageMember): Boolean = synchronized {
    val count = tracked.get((cycle, member))
    count.foreach(value => tracked += (cycle, member) -> (value + 1))
    // The host works again on a member whose follower had ended: it is followed anew.
    if (count.isEmpty) ended -= ((cycle, member))
    count.nonEmpty
  }

  // A request lost in transit is repeated with a doubling pause. A fault the server returned is its answer and is not repeated.
  private def reliably[A](operation: => A): Task[A] = {
    def attempt(remaining: Int, wait: zio.Duration): Task[A] = ZIO.attemptBlocking(operation).catchSome {
      case error if !error.isInstanceOf[DomainFailure] && remaining > 1 => ZIO.sleep(wait) *> attempt(remaining - 1, wait.multipliedBy(2))
    }
    attempt(Attempts, backoff)
  }

  // How a member ended is reported until the server has it or this host ends: a host that gave up would leave the member in flight on
  // the server for ever, and its drive waiting for it. The pause doubles up to `ceiling`. A fault the server returned is its answer.
  private def persistently[A](operation: => A): Task[A] = {
    def attempt(wait: zio.Duration): Task[A] = ZIO.attemptBlocking(operation).catchSome {
      case error if !error.isInstanceOf[DomainFailure] => ZIO.sleep(wait) *> attempt(if (wait.multipliedBy(2).compareTo(ceiling) > 0) ceiling else wait.multipliedBy(2))
    }
    attempt(backoff)
  }

  private def abandon(cycle: CycleId, member: LineageMember, stage: String, outcome: String, error: Throwable): UIO[Unit] = {
    val cause = DispatchProjection.concise(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    persistently(client.fail(cycle, member, s"$outcome: $cause".take(DriverPolicy.MaxDetail))).foldZIO(
      unrecorded => ZIO.succeed(report(s"Driver lineage $stage failed for ${DriverPolicy.member(member)}: $cause; the driver was not stopped: ${unrecorded.getMessage}")),
      _ => ZIO.succeed(report(s"Driver lineage $stage failed for ${DriverPolicy.member(member)}: $cause; the driver stopped")))
  }

  // `last` is what the server was told at resume count `at`. A resume since then put the member in flight on the server, whatever `last` says.
  private def follow(cycle: CycleId, parent: LineageMember, member: LineageMember, observed: Task[Option[LineageOutcome]], last: Option[LineageOutcome], at: Long): Task[Unit] =
    ZIO.succeed(resumes(cycle, member)).flatMap { seen =>
      val known = if (seen == at) last else None
      observed.flatMap {
        case Some(LineageOutcome.Settled) => persistently(client.settle(cycle, member))
        case Some(LineageOutcome.Concluded(outcome)) => persistently(client.conclude(cycle, outcome))
        case current if current == known => ZIO.sleep(pause) *> follow(cycle, parent, member, observed, known, seen)
        // A reading taken before the session resumed the member is stale: it is not reported, and the member is read again.
        case current @ Some(LineageOutcome.Resting) => reports.withPermit(ZIO.suspend {
          if (resumes(cycle, member) == seen) reliably(client.rest(cycle, member)).as(current) else ZIO.succeed(known)
        }).flatMap(follow(cycle, parent, member, observed, _, seen))
        case None => reliably(client.inherit(cycle, parent, member)) *> follow(cycle, parent, member, observed, None, seen)
      }
    }

  // `observed` reads where the member stands on this host: empty while the host works on it.
  def track(cycle: CycleId, parent: LineageMember, member: LineageMember, observed: Task[Option[LineageOutcome]]): Task[Unit] =
    if (!fresh(cycle, member)) ZIO.unit
    else reliably(client.inherit(cycle, parent, member)).foldZIO(
      error => abandon(cycle, member, "registration", "could not be registered", error) *> ZIO.succeed(finished(cycle, member)),
      _ => follow(cycle, parent, member, observed, None, 0L).catchAll(abandon(cycle, member, "settlement", "could not be settled", _))
        .ensuring(ZIO.succeed(finished(cycle, member))).forkDaemon.unit)
  // The session made the host work on a member again. A followed member is in flight on the server before this returns, so a continuation
  // query that follows the dispatch reply never finds it resting. A fault the server returns means the cycle is over and holds nothing;
  // the member's follower reports it. A member that is not followed is registered and followed.
  def resume(cycle: CycleId, parent: LineageMember, member: LineageMember, observed: Task[Option[LineageOutcome]]): Task[Unit] =
    reports.withPermit(ZIO.suspend {
      if (resumed(cycle, member)) reliably(client.inherit(cycle, parent, member)).as(true) else ZIO.succeed(false)
    }).foldZIO({
      case _: DomainFailure => ZIO.unit
      case error => abandon(cycle, member, "resumption", "could not be resumed", error)
    }, followed => if (followed) ZIO.unit else track(cycle, parent, member, observed))
}

final class AttachedDriver(config: SupervisorConfig, authority: SupervisorAuthority, units: DispatchUnits,
  integrations: IntegrationController, combinations: CombinationController, logger: IzLogger) {
  private val WaitMillis = 20000
  private val PollMillis = 1000L
  private val BackoffMillis = 250L
  // The longest pause between two reports of an end the server has not acknowledged: a retry interval, not a deadline.
  private val MaxBackoffMillis = 30000L
  private val project = config.project.project
  val session = new DriverSessionClient(authority.governor, project)
  private val tracker = new LineageTracker(session, message => logger.warn(s"$message"), zio.Duration.fromMillis(PollMillis), zio.Duration.fromMillis(BackoffMillis), zio.Duration.fromMillis(MaxBackoffMillis))
  private val entry = new DriverEntry(authority.root, project)

  // The Pi extension's private channel: state-changing driver entry points keyed by its session, bound to this attached session.
  def extension(command: ExtensionDriver): DriverReply = {
    require(config.run.attempt.harness == Harness.Pi, "Only the Pi extension drives through its attached host")
    def call(key: String): DriverCall = DriverCall(Harness.Pi.toString.toLowerCase, DriverOrigin.Extension.toString, Some(key))
    command match {
      case ExtensionDriver.Start(key, input) => entry.start(call(key), input, Some(config.run.attempt.session))
      case ExtensionDriver.Park(key) => entry.park(call(key))
      case ExtensionDriver.Continue(key, waiting) => entry.continuation(call(key), waiting)
      case ExtensionDriver.Status(key) => entry.status(call(key))
    }
  }

  // Defined while this session's driver has issued a start directive that no activation has used yet: the integrations the server lets
  // the session settle before that activation, which are those earlier drives left unsettled. The host's current workflow is then the
  // one of an earlier drive or of the session before it was driven.
  def settleable: Option[Set[IntegrationId]] = session.settleable

  private def attempt(id: AttemptId): Task[Option[LineageOutcome]] = units.standing(id, WaitMillis).map {
    case AttemptStanding.Working => None
    case AttemptStanding.Resting => Some(LineageOutcome.Resting)
    case AttemptStanding.Concluded(outcome) => Some(LineageOutcome.Concluded(outcome))
  }
  // The request of a unit with the one attempt `id`: it stands as that attempt does.
  private def request(id: AttemptId): Task[Option[LineageOutcome]] = attempt(id).map(_.map {
    case _: LineageOutcome.Concluded => LineageOutcome.Settled
    case other => other
  })

  // A status wait returns at once once a member has left the phase it was awaited in, so the tracker paces every further read.
  def observe(activation: Option[WorkflowActivation], command: DispatchCommand, reply: DispatchReply): Task[Unit] =
    activation.flatMap(value => value.cycle.map(_ -> LineageMember.Run(value.id))).fold(ZIO.unit) { case (cycle, run) => (command, reply) match {
      // The unit is in flight, as its request, until it has ended; each attempt the host makes for it is registered under the request
      // before that and settles with its own outcome. The request therefore covers the time between two candidates.
      case (_: DispatchCommand.StartChoice | _: DispatchCommand.Start, DispatchReply.Status(status)) =>
        val request = LineageMember.Request(status.request)
        val known = new java.util.concurrent.atomic.AtomicInteger(0)
        val unit = ZIO.suspend(units.lineage(status.attempt, known.get, WaitMillis)).flatMap { (attempts, ended) =>
          ZIO.foreachDiscard(attempts)(attempt => tracker.track(cycle, request, LineageMember.Attempt(attempt),
            units.concluded(attempt, WaitMillis).map(_.map(LineageOutcome.Concluded.apply)))) *>
            ZIO.succeed { known.set(attempts.size); Option.when(ended)(LineageOutcome.Settled) }
        }
        tracker.track(cycle, run, request, unit) *> unit.unit
      // The governing session's own work is one attempt under its request. While its workspace is open both rest on the session, so
      // a stop is answered as for any work that waits for the session; the host works on it from its submission or its cancellation.
      case (_: DispatchCommand.OpenWorkspace | _: DispatchCommand.SelfReview, DispatchReply.Status(status)) =>
        tracker.track(cycle, run, LineageMember.Request(status.request), request(status.attempt)) *>
          tracker.track(cycle, LineageMember.Request(status.request), LineageMember.Attempt(status.attempt), attempt(status.attempt))
      case (_: DispatchCommand.SubmitWorkspace | _: DispatchCommand.Cancel, DispatchReply.Status(status)) =>
        ZIO.attempt(units.governing(status.attempt)).flatMap { own =>
          if (!own) ZIO.unit
          else tracker.resume(cycle, run, LineageMember.Request(status.request), request(status.attempt)) *>
            tracker.resume(cycle, LineageMember.Request(status.request), LineageMember.Attempt(status.attempt), attempt(status.attempt))
        }
      case (DispatchCommand.PrepareIntegration(id, _), _: DispatchReply.Integration) =>
        tracker.track(cycle, run, LineageMember.Integration(id), integrations.status(id, WaitMillis).map(value => AttachedDriver.integration(value.phase)))
      // Integrate returns once the host applies the integration; the tracker's next poll may be a status wait away.
      case (DispatchCommand.Integrate(id), _: DispatchReply.Integration) =>
        tracker.resume(cycle, run, LineageMember.Integration(id), integrations.status(id, WaitMillis).map(value => AttachedDriver.integration(value.phase)))
      // A discarded integration no longer rests on the session: it is in flight until the tracker reads it as settled.
      case (DispatchCommand.DiscardIntegration(id), _: DispatchReply.Integration) =>
        tracker.resume(cycle, run, LineageMember.Integration(id), integrations.status(id, WaitMillis).map(value => AttachedDriver.integration(value.phase)))
      case (DispatchCommand.Combine(id, _, _), _: DispatchReply.Combination) =>
        tracker.track(cycle, run, LineageMember.Combination(id), combinations.status(id, WaitMillis).map(value => AttachedDriver.combination(value.phase)))
      case _ => ZIO.unit
    }}
}

object AttachedDriver {
  // The host works on an integration only while it prepares or applies it; a prepared or pending one waits for the session.
  def integration(phase: IntegrationPhase): Option[LineageOutcome] = phase match {
    case IntegrationPhase.Preparing | IntegrationPhase.Running => None
    case IntegrationPhase.Ready | IntegrationPhase.Pending => Some(LineageOutcome.Resting)
    case IntegrationPhase.Recorded | IntegrationPhase.NotApplied | IntegrationPhase.Failed => Some(LineageOutcome.Settled)
  }

  // A combination whose publication is pending waits for the session to repeat it.
  def combination(phase: CombinationPhase): Option[LineageOutcome] = phase match {
    case CombinationPhase.Preparing => None
    case CombinationPhase.PublicationPending => Some(LineageOutcome.Resting)
    case CombinationPhase.Ready | CombinationPhase.Failed => Some(LineageOutcome.Settled)
  }
}
