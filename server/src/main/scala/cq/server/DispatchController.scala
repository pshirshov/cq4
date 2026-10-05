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
  private var ended = Option.empty[ChildOutcome]
  private var withheld = Option.empty[DispatchStatus]
  private var concluded = false
  def outcome: Option[ChildOutcome] = synchronized(ended)
  def ending(value: ChildOutcome): Unit = synchronized { ended = Some(value) }
  def status: DispatchStatus = synchronized(view)
  /** What a reader outside the run sees: a terminal status only once `conclude` has run, and the child as publishing until then. */
  def observed: DispatchStatus = synchronized(withheld.getOrElse(view))
  def conclude(): Unit = synchronized { concluded = true; withheld = None }
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
      view = view.copy(phase = DispatchPhase.Stopping, blocker = stop)
      true
    }
  }
  def phase(value: DispatchPhase): Unit = synchronized { view = view.copy(phase = if (stop.nonEmpty) DispatchPhase.Stopping else value) }
  def own(value: AttemptId): Unit = synchronized { check(); owned += value }
  def active(value: AttemptId): Unit = synchronized { own(value); job = Some(value) }
  def freeze(): Option[String] = synchronized { publishing = true; view = view.copy(phase = DispatchPhase.Publishing); stop }
  def finish(value: DispatchStatus): Unit = synchronized {
    if (!concluded && withheld.isEmpty) withheld = Some(view.copy(phase = DispatchPhase.Publishing))
    view = DispatchProjection.bounded(value)
    publishing = true
  }
}

/** `finished` receives the child's final status and replies with how the attempt ended: whether its input is offered again, or stays
  * deferred because the attempt before it on the same input ended in the same fault.
  * It runs before that status becomes visible to the session, on no lock of the dispatch. */
final case class SelectedDispatch(cohort: Option[UUID], evidence: ArtifactId, admit: () => Unit, finished: DispatchStatus => ChildOutcome)

final class DispatchController(config: SupervisorConfig, runner: ChildRunner, jobs: JobSupervisor, clock: Clock) {
  private val MaxStatusWaitMillis = 20000
  private val units = new SessionUnits(config.directory)
  private val disabled = new AtomicBoolean(false)
  private var closing = false
  private var entries = Map.empty[RequestId, DispatchExecution]
  private def found(attempt: AttemptId): DispatchExecution = synchronized {
    entries.values.find(_.ticket.attempt.id == attempt).getOrElse(throw DomainFailure(Fault.Missing("Child attempt is not owned by this governing session")))
  }
  private def register(request: DispatchRequest, selection: Option[SelectedDispatch], ready: Promise[Throwable, Unit], done: Promise[Nothing, Unit]): (DispatchExecution, Boolean) = synchronized {
    entries.get(request.request) match {
      case Some(existing) =>
        if (existing.ticket.request != request) throw DomainFailure(Fault.Conflict("Dispatch request identity changed"))
        (existing, false)
      case None =>
        require(!closing && !disabled.get(), "Dispatch admission is closed")
        ChildContracts.request(config.project.project, request)
        DispatchController.admissible(entries.values.filter(entry => !DispatchController.terminal(entry.observed.phase)).map(_.ticket.request).toList, request)
        SupervisorConfig.within(request.limits, config.settings.limits)
        val profile = config.settings.harnesses.find(_.harness == request.harness).getOrElse(throw new IllegalArgumentException("Requested harness route is not configured"))
        val id = AttemptId(UUID.randomUUID())
        val members = request.members.map(_.id).toSet
        val assignment = Assignment(AssignmentId(UUID.randomUUID()), config.project.project, members,
          if (members.size == 1) Attribution.Direct else Attribution.Shared,
          selection.fold(if (members.size == 1) None else Some(UUID.randomUUID()))(_.cohort), config.run.assignment.evaluation)
        val attempt = Attempt(id, assignment.id, Some(config.run.attempt.id), config.run.attempt.session, ChildContracts.role(request.work),
          profile.harness, profile.provider, profile.model, "CQ native collector 0.1.0", clock.millis(), ChildContracts.phase(request.work))
        selection.foreach(_.admit())
        val entry = new DispatchExecution(DispatchTicket(request, assignment, attempt, profile, selection.map(_.evidence)), config.directory.resolve("children").resolve(id.value.toString), ready, done)
        entries = entries.updated(request.request, entry)
        (entry, true)
    }
  }
  def start(request: DispatchRequest): Task[DispatchStatus] = execute(request, None)
  def startSelected(request: DispatchRequest, selection: SelectedDispatch): Task[DispatchStatus] = execute(request, Some(selection))
  private def execute(request: DispatchRequest, selection: Option[SelectedDispatch]): Task[DispatchStatus] = for {
    ready <- Promise.make[Throwable, Unit]
    done <- Promise.make[Nothing, Unit]
    registered <- ZIO.attemptBlocking(register(request, selection, ready, done))
    (entry, fresh) = registered
    _ <- if (!fresh) ZIO.unit else {
      val execute = (ZIO.attemptBlocking {
        HostFiles.directory(entry.directory)
        HostFiles.immutable(entry.directory.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, entry.ticket), 65536)
        units.started(SessionUnits.attempt(entry.status))
      } *> ready.succeed(()).unit *> runner.run(entry)).catchAll { failure =>
        ZIO.succeed {
          disabled.set(true)
          entry.finish(entry.status.copy(phase = DispatchPhase.Unknown, next = ChildNext.InspectEvidence,
            blocker = Some(DispatchProjection.concise("Dispatch storage/publication failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))), detailsOmitted = true))
        } *> ready.fail(failure).unit
      // The input is released, and its fault published, while the child still reads as publishing: a status that showed it failed
      // before that would let the session select the same work and find it deferred.
      }.ensuring(ZIO.attemptBlocking(try entry.ending(selection.fold(CohortFailure.outcome(entry.status, None, None))(_.finished(entry.status)))
        finally entry.conclude()).orDie *>
        // What a waiter outside the host reads; written after the end is visible to the session, whose status call may already wait on it.
        done.succeed(()).unit *> ZIO.attemptBlocking(units.ended(SessionUnits.ended(entry.observed))).orDie)
      execute.forkDaemon.unit
    }
    _ <- entry.ready.await
      .tapError(error => ZIO.succeed { disabled.set(true); entry.requestStop(error.getMessage) })
    result <- snapshot(entry)
  } yield result
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
    case None => ZIO.succeed(entry.observed)
    case Some(id) => jobs.status(config.owner, id).flatMap(record => ZIO.attemptBlocking(
      DispatchProjection.bounded(entry.observed.copy(process = Some(record.phase), quietMillis = quiet(id, record))))).catchSome {
      case DomainFailure(_: Fault.Missing) => ZIO.succeed(entry.observed)
    }
  }
  def status(attempt: AttemptId, waitMillis: Int): Task[DispatchStatus] = for {
    entry <- ZIO.attempt { require(waitMillis >= 0 && waitMillis <= MaxStatusWaitMillis, "Status wait must be 0–20000 ms"); found(attempt) }
    _ <- if (waitMillis == 0) ZIO.unit else entry.done.await.timeout(zio.Duration.fromMillis(waitMillis)).unit
    result <- snapshot(entry)
  } yield result
  /** Empty while the attempt runs; then how it ended. */
  def concluded(attempt: AttemptId, waitMillis: Int): Task[Option[ChildOutcome]] = for {
    entry <- ZIO.attempt { require(waitMillis >= 0 && waitMillis <= MaxStatusWaitMillis, "Status wait must be 0–20000 ms"); found(attempt) }
    over <- entry.done.await.timeout(zio.Duration.fromMillis(waitMillis))
  } yield over.map(_ => entry.outcome.getOrElse(throw new IllegalStateException("A finished child attempt has no outcome")))
  def cancel(attempt: AttemptId): Task[DispatchStatus] = for {
    entry <- ZIO.attempt(found(attempt))
    _ <- stop(entry, "Cancelled by the governing session")
    result <- snapshot(entry)
  } yield result
  private def stop(entry: DispatchExecution, reason: String): Task[Unit] = ZIO.attempt(entry.requestStop(reason)).flatMap { requested =>
    if (!requested) ZIO.unit else {
      ZIO.attemptBlocking(HostFiles.immutable(entry.directory.resolve("cancel.txt"), "stop\n", 32)).catchAll { error =>
        ZIO.succeed { disabled.set(true); entry.requestStop("Cancellation record failed: " + error.getClass.getSimpleName) }
      }.forkDaemon.unit *> ZIO.foreachDiscard(entry.ownedJobs) { id =>
        jobs.cancel(config.owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }
          .catchAll(error => ZIO.succeed { disabled.set(true); entry.requestStop("Cancellation acknowledgement failed: " + error.getClass.getSimpleName) }).forkDaemon.unit
      }
    }
  }
  def workspace(attempt: AttemptId, command: WorkspaceCommand): Task[WorkspaceReply] = ZIO.attempt(found(attempt)).flatMap { entry =>
    ZIO.attempt { entry.check(); require(!DispatchController.terminal(entry.observed.phase), "Child workspace capability has ended") } *> runner.workspace(entry, command)
  }
  def unsettled: List[String] = synchronized(entries.values.toList.map(_.observed).filterNot(value => DispatchController.terminal(value.phase))
    .map(value => s"child attempt ${value.attempt.value} (${value.phase})"))
  def quiescent: Boolean = unsettled.isEmpty
  /** The claims under which a sealed publication of a child still awaits delivery: the server admits its result only under the active claim. */
  def undelivered: Set[Fence] = synchronized(entries.values.filter(_.status.phase == DispatchPhase.PublicationPending).map(_.ticket.request.fence).toSet)

  /** A result's checks are rerun only while no child runs on its members and no later worker result for them exists. */
  def revalidatable(result: ChildResult): Unit = synchronized {
    val members = result.request.members.map(_.id).toSet
    val sharing = entries.values.filter(_.ticket.request.members.exists(reference => members(reference.id))).toList
    val active = sharing.filter(entry => !DispatchController.terminal(entry.observed.phase)).flatMap(_.ticket.request.members.map(_.id)).filter(members)
      .distinct.sortBy(LedgerPolicy.key)
    if (active.nonEmpty)
      throw DomainFailure(Fault.Conflict(s"An active child covers ${active.map(id => LedgerPolicy.prefix(id.ledger) + id.number).mkString(", ")}; poll its status before revalidating"))
    val own = sharing.find(_.ticket.attempt.id == result.attempt)
      .getOrElse(throw DomainFailure(Fault.Missing("Result was not produced by a child of this governing session")))
    if (sharing.exists(entry => (entry ne own) && entry.ticket.attempt.startedAt >= own.ticket.attempt.startedAt && entry.observed.result.nonEmpty &&
      entry.ticket.attempt.role == Role.Worker && entry.ticket.request.work != DispatchWork.Worker(WorkerMode.Probe)))
      throw DomainFailure(Fault.Conflict("Result is superseded by a later result for the same members"))
  }

  def shutdown: Task[Unit] = for {
    owned <- ZIO.succeed(synchronized { closing = true; entries.values.toList })
    _ <- ZIO.foreachDiscard(owned)(stop(_, "Governing harness ended; stopping its child hierarchy"))
    _ <- ZIO.foreachDiscard(owned)(_.done.await)
  } yield ()
}

object DispatchController {
  val MaxActiveChildren = 4
  def terminal(phase: DispatchPhase): Boolean = Set(DispatchPhase.Completed, DispatchPhase.Failed, DispatchPhase.Cancelled,
    DispatchPhase.Unknown, DispatchPhase.PublicationPending)(phase)
  // Active children hold disjoint claims: a member belongs to at most one running child (D83).
  def admissible(active: List[DispatchRequest], request: DispatchRequest): Unit = {
    val members = request.members.map(_.id).toSet
    val overlapping = active.flatMap(_.members.map(_.id)).filter(members).distinct.sortBy(LedgerPolicy.key)
    if (overlapping.nonEmpty)
      throw DomainFailure(Fault.Conflict(s"An active child already covers ${overlapping.map(id => LedgerPolicy.prefix(id.ledger) + id.number).mkString(", ")}; poll its status before starting another child on the same members"))
    if (active.size >= MaxActiveChildren)
      throw DomainFailure(Fault.Conflict(s"This session permits at most $MaxActiveChildren active children; poll or cancel one before starting another"))
  }
  final class Resource(config: SupervisorConfig, runner: ChildRunner, jobs: JobSupervisor, clock: Clock, watchdog: SupervisorWatchdog) extends Lifecycle.Of[Task, DispatchController](
    Lifecycle.make(ZIO.succeed(new DispatchController(config, runner, jobs, clock)))(value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown)
  )
}
