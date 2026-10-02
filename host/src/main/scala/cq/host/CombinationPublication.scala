package cq.host

import cq.api.*
import cq.core.Scope
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The owning supervisor or reconciliation caller holds exclusive session journal ownership. */
final class CombinationPublication(root: Path, owner: Scope, governor: AttemptId, repository: String, target: String) {
  private val MaxTicketBytes = 1024
  private def directory(id: RequestId): Path = root.resolve(id.value.toString)
  private def force(path: Path, write: Boolean): Unit =
    Using.resource(FileChannel.open(path, if (write) StandardOpenOption.WRITE else StandardOpenOption.READ))(_.force(true))
  private def validate(plan: CombinationPlan, ticket: CombinationTicket): Unit = {
    CombinationPlans.validate(plan, owner, governor, repository, target)
    require(plan.request == ticket, "Frozen combination differs from its request identity")
  }
  def inventory: List[RequestId] = {
    if (!Files.exists(root)) Nil else {
      HostFiles.directory(root)
      val paths = Using.resource(Files.list(root))(_.iterator().asScala.toList)
      paths.map { path =>
        HostFiles.directory(path)
        val id = UUID.fromString(path.getFileName.toString)
        require(id.toString == path.getFileName.toString, "Invalid combination directory identity")
        RequestId(id)
      }.sortBy(_.value.toString)
    }
  }
  def retain(ticket: CombinationTicket): Unit = {
    HostFiles.directory(root)
    force(root.getParent, false)
    val entry = directory(ticket.id)
    HostFiles.directory(entry)
    force(root, false)
    val path = entry.resolve("ticket.json")
    HostFiles.immutable(path, HostFiles.encode(CombinationTicket_JsonCodec, ticket), MaxTicketBytes)
    force(path, true)
    force(entry, false)
  }
  private def ticket(id: RequestId): CombinationTicket = {
    val entry = directory(id)
    HostFiles.directory(entry)
    val value = HostFiles.read(entry.resolve("ticket.json"), CombinationTicket_JsonCodec, MaxTicketBytes)
    require(value.id == id, "Combination ticket identity differs from its directory")
    value
  }
  private def read(id: RequestId): CombinationPlan = {
    val request = ticket(id)
    val path = directory(id).resolve("plan.json")
    val plan = HostFiles.read(path, CombinationPlan_JsonCodec, CombinationPlans.MaxBytes)
    validate(plan, request)
    force(path, true)
    force(path.getParent, false)
    force(root, false)
    force(root.getParent, false)
    plan
  }
  def freeze(request: CombinationTicket)(prepare: => CombinationPlan): CombinationPlan = {
    require(ticket(request.id) == request, "Combination request identity changed")
    val path = directory(request.id).resolve("plan.json")
    if (!Files.exists(path)) {
      val plan = prepare
      validate(plan, request)
      HostFiles.immutable(path, HostFiles.encode(CombinationPlan_JsonCodec, plan), CombinationPlans.MaxBytes)
    }
    read(request.id)
  }
  def publish(id: RequestId, api: ServerApi): CombinationPreview = {
    val plan = read(id)
    val upload = ArtifactUpload(owner.project, CombinationPlans.artifact(plan), governor, ArtifactKind.Combination,
      "application/json", HostFiles.encode(CombinationPlan_JsonCodec, plan))
    val queue = new DeliveryQueue(directory(id).resolve("delivery"))
    queue.enqueue(0, DeliveryBatch(List(HostDelivery.Artifact(upload))))
    queue.flush(api)
    CombinationPlans.preview(plan)
  }
}
