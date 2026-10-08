package cq.core

import cq.api.*

final case class MeterKey(attempt: AttemptId, meter: String)
final case class PhaseCost(phase: UsagePhase, total: CostTotal)
final case class SpanTally(phase: UsagePhase, spans: Long, wallMillis: Long)
/** The runs of one check (absent for spans stored without a name) that ended in one state. */
final case class CheckTally(check: Option[String], state: AttemptState, runs: Long, wallMillis: Long)
final case class UsageCursors(usage: Long, attempts: Long)
object UsageCursors {
  /** The answer to a page of attempts asked to continue after an attempt the project does not hold. */
  val UnknownAttemptKey = "The attempt a page continues after is not an attempt of this project"
}
final case class WorkAttempts(running: Map[ItemId, WorkAttempt], events: Long)

/** The governing attempt of an attached session is the operator's own harness: CQ's host launches no process for it and nothing
  * observes its end. Without an outcome it is open (started, no outcome delivered), which the host's final delivery or
  * `cq job upload` closes; every other attempt is a process a host launched and observes, and without an outcome it is running. */
object AttemptObservation {
  val AttachedGovernorCollector = "CQ attached session; outer usage unavailable"
  def observed(attempt: Attempt): Boolean = !(attempt.parent.isEmpty && attempt.role == Role.Governor && attempt.collector == AttachedGovernorCollector)
}

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
  /** At most `limit` tallies of Check spans, one per check name and state, ordered by name (absent first, UTF-8 byte order) and then state name. */
  def checks(filter: UsageFilter, limit: Int): List[CheckTally]
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
