package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, ZIO}

abstract class TerminationContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("termination", SessionId(UUID.randomUUID()), Role.Governor))
  private def task(title: String): ItemDraft = ItemDraft(title, "Retained narrative", Set("kept"), false,
    Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil), List(Citation.Url("https://example.org/evidence")))
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Termination fixture"))
  private def create(service: LedgerService[IO], owner: Scope, draft: ItemDraft): IO[Throwable, ItemId] =
    change(service, owner, List(Mutation.Create(draft))).map(_.items.head.id)
  private def link(service: LedgerService[IO], owner: Scope, a: ItemId, relation: Relation, b: ItemId): IO[Throwable, Unit] = for {
    left <- service.get(owner, a)
    right <- service.get(owner, b)
    _ <- change(service, owner, List(Mutation.Reference(a, left.item.revision, relation, b, right.item.revision, true)))
  } yield ()
  private def request(preview: TerminationPreview): ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()),
    List(Mutation.Terminate(preview.plan.roots.toSet, preview.plan.intent, preview.snapshot)), preview.plan.claims.map(_.fence), "Reviewed termination")
  private def changed(preview: TerminationPreview): Set[ItemId] = preview.plan.entries.collect {
    case TerminationEntry(item, _: TerminationEffect.Change) => item.id
  }.toSet
  private def effect(preview: TerminationPreview, id: ItemId): TerminationEffect = preview.plan.entries.find(_.item.id == id).get.effect
  private def reject[A](operation: IO[Throwable, A], accepts: Fault => Boolean): IO[Throwable, Unit] =
    operation.either.flatMap(result => assertIO(result match { case Left(DomainFailure(fault)) => accepts(fault); case _ => false }).unit)
  private def fixed(repository: LedgerRepository[IO], millis: Long): LedgerService[IO] = {
    val parser = new QueryParser
    val worksets = new WorksetTraversal
    new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), parser,
      new QueryCompleter(parser), worksets, new TerminationPlanner(worksets), new ClaimPlanner, new LedgerMutation(new TerminationPlanner(new WorksetTraversal)))
  }

  "Whole-subgraph termination (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "map all ledgers without manufacturing factual outcomes and preserve every non-status field" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val evidence = List(Evidence("Previously reported observation", EvidenceOrigin.HumanReported, Nil))
      val contents = List[Content](
        Content.Milestone(MilestoneStatus.Open, "Release"), Content.Idea(IdeaStatus.Proposed, "Outcome", "Motivation"),
        Content.Defect(DefectStatus.Open, Severity.High, "Actual", "Expected", "Reproduction", Some("Possible cause"), evidence),
        Content.Goal(GoalStatus.Open, "Goal", List("Acceptance"), "Scope"),
        Content.Task(TaskStatus.Active, List("Acceptance"), Some("Partial result"), evidence),
        Content.Research(ResearchStatus.Active, "Question", evidence, Some("Partial finding"), None),
        Content.Hypothesis(HypothesisStatus.Investigating, "Claim", "Rationale", evidence, None),
        Content.Question(QuestionStatus.Open, "Prompt", "Context", List("Alternative"), None),
        Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", Nil),
        Content.Review(ReviewStatus.Active, Nil, Some(Citation.Commit("consumer", "abc123")), evidence, Some("Partial review")),
        Content.Handoff(HandoffStatus.Open, "Outcome", List("Remaining"), List("Blocker")),
        Content.OperatorAction(OperatorActionStatus.Confirmed, "Action", "Expected evidence", Some("Human confirmed"), evidence),
        Content.Memory(MemoryStatus.Current, "Knowledge", "Applicability", evidence),
        Content.Upstream(UpstreamStatus.Reported, "Library", "1", "Reproduction", Some(Citation.Url("https://example.org/issue")), None),
      )
      val expected = List("Cancelled", "Withdrawn", "Withdrawn", "Abandoned", "Cancelled", "Cancelled", "Withdrawn", "Withdrawn",
        "Withdrawn", "Cancelled", "Cancelled", "Cancelled", "Current", "Withdrawn")
      for {
        _ <- service.initialize(owner, "typed outcomes")
        ids <- ZIO.foreach(contents)(content => create(service, human, task(LedgerPolicy.ledger(content).toString).copy(content = content, archived = LedgerPolicy.outcome(content).terminal)))
        originals <- ZIO.foreach(ids)(service.get(owner, _))
        completion <- service.termination(owner, ids.toSet, TerminationIntent.Complete)
        _ <- assertIO(!completion.plan.canApply && changed(completion).size == 3 && completion.plan.entries.count(_.effect.isInstanceOf[TerminationEffect.Unsupported]) == 10)
        _ <- reject(service.change(owner, request(completion)), _.isInstanceOf[Fault.Conflict])
        cancelled <- service.termination(owner, ids.toSet, TerminationIntent.Cancel)
        _ <- assertIO(cancelled.plan.canApply && changed(cancelled).size == 13 && effect(cancelled, ids(12)) == TerminationEffect.Preserve())
        ack <- service.change(owner, request(cancelled))
        after <- ZIO.foreach(ids)(service.get(owner, _))
        _ <- assertIO(after.map(v => LedgerPolicy.status(v.item.draft.content)) == expected && ack.items.size == 13)
        _ <- ZIO.foreachDiscard(originals.zip(after)) { case (before, current) =>
          val oldContent = Wire.encode(Content_JsonCodec, before.item.draft.content)
          val newContent = Wire.encode(Content_JsonCodec, current.item.draft.content)
          def fields(json: String) = io.circe.parser.parse(json).toOption.get.asObject.get.values.head.asObject.get.remove("status")
          assertIO(fields(oldContent) == fields(newContent) && current.item.draft.copy(content = before.item.draft.content) == before.item.draft && current.refs == before.refs)
        }
        factual <- service.termination(owner, ids.toSet, TerminationIntent.Complete)
        _ <- assertIO(factual.plan.entries.forall(_.effect == TerminationEffect.Preserve()) && factual.plan.canApply)
        noop <- service.change(owner, request(factual))
        _ <- assertIO(noop.items.isEmpty && noop.cursor == ack.cursor)
      } yield ()
    }

    "preserve settled decisions and memories under every intent while completing the goal that produced them" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "settled records")
        goal <- create(service, owner, task("Goal").copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope")))
        decision <- create(service, owner, task("Adopted").copy(content = Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil)))
        memory <- create(service, owner, task("Current").copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applicability", Nil)))
        proposed <- create(service, owner, task("Proposed").copy(content = Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", Nil)))
        _ <- ZIO.foreachDiscard(List(decision, memory, proposed))(link(service, owner, goal, Relation.Produces, _))
        completion <- service.termination(owner, Set(goal), TerminationIntent.Complete)
        _ <- assertIO(effect(completion, decision) == TerminationEffect.Preserve() && effect(completion, memory) == TerminationEffect.Preserve())
        _ <- assertIO(!completion.plan.canApply && effect(completion, proposed).isInstanceOf[TerminationEffect.Unsupported])
        cancellation <- service.termination(owner, Set(goal), TerminationIntent.Cancel)
        _ <- assertIO(cancellation.plan.canApply && changed(cancellation) == Set(goal, proposed))
        _ <- assertIO(effect(cancellation, decision) == TerminationEffect.Preserve() && effect(cancellation, memory) == TerminationEffect.Preserve())
        ack <- service.change(owner, request(cancellation))
        kept <- ZIO.foreach(List(decision, memory, proposed))(service.get(owner, _))
        _ <- assertIO(ack.items.map(_.id).toSet == Set(goal, proposed) && kept.map(v => LedgerPolicy.status(v.item.draft.content)) == List("Adopted", "Current", "Withdrawn"))
        completed <- service.termination(owner, Set(goal), TerminationIntent.Complete)
        _ <- assertIO(completed.plan.canApply && completed.plan.entries.forall(_.effect == TerminationEffect.Preserve()))
      } yield ()
    }

    "exclude shared branches, prerequisites and contextual milestone siblings while preserving terminal ancestors" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "termination graph")
        root <- create(service, owner, task("Archived root").copy(archived = true, content = Content.Task(TaskStatus.Done, List("Acceptance"), None, Nil)))
        work <- create(service, owner, task("Active work"))
        terminal <- create(service, owner, task("Terminal ancestor").copy(content = Content.Task(TaskStatus.Done, List("Done"), Some("Factual result"), Nil)))
        below <- create(service, owner, task("Active below terminal"))
        shared <- create(service, owner, task("Shared"))
        hidden <- create(service, owner, task("Below shared"))
        outside <- create(service, owner, task("Outside producer"))
        prerequisite <- create(service, owner, task("Produced but prerequisite"))
        milestone <- create(service, owner, task("Context milestone").copy(content = Content.Milestone(MilestoneStatus.Open, "Release")))
        sibling <- create(service, owner, task("Milestone sibling"))
        _ <- ZIO.foreachDiscard(List((root, Relation.Produces, work), (root, Relation.Produces, terminal),
          (terminal, Relation.Produces, below), (below, Relation.Produces, root), (root, Relation.Produces, shared),
          (outside, Relation.Produces, shared), (shared, Relation.Produces, hidden), (root, Relation.Produces, prerequisite),
          (work, Relation.BlockedBy, prerequisite), (milestone, Relation.Contains, work), (milestone, Relation.Contains, sibling))) {
          case (a, relation, b) => link(service, owner, a, relation, b)
        }
        preview <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(changed(preview) == Set(work, below) && effect(preview, terminal) == TerminationEffect.Preserve())
        _ <- assertIO(effect(preview, shared).asInstanceOf[TerminationEffect.Excluded].reasons.contains(TerminationExclusion.Shared(outside)))
        _ <- assertIO(effect(preview, hidden).asInstanceOf[TerminationEffect.Excluded].reasons.contains(TerminationExclusion.Shared(shared)))
        _ <- assertIO(effect(preview, prerequisite).asInstanceOf[TerminationEffect.Excluded].reasons.contains(TerminationExclusion.Prerequisite(work)))
        _ <- assertIO(!preview.plan.entries.exists(_.item.id == sibling))
        explicit <- service.termination(owner, Set(root, shared, prerequisite, milestone), TerminationIntent.Cancel)
        _ <- assertIO(changed(explicit) == Set(work, below, shared, hidden, prerequisite, milestone, sibling))
        ack <- service.change(owner, request(preview))
        kept <- ZIO.foreach(List(shared, hidden, prerequisite, sibling, terminal))(service.get(owner, _))
        _ <- assertIO(ack.items.map(_.id).toSet == changed(preview) && kept.take(4).forall(_.item.draft.content.asInstanceOf[Content.Task].status == TaskStatus.Ready))
        _ <- assertIO(kept.last.item.draft.content.asInstanceOf[Content.Task].result.contains("Factual result"))
      } yield ()
    }

    "reject stale and altered plans atomically, bind caller scope, and replay an acknowledgement after later changes" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val worker = owner.copy(actor = owner.actor.copy(role = Role.Worker))
      for {
        _ <- service.initialize(owner, "preview consistency")
        root <- create(service, owner, task("Root"))
        child <- create(service, owner, task("Unattached"))
        first <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        _ <- reject(service.termination(owner, Set(root.copy(project = ProjectId(UUID.randomUUID()))), TerminationIntent.Cancel), _.isInstanceOf[Fault.Denied])
        _ <- reject(service.termination(owner, Set.empty, TerminationIntent.Cancel), _.isInstanceOf[Fault.Invalid])
        _ <- reject(service.change(other, request(first)), _.isInstanceOf[Fault.Conflict])
        _ <- reject(service.change(worker, request(first)), _.isInstanceOf[Fault.Denied])
        _ <- link(service, owner, root, Relation.Produces, child)
        _ <- reject(service.change(owner, request(first)), _.isInstanceOf[Fault.Conflict])
        fresh <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(changed(fresh) == Set(root, child))
        _ <- reject(service.change(owner, request(fresh.copy(snapshot = fresh.snapshot.copy(digest = "altered")))), _.isInstanceOf[Fault.Conflict])
        operation = request(fresh)
        _ <- reject(service.change(owner, operation.copy(mutations = Mutation.Create(task("Must not persist")) :: operation.mutations)), _.isInstanceOf[Fault.Invalid])
        before <- ZIO.foreach(List(root, child))(service.get(owner, _))
        applied <- ZIO.collectAllPar(List(service.change(owner, operation), service.change(owner, operation)))
        _ <- assertIO(applied.head == applied.last && applied.head.items.size == 2)
        histories <- ZIO.foreach(List(root, child))(service.history(owner, _, Revision(Long.MaxValue), 20))
        _ <- assertIO(histories.zip(before).forall { case (history, item) => history.entries.size == item.item.revision.value + 1 && history.entries.head.reason == operation.reason })
        _ <- create(service, owner, task("Later mutation"))
        replay <- service.change(owner, operation)
        _ <- assertIO(replay == applied.head)
        _ <- reject(service.change(owner, operation.copy(reason = "Different request content")), _.isInstanceOf[Fault.Conflict])
      } yield ()
    }

    "review claim conflicts and collateral membership, preserve excluded-only claims and fence human takeover atomically" in { (repository: LedgerRepository[IO]) =>
      val service = fixed(repository, 10000)
      val renewed = fixed(repository, 11000)
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      for {
        _ <- service.initialize(owner, "termination claims")
        root <- create(service, owner, task("Selected"))
        collateral <- create(service, owner, task("Collateral"))
        prerequisite <- create(service, owner, task("Context only"))
        _ <- link(service, owner, root, Relation.BlockedBy, prerequisite)
        first <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(root, collateral), 60000)
        protectedClaim <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(prerequisite), 60000)
        blocked <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(!blocked.plan.canApply && blocked.plan.claims.size == 1 && !blocked.plan.claims.head.permitted)
        _ <- reject(service.change(owner, request(blocked)), _.isInstanceOf[Fault.Conflict])
        authorized <- service.termination(human, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(authorized.plan.canApply && authorized.plan.claims.head.members.toSet == Set(root, collateral) && authorized.plan.claims.head.affected == List(root))
        _ <- reject(service.change(human, request(authorized).copy(fences = Nil)), _.isInstanceOf[Fault.StaleFence])
        _ <- renewed.renew(other, first.fence, 60000)
        same <- renewed.termination(human, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(authorized == same)
        operation = request(authorized)
        ack <- renewed.change(human, operation)
        _ <- assertIO(ack.items.map(_.id) == List(root))
        _ <- reject(renewed.renew(other, first.fence, 60000), _.isInstanceOf[Fault.StaleFence])
        kept <- renewed.renew(other, protectedClaim.fence, 60000)
        untouched <- service.get(owner, collateral)
        _ <- assertIO(!kept.released && untouched.item.revision == Revision(1))
        original <- service.get(owner, root)
        _ <- reject(renewed.change(other, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Replace(root, original.item.revision, task("Late result"))), List(first.fence), "Stale work")), _.isInstanceOf[Fault.StaleFence])
        replay <- renewed.change(human, operation)
        _ <- assertIO(replay == ack)
      } yield ()
    }

    "detect claim acquisition, release and expiry without relying on item cursors and reject competing plans" in { (repository: LedgerRepository[IO]) =>
      val service = fixed(repository, 10000)
      val expired = fixed(repository, 12000)
      val owner = scope()
      for {
        _ <- service.initialize(owner, "claim snapshots")
        root <- create(service, owner, task("Root"))
        empty <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(root), 1000)
        claimed <- service.termination(owner, Set(root), TerminationIntent.Cancel)
        _ <- assertIO(empty.snapshot.cursor == claimed.snapshot.cursor && empty.snapshot != claimed.snapshot)
        _ <- reject(service.change(owner, request(empty)), _.isInstanceOf[Fault.Conflict])
        _ <- reject(expired.change(owner, request(claimed)), _.isInstanceOf[Fault.Conflict])
        _ <- service.release(owner, claim.fence)
        _ <- reject(service.change(owner, request(claimed)), _.isInstanceOf[Fault.Conflict])
        fresh <- service.termination(owner, Set(root), TerminationIntent.Complete)
        results <- ZIO.collectAllPar(List(service.change(owner, request(fresh)).either, service.change(owner, request(fresh)).either))
        _ <- assertIO(results.count(_.isRight) == 1 && results.count { case Left(DomainFailure(_: Fault.Conflict)) => true; case _ => false } == 1)
        finalItem <- service.get(owner, root)
        _ <- assertIO(finalItem.item.revision == Revision(2) && finalItem.item.draft.content.asInstanceOf[Content.Task].status == TaskStatus.Done)
      } yield ()
    }

    "reject an explicitly supplied expired fence even after a fresh unclaimed preview" in { (repository: LedgerRepository[IO]) =>
      val service = fixed(repository, 10000)
      val expired = fixed(repository, 12000)
      val owner = scope()
      for {
        _ <- service.initialize(owner, "stale termination fence")
        root <- create(service, owner, task("Root"))
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(root), 1000)
        fresh <- expired.termination(owner, Set(root), TerminationIntent.Cancel)
        result <- expired.change(owner, request(fresh).copy(fences = List(claim.fence))).either
        _ <- ZIO.succeed(println(s"Explicit expired termination fence observation: $result"))
        _ <- assertIO(result match { case Left(DomainFailure(_: Fault.StaleFence)) => true; case _ => false })
      } yield ()
    }

    "fail explicitly for excessive changes and encoded preview bytes while supporting the exact change bound" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val owner = scope()
      val bulky = scope()
      def seed(scope: Scope, count: Int, draft: ItemDraft): IO[Throwable, List[ItemId]] = repository.transact(scope.project) { tx =>
        val provenance = Provenance(scope.actor, 0, RequestId(UUID.randomUUID()))
        val ids = (1 to count).toList.map { _ =>
          val id = tx.allocate(Ledger.Tasks)
          tx.put(Item(id, Revision(1), draft, 0, 0, provenance))
          id
        }
        ids.tail.zipWithIndex.foreach { case (id, index) => tx.edge(LedgerPolicy.canonical(ids(index / 100), Relation.Produces, id), true) }
        ids
      }
      for {
        _ <- service.initialize(owner, "change bound")
        ids <- seed(owner, LedgerPolicy.MaxTouchedItems, task("Selected"))
        preview <- service.termination(owner, Set(ids.head), TerminationIntent.Cancel)
        _ <- assertIO(changed(preview).size == LedgerPolicy.MaxTouchedItems)
        extra <- create(service, owner, task("One too many"))
        _ <- link(service, owner, ids(10), Relation.Produces, extra)
        _ <- reject(service.termination(owner, Set(ids.head), TerminationIntent.Cancel), _.isInstanceOf[Fault.Limit])
        left <- service.get(owner, ids(10))
        right <- service.get(owner, extra)
        _ <- change(service, owner, List(Mutation.Reference(ids(10), left.item.revision, Relation.Produces, extra, right.item.revision, false)))
        bounded <- service.termination(owner, Set(ids.head), TerminationIntent.Cancel)
        committed <- service.change(owner, request(bounded))
        _ <- assertIO(committed.items.size == LedgerPolicy.MaxTouchedItems)
        _ <- service.initialize(bulky, "preview byte bound")
        large <- seed(bulky, 70, task("Large summary").copy(body = "x" * LedgerPolicy.MaxBody, labels = (1 to 32).map(n => n.toString + "界" * 78).toSet))
        _ <- reject(service.termination(bulky, Set(large.head), TerminationIntent.Cancel), _.isInstanceOf[Fault.Limit])
        single <- service.termination(bulky, Set(large.last), TerminationIntent.Cancel)
        _ <- assertIO(single.plan.canApply && changed(single) == Set(large.last))
      } yield ()
    }
  }
}

final class TerminationContractDummy extends TerminationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class TerminationContractPostgres extends TerminationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
