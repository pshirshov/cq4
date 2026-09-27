package cq.host

import cq.api.*
import cq.core.{DomainFailure, ProposalPolicy}
import java.time.Duration

final class WorkflowAssembly(api: ServerApi, project: ProjectId, assets: WorkflowAssets) {
  private val AssemblyNanos = Duration.ofSeconds(60).toNanos
  private val MaxRoots = 64
  def assemble(request: WorkflowRequest): WorkflowContext = {
    val roots = request match {
      case WorkflowRequest.Begin(values) => values
      case WorkflowRequest.Advance(values, _) => require(values.nonEmpty, "Advance requires explicit roots"); values
      case WorkflowRequest.Upstream(values, _) => require(values.nonEmpty, "Upstream requires explicit roots"); values
      case _: WorkflowRequest.Review => Set.empty[ItemId]
    }
    require(roots.size <= MaxRoots && roots.forall(id => id.project == project && id.number > 0), "Workflow roots exceed their project scope or bound")
    val began = System.nanoTime()
    def call(command: Command): Result = {
      require(System.nanoTime() - began < AssemblyNanos, "Workflow assembly deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case result => result
      }
    }
    val subject = request match {
      case WorkflowRequest.Review(id, mode) =>
        val value = new ArtifactReader(call, project).result(id).value
        mode match {
          case ReviewerMode.Candidate => require(value.report.isInstanceOf[ChildReport.Work] && value.candidate.nonEmpty,
            "Candidate review requires a worker result with a candidate")
          case ReviewerMode.Plan => require(ProposalPolicy.prepare(value.request.work, value.request.members, value.report).nonEmpty,
            "Plan review requires a stored proposal")
          case ReviewerMode.Audit => ()
        }
        value.request.members.foreach { member =>
          call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(member.id)))) match {
            case Result.Detail(view) => require(view.item.id == member.id && view.item.revision == member.revision,
              "Review subject is stale; inspect the retained result and select current work before requesting approval")
            case _ => throw new IllegalStateException("Workflow member read returned an unexpected result")
          }
        }
        Some(WorkflowSubject(id, value.request.work, value.request.members, value.candidate))
      case _ => None
    }
    WorkflowContext(request, assets.instructions(request), subject)
  }
}
