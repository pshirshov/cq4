package cq.host

import cq.api.*
import java.nio.file.{Files, Path}

final case class DeclaredCheckReceipt(status: DeclaredCheckStatus, acknowledged: Int)

/** One run of a declared check; the ticket's `failures` are the observations of the failed runs before it.
  * The run's job is a Check span on `assignment`, the reviewer's own. */
final class DeclaredCheckPublication(directory: Path, ticket: DeclaredCheckTicket, assignment: AssignmentId, payload: Path) {
  private val MaxStatusBytes = 4096
  private val prefix = DeclaredCheckPublication.label(ticket.check.name, ticket.failures.size + 1)
  private val statusFile = directory.resolve("result.json")
  private val queue = new DeliveryQueue(directory.resolve("delivery"))
  private def output(name: String): Path = payload.resolve(ticket.workspace.attempt.value.toString).resolve(name)
  private def bytes(name: String): Array[Byte] = NativeTranscript.retained(output(name), ticket.check.retainedOutputBytes)
  private def size(name: String): Long = if (Files.exists(output(name))) Files.size(output(name)) else 0L
  private def retain(status: DeclaredCheckStatus): Unit =
    HostFiles.immutable(statusFile, HostFiles.encode(DeclaredCheckStatus_JsonCodec, status), MaxStatusBytes)

  def seal(record: Option[JobRecord], problem: Option[String]): Unit = {
    record.foreach { value => require(value.workspace == ticket.workspace && value.fingerprint == ticket.fingerprint,
      "Declared check job differs from its immutable execution ticket") }
    val project = ticket.workspace.project
    val stdout = bytes("stdout")
    val stderr = bytes("stderr")
    val (out, outParts) = NativeArtifacts.binary(project, ticket.parent, prefix + "-stdout", "application/octet-stream", stdout)
    val (err, errParts) = NativeArtifacts.binary(project, ticket.parent, prefix + "-stderr", "application/octet-stream", stderr)
    val observed = record.map(JobOutcome.observed)
    val settled = record.exists(_.phase == JobPhase.Settled) && observed.exists(_.state != AttemptState.Unknown)
    val complete = record.flatMap(_.exit).exists(value => value.stdoutBytes == size("stdout") && value.stderrBytes == size("stderr"))
    val state = if (!settled || !complete) ValidationState.Unknown else if (observed.exists(_.succeeded)) ValidationState.Passed else ValidationState.Failed
    val artifact = record.map { value => ArtifactUpload(project, NativeArtifacts.id(ticket.parent, prefix), ticket.parent,
      ArtifactKind.Validation, "application/json", HostFiles.encode(ValidationObservation_JsonCodec,
        ValidationObservation(ticket.check, ticket.workspace.base, value, out, err))) }
    val evidence = artifact.map(value => ValidationEvidence(ticket.check.name, state, value.id, ticket.failures))
    val blocker = problem.orElse(if (!settled || !complete) Some("Declared check settlement or retained output is incomplete") else observed.flatMap(_.problem))
      .map(DispatchProjection.concise)
    val status = DeclaredCheckStatus(ticket.check.name, ticket.workspace.attempt,
      if (settled && complete) DeclaredCheckPhase.Completed else DeclaredCheckPhase.Unknown, evidence, blocker)
    val summary = ArtifactUpload(project, NativeArtifacts.id(ticket.parent, prefix + "-status"), ticket.parent, ArtifactKind.Transcript,
      "application/json", HostFiles.encode(DeclaredCheckStatus_JsonCodec, status))
    queue.commit((outParts ++ errParts ++ artifact.toList :+ summary).map(HostDelivery.Artifact.apply) ++
      record.map(value => PhaseSpans.delivery(project, PhaseSpans.check(value, assignment, ticket.check.name))))
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
      status.evidence.forall(value => value.check == ticket.check.name && value.artifact == NativeArtifacts.id(ticket.parent, prefix) &&
        value.failures == ticket.failures),
      "Declared check publication differs from its ticket")
    require(queue.finalized, "Declared check evidence was not sealed")
    DeclaredCheckReceipt(status, queue.flush(api))
  }
}

object DeclaredCheckPublication {
  /** The first run keeps the names a single run has; a rerun's names begin differently, so no check name can collide with them. */
  def label(name: String, run: Int): String = if (run == 1) "review-check-" + name else s"recheck-$run-$name"
  def job(parent: AttemptId, name: String, run: Int): AttemptId =
    AttemptId(NativeArtifacts.id(parent, "declared-check-job-" + name + (if (run == 1) "" else s":$run")).value)
  def directory(check: Path, run: Int): Path = if (run == 1) check else check.resolve(s"rerun-$run")
  /** The observations of the runs before `run`: what its ticket must record as failures. */
  def earlier(parent: AttemptId, name: String, run: Int): List[ArtifactId] = (1 until run).map(number => NativeArtifacts.id(parent, label(name, number))).toList
}
