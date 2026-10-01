package cq.server

import cq.api.*
import cq.core.{LedgerService, Scope}
import java.util.UUID
import zio.{IO, ZIO}

object MilestoneFixture {
  val Milestone: ItemDraft = ItemDraft("Fixture milestone", "", Set.empty, false, Content.Milestone(MilestoneStatus.Open, "Deliver the fixture tasks"), Nil)

  /** Work is admitted only for Tasks under a milestone: creates the drafts, assigns each to one new Open milestone and returns their assigned revisions. */
  def assigned(ledger: LedgerService[IO], scope: Scope, drafts: List[ItemDraft]): IO[Throwable, List[ItemRevision]] = for {
    created <- ledger.change(scope, ChangeRequest(RequestId(UUID.randomUUID()), Mutation.Create(Milestone) :: drafts.map(Mutation.Create.apply), Nil, "Milestone and its tasks"))
    target = created.items.head.id
    _ <- ZIO.foreachDiscard(created.items.tail) { member => for {
      milestone <- ledger.get(scope, target)
      _ <- ledger.change(scope, ChangeRequest(RequestId(UUID.randomUUID()),
        List(Mutation.Reference(member.id, member.revision, Relation.PartOf, target, milestone.item.revision, true)), Nil, "Milestone assignment"))
    } yield () }
    current <- ZIO.foreach(created.items.tail)(member => ledger.get(scope, member.id))
  } yield current.map(view => ItemRevision(view.item.id, view.item.revision))
}
