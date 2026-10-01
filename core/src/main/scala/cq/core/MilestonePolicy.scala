package cq.core

import cq.api.*

final case class MilestoneRefusal(reason: CohortReason, message: String)

object MilestonePolicy {
  private def governed(work: DispatchWork, member: ItemView): Boolean = work match {
    case DispatchWork.Worker(WorkerMode.Implement | WorkerMode.ResolveConflict) => member.item.id.ledger == Ledger.Tasks
    case _ => false
  }

  // Implementation work requires the Task under an Open milestone; `milestone` supplies the current record of a PartOf target.
  def refusal(work: DispatchWork, member: ItemView, milestone: ItemId => Item): Option[MilestoneRefusal] =
    if (!governed(work, member)) None
    else {
      val task = LedgerPolicy.name(member.item.id)
      member.refs.collect { case ItemRef(Relation.PartOf, id) => milestone(id) } match {
        case Nil => Some(MilestoneRefusal(CohortReason.NoMilestone, s"Work refused: $task has no milestone. " +
          "A Planner must assign each Task to a milestone under plan review before work starts"))
        case assigned => assigned.find(_.draft.content match {
          case Content.Milestone(MilestoneStatus.Open, _) => false
          case _ => true
        }).map(closed => MilestoneRefusal(CohortReason.ClosedMilestone, s"Work refused: $task's milestone ${LedgerPolicy.name(closed.id)} is " +
          s"${LedgerPolicy.status(closed.draft.content)}; a Planner must reassign it under plan review"))
      }
    }

  def admit(work: DispatchWork, members: List[ItemView], milestone: ItemId => Item): Unit =
    members.view.flatMap(refusal(work, _, milestone)).headOption.foreach(value => throw DomainFailure(Fault.Invalid(value.message)))
}
