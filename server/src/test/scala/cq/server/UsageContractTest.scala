package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class UsageContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[UsageRepository[IO]], DIKey[UsageService[IO]], DIKey[LedgerService[IO]]),
  )

  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
  private def collector(scope: Scope): Scope = scope.copy(actor = scope.actor.copy(role = Role.Collector))
  private def observed(value: Long): Counter = Counter(Some(value), Measurement.Observed)
  private def counts(input: Long, output: Long): TokenCounts = TokenCounts(observed(input), observed(output), observed(0), observed(0), observed(0))
  private def price(amount: String): Money = Money(Some(DecimalAmount(amount)), Some("USD"), CostBasis.ProviderEstimate, Some("provider-v1"))
  private def assignment(scope: Scope, members: Set[ItemId], attribution: Attribution, cohort: Option[UUID]): Assignment =
    Assignment(AssignmentId(UUID.randomUUID()), scope.project, members, attribution, cohort, Some(EvaluationScope("consumer-evaluation", "scenario", false)))
  private def task(ledger: LedgerService[IO], owner: Scope, title: String): IO[Throwable, ItemId] = {
    val draft = ItemDraft(title, "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil, None), Nil)
    ledger.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(draft)), Nil, "Usage fixture")).map(_.items.head.id)
  }
  private def start(usage: UsageService[IO], owner: Scope, assignment: Assignment, counterScope: CounterScope, baseline: TokenCounts, baselineCost: Money): IO[Throwable, Attempt] = for {
    _ <- usage.assign(collector(owner), assignment)
    attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Worker, Harness.Codex, "test-provider", "test-model", "fixture-v1", 1000, UsagePhase.Work, None)
    _ <- usage.start(collector(owner), attempt)
    _ <- usage.meter(collector(owner), UsageMeter("provider", attempt.id, counterScope, baseline, baselineCost))
  } yield attempt
  private def phased(usage: UsageService[IO], owner: Scope, member: ItemId, role: Role, phase: UsagePhase, startedAt: Long): IO[Throwable, Attempt] = {
    val work = assignment(owner, Set(member), Attribution.Direct, None)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, role, Harness.Codex, "test-provider", "test-model", "fixture-v1", startedAt, phase, None)
    for {
      _ <- usage.assign(collector(owner), work)
      _ <- usage.start(collector(owner), attempt)
      _ <- usage.meter(collector(owner), UsageMeter("provider", attempt.id, CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney))
    } yield attempt
  }
  private def finished(attempt: Attempt, finishedAt: Long, supersedes: Option[RequestId]): AttemptOutcome =
    AttemptOutcome(RequestId(UUID.randomUUID()), attempt.id, AttemptState.Completed, finishedAt, Nil, supersedes)
  private def upload(attempt: Attempt, position: Long, counterScope: CounterScope, tokens: TokenCounts, cost: Money): UsageUpload =
    UsageUpload(UsageObservation(ObservationId(UUID.randomUUID()), attempt.id, "native-source", position, 2000, 0, counterScope, tokens, true, true, cost, UsageCompleteness.Complete, Nil, None, None), "provider", UsageDisposition.Contribution, None)
  private def denied[A](effect: IO[Throwable, A])(expected: Fault => Boolean): IO[Throwable, Unit] =
    effect.either.flatMap(result => assertIO(result match { case Left(DomainFailure(fault)) => expected(fault); case _ => false })).unit

  "Operational usage audit (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "advance the usage cursor independently of item history and retain it on replay" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "usage cursor")
        member <- task(ledger, owner, "Stable item")
        before <- ledger.get(owner, member)
        empty <- usage.cursor(owner)
        _ <- assertIO(empty == 0)
        run <- start(usage, owner, assignment(owner, Set(member), Attribution.Direct, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        started <- usage.cursor(owner)
        _ <- assertIO(started > empty)
        sample = upload(run, 1, CounterScope.Increment, counts(10, 1), UsageMath.unknownMoney)
        receipt <- usage.ingest(collector(owner), sample)
        observed <- usage.cursor(owner)
        _ <- assertIO(observed == receipt.sequence && observed > started)
        _ <- usage.ingest(collector(owner), sample)
        replayed <- usage.cursor(owner)
        _ <- assertIO(replayed == observed)
        outcome = AttemptOutcome(RequestId(UUID.randomUUID()), run.id, AttemptState.Cancelled, 3000, List("Missing final usage"), None)
        _ <- usage.finish(collector(owner), outcome)
        finished <- usage.cursor(owner)
        _ <- usage.finish(collector(owner), outcome.copy(request = RequestId(UUID.randomUUID()), state = AttemptState.Completed, gaps = Nil, supersedes = Some(outcome.request)))
        corrected <- usage.cursor(owner)
        after <- ledger.get(owner, member)
        history <- ledger.history(owner, member, Revision(Long.MaxValue), 20)
        _ <- assertIO(finished > replayed && corrected > finished && after == before && history.entries.size == 1)
        _ <- denied(usage.cursor(owner.copy(project = ProjectId(UUID.randomUUID()))))(_.isInstanceOf[Fault.Missing])
      } yield ()
    }
    "bound cost-group summaries without discarding distinct pricing evidence" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "bounded costs")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- ZIO.foreachDiscard(1 to 201) { position =>
          usage.ingest(collector(owner), upload(run, position, CounterScope.Increment, counts(1, 0), price("0.01").copy(pricingVersion = Some(f"price-$position%04d"))))
        }
        report <- usage.summary(owner, UsageFilter.ProjectAll())
        groups = report.costs.entries.size
        _ <- assertIO(groups <= 200 && report.costs.hasMore && report.costs.cursor == report.cursor && report.unattributed.total.known == 201)
        remaining <- usage.costs(owner, UsageFilter.ProjectAll(), report.costs.after, Some(report.cursor), 200)
        all = report.costs.entries ++ remaining.entries
        _ <- assertIO(!remaining.hasMore && all.map(_.group.pricingVersion).distinct.size == 201 && all.map(_.amount.value).forall(_ == "0.01"))
        _ <- assertIO(Wire.encode(UsageReport_JsonCodec, report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ReadPage.MaxBytes)
        extra = upload(run, 202, CounterScope.Increment, counts(1, 0), price("0").copy(pricingVersion = None))
        receipt <- usage.ingest(collector(owner), extra)
        _ <- denied(usage.costs(owner, UsageFilter.ProjectAll(), report.costs.after, Some(report.cursor), 200))(_.isInstanceOf[Fault.Resync])
        zero <- usage.costs(owner, UsageFilter.ProjectAll(), None, None, 1)
        _ <- assertIO(zero.entries.head.amount.value == "0" && zero.entries.head.measurements == 1 && zero.entries.head.group.pricingVersion.isEmpty)
        corrected = extra.copy(observation = extra.observation.copy(id = ObservationId(UUID.randomUUID()), cost = price("1").copy(currency = Some("EUR"), basis = CostBasis.ActualBilling), supersedes = Some(receipt.id)))
        _ <- usage.ingest(collector(owner), corrected)
        _ <- usage.ingest(collector(owner), corrected)
        head <- usage.costs(owner, UsageFilter.ProjectAll(), None, None, 2)
        _ <- assertIO(head.entries.map(_.group.currency) == List("EUR", "USD") && head.entries.forall(_.group.pricingVersion.nonEmpty) && head.entries.head.measurements == 1)
        raw <- usage.audit(owner, UsageFilter.ProjectAll(), receipt.sequence - 1, 10)
        _ <- assertIO(raw.entries.size == 2 && raw.entries.head.upload.observation.cost.amount.get.value == "0")
      } yield ()
    }

    "reject oversized cost provenance before storing or returning it" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "cost provenance bounds")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        value = upload(run, 1, CounterScope.Increment, counts(1, 0), price("0.01").copy(pricingVersion = Some("x" * 600000)))
        _ <- denied(usage.ingest(collector(owner), value))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.ingest(collector(owner), value.copy(observation = value.observation.copy(cost = price("0.01").copy(pricingVersion = Some(""))))))(_.isInstanceOf[Fault.Invalid])
      } yield ()
    }

    "page all cost identities losslessly and keep shared spend separate from member spend" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val prices = List(
        price("0").copy(pricingVersion = None), price("0.01").copy(pricingVersion = Some("😀")),
        price("0.02").copy(pricingVersion = Some("\ue000")), price("0.03").copy(basis = CostBasis.PriceTable),
        price("0.04").copy(currency = Some("EUR"), basis = CostBasis.ActualBilling), price("0.05"),
      )
      def pages(after: Option[CostGroup], cursor: Option[Long]): IO[Throwable, List[CostTotal]] =
        usage.costs(owner, UsageFilter.ProjectAll(), after, cursor, 1).flatMap { page =>
          if (page.hasMore) pages(page.after, Some(page.cursor)).map(page.entries ++ _) else ZIO.succeed(page.entries)
        }
      for {
        _ <- ledger.initialize(owner, "cost identities")
        one <- task(ledger, owner, "One")
        two <- task(ledger, owner, "Two")
        _ <- ZIO.foreachDiscard(List((Attribution.Direct, Set(one)), (Attribution.Shared, Set(one, two)), (Attribution.Unattributed, Set.empty[ItemId]))) { case (attribution, members) =>
          for {
            run <- start(usage, owner, assignment(owner, members, attribution, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
            _ <- ZIO.foreachDiscard(prices.zipWithIndex) { case (money, i) => usage.ingest(collector(owner), upload(run, i, CounterScope.Increment, counts(1, 0), money)) }
          } yield ()
        }
        all <- pages(None, None)
        expected = Attribution.all.toList.flatMap(a => prices.map(p => CostGroup(a, p.currency.get, p.basis, p.pricingVersion))).toSet
        _ <- assertIO(all.size == 18 && all.map(_.group).toSet == expected)
        member <- usage.summary(owner, UsageFilter.TaskOnly(two))
        _ <- assertIO(member.costs.entries.size == 6 && member.costs.entries.forall(_.group.attribution == Attribution.Shared))
      } yield ()
    }

    "preserve exact decimal amounts beyond machine decimal precision" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val amount = "9" * 50
      val expected = (BigInt(amount) * 2).toString
      for {
        _ <- ledger.initialize(owner, "exact costs")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- usage.ingest(collector(owner), upload(run, 1, CounterScope.Increment, counts(1, 0), price(amount)))
        _ <- usage.ingest(collector(owner), upload(run, 2, CounterScope.Increment, counts(1, 0), price(amount)))
        report <- usage.summary(owner, UsageFilter.ProjectAll())
        actual = report.costs.entries.head.amount.value
        _ <- assertIO(actual == expected)
        cumulative <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Cumulative, UsageMath.zeroCounts, price("1"))
        _ <- usage.ingest(collector(owner), upload(cumulative, 1, CounterScope.Cumulative, counts(1, 0), price(amount)))
        combined <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(combined.costs.entries.head.amount.value == (BigInt(amount) * 3 - 1).toString)
      } yield ()
    }

    "expose attempt completion gaps independently of complete individual observations" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val gap = "Final request usage unavailable"
      for {
        _ <- ledger.initialize(owner, "attempt coverage")
        member <- task(ledger, owner, "Interrupted task")
        attempt <- start(usage, owner, assignment(owner, Set(member), Attribution.Direct, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- usage.ingest(collector(owner), upload(attempt, 1, CounterScope.Increment, counts(100, 0), UsageMath.unknownMoney))
        _ <- usage.finish(collector(owner), AttemptOutcome(RequestId(UUID.randomUUID()), attempt.id, AttemptState.Cancelled, 3000, List(gap), None))
        report <- usage.summary(owner, UsageFilter.TaskOnly(member))
        attempts <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 20)
        recorded = attempts.entries.head.outcome.get
        _ <- assertIO(report.incompleteMeters == 0 && report.attempts.withGaps == 1 && recorded.value.gaps == List(gap))
        _ <- usage.ingest(collector(owner), upload(attempt, 2, CounterScope.Increment, counts(10, 0), UsageMath.unknownMoney))
        stillIncomplete <- usage.summary(owner, UsageFilter.TaskOnly(member))
        _ <- assertIO(stillIncomplete.attempts.withGaps == 1 && stillIncomplete.direct.total.known == 110)
        corrected = AttemptOutcome(RequestId(UUID.randomUUID()), attempt.id, AttemptState.Completed, 2500, Nil, Some(recorded.value.request))
        _ <- usage.finish(collector(owner), corrected)
        _ <- usage.finish(collector(owner), recorded.value)
        complete <- usage.summary(owner, UsageFilter.TaskOnly(member))
        _ <- assertIO(complete.attempts.withGaps == 0)
        historical <- usage.outcomes(owner, attempt.id, 0, 1)
        next <- usage.outcomes(owner, attempt.id, historical.after, 1)
        _ <- assertIO(historical.hasMore && historical.entries.head == recorded && !next.hasMore && next.entries.head.value == corrected)
        _ <- denied(usage.finish(collector(owner), corrected.copy(request = RequestId(UUID.randomUUID()))))(_.isInstanceOf[Fault.Invalid])
        pending = Attempt(AttemptId(UUID.randomUUID()), attempt.assignment, None, owner.actor.session, Role.Worker, Harness.Claude, "fixture", "fixture", "fixture", 1000, UsagePhase.Work, None)
        _ <- usage.start(collector(owner), pending)
        page <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 1)
        last <- usage.attempts(owner, UsageFilter.TaskOnly(member), page.after, Some(page.cursor), 1)
        _ <- assertIO(page.hasMore && !last.hasMore && (page.entries ++ last.entries).exists(v => v.attempt.id == pending.id && v.outcome.isEmpty))
        running <- usage.summary(owner, UsageFilter.TaskOnly(member))
        _ <- assertIO(running.attempts.running == 1 && running.attemptsWithoutMeters == 1)
        _ <- usage.meter(collector(owner), UsageMeter("new", pending.id, CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney))
        _ <- denied(usage.attempts(owner, UsageFilter.TaskOnly(member), page.after, Some(page.cursor), 1))(_.isInstanceOf[Fault.Resync])
      } yield ()
    }

    "D162: list attempts by start time, newest first, with the attempt ID as tie-breaker, in pages that neither repeat nor skip one" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "attempt order")
        member <- task(ledger, owner, "Listed task")
        work = assignment(owner, Set(member), Attribution.Direct, None)
        _ <- usage.assign(collector(owner), work)
        // Seven attempts whose IDs say nothing about when they started; three of them started in the same millisecond.
        attempts = List(5000L, 1000L, 3000L, 3000L, 9000L, 3000L, 7000L).map(startedAt =>
          Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, Role.Worker, Harness.Codex, "test-provider", "model", "fixture-v1", startedAt, UsagePhase.Work, None))
        _ <- ZIO.foreachDiscard(attempts)(usage.start(collector(owner), _))
        expected = attempts.sortWith((left, right) => left.startedAt > right.startedAt || (left.startedAt == right.startedAt && left.id.value.toString > right.id.value.toString)).map(_.id)
        whole <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 20)
        _ <- assertIO(whole.entries.map(_.attempt.id) == expected && !whole.hasMore)
        // Pages of two, one of which ends inside the tie.
        first <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 2)
        paged <- ZIO.iterate((first.entries.map(_.attempt.id), first))(_._2.hasMore) { (seen, page) =>
          usage.attempts(owner, UsageFilter.TaskOnly(member), page.after, Some(page.cursor), 2).map(next => (seen ++ next.entries.map(_.attempt.id), next))
        }
        _ <- assertIO(paged._1 == expected && first.after.contains(expected(1)))
        // A key that is no attempt of the project is refused instead of read as a position.
        _ <- denied(usage.attempts(owner, UsageFilter.TaskOnly(member), Some(AttemptId(UUID.randomUUID())), Some(whole.cursor), 2))(
          _ == Fault.Invalid(UsageCursors.UnknownAttemptKey))
        // An attempt that starts between two page reads, in the tie the first page ended in: the continuation is refused, because the
        // listing changed, and the listing read again from its start holds every attempt once, the new one in its place.
        late = Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, Role.Worker, Harness.Codex, "test-provider", "model", "fixture-v1", 3000L, UsagePhase.Work, None)
        _ <- usage.start(collector(owner), late)
        _ <- denied(usage.attempts(owner, UsageFilter.TaskOnly(member), first.after, Some(first.cursor), 2))(_ == Fault.Resync("Usage snapshot changed; restart attempt listing"))
        all = (attempts :+ late).sortWith((left, right) => left.startedAt > right.startedAt || (left.startedAt == right.startedAt && left.id.value.toString > right.id.value.toString)).map(_.id)
        again <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 3)
        reread <- ZIO.iterate((again.entries.map(_.attempt.id), again))(_._2.hasMore) { (seen, page) =>
          usage.attempts(owner, UsageFilter.TaskOnly(member), page.after, Some(page.cursor), 3).map(next => (seen ++ next.entries.map(_.attempt.id), next))
        }
        _ <- assertIO(reread._1 == all && all.size == 8)
      } yield ()
    }

    "keep the effort an attempt was launched with, and an abstained outcome with its reason, apart from failed and unknown ones" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val reason = "Abstained (Quota): Quota exceeded. Check your plan and billing details."
      for {
        _ <- ledger.initialize(owner, "abstention")
        member <- task(ledger, owner, "Refused task")
        work = assignment(owner, Set(member), Attribution.Direct, None)
        _ <- usage.assign(collector(owner), work)
        attempts = List(Some(Effort.Ultra), Some(Effort.Off), None).zipWithIndex.map { (effort, index) =>
          Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, Role.Worker, Harness.Codex, "test-provider", s"model-$index", "fixture-v1", 1000 + index, UsagePhase.Work, effort)
        }
        started <- ZIO.foreach(attempts)(usage.start(collector(owner), _))
        // The same attempt replays; the effort is part of what it is.
        _ <- usage.start(collector(owner), attempts.head)
        abstained = AttemptOutcome(RequestId(UUID.randomUUID()), attempts.head.id, AttemptState.Abstained, 3000, List(reason), None)
        recorded <- usage.finish(collector(owner), abstained)
        _ <- usage.finish(collector(owner), abstained)
        page <- usage.attempts(owner, UsageFilter.TaskOnly(member), None, None, 20)
        report <- usage.summary(owner, UsageFilter.TaskOnly(member))
        phases <- usage.phases(owner, UsageFilter.TaskOnly(member))
        outcomes <- usage.outcomes(owner, attempts.head.id, 0, 20)
        _ <- assertIO(started == attempts && recorded == abstained)
        _ <- assertIO(page.entries.map(_.attempt).sortBy(_.startedAt) == attempts && page.entries.map(_.attempt.effort).toSet == Set(Some(Effort.Ultra), Some(Effort.Off), None))
        _ <- assertIO(page.entries.find(_.attempt.id == attempts.head.id).flatMap(_.outcome).map(_.value).contains(abstained) && outcomes.entries.map(_.value) == List(abstained))
        // An abstention is a finished attempt with a stated gap; it is neither running nor of unknown outcome.
        _ <- assertIO(report.attempts.running == 2 && report.attempts.unknown == 0 && report.attempts.withGaps == 1)
        _ <- assertIO(phases.phases.map(entry => (entry.phase, entry.attempts)) == List((UsagePhase.Work, 3L)))
      } yield ()
    }

    "include exclusive member work in cohort totals while preserving direct task attribution" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val cohort = UUID.randomUUID()
      for {
        _ <- ledger.initialize(owner, "cohort direct usage")
        one <- task(ledger, owner, "One")
        two <- task(ledger, owner, "Two")
        shared <- start(usage, owner, assignment(owner, Set(one, two), Attribution.Shared, Some(cohort)), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        direct <- start(usage, owner, assignment(owner, Set(one), Attribution.Direct, Some(cohort)), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- usage.ingest(collector(owner), upload(shared, 1, CounterScope.Increment, counts(1000, 0), UsageMath.unknownMoney))
        _ <- usage.ingest(collector(owner), upload(direct, 1, CounterScope.Increment, counts(200, 0), UsageMath.unknownMoney))
        report <- usage.summary(owner, UsageFilter.CohortOnly(cohort))
        member <- usage.summary(owner, UsageFilter.TaskOnly(one))
        _ <- assertIO(report.direct.total.known == 200 && report.shared.total.known == 1000 && member.direct.total.known == 200)
      } yield ()
    }

    "account 1500 then 1600 tokens without duplicating shared spend or mutating item history" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      val cohort = UUID.randomUUID()
      for {
        _ <- ledger.initialize(owner, "usage attribution")
        one <- task(ledger, owner, "One")
        two <- task(ledger, owner, "Two")
        sharedAssignment = assignment(owner, Set(one, two), Attribution.Shared, Some(cohort))
        shared <- start(usage, owner, sharedAssignment, CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        first <- start(usage, owner, assignment(owner, Set(one), Attribution.Direct, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        second <- start(usage, owner, assignment(owner, Set(two), Attribution.Direct, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        sharedUpload = upload(shared, 1, CounterScope.Increment, counts(1000, 0), UsageMath.unknownMoney)
        firstReceipt <- usage.ingest(host, sharedUpload)
        _ <- usage.ingest(host, upload(first, 1, CounterScope.Increment, counts(200, 0), UsageMath.unknownMoney))
        _ <- usage.ingest(host, upload(second, 1, CounterScope.Increment, counts(300, 0), UsageMath.unknownMoney))
        repeated <- usage.ingest(host, sharedUpload)
        _ <- assertIO(firstReceipt == repeated)
        project <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(project.direct.total.known == 500 && project.shared.total.known == 1000 && project.unattributed.total.known == 0)
        _ <- assertIO(project.direct.unknownCosts == 2 && project.shared.unknownCosts == 1 && project.costs.entries.isEmpty)
        views <- ZIO.foreach(List(one, two))(id => usage.summary(owner, UsageFilter.TaskOnly(id)))
        _ <- assertIO(views.map(_.direct.total.known) == List(200L, 300L) && views.forall(_.sharedAssignments == List(sharedAssignment)))
        correction = sharedUpload.copy(observation = sharedUpload.observation.copy(id = ObservationId(UUID.randomUUID()), counters = counts(1100, 0), supersedes = Some(sharedUpload.observation.id)))
        corrections <- ZIO.foreachPar((1 to 8).toList)(_ => usage.ingest(host, correction))
        _ <- assertIO(corrections.distinct.size == 1)
        _ <- usage.finish(host, AttemptOutcome(RequestId(UUID.randomUUID()), second.id, AttemptState.Cancelled, 3000, List("Cancelled after measured work"), None))
        corrected <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(corrected.direct.total.known + corrected.shared.total.known == 1600)
        cohortView <- usage.summary(owner, UsageFilter.CohortOnly(cohort))
        _ <- assertIO(cohortView.shared.total.known == 1100 && cohortView.direct.total.known == 0)
        sessionView <- usage.summary(owner, UsageFilter.SessionOnly(owner.actor.session))
        evaluation <- usage.summary(owner, UsageFilter.EvaluationOnly("consumer-evaluation", Some("scenario")))
        _ <- assertIO(sessionView.direct == corrected.direct && evaluation.shared == corrected.shared)
        _ <- denied(usage.assign(host, sharedAssignment.copy(members = Set(one), attribution = Attribution.Direct, cohort = None)))(_.isInstanceOf[Fault.Conflict])
        _ <- usage.assign(host, assignment(owner, Set(one, two), Attribution.Shared, Some(UUID.randomUUID())))
        histories <- ZIO.foreach(List(one, two))(id => ledger.history(owner, id, Revision(Long.MaxValue), 200))
        _ <- assertIO(histories.forall(_.entries.size == 1))
        page <- usage.audit(owner, UsageFilter.ProjectAll(), 0, 2)
        next <- usage.audit(owner, UsageFilter.ProjectAll(), page.after, 2)
        records = page.entries ++ next.entries
        _ <- assertIO(page.hasMore && !next.hasMore && records.size == 4)
        _ <- assertIO(records.filter(_.upload.observation.attempt == shared.id).map(_.normalized.input.value) == List(Some(1000L), Some(1100L)))
        _ <- assertIO(records.forall(r => r.upload.observation.receivedAt > 0 && r.actor == host.actor))
        item <- ledger.get(owner, one)
        _ <- ledger.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Replace(one, item.item.revision, item.item.draft.copy(archived = true, content = item.item.draft.content.asInstanceOf[Content.Task].copy(status = TaskStatus.Done)))), Nil, "Archive retains spend"))
        afterArchive <- usage.summary(owner, UsageFilter.TaskOnly(one))
        _ <- assertIO(afterArchive.direct.total.known == 200 && afterArchive.shared.total.known == 1100)
      } yield ()
    }

    "normalize cache/reasoning subsets and preserve excluded aggregate evidence without adding it" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "normalization")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        raw = upload(run, 1, CounterScope.Increment, TokenCounts(observed(48), observed(20), observed(100), observed(10), observed(13)), price("0.00084"))
        inclusive = raw.copy(observation = raw.observation.copy(inputIncludesCache = false))
        _ <- usage.ingest(collector(owner), inclusive)
        detail = upload(run, 2, CounterScope.Increment, counts(158, 20), price("0.00084")).copy(disposition = UsageDisposition.Detail, detailReason = Some("Native aggregate overlaps the contributing message observation"))
        _ <- usage.ingest(collector(owner), detail)
        report <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(report.unattributed.input.known == 158 && report.unattributed.output.known == 20 && report.unattributed.total.known == 178)
        _ <- assertIO(report.unattributed.cacheRead.known == 100 && report.unattributed.reasoning.known == 13)
        _ <- assertIO(report.costs.entries.map(_.amount.value) == List("0.00084"))
        audit <- usage.audit(owner, UsageFilter.ProjectAll(), 0, 200)
        _ <- assertIO(audit.entries.size == 2 && audit.entries.head.upload.observation.counters.input.value.contains(48L) && audit.entries.head.normalized.input.value.contains(158L))
      } yield ()
    }

    "account cumulative resume baselines, out-of-order events and explicit corrections" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      for {
        _ <- ledger.initialize(owner, "cumulative")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Cumulative, counts(100, 20), price("1"))
        one = upload(run, 1, CounterScope.Cumulative, counts(150, 30), price("1.5"))
        two = upload(run, 2, CounterScope.Cumulative, counts(200, 40), price("1.9"))
        _ <- usage.ingest(host, one)
        _ <- usage.ingest(host, two)
        _ <- usage.ingest(host, upload(run, 0, CounterScope.Cumulative, counts(130, 25), price("1.2")))
        before <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(before.unattributed.total.known == 120 && before.costs.entries.head.amount.value == "0.9")
        _ <- denied(usage.ingest(host, upload(run, 3, CounterScope.Cumulative, counts(190, 45), price("2"))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.ingest(host, upload(run, 3, CounterScope.Cumulative, counts(230, 45), price("1.8"))))(_.isInstanceOf[Fault.Invalid])
        correction = two.copy(observation = two.observation.copy(id = ObservationId(UUID.randomUUID()), counters = counts(230, 45), cost = price("2.05"), supersedes = Some(two.observation.id)))
        _ <- usage.ingest(host, correction)
        oldCorrection = one.copy(observation = one.observation.copy(id = ObservationId(UUID.randomUUID()), counters = counts(170, 35), cost = price("1.7"), supersedes = Some(one.observation.id)))
        _ <- usage.ingest(host, oldCorrection)
        report <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(report.unattributed.total.known == 155 && report.costs.entries.head.amount.value == "1.05")
      } yield ()
    }

    "distinguish pending collection, measured zero, missing fields and unknown prices" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      val work = assignment(owner, Set.empty, Attribution.Unattributed, None)
      val attempt = Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, Role.Governor, Harness.Pi, "provider", "model", "fixture-v1", 1000, UsagePhase.Govern, None)
      for {
        _ <- ledger.initialize(owner, "coverage")
        _ <- usage.assign(host, work)
        _ <- usage.start(host, attempt)
        pending <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(pending.attemptsWithoutMeters == 1)
        _ <- usage.meter(host, UsageMeter("provider", attempt.id, CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney))
        empty <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(empty.attemptsWithoutMeters == 0 && empty.incompleteMeters == 1 && empty.unattributed.total.unknown == 1)
        zero = upload(attempt, 1, CounterScope.Increment, counts(0, 0), UsageMath.unknownMoney)
        receipts <- ZIO.foreachPar((1 to 8).toList)(_ => usage.ingest(host, zero))
        _ <- assertIO(receipts.distinct.size == 1)
        sameSource = zero.copy(observation = zero.observation.copy(id = ObservationId(UUID.randomUUID())))
        sourceReceipt <- usage.ingest(host, sameSource)
        _ <- assertIO(sourceReceipt == receipts.head)
        measured <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(measured.unattributed.total.known == 0 && measured.unattributed.total.unknown == 0 && measured.unattributed.unknownCosts == 1)
        missing = upload(attempt, 2, CounterScope.Increment, counts(50, 0).copy(output = Counter(None, Measurement.Unsupported)), UsageMath.unknownMoney)
        _ <- usage.ingest(host, missing.copy(observation = missing.observation.copy(completeness = UsageCompleteness.Partial, gaps = List("Output counter unavailable"))))
        incomplete <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(incomplete.unattributed.total.known == 50 && incomplete.unattributed.total.unknown == 1 && incomplete.incompleteMeters == 1)
        malformed = upload(attempt, 3, CounterScope.Increment, counts(0, 0).copy(input = Counter(Some(0), Measurement.Missing)), UsageMath.unknownMoney)
        _ <- denied(usage.ingest(host, malformed))(_.isInstanceOf[Fault.Invalid])
        after <- usage.summary(owner, UsageFilter.ProjectAll())
        _ <- assertIO(after == incomplete)
      } yield ()
    }

    "accept late usage after claim release while denying telemetry credentials ledger writes" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      for {
        _ <- ledger.initialize(owner, "late collection")
        item <- task(ledger, owner, "Work")
        claim <- ledger.acquire(owner, ClaimId(UUID.randomUUID()), Set(item), 300000)
        run <- start(usage, owner, assignment(owner, Set(item), Attribution.Direct, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- ledger.release(owner, claim.fence)
        value = upload(run, 1, CounterScope.Increment, counts(40, 10), UsageMath.unknownMoney)
        _ <- denied(usage.ingest(owner, value))(_.isInstanceOf[Fault.Denied])
        _ <- usage.ingest(host, value)
        _ <- denied(task(ledger, host, "Collector cannot mutate items"))(_.isInstanceOf[Fault.Denied])
        history <- ledger.history(owner, item, Revision(Long.MaxValue), 200)
        _ <- assertIO(history.entries.size == 1)
        report <- usage.summary(owner, UsageFilter.TaskOnly(item))
        _ <- assertIO(report.direct.total.known == 50)
        _ <- denied(usage.summary(owner, UsageFilter.TaskOnly(item.copy(project = ProjectId(UUID.randomUUID())))))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "report an attached governing attempt without an outcome as open and never as running" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      val session = UsageFilter.SessionOnly(owner.actor.session)
      val overhead = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None)
      val governing = Attempt(AttemptId(UUID.randomUUID()), overhead.id, None, owner.actor.session, Role.Governor, Harness.Claude,
        "unobserved-interactive-provider", "unobserved-interactive-model", SupervisorConfig.AttachedGovernorCollector, 1000, UsagePhase.Govern, None)
      for {
        _ <- ledger.initialize(owner, "attached governing attempt")
        item <- task(ledger, owner, "Managed task")
        _ <- usage.assign(host, overhead)
        _ <- usage.start(host, governing)
        child <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 2000)
        summary <- usage.summary(owner, session)
        _ <- assertIO(summary.attempts.running == 1 && summary.attempts.open == 1)
        report <- usage.phases(owner, session)
        byPhase = report.phases.map(value => value.phase -> value).toMap
        govern = byPhase(UsagePhase.Govern)
        _ <- assertIO(govern.attempts == 1 && govern.running == 0 && govern.open == 1 && govern.wallMillis == 0)
        _ <- assertIO(byPhase(UsagePhase.Work).running == 1 && byPhase(UsagePhase.Work).open == 0)
        listed <- usage.attempts(owner, session, None, None, 2)
        _ <- assertIO(listed.entries.map(entry => entry.attempt.id -> entry.observed).toMap == Map(governing.id -> false, child.id -> true))
        _ <- usage.finish(host, finished(child, 2500, None))
        _ <- usage.finish(host, AttemptOutcome(RequestId(UUID.randomUUID()), governing.id, AttemptState.Unknown, 9000, List("Attached session ended"), None))
        closed <- usage.summary(owner, session)
        _ <- assertIO(closed.attempts.running == 0 && closed.attempts.open == 0 && closed.attempts.unknown == 1)
      } yield ()
    }

    "report attempts, finished wall time, tokens and costs per phase with running attempts apart" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val host = collector(owner)
      val session = UsageFilter.SessionOnly(owner.actor.session)
      for {
        _ <- ledger.initialize(owner, "phase report")
        item <- task(ledger, owner, "Phased task")
        empty <- usage.phases(owner, session)
        _ <- assertIO(empty.phases.isEmpty && !empty.costsTruncated)
        first <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 1000)
        second <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 2000)
        review <- phased(usage, owner, item, Role.Reviewer, UsagePhase.Review, 5000)
        probe <- phased(usage, owner, item, Role.Worker, UsagePhase.Probe, 6000)
        foreign <- phased(usage, other, item, Role.Worker, UsagePhase.Work, 1000)
        _ <- usage.ingest(host, upload(first, 1, CounterScope.Increment, counts(100, 10), price("0.10")))
        _ <- usage.ingest(host, upload(second, 1, CounterScope.Increment, counts(200, 20), price("0.20")))
        _ <- usage.ingest(host, upload(review, 1, CounterScope.Increment, counts(50, 5), UsageMath.unknownMoney))
        _ <- usage.ingest(host, upload(probe, 1, CounterScope.Increment, counts(7, 1), UsageMath.unknownMoney))
        _ <- usage.ingest(host, upload(foreign, 1, CounterScope.Increment, counts(1000, 0), price("1")))
        _ <- usage.finish(host, finished(first, 4000, None))
        early = finished(second, 2500, None)
        _ <- usage.finish(host, early)
        _ <- usage.finish(host, finished(review, 5600, None))
        _ <- usage.finish(host, finished(foreign, 1100, None))
        report <- usage.phases(owner, session)
        cursor <- usage.cursor(owner)
        byPhase = report.phases.map(value => value.phase -> value).toMap
        _ <- assertIO(report.phases.map(_.phase) == List(UsagePhase.Probe, UsagePhase.Work, UsagePhase.Review) && report.cursor == cursor && !report.costsTruncated)
        work = byPhase(UsagePhase.Work)
        _ <- assertIO(work.attempts == 2 && work.running == 0 && work.spans == 0 && work.wallMillis == 3500)
        _ <- assertIO(work.totals.input.known == 300 && work.totals.output.known == 30 && work.totals.total.known == 330 && work.totals.unknownCosts == 0)
        _ <- assertIO(work.costs == List(CostTotal(CostGroup(Attribution.Direct, "USD", CostBasis.ProviderEstimate, Some("provider-v1")), DecimalAmount("0.3"), 2)))
        reviewed = byPhase(UsagePhase.Review)
        _ <- assertIO(reviewed.attempts == 1 && reviewed.running == 0 && reviewed.wallMillis == 600 && reviewed.totals.total.known == 55 && reviewed.totals.unknownCosts == 1 && reviewed.costs.isEmpty)
        probing = byPhase(UsagePhase.Probe)
        _ <- assertIO(probing.attempts == 1 && probing.running == 1 && probing.wallMillis == 0 && probing.totals.total.known == 8)
        _ <- usage.finish(host, finished(second, 3000, Some(early.request)))
        corrected <- usage.phases(owner, session)
        _ <- assertIO(corrected.phases.find(_.phase == UsagePhase.Work).exists(_.wallMillis == 4000))
        project <- usage.phases(owner, UsageFilter.ProjectAll())
        all = project.phases.find(_.phase == UsagePhase.Work).get
        _ <- assertIO(all.attempts == 3 && all.wallMillis == 4100 && all.totals.total.known == 1330 && all.costs.map(_.amount.value) == List("1.3"))
        _ <- denied(usage.phases(owner, UsageFilter.TaskOnly(item.copy(project = ProjectId(UUID.randomUUID())))))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "add host spans to their phase's span count and wall time, once per identity" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val host = collector(owner)
      val session = UsageFilter.SessionOnly(owner.actor.session)
      def span(assignment: AssignmentId, phase: UsagePhase, startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
        PhaseSpan(RequestId(UUID.randomUUID()), assignment, owner.actor.session, phase, startedAt, finishedAt, state, None, None, None)
      for {
        _ <- ledger.initialize(owner, "phase spans")
        item <- task(ledger, owner, "Checked task")
        apart <- task(ledger, owner, "Another task")
        work <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 1000)
        resolver <- phased(usage, owner, item, Role.Worker, UsagePhase.Combine, 9000)
        separate <- phased(usage, owner, apart, Role.Worker, UsagePhase.Work, 1000)
        _ <- usage.finish(host, finished(work, 4000, None))
        _ <- usage.finish(host, finished(resolver, 9400, None))
        passed = span(work.assignment, UsagePhase.Check, 4000, 4700, AttemptState.Completed)
        before <- usage.cursor(owner)
        _ <- denied(usage.span(owner, passed))(_.isInstanceOf[Fault.Denied])
        _ <- denied(usage.span(host, passed.copy(assignment = AssignmentId(UUID.randomUUID()))))(_.isInstanceOf[Fault.Missing])
        _ <- denied(usage.span(host, passed.copy(state = AttemptState.Running)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, passed.copy(finishedAt = 3999)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, passed.copy(phase = UsagePhase.Work)))(_.isInstanceOf[Fault.Invalid])
        refused <- usage.cursor(owner)
        _ <- assertIO(refused == before)
        recorded <- usage.span(host, passed)
        once <- usage.cursor(owner)
        replayed <- usage.span(host, passed)
        twice <- usage.cursor(owner)
        _ <- assertIO(recorded == passed && replayed == passed && once == before + 1 && twice == once)
        _ <- denied(usage.span(host, passed.copy(finishedAt = 4800)))(_.isInstanceOf[Fault.Conflict])
        _ <- usage.span(host, span(work.assignment, UsagePhase.Check, 4700, 5000, AttemptState.Failed))
        _ <- usage.span(host, span(work.assignment, UsagePhase.Check, 5000, 5000, AttemptState.Unknown))
        _ <- usage.span(host, span(work.assignment, UsagePhase.Integrate, 6000, 8500, AttemptState.Cancelled))
        _ <- usage.span(host, span(work.assignment, UsagePhase.Combine, 8500, 8600, AttemptState.Completed))
        _ <- usage.span(host, span(separate.assignment, UsagePhase.Check, 100, 150, AttemptState.Completed))
        _ <- usage.span(host, span(work.assignment, UsagePhase.Check, 1, 11, AttemptState.Completed).copy(session = other.actor.session))
        report <- usage.phases(owner, session)
        cursor <- usage.cursor(owner)
        byPhase = report.phases.map(value => value.phase -> value).toMap
        _ <- assertIO(report.phases.map(_.phase) == List(UsagePhase.Work, UsagePhase.Check, UsagePhase.Combine, UsagePhase.Integrate) && report.cursor == cursor)
        _ <- assertIO(byPhase(UsagePhase.Work).spans == 0 && byPhase(UsagePhase.Work).attempts == 2)
        checked = byPhase(UsagePhase.Check)
        _ <- assertIO(checked.attempts == 0 && checked.running == 0 && checked.spans == 4 && checked.wallMillis == 1050)
        _ <- assertIO(checked.totals == UsageMath.zeroTotals && checked.costs.isEmpty)
        _ <- assertIO(byPhase(UsagePhase.Integrate).spans == 1 && byPhase(UsagePhase.Integrate).wallMillis == 2500)
        _ <- assertIO(byPhase(UsagePhase.Combine).attempts == 1 && byPhase(UsagePhase.Combine).spans == 1 && byPhase(UsagePhase.Combine).wallMillis == 500)
        byTask <- usage.phases(owner, UsageFilter.TaskOnly(item))
        _ <- assertIO(byTask.phases.find(_.phase == UsagePhase.Check).exists(value => value.spans == 4 && value.wallMillis == 1010))
        apartOnly <- usage.phases(owner, UsageFilter.TaskOnly(apart))
        _ <- assertIO(apartOnly.phases.map(value => (value.phase, value.spans, value.wallMillis)) == List((UsagePhase.Work, 0L, 0L), (UsagePhase.Check, 1L, 50L)))
        foreign <- usage.phases(owner, UsageFilter.SessionOnly(other.actor.session))
        _ <- assertIO(foreign.phases.map(value => (value.phase, value.attempts, value.spans, value.wallMillis)) == List((UsagePhase.Check, 0L, 1L, 10L)))
      } yield ()
    }

    "register a check, an integration and a combination Running at their start and finish each once, counted apart from running attempts" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      val filters = List(UsageFilter.SessionOnly(owner.actor.session), UsageFilter.ProjectAll())
      def started(assignment: AssignmentId, phase: UsagePhase, at: Long, name: Option[String]): PhaseSpan =
        PhaseSpan(RequestId(UUID.randomUUID()), assignment, owner.actor.session, phase, at, at, AttemptState.Running, name, None, None)
      def ended(span: PhaseSpan, at: Long, state: AttemptState): PhaseSpan = span.copy(finishedAt = at, state = state)
      // spans, running host spans, running attempts and wall time of the row, with the check rows of the same read.
      def figures(filter: UsageFilter, phase: UsagePhase): IO[Throwable, ((Long, Long, Long, Long), List[(Option[String], AttemptState, Long, Long)])] = for {
        phases <- usage.phases(owner, filter)
        checks <- usage.checks(owner, filter)
        row = phases.phases.find(_.phase == phase).map(value => (value.spans, value.runningSpans, value.running, value.wallMillis)).getOrElse((0L, 0L, 0L, 0L))
        _ <- assertIO(phases.cursor == checks.cursor)
        checked = phases.phases.find(_.phase == UsagePhase.Check).map(value => (value.spans, value.wallMillis)).getOrElse((0L, 0L))
        _ <- assertIO((checks.checks.map(_.runs).sum, checks.checks.map(_.wallMillis).sum) == checked)
      } yield (row, checks.checks.map(value => (value.check, value.state, value.runs, value.wallMillis)))
      def everywhere(phase: UsagePhase, row: (Long, Long, Long, Long), checks: List[(Option[String], AttemptState, Long, Long)]): IO[Throwable, Unit] =
        ZIO.foreachDiscard(filters)(filter => figures(filter, phase).flatMap(value => assertIO(value == ((row, checks)))))
      for {
        _ <- ledger.initialize(owner, "running host spans")
        item <- task(ledger, owner, "Checked task")
        work <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 1000)
        taskFilter = UsageFilter.TaskOnly(item)
        // The check starts at 10 s.
        unit = started(work.assignment, UsagePhase.Check, 10000, Some("unit"))
        before <- usage.cursor(owner)
        _ <- denied(usage.span(owner, unit))(_.isInstanceOf[Fault.Denied])
        _ <- denied(usage.span(host, unit.copy(finishedAt = 10001)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, ended(unit, 9999, AttemptState.Completed)))(_.isInstanceOf[Fault.Invalid])
        refused <- usage.cursor(owner)
        _ <- assertIO(refused == before)
        registered <- usage.span(host, unit)
        cursor1 <- usage.cursor(owner)
        _ <- assertIO(registered == unit && cursor1 == before + 1)
        // Running: counted as a span and as a running span, not as a running attempt, and adds no wall time.
        _ <- everywhere(UsagePhase.Check, (1, 1, 0, 0), List((Some("unit"), AttemptState.Running, 1, 0)))
        _ <- figures(taskFilter, UsagePhase.Check).flatMap(value => assertIO(value._1 == ((1L, 1L, 0L, 0L)) && value._2 == List((Some("unit"), AttemptState.Running, 1L, 0L))))
        _ <- figures(taskFilter, UsagePhase.Work).flatMap(value => assertIO(value._1 == ((0L, 0L, 1L, 0L))))
        // (b) identical content is the stored span.
        _ <- usage.span(host, unit).flatMap(stored => assertIO(stored == unit))
        cursor2 <- usage.cursor(owner)
        _ <- assertIO(cursor2 == cursor1)
        // (a) a changed identity field is refused and nothing changes.
        changes = List(
          unit.copy(assignment = AssignmentId(UUID.randomUUID())), unit.copy(session = SessionId(UUID.randomUUID())), unit.copy(phase = UsagePhase.Integrate, check = None),
          unit.copy(check = Some("lint")), unit.copy(check = None), unit.copy(startedAt = 10001, finishedAt = 10001))
        _ <- ZIO.foreachDiscard(changes) { change => denied(usage.span(host, change))(fault => fault.isInstanceOf[Fault.Invalid] || fault.isInstanceOf[Fault.Missing]) }
        _ <- denied(usage.span(host, ended(unit.copy(check = Some("lint")), 20000, AttemptState.Completed)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, ended(unit.copy(startedAt = 9000), 20000, AttemptState.Completed)))(_.isInstanceOf[Fault.Invalid])
        cursor3 <- usage.cursor(owner)
        _ <- assertIO(cursor3 == cursor1)
        _ <- usage.span(host, unit).flatMap(stored => assertIO(stored == unit))
        _ <- everywhere(UsagePhase.Check, (1, 1, 0, 0), List((Some("unit"), AttemptState.Running, 1, 0)))
        // (c) the finish at 40 s happens once and adds 30 s.
        done = ended(unit, 40000, AttemptState.Completed)
        finishedSpan <- usage.span(host, done)
        cursor4 <- usage.cursor(owner)
        _ <- assertIO(finishedSpan == done && cursor4 == cursor1 + 1)
        _ <- everywhere(UsagePhase.Check, (1, 0, 0, 30000), List((Some("unit"), AttemptState.Completed, 1, 30000)))
        _ <- figures(taskFilter, UsagePhase.Check).flatMap(value => assertIO(value._1 == ((1L, 0L, 0L, 30000L))))
        // (d) the start replayed after the finish returns the stored span; (b) the finish replayed changes nothing.
        _ <- usage.span(host, unit).flatMap(stored => assertIO(stored == done))
        _ <- usage.span(host, done).flatMap(stored => assertIO(stored == done))
        cursor5 <- usage.cursor(owner)
        _ <- assertIO(cursor5 == cursor4)
        // (e) another finish time or state for a finished span is a conflict.
        _ <- denied(usage.span(host, ended(unit, 41000, AttemptState.Completed)))(_.isInstanceOf[Fault.Conflict])
        _ <- denied(usage.span(host, ended(unit, 40000, AttemptState.Failed)))(_.isInstanceOf[Fault.Conflict])
        _ <- denied(usage.span(host, ended(unit, 20000, AttemptState.Cancelled)))(_.isInstanceOf[Fault.Conflict])
        cursor6 <- usage.cursor(owner)
        _ <- assertIO(cursor6 == cursor4)
        _ <- usage.span(host, unit).flatMap(stored => assertIO(stored == done))
        _ <- everywhere(UsagePhase.Check, (1, 0, 0, 30000), List((Some("unit"), AttemptState.Completed, 1, 30000)))
        // A finished span with no earlier start is accepted, as before.
        direct = ended(started(work.assignment, UsagePhase.Check, 50000, Some("lint")), 52000, AttemptState.Failed)
        _ <- usage.span(host, direct).flatMap(stored => assertIO(stored == direct))
        _ <- everywhere(UsagePhase.Check, (2, 0, 0, 32000), List((Some("lint"), AttemptState.Failed, 1, 2000), (Some("unit"), AttemptState.Completed, 1, 30000)))
        // An integration and a combination live the same way, and a state other than Completed ends them too.
        integration = started(work.assignment, UsagePhase.Integrate, 60000, None)
        combination = started(work.assignment, UsagePhase.Combine, 61000, None)
        _ <- usage.span(host, integration)
        _ <- usage.span(host, combination)
        _ <- figures(UsageFilter.SessionOnly(owner.actor.session), UsagePhase.Integrate).flatMap(value => assertIO(value._1 == ((1L, 1L, 0L, 0L))))
        _ <- figures(UsageFilter.SessionOnly(owner.actor.session), UsagePhase.Combine).flatMap(value => assertIO(value._1 == ((1L, 1L, 0L, 0L))))
        _ <- usage.span(host, ended(integration, 90000, AttemptState.Unknown))
        _ <- usage.span(host, ended(combination, 70000, AttemptState.Cancelled))
        _ <- usage.span(host, combination).flatMap(stored => assertIO(stored.state == AttemptState.Cancelled && stored.finishedAt == 70000))
        _ <- figures(taskFilter, UsagePhase.Integrate).flatMap(value => assertIO(value._1 == ((1L, 0L, 0L, 30000L))))
        _ <- figures(taskFilter, UsagePhase.Combine).flatMap(value => assertIO(value._1 == ((1L, 0L, 0L, 9000L))))
      } yield ()
    }

    "report check runs per check name and state, equal to the Check phase row, with spans without a name as one group" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val host = collector(owner)
      def check(assignment: AssignmentId, name: Option[String], startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
        PhaseSpan(RequestId(UUID.randomUUID()), assignment, owner.actor.session, UsagePhase.Check, startedAt, finishedAt, state, name, None, None)
      def entries(report: CheckReport): List[(Option[String], AttemptState, Long, Long)] = report.checks.map(value => (value.check, value.state, value.runs, value.wallMillis))
      def phase(report: PhaseReport): (Long, Long) = report.phases.find(_.phase == UsagePhase.Check).map(value => (value.spans, value.wallMillis)).getOrElse((0L, 0L))
      def consistent(filter: UsageFilter): IO[Throwable, CheckReport] = for {
        report <- usage.checks(owner, filter)
        phases <- usage.phases(owner, filter)
        _ <- assertIO(!report.truncated && report.cursor == phases.cursor && (report.checks.map(_.runs).sum, report.checks.map(_.wallMillis).sum) == phase(phases))
      } yield report
      for {
        _ <- ledger.initialize(owner, "check runs")
        item <- task(ledger, owner, "Checked task")
        apart <- task(ledger, owner, "Another task")
        work <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 1000)
        separate <- phased(usage, owner, apart, Role.Worker, UsagePhase.Work, 1000)
        unit = check(work.assignment, Some("unit"), 4000, 4100, AttemptState.Completed)
        before <- usage.cursor(owner)
        _ <- denied(usage.span(host, unit.copy(phase = UsagePhase.Integrate)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, unit.copy(phase = UsagePhase.Combine)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, unit.copy(check = Some(""))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, unit.copy(check = Some("  \t"))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, unit.copy(check = Some("c" * 301))))(_.isInstanceOf[Fault.Invalid])
        refused <- usage.cursor(owner)
        _ <- assertIO(refused == before)
        _ <- usage.span(host, unit.copy(check = Some("c" * 300), id = RequestId(UUID.randomUUID()), startedAt = 0, finishedAt = 0))
        _ <- usage.span(host, unit)
        replayed <- usage.span(host, unit)
        _ <- assertIO(replayed == unit)
        _ <- usage.span(host, check(work.assignment, Some("unit"), 4100, 4150, AttemptState.Failed))
        _ <- usage.span(host, check(work.assignment, Some("lint"), 4150, 4180, AttemptState.Completed))
        _ <- usage.span(host, check(work.assignment, Some("lint"), 4180, 4200, AttemptState.Cancelled))
        _ <- usage.span(host, check(work.assignment, None, 5000, 5000, AttemptState.Unknown))
        _ <- usage.span(host, check(work.assignment, Some("unit"), 4200, 4210, AttemptState.Completed))
        _ <- usage.span(host, check(separate.assignment, Some("lint"), 100, 105, AttemptState.Unknown))
        _ <- usage.span(host, PhaseSpan(RequestId(UUID.randomUUID()), work.assignment, owner.actor.session, UsagePhase.Integrate, 6000, 8500, AttemptState.Cancelled, None, None, None))
        _ <- usage.span(host, check(work.assignment, Some("unit"), 1, 8, AttemptState.Completed).copy(session = other.actor.session))
        long = Some("c" * 300)
        session <- consistent(UsageFilter.SessionOnly(owner.actor.session))
        _ <- assertIO(entries(session) == List((None, AttemptState.Unknown, 1L, 0L), (long, AttemptState.Completed, 1L, 0L),
          (Some("lint"), AttemptState.Cancelled, 1L, 20L), (Some("lint"), AttemptState.Completed, 1L, 30L), (Some("lint"), AttemptState.Unknown, 1L, 5L),
          (Some("unit"), AttemptState.Completed, 2L, 110L), (Some("unit"), AttemptState.Failed, 1L, 50L)))
        byTask <- consistent(UsageFilter.TaskOnly(item))
        _ <- assertIO(entries(byTask) == List((None, AttemptState.Unknown, 1L, 0L), (long, AttemptState.Completed, 1L, 0L),
          (Some("lint"), AttemptState.Cancelled, 1L, 20L), (Some("lint"), AttemptState.Completed, 1L, 30L),
          (Some("unit"), AttemptState.Completed, 3L, 117L), (Some("unit"), AttemptState.Failed, 1L, 50L)))
        apartOnly <- consistent(UsageFilter.TaskOnly(apart))
        _ <- assertIO(entries(apartOnly) == List((Some("lint"), AttemptState.Unknown, 1L, 5L)))
        foreign <- consistent(UsageFilter.SessionOnly(other.actor.session))
        _ <- assertIO(entries(foreign) == List((Some("unit"), AttemptState.Completed, 1L, 7L)))
        project <- consistent(UsageFilter.ProjectAll())
        _ <- assertIO(entries(project).map(_._1).distinct == List(None, long, Some("lint"), Some("unit")) && entries(project).map(_._3).sum == 9 && entries(project).map(_._4).sum == 222)
        _ <- denied(usage.checks(owner, UsageFilter.TaskOnly(item.copy(project = ProjectId(UUID.randomUUID())))))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "truncate check groups by UTF-8 byte order of the name" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      for {
        _ <- ledger.initialize(owner, "check order")
        item <- task(ledger, owner, "Ordered task")
        work <- phased(usage, owner, item, Role.Worker, UsagePhase.Work, 1000)
        names = (0 until 199).map(index => f"a$index%03d").toList ++ List("\uE000", new String(Character.toChars(0x10000)))
        _ <- ZIO.foreachDiscard(names)(name => usage.span(host, PhaseSpan(RequestId(UUID.randomUUID()), work.assignment, owner.actor.session, UsagePhase.Check, 10, 20, AttemptState.Completed, Some(name), None, None)))
        report <- usage.checks(owner, UsageFilter.ProjectAll())
        _ <- assertIO(report.truncated && report.checks.size == 200 && report.checks.last.check == Some("\uE000"))
      } yield ()
    }

    "register waits on the operator as spans, refresh them while they run and read them by their blocked tasks" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      val governing = assignment(owner, Set.empty, Attribution.Unattributed, None).copy(evaluation = None)
      val elsewhere = governing.copy(id = AssignmentId(UUID.randomUUID()))
      def wait(id: RequestId, item: ItemId, blocked: Set[ItemId], start: Long, finish: Long, state: AttemptState, observation: Long, truncated: Boolean = false): PhaseSpan =
        PhaseSpan(id, governing.id, owner.actor.session, UsagePhase.Await, start, finish, state, None, Some(AwaitedSpan(item, blocked, truncated, observation)), None)
      def observation(span: PhaseSpan, number: Long): PhaseSpan = span.copy(await = span.await.map(_.copy(observation = number)))
      def blockedBy(span: PhaseSpan): Set[ItemId] = span.await.get.blocked
      def row(report: PhaseReport): Option[PhaseUsage] = report.phases.find(_.phase == UsagePhase.Await)
      def figures(report: PhaseReport): Option[(Long, Long, Long, Long)] = row(report).map(value => (value.attempts, value.spans, value.runningSpans, value.wallMillis))
      for {
        _ <- ledger.initialize(owner, "waits")
        blocked <- task(ledger, owner, "Blocked task")
        other <- task(ledger, owner, "Another task")
        question <- task(ledger, owner, "Awaited stand-in 1")
        second <- task(ledger, owner, "Awaited stand-in 2")
        third <- task(ledger, owner, "Awaited stand-in 3")
        _ <- usage.assign(host, governing)
        _ <- usage.assign(host, elsewhere)
        // First registration.
        id = RequestId(UUID.randomUUID())
        first = wait(id, question, Set.empty, 1000, 1000, AttemptState.Running, 1)
        _ <- denied(usage.start(host, Attempt(AttemptId(UUID.randomUUID()), governing.id, None, owner.actor.session, Role.Governor, Harness.Claude, "p", "m", "c", 1000, UsagePhase.Await, None)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(owner, first))(_.isInstanceOf[Fault.Denied])
        _ <- denied(usage.span(host, first.copy(assignment = AssignmentId(UUID.randomUUID()))))(_.isInstanceOf[Fault.Missing])
        _ <- denied(usage.span(host, first.copy(finishedAt = 999)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, first.copy(await = None)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, first.copy(phase = UsagePhase.Check, check = Some("unit"))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, first.copy(phase = UsagePhase.Check, finishedAt = 1100, state = AttemptState.Running, await = None)))(_.isInstanceOf[Fault.Invalid])
        many = (1001L to 1201L).map(number => ItemId(owner.project, Ledger.Tasks, number)).toSet
        _ <- denied(usage.span(host, first.copy(await = first.await.map(_.copy(blocked = many)))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, first.copy(await = first.await.map(_.copy(observation = 0)))))(_.isInstanceOf[Fault.Invalid])
        cursor0 <- usage.cursor(owner)
        registered <- usage.span(host, first)
        cursor1 <- usage.cursor(owner)
        _ <- assertIO(registered == first && cursor1 == cursor0 + 1)
        full <- usage.span(host, wait(RequestId(UUID.randomUUID()), second, many.take(200), 1500, 1500, AttemptState.Running, 1, truncated = true))
        _ <- assertIO(blockedBy(full).size == 200)
        // Refreshing: the finish time and the blocked items grow, a replay changes nothing.
        refreshed <- usage.span(host, wait(id, question, Set(blocked), 1000, 1600, AttemptState.Running, 2))
        cursor2 <- usage.cursor(owner)
        _ <- assertIO(refreshed.finishedAt == 1600 && blockedBy(refreshed) == Set(blocked) && cursor2 == cursor1 + 2)
        latest = wait(id, question, Set(blocked), 1000, 2200, AttemptState.Running, 3)
        _ <- usage.span(host, latest)
        cursor3 <- usage.cursor(owner)
        // Fixed fields, with a higher, the same and a lower observation number.
        changes = List(
          first.copy(assignment = elsewhere.id), first.copy(session = SessionId(UUID.randomUUID())), first.copy(phase = UsagePhase.Check),
          wait(id, second, Set(blocked), 1000, 2200, AttemptState.Running, 3), latest.copy(startedAt = 999))
        _ <- ZIO.foreachDiscard(changes) { change =>
          denied(usage.span(host, observation(change, 4)))(_.isInstanceOf[Fault.Invalid]) *> usage.span(host, observation(latest, 1)).flatMap(stored => assertIO(stored == latest))
        }
        _ <- ZIO.foreachDiscard(List(3L, 1L)) { number => denied(usage.span(host, observation(latest.copy(assignment = elsewhere.id), number)))(_.isInstanceOf[Fault.Invalid]) }
        cursor4 <- usage.cursor(owner)
        _ <- assertIO(cursor4 == cursor3)
        // Same observation: identical content returns the stored span; other content is a conflict.
        same <- usage.span(host, latest)
        _ <- denied(usage.span(host, latest.copy(finishedAt = 2300)))(_.isInstanceOf[Fault.Conflict])
        _ <- denied(usage.span(host, latest.copy(await = latest.await.map(_.copy(blocked = Set.empty)))))(_.isInstanceOf[Fault.Conflict])
        // Lower observation: the stored span, not a conflict.
        stale <- usage.span(host, wait(id, question, Set.empty, 1000, 1000, AttemptState.Running, 1))
        _ <- assertIO(same == latest && stale == latest)
        cursor5 <- usage.cursor(owner)
        _ <- assertIO(cursor5 == cursor3)
        // A higher observation that lowers the finish time, drops a blocked item or clears the flag is refused.
        _ <- denied(usage.span(host, wait(id, question, Set(blocked), 1000, 2199, AttemptState.Running, 4)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, wait(id, question, Set.empty, 1000, 2300, AttemptState.Running, 4)))(_.isInstanceOf[Fault.Invalid])
        cursor6 <- usage.cursor(owner)
        _ <- assertIO(cursor6 == cursor3)
        // A truncated observation sets the flag for good: a later one cannot clear it.
        flagged = wait(id, question, Set(blocked), 1000, 2200, AttemptState.Running, 4, truncated = true)
        _ <- usage.span(host, flagged)
        cursor7 <- usage.cursor(owner)
        _ <- denied(usage.span(host, observation(flagged, 5).copy(await = flagged.await.map(_.copy(truncated = false, observation = 5)))))(_.isInstanceOf[Fault.Invalid])
        _ <- assertIO(cursor7 == cursor6 + 1)
        // A terminal span is immutable: a higher observation is refused, a lower one returns it.
        ended = wait(id, question, Set(blocked), 1000, 2500, AttemptState.Completed, 5, truncated = true)
        _ <- usage.span(host, ended)
        cursor8 <- usage.cursor(owner)
        _ <- denied(usage.span(host, observation(ended, 6).copy(state = AttemptState.Cancelled)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(usage.span(host, observation(ended, 6)))(_.isInstanceOf[Fault.Invalid])
        olderTerminal <- usage.span(host, observation(latest, 2))
        replayTerminal <- usage.span(host, ended)
        cursor9 <- usage.cursor(owner)
        _ <- assertIO(olderTerminal == ended && replayTerminal == ended && cursor9 == cursor8 && cursor8 == cursor7 + 1)
        // The report: one Running wait refreshed twice, one ended, one with the truncation flag.
        running = wait(RequestId(UUID.randomUUID()), second, Set.empty, 5000, 5000, AttemptState.Running, 1)
        _ <- usage.span(host, running)
        _ <- usage.span(host, wait(running.id, second, Set(blocked), 5000, 5400, AttemptState.Running, 2))
        _ <- usage.span(host, wait(running.id, second, Set(blocked), 5000, 5900, AttemptState.Running, 3))
        _ <- usage.span(host, wait(running.id, second, Set(blocked), 5000, 5900, AttemptState.Running, 2))
        completed = wait(RequestId(UUID.randomUUID()), third, Set(blocked), 7000, 7500, AttemptState.Completed, 1)
        _ <- usage.span(host, completed)
        truncated = wait(RequestId(UUID.randomUUID()), third, Set.empty, 8000, 8100, AttemptState.Cancelled, 1, truncated = true)
        _ <- usage.span(host, truncated)
        byProject <- usage.phases(owner, UsageFilter.ProjectAll())
        bySession <- usage.phases(owner, UsageFilter.SessionOnly(owner.actor.session))
        byBlocked <- usage.phases(owner, UsageFilter.TaskOnly(blocked))
        byOther <- usage.phases(owner, UsageFilter.TaskOnly(other))
        // The wait of `question` (1500 ms), the full one (0), the running one (900), the ended (500) and the truncated (100).
        _ <- assertIO(figures(byProject) == Some((0L, 5L, 2L, 1500L + 0L + 900L + 500L + 100L)))
        _ <- assertIO(figures(bySession) == figures(byProject))
        _ <- assertIO(figures(byBlocked) == Some((0L, 3L, 1L, 1500L + 900L + 500L)))
        _ <- assertIO(row(byOther).isEmpty)
        _ <- assertIO(byProject.truncatedWaits == 0 && bySession.truncatedWaits == 0)
        // The truncated waits that do not name the Task: the full one and the truncated one, and the first, flagged, names it.
        _ <- assertIO(byBlocked.truncatedWaits == 2 && byOther.truncatedWaits == 3)
        again <- usage.phases(owner, UsageFilter.SessionOnly(owner.actor.session))
        _ <- assertIO(figures(again) == figures(bySession) && again.cursor == bySession.cursor)
      } yield ()
    }

    "T93: record the unit that requested a span as its fixed parent, register a span parent before or after its child and refuse every change" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val host = collector(owner)
      def span(assignment: AssignmentId, phase: UsagePhase, at: Long, name: Option[String], parent: Option[SpanParent]): PhaseSpan =
        PhaseSpan(RequestId(UUID.randomUUID()), assignment, owner.actor.session, phase, at, at, AttemptState.Running, name, None, parent)
      def ended(value: PhaseSpan, at: Long, state: AttemptState): PhaseSpan = value.copy(finishedAt = at, state = state)
      // The stored span and the audit cursor, read by replaying the registration that is stored.
      def unchanged(stored: PhaseSpan, cursor: Long): IO[Throwable, Unit] = for {
        read <- usage.span(host, stored)
        after <- usage.cursor(owner)
        _ <- assertIO(read == stored && after == cursor)
      } yield ()
      def refused(value: PhaseSpan, stored: PhaseSpan)(expected: Fault => Boolean): IO[Throwable, Unit] = for {
        before <- usage.cursor(owner)
        _ <- denied(usage.span(host, value))(expected)
        _ <- unchanged(stored, before)
      } yield ()
      for {
        _ <- ledger.initialize(owner, "span parent")
        item <- task(ledger, owner, "Parented task")
        governing <- phased(usage, owner, item, Role.Governor, UsagePhase.Govern, 1000)
        reviewer <- phased(usage, owner, item, Role.Reviewer, UsagePhase.Review, 2000)
        // Without a parent, and as stored.
        plain = span(governing.assignment, UsagePhase.Check, 3000, Some("plain"), None)
        _ <- usage.span(host, plain).flatMap(stored => assertIO(stored == plain && stored.parent.isEmpty))
        // An Integrate span whose parent is the governing attempt, and a Check whose parent is that Integrate span.
        integration = span(governing.assignment, UsagePhase.Integrate, 10000, None, Some(SpanParent.Attempt(governing.id)))
        check = span(governing.assignment, UsagePhase.Check, 11000, Some("verify"), Some(SpanParent.Span(integration.id)))
        // The child is registered first: its parent span is not registered yet.
        stored <- usage.span(host, check)
        _ <- assertIO(stored == check && stored.parent.contains(SpanParent.Span(integration.id)))
        stored <- usage.span(host, integration)
        _ <- assertIO(stored == integration && stored.parent.contains(SpanParent.Attempt(governing.id)))
        // A check the reviewer declared names the reviewer's attempt.
        declared = span(reviewer.assignment, UsagePhase.Check, 12000, Some("declared"), Some(SpanParent.Attempt(reviewer.id)))
        _ <- usage.span(host, declared).flatMap(value => assertIO(value == declared))
        // The finish keeps the parent; the start replayed after it returns the stored span.
        done = ended(check, 15000, AttemptState.Completed)
        finishedSpan <- usage.span(host, done)
        _ <- assertIO(finishedSpan == done && finishedSpan.parent == check.parent)
        _ <- usage.span(host, check).flatMap(value => assertIO(value == done && value.parent == check.parent))
        _ <- usage.span(host, ended(integration, 16000, AttemptState.Completed)).flatMap(value => assertIO(value.parent == integration.parent))
        // Registration refusals leave the stored span and the cursor as they were.
        unknown = span(governing.assignment, UsagePhase.Check, 20000, Some("orphan"), Some(SpanParent.Attempt(AttemptId(UUID.randomUUID()))))
        selfId = RequestId(UUID.randomUUID())
        itself = span(governing.assignment, UsagePhase.Check, 21000, Some("self"), None).copy(id = selfId, parent = Some(SpanParent.Span(selfId)))
        beforeNew <- usage.cursor(owner)
        _ <- denied(usage.span(host, unknown))(_.isInstanceOf[Fault.Missing])
        _ <- denied(usage.span(host, itself))(_.isInstanceOf[Fault.Invalid])
        _ <- usage.cursor(owner).flatMap(after => assertIO(after == beforeNew))
        // A parent that is an attempt of another project is not a registered attempt of this one.
        other = scope()
        foreign <- ledger.initialize(other, "span parent elsewhere") *> task(ledger, other, "Elsewhere").flatMap(phased(usage, other, _, Role.Governor, UsagePhase.Govern, 1000))
        _ <- denied(usage.span(host, span(governing.assignment, UsagePhase.Check, 22000, Some("foreign"), Some(SpanParent.Attempt(foreign.id)))))(_.isInstanceOf[Fault.Missing])
        _ <- usage.cursor(owner).flatMap(after => assertIO(after == beforeNew))
        // Another parent, a parent where none is stored and no parent where one is stored.
        _ <- refused(done.copy(parent = Some(SpanParent.Attempt(governing.id))), done)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(done.copy(parent = Some(SpanParent.Span(declared.id))), done)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(done.copy(parent = None), done)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(ended(plain, 4000, AttemptState.Completed).copy(parent = Some(SpanParent.Attempt(governing.id))), plain)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(plain.copy(parent = Some(SpanParent.Span(integration.id))), plain)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(declared.copy(parent = Some(SpanParent.Attempt(governing.id))), declared)(_.isInstanceOf[Fault.Invalid])
        _ <- refused(ended(declared, 13000, AttemptState.Failed).copy(parent = None), declared)(_.isInstanceOf[Fault.Invalid])
        // A wait is a span with a parent too, and the parent is as fixed as its awaited item.
        wait = PhaseSpan(RequestId(UUID.randomUUID()), governing.assignment, owner.actor.session, UsagePhase.Await, 30000, 30000, AttemptState.Running, None,
          Some(AwaitedSpan(item, Set(item), false, 1)), Some(SpanParent.Attempt(governing.id)))
        _ <- usage.span(host, wait).flatMap(value => assertIO(value == wait))
        later = wait.copy(finishedAt = 31000, await = wait.await.map(_.copy(observation = 2)))
        _ <- usage.span(host, later).flatMap(value => assertIO(value == later && value.parent == wait.parent))
        _ <- refused(later.copy(await = later.await.map(_.copy(observation = 3)), parent = None), later)(_.isInstanceOf[Fault.Invalid])
        // Parents do not change what the phase report and the check report hold.
        phases <- usage.phases(owner, UsageFilter.SessionOnly(owner.actor.session))
        checks <- usage.checks(owner, UsageFilter.SessionOnly(owner.actor.session))
        row = phases.phases.find(_.phase == UsagePhase.Check).map(value => (value.spans, value.runningSpans, value.wallMillis))
        _ <- assertIO(row.contains((3L, 2L, 4000L)))
        _ <- assertIO(checks.checks.map(value => (value.check, value.state, value.runs, value.wallMillis)).toSet == Set(
          (Some("plain"), AttemptState.Running, 1L, 0L), (Some("verify"), AttemptState.Completed, 1L, 4000L), (Some("declared"), AttemptState.Running, 1L, 0L)))
      } yield ()
    }

    "bound phase cost groups and report the truncation" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- ledger.initialize(owner, "bounded phase costs")
        run <- start(usage, owner, assignment(owner, Set.empty, Attribution.Unattributed, None), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        _ <- ZIO.foreachDiscard(1 to 201) { position =>
          usage.ingest(collector(owner), upload(run, position, CounterScope.Increment, counts(1, 0), price("0.01").copy(pricingVersion = Some(f"price-$position%04d"))))
        }
        report <- usage.phases(owner, UsageFilter.ProjectAll())
        _ <- assertIO(report.costsTruncated && report.phases.map(_.phase) == List(UsagePhase.Work) && report.phases.head.totals.total.known == 201)
        _ <- assertIO(report.phases.head.costs.map(_.group.pricingVersion.get) == (1 to 200).map(position => f"price-$position%04d").toList)
        _ <- assertIO(Wire.encode(PhaseReport_JsonCodec, report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ReadPage.MaxBytes)
      } yield ()
    }

    "bound shared assignment references while accounting across multiple meter pages" in { (usage: UsageService[IO], ledger: LedgerService[IO]) =>
      val owner = scope()
      val maxReferences = 200
      for {
        _ <- ledger.initialize(owner, "bounded summaries")
        one <- task(ledger, owner, "One")
        two <- task(ledger, owner, "Two")
        _ <- ZIO.foreachDiscard(1 to maxReferences + 1) { _ =>
          start(usage, owner, assignment(owner, Set(one, two), Attribution.Shared, Some(UUID.randomUUID())), CounterScope.Increment, UsageMath.zeroCounts, UsageMath.unknownMoney)
        }
        report <- usage.summary(owner, UsageFilter.TaskOnly(one))
        _ <- assertIO(report.shared.total.unknown == maxReferences + 1 && report.incompleteMeters == maxReferences + 1)
        _ <- assertIO(report.sharedAssignments.size == maxReferences && report.sharedAssignmentsTruncated)
      } yield ()
    }
  }

  private final class Tick(var now: Long) extends java.time.Clock {
    override def getZone: java.time.ZoneId = java.time.ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): java.time.Clock = this
    override def instant(): java.time.Instant = java.time.Instant.ofEpochMilli(now)
    override def millis(): Long = now
  }
  private def ms(seconds: Long): Long = seconds * 1000L
  private def timeSplit(running: Long, waiting: Long, governing: Long, unobserved: Long, idle: Long): TimeSplit =
    TimeSplit(ms(running), ms(waiting), ms(governing), ms(unobserved), ms(idle))
  private def splitSum(split: TimeSplit): Long = split.running + split.waiting + split.governing + split.unobserved + split.idle
  private def windowLength(report: UnitReport): Long = report.window.fold(0L)(w => w.until - w.from)
  private def keyOf(attempt: Attempt): UnitKey = UnitKey.Attempt(attempt.id)
  private def keyOf(span: PhaseSpan): UnitKey = UnitKey.Span(span.id)
  private def unitId(key: UnitKey): String = key match {
    case UnitKey.Attempt(id) => "A:" + id.value
    case UnitKey.Span(id) => "S:" + id.value
  }

  /** A project with a settable clock: the service under test is built over the injected repository, so the fixture's clock is not the DI one. */
  private final class Rig(repository: UsageRepository[IO], ledger: LedgerService[IO]) {
    val owner: Scope = scope()
    val clock = new Tick(0)
    val usage: UsageService[IO] = new UsageService.Impl[IO](repository, ledger, clock)
    val session: SessionId = owner.actor.session
    val host: Scope = collector(owner)
    val work: Assignment = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None)
    def init(): IO[Throwable, Unit] = ledger.initialize(owner, "unit times").flatMap(_ => usage.assign(host, work)).unit
    def attempt(start: Long, phase: UsagePhase = UsagePhase.Work, role: Role = Role.Worker, parent: Option[Attempt] = None, attached: Boolean = false,
      assignment: Assignment = work, in: Option[SessionId] = None): Attempt =
      Attempt(AttemptId(UUID.randomUUID()), assignment.id, parent.map(_.id), in.getOrElse(session), role, Harness.Codex, "p", "m",
        if (attached) AttemptObservation.AttachedGovernorCollector else "fixture-v1", ms(start), phase, None)
    def governing(start: Long, attached: Boolean): Attempt = attempt(start, UsagePhase.Govern, Role.Governor, None, attached)
    def begin(value: Attempt): IO[Throwable, Attempt] = usage.start(host, value)
    def end(value: Attempt, at: Long, state: AttemptState = AttemptState.Completed): IO[Throwable, AttemptOutcome] =
      usage.finish(host, AttemptOutcome(RequestId(UUID.randomUUID()), value.id, state, ms(at), Nil, None))
    /** An attempt that ran from `start` to `finish`. */
    def ran(start: Long, finish: Long, phase: UsagePhase = UsagePhase.Work, role: Role = Role.Worker, parent: Option[Attempt] = None, assignment: Assignment = work,
      in: Option[SessionId] = None): IO[Throwable, Attempt] = {
      val value = attempt(start, phase, role, parent, false, assignment, in)
      begin(value).flatMap(_ => end(value, finish)).as(value)
    }
    def span(phase: UsagePhase, start: Long, finish: Long, state: AttemptState = AttemptState.Completed, parent: Option[SpanParent] = None, check: Option[String] = None,
      await: Option[AwaitedSpan] = None, assignment: Assignment = work, in: Option[SessionId] = None): PhaseSpan =
      PhaseSpan(RequestId(UUID.randomUUID()), assignment.id, in.getOrElse(session), phase, ms(start), ms(finish), state, check, await, parent)
    def put(value: PhaseSpan): IO[Throwable, PhaseSpan] = usage.span(host, value)
    def read(filter: UsageFilter = UsageFilter.ProjectAll(), window: Option[(Long, Long)] = None, limit: Int = 200, after: Option[UnitContinuation] = None): IO[Throwable, UnitReport] =
      usage.units(owner, filter, window.map { case (from, until) => UnitWindowRequest(ms(from), ms(until)) }, limit, after)
    def unit(report: UnitReport, key: UnitKey): UnitTime = report.units.find(_.key == key).getOrElse(throw new AssertionError("Unit not listed: " + key))
    def category(report: UnitReport, kind: UnitKind, phase: UsagePhase, check: Option[String] = None): UnitCategoryTime =
      report.categories.find(c => c.category == UnitCategory(kind, phase, check)).getOrElse(throw new AssertionError("Category missing"))
  }

  protected def adapter: String

  "Unit times (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "S1: an attached governing attempt with one Work attempt leaves a tail unobserved" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = true))
        _ <- rig.ran(10, 30, parent = Some(governing))
        _ = rig.clock.now = ms(100)
        report <- rig.read(window = Some((0, 60)))
        _ <- assertIO(report.split == timeSplit(20, 0, 10, 30, 0) && splitSum(report.split) == windowLength(report) && windowLength(report) == ms(60))
        _ <- assertIO(report.window.contains(UnitWindow(0, ms(60), true, true)) && report.lastObserved.contains(ms(30)) && report.readTime == ms(100))
      } yield ()
    }
    "S2 and S3: a managed governing attempt is observed to the read time or to its outcome" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = false))
        _ <- rig.ran(10, 30, parent = Some(governing))
        _ = rig.clock.now = ms(60)
        managed <- rig.read()
        _ <- assertIO(managed.split == timeSplit(20, 0, 40, 0, 0) && splitSum(managed.split) == windowLength(managed))
        _ <- assertIO(managed.window.contains(UnitWindow(0, ms(60), false, false)))
        _ <- rig.end(governing, 50)
        closed <- rig.read(window = Some((0, 60)))
        _ <- assertIO(closed.split == timeSplit(20, 0, 30, 0, 10) && splitSum(closed.split) == windowLength(closed))
      } yield ()
    }
    "S4 and S5: a running host span counts to the read time and an interrupted one ends at recovery" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = true))
        running = rig.span(UsagePhase.Check, 10, 10, AttemptState.Running, check = Some("cq-fast"))
        _ <- rig.put(running)
        _ = rig.clock.now = ms(25)
        first <- rig.read()
        _ <- assertIO(first.window.contains(UnitWindow(0, ms(25), true, false)) && first.split == timeSplit(15, 0, 10, 0, 0) && splitSum(first.split) == windowLength(first))
        _ <- assertIO(rig.unit(first, keyOf(running)).duration == ms(15) && rig.unit(first, keyOf(running)).state == AttemptState.Running && rig.unit(first, keyOf(running)).finishedAt.isEmpty)
        _ = rig.clock.now = ms(100)
        later <- rig.read()
        _ <- assertIO(later.split == timeSplit(90, 0, 10, 0, 0) && splitSum(later.split) == windowLength(later))
        _ <- rig.put(running.copy(state = AttemptState.Unknown, finishedAt = ms(200)))
        _ <- rig.end(governing, 200, AttemptState.Unknown)
        recovered <- rig.read(window = Some((0, 200)))
        check = rig.unit(recovered, keyOf(running))
        _ <- assertIO(check.duration == ms(190) && check.state == AttemptState.Unknown && check.finishedAt.contains(ms(200)))
        _ <- assertIO(recovered.split == timeSplit(190, 0, 10, 0, 0) && splitSum(recovered.split) == windowLength(recovered))
      } yield ()
    }
    "S6: work whose start has not reached the server is absent until it is delivered" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        _ <- rig.begin(rig.governing(0, attached = true))
        _ <- rig.put(rig.span(UsagePhase.Integrate, 10, 10))
        _ = rig.clock.now = ms(100)
        before <- rig.read(window = Some((0, 25)))
        _ <- assertIO(before.split == timeSplit(0, 0, 10, 15, 0) && splitSum(before.split) == windowLength(before))
        _ <- rig.put(rig.span(UsagePhase.Check, 10, 40, check = Some("cq-fast")))
        after <- rig.read(window = Some((0, 25)))
        _ <- assertIO(after.split == timeSplit(15, 0, 10, 0, 0) && splitSum(after.split) == windowLength(after))
      } yield ()
    }
    "S7 and parallel units: own time is structural and category totals overlap" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = false))
        review <- rig.ran(0, 100, UsagePhase.Review, Role.Reviewer, Some(governing))
        check = rig.span(UsagePhase.Check, 20, 60, parent = Some(SpanParent.Attempt(review.id)), check = Some("cq-fast"))
        _ <- rig.put(check)
        a <- rig.ran(0, 60)
        b <- rig.ran(0, 60)
        c1 <- rig.ran(10, 50, parent = Some(governing))
        c2 <- rig.ran(30, 70, parent = Some(governing))
        _ <- rig.end(governing, 100)
        report <- rig.read()
        _ <- assertIO(rig.unit(report, keyOf(review)).duration == ms(100) && rig.unit(report, keyOf(review)).ownTime == ms(60))
        _ <- assertIO(rig.unit(report, keyOf(check)).duration == ms(40) && rig.unit(report, keyOf(check)).ownTime == ms(40))
        reviews = rig.category(report, UnitKind.Attempt, UsagePhase.Review)
        _ <- assertIO(reviews.total == ms(100) && reviews.ownTotal == ms(60) && reviews.units == 1)
        _ <- assertIO(rig.unit(report, keyOf(governing)).duration == ms(100) && rig.unit(report, keyOf(governing)).ownTime == 0)
        works = rig.category(report, UnitKind.Attempt, UsagePhase.Work)
        _ <- assertIO(works.total == ms(60 + 60 + 40 + 40) && works.units == 4)
        _ <- assertIO(rig.category(report, UnitKind.Span, UsagePhase.Check, Some("cq-fast")).total == ms(40))
        _ <- assertIO(rig.unit(report, keyOf(c1)).parent.contains(keyOf(governing)) && rig.unit(report, keyOf(a)).ownTime == ms(60))
        _ <- assertIO(report.split.running == ms(100) && splitSum(report.split) == windowLength(report))
      } yield ()
    }
    "own time of a parent is its duration minus the union of parallel children" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = false))
        _ <- rig.ran(10, 50, parent = Some(governing))
        _ <- rig.ran(30, 70, parent = Some(governing))
        _ <- rig.end(governing, 100)
        report <- rig.read()
        _ <- assertIO(rig.unit(report, keyOf(governing)).ownTime == ms(40))
      } yield ()
    }
    "a closed wait is waiting, and a running wait is observed to its last observation" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      val awaited = ItemId(rig.owner.project, Ledger.Questions, 1)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = false))
        _ <- rig.put(rig.span(UsagePhase.Await, 20, 50, await = Some(AwaitedSpan(awaited, Set.empty, false, 1)), parent = Some(SpanParent.Attempt(governing.id))))
        _ <- rig.ran(30, 40, parent = Some(governing))
        _ <- rig.end(governing, 60)
        report <- rig.read()
        _ <- assertIO(report.split == timeSplit(10, 20, 30, 0, 0) && splitSum(report.split) == windowLength(report))
        other = new Rig(repository, ledger)
        _ <- other.init()
        attached <- other.begin(other.governing(0, attached = true))
        _ <- other.put(other.span(UsagePhase.Await, 20, 40, AttemptState.Running, await = Some(AwaitedSpan(ItemId(other.owner.project, Ledger.Questions, 1), Set.empty, false, 1))))
        _ = other.clock.now = ms(100)
        beyond <- other.read(window = Some((0, 60)))
        _ <- assertIO(beyond.split == timeSplit(0, 20, 20, 20, 0) && splitSum(beyond.split) == windowLength(beyond) && beyond.window.exists(_.open))
      } yield ()
    }
    "each filter returns exactly its units, lists a parent outside the scope as stored and keeps the existing reads" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      val cohortOne = UUID.randomUUID()
      val cohortTwo = UUID.randomUUID()
      val sessionTwo = SessionId(UUID.randomUUID())
      for {
        _ <- rig.init()
        t1 <- task(ledger, rig.owner, "One")
        t2 <- task(ledger, rig.owner, "Two")
        t3 <- task(ledger, rig.owner, "Three")
        t4 <- task(ledger, rig.owner, "Four")
        shared = Assignment(AssignmentId(UUID.randomUUID()), rig.owner.project, Set(t1, t2), Attribution.Shared, Some(cohortOne), Some(EvaluationScope("runA", "s1", false)))
        direct = Assignment(AssignmentId(UUID.randomUUID()), rig.owner.project, Set(t3), Attribution.Direct, Some(cohortTwo), Some(EvaluationScope("runA", "s2", false)))
        other = Assignment(AssignmentId(UUID.randomUUID()), rig.owner.project, Set.empty, Attribution.Unattributed, None, Some(EvaluationScope("runB", "s1", false)))
        _ <- rig.usage.assign(rig.host, shared)
        _ <- rig.usage.assign(rig.host, direct)
        _ <- rig.usage.assign(rig.host, other)
        g <- rig.begin(rig.attempt(0, UsagePhase.Govern, Role.Governor, None, false, other))
        a1 <- rig.ran(10, 30, parent = Some(g), assignment = shared)
        a2 <- rig.ran(20, 40, parent = Some(g), assignment = shared)
        d1 <- rig.ran(5, 15, assignment = direct, in = Some(sessionTwo))
        _ <- rig.end(g, 100)
        chk = rig.span(UsagePhase.Check, 40, 45, check = Some("cq-fast"), assignment = shared)
        _ <- rig.put(chk)
        w1 = rig.span(UsagePhase.Await, 50, 60, await = Some(AwaitedSpan(ItemId(rig.owner.project, Ledger.Questions, 1), Set(t1), false, 1)), assignment = other)
        w2 = rig.span(UsagePhase.Await, 70, 80, await = Some(AwaitedSpan(ItemId(rig.owner.project, Ledger.Questions, 2), Set(t3), false, 1)), assignment = other)
        _ <- rig.put(w1)
        _ <- rig.put(w2)
        _ = rig.clock.now = ms(200)
        keys = (filter: UsageFilter) => rig.read(filter).map(_.units.map(_.key).toSet)
        everything = Set(keyOf(g), keyOf(a1), keyOf(a2), keyOf(d1), keyOf(chk), keyOf(w1), keyOf(w2))
        project <- keys(UsageFilter.ProjectAll())
        one <- keys(UsageFilter.TaskOnly(t1))
        two <- keys(UsageFilter.TaskOnly(t2))
        three <- keys(UsageFilter.TaskOnly(t3))
        first <- keys(UsageFilter.CohortOnly(cohortOne))
        second <- keys(UsageFilter.CohortOnly(cohortTwo))
        sessionOne <- keys(UsageFilter.SessionOnly(rig.session))
        sessionB <- keys(UsageFilter.SessionOnly(sessionTwo))
        runA <- keys(UsageFilter.EvaluationOnly("runA", None))
        runAs1 <- keys(UsageFilter.EvaluationOnly("runA", Some("s1")))
        runAs2 <- keys(UsageFilter.EvaluationOnly("runA", Some("s2")))
        runB <- keys(UsageFilter.EvaluationOnly("runB", None))
        _ <- assertIO(project == everything)
        _ <- assertIO(one == Set(keyOf(a1), keyOf(a2), keyOf(chk), keyOf(w1)))
        _ <- assertIO(two == Set(keyOf(a1), keyOf(a2), keyOf(chk)))
        _ <- assertIO(three == Set(keyOf(d1), keyOf(w2)))
        _ <- assertIO(first == Set(keyOf(a1), keyOf(a2), keyOf(chk)) && second == Set(keyOf(d1)))
        _ <- assertIO(sessionOne == everything - keyOf(d1) && sessionB == Set(keyOf(d1)))
        _ <- assertIO(runA == Set(keyOf(a1), keyOf(a2), keyOf(chk), keyOf(d1)) && runAs1 == Set(keyOf(a1), keyOf(a2), keyOf(chk)) && runAs2 == Set(keyOf(d1)))
        _ <- assertIO(runB == Set(keyOf(g), keyOf(w1), keyOf(w2)))
        taskReport <- rig.read(UsageFilter.TaskOnly(t1))
        _ <- assertIO(rig.unit(taskReport, keyOf(a1)).parent.contains(keyOf(g)) && rig.unit(taskReport, keyOf(a1)).ownTime == rig.unit(taskReport, keyOf(a1)).duration)
        _ <- assertIO(rig.unit(taskReport, keyOf(a1)).duration == ms(20))
        empty <- rig.read(UsageFilter.TaskOnly(t4))
        _ <- assertIO(empty.window.isEmpty && empty.lastObserved.isEmpty && empty.units.isEmpty && empty.categories.isEmpty && empty.split == timeSplit(0, 0, 0, 0, 0))
        // the existing reads still return what the test computes from its records
        phases <- rig.usage.phases(rig.owner, UsageFilter.TaskOnly(t1))
        _ <- assertIO(phases.phases.map(p => (p.phase, p.attempts, p.spans, p.wallMillis)) ==
          List((UsagePhase.Work, 2L, 0L, ms(20) + ms(20)), (UsagePhase.Check, 0L, 1L, ms(5)), (UsagePhase.Await, 0L, 1L, ms(10))))
        checks <- rig.usage.checks(rig.owner, UsageFilter.TaskOnly(t1))
        _ <- assertIO(checks.checks == List(CheckUsage(Some("cq-fast"), AttemptState.Completed, 1, ms(5))))
        attempts <- rig.usage.attempts(rig.owner, UsageFilter.TaskOnly(t1), None, None, 20)
        _ <- assertIO(attempts.entries.map(_.attempt.id).toSet == Set(a1.id, a2.id))
      } yield ()
    }
    "an explicit window selects by intersection, cuts figures and keeps true start and end" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        before <- rig.ran(0, 20)
        after <- rig.ran(40, 50)
        parent <- rig.ran(10, 50)
        child = rig.span(UsagePhase.Check, 20, 30, parent = Some(SpanParent.Attempt(parent.id)), check = Some("cq-fast"))
        _ <- rig.put(child)
        _ = rig.clock.now = ms(100)
        report <- rig.read(window = Some((25, 40)))
        _ <- assertIO(report.units.map(_.key).toSet == Set(keyOf(parent), keyOf(child)))
        cut = rig.unit(report, keyOf(parent))
        _ <- assertIO(cut.startedAt == ms(10) && cut.finishedAt.contains(ms(50)) && cut.duration == ms(15) && cut.ownTime == ms(10))
        _ <- assertIO(rig.category(report, UnitKind.Attempt, UsagePhase.Work).total == ms(15))
        other = new Rig(repository, ledger)
        _ <- other.init()
        governing <- other.begin(other.governing(0, attached = true))
        _ <- other.ran(10, 30, parent = Some(governing))
        _ <- other.ran(80, 90)
        _ = other.clock.now = ms(200)
        later <- other.read(window = Some((0, 60)))
        _ <- assertIO(later.lastObserved.contains(ms(90)) && later.split == timeSplit(20, 0, 40, 0, 0) && splitSum(later.split) == windowLength(later))
      } yield ()
    }
    "count the truncated waits that do not name the Task, with a cap" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        named <- task(ledger, rig.owner, "Named")
        unnamed <- task(ledger, rig.owner, "Unnamed")
        wait = (blocked: ItemId, at: Long) => rig.span(UsagePhase.Await, at, at + 1, await = Some(AwaitedSpan(ItemId(rig.owner.project, Ledger.Questions, at), Set(blocked), true, 1)))
        _ <- ZIO.foreachDiscard(List(wait(unnamed, 1), wait(unnamed, 3), wait(unnamed, 5), wait(named, 7)))(rig.put)
        report <- rig.read(UsageFilter.TaskOnly(named))
        _ <- assertIO(report.truncatedWaits == 3 && !report.truncatedWaitsAtLeast)
        capped <- repository.read(rig.owner.project)(_.truncatedWaits(UsageFilter.TaskOnly(named), 2))
        _ <- assertIO(capped == 2)
        elsewhere <- rig.read(UsageFilter.ProjectAll())
        _ <- assertIO(elsewhere.truncatedWaits == 0 && !elsewhere.truncatedWaitsAtLeast)
      } yield ()
    }
    "page units in start order with the identity breaking a tie, and refuse bad pages" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        attempts <- ZIO.foreach(List(0L, 10L, 10L, 20L, 30L))(start => rig.ran(start, start + 5))
        expected = attempts.sortBy(a => (a.startedAt, "A:" + a.id.value)).map(keyOf)
        _ = rig.clock.now = ms(100)
        one <- rig.read(limit = 2)
        two <- rig.read(limit = 2, after = one.next)
        three <- rig.read(limit = 2, after = two.next)
        _ <- assertIO(one.units.map(_.key) == expected.take(2) && two.units.map(_.key) == expected.slice(2, 4) && three.units.map(_.key) == expected.drop(4))
        _ <- assertIO(one.next.nonEmpty && two.next.nonEmpty && three.next.isEmpty)
        _ <- assertIO(one.categories == two.categories && one.window == two.window && one.split == two.split)
        _ <- denied(rig.read(limit = 0))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(rig.read(limit = 201))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(rig.read(limit = 2, after = one.next.map(_.copy(after = UnitKey.Attempt(AttemptId(UUID.randomUUID()))))))(_.isInstanceOf[Fault.Invalid])
        _ <- rig.ran(40, 45)
        _ <- denied(rig.read(limit = 2, after = one.next))(_.isInstanceOf[Fault.Resync])
      } yield ()
    }
    "freeze the read time across the pages of one listing" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      for {
        _ <- rig.init()
        governing <- rig.begin(rig.governing(0, attached = false))
        checks = List.fill(3)(rig.span(UsagePhase.Check, 10, 10, AttemptState.Running, parent = Some(SpanParent.Attempt(governing.id)), check = Some("cq-fast")))
        _ <- ZIO.foreachDiscard(checks)(rig.put)
        _ = rig.clock.now = ms(25)
        first <- rig.read(limit = 2)
        _ <- assertIO(first.readTime == ms(25) && first.window.exists(w => w.from == 0 && w.until == ms(25)) && first.split.running == ms(15))
        _ <- assertIO(first.units.filter(_.phase == UsagePhase.Check).forall(_.duration == ms(15)))
        _ = rig.clock.now = ms(60)
        second <- rig.read(limit = 2, after = first.next)
        _ <- assertIO(second.readTime == ms(25) && second.window == first.window && second.split == first.split && second.categories == first.categories)
        _ <- assertIO(second.units.nonEmpty && second.units.forall(_.duration == ms(15)))
        fresh <- rig.read(limit = 2)
        _ <- assertIO(fresh.readTime == ms(60) && fresh.window.exists(w => w.from == 0 && w.until == ms(60)) && fresh.split.running == ms(50))
        _ <- denied(rig.read(limit = 2, after = first.next.map(_.copy(readTime = ms(61)))))(_.isInstanceOf[Fault.Invalid])
      } yield ()
    }
    "bound and measurement" in { (repository: UsageRepository[IO], ledger: LedgerService[IO]) =>
      val rig = new Rig(repository, ledger)
      val groups = 9999
      val sessionTwo = SessionId(UUID.randomUUID())
      val governing = rig.attempt(0, UsagePhase.Govern, Role.Governor)
      def at(value: Long): Long = value
      def seed(from: Int, until: Int, tx: UsageTransaction): Unit = (from until until).foreach { i =>
        val base = 100L * i
        val in = if (i >= groups - 5) sessionTwo else rig.session
        val review = Attempt(AttemptId(UUID.randomUUID()), rig.work.id, Some(governing.id), in, Role.Reviewer, Harness.Codex, "p", "m", "fixture-v1", at(base), UsagePhase.Review, None)
        tx.putAttempt(review, rig.host.actor, 0)
        tx.putOutcome(AttemptOutcome(RequestId(UUID.randomUUID()), review.id, AttemptState.Completed, at(base + 100), Nil, None), rig.host.actor, 0)
        tx.putSpan(PhaseSpan(RequestId(UUID.randomUUID()), rig.work.id, in, UsagePhase.Check, at(base + 20), at(base + 60), AttemptState.Completed, Some("cq-fast"), None,
          Some(SpanParent.Attempt(review.id))), rig.host.actor, 0)
      }
      for {
        _ <- rig.init()
        _ <- repository.transact(rig.owner.project) { tx =>
          tx.putAttempt(governing, rig.host.actor, 0)
          tx.putOutcome(AttemptOutcome(RequestId(UUID.randomUUID()), governing.id, AttemptState.Completed, 100L * groups, Nil, None), rig.host.actor, 0)
          val extra = rig.attempt(0)
          tx.putAttempt(extra.copy(startedAt = 0), rig.host.actor, 0)
          tx.putOutcome(AttemptOutcome(RequestId(UUID.randomUUID()), extra.id, AttemptState.Completed, 50, Nil, None), rig.host.actor, 0)
        }
        _ <- ZIO.foreachDiscard((0 until groups by 1000).toList)(from => repository.transact(rig.owner.project)(tx => seed(from, math.min(groups, from + 1000), tx)))
        _ = rig.clock.now = 10_000_000L
        began = System.nanoTime()
        report <- rig.read()
        elapsed = (System.nanoTime() - began) / 1_000_000L
        _ = println(s"unit-times-measure adapter=$adapter units=20000 readMillis=$elapsed")
        total = 100L * groups
        review = rig.category(report, UnitKind.Attempt, UsagePhase.Review)
        _ <- assertIO(review.units == groups && review.total == total && review.ownTotal == 60L * groups)
        check = rig.category(report, UnitKind.Span, UsagePhase.Check, Some("cq-fast"))
        _ <- assertIO(check.units == groups && check.total == 40L * groups && check.ownTotal == 40L * groups)
        govern = rig.category(report, UnitKind.Attempt, UsagePhase.Govern)
        _ <- assertIO(govern.total == total && govern.ownTotal == 0 && rig.category(report, UnitKind.Attempt, UsagePhase.Work).total == 50)
        _ <- assertIO(report.categories.map(_.units).sum == 20000 && report.window.contains(UnitWindow(0, total, false, false)))
        _ <- assertIO(report.split == TimeSplit(total, 0, 0, 0, 0) && report.units.size == 200)
        pageSize <- rig.read(limit = 200).map(_.units.size)
        _ <- assertIO(pageSize == 200)
        _ <- rig.ran(0, 10)
        over <- rig.read().either
        _ <- assertIO(over match { case Left(DomainFailure(_: Fault.Limit)) => true; case _ => false })
        // the window of the closed form: five reviews and four checks of the groups 500 to 504, and the governing attempt
        selected <- repository.read(rig.owner.project)(_.unitRecords(UsageFilter.ProjectAll(), Some((50030L, 50410L)), 20001).size)
        _ <- assertIO(selected == 10)
        cut <- usageUnits(rig, UsageFilter.ProjectAll(), Some(UnitWindowRequest(50030L, 50410L)))
        _ <- assertIO(cut.units.size == 10)
        sessionRead <- usageUnits(rig, UsageFilter.SessionOnly(sessionTwo), None)
        _ <- assertIO(sessionRead.units.size == 10 && sessionRead.units.filter(_.phase == UsagePhase.Review).forall(u => u.parent.contains(keyOf(governing)) && u.ownTime == 60L))
        _ <- assertIO(sessionRead.units.filter(_.phase == UsagePhase.Check).forall(_.ownTime == 40L))
      } yield ()
    }
  }

  private def usageUnits(rig: Rig, filter: UsageFilter, window: Option[UnitWindowRequest]): IO[Throwable, UnitReport] =
    rig.usage.units(rig.owner, filter, window, 200, None)
}

final class UsageContractDummy extends UsageContractTest {
  override protected def adapter: String = "Dummy"
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class UsageContractPostgres extends UsageContractTest {
  override protected def adapter: String = "Postgres"
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
