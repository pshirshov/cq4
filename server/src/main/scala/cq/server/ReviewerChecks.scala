package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import java.time.Duration
import scala.util.Try
import zio.{Promise, Task, ZIO}

private[server] final case class ClosedReviewerChecks(evidence: List[ValidationEvidence], pending: Boolean, uncertain: Boolean)

private[server] final class ReviewerChecks(entry: DispatchExecution, candidate: GitCommit, config: SupervisorConfig,
  api: ServerApi, jobs: JobSupervisor) {
  private val MaxWaitMillis = 20000
  /** `ticket` is the run in progress or the last one: a failed run is followed by another until the check passes or its attempts are used. */
  private final class Execution(var ticket: DeclaredCheckTicket, val command: JobCommand, val done: Promise[Nothing, Unit]) {
    var status = starting(ticket)
  }
  private def starting(ticket: DeclaredCheckTicket): DeclaredCheckStatus =
    DeclaredCheckStatus(ticket.check.name, ticket.workspace.attempt, DeclaredCheckPhase.Starting, None, None)
  private def ticket(declaration: ValidationCheck, command: JobCommand, failures: List[ArtifactId]): DeclaredCheckTicket = {
    val id = DeclaredCheckPublication.job(entry.ticket.attempt.id, declaration.name, failures.size + 1)
    DeclaredCheckTicket(entry.ticket.attempt.id, declaration,
      WorkspaceSpec(config.project.project, config.run.attempt.session, id, config.run.repository, candidate), command.fingerprint, failures)
  }
  private var executions = Map.empty[String, Execution]
  private var closed = Option.empty[List[(Execution, DeclaredCheckStatus)]]
  private var cleanupUncertain = false

  private def register(name: String, done: Promise[Nothing, Unit]): (Execution, Boolean) = synchronized {
    entry.check()
    require(closed.isEmpty, "Reviewer check admission has ended")
    require(entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate), "Only candidate reviewers may request checks")
    val declaration = config.settings.checks.find(_.name == name).getOrElse(throw new IllegalArgumentException("Unknown declared check name"))
    executions.get(name) match {
      case Some(value) => (value, false)
      case None =>
        if (executions.values.exists(value => !Set(DeclaredCheckPhase.Completed, DeclaredCheckPhase.Unknown, DeclaredCheckPhase.Failed)(value.status.phase)))
          throw DomainFailure(Fault.Conflict("Another declared check is running; poll it before requesting another name"))
        val source = config.limits
        val limits = ExecutionLimits(source.startup, Some(Duration.ofMillis(declaration.executionMillis)), source.heartbeat, source.grace, source.kill, declaration.retainedOutputBytes)
        val command = JobCommand(declaration.command, HostEnvironment.runtime(config.environment), "", limits)
        val execution = new Execution(ticket(declaration, command, Nil), command, done)
        entry.own(execution.ticket.workspace.attempt)
        executions = executions.updated(name, execution)
        (execution, true)
    }
  }
  private def status(value: Execution): DeclaredCheckStatus = synchronized(value.status)
  private def update(value: Execution)(change: DeclaredCheckStatus => DeclaredCheckStatus): Unit = synchronized { value.status = change(value.status) }
  private def stopping: Boolean = synchronized(closed.nonEmpty) || entry.stopReason.nonEmpty
  private def cancel(value: Execution): Task[Unit] = jobs.cancel(config.owner, synchronized(value.ticket).workspace.attempt).unit
    .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }

  /** Publishes the settled run as the check's outcome, or, when it failed with attempts left and the reviewer still runs, starts the next. */
  private def advance(value: Execution, settled: DeclaredCheckStatus): Option[DeclaredCheckTicket] = synchronized {
    val previous = value.ticket
    val next = settled.evidence.filter(evidence => settled.phase == DeclaredCheckPhase.Completed && evidence.state == ValidationState.Failed &&
      previous.failures.size + 1 < previous.check.attempts && closed.isEmpty)
      .map(evidence => ticket(previous.check, value.command, previous.failures :+ evidence.artifact))
      .filter(following => Try(entry.own(following.workspace.attempt)).isSuccess)
    value.status = next.fold(settled)(starting)
    next.foreach(following => value.ticket = following)
    next
  }

  private def execute(value: Execution): Task[Unit] = run(value, value.ticket).ensuring(value.done.succeed(()).unit)

  private def run(value: Execution, ticket: DeclaredCheckTicket): Task[Unit] = {
    val directory = DeclaredCheckPublication.directory(entry.directory.resolve("checks").resolve(ticket.check.name), ticket.failures.size + 1)
    val operation = for {
      publication <- ZIO.attemptBlocking {
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve("ticket.json"), HostFiles.encode(DeclaredCheckTicket_JsonCodec, ticket), 32768)
        new DeclaredCheckPublication(directory, ticket, entry.ticket.assignment.id, config.directory.resolve("payload"))
      }
      _ <- ZIO.attempt { entry.check(); require(!stopping, "Reviewer ended before its check started") }
      _ <- jobs.start(config.owner, ticket.workspace, value.command)
      _ <- if (stopping) cancel(value) else ZIO.unit
      _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Running)))
      record <- jobs.await(config.owner, ticket.workspace.attempt)
      _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Publishing)))
      receipt <- ZIO.attemptBlocking { publication.seal(Some(record), None); publication.finish(api) }
    } yield advance(value, receipt.status)
    operation.catchAll { failure =>
      val reason = DispatchProjection.concise("Declared check execution/publication failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))
      for {
        _ <- ZIO.succeed(entry.requestStop(reason))
        stopped <- ZIO.foreach(entry.ownedJobs.toList) { id => jobs.cancel(config.owner, id).unit
          .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.either }
        settled <- jobs.await(config.owner, ticket.workspace.attempt).unit
          .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.either
        errors = (stopped :+ settled).collect { case Left(error) => error.getClass.getSimpleName }.distinct
        diagnostic = if (errors.isEmpty) reason else DispatchProjection.concise(reason + "; cleanup uncertain: " + errors.mkString(", "))
        _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Unknown, evidence = None, blocker = Some(diagnostic))))
      } yield None
    // A check's evidence is its retained output; its tree is never read. A failed removal is retried by the next host startup.
    }.ensuring(jobs.release(config.owner, ticket.workspace.attempt).ignore).flatMap(_.fold(ZIO.unit)(run(value, _)))
  }

  def request(name: String, waitMillis: Int): Task[DeclaredCheckStatus] = ZIO.uninterruptibleMask { restore => for {
    _ <- ZIO.attempt(require(waitMillis >= 0 && waitMillis <= MaxWaitMillis, "Check wait must be 0–20000 ms"))
    done <- Promise.make[Nothing, Unit]
    registered <- ZIO.attempt(register(name, done))
    (value, fresh) = registered
    _ <- if (fresh) execute(value).forkDaemon.unit else ZIO.unit
    _ <- if (waitMillis == 0) ZIO.unit else restore(value.done.await.timeout(zio.Duration.fromMillis(waitMillis))).unit
  } yield status(value) }

  def close: Task[ClosedReviewerChecks] = for {
    frozen <- ZIO.succeed(synchronized {
      if (closed.isEmpty) closed = Some(executions.toList.sortBy(_._1).map { case (_, value) => (value, value.status) })
      closed.get
    })
    cancelled <- ZIO.foreach(frozen.filter(_._2.phase != DeclaredCheckPhase.Completed)) { case (value, _) => cancel(value).either }
    _ <- ZIO.succeed {
      val errors = cancelled.collect { case Left(error) => error.getClass.getSimpleName }.distinct
      if (errors.nonEmpty) {
        synchronized { cleanupUncertain = true }
        entry.requestStop("Declared check cancellation is unconfirmed: " + errors.mkString(", "))
      }
    }
    _ <- ZIO.foreachDiscard(frozen)(value => value._1.done.await)
    current = frozen.map(value => status(value._1))
  } yield ClosedReviewerChecks(current.flatMap(_.evidence), frozen.exists(_._2.phase != DeclaredCheckPhase.Completed),
    synchronized(cleanupUncertain) || current.exists(value => value.phase != DeclaredCheckPhase.Completed || value.evidence.exists(_.state == ValidationState.Unknown)))
}
