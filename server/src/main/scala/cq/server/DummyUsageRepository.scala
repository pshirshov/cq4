package cq.server

import cq.api.*
import cq.core.*
import distage.Lifecycle
import zio.{IO, Ref, Task, ZIO}

final class DummyUsageResource(ledger: LedgerRepository[IO]) extends Lifecycle.LiftF[Task, UsageRepository[IO]](
  Ref.Synchronized.make(Map.empty[ProjectId, DummyUsageState]).map { projects =>
    new UsageRepository[IO] {
      override def transact[A](project: ProjectId)(operation: UsageTransaction => A): IO[Throwable, A] =
        ledger.transact(project)(_ => ()).flatMap { _ =>
          projects.modifyZIO { current => ZIO.attempt {
            val tx = new DummyUsageTransaction(current.getOrElse(project, DummyUsageState.empty))
            val result = operation(tx)
            (result, current.updated(project, tx.result))
          } }
        }
      override def read[A](project: ProjectId)(operation: UsageReader => A): IO[Throwable, A] =
        ledger.transact(project)(_ => ()).flatMap(_ => projects.get.flatMap(current => ZIO.attempt(operation(new DummyUsageTransaction(current.getOrElse(project, DummyUsageState.empty))))))
    }
  }
)

private final case class DummyUsageState(
  cursor: Long,
  costs: Map[(MeterKey, MoneyKey), CostProjection],
  assignments: Map[AssignmentId, Assignment],
  attempts: Map[AttemptId, Attempt],
  meters: Map[MeterKey, (UsageMeter, MeterProjection)],
  observations: Map[ObservationId, RecordedUsage],
  heads: Map[(MeterKey, Long), ObservationId],
  outcomes: Map[RequestId, RecordedOutcome],
)

private object DummyUsageState {
  def empty: DummyUsageState = DummyUsageState(0, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)
}

private final class DummyUsageTransaction(initial: DummyUsageState) extends UsageTransaction {
  private var state = initial
  def result: DummyUsageState = state
  private def tick(): Long = { state = state.copy(cursor = Math.addExact(state.cursor, 1)); state.cursor }
  override def cost(key: MeterKey, group: MoneyKey): Option[CostProjection] = state.costs.get((key, group))
  override def putCost(key: MeterKey, group: MoneyKey, value: Option[CostProjection]): Unit = {
    val pair = (key, group)
    state = state.copy(costs = value.fold(state.costs - pair)(v => state.costs.updated(pair, v)))
  }
  override def costs(filter: UsageFilter, after: Option[CostGroup], limit: Int): ReadPage[CostTotal] = {
    val groups = state.costs.iterator.filter { case ((meter, _), _) => matches(filter, state.attempts(meter.attempt)) }
      .map { case ((meter, group), value) =>
        val attribution = state.assignments(state.attempts(meter.attempt).assignment).attribution
        CostGroup(attribution, group.currency, group.basis, group.pricingVersion) -> value
      }.toList.groupMapReduce(_._1)(_._2)((a, b) => CostProjection(UsageMath.addAmount(a.amount, b.amount, 1), Math.addExact(a.measurements, b.measurements)))
    val rows = groups.iterator.filter { case (group, _) => after.forall(UsageCosts.compare(group, _) > 0) }.toList
      .sortWith((a, b) => UsageCosts.compare(a._1, b._1) < 0).iterator.map { case (group, value) => CostTotal(group, UsageMath.decimal(value.amount), value.measurements) }
    ReadPage.select(rows, limit, CostTotal_JsonCodec)
  }
  override def cursor: Long = state.cursor
  override def assignment(id: AssignmentId): Option[Assignment] = state.assignments.get(id)
  override def attempt(id: AttemptId): Option[Attempt] = state.attempts.get(id)
  override def meter(key: MeterKey): Option[(UsageMeter, MeterProjection)] = state.meters.get(key)
  override def observation(id: ObservationId): Option[RecordedUsage] = state.observations.get(id)
  override def sample(key: MeterKey, position: Long): Option[RecordedUsage] = state.heads.get((key, position)).flatMap(state.observations.get)
  override def outcome(request: RequestId): Option[AttemptOutcome] = state.outcomes.get(request).map(_.value)
  override def putAssignment(value: Assignment, actor: Actor, receivedAt: Long): Unit = { state = state.copy(assignments = state.assignments.updated(value.id, value)); tick(); () }
  override def putAttempt(value: Attempt, actor: Actor, receivedAt: Long): Unit = { state = state.copy(attempts = state.attempts.updated(value.id, value)); tick(); () }
  override def putMeter(value: UsageMeter, projection: MeterProjection, actor: Actor, receivedAt: Long): Unit = {
    state = state.copy(meters = state.meters.updated(MeterKey(value.attempt, value.key), (value, projection)))
    tick()
    ()
  }
  override def projectMeter(key: MeterKey, projection: MeterProjection): Unit = {
    val existing = state.meters(key)
    state = state.copy(meters = state.meters.updated(key, (existing._1, projection)))
  }
  override def append(value: UsageUpload, normalized: TokenCounts, actor: Actor): RecordedUsage = {
    val recorded = RecordedUsage(value, normalized, actor, tick())
    require(!state.observations.contains(value.observation.id), "Duplicate observation identity")
    state = state.copy(observations = state.observations.updated(value.observation.id, recorded))
    recorded
  }
  override def head(value: RecordedUsage): Unit = {
    val key = (MeterKey(value.upload.observation.attempt, value.upload.meter), value.upload.observation.position)
    state = state.copy(heads = state.heads.updated(key, value.upload.observation.id))
  }
  override def putOutcome(value: AttemptOutcome, actor: Actor, receivedAt: Long): Unit = {
    val recorded = RecordedOutcome(value, actor, receivedAt, tick())
    state = state.copy(outcomes = state.outcomes.updated(value.request, recorded))
  }
  override def latestOutcome(attempt: AttemptId): Option[RecordedOutcome] = state.outcomes.valuesIterator.filter(_.value.attempt == attempt).maxByOption(_.sequence)
  override def outcomes(attempt: AttemptId, after: Long, limit: Int): ReadPage[RecordedOutcome] =
    ReadPage.select(state.outcomes.valuesIterator.filter(o => o.value.attempt == attempt && o.sequence > after).toList.sortBy(_.sequence).iterator, limit, RecordedOutcome_JsonCodec)
  override def attempts(filter: UsageFilter, after: Option[AttemptId], limit: Int): ReadPage[AttemptView] = {
    val values = state.attempts.valuesIterator.filter(a => matches(filter, a) && after.forall(p => a.id.value.toString > p.value.toString))
      .toList.sortBy(_.id.value.toString).iterator.map(a => AttemptView(state.assignments(a.assignment), a, latestOutcome(a.id)))
    ReadPage.select(values, limit, AttemptView_JsonCodec)
  }
  override def coverage(filter: UsageFilter): AttemptCoverage = {
    val outcomes = state.attempts.valuesIterator.filter(matches(filter, _)).map(a => latestOutcome(a.id)).toList
    AttemptCoverage(outcomes.count(_.isEmpty).toLong, outcomes.count(_.exists(_.value.state == AttemptState.Unknown)).toLong, outcomes.count(_.exists(_.value.gaps.nonEmpty)).toLong)
  }
  private def matches(filter: UsageFilter, attempt: Attempt): Boolean = {
    val assignment = state.assignments(attempt.assignment)
    filter match {
      case _: UsageFilter.ProjectAll => true
      case UsageFilter.TaskOnly(item) => assignment.members.contains(item)
      case UsageFilter.CohortOnly(id) => assignment.cohort.contains(id)
      case UsageFilter.SessionOnly(id) => attempt.session == id
      case UsageFilter.EvaluationOnly(run, scenario) => assignment.evaluation.exists(e => e.run == run && scenario.forall(_ == e.scenario))
    }
  }
  private def sortKey(key: MeterKey): (String, String) = (key.attempt.value.toString, key.meter)
  override def meters(filter: UsageFilter, after: Option[MeterKey], limit: Int): List[MeterView] =
    state.meters.toList.filter { case (key, _) => matches(filter, state.attempts(key.attempt)) && after.forall(a => Ordering[(String, String)].gt(sortKey(key), sortKey(a))) }
      .sortBy(entry => sortKey(entry._1)).take(limit).map { case (key, (meter, projection)) =>
        val attempt = state.attempts(key.attempt)
        MeterView(state.assignments(attempt.assignment), attempt, meter, projection)
      }
  override def attemptsWithoutMeters(filter: UsageFilter): Long = state.attempts.valuesIterator.count(a => matches(filter, a) && !state.meters.keysIterator.exists(_.attempt == a.id)).toLong
  override def audit(filter: UsageFilter, after: Long, limit: Int): ReadPage[RecordedUsage] =
    ReadPage.select(state.observations.valuesIterator.filter(r => r.sequence > after && matches(filter, state.attempts(r.upload.observation.attempt))).toList.sortBy(_.sequence).iterator, limit, RecordedUsage_JsonCodec)
}
