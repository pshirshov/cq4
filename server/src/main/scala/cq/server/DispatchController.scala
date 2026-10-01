package cq.server

import cq.api.*
import cq.core.{DomainFailure, LedgerPolicy}
import cq.host.*
import distage.Lifecycle
import java.nio.file.Path
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
      view = view.copy(phase = DispatchPhase.Stopping, blocker = stop)
      true
    }
  }
  def phase(value: DispatchPhase): Unit = synchronized { view = view.copy(phase = if (stop.nonEmpty) DispatchPhase.Stopping else value) }
  def own(value: AttemptId): Unit = synchronized { check(); owned += value }
  def active(value: AttemptId): Unit = synchronized { own(value); job = Some(value) }
  def freeze(): Option[String] = synchronized { publishing = true; view = view.copy(phase = DispatchPhase.Publishing); stop }
  def finish(value: DispatchStatus): Unit = synchronized { view = DispatchProjection.bounded(value); publishing = true }
}

final case class SelectedDispatch(cohort: Option[UUID], evidence: ArtifactId, admit: () => Unit)

final class DispatchController(config: SupervisorConfig, runner: ChildRunner, jobs: JobSupervisor, candidates: CandidateWorkspace, clock: Clock) {
  private val AcknowledgementMillis = 1000L
  private val MaxChildren = 32
  private val MaxStatusWaitMillis = 20000
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
        require(entries.size < MaxChildren, "Governing session reached its child-attempt bound")
        DispatchController.admissible(entries.values.filter(entry => !DispatchController.terminal(entry.status.phase)).map(_.ticket.request).toList, request)
        SupervisorConfig.within(request.limits, config.settings.limits)
        val profile = config.settings.harnesses.find(_.harness == request.harness).getOrElse(throw new IllegalArgumentException("Requested harness route is not configured"))
        candidates.verifyTargetClean()
        val id = AttemptId(UUID.randomUUID())
        val members = request.members.map(_.id).toSet
        val assignment = Assignment(AssignmentId(UUID.randomUUID()), config.project.project, members,
          if (members.size == 1) Attribution.Direct else Attribution.Shared,
          selection.fold(if (members.size == 1) None else Some(UUID.randomUUID()))(_.cohort), config.run.assignment.evaluation)
        val attempt = Attempt(id, assignment.id, Some(config.run.attempt.id), config.run.attempt.session, ChildContracts.role(request.work),
          profile.harness, profile.provider, profile.model, "CQ native collector 0.1.0", clock.millis())
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
      } *> ready.succeed(()).unit *> runner.run(entry)).catchAll { failure =>
        ZIO.succeed {
          disabled.set(true)
          entry.finish(entry.status.copy(phase = DispatchPhase.Unknown, next = ChildNext.InspectEvidence,
            blocker = Some(DispatchProjection.concise("Dispatch storage/publication failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))), detailsOmitted = true))
        } *> ready.fail(failure).unit
      }.ensuring(done.succeed(()).unit)
      execute.forkDaemon.unit
    }
    _ <- entry.ready.await.timeoutFail(new IllegalStateException("Dispatch journal acknowledgement deadline exceeded; admission disabled"))(zio.Duration.fromMillis(AcknowledgementMillis))
      .tapError(error => ZIO.succeed { disabled.set(true); entry.requestStop(error.getMessage) })
    result <- snapshot(entry)
  } yield result
  private def snapshot(entry: DispatchExecution): Task[DispatchStatus] = entry.activeJob match {
    case None => ZIO.succeed(entry.status)
    case Some(id) => jobs.status(config.owner, id).map(record => DispatchProjection.bounded(entry.status.copy(process = Some(record.phase)))).catchSome {
      case DomainFailure(_: Fault.Missing) => ZIO.succeed(entry.status)
    }
  }
  def status(attempt: AttemptId, waitMillis: Int): Task[DispatchStatus] = for {
    entry <- ZIO.attempt { require(waitMillis >= 0 && waitMillis <= MaxStatusWaitMillis, "Status wait must be 0–20000 ms"); found(attempt) }
    _ <- if (waitMillis == 0) ZIO.unit else entry.done.await.timeout(zio.Duration.fromMillis(waitMillis)).unit
    result <- snapshot(entry)
  } yield result
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
    ZIO.attempt { entry.check(); require(!DispatchController.terminal(entry.status.phase), "Child workspace capability has ended") } *> runner.workspace(entry, command)
  }
  def quiescent: Boolean = synchronized(entries.values.forall(value => DispatchController.terminal(value.status.phase)))

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
  final class Resource(config: SupervisorConfig, runner: ChildRunner, jobs: JobSupervisor, candidates: CandidateWorkspace, clock: Clock, watchdog: SupervisorWatchdog)
    extends Lifecycle.Of[Task, DispatchController](
    Lifecycle.make(ZIO.succeed(new DispatchController(config, runner, jobs, candidates, clock)))(value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown)
  )
}
