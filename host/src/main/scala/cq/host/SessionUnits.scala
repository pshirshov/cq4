package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.LedgerPolicy
import io.circe.parser
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The units of work of one governing session that end without the session: child attempts, integrations, combinations and check
  * revalidations. The host appends an event when it starts working on one and when it stops; `cq wait` reads them. Several writers of
  * one host append to the same file, one at a time: an event is one whole line, or the file is cut back to what it held before.
  * What the session waits on a person for is in the same file: a person settles it without the session, as a unit ends without it. */
final class SessionUnits(directory: Path) {
  private def append(event: SessionUnitEvent): Unit = {
    val line = java.nio.ByteBuffer.wrap((SessionUnitEvent_JsonCodec.encode(BaboonCodecContext.Default, event).noSpaces + "\n").getBytes(UTF_8))
    // One host process holds one session directory, and its controllers each have a writer: the appends of the process are serialized.
    SessionUnits.synchronized {
      Using.resource(FileChannel.open(directory.resolve(SessionUnits.File), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { channel =>
        val before = channel.size()
        try {
          channel.position(before)
          while (line.hasRemaining) channel.write(line)
          channel.force(true)
        } catch {
          // No fragment stays for the next event to be joined to.
          case error: Throwable => channel.truncate(before); channel.force(true); throw error
        }
      }
    }
  }
  def started(unit: SessionUnit): Unit = append(SessionUnitEvent.Started(unit))
  def ended(end: UnitEnd): Unit = append(SessionUnitEvent.Ended(end))
  def watching(item: ItemId): Unit = append(SessionUnitEvent.Watching(item))
  def released(item: ItemId): Unit = append(SessionUnitEvent.Released(item))
  /** The end is given to one waiter that runs on the session, or to none, while no waiter of the session starts or ends.
    * `abandon` ends the wait for a waiter that holds its turn and does not go on. */
  def settled(end: AwaitedEnd, abandon: () => Boolean): Unit =
    SessionWaiters.addressing(directory, abandon)(waiter => append(SessionUnitEvent.Settled(end, waiter)))
}

object SessionUnits {
  val File = "units.jsonl"

  def attempt(status: DispatchStatus): SessionUnit = SessionUnit(SessionUnitKind.Attempt, status.attempt.value, status.members)
  def ended(status: DispatchStatus): UnitEnd = UnitEnd(attempt(status), status.phase.toString, Some(status.next.toString), status.blocker)
  def integration(status: IntegrationStatus): SessionUnit =
    SessionUnit(SessionUnitKind.Integration, status.id.value, status.preview.toList.flatMap(_.members.map(_.id)))
  def ended(status: IntegrationStatus): UnitEnd = UnitEnd(integration(status), status.phase.toString, Some(status.next.toString), status.blocker)
  def combination(status: CombinationStatus): SessionUnit =
    SessionUnit(SessionUnitKind.Combination, status.id.value, status.preview.toList.flatMap(_.members.map(_.id)))
  def ended(status: CombinationStatus): UnitEnd = UnitEnd(combination(status), status.phase.toString, None, status.blocker)
  def revalidation(status: RevalidationStatus): SessionUnit = SessionUnit(SessionUnitKind.Revalidation, status.id.value, Nil)
  def ended(status: RevalidationStatus): UnitEnd = UnitEnd(revalidation(status), status.phase.toString, None, status.blocker)

  /** The events written so far, oldest first. A last line without its line end is an append in progress and is not read. */
  def read(directory: Path): List[SessionUnitEvent] = {
    val file = directory.resolve(File)
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Nil
    else {
      // Cut at the last line end before decoding: an append in progress may end inside a character.
      val bytes = Files.readAllBytes(file)
      val text = UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, bytes.lastIndexOf('\n'.toByte) + 1)).toString
      text.linesIterator.map { line =>
        SessionUnitEvent_JsonCodec.decode(BaboonCodecContext.Default, parser.parse(line).fold(throw _, identity)).fold(throw _, identity)
      }.toList
    }
  }

  /** Where each unit stands after `events`: the units the host works on, in the order it started them, and how the others ended last. */
  def standing(events: List[SessionUnitEvent]): (List[SessionUnit], Map[(SessionUnitKind, java.util.UUID), UnitEnd]) =
    events.foldLeft((List.empty[SessionUnit], Map.empty[(SessionUnitKind, java.util.UUID), UnitEnd])) {
      case ((active, ended), SessionUnitEvent.Started(unit)) =>
        (active.filterNot(same(_, unit)) :+ unit, ended - ((unit.kind, unit.id)))
      case ((active, ended), SessionUnitEvent.Ended(end)) =>
        (active.filterNot(same(_, end.unit)), ended.updated((end.unit.kind, end.unit.id), end))
      case (units, _: SessionUnitEvent.Watching | _: SessionUnitEvent.Settled | _: SessionUnitEvent.Released) => units
    }
  private def same(left: SessionUnit, right: SessionUnit): Boolean = left.kind == right.kind && left.id == right.id

  def reference(id: ItemId): String = LedgerPolicy.prefix(id.ledger) + id.number
  def described(unit: SessionUnit): String =
    s"${unit.kind.toString.toLowerCase} ${unit.id}" + (if (unit.members.isEmpty) "" else " on " + unit.members.map(reference).mkString(","))
  /** One line for a person or a model: what ended and how. */
  def described(end: UnitEnd): String = s"${described(end.unit)} ended: ${end.phase}" + end.next.fold("")(", next " + _) +
    end.blocker.fold("")(value => ", blocker: " + DispatchProjection.concise(value).replaceAll("\\s+", " "))
}

/** What the events of a session say about the items it waits on a person for. */
object SessionAwaited {
  /** The items the session waits on after `events`, in the order it began to. */
  def open(events: List[SessionUnitEvent]): List[ItemId] = events.foldLeft(List.empty[ItemId]) {
    case (open, SessionUnitEvent.Watching(item)) => open.filterNot(_ == item) :+ item
    case (open, SessionUnitEvent.Settled(end, _)) => open.filterNot(_ == end.item)
    case (open, SessionUnitEvent.Released(item)) => open.filterNot(_ == item)
    case (open, _) => open
  }
  private def ends(events: List[SessionUnitEvent], from: Int, until: Int)(reported: Option[Long] => Boolean): List[AwaitedEnd] = events.zipWithIndex.collect {
    case (SessionUnitEvent.Settled(end, waiter), index) if index >= from && index < until && reported(waiter) &&
      !events.drop(index).contains(SessionUnitEvent.Released(end.item)) => end
  }
  /** The ends among the events `from` to `until` that the session has not read since, whoever was given them. */
  def unread(events: List[SessionUnitEvent], from: Int, until: Int): List[AwaitedEnd] = ends(events, from, until)(_ => true)
  /** The ends from `from` on that the host gave to the waiter of `slot` and the session has not read since. */
  def handed(events: List[SessionUnitEvent], from: Int, slot: Long): List[AwaitedEnd] = ends(events, from, events.size)(_.contains(slot))

  val MaxTitle = 80
  val MaxDetail = 300
  // User text on one line and unmistakable as text: control characters and the line and paragraph separators become spaces, what is
  // longer than `limit` code points is cut and marked, and the whole is quoted with `\` and `"` escaped. The Pi extension does the same.
  private def quoted(value: String, limit: Int): String = {
    val line = value.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ").replaceAll(" +", " ").replaceAll("^ | $", "")
    val cut = if (line.codePointCount(0, line.length) <= limit) line else line.substring(0, line.offsetByCodePoints(0, limit)) + "…"
    "\"" + cut.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  }
  private def kind(ledger: Ledger): String = ledger match {
    case Ledger.Questions => "question"
    case Ledger.OperatorActions => "operator action"
    case other => throw new IllegalArgumentException(s"No session waits on a person for an item of $other")
  }
  /** One line for a person or a model: which item was settled and how. */
  def described(end: AwaitedEnd): String = s"${kind(end.item.ledger)} ${SessionUnits.reference(end.item)} ${quoted(end.title, MaxTitle)} ${end.status.toLowerCase}" +
    end.detail.fold("")(value => ": " + quoted(value, MaxDetail))
  /** What a session that is told of such ends does with them, in the words every announcement uses. */
  val Act = "Read each of them with the CQ read tool (ItemDetail) and act on it before you end your turn."
}

/** One process as the operating system tells it from every other: a process identifier is given again, its start with it is not. */
final case class ProcessIdentity(pid: Long, startMillis: Long)
object ProcessIdentity {
  def of(handle: ProcessHandle): Option[ProcessIdentity] = {
    val started = handle.info().startInstant()
    Option.when(started.isPresent)(ProcessIdentity(handle.pid(), started.get.toEpochMilli))
  }
  /** The processes `handle` descends from, nearest first. One whose start cannot be read is left out: it cannot be told from another. */
  def ancestors(handle: ProcessHandle): List[ProcessIdentity] =
    Iterator.iterate(handle.parent())(_.flatMap(_.parent())).takeWhile(_.isPresent).map(_.get).flatMap(of).toList
}

object SessionOwner {
  val Starter = "started-by"
  private val MaxBytes = 1024
  /** A host leaves the process that started it, which is its harness, beside the lock it holds, from the moment it holds it: a
    * host whose session has recorded nothing is known by it as well. */
  def provision(directory: Path, owner: ProcessIdentity): Unit = HostFiles.immutable(directory.resolve("journal").resolve(Starter),
    io.circe.Json.obj("pid" -> io.circe.Json.fromLong(owner.pid), "startMillis" -> io.circe.Json.fromLong(owner.startMillis)).noSpaces, MaxBytes)
  def startedBy(directory: Path): Option[ProcessIdentity] = {
    val file = directory.resolve("journal").resolve(Starter)
    Option.when(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      val cursor = parser.parse(HostFiles.text(file, MaxBytes)).fold(throw _, identity).hcursor
      ProcessIdentity(cursor.get[Long]("pid").fold(throw _, identity), cursor.get[Long]("startMillis").fold(throw _, identity))
    }
  }
  /** Every host holds the exclusive lock on `journal/owner.lock` of its session directory for its lifetime, and the operating system
    * releases it when that process ends. A shared lock that is refused therefore proves a live host; it needs read access only. */
  def runs(directory: Path): Boolean = {
    val lock = directory.resolve("journal").resolve("owner.lock")
    Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && Using.resource(FileChannel.open(lock, StandardOpenOption.READ)) { channel =>
      (try Option(channel.tryLock(0L, Long.MaxValue, true)) catch { case _: java.nio.channels.OverlappingFileLockException => None })
        .fold(true) { held => held.release(); false }
    }
  }
}

/** `cq wait`: blocks until a unit of the session ends or a person settles what the session waits on. It has no timeout of its own:
  * it ends when one of those happens or when the host is gone. `slot` tells this waiter from every other on the session, so that
  * the host can give an end to one of them: the process identifier of the command. */
final class SessionWait(directory: Path, pause: () => Unit, slot: Long) {
  if (!Files.isRegularFile(directory.resolve("run.json"), LinkOption.NOFOLLOW_LINKS))
    throw new SessionWait.NotASession(s"$directory is not a CQ session directory: it holds no run.json")

  /** Waits as [[awaited]] does, holding the session's waiter lock meanwhile; a batch session, which nothing wakes, has none. */
  def await(named: List[(SessionUnitKind, java.util.UUID)], after: SessionWait.After): WaitOutcome =
    if (!Files.exists(directory.resolve(SessionWaiters.File), LinkOption.NOFOLLOW_LINKS)) awaited(named, after, read => (read(), last => last))
    else {
      val (held, first) = SessionWaiters.hold(directory, slot)(SessionUnits.read(directory))
      try awaited(named, after, _ => (first, last => held.leave(last))) finally held.close()
    }

  /** `named` are the units to wait for; when empty, the units the host works on at the first reading. What the session waits on a
    * person for is waited for as well, whatever is named. An end is reported when the host gave it to this waiter, and, once, when it
    * was written before this waiter began, at or after `after`, and the session has not read it since: the end a waiter that was
    * killed did not report, or one that was written while no waiter ran.
    * `begin` takes the first reading and returns how the waiter leaves: with a last reading that still gives an outcome, or not at all. */
  private def awaited(named: List[(SessionUnitKind, java.util.UUID)], after: SessionWait.After,
    begin: (() => List[SessionUnitEvent]) => (List[SessionUnitEvent], (=> Option[WaitOutcome]) => Option[WaitOutcome])): WaitOutcome = {
    // The event file only grows: it is read again when its size has changed.
    var seen = (-1L, List.empty[SessionUnitEvent])
    def read(): List[SessionUnitEvent] = {
      val file = directory.resolve(SessionUnits.File)
      val size = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.size(file) else 0L
      if (size != seen._1) seen = (size, SessionUnits.read(directory))
      seen._2
    }
    val (first, leave) = begin(() => read())
    val (initial, known) = SessionUnits.standing(first)
    named.find(key => !initial.exists(unit => (unit.kind, unit.id) == key) && !known.contains(key)).foreach { (kind, id) =>
      throw new IllegalArgumentException(s"${kind.toString.toLowerCase} $id is not a unit of the session in $directory")
    }
    val targets = if (named.nonEmpty) named else initial.map(unit => (unit.kind, unit.id))
    val from = after match {
      case SessionWait.After.Start => 0
      case SessionWait.After.Events(count) => count.min(first.size)
      case SessionWait.After.Now => first.size
    }
    def outcome(events: List[SessionUnitEvent]): Option[WaitOutcome] = {
      val (active, ended) = SessionUnits.standing(events)
      val over = targets.flatMap(ended.get)
      val settled = SessionAwaited.unread(events, from, first.size) ++ SessionAwaited.handed(events, first.size, slot)
      Option.when(over.nonEmpty || settled.nonEmpty)(WaitOutcome.Ended(over, active, settled))
    }
    // With no unit to wait for, the wait lasts while the session waits on a person. Nothing to wait for is said only of a host
    // that runs: a host that is gone is reported as such, whatever it left.
    def finished(events: List[SessionUnitEvent], alive: Boolean): Option[WaitOutcome] = outcome(events)
      .orElse(Option.when(!alive)(WaitOutcome.HostGone(SessionUnits.standing(events)._1)))
      .orElse(Option.when(targets.isEmpty && SessionAwaited.open(events).isEmpty)(WaitOutcome.Idle()))
    var result = Option.empty[WaitOutcome]
    var events = first
    while (result.isEmpty) {
      // The host is asked first: an end it wrote just before it exited is read after it and still reported.
      val alive = SessionOwner.runs(directory)
      // The waiter leaves with a last reading: an end the host gave to it meanwhile is in it, and a reason to leave that the
      // session has taken back meanwhile, by reading what was settled, is none any more.
      if (finished(events, alive).nonEmpty) result = leave(finished(read(), alive))
      if (result.isEmpty) { pause(); events = read() }
    }
    result.get
  }
}

object SessionWait {
  final class NotASession(message: String) extends RuntimeException(message)
  /** Which ends that were written before the waiter began it reports, when the session has not read them: all, those from a number
    * of events on, or none. */
  sealed trait After
  object After {
    case object Start extends After
    final case class Events(count: Int) extends After
    case object Now extends After
    val Flag = "--after"
    def parse(value: String): After = if (value == "now") Now else {
      val count = value.toIntOption.filter(_ >= 0).getOrElse(throw new IllegalArgumentException(s"$Flag takes a number of events or now"))
      Events(count)
    }
  }
  /** How often the session directory's event file is read again. A local file-stat interval, not a protocol timeout. */
  val PollMillis = 250L
  val HostGoneExit = 3
  val NotASessionExit = 4
  val SeveralHostsExit = 5
  def lines(directory: Path, outcome: WaitOutcome): List[String] = outcome match {
    case WaitOutcome.Ended(ended, active, settled) => ended.map(SessionUnits.described) ++ settled.map(SessionAwaited.described) ++
      active.map(unit => "still active: " + SessionUnits.described(unit))
    case _: WaitOutcome.Idle => List("No child attempt, integration, combination or revalidation of this session is active")
    case WaitOutcome.HostGone(active) => List(s"The CQ host of the session in $directory is not running" +
      (if (active.isEmpty) "" else "; unfinished when it ended: " + active.map(SessionUnits.described).mkString("; ")) +
      ". Restart the harness session; retained deliveries are recovered with cq job upload --session " + directory)
  }
}

/** A `cq wait` holds a shared lock on one byte of `waiters.lock` of the session directory it waits on, for as long as it runs: the
  * byte of its slot. Whoever must know that the session will be told when its work ends asks whether an exclusive lock on the slots
  * is refused: no process identity is guessed. The first byte is the turn. It orders the start and the end of a waiter against
  * every look at the slots: a waiter takes its first and its last reading under a shared lock on it, and the host decides which
  * waiter an end is given to under an exclusive one, as does whoever asks whether a waiter runs. A waiter the host finds running
  * therefore reads what the host then writes, and a look never passes for a waiter. One process takes its locks on the file through
  * one channel: closing any channel on a file gives up every lock the process holds on it. */
object SessionWaiters {
  val File = "waiters.lock"
  private val Turn = 0L
  private val Slots = 1L
  /** Slots are process identifiers. */
  val MaxSlots: Long = 1L << 32
  /** How often a turn that is taken is asked for again. A local lock interval, not a protocol timeout. */
  private val TurnMillis = 5L
  /** The host creates the file with its session directory; a waiter only reads the directory. */
  def create(directory: Path): Unit = HostFiles.immutable(directory.resolve(File), "", 0)
  // The locks of one process on a file do not exclude each other, and an overlapping request of the same JVM is refused rather
  // than made to wait: within a process the turn is taken under this monitor.
  private def turn[A](channel: FileChannel, shared: Boolean, abandon: () => Boolean)(body: => A): A = synchronized {
    var lock = channel.tryLock(Turn, 1L, shared)
    while (lock == null) {
      if (abandon()) throw new IllegalStateException("A waiter of the session holds its turn and does not go on")
      Thread.sleep(TurnMillis)
      lock = channel.tryLock(Turn, 1L, shared)
    }
    try body finally lock.release()
  }
  final class Held private[SessionWaiters] (channel: FileChannel, running: java.nio.channels.FileLock) extends AutoCloseable {
    /** Takes the waiter's last reading in one turn, and ends the waiter in that turn when the reading is one to leave with. */
    def leave[A](last: => Option[A]): Option[A] = turn(channel, true, () => false) { val value = last; if (value.nonEmpty) running.release(); value }
    override def close(): Unit = channel.close()
  }
  /** Starts the waiter of `slot` and takes its first reading in one turn. */
  def hold[A](directory: Path, slot: Long)(first: => A): (Held, A) = {
    require(slot >= 0 && slot < MaxSlots, s"A waiter's slot is a number below $MaxSlots")
    val channel = FileChannel.open(directory.resolve(File), StandardOpenOption.READ)
    try turn(channel, true, () => false) { val running = channel.lock(Slots + slot, 1L, true); (new Held(channel, running), first) }
    catch { case error: Throwable => channel.close(); throw error }
  }
  private def free(channel: FileChannel, from: Long, size: Long): Boolean =
    (try Option(channel.tryLock(Slots + from, size, false)) catch { case _: java.nio.channels.OverlappingFileLockException => None }).fold(false) { held => held.release(); true }
  // The lowest slot a waiter holds: an exclusive lock on a range is refused while a waiter holds a byte of it.
  private def lowest(channel: FileChannel, from: Long, size: Long): Option[Long] =
    if (free(channel, from, size)) None
    else if (size == 1L) Some(from)
    else lowest(channel, from, size / 2).orElse(lowest(channel, from + size / 2, size - size / 2))
  /** Runs `write` with the slot of one waiter that runs on the session, when one does, while none starts or ends. */
  def addressing[A](directory: Path, abandon: () => Boolean)(write: Option[Long] => A): A =
    Using.resource(FileChannel.open(directory.resolve(File), StandardOpenOption.WRITE)) { channel =>
      turn(channel, false, abandon)(write(lowest(channel, 0L, MaxSlots)))
    }
  def present(directory: Path): Boolean = addressing(directory, () => false)(_.nonEmpty)
}

/** How the attached host of a session stands, as a process of its checkout finds it. */
sealed trait HostView
object HostView {
  /** No host of this checkout recorded the session: its host ended in order, its session has done no governing work yet, or it is a host of a package before the record existed. */
  case object Unrecorded extends HostView
  /** The host recorded the session and no longer holds its lock. */
  case object Gone extends HostView
  /** `standing` are the units the host works on and `watched` the items the session waits on a person for; `waited` says that a `cq wait` runs on the session. */
  final case class Running(directory: Path, standing: List[SessionUnit], watched: List[ItemId], waited: Boolean, waitCommand: Option[String]) extends HostView
}

/** Where the attached hosts of one checkout say which session directory each maintains (`hosts/` of the checkout's CQ directory).
  * A hook of that checkout knows a drive's attached session only by its identity, and a `cq wait` without a directory knows only its
  * checkout: both ask here. */
trait SessionViews {
  /** The attached session of the harness process that runs this hook, for the harness session `key` the hook was given: the session
    * of the one live host of the checkout that the nearest ancestor of the asking process to have started a host started. None when
    * no ancestor started a live host, when that ancestor started several, and when the hook of another harness session found the
    * host first. */
  def owned(key: String): Option[SessionId]
  def view(session: SessionId): HostView
  /** The standing units the session's last stop was answered for with the order to start its waiter; none when it was answered otherwise. */
  def asked(session: SessionId): Option[String]
  def ask(session: SessionId, units: Option[String]): Unit
  /** The ends the session has not read that no turn end of the session has announced yet; they count as announced from then on. */
  def announce(session: SessionId): List[AwaitedEnd]
}

/** The sessions of a checkout as a process that descends from `ancestors` finds them. */
final class CheckoutSessions(sessions: () => AttachedSessions, ancestors: () => List[ProcessIdentity]) extends SessionViews {
  override def owned(key: String): Option[SessionId] = sessions().owned(ancestors(), key)
  override def view(session: SessionId): HostView = sessions().view(session)
  override def asked(session: SessionId): Option[String] = sessions().asked(session)
  override def ask(session: SessionId, units: Option[String]): Unit = sessions().ask(session, units)
  override def announce(session: SessionId): List[AwaitedEnd] = sessions().announce(session)
}

final class AttachedSessions(configuration: Path) {
  private val MaxBytes = 16384
  private val root = configuration.resolve("hosts")
  private def beside(session: SessionId, suffix: String): Path = root.resolve(session.value.toString + suffix)
  private def file(session: SessionId): Path = beside(session, ".json")
  // Where the directory of a host stands from the moment the host holds its lock, whether or not its session records anything.
  private def live(session: SessionId): Path = beside(session, ".live")
  // How many events of the session the turn ends of the session have examined for ends.
  private def told(session: SessionId): Path = beside(session, ".told")
  private def prompt(session: SessionId): Path = beside(session, ".asked")
  // The harness session whose hook found the host first.
  private def harness(session: SessionId): Path = beside(session, ".harness")
  private def read(path: Path): AttachedHostRecord = HostFiles.read(path, AttachedHostRecord_JsonCodec, MaxBytes)
  private def named(suffix: String): List[(SessionId, Path)] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
    else Using.resource(Files.list(root))(_.iterator().asScala.filter(_.getFileName.toString.endsWith(suffix)).toList).sortBy(_.getFileName.toString)
      .map(path => SessionId(java.util.UUID.fromString(path.getFileName.toString.stripSuffix(suffix))) -> path)
  private def withdraw(session: SessionId): Unit =
    List(file(session), live(session), prompt(session), told(session), harness(session)).foreach(Files.deleteIfExists(_))

  /** A starting host says where its session directory is, and withdraws what hosts that were killed left: their lock is free. What
    * such a host had written for the turn ends of its session and nobody announced is not carried to another host. */
  def provision(session: SessionId, directory: Path): Unit = {
    HostFiles.directory(root)
    named(".live").foreach { (old, path) => if (!SessionOwner.runs(Path.of(HostFiles.text(path, MaxBytes)))) withdraw(old) }
    HostFiles.immutable(live(session), directory.toString, MaxBytes)
  }
  /** Records the session of a host, and withdraws the records of hosts that were killed. */
  def record(session: SessionId, value: AttachedHostRecord): Unit = {
    HostFiles.directory(root)
    recorded.foreach { (old, path, record) => if (!SessionOwner.runs(Path.of(record.directory))) withdraw(old) }
    HostFiles.immutable(file(session), HostFiles.encode(AttachedHostRecord_JsonCodec, value), MaxBytes)
  }
  /** A host that ends in order withdraws its record. */
  def forget(session: SessionId): Unit = withdraw(session)
  // The reading of the count and its advance exclude another turn end of the session.
  def announce(session: SessionId): List[AwaitedEnd] = view(session) match {
    case running: HostView.Running =>
      Using.resource(FileChannel.open(told(session), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) { channel =>
        val lock = channel.lock()
        try {
          val events = SessionUnits.read(running.directory)
          // Read through the channel that holds the lock: another descriptor of the file, once closed, would give the lock up.
          val content = java.nio.ByteBuffer.allocate(Math.toIntExact(channel.size().min(MaxBytes.toLong)))
          while (content.hasRemaining && channel.read(content, content.position().toLong) >= 0) ()
          val recorded = new String(content.array(), 0, content.position(), UTF_8)
          val from = if (recorded.isEmpty) 0 else recorded.toIntOption.getOrElse(throw new IllegalStateException(s"${told(session)} holds no number of events"))
          val ends = SessionAwaited.unread(events, from, events.size)
          if (ends.nonEmpty) {
            channel.truncate(0L)
            channel.write(java.nio.ByteBuffer.wrap(events.size.toString.getBytes(UTF_8)), 0L)
            channel.force(true)
          }
          ends
        } finally lock.release()
      }
    case _ => Nil
  }
  def asked(session: SessionId): Option[String] = Option.when(Files.isRegularFile(prompt(session), LinkOption.NOFOLLOW_LINKS))(HostFiles.text(prompt(session), MaxBytes))
  def ask(session: SessionId, units: Option[String]): Unit = units match {
    case Some(value) => HostFiles.directory(root); Files.writeString(prompt(session), value)
    case None => Files.deleteIfExists(prompt(session)); ()
  }
  private def recorded: List[(SessionId, Path, AttachedHostRecord)] = named(".json").map((session, path) => (session, path, read(path)))
  /** The session directories of the hosts of this checkout that run. */
  def running: List[AttachedHostRecord] = recorded.map(_._3).filter(value => SessionOwner.runs(Path.of(value.directory)))
  /** The session of the harness process among `ancestors`, nearest first, for the harness session `key`: see [[SessionViews.owned]].
    * Every live host of the checkout counts, also one whose session has recorded nothing: a harness that a session started in its
    * shell is nearer to its own hook than the harness of that session, and is not given the other's ends. */
  def owned(ancestors: List[ProcessIdentity], key: String): Option[SessionId] = {
    val started = named(".live").flatMap { (session, path) =>
      val directory = Path.of(HostFiles.text(path, MaxBytes))
      if (SessionOwner.runs(directory)) SessionOwner.startedBy(directory).map(session -> _) else None
    }
    ancestors.iterator.map(ancestor => started.filter(_._2 == ancestor).map(_._1)).find(_.nonEmpty) match {
      case Some(List(session)) =>
        HostFiles.directory(root)
        try { Files.writeString(harness(session), key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); Some(session) }
        catch { case _: java.nio.file.FileAlreadyExistsException => Option.when(HostFiles.text(harness(session), MaxBytes) == key)(session) }
      case _ => None
    }
  }
  def view(session: SessionId): HostView = {
    val path = file(session)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) HostView.Unrecorded
    else {
      val value = read(path)
      val directory = Path.of(value.directory)
      if (!SessionOwner.runs(directory)) HostView.Gone
      else {
        val events = SessionUnits.read(directory)
        HostView.Running(directory, SessionUnits.standing(events)._1, SessionAwaited.open(events), SessionWaiters.present(directory), value.waitCommand)
      }
    }
  }
}
