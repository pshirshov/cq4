package cq.host

import cq.api.*
import java.nio.file.{Files, Path}

final case class DeclaredCheckReceipt(status: DeclaredCheckStatus, acknowledged: Int)

final class DeclaredCheckPublication(directory: Path, ticket: DeclaredCheckTicket, payload: Path) {
  private val MaxStatusBytes = 4096
  private val statusFile = directory.resolve("result.json")
  private val queue = new DeliveryQueue(directory.resolve("delivery"))
  private def bytes(name: String): Array[Byte] = {
    val file = payload.resolve(ticket.workspace.attempt.value.toString).resolve(name)
    if (Files.exists(file)) HostFiles.bytes(file, ticket.check.outputBytes) else Array.emptyByteArray
  }
  private def retain(status: DeclaredCheckStatus): Unit =
    HostFiles.immutable(statusFile, HostFiles.encode(DeclaredCheckStatus_JsonCodec, status), MaxStatusBytes)

  def seal(record: Option[JobRecord], problem: Option[String]): Unit = {
    record.foreach { value => require(value.workspace == ticket.workspace && value.fingerprint == ticket.fingerprint,
      "Declared check job differs from its immutable execution ticket") }
    val project = ticket.workspace.project
    val prefix = "review-check-" + ticket.check.name
    val stdout = bytes("stdout")
    val stderr = bytes("stderr")
    val (out, outParts) = NativeArtifacts.binary(project, ticket.parent, prefix + "-stdout", "application/octet-stream", stdout)
    val (err, errParts) = NativeArtifacts.binary(project, ticket.parent, prefix + "-stderr", "application/octet-stream", stderr)
    val observed = record.map(JobOutcome.observed)
    val settled = record.exists(_.phase == JobPhase.Settled) && observed.exists(_.state != AttemptState.Unknown)
    val complete = record.flatMap(_.exit).exists(value => value.stdoutBytes == stdout.length && value.stderrBytes == stderr.length)
    val state = if (!settled || !complete) ValidationState.Unknown else if (observed.exists(_.succeeded)) ValidationState.Passed else ValidationState.Failed
    val artifact = record.map { value => ArtifactUpload(project, NativeArtifacts.id(ticket.parent, prefix), ticket.parent,
      ArtifactKind.Validation, "application/json", HostFiles.encode(ValidationObservation_JsonCodec,
        ValidationObservation(ticket.check, ticket.workspace.base, value, out, err))) }
    val evidence = artifact.map(value => ValidationEvidence(ticket.check.name, state, value.id))
    val blocker = problem.orElse(if (!settled || !complete) Some("Declared check settlement or retained output is incomplete") else observed.flatMap(_.problem))
      .map(DispatchProjection.concise)
    val status = DeclaredCheckStatus(ticket.check.name, ticket.workspace.attempt,
      if (settled && complete) DeclaredCheckPhase.Completed else DeclaredCheckPhase.Unknown, evidence, blocker)
    val summary = ArtifactUpload(project, NativeArtifacts.id(ticket.parent, prefix + "-status"), ticket.parent, ArtifactKind.Transcript,
      "application/json", HostFiles.encode(DeclaredCheckStatus_JsonCodec, status))
    queue.commit((outParts ++ errParts ++ artifact.toList :+ summary).map(HostDelivery.Artifact.apply))
    retain(status)
  }

  def reconcile(record: Option[JobRecord]): Unit = {
    record.foreach(value => require(value.workspace == ticket.workspace && value.fingerprint == ticket.fingerprint,
      "Recovered declared check job differs from its immutable ticket"))
    if (!Files.exists(statusFile)) {
      if (queue.finalized) retain(DeclaredCheckStatus(ticket.check.name, ticket.workspace.attempt, DeclaredCheckPhase.Unknown, None,
        Some("Check evidence was sealed before its terminal status; retain it for explicit inspection")))
      else seal(record, Some("Check publication was interrupted; execution was not repeated"))
    }
  }

  def finish(api: ServerApi): DeclaredCheckReceipt = {
    val status = HostFiles.read(statusFile, DeclaredCheckStatus_JsonCodec, MaxStatusBytes)
    require(status.name == ticket.check.name && status.job == ticket.workspace.attempt &&
      Set(DeclaredCheckPhase.Completed, DeclaredCheckPhase.Unknown)(status.phase) &&
      status.evidence.forall(value => value.check == ticket.check.name && value.artifact == NativeArtifacts.id(ticket.parent, "review-check-" + ticket.check.name)),
      "Declared check publication differs from its ticket")
    require(queue.finalized, "Declared check evidence was not sealed")
    DeclaredCheckReceipt(status, queue.flush(api))
  }
}
