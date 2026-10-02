package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

final class TerminationPlanner(worksets: WorksetTraversal) {
  import LedgerPolicy.{invalid, key}

  def preview(tx: LedgerTransaction, scope: Scope, roots: Set[ItemId], intent: TerminationIntent, now: Long): TerminationPreview = {
    invalid(roots.nonEmpty, "Termination requires explicit roots")
    val graph = worksets.collect(tx, roots, None)
    val original = graph.entries.filter(_.role == WorksetRole.Selected).map(_.item.id).toSet
    def shared(id: ItemId, selected: Set[ItemId]): List[ItemId] = graph.references(id).collect {
      case ItemRef(Relation.DerivedFrom, producer) if !selected.contains(producer) => producer
    }
    def consumers(id: ItemId): List[ItemId] = graph.references(id).collect {
      case ItemRef(Relation.Blocks, consumer) if original.contains(consumer) => consumer
    }
    def reachable(allowed: Set[ItemId]): Set[ItemId] = {
      val found = mutable.Set.from(roots)
      val pending = mutable.Queue.from(roots.toList.sortBy(key))
      while (pending.nonEmpty) graph.references(pending.dequeue()).foreach { ref =>
        if ((ref.relation == Relation.Produces || ref.relation == Relation.Contains) && allowed.contains(ref.target) && found.add(ref.target))
          pending.enqueue(ref.target)
      }
      found.toSet
    }
    var selected = original
    var settled = false
    while (!settled) {
      val eligible = selected.filter(id => roots.contains(id) || (shared(id, selected).isEmpty && consumers(id).isEmpty))
      val next = reachable(eligible)
      settled = next == selected
      selected = next
    }
    val summaries = graph.entries.map(entry => entry.item.id -> entry.item).toMap
    // The closure gate: the contained Tasks this termination would leave non-terminal under a milestone it closes. A selected Task becomes
    // terminal with the milestone, so these are the Tasks excluded as shared, as a prerequisite or with an excluded branch.
    def retained(milestone: ItemId): List[(ItemId, String)] = graph.references(milestone).collect {
      case ItemRef(Relation.Contains, member) if !selected.contains(member) => member -> summaries(member).status
    }.filter((task, state) => MilestonePolicy.nonTerminal(task, state))
    val entries = graph.entries.map { entry =>
      val id = entry.item.id
      val effect = if (selected.contains(id)) {
        if (entry.item.outcome.terminal || LedgerPolicy.settled(entry.item)) TerminationEffect.Preserve()
        else target(id.ledger, intent) match {
          case TerminationEffect.Change(TerminalStatus.Milestone(status)) if retained(id).nonEmpty =>
            TerminationEffect.Unsupported(MilestonePolicy.retention(id, status, retained(id)))
          case other => other
        }
      } else {
        val reasons = if (entry.role == WorksetRole.Context) entry.reasons.collect {
          case WorksetReason.Context(source, relation) => TerminationExclusion.Context(source, relation)
        } else {
          val specific = shared(id, selected).map(TerminationExclusion.Shared.apply) ++ consumers(id).map(TerminationExclusion.Prerequisite.apply)
          if (specific.nonEmpty) specific else List(TerminationExclusion.ExcludedBranch())
        }
        TerminationEffect.Excluded(reasons)
      }
      TerminationEntry(entry.item, effect)
    }
    if (entries.count(_.effect.isInstanceOf[TerminationEffect.Change]) > LedgerPolicy.MaxTouchedItems)
      throw DomainFailure(Fault.Limit(s"Termination changes at most ${LedgerPolicy.MaxTouchedItems} items; choose narrower roots"))
    val claims = ClaimPolicy.overlapping(tx, selected, now).map { claim =>
        TerminationClaim(claim.fence, claim.owner, claim.members.toList.sortBy(key), claim.members.intersect(selected).toList.sortBy(key),
          scope.actor.role == Role.Human || scope.actor == claim.owner)
      }
    val integrations = IntegrationPolicy.pending(tx, selected ++ claims.flatMap(_.members))
    val plan = TerminationPlan(roots.toList.sortBy(key), intent, entries, claims, integrations,
      integrations.isEmpty && !entries.exists(_.effect.isInstanceOf[TerminationEffect.Unsupported]) && claims.forall(_.permitted))
    val encoded = TerminationPlan_JsonCodec.encode(BaboonCodecContext.Default, plan)
    val digest = PreviewDigest(scope, encoded)
    val result = TerminationPreview(plan, TerminationSnapshot(graph.snapshot.cursor, digest))
    if (TerminationPreview_JsonCodec.encode(BaboonCodecContext.Default, result).noSpaces.getBytes(UTF_8).length > ReadPage.MaxBytes - ReadPage.EnvelopeBytes)
      throw DomainFailure(Fault.Limit("Termination preview exceeds the encoded-byte bound; choose narrower roots"))
    result
  }

  private def target(ledger: Ledger, intent: TerminationIntent): TerminationEffect = {
    def change(value: TerminalStatus): TerminationEffect = TerminationEffect.Change(value)
    intent match {
      case TerminationIntent.Complete => ledger match {
        case Ledger.Milestones => change(TerminalStatus.Milestone(MilestoneStatus.Complete))
        case Ledger.Goals => change(TerminalStatus.Goal(GoalStatus.Achieved))
        case Ledger.Tasks => change(TerminalStatus.Task(TaskStatus.Done))
        case _ => TerminationEffect.Unsupported(s"$ledger requires an explicit typed outcome; generic completion cannot supply its judgment or evidence")
      }
      case TerminationIntent.Cancel => ledger match {
        case Ledger.Milestones => change(TerminalStatus.Milestone(MilestoneStatus.Cancelled))
        case Ledger.Ideas => change(TerminalStatus.Idea(IdeaStatus.Withdrawn))
        case Ledger.Defects => change(TerminalStatus.Defect(DefectStatus.Withdrawn))
        case Ledger.Goals => change(TerminalStatus.Goal(GoalStatus.Abandoned))
        case Ledger.Tasks => change(TerminalStatus.Task(TaskStatus.Cancelled))
        case Ledger.Researches => change(TerminalStatus.Research(ResearchStatus.Cancelled))
        case Ledger.Hypothesis => change(TerminalStatus.Hypothesis(HypothesisStatus.Withdrawn))
        case Ledger.Questions => change(TerminalStatus.Question(QuestionStatus.Withdrawn))
        case Ledger.Decisions => change(TerminalStatus.Decision(DecisionStatus.Withdrawn))
        case Ledger.Reviews => change(TerminalStatus.Review(ReviewStatus.Cancelled))
        case Ledger.Handoffs => change(TerminalStatus.Handoff(HandoffStatus.Cancelled))
        case Ledger.OperatorActions => change(TerminalStatus.OperatorAction(OperatorActionStatus.Cancelled))
        case Ledger.Memories => throw new IllegalStateException("A memory is settled or terminal and is preserved")
        case Ledger.Upstream => change(TerminalStatus.Upstream(UpstreamStatus.Withdrawn))
      }
    }
  }

  def applyStatus(content: Content, status: TerminalStatus): Content = (content, status) match {
    case (c: Content.Milestone, TerminalStatus.Milestone(value)) => c.copy(status = value)
    case (c: Content.Idea, TerminalStatus.Idea(value)) => c.copy(status = value)
    case (c: Content.Defect, TerminalStatus.Defect(value)) => c.copy(status = value)
    case (c: Content.Goal, TerminalStatus.Goal(value)) => c.copy(status = value)
    case (c: Content.Task, TerminalStatus.Task(value)) => c.copy(status = value)
    case (c: Content.Research, TerminalStatus.Research(value)) => c.copy(status = value)
    case (c: Content.Hypothesis, TerminalStatus.Hypothesis(value)) => c.copy(status = value)
    case (c: Content.Question, TerminalStatus.Question(value)) => c.copy(status = value)
    case (c: Content.Decision, TerminalStatus.Decision(value)) => c.copy(status = value)
    case (c: Content.Review, TerminalStatus.Review(value)) => c.copy(status = value)
    case (c: Content.Handoff, TerminalStatus.Handoff(value)) => c.copy(status = value)
    case (c: Content.OperatorAction, TerminalStatus.OperatorAction(value)) => c.copy(status = value)
    case (c: Content.Memory, TerminalStatus.Memory(value)) => c.copy(status = value)
    case (c: Content.Upstream, TerminalStatus.Upstream(value)) => c.copy(status = value)
    case _ => throw new IllegalStateException("Termination status does not match the item ledger")
  }
}
