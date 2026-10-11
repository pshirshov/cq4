package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Host time outside any attempt, on the assignment of the dispatch whose members the work covers. Identities are derived from the
  * work's own identity, so a replay delivers the same span. */
object PhaseSpans {
  private val MaxTicketBytes = 64 * 1024
  private def id(kind: String, value: UUID): RequestId = RequestId(UUID.nameUUIDFromBytes(s"$kind:$value".getBytes(UTF_8)))

  /** One run of a check: from the job's registration to its last recorded transition, which for a settled job is its settlement. */
  def check(record: JobRecord, assignment: AssignmentId, name: String): PhaseSpan = PhaseSpan(id("check", record.workspace.attempt.value), assignment,
    record.workspace.owner, UsagePhase.Check, record.createdAt, record.updatedAt, JobOutcome.observed(record).state, Some(name), None)
  /** The start of `check` for the same job: the identity, assignment, session, phase, name and start time the finished span carries. */
  def checkStart(record: JobRecord, assignment: AssignmentId, name: String): PhaseSpan =
    PhaseSpan(id("check", record.workspace.attempt.value), assignment, record.workspace.owner, UsagePhase.Check, record.createdAt, record.createdAt,
      AttemptState.Running, Some(name), None)
  /** The span as it stands while its work runs: no finish yet, so it adds no wall time. */
  def running(span: PhaseSpan): PhaseSpan = span.copy(finishedAt = span.startedAt, state = AttemptState.Running)
  def integration(value: IntegrationId, assignment: AssignmentId, session: SessionId, startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
    PhaseSpan(id("integration", value.value), assignment, session, UsagePhase.Integrate, startedAt, finishedAt, state, None, None)
  def combination(value: RequestId, assignment: AssignmentId, session: SessionId, startedAt: Long, finishedAt: Long, state: AttemptState): PhaseSpan =
    PhaseSpan(id("combination", value.value), assignment, session, UsagePhase.Combine, startedAt, finishedAt, state, None, None)
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
  * one the server did not acknowledge. A queue holds at most two immutable batches: the start (the span Running) at sequence 0 and the
  * finish at sequence 1; a span recorded only when it ended is one terminal batch at sequence 0. */
final class SpanDelivery(root: Path, project: ProjectId, sync: DeliverySync) {
  import SpanDelivery.*
  private def queue(id: UUID): DeliveryQueue = new DeliveryQueue(root.resolve(id.toString), sync)
  private def exclusive[A](id: UUID)(operation: => A): A = sync.span(id).synchronized(operation)
  private def sequenceOf(span: PhaseSpan): Int =
    if (span.state != AttemptState.Running && SpanDelivery.starts(root, span.id.value)) FinishSequence else StartSequence
  /** The start, or the finish of a span whose start is retained. Repeating the same record changes nothing. */
  def retain(span: PhaseSpan): Unit = exclusive(span.id.value) {
    HostFiles.directory(root)
    queue(span.id.value).enqueue(sequenceOf(span), DeliveryBatch(List(PhaseSpans.delivery(project, span))))
  }
  /** Sends the whole queue: the start and then the finish. For the background delivery of a start. */
  def send(span: PhaseSpan, api: ServerApi): Int = queue(span.id.value).send(api)
  /** Sends only the batch `span` was retained as. A finish goes this way: a start that failed or is still being delivered is neither
    * retried here nor waited for, so it cannot delay the work's result; the start stays retained and is replayed later. */
  def sendRetained(span: PhaseSpan, api: ServerApi): Int = queue(span.id.value).send(api, sequenceOf(span))
  /** Sends every retained span the server has not acknowledged. */
  def flush(api: ServerApi): Int = flush(api, None)
  /** Ends every span whose start is retained and whose finish is not, as Unknown at `at` (not before its start), then sends what the
    * server has not acknowledged. For a host that is gone: the work such a span covers is not known to have ended. */
  def close(api: ServerApi, at: Long): Int = flush(api, Some(at))
  private def flush(api: ServerApi, closing: Option[Long]): Int = if (!Files.exists(root)) 0 else {
    HostFiles.directory(root)
    val retained = Using.resource(Files.list(root))(_.iterator().asScala.toList)
    retained.sortBy(_.getFileName.toString).map { path =>
      val id = UUID.fromString(path.getFileName.toString)
      require(id.toString == path.getFileName.toString && Files.isDirectory(path) && !Files.isSymbolicLink(path), "Invalid retained span identity")
      exclusive(id) {
        closing.foreach { at =>
          SpanDelivery.open(root, id).foreach(span => queue(id).enqueue(FinishSequence,
            DeliveryBatch(List(PhaseSpans.delivery(project, span.copy(finishedAt = math.max(span.startedAt, at), state = AttemptState.Unknown))))))
        }
      }
      queue(id).send(api)
    }.sum
  }
}

object SpanDelivery {
  private val StartSequence = 0
  private val FinishSequence = 1
  private val MaxBatchBytes = 16 * 1024 * 1024
  private def batch(root: Path, id: UUID, sequence: Int): Path = root.resolve(id.toString).resolve(f"$sequence%06d.json")
  private def stored(root: Path, id: UUID, sequence: Int): Option[PhaseSpan] = {
    val path = batch(root, id, sequence)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) None
    else HostFiles.read(path, DeliveryBatch_JsonCodec, MaxBatchBytes).entries match {
      case List(HostDelivery.Usage(HostUsageInput(_, HostUsage.Span(span)))) => Some(span)
      case _ => throw new IllegalStateException("Retained span batch holds more than a span")
    }
  }
  private def starts(root: Path, id: UUID): Boolean = stored(root, id, StartSequence).exists(_.state == AttemptState.Running)
  /** The span of the queue whose start is retained and whose finish is not. */
  def open(root: Path, id: UUID): Option[PhaseSpan] =
    stored(root, id, StartSequence).filter(_.state == AttemptState.Running).filter(_ => stored(root, id, FinishSequence).isEmpty)
  /** Whether a queue of `root` holds a started span without a finish: work whose end was never recorded. */
  def unfinished(root: Path): Boolean = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) &&
    Using.resource(Files.list(root))(_.iterator().asScala.toList).exists { path =>
      scala.util.Try(UUID.fromString(path.getFileName.toString)).toOption.exists(open(root, _).nonEmpty)
    }
}
