package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Host time outside any attempt, on the assignment of the dispatch whose members the work covers. Identities are derived from the
  * work's own identity, so a replay delivers the same span. */
object PhaseSpans {
  private val MaxTicketBytes = 64 * 1024
  private def id(kind: String, value: UUID): RequestId = RequestId(UUID.nameUUIDFromBytes(s"$kind:$value".getBytes(UTF_8)))

  /** One run of a check: from the job's registration to its last recorded transition, which for a settled job is its settlement. */
  def check(record: JobRecord, assignment: AssignmentId): PhaseSpan = PhaseSpan(id("check", record.workspace.attempt.value), assignment,
    record.workspace.owner, UsagePhase.Check, record.createdAt, record.updatedAt, JobOutcome.observed(record).state)
  def integration(value: IntegrationId, assignment: AssignmentId, session: SessionId, startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
    PhaseSpan(id("integration", value.value), assignment, session, UsagePhase.Integrate, startedAt, finishedAt, state)
  def combination(value: RequestId, assignment: AssignmentId, session: SessionId, startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
    PhaseSpan(id("combination", value.value), assignment, session, UsagePhase.Combine, startedAt, finishedAt, state)
  def delivery(project: ProjectId, span: PhaseSpan): HostDelivery = HostDelivery.Usage(HostUsageInput(project, HostUsage.Span(span)))

  /** The assignment of the child of this session that produced `result`; work the governing session runs on that result belongs to it. */
  def producer(session: Path, result: ArtifactId): AssignmentId = {
    val root = session.resolve("children")
    // The iterator reads one ticket at a time and stops at the producer: a session holds any number of children.
    val producer = if (!Files.exists(root)) None else Using.resource(Files.list(root))(_.iterator().asScala.map(_.resolve("ticket.json")).filter(Files.exists(_))
      .map(HostFiles.read(_, DispatchTicket_JsonCodec, MaxTicketBytes)).find(ticket => NativeArtifacts.id(ticket.attempt.id, "result") == result))
    producer.map(_.assignment.id).getOrElse(throw new IllegalStateException("Result was not produced by a child of this governing session"))
  }
}

/** Spans of work the governing session runs itself. Each is retained in its own queue before it is sent, so session recovery replays
  * one the server did not acknowledge. */
final class SpanDelivery(root: Path, project: ProjectId) {
  private def queue(id: UUID): DeliveryQueue = new DeliveryQueue(root.resolve(id.toString))
  def retain(span: PhaseSpan): Unit = {
    HostFiles.directory(root)
    queue(span.id.value).enqueue(0, DeliveryBatch(List(PhaseSpans.delivery(project, span))))
  }
  def send(span: PhaseSpan, api: ServerApi): Int = queue(span.id.value).flush(api)
  /** Sends every retained span the server has not acknowledged. */
  def flush(api: ServerApi): Int = if (!Files.exists(root)) 0 else {
    HostFiles.directory(root)
    val retained = Using.resource(Files.list(root))(_.iterator().asScala.toList)
    retained.sortBy(_.getFileName.toString).map { path =>
      val id = UUID.fromString(path.getFileName.toString)
      require(id.toString == path.getFileName.toString && Files.isDirectory(path) && !Files.isSymbolicLink(path), "Invalid retained span identity")
      queue(id).flush(api)
    }.sum
  }
}
