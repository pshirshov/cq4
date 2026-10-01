package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class StoredWorksetContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(role: Role): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("stored worksets", SessionId(UUID.randomUUID()), role))
  private def task(title: String): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil), Nil)
  private def goal(title: String): ItemDraft = task(title).copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"))
  private def idea(title: String, status: IdeaStatus): ItemDraft = task(title).copy(content = Content.Idea(status, "Outcome", "Motivation"))
  private def milestone(title: String): ItemDraft = task(title).copy(content = Content.Milestone(MilestoneStatus.Open, "Release"))
  private def question(title: String): ItemDraft = task(title).copy(content = Content.Question(QuestionStatus.Open, "Prompt", "Context", Nil, None, None))
  private def decision(title: String): ItemDraft = task(title).copy(content = Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", Nil))
  private def memory(title: String): ItemDraft = task(title).copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applicability", Nil))
  private def review(title: String): ItemDraft = task(title).copy(content = Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None))
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Stored workset scenario"))
  private def create(service: LedgerService[IO], owner: Scope, draft: ItemDraft): IO[Throwable, ItemId] =
    change(service, owner, List(Mutation.Create(draft))).map(_.items.head.id)
  private def link(service: LedgerService[IO], owner: Scope, source: ItemId, relation: Relation, target: ItemId): IO[Throwable, Unit] = for {
    a <- service.get(owner, source)
    b <- service.get(owner, target)
    _ <- change(service, owner, List(Mutation.Reference(source, a.item.revision, relation, target, b.item.revision, true)))
  } yield ()
  private def links(service: LedgerService[IO], owner: Scope, edges: List[(ItemId, Relation, ItemId)]): IO[Throwable, Unit] =
    ZIO.foreachDiscard(edges) { case (a, relation, b) => link(service, owner, a, relation, b) }
  private def advanceable(preview: WorksetPreview): Set[ItemId] = preview.advanceable.map(_.item.id).toSet
  private def context(preview: WorksetPreview): Set[ItemId] = preview.context.map(_.item.id).toSet
  private def readiness(preview: WorksetPreview, id: ItemId): WorksetReadiness = preview.readiness.find(_.item == id).get
  private def fault[A](result: Either[Throwable, A]): Option[Fault] = result.left.toOption.collect { case DomainFailure(value) => value }
  private def invalid[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Invalid])
  private def missing[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Missing])
  private def denied[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Denied])
  private def measured(page: SubgraphPage, id: ItemId): SubgraphExtent = page.entries.find(_.root.id == id).get.extent

  "Stored worksets and subgraph discovery (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "list open candidate roots with summaries of their selected descendants and page them under a snapshot" in { (service: LedgerService[IO]) =>
      val owner = scope(Role.Human)
      for {
        _ <- service.initialize(owner, "discovery")
        intake <- create(service, owner, idea("Open intake", IdeaStatus.Proposed))
        product <- create(service, owner, goal("Goal under open intake"))
        work <- create(service, owner, task("Produced work"))
        blocked <- create(service, owner, task("Blocked produced work"))
        release <- create(service, owner, milestone("Release"))
        member <- create(service, owner, task("Milestone-only member"))
        prerequisite <- create(service, owner, question("Open prerequisite"))
        declined <- create(service, owner, idea("Declined intake", IdeaStatus.Declined))
        orphan <- create(service, owner, goal("Goal whose producer ended"))
        archived <- create(service, owner, task("Archived").copy(archived = true, content = Content.Task(TaskStatus.Done, List("Acceptance"), None, Nil)))
        done <- create(service, owner, task("Done").copy(content = Content.Task(TaskStatus.Done, List("Acceptance"), None, Nil)))
        _ <- links(service, owner, List((intake, Relation.Produces, product), (product, Relation.Produces, work), (product, Relation.Produces, blocked),
          (release, Relation.Contains, work), (release, Relation.Contains, member), (blocked, Relation.BlockedBy, prerequisite),
          (declined, Relation.Produces, orphan)))
        first <- service.subgraphs(owner, None, None, WorksetPlanner.MaxSubgraphs)
        roots = first.entries.map(_.root.id)
        _ <- assertIO(roots.toSet == Set(intake, release, prerequisite, orphan) && roots == roots.sortBy(LedgerPolicy.key) && !first.hasMore)
        _ <- assertIO(!roots.exists(Set(product, work, blocked, member, declined, archived, done)))
        _ <- assertIO(measured(first, intake) == SubgraphExtent.Measured(3, 2, 2, List(LedgerCount(Ledger.Goals, 1), LedgerCount(Ledger.Tasks, 2))))
        _ <- assertIO(measured(first, release) == SubgraphExtent.Measured(2, 2, 1, List(LedgerCount(Ledger.Tasks, 2))))
        _ <- assertIO(measured(first, prerequisite) == SubgraphExtent.Measured(0, 0, 1, Nil))
        _ <- assertIO(measured(first, orphan) == SubgraphExtent.Measured(0, 0, 1, Nil))
        paged <- service.subgraphs(owner, None, None, 1)
        second <- service.subgraphs(owner, paged.after, Some(paged.cursor), 3)
        _ <- assertIO(paged.hasMore && paged.entries.size == 1 && (paged.entries ++ second.entries).map(_.root.id) == roots && !second.hasMore)
        rejected <- ZIO.foreach(List(service.subgraphs(owner, paged.after, None, 1), service.subgraphs(owner, None, None, 0),
          service.subgraphs(owner, None, None, WorksetPlanner.MaxSubgraphs + 1)))(_.either)
        _ <- assertIO(rejected.forall(invalid))
        _ <- create(service, owner, task("Concurrent root"))
        stale <- service.subgraphs(owner, paged.after, Some(paged.cursor), 1).either
        _ <- assertIO(fault(stale).exists(_.isInstanceOf[Fault.Resync]))
      } yield ()
    }

    "report an oversized subgraph explicitly instead of failing discovery" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val owner = scope(Role.Human)
      for {
        _ <- service.initialize(owner, "oversized discovery")
        ids <- repository.transact(owner.project) { tx =>
          val provenance = Provenance(owner.actor, 0, RequestId(UUID.randomUUID()))
          val ids = (1 to WorksetTraversal.MaxItems + 1).toList.map { _ =>
            val id = tx.allocate(Ledger.Tasks)
            tx.put(Item(id, Revision(1), task("Bounded fixture"), 0, 0, provenance))
            id
          }
          ids.slice(1, 7).foreach(id => tx.edge(LedgerPolicy.canonical(ids.head, Relation.Produces, id), true))
          ids.slice(7, ids.size).grouped(170).zipWithIndex.foreach { case (group, index) =>
            group.foreach(id => tx.edge(LedgerPolicy.canonical(ids(index + 1), Relation.RelatesTo, id), true))
          }
          ids
        }
        page <- service.subgraphs(owner, None, None, 1)
        _ <- assertIO(page.entries.map(_.root.id) == List(ids.head) && page.entries.head.extent == SubgraphExtent.Oversized(WorksetTraversal.MaxItems))
      } yield ()
    }

    "store a workset from explicit targets and a through phase and look it up by its host-generated ID" in { (service: LedgerService[IO]) =>
      val owner = scope(Role.Human)
      val governor = owner.copy(actor = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val worker = owner.copy(actor = Actor("worker", SessionId(UUID.randomUUID()), Role.Worker))
      val other = scope(Role.Human)
      for {
        _ <- service.initialize(owner, "stored")
        _ <- service.initialize(other, "other")
        root <- create(service, owner, goal("Goal"))
        child <- create(service, owner, task("Child"))
        _ <- link(service, owner, root, Relation.Produces, child)
        stored <- service.createWorkset(owner, Set(root), WorkflowPhase.Plan)
        _ <- assertIO(stored.targets == Set(root) && stored.through == WorkflowPhase.Plan && stored.actor == owner.actor)
        found <- service.lookupWorkset(owner, stored.id)
        _ <- assertIO(found == stored)
        byGovernor <- service.createWorkset(governor, Set(root, child), WorkflowPhase.Integrate)
        _ <- assertIO(byGovernor.id != stored.id && byGovernor.through == WorkflowPhase.Integrate)
        _ <- ZIO.foreachDiscard(WorkflowPhase.all)(phase => service.createWorkset(owner, Set(child), phase).flatMap(value => assertIO(value.through == phase)))
        byWorker <- service.createWorkset(worker, Set(root), WorkflowPhase.Work).either
        _ <- assertIO(denied(byWorker))
        readByWorker <- service.lookupWorkset(worker, stored.id)
        _ <- assertIO(readByWorker == stored)
        unknown <- service.lookupWorkset(owner, WorksetId(UUID.randomUUID())).either
        foreign <- service.lookupWorkset(other, stored.id).either
        _ <- assertIO(missing(unknown) && missing(foreign))
        byId <- service.previewWorkset(owner, WorksetTarget.Stored(stored.id))
        inline <- service.previewWorkset(owner, WorksetTarget.Inline(Set(root), WorkflowPhase.Plan))
        _ <- assertIO(byId.workset.contains(stored.id) && inline.workset.isEmpty && byId.copy(workset = None) == inline)
        _ <- assertIO(byId.targets == Set(root) && byId.through == WorkflowPhase.Plan && advanceable(byId) == Set(root, child))
        unknownPreview <- service.previewWorkset(owner, WorksetTarget.Stored(WorksetId(UUID.randomUUID()))).either
        _ <- assertIO(missing(unknownPreview))
      } yield ()
    }

    "group the preview into advanceable targets and descendants, context-only items and readiness reasons" in { (service: LedgerService[IO]) =>
      val owner = scope(Role.Human)
      for {
        _ <- service.initialize(owner, "preview")
        product <- create(service, owner, goal("Goal"))
        work <- create(service, owner, task("Produced work"))
        ready <- create(service, owner, task("Ready work"))
        release <- create(service, owner, milestone("Release reached only by PartOf"))
        sibling <- create(service, owner, task("Milestone sibling"))
        prerequisite <- create(service, owner, question("Prerequisite question"))
        producer <- create(service, owner, goal("Other producer"))
        audit <- create(service, owner, review("Review of work"))
        choice <- create(service, owner, decision("Decision target"))
        lesson <- create(service, owner, memory("Retracted memory target").copy(content = Content.Memory(MemoryStatus.Retracted, "Knowledge", "Applicability", Nil)))
        known <- create(service, owner, memory("Current memory target"))
        asked <- create(service, owner, question("Question target"))
        _ <- links(service, owner, List((product, Relation.Produces, work), (product, Relation.Produces, ready), (producer, Relation.Produces, ready),
          (release, Relation.Contains, work), (release, Relation.Contains, sibling), (work, Relation.BlockedBy, prerequisite),
          (audit, Relation.Reviews, work), (choice, Relation.RelatesTo, asked)))
        preview <- service.previewWorkset(owner, WorksetTarget.Inline(Set(product, audit, choice, lesson, known, asked), WorkflowPhase.Review))
        _ <- assertIO(advanceable(preview) == Set(product, work, ready, audit, choice, lesson, known, asked))
        _ <- assertIO(preview.advanceable.filter(_.root).map(_.item.id).toSet == Set(product, audit, choice, lesson, known, asked))
        _ <- assertIO(Set(Ledger.Goals, Ledger.Reviews, Ledger.Decisions, Ledger.Memories, Ledger.Questions).subsetOf(preview.advanceable.filter(_.root).map(_.item.id.ledger).toSet))
        _ <- assertIO(context(preview) == Set(release, prerequisite, producer) && (advanceable(preview) & context(preview)).isEmpty)
        _ <- assertIO(!context(preview).contains(sibling) && !advanceable(preview).contains(sibling))
        _ <- assertIO(preview.context.find(_.item.id == release).get.reasons == List(WorksetReason.Context(work, Relation.PartOf)))
        _ <- assertIO(preview.readiness.map(_.item).toSet == advanceable(preview) && preview.readiness.size == preview.advanceable.size)
        _ <- assertIO(readiness(preview, work) == WorksetReadiness(work, false, List(WorksetReason.Blocked(prerequisite))))
        _ <- assertIO(readiness(preview, ready) == WorksetReadiness(ready, true, List(WorksetReason.Shared(producer))))
        _ <- assertIO(readiness(preview, lesson) == WorksetReadiness(lesson, false, List(WorksetReason.Terminal())))
        _ <- assertIO(readiness(preview, product).ready && readiness(preview, audit).ready && readiness(preview, choice).ready)
        _ <- assertIO(readiness(preview, known) == WorksetReadiness(known, false, List(WorksetReason.Settled())))
        promoted <- service.previewWorkset(owner, WorksetTarget.Inline(Set(product, prerequisite, release), WorkflowPhase.Work))
        _ <- assertIO(Set(prerequisite, release, sibling).subsetOf(advanceable(promoted)) && (advanceable(promoted) & context(promoted)).isEmpty)
        narrow <- service.previewWorkset(owner, WorksetTarget.Inline(Set(work), WorkflowPhase.Explore))
        _ <- assertIO(advanceable(narrow) == Set(work) && context(narrow) == Set(product, release, prerequisite, audit))
      } yield ()
    }

    "evaluate the same selection on the ledger state after a write without committing it" in { (service: LedgerService[IO]) =>
      val owner = scope(Role.Governor)
      for {
        _ <- service.initialize(owner, "hypothetical")
        product <- create(service, owner, goal("Goal"))
        stranger <- create(service, owner, goal("Unrelated goal"))
        stored <- service.createWorkset(owner, Set(product), WorkflowPhase.Work)
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(product), 600000L)
        strangerClaim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(stranger), 600000L)
        before <- service.previewWorkset(owner, WorksetTarget.Stored(stored.id))
        current <- service.get(owner, product)
        produced = ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Produce(product, current.item.revision, List(task("Descendant")))), List(claim.fence), "Hypothetical descendant")
        after <- service.previewWorksetAfter(owner, WorksetTarget.Stored(stored.id), produced)
        added = advanceable(after) -- advanceable(before)
        _ <- assertIO(advanceable(before) == Set(product) && added.size == 1 && added.head.ledger == Ledger.Tasks && after.snapshot.cursor.value > before.snapshot.cursor.value)
        _ <- assertIO(readiness(after, added.head).ready && !after.advanceable.find(_.item.id == added.head).get.root)
        unchanged <- service.previewWorkset(owner, WorksetTarget.Stored(stored.id))
        _ <- assertIO(unchanged == before)
        lost <- service.get(owner, added.head).either
        _ <- assertIO(missing(lost))
        strangerRevision <- service.get(owner, stranger)
        outside = ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Produce(stranger, strangerRevision.item.revision, List(task("Outside descendant")))), List(strangerClaim.fence), "Outside")
        outsideAfter <- service.previewWorksetAfter(owner, WorksetTarget.Inline(Set(product), WorkflowPhase.Work), outside)
        _ <- assertIO(advanceable(outsideAfter) == Set(product) && outsideAfter.snapshot.cursor.value > before.snapshot.cursor.value)
        committed <- service.change(owner, produced.copy(request = RequestId(UUID.randomUUID())))
        real <- service.previewWorkset(owner, WorksetTarget.Stored(stored.id))
        _ <- assertIO(advanceable(real) == Set(product, committed.items.find(_.id != product).get.id))
      } yield ()
    }

    "reject empty targets, unknown items, unknown phases, foreign items and oversized previews explicitly" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val owner = scope(Role.Human)
      val other = scope(Role.Human)
      for {
        _ <- service.initialize(owner, "rejection")
        _ <- service.initialize(other, "foreign")
        root <- create(service, owner, task("Root"))
        foreignItem <- create(service, other, task("Foreign"))
        empty <- ZIO.foreach(List(service.createWorkset(owner, Set.empty, WorkflowPhase.Work).map(_ => ()),
          service.previewWorkset(owner, WorksetTarget.Inline(Set.empty, WorkflowPhase.Work)).map(_ => ())))(_.either)
        _ <- assertIO(empty.forall(invalid) && empty.forall(result => fault(result).exists(_.toString.contains("never select the whole project"))))
        unknown = root.copy(number = 999)
        absent <- ZIO.foreach(List(service.createWorkset(owner, Set(root, unknown), WorkflowPhase.Work).map(_ => ()),
          service.previewWorkset(owner, WorksetTarget.Inline(Set(unknown), WorkflowPhase.Work)).map(_ => ())))(_.either)
        _ <- assertIO(absent.forall(missing) && absent.forall(result => fault(result).exists(_.toString.contains("T999"))))
        foreign <- ZIO.foreach(List(service.createWorkset(owner, Set(foreignItem), WorkflowPhase.Work).map(_ => ()),
          service.previewWorkset(owner, WorksetTarget.Inline(Set(root, foreignItem), WorkflowPhase.Work)).map(_ => ())))(_.either)
        _ <- assertIO(foreign.forall(denied))
        wide <- service.createWorkset(owner, (1L to 65L).map(n => root.copy(number = n)).toSet, WorkflowPhase.Work).either
        _ <- assertIO(invalid(wide))
        nothingStored <- repository.transact(owner.project)(tx => tx.cursor)
        _ <- assertIO(nothingStored.value == 1L)
        valid = s"""{"Workset":{"input":{"project":{"value":"${owner.project.value}"},"action":{"Create":{"targets":[{"project":{"value":"${owner.project.value}"},"ledger":"Tasks","number":"1"}],"through":"%s"}}}}}"""
        decoded = Command_JsonCodec.decode(BaboonCodecContext.Default, parse(valid.format("Integrate")).toOption.get)
        _ <- assertIO(decoded.toOption.contains(Command.Workset(WorksetInput(owner.project, WorksetAction.Create(Set(root), WorkflowPhase.Integrate)))))
        _ <- assertIO(List("Deploy", "integrate", "").forall(phase => Command_JsonCodec.decode(BaboonCodecContext.Default, parse(valid.format(phase)).toOption.get).isLeft))
        inlinePhase = s"""{"Workset":{"input":{"project":{"value":"${owner.project.value}"},"action":{"Preview":{"target":{"Inline":{"targets":[],"through":"Ship"}}}}}}}"""
        _ <- assertIO(Command_JsonCodec.decode(BaboonCodecContext.Default, parse(inlinePhase).toOption.get).isLeft)
        large = task("Large summary").copy(labels = (1 to 32).map(n => n.toString + "界" * 78).toSet)
        largeIds <- repository.transact(owner.project) { tx =>
          val provenance = Provenance(owner.actor, 0, RequestId(UUID.randomUUID()))
          val ids = (1 to 130).toList.map { _ =>
            val id = tx.allocate(Ledger.Tasks)
            tx.put(Item(id, Revision(1), large, 0, 0, provenance))
            id
          }
          ids.tail.foreach(id => tx.edge(LedgerPolicy.canonical(ids.head, Relation.Produces, id), true))
          ids
        }
        oversized <- service.previewWorkset(owner, WorksetTarget.Inline(Set(largeIds.head), WorkflowPhase.Work)).either
        _ <- assertIO(fault(oversized).exists(_.isInstanceOf[Fault.Limit]))
      } yield ()
    }
  }
}

final class StoredWorksetContractDummy extends StoredWorksetContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class StoredWorksetContractPostgres extends StoredWorksetContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
