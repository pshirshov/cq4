package cq.core

import cq.api.*
import izumi.functional.bio.{Error2, F, *}
import java.time.Clock
import scala.util.Try

trait UsageService[F[_, _]] {
  def assign(scope: Scope, value: Assignment): F[Throwable, Assignment]
  def start(scope: Scope, value: Attempt): F[Throwable, Attempt]
  def meter(scope: Scope, value: UsageMeter): F[Throwable, UsageMeter]
  def ingest(scope: Scope, value: UsageUpload): F[Throwable, UsageReceipt]
  def finish(scope: Scope, value: AttemptOutcome): F[Throwable, AttemptOutcome]
  def summary(scope: Scope, filter: UsageFilter): F[Throwable, UsageReport]
  def audit(scope: Scope, filter: UsageFilter, after: Long, limit: Int): F[Throwable, UsagePage]
}

object UsageService {
  final class Impl[F[+_, +_]: Error2](repository: UsageRepository[F], ledger: LedgerService[F], clock: Clock) extends UsageService[F] {
    import LedgerPolicy.invalid
    private val MaxIdentity = 300
    private val MaxGaps = 32
    private val ReadBatch = 200

    private def host(scope: Scope): Unit =
      if (scope.actor.role != Role.Collector && scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Usage ingestion requires a host collector or human credential"))

    private def text(value: String, field: String): Unit = invalid(value.trim.nonEmpty && value.length <= MaxIdentity, s"Invalid $field")
    private def gaps(values: List[String]): Unit = { invalid(values.size <= MaxGaps, "Too many usage gaps"); values.foreach(text(_, "usage gap")) }
    private def found[A](value: Option[A], description: String): A = value.getOrElse(throw DomainFailure(Fault.Missing(description)))
    private def same[A](previous: Option[A], current: A): Boolean = previous match {
      case Some(value) =>
        if (value != current) throw DomainFailure(Fault.Conflict("Audit identity reused with different immutable content"))
        true
      case None => false
    }

    override def assign(scope: Scope, value: Assignment): F[Throwable, Assignment] = for {
      _ <- F.fromEither(Try {
        host(scope)
        invalid(value.project == scope.project && value.members.forall(_.project == scope.project), "Assignment must stay within its authenticated project")
        invalid(value.members.size <= LedgerPolicy.MaxBatch, "Assignment membership exceeds the bound")
        value.attribution match {
          case Attribution.Direct => invalid(value.members.size == 1 && value.cohort.isEmpty, "Direct work requires exactly one item and no cohort")
          case Attribution.Shared => invalid(value.members.size >= 2, "Shared work requires at least two members")
          case Attribution.Unattributed => invalid(value.members.isEmpty && value.cohort.isEmpty, "Unattributed overhead cannot claim item membership")
        }
        value.evaluation.foreach { e => text(e.run, "evaluation run"); text(e.scenario, "evaluation scenario") }
      }.toEither)
      _ <- ledger.search(scope, ItemFilter(None, ArchiveFilter.All), None, 1)
      _ <- value.members.foldLeft(F.pure(())) { (previous, id) => previous.flatMap(_ => ledger.get(scope, id).map(_ => ())) }
      assigned <- repository.transact(scope.project) { tx =>
        if (!same(tx.assignment(value.id), value)) tx.putAssignment(value, scope.actor, clock.millis())
        value
      }
    } yield assigned

    override def start(scope: Scope, value: Attempt): F[Throwable, Attempt] = repository.transact(scope.project) { tx =>
      host(scope)
      found(tx.assignment(value.assignment), "Assignment not registered")
      value.parent.foreach(id => found(tx.attempt(id), "Parent attempt not registered"))
      invalid(!value.parent.contains(value.id), "An attempt cannot parent itself")
      List(value.provider, value.model, value.collector).foreach(text(_, "attempt provider/model/collector"))
      invalid(value.startedAt >= 0, "Invalid attempt start time")
      if (!same(tx.attempt(value.id), value)) tx.putAttempt(value, scope.actor, clock.millis())
      value
    }

    override def meter(scope: Scope, value: UsageMeter): F[Throwable, UsageMeter] = repository.transact(scope.project) { tx =>
      host(scope)
      found(tx.attempt(value.attempt), "Attempt not registered")
      text(value.key, "accounting meter")
      UsageMath.validate(value.baseline)
      UsageMath.validate(value.baselineCost)
      if (value.scope == CounterScope.Increment)
        invalid(value.baseline == UsageMath.zeroCounts && value.baselineCost == UsageMath.unknownMoney, "Increment meters use zero token baselines and no cost baseline")
      if (!same(tx.meter(MeterKey(value.attempt, value.key)).map(_._1), value)) tx.putMeter(value, UsageMath.emptyProjection, scope.actor, clock.millis())
      value
    }

    private def equivalent(previous: UsageUpload, incoming: UsageUpload): Boolean =
      previous.copy(observation = previous.observation.copy(id = incoming.observation.id, receivedAt = incoming.observation.receivedAt)) == incoming

    override def ingest(scope: Scope, value: UsageUpload): F[Throwable, UsageReceipt] = repository.transact(scope.project) { tx =>
      host(scope)
      val observation = value.observation
      invalid(observation.position >= 0 && observation.occurredAt >= 0 && observation.receivedAt == 0, "Invalid source position/time; receipt time is assigned by the server")
      text(observation.source, "native source identity")
      gaps(observation.gaps)
      val key = MeterKey(observation.attempt, value.meter)
      val (meter, projection) = found(tx.meter(key), "Accounting meter not registered")
      invalid(meter.scope == observation.scope, "Counter scope differs from the frozen meter")
      value.disposition match {
        case UsageDisposition.Contribution => invalid(value.detailReason.isEmpty, "A contributing observation has no detail-exclusion reason")
        case UsageDisposition.Detail =>
          invalid(value.detailReason.exists(_.trim.nonEmpty) && observation.supersedes.isEmpty, "Supplemental evidence requires a reason and cannot supersede a contribution")
          value.detailReason.foreach(text(_, "detail reason"))
      }
      val normalized = UsageMath.normalize(observation)
      tx.observation(observation.id) match {
        case Some(existing) =>
          if (!equivalent(existing.upload, value)) throw DomainFailure(Fault.Conflict("Observation identity reused with different content"))
          UsageReceipt(existing.upload.observation.id, existing.sequence)
        case None =>
          val previous = if (value.disposition == UsageDisposition.Contribution) tx.sample(key, observation.position) else None
          val duplicate = previous.filter(p => equivalent(p.upload, value))
          duplicate match {
            case Some(existing) => UsageReceipt(existing.upload.observation.id, existing.sequence)
            case None =>
              if (value.disposition == UsageDisposition.Contribution) {
                invalid(observation.supersedes == previous.map(_.upload.observation.id), "A correction must supersede the current observation at the same meter position")
                previous.foreach(p => invalid(p.upload.observation.source == observation.source, "Correction cannot change native source identity"))
              }
              val received = value.copy(observation = observation.copy(receivedAt = clock.millis()))
              val recorded = tx.append(received, normalized, scope.actor)
              if (value.disposition == UsageDisposition.Contribution) {
                val incomplete = if (observation.completeness == UsageCompleteness.Complete) 0L else 1L
                val next = meter.scope match {
                  case CounterScope.Increment =>
                    val prior = if (projection.latestPosition.isEmpty) UsageMath.zeroTotals else projection.totals
                    val removed = previous.fold(prior)(p => UsageMath.combine(prior, UsageMath.totals(p.normalized, p.upload.observation.cost), -1))
                    val totals = UsageMath.combine(removed, UsageMath.totals(normalized, observation.cost), 1)
                    val oldIncomplete = previous.fold(0L)(p => if (p.upload.observation.completeness == UsageCompleteness.Complete) 0L else 1L)
                    val incompleteSamples = (if (projection.latestPosition.isEmpty) 0L else projection.incompleteSamples) - oldIncomplete + incomplete
                    require(incompleteSamples >= 0, "Usage coverage projection underflow")
                    MeterProjection(totals, Some(projection.latestPosition.fold(observation.position)(_.max(observation.position))), incompleteSamples,
                      if (incompleteSamples == 0) UsageCompleteness.Complete else UsageCompleteness.Partial,
                      if (incompleteSamples == 0) Nil else List(s"$incompleteSamples incomplete source samples; inspect the audit"))
                  case CounterScope.Cumulative =>
                    if (projection.latestPosition.exists(_ > observation.position)) projection
                    else {
                      if (previous.isEmpty) projection.latestPosition.flatMap(tx.sample(key, _)).foreach { p =>
                        UsageMath.monotonic(p.normalized, normalized)
                        UsageMath.monotonic(p.upload.observation.cost, observation.cost)
                      }
                      val totals = UsageMath.totals(UsageMath.since(normalized, meter.baseline), UsageMath.since(observation.cost, meter.baselineCost))
                      MeterProjection(totals, Some(observation.position), incomplete, observation.completeness, observation.gaps)
                    }
                }
                tx.head(recorded)
                tx.projectMeter(key, next)
              }
              UsageReceipt(observation.id, recorded.sequence)
          }
      }
    }

    override def finish(scope: Scope, value: AttemptOutcome): F[Throwable, AttemptOutcome] = repository.transact(scope.project) { tx =>
      host(scope)
      val attempt = found(tx.attempt(value.attempt), "Attempt not registered")
      invalid(value.state != AttemptState.Running && value.finishedAt >= attempt.startedAt, "Invalid attempt outcome or finish time")
      gaps(value.gaps)
      if (!same(tx.outcome(value.request), value)) tx.putOutcome(value, scope.actor, clock.millis())
      value
    }

    private def filter(scope: Scope, value: UsageFilter): Unit = value match {
      case UsageFilter.TaskOnly(item) => if (item.project != scope.project) throw DomainFailure(Fault.Denied("Usage filter belongs to another project"))
      case UsageFilter.EvaluationOnly(run, scenario) => text(run, "evaluation run"); scenario.foreach(text(_, "evaluation scenario"))
      case _ => ()
    }

    override def summary(scope: Scope, value: UsageFilter): F[Throwable, UsageReport] = repository.read(scope.project) { reader =>
      filter(scope, value)
      var direct = UsageMath.zeroTotals
      var shared = UsageMath.zeroTotals
      var unattributed = UsageMath.zeroTotals
      var sharedAssignments = Set.empty[AssignmentId]
      var sharedAssignmentsTruncated = false
      var incomplete = 0L
      var after = Option.empty[MeterKey]
      var more = true
      while (more) {
        val page = reader.meters(value, after, ReadBatch)
        page.foreach { entry =>
          entry.assignment.attribution match {
            case Attribution.Direct => direct = UsageMath.combine(direct, entry.projection.totals, 1)
            case Attribution.Shared =>
              shared = UsageMath.combine(shared, entry.projection.totals, 1)
              if (sharedAssignments.size < ReadBatch) sharedAssignments += entry.assignment.id
              else if (!sharedAssignments.contains(entry.assignment.id)) sharedAssignmentsTruncated = true
            case Attribution.Unattributed => unattributed = UsageMath.combine(unattributed, entry.projection.totals, 1)
          }
          if (entry.projection.completeness != UsageCompleteness.Complete) incomplete = Math.addExact(incomplete, 1)
        }
        after = page.lastOption.map(p => MeterKey(p.attempt.id, p.meter.key))
        more = page.size == ReadBatch
      }
      UsageReport(direct, shared, unattributed, sharedAssignments, sharedAssignmentsTruncated, reader.cursor, incomplete, reader.attemptsWithoutMeters(value))
    }

    override def audit(scope: Scope, value: UsageFilter, after: Long, limit: Int): F[Throwable, UsagePage] = repository.read(scope.project) { reader =>
      filter(scope, value)
      invalid(limit > 0 && limit <= ReadBatch && after >= 0, "Invalid audit page bounds")
      val entries = reader.audit(value, after, limit + 1)
      val page = entries.take(limit)
      UsagePage(page, page.lastOption.fold(after)(_.sequence), entries.size > limit, reader.cursor)
    }
  }
}
