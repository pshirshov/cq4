package cq.server

import cq.api.*
import cq.host.{SessionOwner, SessionUnits, SessionWait}
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class SessionWaitLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
  private def task(number: Long): ItemId = ItemId(project, Ledger.Tasks, number)
  private def attempt(members: Long*): SessionUnit = SessionUnit(SessionUnitKind.Attempt, UUID.randomUUID(), members.toList.map(task))
  private def key(unit: SessionUnit): (SessionUnitKind, UUID) = (unit.kind, unit.id)
  private def over(unit: SessionUnit, phase: String): UnitEnd = UnitEnd(unit, phase, Some("ConsiderAcceptance"), None)

  /** A session directory as a host leaves it, with the lock a live host holds. `steps` run one per pause of the waiter, in order;
    * a waiter that pauses more often than that would wait for ever, which fails the test instead. */
  private final class Session(steps: (Session => Unit)*) extends AutoCloseable {
    val directory: Path = Files.createTempDirectory("cq-wait-")
    Files.writeString(directory.resolve("run.json"), "{}")
    Files.createDirectories(directory.resolve("journal"))
    private val channel = FileChannel.open(directory.resolve("journal/owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private var lock = Option.empty[FileLock]
    val units = new SessionUnits(directory)
    private var remaining = steps.toList
    var pauses = 0
    def hostStarts(): Unit = lock = Some(channel.lock())
    def hostEnds(): Unit = { lock.foreach(_.release()); lock = None }
    def await(named: SessionUnit*): WaitOutcome = new SessionWait(directory, () => {
      pauses += 1
      remaining match {
        case step :: later => remaining = later; step(this)
        case Nil => fail("The waiter kept waiting after everything that could end its wait had happened")
      }
    }).await(named.toList.map(key))
    hostStarts()
    override def close(): Unit = { hostEnds(); channel.close() }
  }

  "cq wait over a session directory (Behavioral Active Blackbox; filesystem Communication)" should {
    "return at once, saying so, when the host works on nothing" in {
      val session = new Session()
      try {
        assert(session.await() == WaitOutcome.Idle() && session.pauses == 0)
        val done = attempt(1)
        session.units.started(done); session.units.ended(over(done, "Completed"))
        assert(session.await() == WaitOutcome.Idle() && session.pauses == 0)
      } finally session.close()
    }
    "wait for the next end among the units active when it starts, and name what is still active" in {
      val (first, second, later) = (attempt(1, 2), attempt(3), attempt(4))
      val session = new Session(_ => (), _.units.started(later), _.units.ended(over(later, "Completed")), _.units.ended(over(second, "Failed")))
      try {
        session.units.started(first); session.units.started(second)
        // A unit started after the waiter does not end its wait: the session starts a waiter of its own for it.
        assert(session.await() == WaitOutcome.Ended(List(over(second, "Failed")), List(first)) && session.pauses == 4)
        assert(SessionWait.lines(session.directory, session.await(second)) ==
          List(s"attempt ${second.id} on T3 ended: Failed, next ConsiderAcceptance", s"still active: attempt ${first.id} on T1,T2"))
      } finally session.close()
    }
    "wait for the first of the named units, return at once for one that has ended, and refuse one the session does not have" in {
      val (first, second) = (attempt(1), attempt(2))
      val session = new Session(_.units.ended(over(first, "Completed")))
      try {
        session.units.started(first); session.units.started(second)
        assert(session.await(second, first) == WaitOutcome.Ended(List(over(first, "Completed")), List(second)) && session.pauses == 1)
        assert(session.await(first) == WaitOutcome.Ended(List(over(first, "Completed")), List(second)) && session.pauses == 1)
        val unknown = intercept[IllegalArgumentException](session.await(attempt(9)))
        assert(unknown.getMessage.contains("is not a unit of the session"))
      } finally session.close()
    }
    "report each time the host stops working on an integration, and a blocker on one line" in {
      val integration = SessionUnit(SessionUnitKind.Integration, UUID.randomUUID(), Nil)
      val prepared = UnitEnd(integration.copy(members = List(task(5))), "Ready", Some("Confirm"), Some("Host check verify:\nFailed"))
      val applied = UnitEnd(prepared.unit, "Recorded", Some("Complete"), None)
      val session = new Session(_.units.ended(prepared), _.units.ended(applied))
      try {
        session.units.started(integration)
        assert(session.await() == WaitOutcome.Ended(List(prepared), Nil))
        assert(SessionWait.lines(session.directory, WaitOutcome.Ended(List(prepared), Nil)) ==
          List(s"integration ${integration.id} on T5 ended: Ready, next Confirm, blocker: Host check verify: Failed"))
        // Applying it is the host working on it again: the earlier end no longer answers a wait for it.
        session.units.started(prepared.unit)
        assert(session.await(integration) == WaitOutcome.Ended(List(applied), Nil) && session.pauses == 2)
      } finally session.close()
    }
    "end when the host is gone, reporting an end the host wrote last and otherwise what it left unfinished" in {
      val (first, second) = (attempt(1), attempt(2))
      val written = new Session(session => { session.units.ended(over(first, "Cancelled")); session.hostEnds() })
      try {
        written.units.started(first)
        assert(written.await() == WaitOutcome.Ended(List(over(first, "Cancelled")), Nil))
      } finally written.close()
      val lost = new Session(_ => (), _.hostEnds())
      try {
        lost.units.started(second)
        assert(SessionOwner.runs(lost.directory))
        val outcome = lost.await()
        assert(outcome == WaitOutcome.HostGone(List(second)) && lost.pauses == 2 && !SessionOwner.runs(lost.directory))
        val line :: Nil = SessionWait.lines(lost.directory, outcome): @unchecked
        assert(line.contains("is not running") && line.contains(s"attempt ${second.id} on T2") && line.contains("cq job upload --session " + lost.directory))
      } finally lost.close()
    }
    "not read an event whose line is still being written, and refuse a directory that is no session" in {
      val unit = attempt(1)
      val session = new Session(session => Files.writeString(session.directory.resolve(SessionUnits.File), "\n", StandardOpenOption.APPEND))
      try {
        session.units.started(unit)
        val line = SessionUnitEvent_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, SessionUnitEvent.Ended(over(unit, "Completed"))).noSpaces
        Files.writeString(session.directory.resolve(SessionUnits.File), line, StandardOpenOption.APPEND)
        assert(session.await() == WaitOutcome.Ended(List(over(unit, "Completed")), Nil) && session.pauses == 1)
      } finally session.close()
      val refused = intercept[SessionWait.NotASession](new SessionWait(Files.createTempDirectory("cq-no-session-"), () => ()))
      assert(refused.getMessage.contains("is not a CQ session directory"))
    }
  }
}
