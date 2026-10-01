package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.{DriverSessionClient, HostFiles, OperatorRequirements, WorkflowAssembly, WorkflowAssets, WorkflowExecution}
import zio.{Task, ZIO}

// The workflow activations of one attached session. `begin` makes an accepted activation the session's current workflow.
final class WorkflowActivations(session: DriverSessionClient, quiescent: () => Boolean,
  begin: (RequestId, WorkflowRequest, String, Option[CycleId]) => WorkflowActivation) {
  private final case class Accepted(id: RequestId, value: WorkflowActivation, token: Option[CycleToken])
  // Kept only to answer a repeated request: an attached session is not limited in how many workflows it activates.
  private val MaxRetained = 64
  private var retained = Vector.empty[Accepted]
  private var active = Option.empty[WorkflowActivation]
  def current: Option[WorkflowActivation] = synchronized(active)

  // While this session's driver is on, the server admits only the token of a host-issued directive.
  // A start token creates the cycle's one run; a resume token returns that run and never creates another.
  def activate(id: RequestId, request: WorkflowRequest, operatorRequirements: String, token: Option[CycleToken]): WorkflowActivation = synchronized {
    OperatorRequirements.admitted(operatorRequirements, token)
    retained.find(_.id == id).map { accepted =>
      require(accepted.value.context.request == request && accepted.value.operatorRequirements == operatorRequirements && accepted.token == token,
        "Workflow activation identity changed")
      accepted.value
    }.getOrElse(token match {
      case Some(_: CycleToken.Resume) => session.activate(id, request, token) match {
        case DriverActivation.Resumed(cycle, run) => active.filter(value => value.id == run && value.cycle.contains(cycle)).getOrElse {
          // This host holds no record of the run, so no resume directive can succeed here: the drive ends instead of issuing more of them.
          val stopped = session.fail(cycle, LineageMember.Run(run), "is not the active workflow of its attached host")
          throw DomainFailure(Fault.Denied(s"CQ driver stopped with reason failure: ${stopped.detail}"))
        }
        case other => throw new IllegalStateException(s"A resume token was answered with ${other.getClass.getSimpleName}")
      }
      case _ =>
        // The host's own preconditions come first: a refusal leaves the directive's token unused, so the session can settle its work and activate again.
        require(quiescent(), "Settle active child/check/integration/combination work before changing workflow")
        val cycle = session.activate(id, request, token) match {
          case DriverActivation.Started(value) => Some(value)
          case _: DriverActivation.Undriven => None
          case other => throw new IllegalStateException(s"A new activation was answered with ${other.getClass.getSimpleName}")
        }
        val value = begin(id, request, operatorRequirements, cycle)
        retained = (retained :+ Accepted(id, value, token)).takeRight(MaxRetained)
        active = Some(value)
        value
    })
  }
}

final class AttachedWorkflow(config: SupervisorConfig, authority: SupervisorAuthority, assets: WorkflowAssets, execution: WorkflowExecution,
  requirements: OperatorRequirements, dispatch: DispatchController, integrations: IntegrationController, combinations: CombinationController,
  revalidations: RevalidationController, driver: AttachedDriver) {
  // Instructions plus a session request of up to 64 KiB (the gateway bound) no longer fit the former 64 KiB record.
  private val MaxActivationBytes = 131072
  private val activations = new WorkflowActivations(driver.session,
    () => dispatch.quiescent && integrations.quiescent && combinations.quiescent && revalidations.quiescent, begin)
  private var integrationsByEpoch = Map.empty[IntegrationId, Long]
  private var combinationsByEpoch = Map.empty[RequestId, Long]
  def current: Option[WorkflowActivation] = activations.current

  private def begin(id: RequestId, request: WorkflowRequest, operatorRequirements: String, cycle: Option[CycleId]): WorkflowActivation = {
    val value = WorkflowActivation(id, new WorkflowAssembly(authority.governor, config.project.project, assets).assemble(request), operatorRequirements, cycle)
    val directory = config.directory.resolve("workflows")
    HostFiles.directory(directory)
    HostFiles.immutable(directory.resolve(id.value.toString + ".json"), HostFiles.encode(WorkflowActivation_JsonCodec, value), MaxActivationBytes)
    execution.activate(request)
    requirements.replace(operatorRequirements)
    value
  }

  def activate(id: RequestId, request: WorkflowRequest, operatorRequirements: String, token: Option[CycleToken]): Task[WorkflowActivation] = ZIO.attemptBlocking(synchronized {
    require(config.run.ownership == SessionOwnership.Attached, "Interactive activation requires an attached session")
    activations.activate(id, request, operatorRequirements, token)
  })

  def authorize(command: DispatchCommand): Unit = synchronized {
    command match {
      case _: DispatchCommand.Status | _: DispatchCommand.Cancel | _: DispatchCommand.IntegrationStatus | _: DispatchCommand.CombinationStatus => ()
      case _ =>
        if (current.isEmpty) throw DomainFailure(Fault.Denied("Activate a CQ workflow with session/Workflow before dispatch"))
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
