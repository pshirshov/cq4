package cq.host

import cq.api.*
import cq.core.{CohortBounds, DomainFailure, LedgerPolicy, Scope, WorksetTraversal}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

final case class CohortPlan(evidence: CohortEvidence, fingerprints: Map[RequestId, CohortExecutionFingerprint])

final class CohortPlanner(api: ServerApi, owner: Scope, bases: ExecutionBase, checks: List[ValidationCheck], progress: CohortProgress,
  requirements: OperatorRequirements) {
  private val DeadlineNanos = Duration.ofSeconds(60).toNanos
  private val PageSize = 200
  private val RequestBytes = 16 * 1024
  private final case class Context(guidance: List[ItemView], artifacts: List[ResolvedArtifact], operative: List[CohortArtifactFingerprint], results: List[AdmittedResult],
    operativeResults: List[CohortResultFingerprint], assessments: List[ExecutedResult], reviews: List[ExecutedResult], previous: Option[ChildResult], base: GitCommit) {
    def executionBase: GitCommit = previous.flatMap(_.candidate).getOrElse(base)
  }

  private def caller(): Command => Result = {
    val began = System.nanoTime()
    command => {
      require(System.nanoTime() - began < DeadlineNanos, "Cohort selection deadline exceeded")
      api.call(command) match { case Result.Failed(fault) => throw DomainFailure(fault); case value => value }
    }
  }

  private def validate(request: CohortRequest): Unit = {
    require(request.roots.nonEmpty && request.roots.size <= WorksetTraversal.MaxRoots &&
      request.roots.forall(id => id.project == owner.project && id.number > 0), "Cohort selection requires explicit project-local roots")
    require(request.guidance.size <= CohortBounds.References && request.guidance.map(_.id).distinct.size == request.guidance.size &&
      request.guidance.forall(ref => ref.id.project == owner.project && ref.id.number > 0 && ref.revision.value > 0) &&
      request.artifacts.size <= CohortBounds.References && request.artifacts.distinct.size == request.artifacts.size, "Invalid cohort context references")
    require(HostFiles.encode(CohortRequest_JsonCodec, request).getBytes(UTF_8).length <= RequestBytes, "Cohort request exceeds its byte bound")
    require(!Set[DispatchWork](DispatchWork.Reviewer(ReviewerMode.Plan), DispatchWork.Reviewer(ReviewerMode.Candidate),
      DispatchWork.Worker(WorkerMode.ResolveConflict))(request.work) || request.previous.nonEmpty,
      "This cohort operation requires its exact previous result")
  }

  private def graph(call: Command => Result, request: CohortRequest): (List[WorksetEntry], WorksetSnapshot) = {
    var after = Option.empty[ItemId]
    var snapshot = Option.empty[WorksetSnapshot]
    var entries = List.empty[WorksetEntry]
    var more = true
    while (more) {
      val page = call(Command.Graph(GraphInput(owner.project, request.roots, after, snapshot, PageSize))) match {
        case Result.Workset(value) => value
        case _ => throw new IllegalStateException("Cohort workset returned an unexpected result")
      }
      entries ++= page.entries
      require(entries.size <= WorksetTraversal.MaxItems && (!page.hasMore || (page.entries.nonEmpty && page.after != after)),
        "Cohort workset exceeded its traversal bound or failed to advance")
      after = page.after
      snapshot = Some(page.snapshot)
      more = page.hasMore
    }
    (entries, snapshot.get)
  }

  private def details(call: Command => Result, members: List[ItemRevision]): ItemViews = {
    if (members.isEmpty) ItemViews(Nil, Nil)
    else call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetails(members, CohortBounds.CandidateBytes)))) match {
      case Result.Details(value) =>
        require((value.items.map(view => ItemRevision(view.item.id, view.item.revision)) ++ value.omitted).toSet == members.toSet &&
          value.items.size + value.omitted.size == members.size &&
          value.items.map(view => HostFiles.encode(ItemView_JsonCodec, view).getBytes(UTF_8).length.toLong).sum <= CohortBounds.CandidateBytes,
          "Cohort content response violates its assignment or byte budget")
        value
      case _ => throw new IllegalStateException("Cohort content read returned an unexpected result")
    }
  }

  // The fresh base is resolved once per selection or verification so every choice of one decision shares the same observed target head.
  private def context(call: Command => Result, request: CohortRequest, base: GitCommit): Context = {
    val reader = new ArtifactReader(call, owner.project)
    val guidance = details(call, request.guidance)
    require(guidance.omitted.isEmpty, "Cohort guidance exceeds its content budget")
    val artifacts = request.artifacts.map(reader.read)
    val results = artifacts.filter(_.metadata.kind == ArtifactKind.Result).map(value => reader.result(value.metadata.id))
    val previous = request.previous.map(reader.result)
    val sources = (results ++ previous).distinct
    val assessments = sources.collect { case value if value.value.report match {
      case plan: ChildReport.Plan => plan.assessments.nonEmpty
      case _ => false
    } => reader.assessment(value.metadata.id) }
    val reviews = sources.filter(_.value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate)).map(value => reader.review(value.metadata.id))
    val cache = scala.collection.mutable.Map.from(artifacts.map(value => value.metadata.id -> value))
    def read(id: ArtifactId): ResolvedArtifact = cache.getOrElseUpdate(id, reader.read(id))
    val operative = artifacts.map(value => CohortArtifacts(value, read))
    val operativeResults = sources.map(value => CohortArtifacts.result(value, read))
    Context(guidance.items, artifacts, operative, results, operativeResults, assessments, reviews, previous.map(_.value), base)
  }

  private def producers(value: ItemView): Set[ItemId] = value.refs.collect { case ItemRef(Relation.DerivedFrom, id) => id }.toSet
  private def independent(members: List[ItemView]): Boolean = {
    val ids = members.map(_.item.id).toSet
    members.forall(value => !value.refs.exists(ref => Set[Relation](Relation.Blocks, Relation.BlockedBy)(ref.relation) && ids(ref.target)))
  }
  private def supports(work: DispatchWork, id: ItemId): Boolean =
    work != DispatchWork.Worker(WorkerMode.Implement) || id.ledger == Ledger.Tasks
  private def blocked(entry: WorksetEntry): Boolean = entry.reasons.exists(_.isInstanceOf[WorksetReason.Blocked])
  // Blocking constrains execution, not planning: a Planner may organise an open Selected entry whose only non-readiness is
  // an unsatisfied BlockedBy (Decision 10). An explicit terminal root stays plannable for its reopening only while unblocked.
  private def ready(work: DispatchWork, entry: WorksetEntry): Boolean =
    entry.ready || (work == DispatchWork.Planner() && entry.role == WorksetRole.Selected && !entry.item.archived && (
      (entry.root && entry.item.outcome.terminal && !blocked(entry)) ||
      (!entry.item.outcome.terminal && blocked(entry))))
  private def fingerprint(work: DispatchWork, members: List[ItemView], context: Context): String =
    CohortFingerprint(work, members, context.guidance, context.operative, context.operativeResults, context.executionBase, checks)
  private def executionFingerprint(work: DispatchWork, members: List[ItemView], context: Context, reason: CohortReason): CohortExecutionFingerprint = {
    val semantic = fingerprint(work, members, context)
    if (reason == CohortReason.AssessmentRequired) {
      require(work == DispatchWork.Planner(), "Only an assessment Planner may use revision-specific progress")
      val group = CohortFingerprint.assessment(semantic, members.map(member => ItemRevision(member.item.id, member.item.revision)))
      CohortExecutionFingerprint(group, members.map(member => member.item.id -> group).toMap)
    } else CohortExecutionFingerprint(semantic, members.map(member => member.item.id -> fingerprint(work, List(member), context)).toMap)
  }

  private def assessments(context: Context): List[CohortAssessment] = context.assessments
    .filter(value => value.input.base == context.executionBase && value.input.checks.sortBy(_.name) == checks.sortBy(_.name))
    .flatMap(_.result.value.report.asInstanceOf[ChildReport.Plan].assessments)

  private def compatibility(members: List[ItemView], context: Context): Option[CohortCompatibility] = {
    val refs = members.map(value => ItemRevision(value.item.id, value.item.revision)).toSet
    val applicable = assessments(context).filter(_.members.map(_.member).toSet == refs)
    require(applicable.map(_.compatibility).distinct.size <= 1, "Conflicting cohort assessments require an explicit narrower context")
    applicable.headOption.map(_.compatibility)
  }

  private def assessedGroup(first: ItemView, pending: List[ItemView], request: CohortRequest, context: Context): Option[List[ItemView]] = {
    val current = pending.map(value => value.item.id -> value).toMap
    val candidates = assessments(context).map(_.members.map(_.member).toSet).distinct.filter(refs =>
      refs.exists(_.id == first.item.id) && refs.forall(ref => current.contains(ref.id)))
    val exact = candidates.filter(_.forall(ref => current(ref.id).item.revision == ref.revision))
    val groups = (if (exact.nonEmpty) exact else candidates).map(_.map(_.id)).distinct
      .map(ids => pending.filter(value => ids(value.item.id)))
      .filter(group => independent(group) && fits(request, request.work, group, context))
    require(groups.size <= 1, "Overlapping applicable cohort groups require an explicit narrower context")
    groups.headOption
  }

  private def reviewDisposition(member: ItemView, work: DispatchWork, context: Context): Option[CohortReason] = {
    if (work != DispatchWork.Worker(WorkerMode.Implement)) None
    else {
      val ref = ItemRevision(member.item.id, member.item.revision)
      val candidates = context.previous.flatMap(_.candidate).fold(context.results.flatMap(_.value.candidate).distinct)(List(_))
      val reviews = context.reviews.filter(value => value.input.checks.sortBy(_.name) == checks.sortBy(_.name) &&
        candidates.size == 1 && value.result.value.candidate.contains(candidates.head)).map(_.result.value).filter(_.request.members.contains(ref))
      val verdicts = reviews.flatMap(_.report.asInstanceOf[ChildReport.Review].members.filter(_.item == ref.id).map(_.verdict)).distinct
      require(verdicts.size <= 1, "Conflicting per-member review feedback requires an explicit narrower context")
      verdicts.headOption.flatMap {
        case ReviewVerdict.Accepted if reviews.forall(value => value.validation.map(_.check).toSet == checks.map(_.name).toSet &&
          value.validation.forall(_.state == ValidationState.Passed)) => Some(CohortReason.ReviewAccepted)
        case ReviewVerdict.Blocked => Some(CohortReason.ReviewBlocked)
        case _ => None
      }
    }
  }

  private def fits(request: CohortRequest, work: DispatchWork, members: List[ItemView], context: Context): Boolean = {
    val refs = members.map(value => ItemRevision(value.item.id, value.item.revision))
    val dispatch = DispatchRequest(request.request, work, Harness.Claude, refs, request.guidance, request.artifacts, request.previous,
      Fence(ClaimId(request.request.value), Long.MaxValue), request.limits)
    // The budget check carries the operator requirements assembly will deliver, so an offered cohort cannot fail at assembly.
    val input = ChildInput(owner.project, dispatch, members, context.guidance, context.artifacts, context.previous,
      OperatorRequirements.delivered(work, requirements.current))
    refs.map(_.id).toSet.intersect(request.guidance.map(_.id).toSet).isEmpty &&
      HostFiles.encode(ChildInput_JsonCodec, input).getBytes(UTF_8).length <= ChildContracts.MaxInputBytes
  }

  def plan(original: CohortRequest, artifact: ArtifactId): CohortPlan = {
    validate(original)
    val call = caller()
    val (entries, snapshot) = graph(call, original)
    val selected = entries.filter(_.role == WorksetRole.Selected)
    val byId = selected.map(entry => entry.item.id -> entry).toMap
    // Planner organisation: an explicitly rooted Milestone, or an entry derived from a producer inside the Selected scope.
    def organisable(value: ItemView): Boolean =
      (value.item.id.ledger == Ledger.Milestones && byId.get(value.item.id).exists(_.root)) || producers(value).exists(byId.contains)
    val planner = original.work == DispatchWork.Planner()
    val order = progress.order(selected.map(_.item.id))
    val base = bases.fresh()
    val originalContext = context(call, original, base)
    val partition = original.work == DispatchWork.Worker(WorkerMode.Implement) && originalContext.previous.exists(_.report.isInstanceOf[ChildReport.Plan])
    val request = if (partition) original.copy(artifacts = (original.artifacts ++ original.previous).distinct, previous = None) else original
    validate(request)
    val ctx = if (partition) context(call, request, base) else originalContext
    val exact = originalContext.previous.map(_.request.members)
    // A member revised only by reference or provenance changes since the previous result continues at its current revision (D80).
    val drafts = new HistoricalDrafts(call, owner.project)
    exact.foreach(members => require(members.forall(ref => byId.get(ref.id).exists(entry =>
      drafts.unchanged(ref, ItemRevision(ref.id, entry.item.revision)) && supports(request.work, ref.id))),
      "Exact previous cohort is outside the selection, stale or incompatible with the operation"))
    val eligible = order.filter(id => ready(request.work, byId(id)) && supports(request.work, id) && exact.forall(_.exists(_.id == id)))
    val automatic = exact.isEmpty || partition
    val candidates = if (automatic) progress.pool(eligible, CohortBounds.Candidates).map(id => ItemRevision(id, byId(id).item.revision))
      else exact.get.map(ref => ItemRevision(ref.id, byId(ref.id).item.revision))
    val loaded = details(call, candidates)
    val views = loaded.items.map(value => value.item.id -> value).toMap
    val claim = if (loaded.items.isEmpty) None else Some(call(Command.Read(ReadInput(owner.project, ReadSelection.Claims(views.keySet)))) match {
      case Result.Claims(value) => value
      case _ => throw new IllegalStateException("Cohort claim read returned an unexpected result")
    })
    val held = claim.toList.flatMap(_.claims.filter(_.owner != owner.actor).flatMap(_.members)).toSet ++
      claim.toList.flatMap(_.integrations.flatMap(_.members.map(_.id))).toSet
    val excludedMembers = loaded.items.flatMap(value => {
      val reason = if (held(value.item.id)) Some(CohortReason.Claimed) else reviewDisposition(value, request.work, ctx)
      reason.map(value.item.id -> _)
    }).toMap
    var pending = candidates.flatMap(ref => views.get(ref.id)).filterNot(value => excludedMembers.contains(value.item.id))
    var considered = loaded.items.filter(value => excludedMembers.contains(value.item.id)).map(value =>
      CohortConsidered(List(ItemRevision(value.item.id, value.item.revision)), excludedMembers(value.item.id), None))
    val choices = List.newBuilder[CohortChoice]
    var fingerprints = Map.empty[RequestId, CohortExecutionFingerprint]
    var offered = 0

    def offer(group: List[ItemView], work: DispatchWork, reason: CohortReason, witness: Option[ItemId], inputs: CohortRequest, content: Context): Unit = {
      val refs = group.map(value => ItemRevision(value.item.id, value.item.revision))
      val hash = executionFingerprint(work, group, content, reason)
      val deferred = group.filter(member => progress.deferred(hash.members(member.item.id)))
      if (deferred.nonEmpty && deferred.size < group.size && inputs.previous.isEmpty) {
        considered :+= CohortConsidered(deferred.map(value => ItemRevision(value.item.id, value.item.revision)), CohortReason.Deferred, Some(hash.group))
        group.filterNot(deferred.contains).take(CohortBounds.Choices - offered).foreach(member =>
          offer(List(member), work, if (reason == CohortReason.AssessmentRequired) reason else CohortReason.Single, None, inputs, content))
      } else {
        val excluded = if (!fits(inputs, work, group, content)) Some(CohortReason.InputBound)
          else if (progress.deferred(hash.group) || deferred.nonEmpty) Some(CohortReason.Deferred) else None
        considered :+= CohortConsidered(refs, excluded.getOrElse(reason), Some(hash.group))
        if (excluded.isEmpty) {
          val id = RequestId(UUID.randomUUID())
          val choiceReason = if (work == DispatchWork.Worker(WorkerMode.Implement) && inputs.previous.isEmpty && content.results.exists(_.value.candidate.nonEmpty))
            CohortReason.FreshFromBase else reason
          choices += CohortChoice(id, work, refs, inputs.guidance, inputs.artifacts, inputs.previous, inputs.limits,
            if (refs.size > 1) Some(UUID.randomUUID()) else None, choiceReason, witness)
          fingerprints += id -> hash
          offered += 1
        }
      }
    }

    if (exact.nonEmpty && !partition) {
      require(loaded.omitted.isEmpty && !candidates.exists(ref => held(ref.id)), "Exact previous cohort is too large or claimed by another session")
      def continuity(): Unit = if (pending.nonEmpty) considered :+= CohortConsidered(
        pending.map(value => ItemRevision(value.item.id, value.item.revision)), CohortReason.CandidateContinuity, None)
      if (pending.size != candidates.size) continuity()
      else if (request.work == DispatchWork.Worker(WorkerMode.Implement) && pending.size > CohortBounds.Members) continuity()
      else if (request.work == DispatchWork.Worker(WorkerMode.Implement) && pending.size > 1) compatibility(pending, ctx) match {
        case Some(CohortCompatibility.Compatible) => offer(pending, request.work, CohortReason.CompatibleAssessment, None, request, ctx)
        case None => offer(pending, DispatchWork.Planner(), CohortReason.AssessmentRequired, None, request, ctx)
        case Some(_) if ctx.previous.exists(_.candidate.nonEmpty) => continuity()
        case Some(value) =>
          val split = request.copy(artifacts = (request.artifacts ++ request.previous).distinct, previous = None)
          validate(split)
          val splitContext = context(call, split, base)
          val reason = if (value == CohortCompatibility.Unknown) CohortReason.UnknownAssessment else CohortReason.IncompatibleAssessment
          pending.foreach(member => offer(List(member), request.work, reason, None, split, splitContext))
      } else offer(pending, request.work, CohortReason.ExactPrevious, None, request, ctx)
      pending = Nil
    } else while (pending.nonEmpty && offered < CohortBounds.Choices) {
      val first = pending.head
      val assessed = if (request.work == DispatchWork.Worker(WorkerMode.Implement)) assessedGroup(first, pending, request, ctx) else None
      var group = assessed.getOrElse(List(first))
      var common = producers(first)
      val mayGroup = request.work match {
        case _: DispatchWork.Explorer | _: DispatchWork.Planner | DispatchWork.Worker(WorkerMode.Probe) | DispatchWork.Worker(WorkerMode.Implement) => true
        case _ => false
      }
      var organised = false
      pending.tail.filter(_ => mayGroup && assessed.isEmpty).foreach { candidate =>
        val next = group :+ candidate
        val shared = common.intersect(producers(candidate))
        if (group.size < CohortBounds.Members && fits(request, request.work, next, ctx)) {
          // Decision 10: Blocks/BlockedBy constrain execution, not planning, so Planner groups skip the independence check.
          if (!organised && shared.nonEmpty && (planner || independent(next))) {
            group = next
            common = shared
          } else if (planner && next.forall(organisable)) {
            group = next
            organised = true
          }
        }
      }
      var work = request.work
      var reason = if (group.size > 1 && organised) CohortReason.PlannerOrganisation
        else if (group.size > 1) CohortReason.CommonProducer else CohortReason.NoCommonProducer
      if (request.work == DispatchWork.Worker(WorkerMode.Implement) && group.size > 1) {
        compatibility(group, ctx) match {
          case Some(CohortCompatibility.Compatible) => reason = CohortReason.CompatibleAssessment
          case Some(value) =>
            reason = if (value == CohortCompatibility.Unknown) CohortReason.UnknownAssessment else CohortReason.IncompatibleAssessment
            group = List(first)
          case None => work = DispatchWork.Planner(); reason = CohortReason.AssessmentRequired
        }
      }
      offer(group, work, reason, if (group.size > 1 && assessed.isEmpty && !organised) common.toList.sortBy(LedgerPolicy.key).headOption else None, request, ctx)
      val ids = group.map(_.item.id).toSet
      pending = pending.filterNot(value => ids(value.item.id))
    }
    call(Command.Graph(GraphInput(owner.project, request.roots, None, Some(snapshot), 1)))
    val chosen = choices.result()
    val excluded = considered.filter(value => Set(CohortReason.Claimed, CohortReason.Deferred, CohortReason.InputBound,
      CohortReason.ReviewAccepted, CohortReason.ReviewBlocked, CohortReason.CandidateContinuity)(value.reason)).map(_.members.size).sum
    val counts = CohortCounts(entries.size, selected.size, loaded.items.size, excluded,
      eligible.size - loaded.items.count(value => eligible.contains(value.item.id)), selected.count(entry => !ready(request.work, entry)),
      selected.count(entry => ready(request.work, entry) && !supports(request.work, entry.item.id)), selected.size - chosen.map(_.members.size).sum)
    val decision = CohortDecision(request.request, artifact, counts, chosen)
    require(HostFiles.encode(CohortDecision_JsonCodec, decision).getBytes(UTF_8).length <= CohortBounds.ReplyBytes, "Cohort reply exceeds its byte bound")
    if (automatic) progress.inspected(candidates.map(_.id), chosen.nonEmpty)
    CohortPlan(CohortEvidence(original, decision, considered), fingerprints)
  }

  def verify(request: CohortRequest, choice: CohortChoice, expected: CohortExecutionFingerprint): Unit = {
    val call = caller()
    val (entries, snapshot) = graph(call, request)
    val current = entries.filter(_.role == WorksetRole.Selected).map(entry => entry.item.id -> entry).toMap
    require(choice.members.forall(ref => current.get(ref.id).exists(entry => entry.item.revision == ref.revision &&
      (choice.previous.nonEmpty || ready(choice.work, entry)))), "Selected cohort is no longer selected, ready or current")
    val loaded = details(call, choice.members)
    require(loaded.omitted.isEmpty, "Selected cohort no longer fits its content budget")
    val inputs = request.copy(work = choice.work, guidance = choice.guidance, artifacts = choice.artifacts, previous = choice.previous)
    val ctx = context(call, inputs, bases.fresh())
    require(fits(inputs, choice.work, loaded.items, ctx) && executionFingerprint(choice.work, loaded.items, ctx, choice.reason) == expected,
      "Cohort operative input changed; select again")
    call(Command.Graph(GraphInput(owner.project, request.roots, None, Some(snapshot), 1)))
  }
}
