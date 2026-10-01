package cq.host

import cq.api.*
import cq.core.{DomainFailure, IntegrationPolicy, Scope}
import java.time.{Clock, Duration}

/** Worker and reviewer results whose evidence establishes an independently reviewed candidate. */
final case class ReviewedCandidate(ticket: IntegrationTicket, workerId: ArtifactId, worker: ChildResult, validation: ValidationCitations) {
  def candidate: GitCommit = worker.candidate.get
}

/** A host rebase of the reviewed candidate: `commit` merges it onto the advanced target `head`. */
final case class AppliedRebase(head: GitCommit, commit: GitCommit, evidence: IntegrationRebase)

final class IntegrationPreparation(api: ServerApi, owner: Scope, repository: String, target: String,
  checks: List[ValidationCheck], clock: Clock, bases: ExecutionBase) {
  private val PreparationNanos = Duration.ofSeconds(60).toNanos
  private val ClaimMillis = Duration.ofMinutes(3).toMillis

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
  private def renew(call: Command => Result, worker: ChildResult): Unit =
    call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(worker.request.fence, ClaimMillis)))) match {
      case Result.Claimed(claim) => require(claim.owner == owner.actor && claim.fence == worker.request.fence && !claim.released &&
        claim.members == worker.request.members.map(_.id).toSet && claim.expiresAt > clock.millis(), "Integration claim no longer covers this assignment")
      case _ => throw new IllegalStateException("Integration claim renewal returned an unexpected result")
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
      require(value.admission.owner == owner.actor && value.metadata.actor.session == owner.actor.session &&
        value.metadata.actor.role == Role.Collector, "Integration evidence belongs to another governing session")
    }
    require(worker.request.work.isInstanceOf[DispatchWork.Worker] && worker.request.work != DispatchWork.Worker(WorkerMode.Probe) &&
      reviewer.attempt != worker.attempt && drafts.unchanged(worker.request.members, reviewer.request.members) && reviewer.request.fence == worker.request.fence &&
      worker.candidate.nonEmpty && reviewer.candidate == worker.candidate && reviewer.base == worker.candidate.get,
      "Integration requires independently reviewed exact worker output")
    require(worker.report match { case ChildReport.Work(members) => members.forall(_.disposition == WorkDisposition.CandidateReady); case _ => false },
      "Integration requires every worker member to be ready")
    require(reviewer.report match { case ChildReport.Review(members, _) => members.forall(_.verdict == ReviewVerdict.Accepted); case _ => false },
      "Integration requires every reviewer member to be accepted")
    val evidence = IntegrationValidation.applicable(IntegrationValidation.effective(owner.project, owner.actor.session, workerId, worker, checks,
      reader.amendments(workerId)), reviewer)
    evidence.passing.foreach { expected =>
      val stored = reader.read(expected.evidence.artifact)
      IntegrationValidation.verify(owner.project, owner.actor.session, worker.candidate.get, expected,
        stored.metadata, IntegrationValidation.decode(stored))
    }
    evidence.failed.foreach { expected =>
      val stored = reader.read(expected.artifact)
      IntegrationValidation.verifyFailure(owner.project, owner.actor.session, worker.candidate.get, expected,
        stored.metadata, IntegrationValidation.decode(stored))
    }
    renew(call, worker)
    ReviewedCandidate(ticket, workerId, worker, evidence.citations)
  }

  def renew(reviewed: ReviewedCandidate): Unit = bounded(renew(_, reviewed.worker))

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
      reviewed.validation.established, reviewed.validation.failed, reviewed.validation.rounds, worker.request.fence, items)
    renew(call, worker)
    IntegrationIntent(ticket.id, owner.project, owner.actor, repository, target,
      rebase.fold(bases.expected(worker.base, reviewed.candidate))(_.head), candidate,
      reviewed.workerId, ticket.reviewer, checks, worker.request.fence, items.map(item => ItemRevision(item.id, item.revision)), change, rebase.map(_.evidence))
  }
}
