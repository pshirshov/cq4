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
  * one host append to the same file, one at a time: an event is one whole line, or the file is cut back to what it held before. */
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
    }
  private def same(left: SessionUnit, right: SessionUnit): Boolean = left.kind == right.kind && left.id == right.id

  def reference(id: ItemId): String = LedgerPolicy.prefix(id.ledger) + id.number
  def described(unit: SessionUnit): String =
    s"${unit.kind.toString.toLowerCase} ${unit.id}" + (if (unit.members.isEmpty) "" else " on " + unit.members.map(reference).mkString(","))
  /** One line for a person or a model: what ended and how. */
  def described(end: UnitEnd): String = s"${described(end.unit)} ended: ${end.phase}" + end.next.fold("")(", next " + _) +
    end.blocker.fold("")(value => ", blocker: " + DispatchProjection.concise(value).replaceAll("\\s+", " "))
}

object SessionOwner {
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
    if (!Files.exists(directory.resolve(SessionWaiters.File), LinkOption.NOFOLLOW_LINKS)) awaited(named)
    else Using.resource(SessionWaiters.hold(directory))(_ => awaited(named))

  /** `named` are the units to wait for; when empty, the units the host works on at the first reading. */
  private def awaited(named: List[(SessionUnitKind, java.util.UUID)]): WaitOutcome = {
    // The event file only grows: it is read again when its size has changed.
    var seen = (-1L, SessionUnits.standing(Nil))
    def read = {
      val file = directory.resolve(SessionUnits.File)
      val size = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.size(file) else 0L
      if (size != seen._1) seen = (size, SessionUnits.standing(SessionUnits.read(directory)))
      seen._2
    }
    val (initial, known) = read
    named.find(key => !initial.exists(unit => (unit.kind, unit.id) == key) && !known.contains(key)).foreach { (kind, id) =>
      throw new IllegalArgumentException(s"${kind.toString.toLowerCase} $id is not a unit of the session in $directory")
    }
    val targets = if (named.nonEmpty) named else initial.map(unit => (unit.kind, unit.id))
    def outcome(active: List[SessionUnit], ended: Map[(SessionUnitKind, java.util.UUID), UnitEnd]): Option[WaitOutcome] = {
      val over = targets.flatMap(ended.get)
      Option.when(over.nonEmpty)(WaitOutcome.Ended(over, active))
    }
    // Nothing to wait for is said only of a host that runs: a host that is gone is reported as such, whatever it left.
    if (targets.isEmpty) (if (SessionOwner.runs(directory)) WaitOutcome.Idle() else WaitOutcome.HostGone(initial))
    else {
      var result = outcome(initial, known)
      while (result.isEmpty) {
        // The host is asked first: an end it wrote just before it exited is read after it and still reported.
        val alive = SessionOwner.runs(directory)
        val (active, ended) = read
        result = outcome(active, ended).orElse(Option.when(!alive)(WaitOutcome.HostGone(active)))
        if (result.isEmpty) pause()
      }
      result.get
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
    case WaitOutcome.Ended(ended, active) => ended.map(SessionUnits.described) ++ active.map(unit => "still active: " + SessionUnits.described(unit))
    case _: WaitOutcome.Idle => List("No child attempt, integration, combination or revalidation of this session is active")
    case WaitOutcome.HostGone(active) => List(s"The CQ host of the session in $directory is not running" +
      (if (active.isEmpty) "" else "; unfinished when it ended: " + active.map(SessionUnits.described).mkString("; ")) +
      ". Restart the harness session; retained deliveries are recovered with cq job upload --session " + directory)
  }
}

/** A `cq wait` holds a shared lock on `waiters.lock` of the session directory it waits on, for as long as it runs. Whoever must know
  * that the session will be told when its work ends asks whether an exclusive lock is refused: no process identity is guessed. */
object SessionWaiters {
  val File = "waiters.lock"
  /** The host creates the file with its session directory; a waiter only reads the directory. */
  def create(directory: Path): Unit = HostFiles.immutable(directory.resolve(File), "", 0)
  def hold(directory: Path): AutoCloseable = {
    val channel = FileChannel.open(directory.resolve(File), StandardOpenOption.READ)
    try { channel.lock(0L, Long.MaxValue, true); channel } catch { case error: Throwable => channel.close(); throw error }
  }
  def present(directory: Path): Boolean = Using.resource(FileChannel.open(directory.resolve(File), StandardOpenOption.WRITE)) { channel =>
    (try Option(channel.tryLock()) catch { case _: java.nio.channels.OverlappingFileLockException => None }).fold(true) { held => held.release(); false }
  }
}

/** How the attached host of a session stands, as a process of its checkout finds it. */
sealed trait HostView
object HostView {
  /** No host of this checkout recorded the session: its host ended in order, or it is a host of a package before the record existed. */
  case object Unrecorded extends HostView
  /** The host recorded the session and no longer holds its lock. */
  case object Gone extends HostView
  /** `standing` are the units the host works on; `waited` says that a `cq wait` runs on the session. */
  final case class Running(directory: Path, standing: List[SessionUnit], waited: Boolean, waitCommand: Option[String]) extends HostView
}

/** Where the attached hosts of one checkout say which session directory each maintains (`hosts/` of the checkout's CQ directory).
  * A hook of that checkout knows a drive's attached session only by its identity, and a `cq wait` without a directory knows only its
  * checkout: both ask here. */
trait SessionViews {
  def view(session: SessionId): HostView
  /** The standing units the session's last stop was answered for with the order to start its waiter; none when it was answered otherwise. */
  def asked(session: SessionId): Option[String]
  def ask(session: SessionId, units: Option[String]): Unit
}

final class AttachedSessions(configuration: Path) extends SessionViews {
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
  def forget(session: SessionId): Unit = { Files.deleteIfExists(file(session)); Files.deleteIfExists(prompt(session)); () }
  private def prompt(session: SessionId): Path = root.resolve(session.value.toString + ".asked")
  override def asked(session: SessionId): Option[String] = Option.when(Files.isRegularFile(prompt(session), LinkOption.NOFOLLOW_LINKS))(HostFiles.text(prompt(session), MaxBytes))
  override def ask(session: SessionId, units: Option[String]): Unit = units match {
    case Some(value) => HostFiles.directory(root); Files.writeString(prompt(session), value)
    case None => Files.deleteIfExists(prompt(session)); ()
  }
  private def recorded: List[(Path, AttachedHostRecord)] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
    else Using.resource(Files.list(root))(_.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).toList).sortBy(_.getFileName.toString).map(path => path -> read(path))
  /** The session directories of the hosts of this checkout that run. */
  def running: List[AttachedHostRecord] = recorded.map(_._2).filter(value => SessionOwner.runs(Path.of(value.directory)))
  override def view(session: SessionId): HostView = {
    val path = file(session)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) HostView.Unrecorded
    else {
      val value = read(path)
      val directory = Path.of(value.directory)
      if (!SessionOwner.runs(directory)) HostView.Gone
      else HostView.Running(directory, SessionUnits.standing(SessionUnits.read(directory))._1, SessionWaiters.present(directory), value.waitCommand)
    }
  }
}
