package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.JsonRoundtrip
import cq.core.{DomainFailure, Scope}
import io.circe.parser
import java.time.{Clock, Duration}

object CombinationPlans {
  val MaxBytes = 16384
  val MaxOperations = 32
  private val MaxMembers = 16
  def artifact(plan: CombinationPlan): ArtifactId = NativeArtifacts.id(plan.governor, "combination-" + plan.request.id.value)
  def validate(plan: CombinationPlan, owner: Scope, governor: AttemptId, repository: String, target: String): Unit = {
    require(owner.actor.role == Role.Governor && plan.project == owner.project && plan.owner == owner.actor && plan.governor == governor &&
      plan.repository == repository && plan.target == target, "Combination plan belongs to another owner, repository or target")
    require(plan.members.nonEmpty && plan.members.size <= MaxMembers && plan.members.map(_.id).distinct.size == plan.members.size &&
      plan.members.forall(ref => ref.id.project == owner.project && ref.id.number > 0 && ref.revision.value > 0) && plan.request.fence.generation > 0,
      "Invalid combination assignment or fence")
    require(plan.candidate != plan.observedTarget && List(plan.candidate, plan.observedTarget).forall(_.value.matches("[0-9a-f]{40}|[0-9a-f]{64}")),
      "Combination requires distinct full commit IDs")
    require(HostFiles.encode(CombinationPlan_JsonCodec, plan).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxBytes,
      "Combination plan exceeds its byte bound")
  }
  def preview(plan: CombinationPlan): CombinationPreview =
    CombinationPreview(artifact(plan), plan.worker, plan.members, plan.request.fence, plan.observedTarget)
}

final class CombinationPreparation(api: ServerApi, owner: Scope, governor: AttemptId, repository: String, target: String, clock: Clock) {
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val DeadlineNanos = Duration.ofSeconds(60).toNanos

  private def bounded[A](operation: (Command => Result) => A): A = {
    val began = System.nanoTime()
    operation { command =>
      require(System.nanoTime() - began < DeadlineNanos, "Combination preparation deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case result => result
      }
    }
  }
  private def source(call: Command => Result, ticket: CombinationTicket): IntegrationIntent = {
    val record = call(Command.Read(ReadInput(owner.project, ReadSelection.Integration(ticket.source)))) match {
      case Result.Integration(value) => value
      case _ => throw new IllegalStateException("Combination integration read returned an unexpected result")
    }
    require(record.intent.id == ticket.source && record.resolution.isInstanceOf[IntegrationResolution.NotApplied] && record.resolvedAt.nonEmpty,
      "Combination source must be a resolved integration with no applied effect")
    val intent = record.intent
    require(intent.project == owner.project && intent.owner == owner.actor && intent.repository == repository && intent.target == target,
      "Combination source belongs to another owner, repository or target")
    val work = new ArtifactReader(call, owner.project).result(intent.worker)
    require(work.admission.owner == owner.actor && work.metadata.actor.session == owner.actor.session && work.metadata.actor.role == Role.Collector &&
      work.value.candidate.contains(intent.candidate) && work.value.base == intent.expected && work.value.request.members == intent.members &&
      work.value.request.fence == intent.fence && work.value.request.work.isInstanceOf[DispatchWork.Worker] &&
      work.value.request.work != DispatchWork.Worker(WorkerMode.Probe), "Combination source has no matching admitted original worker")
    intent
  }
  private def renew(call: Command => Result, fence: Fence, members: List[ItemRevision]): Unit = {
    call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(fence, ClaimMillis)))) match {
      case Result.Claimed(claim) => require(claim.owner == owner.actor && claim.fence == fence && !claim.released &&
        claim.members == members.map(_.id).toSet && claim.expiresAt > clock.millis(), "Combination claim no longer covers its exact assignment")
      case _ => throw new IllegalStateException("Combination claim renewal returned an unexpected result")
    }
  }
  def prepare(ticket: CombinationTicket, observe: GitCommit => GitCommit): CombinationPlan = bounded { call =>
    val intent = source(call, ticket)
    renew(call, ticket.fence, intent.members)
    intent.members.foreach { reference =>
      call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetail(reference.id)))) match {
        case Result.Detail(value) => require(value.item.id == reference.id && value.item.revision == reference.revision, "Combination member revision changed")
        case _ => throw new IllegalStateException("Combination member read returned an unexpected result")
      }
    }
    val plan = CombinationPlan(ticket, owner.project, owner.actor, governor, repository, target, observe(intent.candidate), intent.candidate, intent.worker, intent.members)
    CombinationPlans.validate(plan, owner, governor, repository, target)
    renew(call, ticket.fence, intent.members)
    plan
  }
  def consume(input: ChildInput): Option[CombinationPlan] = {
    val artifacts = input.artifacts.filter(_.metadata.kind == ArtifactKind.Combination)
    require(artifacts.size <= 1, "Only one combination plan may be consumed")
    artifacts.headOption.map { artifact => bounded { call =>
      val json = parser.parse(artifact.body).fold(throw _, identity)
      val plan = CombinationPlan_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
      require(JsonRoundtrip.lossless(json, CombinationPlan_JsonCodec.encode(BaboonCodecContext.Default, plan)), "Combination plan contains undeclared or noncanonical fields")
      CombinationPlans.validate(plan, owner, governor, repository, target)
      require(artifact.metadata.id == CombinationPlans.artifact(plan) && artifact.metadata.project == owner.project &&
        artifact.metadata.attempt == governor && artifact.metadata.actor.session == owner.actor.session &&
        artifact.metadata.actor.role == Role.Collector && artifact.metadata.mediaType == "application/json", "Combination plan has another publisher or artifact identity")
      val request = input.request
      require(request.work == DispatchWork.Worker(WorkerMode.ResolveConflict) && request.previous.contains(plan.worker) &&
        request.members == plan.members && request.fence == plan.request.fence && input.project == owner.project,
        "Combination plan requires its exact resolver, original worker, members and current fence")
      val intent = source(call, plan.request)
      require(intent.worker == plan.worker && intent.candidate == plan.candidate && intent.members == plan.members &&
        input.previous.exists(value => value.candidate.contains(plan.candidate) && value.request.members == plan.members),
        "Combination plan differs from its original integration evidence")
      renew(call, plan.request.fence, plan.members)
      plan
    }}
  }
}
