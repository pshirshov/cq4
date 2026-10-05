package cq.server

import cq.api.*
import cq.core.{ArtifactService, DomainFailure}
import cq.host.*
import java.time.Clock
import logstage.IzLogger
import scala.util.{Failure, Success, Try}
import zio.{Task, ZIO}

final class CohortController(config: SupervisorConfig, authority: SupervisorAuthority, workflow: WorkflowExecution,
  dispatch: DispatchController, candidates: CandidateWorkspace, requirements: OperatorRequirements, clock: Clock, logger: IzLogger) {
  private val progress = new CohortProgress
  private val planner = new CohortPlanner(authority.governor, config.owner, candidates, config.settings.checks, progress, requirements)
  private var decisions = Map.empty[RequestId, CohortPlan]
  private var generations = Map.empty[RequestId, Long]
  private var advertised = Set.empty[RequestId]

  def select(request: CohortRequest): Task[CohortDecision] = ZIO.attemptBlocking(synchronized {
    // A decision of an earlier workflow activation can be neither replayed nor started, so only its generation is kept, to refuse it.
    val stale = generations.collect { case (id, generation) if generation != workflow.generation => id }.toSet
    decisions --= stale
    advertised --= stale
    require(generations.get(request.request).forall(_ == workflow.generation), "Cohort selection belongs to a previous workflow activation")
    val value = decisions.get(request.request) match {
      case Some(existing) =>
        if (existing.evidence.request != request) throw DomainFailure(Fault.Conflict("Cohort selection identity changed"))
        existing
      case None =>
        workflow.selection(request)
        SupervisorConfig.within(request.limits, config.settings.limits)
        val artifact = NativeArtifacts.id(config.run.attempt.id, "selection-" + request.request.value)
        val value = planner.plan(request, artifact)
        val body = HostFiles.encode(CohortEvidence_JsonCodec, value.evidence)
        require(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ArtifactService.MaxBytes, "Cohort evidence exceeds its artifact bound")
        val directory = config.directory.resolve("selections")
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve(request.request.value.toString + ".json"), body, ArtifactService.MaxBytes)
        decisions += request.request -> value
        generations += request.request -> workflow.generation
        value
    }
    if (!advertised(request.request)) {
      val metadata = authority.collector.artifact(ArtifactUpload(config.project.project, value.evidence.decision.artifact, config.run.attempt.id,
        ArtifactKind.Selection, "application/json", HostFiles.encode(CohortEvidence_JsonCodec, value.evidence)))
      require(metadata.id == value.evidence.decision.artifact && metadata.attempt == config.run.attempt.id, "Cohort evidence publication changed identity")
      advertised += request.request
      progress.offered(value.evidence.decision.choices.flatMap(_.members.map(_.id)))
    }
    value.evidence.decision
  })

  def start(id: RequestId, harness: Harness, fence: Fence): Task[DispatchStatus] = {
    val resolved = ZIO.attemptBlocking(synchronized {
      val (plan, choice) = decisions.values.filter(plan => advertised(plan.evidence.request.request)).toList.flatMap(plan => plan.evidence.decision.choices.map(plan -> _)).find(_._2.id == id)
        .getOrElse(throw DomainFailure(Fault.Missing("Cohort choice is not owned by this governing session or belongs to a previous workflow activation")))
      require(generations(plan.evidence.request.request) == workflow.generation, "Cohort choice belongs to a previous workflow activation")
      val request = DispatchRequest(choice.id, choice.work, harness, choice.members, choice.guidance, choice.artifacts, choice.previous, fence, choice.limits)
      workflow.authorize(DispatchCommand.Start(request))
      val admission = () => {
        planner.verify(plan.evidence.request, choice, plan.fingerprints(id))
        val preview = authority.governor.call(Command.Read(ReadInput(config.project.project, ReadSelection.Claims(choice.members.map(_.id).toSet)))) match {
          case Result.Claims(value) => value
          case Result.Failed(fault) => throw DomainFailure(fault)
          case _ => throw new IllegalStateException("Cohort start claim read returned an unexpected result")
        }
        require(preview.members.toSet == choice.members.toSet && preview.integrations.isEmpty && preview.claims.exists(claim =>
          claim.fence == fence && claim.owner == config.owner.actor && claim.members == choice.members.map(_.id).toSet &&
          !claim.released && claim.expiresAt > clock.millis()), "Cohort start requires its exact current governing claim")
        progress.started(plan.fingerprints(id))
      }
      request -> SelectedDispatch(choice.cohort, plan.evidence.decision.artifact, admission, concluded(plan.fingerprints(id), _))
    })
    resolved.flatMap((request, selected) => dispatch.startSelected(request, selected))
  }

  // A child whose receipt advises Retry left no result: its fault is published for the next attempt and its input is offered again.
  // When the attempt before it on the same input ended in the same fault, that input stays deferred, as it does when the fault cannot
  // be published. The reply tells a drive which of these happened.
  private def concluded(fingerprint: CohortExecutionFingerprint, status: DispatchStatus): ChildOutcome = {
    val failure = CohortFailure.fault(status).flatMap { fault =>
      val upload = ArtifactUpload(config.project.project, NativeArtifacts.id(config.run.attempt.id, "failure-" + status.attempt.value), config.run.attempt.id,
        ArtifactKind.Evidence, "text/plain", s"The previous attempt on this assignment (${status.attempt.value}) left no admitted result. Its fault: $fault")
      Try(authority.collector.artifact(upload)) match {
        case Success(metadata) => Some(CohortFailure(metadata.id, fault))
        case Failure(error) =>
          val message = s"The fault of attempt ${status.attempt.value} was not published, so its input stays deferred: ${error.getMessage}"
          logger.warn(s"$message")
          None
      }
    }
    val repeated = progress.finished(fingerprint, failure)
    CohortFailure.outcome(status, Some(fingerprint.group), failure.map(_ => !repeated))
  }
}
