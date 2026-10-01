package cq.core

import cq.api.*
import izumi.functional.bio.{Error2, F, *}
import scala.util.Try

trait WorkspaceRepository[F[_, _]] {
  def prepare(spec: WorkspaceSpec): F[Throwable, WorkspaceRecord]
  def get(attempt: AttemptId): F[Throwable, Option[WorkspaceRecord]]
  def quarantine(attempt: AttemptId, reason: String): F[Throwable, WorkspaceRecord]
  /** Removes an open workspace whose Git identity still matches its record, finishing a removal that was cut; a mismatch quarantines the record instead. */
  def remove(attempt: AttemptId): F[Throwable, WorkspaceRecord]
  /** Drops the source repository's worktree registrations whose directories no longer exist; returns how many. */
  def prune(repository: String): F[Throwable, Int]
}

trait WorkspaceService[F[_, _]] {
  def prepare(scope: Scope, spec: WorkspaceSpec): F[Throwable, WorkspaceRecord]
  def get(scope: Scope, attempt: AttemptId): F[Throwable, WorkspaceRecord]
  def quarantine(scope: Scope, attempt: AttemptId, reason: String): F[Throwable, WorkspaceRecord]
  def remove(scope: Scope, attempt: AttemptId): F[Throwable, WorkspaceRecord]
  def prune(scope: Scope, repository: String): F[Throwable, Int]
}

object WorkspaceService {
  final class Impl[F[+_, +_]: Error2](repository: WorkspaceRepository[F]) extends WorkspaceService[F] {
    private def authorized(scope: Scope, spec: WorkspaceSpec): Unit = {
      if (!Set(Role.Governor, Role.Human).contains(scope.actor.role) || scope.project != spec.project || scope.actor.session != spec.owner)
        throw DomainFailure(Fault.Denied("Workspace requires its governing project and session"))
    }
    override def prepare(scope: Scope, spec: WorkspaceSpec): F[Throwable, WorkspaceRecord] = for {
      _ <- F.fromEither(Try {
        authorized(scope, spec)
        LedgerPolicy.invalid(spec.repository.nonEmpty && spec.repository.length <= LedgerPolicy.MaxLocation && !spec.repository.contains('\u0000'), "Invalid workspace repository")
        LedgerPolicy.invalid(java.nio.file.Path.of(spec.repository).isAbsolute, "Workspace repository must be absolute")
        LedgerPolicy.invalid(spec.base.value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Workspace base must be a full Git commit object ID")
      }.toEither)
      value <- repository.prepare(spec)
    } yield value

    override def get(scope: Scope, attempt: AttemptId): F[Throwable, WorkspaceRecord] = for {
      stored <- repository.get(attempt)
      value <- F.fromEither(Try {
        val record = stored.getOrElse(throw DomainFailure(Fault.Missing("Workspace not registered")))
        authorized(scope, record.spec)
        record
      }.toEither)
    } yield value

    override def quarantine(scope: Scope, attempt: AttemptId, reason: String): F[Throwable, WorkspaceRecord] = for {
      _ <- get(scope, attempt)
      _ <- F.fromEither(Try(LedgerPolicy.invalid(reason.trim.nonEmpty && reason.length <= LedgerPolicy.MaxTitle, "Quarantine reason required")).toEither)
      record <- repository.quarantine(attempt, reason)
    } yield record

    override def remove(scope: Scope, attempt: AttemptId): F[Throwable, WorkspaceRecord] = for {
      _ <- get(scope, attempt)
      record <- repository.remove(attempt)
    } yield record

    override def prune(scope: Scope, repository: String): F[Throwable, Int] = for {
      _ <- F.fromEither(Try {
        if (!Set(Role.Governor, Role.Human).contains(scope.actor.role)) throw DomainFailure(Fault.Denied("Worktree pruning requires a governing role"))
        LedgerPolicy.invalid(java.nio.file.Path.of(repository).isAbsolute, "Workspace repository must be absolute")
      }.toEither)
      count <- this.repository.prune(repository)
    } yield count
  }
}
