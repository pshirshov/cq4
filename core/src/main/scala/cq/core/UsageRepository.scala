package cq.core

import cq.api.*

final case class MeterKey(attempt: AttemptId, meter: String)
final case class PhaseCost(phase: UsagePhase, total: CostTotal)
final case class SpanTally(phase: UsagePhase, spans: Long, wallMillis: Long)
final case class UsageCursors(usage: Long, attempts: Long)
final case class WorkAttempts(running: Map[ItemId, WorkAttempt], events: Long)

trait UsageRepository[F[_, _]] {
  def transact[A](project: ProjectId)(operation: UsageTransaction => A): F[Throwable, A]
  def read[A](project: ProjectId)(operation: UsageReader => A): F[Throwable, A]
}

trait UsageReader {
  def costs(filter: UsageFilter, after: Option[CostGroup], limit: Int): ReadPage[CostTotal]
  /** At most `limit` cost groups, ordered by phase name and then as `costs` orders its groups. */
  def phaseCosts(filter: UsageFilter, limit: Int): List[PhaseCost]
  def cost(key: MeterKey, group: MoneyKey): Option[CostProjection]
  def cursor: Long
  /** Attempts started plus attempts with a recorded outcome: it grows when an attempt starts and when its first outcome is recorded. */
  def attemptEvents: Long
  /** For each item, the attempts of its session without a recorded outcome whose assignment covers the item; items without one are absent. */
  def running(claimed: Map[ItemId, SessionId]): Map[ItemId, List[Attempt]]
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
  def span(id: RequestId): Option[PhaseSpan]
  /** One tally per phase that has a matching span. */
  def spans(filter: UsageFilter): List[SpanTally]
}

trait UsageTransaction extends UsageReader {
  def putCost(key: MeterKey, group: MoneyKey, value: Option[CostProjection]): Unit
  def putAssignment(value: Assignment, actor: Actor, receivedAt: Long): Unit
  def putAttempt(value: Attempt, actor: Actor, receivedAt: Long): Unit
  def putMeter(value: UsageMeter, projection: MeterProjection, actor: Actor, receivedAt: Long): Unit
  def projectMeter(key: MeterKey, projection: MeterProjection): Unit
  def append(value: UsageUpload, normalized: TokenCounts, actor: Actor): RecordedUsage
  def head(value: RecordedUsage): Unit
  def putOutcome(value: AttemptOutcome, actor: Actor, receivedAt: Long): Unit
  def putSpan(value: PhaseSpan, actor: Actor, receivedAt: Long): Unit
}
