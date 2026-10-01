package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.IO

// Rows written by the installed release carry the pre-D89 classification (`terminal: true` for adopted decisions and current
// memories) in `cq_items.summary`; every read must derive the outcome from the persisted status instead.
final class StaleSummaryPostgres extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
    activation = Activation(Repo -> Repo.Prod),
  )
  private def request(mutations: List[Mutation]): ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Stale summary scenario")

  "Persisted summaries (Behavioral Active Blackbox; PostgreSQL Good Communication)" should {
    "derive the outcome from the persisted status rather than the stored summary" in { (service: LedgerService[IO], database: LedgerDatabase) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
      val adopted = ItemDraft("Adopted decision", "", Set.empty, false, Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil), Nil)
      val done = ItemDraft("Done task", "", Set.empty, false, Content.Task(TaskStatus.Done, List("Observable result"), None, Nil), Nil)
      val ready = ItemDraft("Ready task", "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil)
      for {
        _ <- service.initialize(owner, "stale summaries")
        created <- service.change(owner, request(List(Mutation.Create(adopted), Mutation.Create(done), Mutation.Create(ready))))
        decision = created.items.head
        finished = created.items(1)
        work = created.items(2)
        linked <- service.change(owner, request(List(Mutation.Reference(finished.id, finished.revision, Relation.DerivedFrom, decision.id, decision.revision, true))))
        _ <- service.change(owner, request(List(Mutation.Reference(work.id, work.revision, Relation.DerivedFrom, decision.id, linked.items.find(_.id == decision.id).get.revision, true))))
        stale <- database.transaction { connection =>
          new Jdbc(connection).execute("UPDATE cq_items SET summary = jsonb_set(summary, '{outcome,terminal}', 'true') WHERE project_id = ? AND ledger = ? AND number = ?") { s =>
            s.setObject(1, owner.project.value); s.setString(2, decision.id.ledger.toString); s.setLong(3, decision.id.number)
          }
        }
        _ <- assertIO(stale == 1)
        preview <- service.archivePreview(owner, "", 50)
        _ <- assertIO(preview.members.map(_.id) == List(finished.id) && preview.retained.isEmpty)
        found <- service.search(owner, "ledger:Decisions", None, 200)
        _ <- assertIO(found.items.map(_.outcome) == List(ItemOutcome(false, true)))
        browsed <- service.browse(owner, "ledger:Decisions", ItemOrder(ItemOrderField.Id, SortDirection.Ascending), None, None, 200)
        _ <- assertIO(browsed.items.map(_.summary.outcome) == List(ItemOutcome(false, true)))
        workset <- service.workset(owner, Set(decision.id), None, None, 200)
        _ <- assertIO(workset.entries.find(_.item.id == decision.id).map(_.reasons).contains(List(WorksetReason.Settled())) && workset.readyCount == 1)
        subgraphs <- service.subgraphs(owner, None, None, 32)
        _ <- assertIO(subgraphs.entries.map(_.root.id) == List(work.id))
        current <- service.get(owner, decision.id)
        archive <- service.change(owner, request(List(Mutation.Archive(List(ItemRevision(decision.id, current.item.revision)))))).either
        _ <- assertIO(archive.left.exists { case DomainFailure(_: Fault.Invalid) => true; case _ => false })
      } yield ()
    }
  }
}
