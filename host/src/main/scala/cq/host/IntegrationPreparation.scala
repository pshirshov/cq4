package cq.host

import cq.api.*
import cq.core.{DomainFailure, IntegrationPolicy, Scope}
import java.time.{Clock, Duration}

final class IntegrationPreparation(api: ServerApi, owner: Scope, repository: String, target: String,
  checks: List[ValidationCheck], clock: Clock) {
  private val PreparationNanos = Duration.ofSeconds(60).toNanos
  private val ClaimMillis = Duration.ofMinutes(3).toMillis

  def prepare(ticket: IntegrationTicket): IntegrationIntent = {
    val began = System.nanoTime()
    def call(command: Command): Result = {
      require(System.nanoTime() - began < PreparationNanos, "Integration preparation deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case value => value
      }
    }
    val reader = new ArtifactReader(call, owner.project)
    val review = reader.result(ticket.reviewer)
    require(review.value.request.work == DispatchWork.Reviewer(), "Integration requires a reviewer result handle")
    val workerId = review.value.request.previous.getOrElse(throw new IllegalArgumentException("Review has no worker handle"))
    val work = reader.result(workerId)
    val worker = work.value
    val reviewer = review.value
    List(work, review).foreach { value =>
      require(value.admission.owner == owner.actor && value.metadata.actor.session == owner.actor.session &&
        value.metadata.actor.role == Role.Collector, "Integration evidence belongs to another governing session")
    }
    require(worker.request.work.isInstanceOf[DispatchWork.Worker] && worker.request.work != DispatchWork.Worker(WorkerMode.Probe) &&
      reviewer.attempt != worker.attempt && reviewer.request.members == worker.request.members && reviewer.request.fence == worker.request.fence &&
      worker.candidate.nonEmpty && reviewer.candidate == worker.candidate && reviewer.base == worker.candidate.get &&
      reviewer.validation == worker.validation, "Integration requires independently reviewed exact worker output")
    require(worker.report match { case ChildReport.Work(members) => members.forall(_.disposition == WorkDisposition.CandidateReady); case _ => false },
      "Integration requires every worker member to be ready")
    require(reviewer.report match { case ChildReport.Review(members) => members.forall(_.verdict == ReviewVerdict.Accepted); case _ => false },
      "Integration requires every reviewer member to be accepted")
    require(worker.validation.map(_.check) == checks.map(_.name) && worker.validation.forall(_.state == ValidationState.Passed),
      "Integration requires all configured checks to pass")
    def renew(): Unit = call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(worker.request.fence, ClaimMillis)))) match {
      case Result.Claimed(claim) => require(claim.owner == owner.actor && claim.fence == worker.request.fence && !claim.released &&
        claim.members == worker.request.members.map(_.id).toSet && claim.expiresAt > clock.millis(), "Integration claim no longer covers this assignment")
      case _ => throw new IllegalStateException("Integration claim renewal returned an unexpected result")
    }
    renew()
    val items = worker.request.members.map { reference =>
      call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetail(reference.id)))) match {
        case Result.Detail(value) =>
          require(value.item.id == reference.id && value.item.revision == reference.revision, "Integration member revision changed")
          value.item
        case _ => throw new IllegalStateException("Integration member read returned an unexpected result")
      }
    }
    val change = IntegrationPolicy.completion(ticket.id, repository, target, worker.candidate.get, workerId, ticket.reviewer,
      worker.validation.map(_.artifact), worker.request.fence, items)
    renew()
    IntegrationIntent(ticket.id, owner.project, owner.actor, repository, target, worker.base, worker.candidate.get,
      workerId, ticket.reviewer, checks, worker.request.fence, worker.request.members, change)
  }
}
