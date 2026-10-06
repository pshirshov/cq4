package cq.host

import cq.api.*
import cq.core.{CandidateAuthorship, DomainFailure, GoverningWorkPolicy, IntegrationPolicy, Scope}
import java.time.{Clock, Duration}
import scala.annotation.tailrec

/** Worker and reviewer results whose evidence establishes an independently reviewed candidate. */
final case class ReviewedCandidate(ticket: IntegrationTicket, workerId: ArtifactId, worker: ChildResult, authorship: CandidateAuthorship,
  validation: ValidationCitations, fence: Fence) {
  def candidate: GitCommit = worker.candidate.get
}

/** A host rebase of the reviewed candidate: `commit` merges it onto the advanced target `head`. */
final case class AppliedRebase(head: GitCommit, commit: GitCommit, evidence: IntegrationRebase)

final class IntegrationPreparation(api: ServerApi, owner: Scope, repository: String, target: String,
  checks: List[ValidationCheck], clock: Clock, bases: ExecutionBase) {
  private val PreparationNanos = Duration.ofSeconds(60).toNanos
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val AttemptPageSize = 200

  /** The deadline bounds the server calls of one operation; host checks of a rebased commit run between operations. */
  private def bounded[A](operation: (Command => Result) => A): A = {
    val began = System.nanoTime()
    operation { command =>
      require(System.nanoTime() - began < PreparationNanos, "Integration preparation deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case value => value
      }
    }
  }
  private def renew(call: Command => Result, fence: Fence, members: Set[ItemId]): Unit =
    call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(fence, ClaimMillis)))) match {
      case Result.Claimed(claim) => require(claim.owner == owner.actor && claim.fence == fence && !claim.released &&
        claim.members == members && claim.expiresAt > clock.millis(), "Integration claim no longer covers this assignment")
      case _ => throw new IllegalStateException("Integration claim renewal returned an unexpected result")
    }
  private def claimed(call: Command => Result, worker: ChildResult): Fence = {
    val members = worker.request.members.map(_.id).toSet
    val preview = call(Command.Read(ReadInput(owner.project, ReadSelection.Claims(members)))) match {
      case Result.Claims(value) => value
      case _ => throw new IllegalStateException("Integration claim read returned an unexpected result")
    }
    val matches = preview.claims.filter(claim => claim.owner == owner.actor && claim.members == members && !claim.released && claim.expiresAt > clock.millis())
    require(matches.size == 1, "Integration requires the current full governing claim")
    matches.head.fence
  }

  /** The roles of the attempts that made and reviewed the candidate, as the server registered them. Both attempts are assigned the
    * candidate's members, so they are among the attempts of any one member. */
  private def authorship(call: Command => Result, worker: ChildResult, reviewer: ChildResult): CandidateAuthorship = {
    val filter = UsageFilter.TaskOnly(worker.request.members.head.id)
    def page(after: Option[AttemptId], snapshot: Option[Long]): AttemptPage =
      call(Command.Usage(UsageInput(owner.project, UsageSelection.Attempts(filter, after, snapshot, AttemptPageSize)))) match {
        case Result.UsageAttempts(value) => value
        case _ => throw new IllegalStateException("Attempt read returned an unexpected result")
      }
    @tailrec def listed(found: Map[AttemptId, Role], current: AttemptPage): Map[AttemptId, Role] = {
      val known = found ++ current.entries.map(_.attempt).filter(attempt => attempt.id == worker.attempt || attempt.id == reviewer.attempt)
        .map(attempt => attempt.id -> attempt.role)
      if (known.size == 2 || !current.hasMore) known else listed(known, page(current.after, Some(current.cursor)))
    }
    // A later page is read against the first one's usage cursor; usage recorded in between restarts the listing.
    @tailrec def roles(): Map[AttemptId, Role] =
      (try Some(listed(Map.empty, page(None, None))) catch { case DomainFailure(_: Fault.Resync) => None }) match {
        case Some(value) => value
        case None => roles()
      }
    val known = roles()
    def role(attempt: AttemptId): Role = known.getOrElse(attempt, throw new IllegalStateException("Integration result attempt is not registered"))
    CandidateAuthorship(role(worker.attempt), role(reviewer.attempt))
  }

  def review(ticket: IntegrationTicket): ReviewedCandidate = bounded { call =>
    val reader = new ArtifactReader(call, owner.project)
    val drafts = new HistoricalDrafts(call, owner.project)
    val review = reader.result(ticket.reviewer)
    require(review.value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate), "Integration requires a reviewer result handle")
    val workerId = review.value.request.previous.getOrElse(throw new IllegalArgumentException("Review has no worker handle"))
    val work = reader.result(workerId)
    val worker = work.value
    val reviewer = review.value
    List(work, review).foreach { value =>
      require(value.admission.owner.role == Role.Governor && value.metadata.actor.session == value.admission.owner.session &&
        value.metadata.actor.role == Role.Collector, "Integration evidence has inconsistent governing provenance")
    }
    require(worker.request.work.isInstanceOf[DispatchWork.Worker] && worker.request.work != DispatchWork.Worker(WorkerMode.Probe) &&
      reviewer.attempt != worker.attempt && drafts.unchanged(worker.request.members, reviewer.request.members) &&
      worker.candidate.nonEmpty && reviewer.candidate == worker.candidate && reviewer.base == worker.candidate.get,
      "Integration requires independently reviewed exact worker output")
    require(worker.report match { case ChildReport.Work(members) => members.forall(_.disposition == WorkDisposition.CandidateReady); case _ => false },
      "Integration requires every worker member to be ready")
    require(reviewer.report match { case ChildReport.Review(members, _) => members.forall(_.verdict == ReviewVerdict.Accepted); case _ => false },
      "Integration requires every reviewer member to be accepted")
    // The server applies the same rule to the same roles when it reserves; the mode is read now, not taken from the activation.
    val authorship = this.authorship(call, worker, reviewer)
    if (authorship.governing) GoverningWorkPolicy.integrate(ProcessModes.setting(call, owner.project), authorship, checks.size)
    val evidence = IntegrationValidation.applicable(IntegrationValidation.effective(owner.project, work.metadata.actor.session, workerId, worker, checks,
      reader.amendments(workerId)), reviewer)
    def publisher(author: AttemptId): SessionId = if (author == reviewer.attempt) review.metadata.actor.session else work.metadata.actor.session
    evidence.passing.foreach { expected =>
      val stored = reader.read(expected.evidence.artifact)
      IntegrationValidation.verify(owner.project, publisher(expected.author), worker.candidate.get, expected,
        stored.metadata, IntegrationValidation.decode(stored))
    }
    evidence.failed.foreach { expected =>
      val stored = reader.read(expected.artifact)
      IntegrationValidation.verifyFailure(owner.project, publisher(expected.author), worker.candidate.get, expected,
        stored.metadata, IntegrationValidation.decode(stored))
    }
    val fence = claimed(call, worker)
    renew(call, fence, worker.request.members.map(_.id).toSet)
    ReviewedCandidate(ticket, workerId, worker, authorship, evidence.citations, fence)
  }

  def renew(reviewed: ReviewedCandidate): Unit = bounded(renew(_, reviewed.fence, reviewed.worker.request.members.map(_.id).toSet))

  /** Without a rebase the intent expects the reviewed candidate at the target; with one it expects the rebased commit on the advanced head. */
  def freeze(reviewed: ReviewedCandidate, rebase: Option[AppliedRebase]): IntegrationIntent = bounded { call =>
    val drafts = new HistoricalDrafts(call, owner.project)
    val worker = reviewed.worker
    val ticket = reviewed.ticket
    val items = worker.request.members.map { reference =>
      call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetail(reference.id)))) match {
        case Result.Detail(value) =>
          require(value.item.id == reference.id && drafts.unchanged(reference, ItemRevision(value.item.id, value.item.revision)), "Integration member content changed")
          value.item
        case _ => throw new IllegalStateException("Integration member read returned an unexpected result")
      }
    }
    val candidate = rebase.fold(reviewed.candidate)(_.commit)
    val change = IntegrationPolicy.completion(ticket.id, repository, target, candidate, rebase.map(_.evidence), reviewed.workerId, ticket.reviewer,
      reviewed.authorship, reviewed.validation.established, reviewed.validation.failed, reviewed.validation.rounds, reviewed.fence, items)
    renew(call, reviewed.fence, worker.request.members.map(_.id).toSet)
    IntegrationIntent(ticket.id, owner.project, owner.actor, repository, target,
      rebase.fold(bases.expected(worker.base, reviewed.candidate))(_.head), candidate,
      reviewed.workerId, ticket.reviewer, checks, reviewed.fence, items.map(item => ItemRevision(item.id, item.revision)), change, rebase.map(_.evidence))
  }
}
