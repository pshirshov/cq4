package cq.core

import cq.api.*

final case class MeterKey(attempt: AttemptId, meter: String)

trait UsageRepository[F[_, _]] {
  def transact[A](project: ProjectId)(operation: UsageTransaction => A): F[Throwable, A]
  def read[A](project: ProjectId)(operation: UsageReader => A): F[Throwable, A]
}

trait UsageReader {
  def cursor: Long
  def assignment(id: AssignmentId): Option[Assignment]
  def attempt(id: AttemptId): Option[Attempt]
  def meter(key: MeterKey): Option[(UsageMeter, MeterProjection)]
  def observation(id: ObservationId): Option[RecordedUsage]
  def sample(key: MeterKey, position: Long): Option[RecordedUsage]
  def outcome(request: RequestId): Option[AttemptOutcome]
  def latestOutcome(attempt: AttemptId): Option[RecordedOutcome]
  def outcomes(attempt: AttemptId, after: Long, limit: Int): ReadPage[RecordedOutcome]
  def attempts(filter: UsageFilter, after: Option[AttemptId], limit: Int): ReadPage[AttemptView]
  def coverage(filter: UsageFilter): AttemptCoverage
  def meters(filter: UsageFilter, after: Option[MeterKey], limit: Int): List[MeterView]
  def attemptsWithoutMeters(filter: UsageFilter): Long
  def audit(filter: UsageFilter, after: Long, limit: Int): ReadPage[RecordedUsage]
}

trait UsageTransaction extends UsageReader {
  def putAssignment(value: Assignment, actor: Actor, receivedAt: Long): Unit
  def putAttempt(value: Attempt, actor: Actor, receivedAt: Long): Unit
  def putMeter(value: UsageMeter, projection: MeterProjection, actor: Actor, receivedAt: Long): Unit
  def projectMeter(key: MeterKey, projection: MeterProjection): Unit
  def append(value: UsageUpload, normalized: TokenCounts, actor: Actor): RecordedUsage
  def head(value: RecordedUsage): Unit
  def putOutcome(value: AttemptOutcome, actor: Actor, receivedAt: Long): Unit
}
