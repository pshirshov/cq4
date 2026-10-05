package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.LedgerPolicy
import io.circe.parser
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
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

  /** `named` are the units to wait for; when empty, the units the host works on at the first reading. */
  def await(named: List[(SessionUnitKind, java.util.UUID)]): WaitOutcome = {
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
  def lines(directory: Path, outcome: WaitOutcome): List[String] = outcome match {
    case WaitOutcome.Ended(ended, active) => ended.map(SessionUnits.described) ++ active.map(unit => "still active: " + SessionUnits.described(unit))
    case _: WaitOutcome.Idle => List("No child attempt, integration, combination or revalidation of this session is active")
    case WaitOutcome.HostGone(active) => List(s"The CQ host of the session in $directory is not running" +
      (if (active.isEmpty) "" else "; unfinished when it ended: " + active.map(SessionUnits.described).mkString("; ")) +
      ". Restart the harness session; retained deliveries are recovered with cq job upload --session " + directory)
  }
}

/** Where the attached hosts of one checkout say which session directory each maintains. A hook of that checkout, which knows a drive's
  * attached session only by its identity, asks here whether the session's host still runs. */
object AttachedHosts {
  private val MaxBytes = 8192
  private def file(configuration: Path, session: SessionId): Path = configuration.resolve("hosts").resolve(session.value.toString)
  def record(configuration: Path, session: SessionId, directory: Path): Unit = {
    HostFiles.directory(configuration.resolve("hosts"))
    HostFiles.immutable(file(configuration, session), directory.toString, MaxBytes)
  }
  /** A host that ends in order withdraws its record; one that is killed leaves it, and its released lock says the same. */
  def forget(configuration: Path, session: SessionId): Unit = { Files.deleteIfExists(file(configuration, session)); () }
  def runs(configuration: Path, session: SessionId): Boolean = {
    val record = file(configuration, session)
    Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS) && SessionOwner.runs(Path.of(HostFiles.text(record, MaxBytes)))
  }
}
