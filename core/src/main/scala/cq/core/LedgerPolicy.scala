package cq.core

import cq.api.*

object LedgerPolicy {
  val MaxBatch = 64
  val MaxPage = 200
  val MaxBody = 65536
  val MaxTitle = 300
  val MaxLabels = 32
  val MaxLabel = 80
  val MaxRefs = 200
  val MaxClaimMillis = 1800000L
  val MaxNestedEntries = 64
  val MaxLocation = 2048
  val MaxDraftBytes = 262144
  val MaxTouchedItems = 512
  // Standing requirements reach every Planner, Worker and reviewer untruncated, beside a session request of at most 16384 code points.
  val MaxRequirementsCodePoints = 8192

  def invalid(condition: Boolean, message: String): Unit =
    if (!condition) throw DomainFailure(Fault.Invalid(message))

  def validateRequirements(text: String): Unit = {
    val count = text.codePointCount(0, text.length)
    invalid(count <= MaxRequirementsCodePoints, s"Standing requirements exceed $MaxRequirementsCodePoints code points: $count supplied")
    invalid(!text.contains('\u0000') && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text), "Standing requirements contain invalid Unicode or NUL")
  }

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

  def name(id: ItemId): String = s"${prefix(id.ledger)}${id.number}"

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

  def outcome(value: Content): ItemOutcome = outcome(ledger(value), status(value))

  private def parsed[A](ledger: Ledger, status: String)(parse: String => Option[A]): A =
    parse(status).getOrElse(throw new IllegalStateException(s"Invalid persisted $ledger status $status"))

  // Outcome classifications derive from the ledger and its status alone, so a summary read back from storage recomputes them.
  def outcome(ledger: Ledger, status: String): ItemOutcome = {
    def of[A](parse: String => Option[A])(terminal: A => Boolean, satisfies: A => Boolean): ItemOutcome = {
      val value = parsed(ledger, status)(parse)
      ItemOutcome(terminal(value), satisfies(value))
    }
    ledger match {
      case Ledger.Milestones => of(MilestoneStatus.parse)(_ != MilestoneStatus.Open, _ == MilestoneStatus.Complete)
      case Ledger.Ideas => of(IdeaStatus.parse)(!Set[IdeaStatus](IdeaStatus.Proposed, IdeaStatus.Accepted).contains(_), _ == IdeaStatus.Implemented)
      case Ledger.Defects => of(DefectStatus.parse)(_ != DefectStatus.Open, _ == DefectStatus.Resolved)
      case Ledger.Goals => of(GoalStatus.parse)(_ != GoalStatus.Open, _ == GoalStatus.Achieved)
      case Ledger.Tasks => of(TaskStatus.parse)(Set[TaskStatus](TaskStatus.Done, TaskStatus.Cancelled).contains, _ == TaskStatus.Done)
      case Ledger.Researches => of(ResearchStatus.parse)(Set[ResearchStatus](ResearchStatus.Concluded, ResearchStatus.Inconclusive, ResearchStatus.Cancelled).contains, _ == ResearchStatus.Concluded)
      case Ledger.Hypothesis => of(HypothesisStatus.parse)(!Set[HypothesisStatus](HypothesisStatus.Proposed, HypothesisStatus.Investigating).contains(_), Set[HypothesisStatus](HypothesisStatus.Supported, HypothesisStatus.Refuted).contains)
      case Ledger.Questions => of(QuestionStatus.parse)(_ != QuestionStatus.Open, _ == QuestionStatus.Answered)
      case Ledger.Decisions => of(DecisionStatus.parse)(Set[DecisionStatus](DecisionStatus.Superseded, DecisionStatus.Withdrawn).contains, _ == DecisionStatus.Adopted)
      case Ledger.Reviews => of(ReviewStatus.parse)(!Set[ReviewStatus](ReviewStatus.Pending, ReviewStatus.Active).contains(_), _ == ReviewStatus.Approved)
      case Ledger.Handoffs => of(HandoffStatus.parse)(_ != HandoffStatus.Open, _ == HandoffStatus.Accepted)
      case Ledger.OperatorActions => of(OperatorActionStatus.parse)(!Set[OperatorActionStatus](OperatorActionStatus.Requested, OperatorActionStatus.Confirmed).contains(_), _ == OperatorActionStatus.Observed)
      case Ledger.Memories => of(MemoryStatus.parse)(Set[MemoryStatus](MemoryStatus.Superseded, MemoryStatus.Retracted).contains, _ == MemoryStatus.Current)
      case Ledger.Upstream => of(UpstreamStatus.parse)(!Set[UpstreamStatus](UpstreamStatus.Identified, UpstreamStatus.Reported).contains(_), _ == UpstreamStatus.Resolved)
    }
  }

  // A settled record is a reference record in its live state (an adopted decision, a current memory): neither terminal nor open work.
  def settled(ledger: Ledger, status: String): Boolean = ledger match {
    case Ledger.Decisions => parsed(ledger, status)(DecisionStatus.parse) == DecisionStatus.Adopted
    case Ledger.Memories => parsed(ledger, status)(MemoryStatus.parse) == MemoryStatus.Current
    case _ => false
  }
  def settled(item: ItemSummary): Boolean = settled(item.id.ledger, item.status)
  def settled(content: Content): Boolean = settled(ledger(content), status(content))

  // Open work: neither terminal nor settled; an open item is also unarchived.
  def open(ledger: Ledger, status: String): Boolean = !outcome(ledger, status).terminal && !settled(ledger, status)
  def open(item: ItemSummary): Boolean = !item.archived && open(item.id.ledger, item.status)

  def statuses(ledger: Ledger): List[String] = ledger match {
    case Ledger.Milestones => MilestoneStatus.all.map(_.toString)
    case Ledger.Ideas => IdeaStatus.all.map(_.toString)
    case Ledger.Defects => DefectStatus.all.map(_.toString)
    case Ledger.Goals => GoalStatus.all.map(_.toString)
    case Ledger.Tasks => TaskStatus.all.map(_.toString)
    case Ledger.Researches => ResearchStatus.all.map(_.toString)
    case Ledger.Hypothesis => HypothesisStatus.all.map(_.toString)
    case Ledger.Questions => QuestionStatus.all.map(_.toString)
    case Ledger.Decisions => DecisionStatus.all.map(_.toString)
    case Ledger.Reviews => ReviewStatus.all.map(_.toString)
    case Ledger.Handoffs => HandoffStatus.all.map(_.toString)
    case Ledger.OperatorActions => OperatorActionStatus.all.map(_.toString)
    case Ledger.Memories => MemoryStatus.all.map(_.toString)
    case Ledger.Upstream => UpstreamStatus.all.map(_.toString)
  }

  def validateCitation(value: Citation): Unit = value match {
    case Citation.Url(address) =>
      invalid(address.length <= MaxLocation && scala.util.Try {
        val uri = java.net.URI.create(address)
        Set("http", "https").contains(uri.getScheme) && uri.getHost != null
      }.getOrElse(false), "Citation URL must be an absolute HTTP(S) address")
    case Citation.File(path, revision) =>
      invalid(path.trim.nonEmpty && path.length <= MaxLocation && !path.contains('\u0000'), "Invalid cited file path")
      revision.foreach(r => invalid(r.trim.nonEmpty && r.length <= MaxLocation, "Invalid cited file revision"))
    case Citation.Commit(repository, hash) =>
      invalid(repository.trim.nonEmpty && repository.length <= MaxLocation, "Invalid cited repository")
      invalid(hash.matches("[0-9a-fA-F]{4,64}"), "Invalid cited commit hash")
    case _: Citation.Artifact => ()
  }

  def validate(draft: ItemDraft): Unit = {
    invalid(!draft.archived || outcome(draft.content).terminal || settled(draft.content), "Only terminal or settled items may be archived; unarchive an item before reopening it")
    invalid(draft.title.trim.nonEmpty && draft.title.length <= MaxTitle, s"Title must contain 1–$MaxTitle characters")
    invalid(draft.body.length <= MaxBody, s"Body exceeds $MaxBody characters")
    invalid(draft.labels.size <= MaxLabels && draft.labels.forall(s => s.trim.nonEmpty && s.length <= MaxLabel), "Invalid labels")
    def text(value: String, field: String): Unit = invalid(value.trim.nonEmpty && value.length <= MaxBody, s"Invalid $field")
    // Operator intake (ideas, defects, goals) may leave narrative fields empty; planning fills them in later.
    def bounded(value: String, field: String): Unit = invalid(value.length <= MaxBody, s"Invalid $field")
    def texts(values: List[String], field: String): Unit = {
      invalid(values.size <= MaxNestedEntries, s"Invalid $field")
      values.foreach(text(_, field))
    }
    def optional(value: Option[String], field: String): Unit = value.foreach(text(_, field))
    def citations(values: List[Citation]): Unit = {
      invalid(values.size <= MaxNestedEntries, "Too many citations")
      values.foreach(validateCitation)
    }
    citations(draft.citations)
    val observations = evidence(draft.content)
    invalid(observations.size <= MaxNestedEntries, "Too many evidence entries")
    observations.foreach { value => text(value.description, "evidence description"); citations(value.citations) }
    draft.content match {
      case c: Content.Milestone => text(c.objective, "objective")
      case c: Content.Idea => bounded(c.outcome, "outcome"); bounded(c.motivation, "motivation")
      case c: Content.Defect => bounded(c.observed, "observed"); bounded(c.expected, "expected"); bounded(c.reproduction, "reproduction"); optional(c.cause, "cause")
      case c: Content.Goal => bounded(c.outcome, "outcome"); texts(c.acceptance, "acceptance"); bounded(c.scope, "scope")
      case c: Content.Task => invalid(c.acceptance.nonEmpty, "Acceptance is required"); texts(c.acceptance, "acceptance"); optional(c.result, "result")
      case c: Content.Research => text(c.question, "research question"); optional(c.conclusion, "conclusion"); optional(c.recommendation, "recommendation")
      case c: Content.Hypothesis => text(c.claim, "claim"); text(c.rationale, "rationale"); optional(c.adjudication, "adjudication")
      case c: Content.Question =>
        text(c.prompt, "question"); text(c.context, "context"); texts(c.alternatives, "alternatives"); optional(c.answer, "answer")
        c.recommendation.foreach { value =>
          invalid(c.alternatives.indices.contains(value.alternative), "Recommended alternative must index the alternatives list")
          text(value.reason, "recommendation reason")
        }
      case c: Content.Decision => text(c.choice, "choice"); text(c.rationale, "rationale"); texts(c.alternatives, "alternatives")
      case c: Content.Review =>
        invalid(c.subjects.nonEmpty || c.candidate.nonEmpty, "Review requires an item revision or candidate commit")
        invalid(c.subjects.size <= MaxNestedEntries && c.subjects.distinct.size == c.subjects.size, "Invalid reviewed subjects")
        c.subjects.foreach(s => invalid(s.item.number > 0 && s.revision.value > 0, "Invalid reviewed revision"))
        c.candidate.foreach(validateCitation); optional(c.summary, "summary")
      case c: Content.Handoff => text(c.outcome, "outcome"); texts(c.remaining, "remaining work"); texts(c.blockers, "blockers")
      case c: Content.OperatorAction => text(c.action, "action"); text(c.expectedEvidence, "expected evidence"); optional(c.confirmation, "confirmation")
      case c: Content.Memory => text(c.knowledge, "knowledge"); text(c.applicability, "applicability")
      case c: Content.Upstream => text(c.component, "component"); text(c.version, "version"); text(c.reproduction, "reproduction"); c.report.foreach(validateCitation); optional(c.outcome, "upstream outcome")
    }
    val encoded = ItemDraft_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, draft)
    val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
    def representable(value: io.circe.Json): Boolean = value.fold(
      true, _ => true, _ => true,
      text => !text.contains('\u0000') && encoder.canEncode(text),
      _.forall(representable), _.values.forall(representable),
    )
    invalid(representable(encoded), "Item text must contain scalar Unicode without NUL")
    val bytes = encoded.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    if (bytes > MaxDraftBytes) throw DomainFailure(Fault.Limit(s"Item draft exceeds $MaxDraftBytes UTF-8 bytes"))
  }

  def evidence(content: Content): List[Evidence] = content match {
    case c: Content.Defect => c.resolution
    case c: Content.Task => c.validation
    case c: Content.Research => c.findings
    case c: Content.Hypothesis => c.evidence
    case c: Content.Review => c.findings
    case c: Content.OperatorAction => c.observedEvidence
    case c: Content.Memory => c.evidence
    case _ => Nil
  }

  def provenance(role: Role, draft: ItemDraft, recorded: List[ItemDraft]): Unit = {
    val existing = recorded.flatMap(d => evidence(d.content)).toSet
    evidence(draft.content).foreach { value =>
      val permitted = value.origin match {
        case EvidenceOrigin.ModelDeclared => true
        case EvidenceOrigin.HumanReported => role == Role.Human || existing.contains(value)
        case EvidenceOrigin.HostObserved => existing.contains(value)
      }
      if (!permitted) throw DomainFailure(Fault.Denied("Declared evidence cannot fabricate human or host provenance"))
    }
    draft.content match {
      case c: Content.OperatorAction if c.confirmation.nonEmpty && role != Role.Human =>
        if (!recorded.exists(_.content match { case previous: Content.OperatorAction => previous.confirmation == c.confirmation && previous.action == c.action && previous.expectedEvidence == c.expectedEvidence; case _ => false }))
          throw DomainFailure(Fault.Denied("Operator confirmation requires human authority"))
      case _ => ()
    }
  }

  def recommended(draft: ItemDraft): Unit = draft.content match {
    case c: Content.Question => invalid(c.status != QuestionStatus.Open || c.alternatives.isEmpty || c.recommendation.nonEmpty,
      "An agent-created Question with alternatives must state its recommended alternative and reason")
    case _ => ()
  }

  // An agent that revises an item without changing its recorded content (a reference, a title) has not written the Question.
  def recommendation(role: Role, draft: ItemDraft, recorded: List[ItemDraft]): Unit =
    if (role != Role.Human && !recorded.exists(_.content == draft.content)) recommended(draft)

  // An archived item is terminal or settled (validate), so an Open milestone is also unarchived.
  def openMilestone(status: MilestoneStatus, name: String): Unit =
    invalid(status == MilestoneStatus.Open, s"Tasks can be assigned only to an Open milestone; $name is $status")
  def batchMilestone(index: Int): String = s"the Milestone created at index $index of this batch"

  def summary(item: Item): ItemSummary = ItemSummary(item.id, item.revision, item.draft.title,
    status(item.draft.content), item.draft.archived, item.draft.labels, item.updatedAt, outcome(item.draft.content))

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
  def bulkArchivable(tx: LedgerTransaction, id: ItemId, status: String): Boolean = {
    outcome(id.ledger, status).terminal || (id.ledger == Ledger.Decisions && status == DecisionStatus.Adopted.toString && {
      val anchors = tx.refs(id).filter(ref => ref.relation == Relation.DerivedFrom || ref.relation == Relation.PartOf).map(_.target).distinct
      anchors.nonEmpty && anchors.forall(target => tx.summary(target).getOrElse(
        throw new IllegalStateException("Decision scope anchor is missing")).archived)
    })
  }

  // A terminal item stays out of archival while any related item (either direction) is still open; settled records retain nothing.
  def openRelated(tx: LedgerTransaction, id: ItemId): List[ItemId] =
    tx.refs(id).map(_.target).distinct.filter(target => tx.summary(target).exists(open)).sortBy(key)

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
