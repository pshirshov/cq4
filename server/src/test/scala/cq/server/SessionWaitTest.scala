package cq.server

import cq.api.*
import cq.host.{AttachedSessions, HostView, SessionOwner, SessionAwaited, SessionUnits, SessionWait, SessionWaiters}
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class SessionWaitLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
  private def task(number: Long): ItemId = ItemId(project, Ledger.Tasks, number)
  private def attempt(members: Long*): SessionUnit = SessionUnit(SessionUnitKind.Attempt, UUID.randomUUID(), members.toList.map(task))
  private def key(unit: SessionUnit): (SessionUnitKind, UUID) = (unit.kind, unit.id)
  private val Slot = 7L
  private def over(unit: SessionUnit, phase: String): UnitEnd = UnitEnd(unit, phase, Some("ConsiderAcceptance"), None)
  private def question(number: Long): ItemId = ItemId(project, Ledger.Questions, number)
  private def answered(number: Long, answer: String): AwaitedEnd = AwaitedEnd(question(number), s"Question $number", "Answered", Some(answer))

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
    def await(named: SessionUnit*): WaitOutcome = awaitAs(Slot, SessionWait.After.Start, named*)
    def awaitAs(slot: Long, after: SessionWait.After, named: SessionUnit*): WaitOutcome = new SessionWait(directory, () => {
      pauses += 1
      remaining match {
        case step :: later => remaining = later; step(this)
        case Nil => fail("The waiter kept waiting after everything that could end its wait had happened")
      }
    }, slot).await(named.toList.map(key), after)
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
        assert(session.await() == WaitOutcome.Ended(List(over(second, "Failed")), List(first), Nil) && session.pauses == 4)
        assert(SessionWait.lines(session.directory, session.await(second)) ==
          List(s"attempt ${second.id} on T3 ended: Failed, next ConsiderAcceptance", s"still active: attempt ${first.id} on T1,T2"))
      } finally session.close()
    }
    "wait for the first of the named units, return at once for one that has ended, and refuse one the session does not have" in {
      val (first, second) = (attempt(1), attempt(2))
      val session = new Session(_.units.ended(over(first, "Completed")))
      try {
        session.units.started(first); session.units.started(second)
        assert(session.await(second, first) == WaitOutcome.Ended(List(over(first, "Completed")), List(second), Nil) && session.pauses == 1)
        assert(session.await(first) == WaitOutcome.Ended(List(over(first, "Completed")), List(second), Nil) && session.pauses == 1)
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
        assert(session.await() == WaitOutcome.Ended(List(prepared), Nil, Nil))
        assert(SessionWait.lines(session.directory, WaitOutcome.Ended(List(prepared), Nil, Nil)) ==
          List(s"integration ${integration.id} on T5 ended: Ready, next Confirm, blocker: Host check verify: Failed"))
        // Applying it is the host working on it again: the earlier end no longer answers a wait for it.
        session.units.started(prepared.unit)
        assert(session.await(integration) == WaitOutcome.Ended(List(applied), Nil, Nil) && session.pauses == 2)
      } finally session.close()
    }
    "end when the host is gone, reporting an end the host wrote last and otherwise what it left unfinished" in {
      val (first, second) = (attempt(1), attempt(2))
      val written = new Session(session => { session.units.ended(over(first, "Cancelled")); session.hostEnds() })
      try {
        written.units.started(first)
        assert(written.await() == WaitOutcome.Ended(List(over(first, "Cancelled")), Nil, Nil))
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
    "say that the host is gone, not that nothing is active, when a host that left nothing unfinished no longer runs" in {
      val session = new Session()
      try {
        val done = attempt(1)
        session.units.started(done); session.units.ended(over(done, "Completed"))
        session.hostEnds()
        assert(session.await() == WaitOutcome.HostGone(Nil) && session.pauses == 0)
      } finally session.close()
    }
    "read the events before an append that so far ends inside a character" in {
      val unit = attempt(1)
      val session = new Session()
      try {
        session.units.started(unit); session.units.ended(over(unit, "Completed"))
        val next = SessionUnitEvent_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default,
          SessionUnitEvent.Ended(UnitEnd(unit, "Failed", None, Some("Prüfung")))).noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val cut = next.indexWhere(_ < 0) + 1
        assert(cut > 0 && next(cut) < 0, "The fragment must end inside a multi-byte character")
        Files.write(session.directory.resolve(SessionUnits.File), next.take(cut), StandardOpenOption.APPEND)
        assert(SessionUnits.read(session.directory) == List(SessionUnitEvent.Started(unit), SessionUnitEvent.Ended(over(unit, "Completed"))))
        assert(session.await(unit) == WaitOutcome.Ended(List(over(unit, "Completed")), Nil, Nil))
      } finally session.close()
    }
    "D164: wait while the session waits on a person, report the end the host gave to this waiter and the ones the session has not read, and none it has read" in {
      val never = () => false
      // Nothing runs, and the session waits on Q1: the waiter stays until Q1 is settled, and the host, which finds it running, gives it the end.
      val session = new Session(_ => (), _.units.settled(answered(1, "Yes"), never))
      try {
        SessionWaiters.create(session.directory)
        session.units.watching(question(1))
        val outcome = session.await()
        assert(outcome == WaitOutcome.Ended(Nil, Nil, List(answered(1, "Yes"))) && session.pauses == 2, outcome.toString)
        assert(SessionWait.lines(session.directory, outcome) == List("question Q1 \"Question 1\" answered: \"Yes\""))
        assert(SessionUnits.read(session.directory).last == SessionUnitEvent.Settled(answered(1, "Yes"), Some(Slot)))
        // An end written while no waiter ran is given to none. A waiter that starts later reports it and the one a waiter was given,
        // since the session has read neither: the report of a waiter that was killed is not lost. One that is asked for what is
        // settled from some event on, or from now on, leaves out what was written before.
        session.units.watching(question(2))
        session.units.settled(answered(2, "No"), never)
        val events = SessionUnits.read(session.directory)
        assert(events.last == SessionUnitEvent.Settled(answered(2, "No"), None))
        assert(session.await() == WaitOutcome.Ended(Nil, Nil, List(answered(1, "Yes"), answered(2, "No"))) && session.pauses == 2)
        assert(session.awaitAs(Slot, SessionWait.After.Events(events.size - 1)) == WaitOutcome.Ended(Nil, Nil, List(answered(2, "No"))))
        assert(session.awaitAs(Slot, SessionWait.After.Now) == WaitOutcome.Idle() && session.pauses == 2)
        // What the session has read is reported by nothing.
        session.units.released(question(1)); session.units.released(question(2))
        assert(session.await() == WaitOutcome.Idle() && SessionAwaited.unread(SessionUnits.read(session.directory), 0, events.size + 2).isEmpty)
      } finally session.close()
      // A unit that ends and an item that is settled meanwhile are reported together, and the session reading what a waiter was
      // about to leave for keeps the waiter: its reason is gone.
      val unit = attempt(1)
      val both = new Session(_.units.settled(answered(3, "Both"), never), _.units.ended(over(unit, "Completed")), _.units.released(question(4)))
      try {
        SessionWaiters.create(both.directory)
        both.units.started(unit); both.units.watching(question(3)); both.units.watching(question(4))
        assert(both.awaitAs(Slot, SessionWait.After.Now, unit) == WaitOutcome.Ended(Nil, List(unit), List(answered(3, "Both"))) && both.pauses == 1)
        both.units.released(question(3))
        assert(both.await(unit) == WaitOutcome.Ended(List(over(unit, "Completed")), Nil, Nil) && both.pauses == 2)
        assert(both.await() == WaitOutcome.Idle() && both.pauses == 3)
      } finally both.close()
      // The end is given to the waiter of the lowest slot that runs, and to no other: a second waiter stays for what it waits for.
      val shared = new Session()
      try {
        SessionWaiters.create(shared.directory)
        shared.units.watching(question(5))
        val (low, _) = SessionWaiters.hold(shared.directory, 3L)(())
        val (high, _) = SessionWaiters.hold(shared.directory, 900000L)(())
        try {
          shared.units.settled(answered(5, "One"), never)
          val written = SessionUnits.read(shared.directory)
          assert(written.last == SessionUnitEvent.Settled(answered(5, "One"), Some(3L)))
          assert(SessionAwaited.handed(written, 1, 3L) == List(answered(5, "One")) && SessionAwaited.handed(written, 1, 900000L).isEmpty)
          // Asking whether a waiter runs takes the turn the host decides in: a look never passes for a waiter.
          assert(SessionWaiters.present(shared.directory))
        } finally { low.close(); high.close() }
        assert(!SessionWaiters.present(shared.directory))
        shared.units.watching(question(6))
        shared.units.settled(answered(6, "None"), never)
        assert(SessionUnits.read(shared.directory).last == SessionUnitEvent.Settled(answered(6, "None"), None))
      } finally shared.close()
    }
    "tell a process of the checkout how an attached session's host stands: unrecorded, gone, or running with its standing units and its waiter" in {
      val configuration = Files.createTempDirectory("cq-checkout-")
      val sessions = new AttachedSessions(configuration)
      val (id, other) = (SessionId(UUID.randomUUID()), SessionId(UUID.randomUUID()))
      val session = new Session()
      val killed = new Session()
      try {
        assert(sessions.view(id) == HostView.Unrecorded && sessions.running.isEmpty)
        SessionWaiters.create(session.directory)
        sessions.record(other, AttachedHostRecord(killed.directory.toString, None))
        killed.hostEnds()
        assert(sessions.view(other) == HostView.Gone)
        // A starting host withdraws the records of hosts that were killed, and leaves its own.
        sessions.record(id, AttachedHostRecord(session.directory.toString, Some("/opt/cq/bin/cq wait")))
        assert(sessions.view(other) == HostView.Unrecorded && sessions.running == List(AttachedHostRecord(session.directory.toString, Some("/opt/cq/bin/cq wait"))))
        val unit = attempt(1)
        session.units.started(unit)
        assert(sessions.view(id) == HostView.Running(session.directory, List(unit), Nil, false, Some("/opt/cq/bin/cq wait")))
        // A `cq wait` on the session is seen for as long as it runs, by its lock and without knowing its process.
        val waiter = SessionWaiters.hold(session.directory, Slot)(())._1
        try assert(sessions.view(id) == HostView.Running(session.directory, List(unit), Nil, true, Some("/opt/cq/bin/cq wait")))
        finally waiter.close()
        session.units.ended(over(unit, "Completed"))
        assert(sessions.view(id) == HostView.Running(session.directory, Nil, Nil, false, Some("/opt/cq/bin/cq wait")))
        session.hostEnds()
        assert(sessions.view(id) == HostView.Gone && sessions.running.isEmpty)
        sessions.forget(id)
        assert(sessions.view(id) == HostView.Unrecorded)
      } finally { session.close(); killed.close() }
    }
    "not read an event whose line is still being written, and refuse a directory that is no session" in {
      val unit = attempt(1)
      val session = new Session(session => Files.writeString(session.directory.resolve(SessionUnits.File), "\n", StandardOpenOption.APPEND))
      try {
        session.units.started(unit)
        val line = SessionUnitEvent_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, SessionUnitEvent.Ended(over(unit, "Completed"))).noSpaces
        Files.writeString(session.directory.resolve(SessionUnits.File), line, StandardOpenOption.APPEND)
        assert(session.await() == WaitOutcome.Ended(List(over(unit, "Completed")), Nil, Nil) && session.pauses == 1)
      } finally session.close()
      val refused = intercept[SessionWait.NotASession](new SessionWait(Files.createTempDirectory("cq-no-session-"), () => (), Slot))
      assert(refused.getMessage.contains("is not a CQ session directory"))
    }
  }
}
