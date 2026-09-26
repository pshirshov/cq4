package cq.core

import cq.api.*

object LedgerPolicy {
  final case class Outcome(terminal: Boolean, satisfiesDependency: Boolean)
  val MaxBatch = 64
  val MaxPage = 200
  val MaxBody = 65536
  val MaxTitle = 300
  val MaxLabels = 32
  val MaxLabel = 80
  val MaxRefs = 200
  val MaxClaimMillis = 300000L

  def invalid(condition: Boolean, message: String): Unit =
    if (!condition) throw DomainFailure(Fault.Invalid(message))

  def ledger(content: Content): Ledger = content match {
    case _: Content.Milestone => Ledger.Milestones
    case _: Content.Idea => Ledger.Ideas
    case _: Content.Defect => Ledger.Defects
    case _: Content.Goal => Ledger.Goals
    case _: Content.Task => Ledger.Tasks
    case _: Content.Research => Ledger.Researches
    case _: Content.Hypothesis => Ledger.Hypothesis
    case _: Content.Question => Ledger.Questions
    case _: Content.Decision => Ledger.Decisions
    case _: Content.Review => Ledger.Reviews
    case _: Content.Handoff => Ledger.Handoffs
    case _: Content.OperatorAction => Ledger.OperatorActions
    case _: Content.Memory => Ledger.Memories
    case _: Content.Upstream => Ledger.Upstream
  }

  def prefix(value: Ledger): String = value match {
    case Ledger.Milestones => "M"
    case Ledger.Ideas => "I"
    case Ledger.Defects => "D"
    case Ledger.Goals => "G"
    case Ledger.Tasks => "T"
    case Ledger.Researches => "RS"
    case Ledger.Hypothesis => "H"
    case Ledger.Questions => "Q"
    case Ledger.Decisions => "K"
    case Ledger.Reviews => "R"
    case Ledger.Handoffs => "HO"
    case Ledger.OperatorActions => "OA"
    case Ledger.Memories => "MEM"
    case Ledger.Upstream => "U"
  }

  def status(value: Content): String = value match {
    case c: Content.Milestone => c.status.toString
    case c: Content.Idea => c.status.toString
    case c: Content.Defect => c.status.toString
    case c: Content.Goal => c.status.toString
    case c: Content.Task => c.status.toString
    case c: Content.Research => c.status.toString
    case c: Content.Hypothesis => c.status.toString
    case c: Content.Question => c.status.toString
    case c: Content.Decision => c.status.toString
    case c: Content.Review => c.status.toString
    case c: Content.Handoff => c.status.toString
    case c: Content.OperatorAction => c.status.toString
    case c: Content.Memory => c.status.toString
    case c: Content.Upstream => c.status.toString
  }

  def outcome(value: Content): Outcome = value match {
    case c: Content.Milestone => Outcome(c.status != MilestoneStatus.Open, c.status == MilestoneStatus.Complete)
    case c: Content.Idea => Outcome(c.status != IdeaStatus.Proposed, c.status == IdeaStatus.Accepted)
    case c: Content.Defect => Outcome(c.status != DefectStatus.Open, c.status == DefectStatus.Resolved)
    case c: Content.Goal => Outcome(c.status != GoalStatus.Open, c.status == GoalStatus.Achieved)
    case c: Content.Task => Outcome(Set[TaskStatus](TaskStatus.Done, TaskStatus.Cancelled).contains(c.status), c.status == TaskStatus.Done)
    case c: Content.Research => Outcome(Set[ResearchStatus](ResearchStatus.Concluded, ResearchStatus.Inconclusive, ResearchStatus.Cancelled).contains(c.status), c.status == ResearchStatus.Concluded)
    case c: Content.Hypothesis => Outcome(!Set[HypothesisStatus](HypothesisStatus.Proposed, HypothesisStatus.Investigating).contains(c.status), Set[HypothesisStatus](HypothesisStatus.Supported, HypothesisStatus.Refuted).contains(c.status))
    case c: Content.Question => Outcome(c.status != QuestionStatus.Open, c.status == QuestionStatus.Answered)
    case c: Content.Decision => Outcome(c.status != DecisionStatus.Proposed, c.status == DecisionStatus.Adopted)
    case c: Content.Review => Outcome(!Set[ReviewStatus](ReviewStatus.Pending, ReviewStatus.Active).contains(c.status), c.status == ReviewStatus.Approved)
    case c: Content.Handoff => Outcome(c.status != HandoffStatus.Open, c.status == HandoffStatus.Accepted)
    case c: Content.OperatorAction => Outcome(!Set[OperatorActionStatus](OperatorActionStatus.Requested, OperatorActionStatus.Confirmed).contains(c.status), c.status == OperatorActionStatus.Observed)
    case c: Content.Memory => Outcome(true, c.status == MemoryStatus.Current)
    case c: Content.Upstream => Outcome(!Set[UpstreamStatus](UpstreamStatus.Identified, UpstreamStatus.Reported).contains(c.status), c.status == UpstreamStatus.Resolved)
  }

  def validate(draft: ItemDraft): Unit = {
    invalid(draft.title.trim.nonEmpty && draft.title.length <= MaxTitle, s"Title must contain 1–$MaxTitle characters")
    invalid(draft.body.length <= MaxBody, s"Body exceeds $MaxBody characters")
    invalid(draft.labels.size <= MaxLabels && draft.labels.forall(s => s.trim.nonEmpty && s.length <= MaxLabel), "Invalid labels")
    def text(value: String, field: String): Unit = invalid(value.trim.nonEmpty && value.length <= MaxBody, s"Invalid $field")
    def texts(values: List[String], field: String): Unit = {
      invalid(values.nonEmpty && values.size <= MaxPage, s"Invalid $field")
      values.foreach(text(_, field))
    }
    draft.content match {
      case c: Content.Milestone => text(c.objective, "objective")
      case c: Content.Idea => text(c.outcome, "outcome"); text(c.motivation, "motivation")
      case c: Content.Defect => text(c.observed, "observed"); text(c.expected, "expected"); text(c.reproduction, "reproduction")
      case c: Content.Goal => text(c.outcome, "outcome"); texts(c.acceptance, "acceptance"); text(c.scope, "scope")
      case c: Content.Task => texts(c.acceptance, "acceptance")
      case c: Content.Research => text(c.question, "research question")
      case c: Content.Hypothesis => text(c.claim, "claim"); text(c.rationale, "rationale")
      case c: Content.Question => text(c.prompt, "question"); text(c.context, "context")
      case c: Content.Decision => text(c.choice, "choice"); text(c.rationale, "rationale")
      case c: Content.Review => invalid(c.subjects.nonEmpty || c.candidate.nonEmpty, "Review requires an item revision or candidate commit")
      case c: Content.Handoff => text(c.outcome, "outcome")
      case c: Content.OperatorAction => text(c.action, "action"); text(c.expectedEvidence, "expected evidence")
      case c: Content.Memory => text(c.knowledge, "knowledge"); text(c.applicability, "applicability")
      case c: Content.Upstream => text(c.component, "component"); text(c.version, "version"); text(c.reproduction, "reproduction")
    }
  }

  def inverse(relation: Relation): Relation = relation match {
    case Relation.DerivedFrom => Relation.Produces
    case Relation.Produces => Relation.DerivedFrom
    case Relation.PartOf => Relation.Contains
    case Relation.Contains => Relation.PartOf
    case Relation.BlockedBy => Relation.Blocks
    case Relation.Blocks => Relation.BlockedBy
    case Relation.Reviews => Relation.ReviewedBy
    case Relation.ReviewedBy => Relation.Reviews
    case Relation.Supports => Relation.SupportedBy
    case Relation.SupportedBy => Relation.Supports
    case Relation.Contradicts => Relation.ContradictedBy
    case Relation.ContradictedBy => Relation.Contradicts
    case Relation.Supersedes => Relation.SupersededBy
    case Relation.SupersededBy => Relation.Supersedes
    case Relation.RelatesTo => Relation.RelatesTo
  }

  def key(id: ItemId): (String, Long) = (id.ledger.toString, id.number)

  def canonical(source: ItemId, relation: Relation, target: ItemId): CanonicalEdge = {
    invalid(source != target, "Self references are not allowed")
    invalid(source.project == target.project, "References cannot cross projects")
    relation match {
      case Relation.Produces | Relation.Contains | Relation.Blocks | Relation.ReviewedBy |
           Relation.SupportedBy | Relation.ContradictedBy | Relation.SupersededBy =>
        CanonicalEdge(target, inverse(relation), source)
      case Relation.RelatesTo if Ordering[(String, Long)].gt(key(source), key(target)) => CanonicalEdge(target, relation, source)
      case _ => CanonicalEdge(source, relation, target)
    }
  }

  def endpoints(edge: CanonicalEdge): Unit = edge.relation match {
    case Relation.PartOf =>
      invalid(edge.target.ledger == Ledger.Milestones, "PartOf target must be a milestone")
      invalid(!Set[Ledger](Ledger.Milestones, Ledger.Ideas, Ledger.Defects, Ledger.Goals).contains(edge.source.ledger),
        "Milestones organize process artifacts, not intake or goal ownership")
    case Relation.Reviews => invalid(edge.source.ledger == Ledger.Reviews, "Reviews source must be a review")
    case _ => ()
  }
}
