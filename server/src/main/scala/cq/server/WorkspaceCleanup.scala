package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import cq.host.{HostFiles, JobSupervisor}
import java.nio.file.Files
import java.time.{Clock, Duration}
import logstage.IzLogger
import zio.{IO, Task, ZIO}

final case class WorkspaceCleanupReport(removed: List[AttemptId], quarantined: List[RetainedWorkspace], retained: List[RetainedWorkspace], deadlineExceeded: Boolean)

object WorkspaceCleanup {
  /** Upper bound of one session's shutdown sweep; the supervisor watchdog extends its halt deadline by at most this much, one drain window per completed Git operation. */
  val Budget: Duration = Duration.ofMinutes(5)
  val DeadlineExceeded = "Cleanup deadline exceeded"
  private val MaxReceiptBytes = 65536

  /** Removes the workspace of every settled job whose record is open and which no pending integration references, until `deadline` (epoch millis), reporting each completed Git operation to `progress`; everything else is retained with its reason. */
  def sweep(owner: Scope, records: List[JobRecord], pending: Set[AttemptId], workspaces: WorkspaceService[IO], clock: Clock, deadline: Long,
    progress: () => Unit): Task[WorkspaceCleanupReport] =
    ZIO.foldLeft(records.sortBy(_.workspace.attempt.value.toString))(WorkspaceCleanupReport(Nil, Nil, Nil, false)) { (report, record) =>
      val attempt = record.workspace.attempt
      def retain(reason: String): WorkspaceCleanupReport = report.copy(retained = report.retained :+ RetainedWorkspace(attempt, reason))
      def quarantined(reason: String): WorkspaceCleanupReport = report.copy(quarantined = report.quarantined :+ RetainedWorkspace(attempt, reason))
      if (report.deadlineExceeded || clock.millis() >= deadline) ZIO.succeed(retain(DeadlineExceeded).copy(deadlineExceeded = true))
      else workspaces.get(owner, attempt).either.flatMap {
        case Left(DomainFailure(_: Fault.Missing)) => ZIO.succeed(report)
        case Left(error) => ZIO.succeed(retain("Workspace record unreadable: " + error.getClass.getSimpleName))
        case Right(workspace) => workspace.admission match {
          case WorkspaceAdmission.Removed => ZIO.succeed(report)
          case WorkspaceAdmission.Quarantined => ZIO.succeed(quarantined(workspace.quarantineReason.getOrElse("no reason recorded")))
          case WorkspaceAdmission.Open if record.phase != JobPhase.Settled => ZIO.succeed(retain("Job not settled: " + record.phase))
          case WorkspaceAdmission.Open if pending(attempt) => ZIO.succeed(retain("Referenced by a pending integration"))
          case WorkspaceAdmission.Open => workspaces.remove(owner, attempt).either.map {
            case Right(removed) if removed.admission == WorkspaceAdmission.Removed => report.copy(removed = report.removed :+ attempt)
            case Right(refused) => quarantined(refused.quarantineReason.getOrElse("Removal refused"))
            case Left(error) => retain("Removal failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
          }.tap(_ => ZIO.succeed(progress()))
        }
      }
    }
}

final class WorkspaceCleanup(config: SupervisorConfig, jobs: JobSupervisor, workspaces: WorkspaceService[IO],
  integrations: IntegrationController, watchdog: SupervisorWatchdog, clock: Clock, logger: IzLogger) {
  import WorkspaceCleanup.*
  /** Governing-session shutdown housekeeping: runs after every controller has settled its work, records `workspaces/cleanup.json` and never fails the session. */
  def run: Task[Unit] = (for {
    startedAt <- ZIO.succeed(clock.millis())
    records <- jobs.records(config.owner)
    report <- sweep(config.owner, records, integrations.pendingJobs, workspaces, clock, startedAt + Budget.toMillis, () => watchdog.progress())
    _ <- ZIO.attemptBlocking {
      val receipt = WorkspaceCleanupReceipt(config.owner.actor.session, startedAt, clock.millis(), report.deadlineExceeded,
        report.removed, report.quarantined, report.retained)
      val directory = config.directory.resolve("workspaces")
      Files.createDirectories(directory)
      HostFiles.immutable(directory.resolve("cleanup.json"), HostFiles.encode(WorkspaceCleanupReceipt_JsonCodec, receipt), MaxReceiptBytes)
      val removed = report.removed.size
      val quarantined = report.quarantined.size
      val retained = report.retained.size
      logger.info(s"Workspace cleanup removed $removed worktrees, left $quarantined quarantined and retained $retained under $directory")
      (report.quarantined ++ report.retained).foreach(value => logger.info(s"Retained workspace ${value.attempt.value}: ${value.reason}"))
    }
  } yield ()).catchAll(error => ZIO.succeed(logger.warn(s"Workspace cleanup did not complete: ${error.getMessage}")))

  def prune: Task[Unit] = workspaces.prune(config.owner, config.run.repository)
    .flatMap(count => ZIO.succeed(logger.info(s"Pruned $count stale worktree registrations from ${config.run.repository}")))
    .catchAll(error => ZIO.succeed(logger.warn(s"Worktree pruning did not complete: ${error.getMessage}")))
}
