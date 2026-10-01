package cq.server

import cq.api.*
import cq.host.{DriverCall, DriverEntry, DriverSessionClient}
import logstage.IzLogger
import zio.{Task, ZIO}

// Registers the work an attached session dispatches from a driven advance run under that run's cycle, and reports when each member settles.
final class LineageTracker(client: DriverSessionClient, report: String => Unit) {
  private var tracked = Set.empty[(CycleId, LineageMember)]
  private def fresh(cycle: CycleId, member: LineageMember): Boolean = synchronized {
    val added = !tracked.contains((cycle, member))
    tracked += ((cycle, member))
    added
  }
  // `settled` completes when the member is no longer in flight on this host.
  def track(cycle: CycleId, parent: LineageMember, member: LineageMember, settled: Task[Unit]): Task[Unit] =
    if (!fresh(cycle, member)) ZIO.unit
    else ZIO.attemptBlocking(client.inherit(cycle, parent, member)).foldZIO(
      error => ZIO.succeed(report(s"Driver lineage registration failed: ${error.getMessage}")),
      _ => (settled *> ZIO.attemptBlocking(client.settle(cycle, member)))
        .catchAll(error => ZIO.succeed(report(s"Driver lineage settlement failed: ${error.getMessage}"))).forkDaemon.unit)
  def record(cycle: CycleId, parent: LineageMember, member: LineageMember): Task[Unit] = track(cycle, parent, member, ZIO.unit)
}

final class AttachedDriver(config: SupervisorConfig, authority: SupervisorAuthority, dispatch: DispatchController,
  integrations: IntegrationController, combinations: CombinationController, logger: IzLogger) {
  private val WaitMillis = 20000
  private val PollMillis = 1000L
  private val project = config.project.project
  val session = new DriverSessionClient(authority.governor, project)
  private val tracker = new LineageTracker(session, message => logger.warn(s"$message"))
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

  // A status wait returns at once while a member rests in a non-terminal phase, so each further poll is paced.
  private def until[A](poll: Task[A])(settled: A => Boolean): Task[Unit] =
    poll.flatMap(value => if (settled(value)) ZIO.unit else ZIO.sleep(zio.Duration.fromMillis(PollMillis)) *> until(poll)(settled))

  def observe(activation: Option[WorkflowActivation], command: DispatchCommand, reply: DispatchReply): Task[Unit] =
    activation.flatMap(value => value.cycle.map(_ -> LineageMember.Run(value.id))).fold(ZIO.unit) { case (cycle, run) => (command, reply) match {
      case (_: DispatchCommand.StartChoice | _: DispatchCommand.Start, DispatchReply.Status(status)) =>
        val request = LineageMember.Request(status.request)
        tracker.record(cycle, run, request) *> tracker.track(cycle, request, LineageMember.Attempt(status.attempt),
          until(dispatch.status(status.attempt, WaitMillis))(value => DispatchController.terminal(value.phase)))
      case (DispatchCommand.PrepareIntegration(id, _), _: DispatchReply.Integration) =>
        tracker.track(cycle, run, LineageMember.Integration(id),
          until(integrations.status(id, WaitMillis))(value => IntegrationController.terminal(value.phase)))
      case (DispatchCommand.Combine(id, _, _), _: DispatchReply.Combination) =>
        tracker.track(cycle, run, LineageMember.Combination(id),
          until(combinations.status(id, WaitMillis))(value => Set(CombinationPhase.Ready, CombinationPhase.Failed)(value.phase)))
      case _ => ZIO.unit
    }}
}
