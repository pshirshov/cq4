package cq.server

import cq.api.*
import cq.core.{DomainFailure, IntegrationPolicy}
import cq.host.HttpServerApi
import java.net.URI
import java.time.Duration
import java.util.UUID

object IntegrationApiCheck {
  def main(arguments: Array[String]): Unit = {
    require(arguments.isEmpty)
    def uuid: UUID = UUID.randomUUID()
    val endpoint = URI.create(sys.env("CQ_ORIGIN"))
    val session = SessionId(uuid)
    val project = ProjectId(uuid)
    val timeout = Duration.ofSeconds(10)
    val root = new HttpServerApi(endpoint, sys.env("CQ_TOKEN"), session, timeout)
    require(root.call(Command.Initialize(ProjectConfig(project, endpoint.toString, "Integration HTTP fixture"))).isInstanceOf[Result.Initialized])
    val owner = Actor("integration HTTP governor", session, Role.Governor)
    def client(actor: Actor): HttpServerApi = new HttpServerApi(endpoint,
      root.grant(GrantRequest(project, actor, System.currentTimeMillis() + 300000)).value, actor.session, timeout)
    val governor = client(owner)
    val collector = client(owner.copy(role = Role.Collector))
    val workerClient = client(owner.copy(role = Role.Worker))
    val foreign = client(owner.copy(session = SessionId(uuid), role = Role.Collector))
    val task = ItemDraft("Reviewed task", "Preserved HTTP narrative", Set("consumer"), false,
      Content.Task(TaskStatus.Ready, List("Reviewed behavior"), None, Nil), Nil)
    val created = governor.call(Command.Change(ChangeInput(project,
      ChangeRequest(RequestId(uuid), List(Mutation.Create(task), Mutation.Create(MilestoneFixture.Milestone)), Nil, "HTTP fixture")))).asInstanceOf[Result.Changed].ack
    val members = governor.call(Command.Change(ChangeInput(project, ChangeRequest(RequestId(uuid), List(Mutation.Reference(created.items.head.id, created.items.head.revision,
      Relation.PartOf, created.items.last.id, created.items.last.revision, true)), Nil, "HTTP fixture milestone")))).asInstanceOf[Result.Changed].ack.items.filter(_.id == created.items.head.id)
    val item = governor.call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(members.head.id)))).asInstanceOf[Result.Detail].view.item
    val claim = governor.call(Command.ClaimWork(ClaimInput(project, ClaimAction.Acquire(ClaimId(uuid), members.map(_.id).toSet, 300000))))
      .asInstanceOf[Result.Claimed].claim
    def attempt(role: Role, parent: Option[AttemptId]): Attempt = {
      val assignment = Assignment(AssignmentId(uuid), project, members.map(_.id).toSet, Attribution.Direct, None, None)
      require(collector.usage(HostUsageInput(project, HostUsage.Assign(assignment))) == HostUsageResult.Assigned(assignment))
      val value = Attempt(AttemptId(uuid), assignment.id, parent, session, role, Harness.Codex, "fixture", "fixture", "fixture", 1000)
      require(collector.usage(HostUsageInput(project, HostUsage.Start(value))) == HostUsageResult.Started(value))
      value
    }
    val parent = attempt(Role.Governor, None)
    val worker = attempt(Role.Worker, Some(parent.id))
    val reviewer = attempt(Role.Reviewer, Some(parent.id))
    val request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, members, Nil, Nil, None,
      claim.fence, HostLimits(3000, 10000, 1000, 300, 2000, 262144))
    val base = GitCommit("a" * 40)
    val candidate = GitCommit("b" * 40)
    def publish(result: ChildResult): ArtifactId = {
      val metadata = collector.artifact(ArtifactUpload(project, ArtifactId(uuid), result.attempt, ArtifactKind.Result, "application/json",
        Wire.encode(ChildResult_JsonCodec, result)))
      require(collector.admit(HostAdmissionInput(project, metadata.id, owner)).decision == AdmissionDecision.Accepted())
      metadata.id
    }
    val workerResult = ChildResult(worker.id, request, base, Some(candidate),
      ChildReport.Work(members.map(ref => WorkMember(ref.id, WorkDisposition.CandidateReady, "Ready", Nil))), Nil, RetainedEvidence(Nil, Nil))
    val workerHandle = publish(workerResult)
    val reviewerHandle = publish(ChildResult(reviewer.id,
      request.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate), previous = Some(workerHandle)), candidate, Some(candidate),
      ChildReport.Review(members.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), Nil, RetainedEvidence(Nil, Nil)))
    val id = IntegrationId(uuid)
    val repository = "/http-fixture"
    val target = "refs/heads/integration"
    val change = IntegrationPolicy.completion(id, repository, target, candidate, workerHandle, reviewerHandle, Nil, claim.fence, List(item))
    val intent = IntegrationIntent(id, project, owner, repository, target, base, candidate, workerHandle, reviewerHandle, Nil, claim.fence, members, change)
    val input = HostIntegrationInput(project, HostIntegration.Reserve(intent))
    def denied(operation: => Any): Unit = require(try { operation; false } catch { case DomainFailure(_: Fault.Denied) => true })
    denied(governor.integrate(input)); denied(workerClient.integrate(input)); denied(foreign.integrate(input))
    val reserved = collector.integrate(input)
    require(reserved.resolution == IntegrationResolution.Pending() && collector.integrate(input) == reserved)
    require(governor.call(Command.Read(ReadInput(project, ReadSelection.Integration(id)))) == Result.Integration(reserved))
    val reused = change.copy(mutations = List(Mutation.Create(task)), fences = Nil)
    require(governor.call(Command.Change(ChangeInput(project, reused))) == Result.Failed(Fault.IntegrationPending(id)))
    require(governor.call(Command.ClaimWork(ClaimInput(project, ClaimAction.Release(claim.fence)))) == Result.Failed(Fault.IntegrationPending(id)))
    val observe = HostIntegrationInput(project, HostIntegration.Observe(id, IntegrationObservation.Incorporated(candidate)))
    denied(workerClient.integrate(observe)); denied(foreign.integrate(observe))
    val recorded = collector.integrate(observe)
    require(recorded.resolution.isInstanceOf[IntegrationResolution.Recorded] && collector.integrate(observe) == recorded && collector.integrate(input) == recorded)
    val acknowledgement = recorded.resolution.asInstanceOf[IntegrationResolution.Recorded].acknowledgement
    require(governor.call(Command.Change(ChangeInput(project, change))) == Result.Changed(acknowledgement))
    val completed = governor.call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(item.id)))).asInstanceOf[Result.Detail].view.item
    require(completed.revision == Revision(item.revision.value + 1) && completed.draft.body == task.body && completed.draft.content.asInstanceOf[Content.Task].status == TaskStatus.Done)
    require(governor.call(Command.ClaimWork(ClaimInput(project, ClaimAction.Release(claim.fence)))).isInstanceOf[Result.Claimed])
    println("Integration HTTP: owning host authority, exact reservation replay, pending exclusions, recorded completion and idempotent domain acknowledgement passed; Git execution is not part of this fixture")
  }
}
