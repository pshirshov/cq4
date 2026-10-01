package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import java.time.Duration
import zio.{Promise, Task, ZIO}

private[server] final case class ClosedReviewerChecks(evidence: List[ValidationEvidence], pending: Boolean, uncertain: Boolean)

private[server] final class ReviewerChecks(entry: DispatchExecution, candidate: GitCommit, config: SupervisorConfig,
  api: ServerApi, jobs: JobSupervisor) {
  private val MaxWaitMillis = 20000
  private final class Execution(val ticket: DeclaredCheckTicket, val command: JobCommand, val done: Promise[Nothing, Unit]) {
    var status = DeclaredCheckStatus(ticket.check.name, ticket.workspace.attempt, DeclaredCheckPhase.Starting, None, None)
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
        val id = AttemptId(NativeArtifacts.id(entry.ticket.attempt.id, "declared-check-job-" + name).value)
        val spec = WorkspaceSpec(config.project.project, config.run.attempt.session, id, config.run.repository, candidate)
        val execution = new Execution(DeclaredCheckTicket(entry.ticket.attempt.id, declaration, spec, command.fingerprint), command, done)
        entry.own(id)
        executions = executions.updated(name, execution)
        (execution, true)
    }
  }
  private def status(value: Execution): DeclaredCheckStatus = synchronized(value.status)
  private def update(value: Execution)(change: DeclaredCheckStatus => DeclaredCheckStatus): Unit = synchronized { value.status = change(value.status) }
  private def stopping: Boolean = synchronized(closed.nonEmpty) || entry.stopReason.nonEmpty
  private def cancel(value: Execution): Task[Unit] = jobs.cancel(config.owner, value.ticket.workspace.attempt).unit
    .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }

  private def execute(value: Execution): Task[Unit] = {
    val directory = entry.directory.resolve("checks").resolve(value.ticket.check.name)
    val operation = for {
      publication <- ZIO.attemptBlocking {
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve("ticket.json"), HostFiles.encode(DeclaredCheckTicket_JsonCodec, value.ticket), 32768)
        new DeclaredCheckPublication(directory, value.ticket, config.directory.resolve("payload"))
      }
      _ <- ZIO.attempt { entry.check(); require(!stopping, "Reviewer ended before its check started") }
      _ <- jobs.start(config.owner, value.ticket.workspace, value.command)
      _ <- if (stopping) cancel(value) else ZIO.unit
      _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Running)))
      record <- jobs.await(config.owner, value.ticket.workspace.attempt)
      _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Publishing)))
      receipt <- ZIO.attemptBlocking { publication.seal(Some(record), None); publication.finish(api) }
      _ <- ZIO.succeed(update(value)(_ => receipt.status))
    } yield ()
    operation.catchAll { failure =>
      val reason = DispatchProjection.concise("Declared check execution/publication failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))
      for {
        _ <- ZIO.succeed(entry.requestStop(reason))
        stopped <- ZIO.foreach(entry.ownedJobs.toList) { id => jobs.cancel(config.owner, id).unit
          .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.either }
        settled <- jobs.await(config.owner, value.ticket.workspace.attempt).unit
          .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.either
        errors = (stopped :+ settled).collect { case Left(error) => error.getClass.getSimpleName }.distinct
        diagnostic = if (errors.isEmpty) reason else DispatchProjection.concise(reason + "; cleanup uncertain: " + errors.mkString(", "))
        _ <- ZIO.succeed(update(value)(_.copy(phase = DeclaredCheckPhase.Unknown, evidence = None, blocker = Some(diagnostic))))
      } yield ()
    // A check's evidence is its retained output; its tree is never read. A failed removal is retried by the next host startup.
    }.ensuring(jobs.release(config.owner, value.ticket.workspace.attempt).ignore *> value.done.succeed(()).unit)
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
