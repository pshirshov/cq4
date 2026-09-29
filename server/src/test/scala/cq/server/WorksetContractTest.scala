package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import zio.{IO, ZIO}

abstract class WorksetContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("worksets", SessionId(UUID.randomUUID()), Role.Governor))
  private def task(title: String): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil), Nil)
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Workset scenario"))
  private def create(service: LedgerService[IO], owner: Scope, draft: ItemDraft): IO[Throwable, ItemId] =
    change(service, owner, List(Mutation.Create(draft))).map(_.items.head.id)
  private def link(service: LedgerService[IO], owner: Scope, source: ItemId, relation: Relation, target: ItemId): IO[Throwable, Unit] = for {
    a <- service.get(owner, source)
    b <- service.get(owner, target)
    _ <- change(service, owner, List(Mutation.Reference(source, a.item.revision, relation, target, b.item.revision, true)))
  } yield ()
  private def graph(service: LedgerService[IO], owner: Scope, roots: Set[ItemId]): IO[Throwable, WorksetPage] =
    service.workset(owner, roots, None, None, 200)
  private def selected(page: WorksetPage): Set[ItemId] = page.entries.filter(_.role == WorksetRole.Selected).map(_.item.id).toSet
  private def context(page: WorksetPage): Set[ItemId] = page.entries.filter(_.role == WorksetRole.Context).map(_.item.id).toSet
  private def entry(page: WorksetPage, id: ItemId): WorksetEntry = page.entries.find(_.item.id == id).get
  private def pages(service: LedgerService[IO], owner: Scope, roots: Set[ItemId], page: WorksetPage): IO[Throwable, List[WorksetEntry]] =
    if (!page.hasMore) ZIO.succeed(page.entries)
    else service.workset(owner, roots, page.after, Some(page.snapshot), 200).flatMap(next => pages(service, owner, roots, next)).map(page.entries ++ _)

  "Transient worksets (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "bound full candidate content before sending it to a selector and preserve explicit omitted revisions" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "bounded candidates")
        created <- change(service, owner, List.fill(6)(Mutation.Create(task("Large task").copy(body = "x" * 50000))))
        page <- service.details(owner, created.items, 80000)
        _ <- assertIO(page.items.size == 1 && page.omitted == created.items.tail &&
          page.items.map(view => Wire.encode(ItemView_JsonCodec, view).getBytes(UTF_8).length).sum <= 80000)
        empty <- service.details(owner, created.items, 1)
        _ <- assertIO(empty.items.isEmpty && empty.omitted == created.items)
        stale <- service.details(owner, created.items.map(_.copy(revision = Revision(2))), CohortBounds.CandidateBytes).either
        _ <- assertIO(stale.left.exists { case DomainFailure(_: Fault.Conflict) => true; case _ => false })
        duplicate <- service.details(owner, List(created.items.head, created.items.head), 1000).either
        _ <- assertIO(duplicate.left.exists { case DomainFailure(_: Fault.Invalid) => true; case _ => false })
      } yield ()
    }

    "separate produced work from non-expanding context and retain shared members once" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "selection")
        intake <- create(service, owner, task("Intake").copy(content = Content.Idea(IdeaStatus.Proposed, "Outcome", "Motivation")))
        goal <- create(service, owner, task("Goal").copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope")))
        outside <- create(service, owner, task("Other producer").copy(content = Content.Goal(GoalStatus.Open, "Other", List("Acceptance"), "Scope")))
        milestone <- create(service, owner, task("Milestone").copy(content = Content.Milestone(MilestoneStatus.Open, "Release")))
        work <- create(service, owner, task("Work"))
        sibling <- create(service, owner, task("Unrelated milestone sibling"))
        shared <- create(service, owner, task("Shared work"))
        prerequisite <- create(service, owner, task("Prerequisite"))
        hidden <- create(service, owner, task("Context's produced work"))
        review <- create(service, owner, task("Review").copy(content = Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None)))
        _ <- ZIO.foreachDiscard(List((intake, Relation.Produces, goal), (goal, Relation.Produces, work),
          (goal, Relation.Produces, shared), (outside, Relation.Produces, shared), (outside, Relation.Produces, hidden),
          (milestone, Relation.Contains, work), (milestone, Relation.Contains, sibling),
          (work, Relation.BlockedBy, prerequisite), (prerequisite, Relation.Produces, hidden), (review, Relation.Reviews, work))) {
          case (a, relation, b) => link(service, owner, a, relation, b)
        }
        result <- graph(service, owner, Set(intake, goal))
        _ <- assertIO(selected(result) == Set(intake, goal, work, shared) && context(result) == Set(outside, milestone, prerequisite, review))
        _ <- assertIO(result.selectedCount == 4 && result.contextCount == 4 && result.readyCount == 3 && !result.hasMore)
        _ <- assertIO(entry(result, shared).ready && entry(result, shared).reasons.contains(WorksetReason.Shared(outside)))
        _ <- assertIO(!entry(result, work).ready && entry(result, work).reasons.contains(WorksetReason.Blocked(prerequisite)))
        _ <- assertIO(entry(result, milestone).reasons == List(WorksetReason.Context(work, Relation.PartOf)))
        _ <- assertIO(result.entries.filter(_.root).map(_.item.id).toSet == Set(intake, goal) && result.entries.filter(_.role == WorksetRole.Context).forall(!_.ready))
        explicit <- graph(service, owner, Set(milestone))
        _ <- assertIO(selected(explicit) == Set(milestone, work, sibling) && !explicit.entries.exists(_.item.id == shared))
        child <- graph(service, owner, Set(work))
        _ <- assertIO(selected(child) == Set(work) && !child.entries.exists(_.item.id == sibling))
      } yield ()
    }

    "traverse cycles and archived ancestors while distinguishing terminal prerequisites from satisfied ones" in { (service: LedgerService[IO]) =>
      val owner = scope()
      def ended(status: TaskStatus): ItemDraft = task(status.toString).copy(archived = true, content = Content.Task(status, List("Acceptance"), None, Nil))
      for {
        _ <- service.initialize(owner, "cycles and outcomes")
        a <- create(service, owner, ended(TaskStatus.Done).copy(title = "Archived ancestor"))
        b <- create(service, owner, ended(TaskStatus.Done).copy(archived = false))
        c <- create(service, owner, task("Active descendant"))
        cancelled <- create(service, owner, ended(TaskStatus.Cancelled))
        done <- create(service, owner, ended(TaskStatus.Done))
        _ <- ZIO.foreachDiscard(List((a, b), (b, c), (c, a))) { case (from, to) => link(service, owner, from, Relation.Produces, to) }
        _ <- link(service, owner, c, Relation.BlockedBy, cancelled)
        _ <- link(service, owner, c, Relation.BlockedBy, done)
        blocked <- graph(service, owner, Set(a))
        _ <- assertIO(selected(blocked) == Set(a, b, c) && blocked.readyCount == 0)
        _ <- assertIO(entry(blocked, a).reasons.contains(WorksetReason.Archived()) && entry(blocked, b).reasons.contains(WorksetReason.Terminal()))
        _ <- assertIO(entry(blocked, c).reasons == List(WorksetReason.Blocked(cancelled)))
        current <- service.get(owner, cancelled)
        _ <- change(service, owner, List(Mutation.Replace(cancelled, current.item.revision, ended(TaskStatus.Done))))
        ready <- graph(service, owner, Set(a))
        _ <- assertIO(ready.readyCount == 1 && entry(ready, c).ready)
      } yield ()
    }

    "bind stable pages to roots and revisions, reject scope violations and return an empty selection for empty roots" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = scope()
      for {
        _ <- service.initialize(owner, "snapshots")
        _ <- service.initialize(other, "other")
        a <- create(service, owner, task("Root"))
        b <- create(service, owner, task("Child"))
        c <- create(service, owner, task("New child"))
        foreign <- create(service, other, task("Foreign"))
        _ <- link(service, owner, a, Relation.Produces, b)
        empty <- graph(service, owner, Set.empty)
        _ <- assertIO(empty.entries.isEmpty && empty.selectedCount == 0 && empty.contextCount == 0 && empty.readyCount == 0 && !empty.hasMore)
        first <- service.workset(owner, Set(a), None, None, 1)
        all <- pages(service, owner, Set(a), first)
        _ <- assertIO(first.hasMore && all.map(_.item.id) == List(a, b))
        invalid <- ZIO.foreach(List(
          service.workset(owner, Set(a), first.after, None, 1),
          service.workset(owner, Set(b), first.after, Some(first.snapshot), 1),
          service.workset(owner, Set(a), Some(c), Some(first.snapshot), 1),
          service.workset(owner, Set(a), None, None, 0), service.workset(owner, Set(a), None, None, 201),
          service.workset(owner, (1L to 65L).map(n => a.copy(number = n)).toSet, None, None, 1),
        ))(_.either)
        _ <- assertIO(invalid.forall { case Left(DomainFailure(_: Fault.Invalid)) => true; case _ => false })
        denied <- ZIO.foreach(List(graph(service, owner, Set(foreign)), service.workset(owner, Set(a), Some(foreign), Some(first.snapshot), 1)))(_.either)
        _ <- assertIO(denied.forall { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
        missing <- graph(service, owner, Set(a.copy(number = 999))).either
        _ <- assertIO(missing match { case Left(DomainFailure(_: Fault.Missing)) => true; case _ => false })
        _ <- link(service, owner, b, Relation.Produces, c)
        stale <- service.workset(owner, Set(a), first.after, Some(first.snapshot), 1).either
        _ <- assertIO(stale match { case Left(DomainFailure(_: Fault.Resync)) => true; case _ => false })
        fresh <- graph(service, owner, Set(a))
        _ <- assertIO(selected(fresh) == Set(a, b, c))
      } yield ()
    }

    "fail explicitly beyond the visited-item bound and paginate large summaries without narratives" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val owner = scope()
      val bulky = scope()
      def seed(scope: Scope, count: Int, value: ItemDraft): IO[Throwable, List[ItemId]] = repository.transact(scope.project) { tx =>
        val provenance = Provenance(scope.actor, 0, RequestId(UUID.randomUUID()))
        (1 to count).toList.map { _ =>
          val id = tx.allocate(Ledger.Tasks)
          tx.put(Item(id, Revision(1), value, 0, 0, provenance))
          id
        }
      }
      for {
        _ <- service.initialize(owner, "visited bound")
        ids <- seed(owner, WorksetTraversal.MaxItems + 1, task("Bounded fixture"))
        _ <- repository.transact(owner.project) { tx =>
          ids.slice(1, 7).foreach(id => tx.edge(LedgerPolicy.canonical(ids.head, Relation.Produces, id), true))
          ids.slice(7, WorksetTraversal.MaxItems).grouped(170).zipWithIndex.foreach { case (group, index) =>
            group.foreach(id => tx.edge(LedgerPolicy.canonical(ids(index + 1), Relation.RelatesTo, id), true))
          }
        }
        full <- graph(service, owner, Set(ids.head))
        _ <- assertIO(full.selectedCount == 7 && full.contextCount == WorksetTraversal.MaxItems - 7 && full.hasMore)
        _ <- link(service, owner, ids.head, Relation.RelatesTo, ids.last)
        staleBound <- service.workset(owner, Set(ids.head), full.after, Some(full.snapshot), 200).either
        _ <- ZIO.succeed(println(s"Stale traversal observation: $staleBound"))
        _ <- assertIO(staleBound match { case Left(DomainFailure(_: Fault.Resync)) => true; case _ => false })
        overflow <- graph(service, owner, Set(ids.head)).either
        _ <- assertIO(overflow match { case Left(DomainFailure(_: Fault.Limit)) => true; case _ => false })
        _ <- service.initialize(bulky, "byte bound")
        large = task("Large summary").copy(body = "n" * LedgerPolicy.MaxBody, labels = (1 to 32).map(n => n.toString + "界" * 78).toSet)
        largeIds <- seed(bulky, 130, large)
        _ <- repository.transact(bulky.project)(tx => largeIds.tail.foreach(id => tx.edge(LedgerPolicy.canonical(largeIds.head, Relation.RelatesTo, id), true)))
        page <- graph(service, bulky, Set(largeIds.head))
        _ <- assertIO(page.hasMore && page.entries.size < 130 && Wire.encode(WorksetPage_JsonCodec, page).getBytes(UTF_8).length <= ReadPage.MaxBytes)
        collected <- pages(service, bulky, Set(largeIds.head), page)
        _ <- assertIO(collected.map(_.item.id) == largeIds && collected.forall(_.item.title == "Large summary"))
      } yield ()
    }
  }
}

final class WorksetContractDummy extends WorksetContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class WorksetContractPostgres extends WorksetContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
