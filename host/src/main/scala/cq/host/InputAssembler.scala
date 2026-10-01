package cq.host

import cq.api.*
import cq.core.{DomainFailure, MilestonePolicy, Scope}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration}

final class InputAssembler(api: ServerApi, owner: Scope, clock: Clock, operatorRequirements: String) {
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val AssemblyNanos = Duration.ofSeconds(60).toNanos
  require(owner.actor.role == Role.Governor, "Input assembly requires governing authority")

  def assemble(request: DispatchRequest): ChildInput = {
    ChildContracts.request(owner.project, request)
    val began = System.nanoTime()
    def call(command: Command): Result = {
      require(System.nanoTime() - began < AssemblyNanos, "Input assembly deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case value => value
      }
    }
    def claim(): Unit = call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(request.fence, ClaimMillis)))) match {
      case Result.Claimed(value) => require(value.fence == request.fence && value.owner == owner.actor &&
        value.members == request.members.map(_.id).toSet && !value.released && value.expiresAt > clock.millis(),
        "Dispatch claim does not cover its exact governing assignment")
      case _ => throw new IllegalStateException("Claim renewal returned an unexpected result")
    }
    def item(reference: ItemRevision): ItemView = call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetail(reference.id)))) match {
      case Result.Detail(value) =>
        require(value.item.id == reference.id && value.item.revision == reference.revision, "Dispatch item revision changed")
        value
      case _ => throw new IllegalStateException("Item read returned an unexpected result")
    }
    val reader = new ArtifactReader(call, owner.project)
    val drafts = new HistoricalDrafts(call, owner.project)
    claim()
    val members = request.members.map(item)
    MilestonePolicy.admit(request.work, members)
    val guidance = request.guidance.map(item)
    // A candidate reviewer also receives the revalidation rounds of its subject, which supersede the failed checks of the unchanged result.
    val amendments = if (request.work == DispatchWork.Reviewer(ReviewerMode.Candidate)) request.previous.toList.flatMap(reader.amendments).map(_.stored) else Nil
    val artifacts = request.artifacts.map(reader.read) ++ amendments
    val previous = request.previous.map { id =>
      val value = reader.result(id).value
      require(drafts.unchanged(value.request.members, request.members), "Prior result belongs to another assignment revision")
      if (request.work == DispatchWork.Reviewer(ReviewerMode.Candidate))
        require(value.report.isInstanceOf[ChildReport.Work] && value.candidate.nonEmpty, "Candidate review requires a worker result with a candidate")
      if (request.work == DispatchWork.Reviewer(ReviewerMode.Plan))
        require(cq.core.CohortAssessmentPolicy.reviewable(value.request.work, value.request.members, value.report),
          "Plan review requires a result containing a typed proposal or cohort assessment")
      value
    }
    val input = ChildInput(owner.project, request, members, guidance, artifacts, previous, OperatorRequirements.delivered(request.work, operatorRequirements))
    require(HostFiles.encode(ChildInput_JsonCodec, input).getBytes(UTF_8).length <= ChildContracts.MaxInputBytes, "Assembled input exceeds its byte bound")
    claim()
    input
  }
}
