package cq.core

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

final case class PreparedProposal(value: LedgerProposal, mutations: List[Mutation])

object ProposalPolicy {
  import LedgerPolicy.*

  def request(id: ArtifactId): RequestId = RequestId(UUID.nameUUIDFromBytes(("cq-proposal:" + id.value).getBytes(UTF_8)))

  def prepare(work: DispatchWork, members: List[ItemRevision], report: ChildReport): Option[PreparedProposal] = {
    val (proposal, eligible) = (work, report) match {
      case (_: DispatchWork.Planner, ChildReport.Plan(entries, value, _)) =>
        invalid(!entries.exists(_.disposition == PlanDisposition.Proposed) || value.nonEmpty, "Proposed members require a stored proposal")
        (value, entries.filter(_.disposition == PlanDisposition.Proposed).map(_.item).toSet)
      case (DispatchWork.Reviewer(mode), ChildReport.Review(entries, value)) =>
        invalid(mode != ReviewerMode.Candidate || value.isEmpty, "Candidate reviewers cannot propose ledger changes")
        (value, entries.filter(_.verdict == ReviewVerdict.ChangesRequested).map(_.item).toSet)
      case _ => (None, Set.empty[ItemId])
    }
    proposal.map { value =>
      invalid(eligible.nonEmpty && eligible.subsetOf(members.map(_.id).toSet), "Proposal requires eligible assigned members")
      invalid(value.mutations.nonEmpty && value.mutations.size <= MaxBatch, "Invalid proposal mutation count")
      invalid(value.reason.trim.nonEmpty && value.reason.length <= MaxTitle, "Proposal reason required")
      val revisions = members.map(ref => ref.id -> ref.revision).toMap
      def revision(id: ItemId): Revision = {
        invalid(eligible(id), "Proposal endpoint is outside its eligible assignment")
        revisions(id)
      }
      def draft(value: ItemDraft): ItemDraft = {
        validate(value)
        recommended(value)
        value.content match {
          case memory: Content.Memory => invalid(memory.status == MemoryStatus.Current && memory.evidence.nonEmpty && memory.evidence.forall(_.citations.nonEmpty),
            "Proposed Memory requires Current status and cited evidence")
          case _ => ()
        }
        value
      }
      def task(value: ItemDraft): Boolean = ledger(value.content) == Ledger.Tasks
      val mutations = value.mutations.zipWithIndex.map {
        case (ProposedMutation.Create(value), _) =>
          invalid(!task(value), "Proposed Tasks must be produced from an assigned producer and assigned to a milestone")
          Mutation.Create(draft(value))
        case (ProposedMutation.Replace(id, value), _) => Mutation.Replace(id, revision(id), draft(value))
        case (ProposedMutation.Produce(producer, values, milestone), index) =>
          invalid(values.nonEmpty && values.size <= MaxBatch, "Invalid proposed production count")
          invalid(milestone.nonEmpty || !values.exists(task),
            "Produce creates Tasks without a milestone: assign an existing Open milestone or a Milestone created earlier in this proposal")
          milestone.foreach { assigned =>
            invalid(values.exists(task), "A Produce milestone requires a Task draft")
            assigned match {
              case MilestoneRef.Existing(id) => invalid(id.ledger == Ledger.Milestones, "PartOf target must be a milestone")
              case MilestoneRef.Created(mutation) => value.mutations.take(index).lift(mutation).collect {
                case ProposedMutation.Create(created) => created.content
              } match {
                case Some(created: Content.Milestone) => openMilestone(created.status, batchMilestone(mutation))
                case _ => invalid(false, "Produce milestone must reference an earlier Create of a Milestone in this batch")
              }
            }
          }
          Mutation.Produce(producer, revision(producer), values.map(draft), milestone)
        case (ProposedMutation.Reference(source, relation, target, present), _) =>
          endpoints(canonical(source, relation, target))
          Mutation.Reference(source, revision(source), relation, target, revision(target), present)
      }
      PreparedProposal(value, mutations)
    }
  }

  def status(content: Content): TerminalStatus = content match {
    case c: Content.Milestone => TerminalStatus.Milestone(c.status)
    case c: Content.Idea => TerminalStatus.Idea(c.status)
    case c: Content.Defect => TerminalStatus.Defect(c.status)
    case c: Content.Goal => TerminalStatus.Goal(c.status)
    case c: Content.Task => TerminalStatus.Task(c.status)
    case c: Content.Research => TerminalStatus.Research(c.status)
    case c: Content.Hypothesis => TerminalStatus.Hypothesis(c.status)
    case c: Content.Question => TerminalStatus.Question(c.status)
    case c: Content.Decision => TerminalStatus.Decision(c.status)
    case c: Content.Review => TerminalStatus.Review(c.status)
    case c: Content.Handoff => TerminalStatus.Handoff(c.status)
    case c: Content.OperatorAction => TerminalStatus.OperatorAction(c.status)
    case c: Content.Memory => TerminalStatus.Memory(c.status)
    case c: Content.Upstream => TerminalStatus.Upstream(c.status)
  }

  def summary(draft: ItemDraft): ProposalDraftSummary = ProposalDraftSummary(ledger(draft.content), draft.title, status(draft.content), draft.archived)

  def fields(before: ItemDraft, after: ItemDraft): ProposalFieldChanges = {
    def confirmation(draft: ItemDraft): Option[String] = draft.content match {
      case c: Content.OperatorAction => c.confirmation
      case _ => None
    }
    ProposalFieldChanges(before.body != after.body, before.labels != after.labels, before.content != after.content, before.citations != after.citations,
      evidence(before.content) != evidence(after.content), evidence(before.content).map(_.origin) != evidence(after.content).map(_.origin),
      confirmation(before) != confirmation(after))
  }
}
