package cq.host

import cq.api.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.Using

final case class ChildPublicationReceipt(status: DispatchStatus, acknowledged: Int)

final class ChildPublicationDelivery(directory: Path, ticket: DispatchTicket) {
  private val MaxIntentBytes = 256 * 1024
  private val MaxGaps = 32
  private val intent = directory.resolve("publication.json")
  private val evidence = new DeliveryQueue(directory.resolve("publication-evidence"))
  private val delivery = new DeliveryQueue(directory.resolve("delivery"))

  def sealedIntent: Boolean = Files.exists(intent)
  /** The sealed publication: what the child was to publish, with its result when it left one. */
  def published: ChildPublication = HostFiles.read(intent, ChildPublication_JsonCodec, MaxIntentBytes)

  private def validate(value: ChildPublication): Unit = {
    require(value.project == ticket.assignment.project && value.owner.role == Role.Governor && value.owner.session == ticket.attempt.session &&
      value.outcome.attempt == ticket.attempt.id && value.outcome.request.value == NativeArtifacts.id(ticket.attempt.id, "outcome").value &&
      value.outcome.supersedes.isEmpty && value.status.request == ticket.request.request && value.status.attempt == ticket.attempt.id &&
      value.status.members.toSet == ticket.assignment.members && value.status.result.isEmpty && !value.status.usageDelivered,
      "Child publication differs from its frozen ticket")
    value.result.foreach { result =>
      ChildContracts.result(value.project, result)
      require(result.attempt == ticket.attempt.id && result.request == ticket.request && value.outcome.state == AttemptState.Completed,
        "Child publication result differs from its assignment or process outcome")
    }
    require(value.result.nonEmpty || value.outcome.state != AttemptState.Completed, "Child completion requires a result")
  }

  private def forceIntent(): Unit = Using.resource(FileChannel.open(directory, StandardOpenOption.READ))(_.force(true))

  def seal(value: ChildPublication, entries: List[HostDelivery]): Unit = {
    validate(value)
    require(!entries.exists { case HostDelivery.Usage(HostUsageInput(_, _: HostUsage.Finish)) => true; case _ => false },
      "Terminal usage outcome cannot precede result admission")
    require(!entries.exists { case HostDelivery.Artifact(value) => value.kind == ArtifactKind.Result; case _ => false },
      "Result artifact must be derived from the sealed publication")
    val result = value.result.toList.map(body => HostDelivery.Artifact(ArtifactUpload(value.project,
      NativeArtifacts.id(ticket.attempt.id, "result"), ticket.attempt.id, ArtifactKind.Result, "application/json", HostFiles.encode(ChildResult_JsonCodec, body))))
    evidence.commit(entries ++ result)
    HostFiles.immutable(intent, HostFiles.encode(ChildPublication_JsonCodec, value), MaxIntentBytes)
    forceIntent()
  }

  def finish(api: ServerApi): ChildPublicationReceipt = {
    val value = published
    validate(value)
    require(evidence.finalized, "Child publication evidence was not sealed")
    forceIntent()
    val initial = delivery.flush(api)
    val uploaded = evidence.flush(api)
    val admission = value.result.map { result =>
      val artifact = NativeArtifacts.id(ticket.attempt.id, "result")
      val record = api.admit(HostAdmissionInput(value.project, artifact, value.owner))
      require(record.artifact.id == artifact && record.artifact.project == value.project && record.artifact.attempt == ticket.attempt.id &&
        record.owner == value.owner && record.fence == result.request.fence && record.members == result.request.members,
        "Server admission does not match the sealed result")
      record
    }
    val rejection = admission.flatMap(_.decision match {
      case AdmissionDecision.Accepted() => None
      case AdmissionDecision.Rejected(reason) => Some(s"Result admission rejected: $reason")
    })
    val outcome = rejection.fold(value.outcome)(reason => value.outcome.copy(state = AttemptState.Failed, gaps = (reason :: value.outcome.gaps).take(MaxGaps)))
    delivery.commit(List(HostDelivery.Usage(HostUsageInput(value.project, HostUsage.Finish(outcome)))))
    val finalized = delivery.flush(api)
    val base = value.status.copy(usageDelivered = true, detailsOmitted = true, blocker = rejection.orElse(value.status.blocker))
    val status = value.result.filter(_ => rejection.isEmpty) match {
      case Some(result) => DispatchProjection.completed(base, result, admission.get.artifact.id)
      case None => base.copy(phase = outcome.state match {
        case AttemptState.Cancelled => DispatchPhase.Cancelled
        case AttemptState.Unknown => DispatchPhase.Unknown
        case AttemptState.Abstained => DispatchPhase.Abstained
        case _ => DispatchPhase.Failed
      // An abstention is no fault of the work: the same input on the same model would abstain again, so it is not advised to retry.
      }, next = outcome.state match {
        case AttemptState.Unknown => ChildNext.InspectEvidence
        case AttemptState.Abstained => ChildNext.ResolveBlocker
        case _ => ChildNext.Retry
      })
    }
    HostFiles.immutable(directory.resolve("receipt.json"), HostFiles.encode(DispatchStatus_JsonCodec, status), 16384)
    ChildPublicationReceipt(status, initial + uploaded + finalized)
  }
}
