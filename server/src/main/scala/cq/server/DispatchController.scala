package cq.server

import cq.api.*
import cq.core.{DomainFailure, LedgerPolicy}
import cq.host.*
import distage.Lifecycle
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import zio.{Promise, Task, ZIO}

private[server] final class DispatchExecution(val ticket: DispatchTicket, val directory: Path,
  val ready: Promise[Throwable, Unit], val done: Promise[Nothing, Unit]) {
  private var view = DispatchProjection.pending(ticket)
  private var stop = Option.empty[String]
  private var publishing = false
  private var job = Option.empty[AttemptId]
  private var owned = Set.empty[AttemptId]
  private var reviewer = Option.empty[ReviewerChecks]
  private var refused = Option.empty[Abstention]
  /** Why the attempt abstained, once it has. */
  def abstention: Option[Abstention] = synchronized(refused)
  def abstained(value: Abstention): Unit = synchronized { refused = Some(value) }
  /** The attempt's own status. A governing session reads the status of the attempt's unit, which `DispatchUnits` derives from it. */
  def status: DispatchStatus = synchronized(view)
  def activeJob: Option[AttemptId] = synchronized(job)
  def ownedJobs: Set[AttemptId] = synchronized(owned)
  def reviewerChecks: Option[ReviewerChecks] = synchronized(reviewer)
  def installChecks(value: ReviewerChecks): Unit = synchronized { require(reviewer.isEmpty, "Reviewer check owner already installed"); reviewer = Some(value) }
  def stopReason: Option[String] = synchronized(stop)
  def check(): Unit = synchronized { stop.foreach(value => throw new IllegalStateException(value)) }
  def requestStop(reason: String): Boolean = synchronized {
    if (publishing || DispatchController.terminal(view.phase)) false
    else {
      stop = stop.orElse(Some(DispatchProjection.concise(reason)))
      view = view.copy(phase = DispatchPhase.Stopping, next = ChildNext.Wait, blocker = stop)
      true
    }
  }
  def phase(value: DispatchPhase): Unit = synchronized { view = view.copy(phase = if (stop.nonEmpty) DispatchPhase.Stopping else value, next = ChildNext.Wait) }
  /** The governing session works in `workspace` until it submits it; the host waits for that. */
  def editing(workspace: WorkspaceState): Unit = synchronized {
    if (stop.isEmpty) view = view.copy(phase = DispatchPhase.Editing, next = ChildNext.Submit, workspace = Some(workspace))
  }
  def own(value: AttemptId): Unit = synchronized { check(); owned += value }
  def active(value: AttemptId): Unit = synchronized { own(value); job = Some(value) }
  def freeze(): Option[String] = synchronized { publishing = true; view = view.copy(phase = DispatchPhase.Publishing); stop }
  def finish(value: DispatchStatus): Unit = synchronized {
    view = DispatchProjection.bounded(value)
    publishing = true
  }
}

/** `finished` receives the final status of a selected unit and replies with how it ended: whether its input is offered again, or stays
  * deferred because the unit before it on the same input ended in the same fault.
  * It runs before that status becomes visible to the session, on no lock of the dispatch. */
final case class SelectedDispatch(cohort: Option[UUID], evidence: ArtifactId, admit: () => Unit, finished: DispatchStatus => ChildOutcome)

/** What a unit gives each attempt it starts: the cohort of the attempt's assignment and the selection evidence its ticket cites.
  * `admit` runs once the attempt is admissible and before it is registered; when it refuses, nothing is registered. */
final case class AttemptOrigin(cohort: Option[UUID], selection: Option[ArtifactId], admit: () => Unit)

/** Runs the child attempts of one governing session, each on the model route its unit names. What a governing session starts, reads
  * and cancels is a unit (`DispatchUnits`); an attempt is one candidate of one seat of a unit. */
final class DispatchController(config: SupervisorConfig, runner: ChildRunner, governor: GovernorWork, jobs: JobSupervisor, clock: Clock) {
  private val disabled = new AtomicBoolean(false)
  private var closing = false
  private var entries = Vector.empty[DispatchExecution]
  // The tickets of the attempts that have not ended. Called under the lock.
  private def live: List[DispatchTicket] = entries.filter(entry => !DispatchController.terminal(entry.status.phase)).map(_.ticket).toList
  private def found(attempt: AttemptId): DispatchExecution = synchronized {
    entries.find(_.ticket.attempt.id == attempt).getOrElse(throw DomainFailure(Fault.Missing("Child attempt is not owned by this governing session")))
  }
  /** Registers an attempt on the model, provider and effort of `route`; a route that names no provider takes that of the settings
    * entry of its harness. Nothing of the attempt runs before `launch`. */
  def register(request: DispatchRequest, route: ModelRoute, origin: AttemptOrigin): Task[DispatchExecution] = for {
    ready <- Promise.make[Throwable, Unit]
    done <- Promise.make[Nothing, Unit]
    entry <- ZIO.attemptBlocking(synchronized {
      require(!closing && !disabled.get(), DispatchController.Closed)
      ChildContracts.request(config.project.project, request)
      val (governing, children) = live.partition(AttemptSettlement.own)
      // The governing session's own work holds its members as a child does and no child slot: it runs no process.
      DispatchController.uncovered(governing.map(_.request), request)
      DispatchController.admissible(children.map(_.request), request)
      SupervisorConfig.within(request.limits, config.settings.limits)
      require(route.harness == request.harness, "Model route and dispatch request name different harnesses")
      val profile = config.settings.harnesses.find(_.harness == request.harness)
      // The attempt is recorded even when its harness has no settings entry, and then abstains before any launch. Only the route or
      // the harness can then name the provider; where neither does, the record says that none was configured.
      val provider = route.provider.orElse(profile.map(_.provider)).orElse(Option.when(request.harness == Harness.Claude)(ClaudeAdapter.Provider))
        .getOrElse(DispatchController.UnconfiguredProvider)
      val id = AttemptId(UUID.randomUUID())
      val members = request.members.map(_.id).toSet
      val assignment = Assignment(AssignmentId(UUID.randomUUID()), config.project.project, members,
        if (members.size == 1) Attribution.Direct else Attribution.Shared, origin.cohort, config.run.assignment.evaluation)
      val attempt = Attempt(id, assignment.id, Some(config.run.attempt.id), config.run.attempt.session, ChildContracts.role(request.work),
        request.harness, provider, route.model, "CQ native collector 0.1.0", clock.millis(), ChildContracts.phase(request.work), route.effort)
      origin.admit()
      val entry = new DispatchExecution(DispatchTicket(request, assignment, attempt, profile, origin.selection), config.directory.resolve("children").resolve(id.value.toString), ready, done)
      entries = entries :+ entry
      entry
    })
  } yield entry
  /** Registers an attempt of the governing session itself on `request`: its own work in a host workspace, or its own review. The
    * attempt is of the Governor role under the governing attempt, on that attempt's harness, provider, model and effort, and it has no
    * settings entry: the host launches nothing for it. */
  def own(request: DispatchRequest, cohort: Option[UUID]): Task[DispatchExecution] = for {
    ready <- Promise.make[Throwable, Unit]
    done <- Promise.make[Nothing, Unit]
    entry <- ZIO.attemptBlocking(synchronized {
      require(!closing && !disabled.get(), DispatchController.Closed)
      ChildContracts.request(config.project.project, request)
      require(cq.core.GoverningWorkPolicy.permits(request.work), "The governing session works itself only as a Worker Implement or a Reviewer Candidate")
      val (own, children) = live.filter(_.request.request != request.request).partition(AttemptSettlement.own)
      DispatchController.uncovered(own.map(_.request), request)
      DispatchController.disjoint(children.map(_.request), request)
      SupervisorConfig.within(request.limits, config.settings.limits)
      val governing = config.run.attempt
      require(request.harness == governing.harness, "The governing session's own work is on its own harness")
      val id = AttemptId(UUID.randomUUID())
      val members = request.members.map(_.id).toSet
      val assignment = Assignment(AssignmentId(UUID.randomUUID()), config.project.project, members,
        if (members.size == 1) Attribution.Direct else Attribution.Shared, cohort, config.run.assignment.evaluation)
      val attempt = Attempt(id, assignment.id, Some(governing.id), governing.session, Role.Governor, governing.harness, governing.provider, governing.model,
        DispatchController.OwnWorkCollector, clock.millis(), ChildContracts.phase(request.work), governing.effort)
      val entry = new DispatchExecution(DispatchTicket(request, assignment, attempt, None, None), config.directory.resolve("children").resolve(id.value.toString), ready, done)
      entries = entries :+ entry
      entry
    })
  } yield entry
  /** Runs a registered attempt and returns once its ticket is retained. `prepared` runs before that; `ended` once the attempt's
    * terminal status stands, however the attempt ended. */
  def launch(entry: DispatchExecution, prepared: () => Unit, ended: zio.UIO[Unit]): Task[DispatchStatus] = {
    val execute = (ZIO.attemptBlocking {
      HostFiles.directory(entry.directory)
      HostFiles.immutable(entry.directory.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, entry.ticket), 65536)
      prepared()
    } *> entry.ready.succeed(()).unit *> (if (AttemptSettlement.own(entry.ticket)) governor.run(entry) else runner.run(entry))).catchAll { failure =>
      ZIO.succeed {
        disabled.set(true)
        entry.finish(entry.status.copy(phase = DispatchPhase.Unknown, next = ChildNext.InspectEvidence,
          blocker = Some(DispatchProjection.concise("Dispatch storage/publication failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))), detailsOmitted = true))
      } *> governor.abandon(entry.ticket.attempt.id) *> entry.ready.fail(failure).unit
    }.ensuring(ZIO.succeed {
      // A run that ended without publishing left no receipt: what it did is not known.
      if (!DispatchController.terminal(entry.status.phase)) entry.finish(entry.status.copy(phase = DispatchPhase.Unknown, next = ChildNext.InspectEvidence,
        blocker = Some("The child attempt ended without a terminal status"), detailsOmitted = true))
    } *> entry.done.succeed(()).unit *> ended)
    // A unit starts its next candidate while the one before it ends, which is a region that cannot be interrupted; a run begun there
    // would inherit that and its claim renewal could never be stopped.
    execute.interruptible.forkDaemon *> entry.ready.await.tapError(error => ZIO.succeed { disabled.set(true); entry.requestStop(error.getMessage) }) *> snapshot(entry)
  }
  /**
   * Time since a running job last wrote to stdout or stderr, read from the modification times of its output files when a status is asked for.
   * A harness is also silent throughout a long tool call, so the host reports this and never acts on it.
   */
  private def quiet(id: AttemptId, record: JobRecord): Option[Long] = Option.when(record.phase == JobPhase.Running) {
    val payload = config.directory.resolve("payload").resolve(id.value.toString)
    val written = List("stdout", "stderr").map(name => Files.getLastModifiedTime(payload.resolve(name)).toMillis).max
    math.max(0L, clock.millis() - written)
  }
  private def snapshot(entry: DispatchExecution): Task[DispatchStatus] = entry.activeJob match {
    case None => ZIO.succeed(entry.status)
    case Some(id) => jobs.status(config.owner, id).flatMap(record => ZIO.attemptBlocking(
      DispatchProjection.bounded(entry.status.copy(process = Some(record.phase), quietMillis = quiet(id, record))))).catchSome {
      case DomainFailure(_: Fault.Missing) => ZIO.succeed(entry.status)
    }
  }
  /** The attempt's own status now, with its process and how long it has been quiet. */
  def status(attempt: AttemptId): Task[DispatchStatus] = ZIO.attempt(found(attempt)).flatMap(snapshot)
  /** Asks the attempt to stop. False when it no longer can be stopped: it is publishing or has ended. */
  def stop(attempt: AttemptId, reason: String): Task[Boolean] = ZIO.attempt(found(attempt)).flatMap(stop(_, reason))
  private def stop(entry: DispatchExecution, reason: String): Task[Boolean] = ZIO.attempt(entry.requestStop(reason)).flatMap { requested =>
    if (!requested) ZIO.succeed(false) else {
      ZIO.attemptBlocking(HostFiles.immutable(entry.directory.resolve("cancel.txt"), "stop\n", 32)).catchAll { error =>
        ZIO.succeed { disabled.set(true); entry.requestStop("Cancellation record failed: " + error.getClass.getSimpleName) }
      }.forkDaemon.unit *> ZIO.foreachDiscard(entry.ownedJobs) { id =>
        jobs.cancel(config.owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }
          .catchAll(error => ZIO.succeed { disabled.set(true); entry.requestStop("Cancellation acknowledgement failed: " + error.getClass.getSimpleName) }).forkDaemon.unit
      } *> governor.withdraw(entry.ticket.attempt.id).as(true)
    }
  }
  def workspace(attempt: AttemptId, command: WorkspaceCommand): Task[WorkspaceReply] = ZIO.attempt(found(attempt)).flatMap { entry =>
    ZIO.attempt { entry.check(); require(!DispatchController.terminal(entry.status.phase), "Child workspace capability has ended") } *> runner.workspace(entry, command)
  }
  /** The claims under which a sealed publication of a child still awaits delivery: the server admits its result only under the active claim. */
  def undelivered: Set[Fence] = synchronized(entries.filter(_.status.phase == DispatchPhase.PublicationPending).map(_.ticket.request.fence).toSet)

  /** A result's checks are rerun only while no later worker result for its members exists. `DispatchUnits` refuses it while a unit covers them. */
  def superseded(result: ChildResult): Unit = synchronized {
    val members = result.request.members.map(_.id).toSet
    val sharing = entries.filter(_.ticket.request.members.exists(reference => members(reference.id))).toList
    val own = sharing.find(_.ticket.attempt.id == result.attempt)
      .getOrElse(throw DomainFailure(Fault.Missing("Result was not produced by a child of this governing session")))
    // A worker result is one whoever made it: a Worker child, or the governing session in its own workspace.
    if (sharing.exists(entry => (entry ne own) && entry.ticket.attempt.startedAt >= own.ticket.attempt.startedAt && entry.status.result.nonEmpty &&
      ChildContracts.role(entry.ticket.request.work) == Role.Worker && entry.ticket.request.work != DispatchWork.Worker(WorkerMode.Probe)))
      throw DomainFailure(Fault.Conflict("Result is superseded by a later result for the same members"))
  }

  def shutdown: Task[Unit] = for {
    owned <- ZIO.succeed(synchronized { closing = true; entries.toList })
    _ <- ZIO.foreachDiscard(owned)(stop(_, DispatchController.Ending))
    _ <- ZIO.foreachDiscard(owned)(_.done.await)
  } yield ()
}

object DispatchController {
  val MaxActiveChildren = cq.core.ChildCapacity.MaxActiveChildren
  val Closed = "Dispatch admission is closed"
  val Ending = "Governing harness ended; stopping its child hierarchy"
  /** The provider of an attempt whose route names none and whose harness has no settings entry to take one from. */
  val UnconfiguredProvider = "unconfigured"
  /** The collector of an attempt of the governing session's own work: the host records it and collects no usage for it. */
  val OwnWorkCollector = "CQ host; own work of the governing session, no meter"
  def terminal(phase: DispatchPhase): Boolean = Set(DispatchPhase.Completed, DispatchPhase.Failed, DispatchPhase.Cancelled,
    DispatchPhase.Unknown, DispatchPhase.PublicationPending, DispatchPhase.Abstained)(phase)
  // Active units hold disjoint claims: a member belongs to at most one running unit (D83).
  def disjoint(active: List[DispatchRequest], request: DispatchRequest): Unit = overlap(active, request).foreach(covered =>
    throw DomainFailure(Fault.Conflict(s"An active child already covers $covered; wait for it to end before starting another child on the same members")))
  /** The same rule against the governing session's own work, which does not end by itself: only the session ends it. */
  def uncovered(own: List[DispatchRequest], request: DispatchRequest): Unit = overlap(own, request).foreach(covered =>
    throw DomainFailure(Fault.Conflict(s"The governing session's own open workspace already covers $covered; submit or cancel it before starting other work on the same members")))
  private def overlap(active: List[DispatchRequest], request: DispatchRequest): Option[String] = {
    val members = request.members.map(_.id).toSet
    val overlapping = active.flatMap(_.members.map(_.id)).filter(members).distinct.sortBy(LedgerPolicy.key)
    Option.when(overlapping.nonEmpty)(overlapping.map(id => LedgerPolicy.prefix(id.ledger) + id.number).mkString(", "))
  }
  /** `active` child attempts run; `wanted` more are to start together. */
  def capacity(active: Int, wanted: Int): Unit =
    if (active + wanted > MaxActiveChildren)
      throw DomainFailure(Fault.Conflict(s"This session permits at most $MaxActiveChildren active children; wait for one to end or cancel it before starting another"))
  // The attempts of one unit share its members: the seats of a panel run side by side, and a candidate follows the one that abstained.
  // Each of them still counts against the bound.
  def admissible(active: List[DispatchRequest], request: DispatchRequest): Unit = {
    disjoint(active.filter(_.request != request.request), request)
    capacity(active.size, 1)
  }
  final class Resource(config: SupervisorConfig, runner: ChildRunner, governor: GovernorWork, jobs: JobSupervisor, clock: Clock, watchdog: SupervisorWatchdog) extends Lifecycle.Of[Task, DispatchController](
    Lifecycle.make(ZIO.succeed(new DispatchController(config, runner, governor, jobs, clock)))(value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown)
  )
}
