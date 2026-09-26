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

abstract class LedgerContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )

  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
  private def task(title: String): ItemDraft = ItemDraft(title, "Markdown λ", Set("test"), false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil)
  private def request(mutations: List[Mutation], fences: List[Fence]): ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()), mutations, fences, "Contract scenario")
  private def create(service: LedgerService[IO], scope: Scope, draft: ItemDraft): IO[Throwable, ItemRevision] =
    service.change(scope, request(List(Mutation.Create(draft)), Nil)).map(_.items.head)
  private def denied[A](effect: IO[Throwable, A])(expected: Fault => Boolean): IO[Throwable, Unit] =
    effect.either.flatMap(result => assertIO(result match { case Left(DomainFailure(fault)) => expected(fault); case _ => false })).unit

  "Durable ledger service (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "create all fourteen typed ledgers and preserve project identity through reattachment" in { (service: LedgerService[IO]) =>
      val first = scope()
      val second = scope()
      val contents: List[Content] = List(
        Content.Milestone(MilestoneStatus.Open, "Deliver the release"),
        Content.Idea(IdeaStatus.Proposed, "Useful outcome", "User motivation"),
        Content.Defect(DefectStatus.Open, Severity.High, "Actual output", "Expected output", "Exact reproduction", None, Nil),
        Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"),
        Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil),
        Content.Research(ResearchStatus.Open, "Empirical uncertainty", Nil, None, None),
        Content.Hypothesis(HypothesisStatus.Proposed, "Falsifiable claim", "Rationale", Nil, None),
        Content.Question(QuestionStatus.Open, "Preference?", "Context", List("A", "B"), None),
        Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", List("Alternative")),
        Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None),
        Content.Handoff(HandoffStatus.Open, "Result so far", List("Remaining"), Nil),
        Content.OperatorAction(OperatorActionStatus.Requested, "Operator action", "Observable evidence", None, Nil),
        Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil),
        Content.Upstream(UpstreamStatus.Identified, "component", "1.2.3", "Reproduction", None, None),
      )
      for {
        initialized <- service.initialize(first, "first")
        attached <- service.initialize(first, "renamed directory")
        _ <- assertIO(initialized == attached && attached.name == "first")
        _ <- service.initialize(second, "second")
        created <- ZIO.foreach(contents)(c => create(service, first, task(c.getClass.getSimpleName).copy(content = c)))
        _ <- assertIO(created.map(_.id.ledger).toSet == Ledger.all.toSet && created.forall(_.id.number == 1))
        other <- create(service, second, task("Other project"))
        _ <- assertIO(other.id.number == 1 && other.id.project != first.project)
        _ <- denied(service.get(first, other.id))(_.isInstanceOf[Fault.Denied])
        worker = first.copy(actor = first.actor.copy(role = Role.Worker))
        _ <- denied(create(service, worker, task("Forbidden")))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "allocate independently under concurrency and replay acknowledgements without duplicate effects" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val another = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val sameRequest = request(List(Mutation.Create(task("Retry"))), Nil)
      for {
        _ <- service.initialize(owner, "concurrency")
        results <- ZIO.foreachPar((1 to 16).toList)(n => create(service, if (n % 2 == 0) owner else another, task(s"Task $n")))
        _ <- assertIO(results.map(_.id.number).toSet == (1L to 16L).toSet)
        retries <- ZIO.foreachPar((1 to 8).toList)(_ => service.change(owner, sameRequest))
        _ <- assertIO(retries.distinct.size == 1 && retries.head.items.head.id.number == 17)
        _ <- denied(service.change(owner, sameRequest.copy(reason = "Changed payload")))(_.isInstanceOf[Fault.Conflict])
        replay <- service.changes(owner, ChangeCursor(0), 200)
        _ <- assertIO(replay.events.size == 17 && replay.events.map(_.cursor.value) == (1L to 17L).toList)
        history <- service.history(owner, retries.head.items.head.id, Revision(Long.MaxValue), 200)
        _ <- assertIO(history.entries.size == 1)
      } yield ()
    }

    "roll back an entire batch and allow archive, status correction and restore as a new revision" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val initial = task("Task")
      for {
        _ <- service.initialize(owner, "rollback")
        first <- create(service, owner, initial)
        failed = request(List(Mutation.Create(task("Must roll back")), Mutation.Replace(first.id, Revision(9), initial)), Nil)
        _ <- denied(service.change(owner, failed))(_.isInstanceOf[Fault.Conflict])
        afterFailure <- service.search(owner, ItemFilter(None, ArchiveFilter.All), None, 200)
        _ <- assertIO(afterFailure.items.size == 1 && afterFailure.cursor.value == 1)
        archived = initial.copy(archived = true, content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Done"), Nil))
        _ <- service.change(owner, request(List(Mutation.Replace(first.id, Revision(1), archived)), Nil))
        hidden <- service.search(owner, ItemFilter(None, ArchiveFilter.Active), None, 200)
        _ <- assertIO(hidden.items.isEmpty)
        direct <- service.get(owner, first.id)
        _ <- assertIO(direct.item.draft.archived)
        _ <- service.change(owner, request(List(Mutation.Restore(first.id, Revision(2), Revision(1))), Nil))
        restored <- service.get(owner, first.id)
        _ <- assertIO(restored.item.revision.value == 3 && restored.item.draft == initial)
        entries <- service.history(owner, first.id, Revision(Long.MaxValue), 2)
        _ <- assertIO(entries.hasMore && entries.entries.map(_.item.item.revision.value) == List(3L, 2L))
        older <- service.history(owner, first.id, Revision(2), 2)
        _ <- assertIO(!older.hasMore && older.entries.head.item.item.draft == initial)
      } yield ()
    }

    "normalize inverse references once and record both endpoint histories" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "references")
        left <- create(service, owner, task("Dependent"))
        right <- create(service, owner, task("Prerequisite").copy(archived = true))
        added <- service.change(owner, request(List(Mutation.Reference(right.id, Revision(1), Relation.Blocks, left.id, Revision(1), true)), Nil))
        _ <- assertIO(added.items.map(_.revision.value) == List(2L, 2L))
        forward <- service.get(owner, left.id)
        backward <- service.get(owner, right.id)
        _ <- assertIO(forward.refs == List(ItemRef(Relation.BlockedBy, right.id)) && backward.refs == List(ItemRef(Relation.Blocks, left.id)))
        duplicate <- service.change(owner, request(List(Mutation.Reference(left.id, Revision(2), Relation.BlockedBy, right.id, Revision(2), true)), Nil))
        _ <- assertIO(duplicate.items.isEmpty && duplicate.cursor == added.cursor)
        _ <- service.change(owner, request(List(Mutation.Reference(left.id, Revision(2), Relation.BlockedBy, right.id, Revision(2), false)), Nil))
        histories <- ZIO.foreach(List(left.id, right.id))(id => service.history(owner, id, Revision(Long.MaxValue), 200))
        _ <- assertIO(histories.forall(h => h.entries.size == 3 && h.entries.head.item.refs.isEmpty && h.entries(1).item.refs.size == 1 && h.entries.last.item.refs.isEmpty))
        cross = right.id.copy(project = ProjectId(UUID.randomUUID()))
        _ <- denied(service.change(owner, request(List(Mutation.Reference(left.id, Revision(3), Relation.RelatesTo, cross, Revision(1), true)), Nil)))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "handoff a committed snapshot cursor to a paginated change stream" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "stream")
        first <- create(service, owner, task("Before snapshot"))
        snapshot <- service.search(owner, ItemFilter(None, ArchiveFilter.All), None, 200)
        second <- create(service, owner, task("After snapshot"))
        third <- create(service, owner, task("Later"))
        page <- service.changes(owner, snapshot.cursor, 1)
        _ <- assertIO(page.hasMore && page.events.flatMap(_.items).map(_.id) == List(second.id))
        next <- service.changes(owner, page.cursor, 1)
        _ <- assertIO(!next.hasMore && next.events.flatMap(_.items).map(_.id) == List(third.id))
        _ <- denied(service.changes(owner, ChangeCursor(999), 1))(_.isInstanceOf[Fault.Resync])
      } yield ()
    }

    "acquire sets atomically, reject overlap and fence results after expiry or release" in { (repository: LedgerRepository[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val start = 1000000L
      val service = new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(start), ZoneOffset.UTC))
      val later = new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(start + 2000), ZoneOffset.UTC))
      for {
        _ <- service.initialize(owner, "claims")
        left <- create(service, owner, task("One"))
        right <- create(service, owner, task("Two"))
        claimId = ClaimId(UUID.randomUUID())
        claim <- service.acquire(owner, claimId, Set(left.id), 1000)
        repeated <- service.acquire(owner, claimId, Set(left.id), 1000)
        _ <- assertIO(claim == repeated)
        _ <- denied(service.acquire(other, ClaimId(UUID.randomUUID()), Set(left.id, right.id), 1000))(_.isInstanceOf[Fault.Conflict])
        independent <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(right.id), 1000)
        _ <- denied(service.change(other, request(List(Mutation.Replace(left.id, Revision(1), task("Stale"))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        _ <- service.change(owner, request(List(Mutation.Replace(left.id, Revision(1), task("Owned"))), List(claim.fence)))
        replacement <- later.acquire(other, ClaimId(UUID.randomUUID()), Set(left.id), 1000)
        _ <- assertIO(replacement.fence.generation > independent.fence.generation)
        _ <- denied(later.change(owner, request(List(Mutation.Replace(left.id, Revision(2), task("Late result"))), List(claim.fence))))(_.isInstanceOf[Fault.StaleFence])
        _ <- later.release(other, replacement.fence)
        _ <- denied(later.change(other, request(List(Mutation.Replace(left.id, Revision(2), task("Released result"))), List(replacement.fence))))(_.isInstanceOf[Fault.StaleFence])
        _ <- later.change(owner, request(List(Mutation.Replace(left.id, Revision(2), task("Human correction"))), Nil))
      } yield ()
    }

    "keep a replacement claim active when delivery repeats an earlier release" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      for {
        _ <- service.initialize(owner, "release retry")
        item <- create(service, owner, task("Claimed"))
        first <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(item.id), 300000)
        _ <- service.release(owner, first.fence)
        second <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(item.id), 300000)
        _ <- service.release(owner, first.fence)
        _ <- denied(service.change(owner, request(List(Mutation.Replace(item.id, Revision(1), task("Must remain fenced"))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        _ <- service.change(other, request(List(Mutation.Replace(item.id, Revision(1), task("Replacement owner"))), List(second.fence)))
      } yield ()
    }
  }
}

final class LedgerContractDummy extends LedgerContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class LedgerContractPostgres extends LedgerContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
