package cq.core

import cq.api.*

object MilestonePolicy {
  def missing(work: DispatchWork, member: ItemView): Boolean = work match {
    case DispatchWork.Worker(WorkerMode.Implement | WorkerMode.ResolveConflict) =>
      member.item.id.ledger == Ledger.Tasks && !member.refs.exists(_.relation == Relation.PartOf)
    case _ => false
  }

  def admit(work: DispatchWork, members: List[ItemView]): Unit = members.find(missing(work, _)).foreach { member =>
    throw DomainFailure(Fault.Invalid(s"Work refused: ${LedgerPolicy.prefix(member.item.id.ledger)}${member.item.id.number} has no milestone. " +
      "A Planner must assign each Task to a milestone under plan review before work starts"))
  }
}
