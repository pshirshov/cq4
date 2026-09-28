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
    val draft = ItemDraft(title, "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil), Nil)
    ledger.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(draft)), Nil, "Usage fixture")).map(_.items.head.id)
  }
  private def start(usage: UsageService[IO], owner: Scope, assignment: Assignment, counterScope: CounterScope, baseline: TokenCounts, baselineCost: Money): IO[Throwable, Attempt] = for {
    _ <- usage.assign(collector(owner), assignment)
    attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Worker, Harness.Codex, "test-provider", "test-model", "fixture-v1", 1000)
    _ <- usage.start(collector(owner), attempt)
    _ <- usage.meter(collector(owner), UsageMeter("provider", attempt.id, counterScope, baseline, baselineCost))
  } yield attempt
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
        pending = Attempt(AttemptId(UUID.randomUUID()), attempt.assignment, None, owner.actor.session, Role.Worker, Harness.Claude, "fixture", "fixture", "fixture", 1000)
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
        _ <- assertIO(views.map(_.direct.total.known) == List(200L, 300L) && views.forall(_.sharedAssignments == Set(sharedAssignment.id)))
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
        _ <- ledger.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Replace(one, item.item.revision, item.item.draft.copy(archived = true))), Nil, "Archive retains spend"))
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
      val attempt = Attempt(AttemptId(UUID.randomUUID()), work.id, None, owner.actor.session, Role.Governor, Harness.Pi, "provider", "model", "fixture-v1", 1000)
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
}

final class UsageContractDummy extends UsageContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class UsageContractPostgres extends UsageContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
