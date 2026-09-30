package cq.host

import cq.api.*
import cq.core.{DomainFailure, WorksetTraversal}
import java.time.Duration

final class WorkflowExecution(api: ServerApi, project: ProjectId, session: SessionId, workflow: Option[WorkflowRequest]) {
  private val DeadlineNanos = Duration.ofSeconds(20).toNanos
  private val PageSize = 200
  private var current = workflow
  private var epoch = 0L
  def generation: Long = synchronized(epoch)
  def activate(request: WorkflowRequest): Unit = synchronized { current = Some(request); epoch = Math.addExact(epoch, 1) }

  private def permit(condition: Boolean, message: String): Unit =
    if (!condition) throw DomainFailure(Fault.Denied("Workflow execution: " + message))

  private def phase(work: DispatchWork): WorkflowPhase = work match {
    case _: DispatchWork.Explorer | DispatchWork.Worker(WorkerMode.Probe) | DispatchWork.Reviewer(ReviewerMode.Audit) => WorkflowPhase.Explore
    case _: DispatchWork.Planner | DispatchWork.Reviewer(ReviewerMode.Plan) => WorkflowPhase.Plan
    case _: DispatchWork.Worker => WorkflowPhase.Work
    case DispatchWork.Reviewer(ReviewerMode.Candidate) => WorkflowPhase.Review
  }

  private def within(actual: WorkflowPhase, limit: WorkflowPhase): Boolean = {
    val order = List(WorkflowPhase.Explore, WorkflowPhase.Plan, WorkflowPhase.Work, WorkflowPhase.Review, WorkflowPhase.Integrate)
    order.indexOf(actual) <= order.indexOf(limit)
  }

  private def selected(call: Command => Result, roots: Set[ItemId], members: Set[ItemId]): Unit = {
    var after = Option.empty[ItemId]
    var snapshot = Option.empty[WorksetSnapshot]
    var remaining = members
    var visited = 0
    var more = true
    while (more && remaining.nonEmpty) {
      val page = call(Command.Graph(GraphInput(project, roots, after, snapshot, PageSize))) match {
        case Result.Workset(value) => value
        case _ => throw new IllegalStateException("Workflow workset returned an unexpected result")
      }
      visited += page.entries.size
      require(visited <= WorksetTraversal.MaxItems && (!page.hasMore || (page.entries.nonEmpty && page.after != after)),
        "Workflow workset exceeded its traversal bound or failed to advance")
      remaining --= page.entries.filter(_.role == WorksetRole.Selected).map(_.item.id)
      after = page.after
      snapshot = Some(page.snapshot)
      more = page.hasMore
    }
    permit(remaining.isEmpty, "child or integration members are outside the selected descendants")
  }

  private def newIntake(call: Command => Result, members: Set[ItemId]): Unit = members.foreach { member =>
    val created = call(Command.Read(ReadInput(project, ReadSelection.History(member, Revision(2), 1)))) match {
      case Result.History(page) =>
        require(page.entries.size == 1 && page.entries.head.item.item.revision == Revision(1), "Workflow creation history is unavailable")
        page.entries.head.item.item
      case _ => throw new IllegalStateException("Workflow creation history returned an unexpected result")
    }
    permit(created.provenance.actor.session == session && created.provenance.actor.role == Role.Governor,
      "begin without roots may execute only records created by this governing session")
  }

  private def allowed(work: DispatchWork, request: WorkflowRequest, previous: Option[ArtifactId]): Unit = request match {
    case WorkflowRequest.Review(result, mode) => permit(work == DispatchWork.Reviewer(mode) && previous.contains(result), "standalone review requires its exact subject and mode")
    case WorkflowRequest.Advance(_, through) => permit(within(phase(work), through), "child exceeds the requested phase")
    case _: WorkflowRequest.Begin | _: WorkflowRequest.Upstream => permit(within(phase(work), WorkflowPhase.Plan), "this command permits exploration and planning only")
  }

  def selection(value: CohortRequest): Unit = synchronized { current.foreach { request =>
    allowed(value.work, request, value.previous)
    val began = System.nanoTime()
    def call(command: Command): Result = {
      require(System.nanoTime() - began < DeadlineNanos, "Workflow selection deadline exceeded")
      api.call(command) match { case Result.Failed(fault) => throw DomainFailure(fault); case result => result }
    }
    request match {
      case WorkflowRequest.Begin(roots) if roots.isEmpty => newIntake(call, value.roots)
      case WorkflowRequest.Begin(roots) => selected(call, roots, value.roots)
      case WorkflowRequest.Advance(roots, _) => selected(call, roots, value.roots)
      case WorkflowRequest.Upstream(roots, _) => selected(call, roots, value.roots)
      case WorkflowRequest.Review(result, _) => permit(value.roots == new ArtifactReader(call, project).result(result).value.request.members.map(_.id).toSet,
        "standalone review selection differs from its exact subject")
    }
  }}

  def authorize(command: DispatchCommand): Unit = synchronized { current.foreach { request =>
    val began = System.nanoTime()
    def call(value: Command): Result = {
      require(System.nanoTime() - began < DeadlineNanos, "Workflow execution admission deadline exceeded")
      api.call(value) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case result => result
      }
    }
    def members(values: List[ItemRevision]): Unit = request match {
      case WorkflowRequest.Begin(roots) if roots.isEmpty => newIntake(call, values.map(_.id).toSet)
      case WorkflowRequest.Begin(roots) => selected(call, roots, values.map(_.id).toSet)
      case WorkflowRequest.Advance(roots, _) => selected(call, roots, values.map(_.id).toSet)
      case WorkflowRequest.Upstream(roots, _) => selected(call, roots, values.map(_.id).toSet)
      case WorkflowRequest.Review(result, _) =>
        permit(new HistoricalDrafts(call, project).unchanged(new ArtifactReader(call, project).result(result).value.request.members, values),
          "standalone review members differ from its exact subject")
    }
    def integration(): Unit = permit(request match {
      case WorkflowRequest.Advance(_, WorkflowPhase.Integrate) => true
      case _ => false
    }, "integration and combination require advance through integrate")
    command match {
      case DispatchCommand.Start(value) =>
        allowed(value.work, request, value.previous)
        members(value.members)
      case _: DispatchCommand.Select | _: DispatchCommand.StartChoice => ()
      case DispatchCommand.PrepareIntegration(_, reviewer) =>
        integration()
        members(new ArtifactReader(call, project).result(reviewer).value.request.members)
      case _: DispatchCommand.Integrate => integration()
      case DispatchCommand.Combine(_, source, _) =>
        integration()
        call(Command.Read(ReadInput(project, ReadSelection.Integration(source)))) match {
          case Result.Integration(record) => members(record.intent.members)
          case _ => throw new IllegalStateException("Workflow integration read returned an unexpected result")
        }
      case _: DispatchCommand.Status | _: DispatchCommand.Cancel | _: DispatchCommand.IntegrationStatus | _: DispatchCommand.CombinationStatus => ()
    }
  }}
}
