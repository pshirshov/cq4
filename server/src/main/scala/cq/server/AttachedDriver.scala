package cq.server

import cq.api.*
import cq.core.{DomainFailure, DriverPolicy}
import cq.host.{DispatchProjection, DriverCall, DriverEntry, DriverSessionClient}
import logstage.IzLogger
import zio.{Task, UIO, ZIO}

// Where a lineage member stands on its host once the host no longer works on it: finished, or waiting for the session to act on it.
sealed trait LineageOutcome
object LineageOutcome {
  case object Settled extends LineageOutcome
  case object Resting extends LineageOutcome
}

// Registers the work an attached session dispatches from a driven advance run under that run's cycle and keeps the server's view of each
// member current: in flight, resting or settled. A member the host cannot account for ends the drive.
final class LineageTracker(client: DriverSessionClient, report: String => Unit, pause: zio.Duration, backoff: zio.Duration) {
  private val Attempts = 6
  private var tracked = Set.empty[(CycleId, LineageMember)]
  private def fresh(cycle: CycleId, member: LineageMember): Boolean = synchronized {
    val added = !tracked.contains((cycle, member))
    tracked += ((cycle, member))
    added
  }
  private def finished(cycle: CycleId, member: LineageMember): Unit = synchronized { tracked -= ((cycle, member)) }

  // A request lost in transit is repeated with a doubling pause. A fault the server returned is its answer and is not repeated.
  private def reliably[A](operation: => A): Task[A] = {
    def attempt(remaining: Int, wait: zio.Duration): Task[A] = ZIO.attemptBlocking(operation).catchSome {
      case error if !error.isInstanceOf[DomainFailure] && remaining > 1 => ZIO.sleep(wait) *> attempt(remaining - 1, wait.multipliedBy(2))
    }
    attempt(Attempts, backoff)
  }

  private def abandon(cycle: CycleId, member: LineageMember, stage: String, outcome: String, error: Throwable): UIO[Unit] = {
    val cause = DispatchProjection.concise(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    reliably(client.fail(cycle, member, s"$outcome: $cause".take(DriverPolicy.MaxDetail))).foldZIO(
      unrecorded => ZIO.succeed(report(s"Driver lineage $stage failed for ${DriverPolicy.member(member)}: $cause; the driver was not stopped: ${unrecorded.getMessage}")),
      _ => ZIO.succeed(report(s"Driver lineage $stage failed for ${DriverPolicy.member(member)}: $cause; the driver stopped")))
  }

  private def follow(cycle: CycleId, parent: LineageMember, member: LineageMember, observed: Task[Option[LineageOutcome]], last: Option[LineageOutcome]): Task[Unit] =
    observed.flatMap {
      case Some(LineageOutcome.Settled) => reliably(client.settle(cycle, member))
      case current if current == last => ZIO.sleep(pause) *> follow(cycle, parent, member, observed, last)
      case current @ Some(LineageOutcome.Resting) => reliably(client.rest(cycle, member)) *> follow(cycle, parent, member, observed, current)
      case None => reliably(client.inherit(cycle, parent, member)) *> follow(cycle, parent, member, observed, None)
    }

  // `observed` reads where the member stands on this host: empty while the host works on it.
  def track(cycle: CycleId, parent: LineageMember, member: LineageMember, observed: Task[Option[LineageOutcome]]): Task[Unit] =
    if (!fresh(cycle, member)) ZIO.unit
    else reliably(client.inherit(cycle, parent, member)).foldZIO(
      error => abandon(cycle, member, "registration", "could not be registered", error) *> ZIO.succeed(finished(cycle, member)),
      _ => follow(cycle, parent, member, observed, None).catchAll(abandon(cycle, member, "settlement", "could not be settled", _))
        .ensuring(ZIO.succeed(finished(cycle, member))).forkDaemon.unit)
  def record(cycle: CycleId, parent: LineageMember, member: LineageMember): Task[Unit] = track(cycle, parent, member, ZIO.some(LineageOutcome.Settled))
}

final class AttachedDriver(config: SupervisorConfig, authority: SupervisorAuthority, dispatch: DispatchController,
  integrations: IntegrationController, combinations: CombinationController, logger: IzLogger) {
  private val WaitMillis = 20000
  private val PollMillis = 1000L
  private val BackoffMillis = 250L
  private val project = config.project.project
  val session = new DriverSessionClient(authority.governor, project)
  private val tracker = new LineageTracker(session, message => logger.warn(s"$message"), zio.Duration.fromMillis(PollMillis), zio.Duration.fromMillis(BackoffMillis))
  private val entry = new DriverEntry(authority.root, project)

  // The Pi extension's private channel: state-changing driver entry points keyed by its session, bound to this attached session.
  def extension(command: ExtensionDriver): DriverReply = {
    require(config.run.attempt.harness == Harness.Pi, "Only the Pi extension drives through its attached host")
    def call(key: String): DriverCall = DriverCall(Harness.Pi.toString.toLowerCase, DriverOrigin.Extension.toString, Some(key))
    command match {
      case ExtensionDriver.Start(key, input) => entry.start(call(key), input, Some(config.run.attempt.session))
      case ExtensionDriver.Park(key) => entry.park(call(key))
      case ExtensionDriver.Continue(key) => entry.continuation(call(key))
      case ExtensionDriver.Status(key) => entry.status(call(key))
    }
  }

  // A status wait returns at once once a member has left the phase it was awaited in, so the tracker paces every further read.
  def observe(activation: Option[WorkflowActivation], command: DispatchCommand, reply: DispatchReply): Task[Unit] =
    activation.flatMap(value => value.cycle.map(_ -> LineageMember.Run(value.id))).fold(ZIO.unit) { case (cycle, run) => (command, reply) match {
      case (_: DispatchCommand.StartChoice | _: DispatchCommand.Start, DispatchReply.Status(status)) =>
        val request = LineageMember.Request(status.request)
        tracker.record(cycle, run, request) *> tracker.track(cycle, request, LineageMember.Attempt(status.attempt),
          dispatch.status(status.attempt, WaitMillis).map(value => Option.when(DispatchController.terminal(value.phase))(LineageOutcome.Settled)))
      case (DispatchCommand.PrepareIntegration(id, _), _: DispatchReply.Integration) =>
        tracker.track(cycle, run, LineageMember.Integration(id), integrations.status(id, WaitMillis).map(value => AttachedDriver.integration(value.phase)))
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
