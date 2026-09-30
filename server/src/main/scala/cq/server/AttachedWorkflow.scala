package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.{HostFiles, OperatorRequirements, WorkflowAssembly, WorkflowAssets, WorkflowExecution}
import zio.{Task, ZIO}

final class AttachedWorkflow(config: SupervisorConfig, authority: SupervisorAuthority, assets: WorkflowAssets, execution: WorkflowExecution,
  requirements: OperatorRequirements, dispatch: DispatchController, integrations: IntegrationController, combinations: CombinationController) {
  private val MaxActivations = 64
  // Instructions plus a session request of up to 64 KiB (the gateway bound) no longer fit the former 64 KiB record.
  private val MaxActivationBytes = 131072
  private var activations = Map.empty[RequestId, WorkflowActivation]
  private var active = Option.empty[WorkflowActivation]
  private var integrationsByEpoch = Map.empty[IntegrationId, Long]
  private var combinationsByEpoch = Map.empty[RequestId, Long]
  def current: Option[WorkflowActivation] = synchronized(active)

  def activate(id: RequestId, request: WorkflowRequest, operatorRequirements: String): Task[WorkflowActivation] = ZIO.attemptBlocking(synchronized {
    require(config.run.ownership == SessionOwnership.Attached, "Interactive activation requires an attached session")
    activations.get(id) match {
      case Some(value) =>
        require(value.context.request == request && value.operatorRequirements == operatorRequirements, "Workflow activation identity changed")
        value
      case None =>
        require(dispatch.quiescent && integrations.quiescent && combinations.quiescent,
          "Settle active child/check/integration/combination work before changing workflow")
        require(activations.size < MaxActivations, "Session workflow activation limit reached")
        val value = WorkflowActivation(id, new WorkflowAssembly(authority.governor, config.project.project, assets).assemble(request), operatorRequirements)
        val directory = config.directory.resolve("workflows")
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve(id.value.toString + ".json"), HostFiles.encode(WorkflowActivation_JsonCodec, value), MaxActivationBytes)
        execution.activate(request)
        requirements.replace(operatorRequirements)
        activations += id -> value
        active = Some(value)
        value
    }
  })

  def authorize(command: DispatchCommand): Unit = synchronized {
    command match {
      case _: DispatchCommand.Status | _: DispatchCommand.Cancel | _: DispatchCommand.IntegrationStatus | _: DispatchCommand.CombinationStatus => ()
      case _ =>
        if (active.isEmpty) throw DomainFailure(Fault.Denied("Activate a CQ workflow with session/Workflow before dispatch"))
        execution.authorize(command)
        command match {
          case DispatchCommand.PrepareIntegration(id, _) =>
            require(integrationsByEpoch.get(id).forall(_ == execution.generation), "Integration belongs to a previous workflow activation")
            integrationsByEpoch += id -> execution.generation
          case DispatchCommand.Integrate(id) =>
            require(integrationsByEpoch.get(id).contains(execution.generation), "Integration is not prepared in this workflow activation")
          case DispatchCommand.Combine(id, source, _) =>
            require(integrationsByEpoch.get(source).contains(execution.generation) &&
              combinationsByEpoch.get(id).forall(_ == execution.generation), "Combination belongs to a previous workflow activation")
            combinationsByEpoch += id -> execution.generation
          case _ => ()
        }
    }
  }
}
