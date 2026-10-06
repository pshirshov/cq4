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
          "Assign each Task to an Open milestone before work starts"))
        case assigned => assigned.find(_.draft.content match {
          case Content.Milestone(MilestoneStatus.Open, _) => false
          case _ => true
        }).map { closed =>
          val name = LedgerPolicy.name(closed.id)
          MilestoneRefusal(CohortReason.ClosedMilestone, s"Work refused: $task's milestone $name is ${LedgerPolicy.status(closed.draft.content)}. " +
            s"Reopen $name or reassign $task to an Open milestone before work starts")
        }
      }
    }

  def admit(work: DispatchWork, members: List[ItemView], milestone: ItemId => Item): Unit =
    members.view.flatMap(refusal(work, _, milestone)).headOption.foreach(value => throw DomainFailure(Fault.Invalid(value.message)))

  // The closure gate: a milestone does not become Complete or Cancelled while a Task it contains is non-terminal (Ready or Active).
  // It is judged on the state a request leaves, so a request that also makes those Tasks terminal passes.
  def closes(before: Content, after: Content): Boolean = (before, after) match {
    case (Content.Milestone(was, _), Content.Milestone(now, _)) => now != MilestoneStatus.Open && now != was
    case _ => false
  }

  def nonTerminal(task: ItemId, status: String): Boolean = task.ledger == Ledger.Tasks && !LedgerPolicy.outcome(Ledger.Tasks, status).terminal

  // `tasks` are the contained non-terminal Tasks with their statuses.
  def closure(milestone: ItemId, status: MilestoneStatus, tasks: List[(ItemId, String)]): String = {
    val name = LedgerPolicy.name(milestone)
    val listed = tasks.sortBy((id, _) => LedgerPolicy.key(id)).map((id, state) => s"${LedgerPolicy.name(id)} ($state)").mkString(", ")
    s"$name cannot be changed to $status while it contains non-terminal Tasks: $listed. " +
      s"Make each Done or Cancelled, or reassign it to another Open milestone, before closing $name"
  }

  // The same refusal for a termination, whose selection retains those Tasks unchanged.
  def retention(milestone: ItemId, status: MilestoneStatus, tasks: List[(ItemId, String)]): String =
    closure(milestone, status, tasks) + s". This termination leaves them unchanged; add them to its roots to terminate them with ${LedgerPolicy.name(milestone)}"
}
