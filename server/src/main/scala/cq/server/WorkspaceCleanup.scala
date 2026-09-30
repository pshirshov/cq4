package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import cq.host.JobSupervisor
import logstage.IzLogger
import zio.{IO, Task, ZIO}

final case class WorkspaceCleanupReport(removed: List[AttemptId], retained: Map[AttemptId, String])

object WorkspaceCleanup {
  /** Removes the workspace of every settled job whose record is open and which no pending integration references; everything else is retained with its reason. */
  def sweep(owner: Scope, records: List[JobRecord], pending: Set[AttemptId], workspaces: WorkspaceService[IO]): Task[WorkspaceCleanupReport] =
    ZIO.foldLeft(records.sortBy(_.workspace.attempt.value.toString))(WorkspaceCleanupReport(Nil, Map.empty)) { (report, record) =>
      val attempt = record.workspace.attempt
      def retain(reason: String): WorkspaceCleanupReport = report.copy(retained = report.retained.updated(attempt, reason))
      workspaces.get(owner, attempt).either.flatMap {
        case Left(DomainFailure(_: Fault.Missing)) => ZIO.succeed(report)
        case Left(error) => ZIO.succeed(retain("Workspace record unreadable: " + error.getClass.getSimpleName))
        case Right(workspace) => workspace.admission match {
          case WorkspaceAdmission.Removed => ZIO.succeed(report)
          case WorkspaceAdmission.Quarantined => ZIO.succeed(retain("Quarantined: " + workspace.quarantineReason.getOrElse("no reason recorded")))
          case WorkspaceAdmission.Open if record.phase != JobPhase.Settled => ZIO.succeed(retain("Job not settled: " + record.phase))
          case WorkspaceAdmission.Open if pending(attempt) => ZIO.succeed(retain("Referenced by a pending integration"))
          case WorkspaceAdmission.Open => workspaces.remove(owner, attempt).either.map {
            case Right(removed) if removed.admission == WorkspaceAdmission.Removed => report.copy(removed = report.removed :+ attempt)
            case Right(refused) => retain(refused.quarantineReason.getOrElse("Removal refused"))
            case Left(error) => retain("Removal failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
          }
        }
      }
    }
}

final class WorkspaceCleanup(config: SupervisorConfig, jobs: JobSupervisor, workspaces: WorkspaceService[IO],
  integrations: IntegrationController, logger: IzLogger) {
  /** Governing-session shutdown housekeeping: runs after every controller has settled its work and never fails the session. */
  def run: Task[Unit] = (for {
    records <- jobs.records(config.owner)
    report <- WorkspaceCleanup.sweep(config.owner, records, integrations.pendingJobs, workspaces)
    _ <- ZIO.succeed {
      val removed = report.removed.size
      val retained = report.retained.size
      logger.info(s"Workspace cleanup removed $removed worktrees and retained $retained under ${config.directory.resolve("workspaces")}")
      report.retained.foreach { case (attempt, reason) => logger.info(s"Retained workspace ${attempt.value}: $reason") }
    }
  } yield ()).catchAll(error => ZIO.succeed(logger.warn(s"Workspace cleanup did not complete: ${error.getMessage}")))

  def prune: Task[Unit] = workspaces.prune(config.owner, config.run.repository)
    .flatMap(count => ZIO.succeed(logger.info(s"Pruned $count stale worktree registrations from ${config.run.repository}")))
    .catchAll(error => ZIO.succeed(logger.warn(s"Worktree pruning did not complete: ${error.getMessage}")))
}
