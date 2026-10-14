package cq.host

import cq.api.*
import cq.core.LedgerPolicy
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** The time one governing session waits on a person, recorded as spans of the phase `Await` on the governing attempt's own
  * assignment. One wait is one span, identified by the session, the awaited item and the start of the wait; it is registered Running
  * when the wait begins, observed again when the union of the items it blocks grows and at least every `refresh` while it lasts, and
  * ended once. The session directory holds one record per wait, replaced atomically by each observation and written before it is
  * sent, together with the observation number the server last acknowledged. */
final class OperatorWaits(directory: Path, run: SupervisorRun, collector: ServerApi, clock: Clock, interval: Duration = OperatorWaits.Refresh) {
  private final case class Active(id: RequestId, startedAt: Long, blocked: Set[ItemId], truncated: Boolean, observation: Long, observedAt: Long)
  private var active = Map.empty[ItemId, Active]
  private val store = new WaitStore(directory.resolve(OperatorWaits.Directory), run.project.project)

  private def span(item: ItemId, wait: Active, finishedAt: Long, state: AttemptState): PhaseSpan = PhaseSpan(wait.id, run.attempt.assignment, run.attempt.session,
    UsagePhase.Await, wait.startedAt, finishedAt, state, None, Some(AwaitedSpan(item, wait.blocked, wait.truncated, wait.observation)), Some(SpanParent.Attempt(run.attempt.id)))

  private def observe(item: ItemId, wait: Active, finishedAt: Long, state: AttemptState): Unit = {
    active = if (state == AttemptState.Running) active.updated(item, wait.copy(observedAt = finishedAt)) else active - item
    val value = span(item, wait, finishedAt, state)
    store.retain(value)
    store.send(value, collector)
  }

  /** The union of what was observed blocked so far and `more`, at most `MaxBlocked` of them: an item left out sets the flag for good. */
  private def union(wait: Active, more: Set[ItemId]): Active = {
    val fresh = (more -- wait.blocked).toList.sortBy(LedgerPolicy.key)
    val room = math.max(0, cq.core.UsageService.MaxBlocked - wait.blocked.size)
    wait.copy(blocked = wait.blocked ++ fresh.take(room), truncated = wait.truncated || fresh.size > room)
  }

  /** The session began to wait on `item`, which blocks `blocked` as far as the host could read. */
  def begin(item: ItemId, blocked: Set[ItemId]): Unit = synchronized {
    if (!active.contains(item)) {
      val now = clock.millis()
      val wait = union(Active(RequestId(OperatorWaits.identity(run.attempt.session, item, now)), now, Set.empty, false, 1, now), blocked)
      active = active.updated(item, wait)
      observe(item, wait, now, AttemptState.Running)
    }
  }
  def waiting: Set[ItemId] = synchronized(active.keySet)
  def isWaiting(item: ItemId): Boolean = synchronized(active.contains(item))

  /** One round saw the session still waiting on `item`, which blocks `blocked` as far as the host could read: a union that grew, or
    * the time since the last observation, makes a new observation. */
  def refresh(item: ItemId, blocked: Set[ItemId]): Unit = synchronized {
    active.get(item).foreach { wait =>
      val now = math.max(clock.millis(), wait.observedAt)
      val next = union(wait, blocked)
      val grown = next.blocked != wait.blocked || next.truncated != wait.truncated
      if (grown || now - wait.observedAt >= interval.toMillis) {
        val observed = next.copy(observation = wait.observation + 1)
        active = active.updated(item, observed)
        observe(item, observed, now, AttemptState.Running)
      } else {
        // An unsent observation is sent again.
        store.unsent(wait.id).foreach(store.send(_, collector))
      }
    }
  }

  /** The wait on `item` ended: `state` is Completed when a person settled it and Cancelled when the session released it. */
  def end(item: ItemId, state: AttemptState): Unit = synchronized {
    active.get(item).foreach { wait =>
      val now = math.max(clock.millis(), wait.observedAt)
      observe(item, wait.copy(observation = wait.observation + 1), now, state)
    }
  }
  /** The session ended: every wait it was in is cancelled. */
  def close(): Unit = synchronized(active.keys.toList.foreach(end(_, AttemptState.Cancelled)))
  /** Sends every record whose latest observation the server has not acknowledged; returns how many were acknowledged. Records that
    * stay unacknowledged are reported by `unacknowledged`. */
  def flush(): Int = synchronized(store.flush(collector))
}

object OperatorWaits {
  val Directory = "waits"
  /** Between two observations of a wait that does not change. */
  val Refresh: Duration = Duration.ofMinutes(5)
  def identity(session: SessionId, item: ItemId, startedAt: Long): UUID =
    UUID.nameUUIDFromBytes(s"await:${session.value}:${item.ledger}:${item.number}:$startedAt".getBytes(UTF_8))

  /** Whether recovery has a retained wait left to end or to send. */
  def pending(directory: Path, project: ProjectId): Boolean = new WaitStore(directory.resolve(Directory), project).pending

  /** The startup recovery of a later host and `cq job upload`: a wait still Running when its host ended is ended as Unknown with the
    * finish time of its last observation, and every record the server did not acknowledge is sent. Returns how many were sent. */
  def recover(directory: Path, project: ProjectId, collector: ServerApi): Int = {
    val store = new WaitStore(directory.resolve(Directory), project)
    store.recover()
    val sent = store.flush(collector)
    val left = store.unacknowledged
    if (left > 0) throw new java.io.IOException(s"$left operator wait records of the session are not acknowledged by the server and stay retained")
    sent
  }
}

/** The retained records of the waits of one session: one file per wait, replaced by each observation. */
final class WaitStore(root: Path, project: ProjectId) {
  private val MaxRecordBytes = 256 * 1024
  private def path(id: RequestId): Path = root.resolve(id.value.toString + ".json")

  def retain(span: PhaseSpan): Unit = {
    HostFiles.directory(root)
    val acknowledged = read(span.id).flatMap(_.acknowledged)
    HostFiles.replace(path(span.id), HostFiles.encode(RetainedWait_JsonCodec, RetainedWait(span, acknowledged)), MaxRecordBytes)
  }
  def read(id: RequestId): Option[RetainedWait] = Option.when(Files.exists(path(id)))(HostFiles.read(path(id), RetainedWait_JsonCodec, MaxRecordBytes))
  def unsent(id: RequestId): Option[PhaseSpan] = read(id).filter(record => !record.acknowledged.exists(_ >= observation(record.span))).map(_.span)
  private def observation(span: PhaseSpan): Long = span.await.fold(0L)(_.observation)

  /** Sends the span the record holds. A send that fails leaves the record: the next observation replaces it. A send for an
    * observation that is no longer the retained one acknowledges nothing. */
  def send(span: PhaseSpan, api: ServerApi): Boolean =
    try {
      api.usage(HostUsageInput(project, HostUsage.Span(span)))
      read(span.id).filter(_.span == span).foreach(record =>
        HostFiles.replace(path(span.id), HostFiles.encode(RetainedWait_JsonCodec, record.copy(acknowledged = Some(observation(span)))), MaxRecordBytes))
      true
    } catch { case NonFatal(_) => false }

  private def records: List[RetainedWait] = if (!Files.exists(root)) Nil else {
    HostFiles.directory(root)
    Using.resource(Files.list(root))(_.iterator().asScala.toList).filter(_.getFileName.toString.matches("[0-9a-f-]{36}\\.json")).sortBy(_.getFileName.toString)
      .map(HostFiles.read(_, RetainedWait_JsonCodec, MaxRecordBytes))
  }
  /** Some retained wait is still Running or has an observation the server did not acknowledge. */
  def pending: Boolean = records.exists(record => record.span.state == AttemptState.Running || !record.acknowledged.exists(_ >= observation(record.span)))
  /** How many retained records have an observation the server did not acknowledge. */
  def unacknowledged: Int = records.count(record => !record.acknowledged.exists(_ >= observation(record.span)))
  /** Ends every retained wait that is still Running as Unknown, with the finish time of its last observation. */
  def recover(): Unit = records.filter(_.span.state == AttemptState.Running).foreach { record =>
    val wait = record.span.await.get
    val ended = record.span.copy(state = AttemptState.Unknown, await = Some(wait.copy(observation = wait.observation + 1)))
    HostFiles.replace(path(ended.id), HostFiles.encode(RetainedWait_JsonCodec, RetainedWait(ended, record.acknowledged)), MaxRecordBytes)
  }
  /** Sends every record whose latest observation is not acknowledged; returns how many were acknowledged. A record the server
    * refuses or cannot be reached for stays. */
  def flush(api: ServerApi): Int = records.filter(record => !record.acknowledged.exists(_ >= observation(record.span))).count(record => send(record.span, api))
}
