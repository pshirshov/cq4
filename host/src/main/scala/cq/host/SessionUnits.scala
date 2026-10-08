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
  * The Open Questions the session waits on are in the same file: a person settles them without the session, as a unit ends without it. */
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
  def watching(question: ItemId): Unit = append(SessionUnitEvent.Watching(question))
  def released(question: ItemId): Unit = append(SessionUnitEvent.Released(question))
  /** Whether a waiter reports the end, or the session's next turn end, is decided while no waiter of the session starts or ends. */
  def settled(end: QuestionEnd): Unit = SessionWaiters.addressing(directory)(waiter => append(SessionUnitEvent.Settled(end, waiter)))
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

/** What the events of a session say about the Questions it waits on. */
object SessionQuestions {
  /** The Open Questions the session waits on after `events`, in the order it began to. */
  def open(events: List[SessionUnitEvent]): List[ItemId] = events.foldLeft(List.empty[ItemId]) {
    case (open, SessionUnitEvent.Watching(question)) => open.filterNot(_ == question) :+ question
    case (open, SessionUnitEvent.Settled(end, _)) => open.filterNot(_ == end.question)
    case (open, SessionUnitEvent.Released(question)) => open.filterNot(_ == question)
    case (open, _) => open
  }
  /** The ends among the events from `from` on that a turn end of the session announces: no waiter was given them, and the session
    * has not read the Question since. */
  def unannounced(events: List[SessionUnitEvent], from: Int): List[QuestionEnd] = ends(events, from, false)
  /** The ends a waiter reports that began when the session held `from` events. */
  def waited(events: List[SessionUnitEvent], from: Int): List[QuestionEnd] = ends(events, from, true)
  private def ends(events: List[SessionUnitEvent], from: Int, waiter: Boolean): List[QuestionEnd] = events.zipWithIndex.collect {
    case (SessionUnitEvent.Settled(end, `waiter`), index) if index >= from && !events.drop(index).contains(SessionUnitEvent.Released(end.question)) => end
  }
  /** One line for a person or a model: which Question was settled and how. */
  def described(end: QuestionEnd): String = s"question ${SessionUnits.reference(end.question)} \"${line(end.title)}\" ${end.status.toString.toLowerCase}" +
    end.answer.fold("")(value => ": " + line(value))
  private def line(value: String): String = DispatchProjection.concise(value.replaceAll("\\s+", " ").trim)
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
  private val File = "owner.json"
  private val MaxBytes = 1024
  /** The host leaves the process that started it, which is its harness, in its session directory. */
  def record(directory: Path, owner: ProcessIdentity): Unit = HostFiles.immutable(directory.resolve(File),
    io.circe.Json.obj("pid" -> io.circe.Json.fromLong(owner.pid), "startMillis" -> io.circe.Json.fromLong(owner.startMillis)).noSpaces, MaxBytes)
  def started(directory: Path): Option[ProcessIdentity] = {
    val file = directory.resolve(File)
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

/** `cq wait`: blocks until a unit of the session ends. It has no timeout of its own: it ends when a unit does or when the host is gone. */
final class SessionWait(directory: Path, pause: () => Unit) {
  if (!Files.isRegularFile(directory.resolve("run.json"), LinkOption.NOFOLLOW_LINKS))
    throw new SessionWait.NotASession(s"$directory is not a CQ session directory: it holds no run.json")

  /** Waits as [[awaited]] does, holding the session's waiter lock meanwhile; a batch session, which nothing wakes, has none. */
  def await(named: List[(SessionUnitKind, java.util.UUID)]): WaitOutcome =
    if (!Files.exists(directory.resolve(SessionWaiters.File), LinkOption.NOFOLLOW_LINKS)) awaited(named, read => (read(), identity))
    else {
      val (held, first) = SessionWaiters.hold(directory)(SessionUnits.read(directory))
      try awaited(named, _ => (first, held.leave)) finally held.close()
    }

  /** `named` are the units to wait for; when empty, the units the host works on at the first reading. A Question the session waits
    * on is waited for as well, whatever is named: its end is reported when the host gave it to the waiters that ran then.
    * `begin` takes the first reading and returns how the last one is taken. */
  private def awaited(named: List[(SessionUnitKind, java.util.UUID)],
    begin: (() => List[SessionUnitEvent]) => (List[SessionUnitEvent], (=> List[SessionUnitEvent]) => List[SessionUnitEvent])): WaitOutcome = {
    // The event file only grows: it is read again when its size has changed.
    var seen = (-1L, List.empty[SessionUnitEvent])
    def read(): List[SessionUnitEvent] = {
      val file = directory.resolve(SessionUnits.File)
      val size = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.size(file) else 0L
      if (size != seen._1) seen = (size, SessionUnits.read(directory))
      seen._2
    }
    val (first, last) = begin(() => read())
    val (initial, known) = SessionUnits.standing(first)
    named.find(key => !initial.exists(unit => (unit.kind, unit.id) == key) && !known.contains(key)).foreach { (kind, id) =>
      throw new IllegalArgumentException(s"${kind.toString.toLowerCase} $id is not a unit of the session in $directory")
    }
    val targets = if (named.nonEmpty) named else initial.map(unit => (unit.kind, unit.id))
    def outcome(events: List[SessionUnitEvent]): Option[WaitOutcome] = {
      val (active, ended) = SessionUnits.standing(events)
      val over = targets.flatMap(ended.get)
      val settled = SessionQuestions.waited(events, first.size)
      Option.when(over.nonEmpty || settled.nonEmpty)(WaitOutcome.Ended(over, active, settled))
    }
    // With no unit to wait for, the wait lasts while the session waits on a Question. Nothing to wait for is said only of a host
    // that runs: a host that is gone is reported as such, whatever it left.
    def finished(events: List[SessionUnitEvent], alive: Boolean): Option[WaitOutcome] = outcome(events)
      .orElse(Option.when(!alive)(WaitOutcome.HostGone(SessionUnits.standing(events)._1)))
      .orElse(Option.when(targets.isEmpty && SessionQuestions.open(events).isEmpty)(WaitOutcome.Idle()))
    var result = if (targets.isEmpty && SessionQuestions.open(first).isEmpty) finished(first, SessionOwner.runs(directory)) else outcome(first)
    while (result.isEmpty) {
      // The host is asked first: an end it wrote just before it exited is read after it and still reported.
      val alive = SessionOwner.runs(directory)
      result = finished(read(), alive)
      if (result.isEmpty) pause()
    }
    // The last reading is taken as the waiter leaves: an end the host gave to this waiter meanwhile is in it.
    result match {
      case Some(_: WaitOutcome.Ended) => outcome(last(read())).get
      case other => last(read()); other.get
    }
  }
}

object SessionWait {
  final class NotASession(message: String) extends RuntimeException(message)
  /** How often the session directory's event file is read again. A local file-stat interval, not a protocol timeout. */
  val PollMillis = 250L
  val HostGoneExit = 3
  val NotASessionExit = 4
  val SeveralHostsExit = 5
  def lines(directory: Path, outcome: WaitOutcome): List[String] = outcome match {
    case WaitOutcome.Ended(ended, active, settled) => ended.map(SessionUnits.described) ++ settled.map(SessionQuestions.described) ++
      active.map(unit => "still active: " + SessionUnits.described(unit))
    case _: WaitOutcome.Idle => List("No child attempt, integration, combination or revalidation of this session is active")
    case WaitOutcome.HostGone(active) => List(s"The CQ host of the session in $directory is not running" +
      (if (active.isEmpty) "" else "; unfinished when it ended: " + active.map(SessionUnits.described).mkString("; ")) +
      ". Restart the harness session; retained deliveries are recovered with cq job upload --session " + directory)
  }
}

/** A `cq wait` holds a shared lock on the first byte of `waiters.lock` of the session directory it waits on, for as long as it runs.
  * Whoever must know that the session will be told when its work ends asks whether an exclusive lock is refused: no process identity
  * is guessed. The second byte orders the start and the end of a waiter against the host's decision who reports the end of a
  * Question: a waiter takes its first and its last reading under a shared lock on it and the host decides under an exclusive one,
  * so a waiter the host finds running reads what the host then writes. One process takes its locks on the file through one channel:
  * closing any channel on a file gives up every lock the process holds on it. */
object SessionWaiters {
  val File = "waiters.lock"
  private val Running = 0L
  private val Turn = 1L
  /** The host creates the file with its session directory; a waiter only reads the directory. */
  def create(directory: Path): Unit = HostFiles.immutable(directory.resolve(File), "", 0)
  // The locks of one process on a file do not exclude each other, and an overlapping request of the same JVM is refused rather
  // than made to wait: within a process the turn is taken under this monitor.
  private def turn[A](channel: FileChannel, shared: Boolean)(body: => A): A = synchronized {
    val lock = channel.lock(Turn, 1L, shared)
    try body finally lock.release()
  }
  final class Held private[SessionWaiters] (channel: FileChannel, running: java.nio.channels.FileLock) extends AutoCloseable {
    /** Takes the waiter's last reading and ends the waiter in one turn. */
    def leave[A](last: => A): A = turn(channel, true) { val value = last; running.release(); value }
    override def close(): Unit = channel.close()
  }
  /** Starts a waiter and takes its first reading in one turn. */
  def hold[A](directory: Path)(first: => A): (Held, A) = {
    val channel = FileChannel.open(directory.resolve(File), StandardOpenOption.READ)
    try turn(channel, true) { val running = channel.lock(Running, 1L, true); (new Held(channel, running), first) }
    catch { case error: Throwable => channel.close(); throw error }
  }
  private def taken(channel: FileChannel): Boolean =
    (try Option(channel.tryLock(Running, 1L, false)) catch { case _: java.nio.channels.OverlappingFileLockException => None }).fold(true) { held => held.release(); false }
  def present(directory: Path): Boolean = Using.resource(FileChannel.open(directory.resolve(File), StandardOpenOption.WRITE))(taken)
  /** Runs `write` with whether a waiter of the session runs, while none starts or ends. */
  def addressing[A](directory: Path)(write: Boolean => A): A = Using.resource(FileChannel.open(directory.resolve(File), StandardOpenOption.WRITE)) { channel =>
    turn(channel, false)(write(taken(channel)))
  }
}

/** How the attached host of a session stands, as a process of its checkout finds it. */
sealed trait HostView
object HostView {
  /** No host of this checkout recorded the session: its host ended in order, its session has done no governing work yet, or it is a host of a package before the record existed. */
  case object Unrecorded extends HostView
  /** The host recorded the session and no longer holds its lock. */
  case object Gone extends HostView
  /** `standing` are the units the host works on and `watched` the Open Questions the session waits on; `waited` says that a `cq wait` runs on the session. */
  final case class Running(directory: Path, standing: List[SessionUnit], watched: List[ItemId], waited: Boolean, waitCommand: Option[String]) extends HostView
}

/** Where the attached hosts of one checkout say which session directory each maintains (`hosts/` of the checkout's CQ directory).
  * A hook of that checkout knows a drive's attached session only by its identity, and a `cq wait` without a directory knows only its
  * checkout: both ask here. */
trait SessionViews {
  /** The attached session whose host was started by a process the asking process descends from: the session of the harness process
    * that runs this hook. None when no host of the checkout that runs was started by such a process, or more than one was. */
  def owned: Option[SessionId]
  def view(session: SessionId): HostView
  /** The standing units the session's last stop was answered for with the order to start its waiter; none when it was answered otherwise. */
  def asked(session: SessionId): Option[String]
  def ask(session: SessionId, units: Option[String]): Unit
  /** The ends of Questions the session waits on that a turn end of the session is to announce, each returned once. */
  def announce(session: SessionId): List[QuestionEnd]
}

/** The sessions of a checkout as a process that descends from `ancestors` finds them. */
final class CheckoutSessions(sessions: () => AttachedSessions, ancestors: () => List[ProcessIdentity]) extends SessionViews {
  override def owned: Option[SessionId] = sessions().owned(ancestors())
  override def view(session: SessionId): HostView = sessions().view(session)
  override def asked(session: SessionId): Option[String] = sessions().asked(session)
  override def ask(session: SessionId, units: Option[String]): Unit = sessions().ask(session, units)
  override def announce(session: SessionId): List[QuestionEnd] = sessions().announce(session)
}

final class AttachedSessions(configuration: Path) {
  private val MaxBytes = 16384
  private val root = configuration.resolve("hosts")
  private def file(session: SessionId): Path = root.resolve(session.value.toString + ".json")
  private def read(path: Path): AttachedHostRecord = HostFiles.read(path, AttachedHostRecord_JsonCodec, MaxBytes)

  /** Records the session of a starting host, and withdraws the records of hosts that were killed: their lock is free. */
  def record(session: SessionId, value: AttachedHostRecord): Unit = {
    HostFiles.directory(root)
    recorded.foreach { (path, old) => if (!SessionOwner.runs(Path.of(old.directory))) Files.deleteIfExists(path) }
    HostFiles.immutable(file(session), HostFiles.encode(AttachedHostRecord_JsonCodec, value), MaxBytes)
  }
  /** A host that ends in order withdraws its record. */
  def forget(session: SessionId): Unit = { Files.deleteIfExists(file(session)); Files.deleteIfExists(prompt(session)); Files.deleteIfExists(told(session)); () }
  // How many events of the session the turn ends of the session have examined for the ends of Questions.
  private def told(session: SessionId): Path = root.resolve(session.value.toString + ".told")
  def announce(session: SessionId): List[QuestionEnd] = view(session) match {
    case running: HostView.Running =>
      val events = SessionUnits.read(running.directory)
      val from = if (Files.isRegularFile(told(session), LinkOption.NOFOLLOW_LINKS)) HostFiles.text(told(session), MaxBytes).toInt else 0
      val ends = SessionQuestions.unannounced(events, from)
      if (ends.nonEmpty) Files.writeString(told(session), events.size.toString)
      ends
    case _ => Nil
  }
  private def prompt(session: SessionId): Path = root.resolve(session.value.toString + ".asked")
  def asked(session: SessionId): Option[String] = Option.when(Files.isRegularFile(prompt(session), LinkOption.NOFOLLOW_LINKS))(HostFiles.text(prompt(session), MaxBytes))
  def ask(session: SessionId, units: Option[String]): Unit = units match {
    case Some(value) => HostFiles.directory(root); Files.writeString(prompt(session), value)
    case None => Files.deleteIfExists(prompt(session)); ()
  }
  private def recorded: List[(Path, AttachedHostRecord)] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
    else Using.resource(Files.list(root))(_.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).toList).sortBy(_.getFileName.toString).map(path => path -> read(path))
  /** The session directories of the hosts of this checkout that run. */
  def running: List[AttachedHostRecord] = recorded.map(_._2).filter(value => SessionOwner.runs(Path.of(value.directory)))
  /** The session of the one running host that a process among `ancestors` started; no session when there is none or several. */
  def owned(ancestors: List[ProcessIdentity]): Option[SessionId] = recorded.collect {
    case (path, value) if SessionOwner.runs(Path.of(value.directory)) && SessionOwner.started(Path.of(value.directory)).exists(ancestors.contains) =>
      SessionId(java.util.UUID.fromString(path.getFileName.toString.stripSuffix(".json")))
  } match {
    case List(only) => Some(only)
    case _ => None
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
        HostView.Running(directory, SessionUnits.standing(events)._1, SessionQuestions.open(events), SessionWaiters.present(directory), value.waitCommand)
      }
    }
  }
}
