package cq.server

import cq.api.*
import cq.host.{DeliveredSeat, DispatchProjection, FailedSeat, ReviewAggregate, ReviewSeat, SeatCandidate, SeatRotation, UnitEvent, UnitOutcome, UnitProgress, UnitStep}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.scalatest.wordspec.AnyWordSpec

final class UnitProgressLocal extends AnyWordSpec {
  private val request = RequestId(UUID.randomUUID())
  private val project = ProjectId(UUID.randomUUID())
  private val origin = RoleOrigin(AgentLayer.Project, RoleSource.DefaultRoles)
  private def route(seat: Int, candidate: Int): ModelRoute = ModelRoute(Harness.Pi, Some(s"provider$seat"), s"model$candidate", None)
  private def seat(index: Int, strategy: SeatStrategy, candidates: Int): ResolvedSeat =
    ResolvedSeat(strategy, (0 until candidates).toList.map(route(index, _)))
  private def role(mode: PanelMode, min: Int, seats: (SeatStrategy, Int)*): ResolvedRole =
    ResolvedRole(mode, min, seats.toList.zipWithIndex.map { case ((strategy, candidates), index) => seat(index, strategy, candidates) }, origin, Nil)
  private def single(strategy: SeatStrategy, candidates: Int): ResolvedRole = role(PanelMode.All, 1, strategy -> candidates)
  private val Fallback = SeatStrategy.Fallback
  private val First = SeatStrategy.First
  private val Rr = SeatStrategy.RoundRobin
  private def attempt(seat: Int, candidate: Int): AttemptId = AttemptId(new UUID(seat.toLong, candidate.toLong))
  private def result(seat: Int): ArtifactId = ArtifactId(new UUID(7L, seat.toLong))
  private def launch(candidates: (Int, Int)*): UnitStep = UnitStep.Launch(candidates.toList.map(SeatCandidate(_, _)))
  private def ended(outcome: UnitOutcome): UnitStep = UnitStep.Ended(outcome)
  private def delivered(seats: Int*)(using play: Play): List[DeliveredSeat] =
    seats.toList.map(seat => DeliveredSeat(seat, play.last(seat), result(seat), ChildNext.ConsiderAcceptance))

  /** One unit under test. An end reports the candidate's attempt first when the test has not, as the unit layer does at launch. */
  private final class Play(val role: ResolvedRole, start: Int => Int) {
    var drawn = List.empty[Int]
    private val begun = UnitProgress.begin(request, role, seat => { drawn :+= seat; start(seat) })
    var state: UnitProgress = begun._1
    val first: UnitStep = begun._2
    private var known = Set.empty[SeatCandidate]
    private var latest = Map.empty[Int, AttemptId]
    // What this test told the unit, by seat and in order: its expectations are built from it, not from the state under test.
    private var faults = Map.empty[Int, Int]
    private var refusals = Vector.empty[(Int, Int, AbstentionReason)]
    def last(seat: Int): AttemptId = latest(seat)
    private def send(event: UnitEvent): UnitStep = {
      val (next, step) = state(event)
      state = next
      step
    }
    def started(seat: Int, candidate: Int): UnitStep = {
      known += SeatCandidate(seat, candidate)
      latest += seat -> attempt(seat, candidate)
      send(UnitEvent.Started(SeatCandidate(seat, candidate), attempt(seat, candidate)))
    }
    private def end(seat: Int, candidate: Int, event: UnitEvent): UnitStep = {
      if (!known(SeatCandidate(seat, candidate))) assert(started(seat, candidate) == UnitStep.Wait)
      send(event)
    }
    def deliver(seat: Int, candidate: Int): UnitStep = end(seat, candidate, UnitEvent.Delivered(SeatCandidate(seat, candidate), result(seat), ChildNext.ConsiderAcceptance))
    def abstain(seat: Int, candidate: Int, reason: AbstentionReason): UnitStep = {
      refusals :+= (seat, candidate, reason)
      end(seat, candidate, UnitEvent.Abstained(SeatCandidate(seat, candidate), reason, s"detail $seat.$candidate"))
    }
    def abstain(seat: Int, candidate: Int): UnitStep = abstain(seat, candidate, AbstentionReason.Quota)
    def fail(seat: Int, candidate: Int): UnitStep = {
      faults += seat -> candidate
      end(seat, candidate, UnitEvent.Failed(SeatCandidate(seat, candidate), s"fault $seat.$candidate"))
    }
    def cancelled(seat: Int, candidate: Int): UnitStep = send(UnitEvent.Cancelled(SeatCandidate(seat, candidate)))
    def cancel(): UnitStep = {
      val (next, step) = state.cancel
      state = next
      step
    }
    def raw(event: UnitEvent): UnitStep = send(event)
    def ends: List[SeatEnd] = state.snapshot.seats.map(_.end)
    def tried(seat: Int): List[(Int, Option[AbstentionReason])] = state.snapshot.seats(seat).attempts.map(value =>
      role.seats(seat).candidates.indexOf(value.route) -> value.abstained)
    def failed(seats: Int*): List[FailedSeat] = seats.toList.map(seat => FailedSeat(seat, attempt(seat, faults(seat)), s"fault $seat.${faults(seat)}"))
    def abstentions(seats: Int*): List[SeatAttempt] = seats.toList.flatMap(seat => refusals.toList.collect {
      case (`seat`, candidate, reason) => SeatAttempt(attempt(seat, candidate), route(seat, candidate), Some(reason), Some(s"detail $seat.$candidate"))
    })
  }
  private def play(role: ResolvedRole): Play = new Play(role, _ => 0)
  private val Pending = SeatEnd.Pending()
  private val Abstained = SeatEnd.Abstained()
  private val Cancelled = SeatEnd.Cancelled()
  private def Delivered(seat: Int): SeatEnd = SeatEnd.Delivered(result(seat), ChildNext.ConsiderAcceptance)
  private def Failed(seat: Int, candidate: Int): SeatEnd = SeatEnd.Failed(s"fault $seat.$candidate")
  private val quota = Some(AbstentionReason.Quota)

  "A unit with one seat (Behavioral Active Blackbox Atomic)" should {
    "end as a single attempt does today: a result decides it, a failure fails it with the fault, a cancellation cancels it" in {
      given delivering: Play = play(single(Fallback, 1))
      assert(delivering.first == launch(0 -> 0) && delivering.state.outcome.isEmpty && delivering.ends == List(Pending))
      assert(delivering.started(0, 0) == UnitStep.Wait && delivering.tried(0) == List(0 -> None))
      assert(delivering.deliver(0, 0) == ended(UnitOutcome.Decided(delivered(0), Nil)))
      assert(delivering.state.snapshot == UnitSeats(request, PanelMode.All, 1, List(SeatStatus(0, List(SeatAttempt(attempt(0, 0), route(0, 0), None, None)), Delivered(0)))))
      val failing = play(single(Fallback, 2))
      assert(failing.fail(0, 0) == ended(UnitOutcome.Failed(failing.failed(0), Nil)) && failing.ends == List(Failed(0, 0)) && failing.tried(0) == List(0 -> None))
      val cancelling = play(single(Fallback, 2))
      assert(cancelling.cancelled(0, 0) == ended(UnitOutcome.Cancelled) && cancelling.ends == List(Cancelled) && cancelling.tried(0) == Nil)
    }
    "I30: run the governing session's own work as one seat with one candidate that no agent configuration resolved" in {
      val own = ModelRoute(Harness.Claude, Some("unobserved-interactive-provider"), "unobserved-interactive-model", None)
      val at = SeatCandidate(0, 0)
      def begun: UnitProgress = {
        val (state, step) = UnitProgress.single(request, own)
        assert(step == launch(0 -> 0) && state.outcome.isEmpty)
        state(UnitEvent.Started(at, attempt(0, 0)))._1
      }
      val (made, end) = begun(UnitEvent.Delivered(at, result(0), ChildNext.Review))
      assert(end == ended(UnitOutcome.Decided(List(DeliveredSeat(0, attempt(0, 0), result(0), ChildNext.Review)), Nil)))
      assert(made.snapshot == UnitSeats(request, PanelMode.All, 1, List(SeatStatus(0, List(SeatAttempt(attempt(0, 0), own, None, None)), SeatEnd.Delivered(result(0), ChildNext.Review)))))
      // A capture the host refused fails the unit, and a withdrawn workspace cancels it: neither is an abstention, and nothing follows.
      assert(begun(UnitEvent.Failed(at, "the session committed"))._2 == ended(UnitOutcome.Failed(List(FailedSeat(0, attempt(0, 0), "the session committed")), Nil)))
      val (stopping, waiting) = begun.cancel
      assert(waiting == UnitStep.Wait && stopping(UnitEvent.Cancelled(at))._2 == ended(UnitOutcome.Cancelled))
    }
    "go to the next candidate only when one abstains, and deliver with the candidate that ran" in {
      given unit: Play = play(single(Fallback, 3))
      assert(unit.abstain(0, 0) == launch(0 -> 1) && unit.ends == List(Pending) && unit.state.outcome.isEmpty)
      assert(unit.abstain(0, 1, AbstentionReason.Launch) == launch(0 -> 2))
      assert(unit.deliver(0, 2) == ended(UnitOutcome.Decided(delivered(0), Nil)))
      assert(unit.tried(0) == List(0 -> quota, 1 -> Some(AbstentionReason.Launch), 2 -> None))
      assert(unit.state.snapshot.seats.head.attempts.map(_.detail) == List(Some("detail 0.0"), Some("detail 0.1"), None))
      // A failure of a later candidate ends the seat Failed: the candidates after it are not tried.
      val failing = play(single(Fallback, 3))
      assert(failing.abstain(0, 0) == launch(0 -> 1) && failing.fail(0, 1) == ended(UnitOutcome.Failed(failing.failed(0), Nil)))
      assert(failing.ends == List(Failed(0, 1)) && failing.tried(0) == List(0 -> quota, 1 -> None))
    }
    "abstain, naming every candidate and reason, when its strategy is exhausted" in {
      val unit = play(single(Fallback, 2))
      assert(unit.abstain(0, 0, AbstentionReason.Credential) == launch(0 -> 1))
      val reasons = unit.abstain(0, 1, AbstentionReason.Unavailable)
      val named = List(SeatAttempt(attempt(0, 0), route(0, 0), Some(AbstentionReason.Credential), Some("detail 0.0")),
        SeatAttempt(attempt(0, 1), route(0, 1), Some(AbstentionReason.Unavailable), Some("detail 0.1")))
      assert(reasons == ended(UnitOutcome.Abstained(named, Nil)) && unit.ends == List(Abstained))
      assert(UnitProgress.abstention(named) ==
        "No configured model could run this work: pi:provider0/model0 Credential (detail 0.0); pi:provider0/model1 Unavailable (detail 0.1)")
    }
    "try candidate 0 only under `first`" in {
      val abstaining = play(single(First, 3))
      assert(abstaining.first == launch(0 -> 0) && abstaining.abstain(0, 0) == ended(UnitOutcome.Abstained(abstaining.abstentions(0), Nil)))
      assert(abstaining.ends == List(Abstained) && abstaining.tried(0) == List(0 -> quota))
      given delivering: Play = play(single(First, 3))
      assert(delivering.deliver(0, 0) == ended(UnitOutcome.Decided(delivered(0), Nil)))
    }
    "rotate the fallback order of `rr` by the supplied start, wrap, and abstain after one full turn" in {
      for (start <- 0 until 3) {
        val unit = new Play(single(Rr, 3), _ => start)
        val order = List(start, (start + 1) % 3, (start + 2) % 3)
        assert(unit.first == launch(0 -> order.head), s"start $start")
        assert(unit.abstain(0, order(0)) == launch(0 -> order(1)) && unit.abstain(0, order(1)) == launch(0 -> order(2)), s"start $start")
        assert(unit.abstain(0, order(2)) == ended(UnitOutcome.Abstained(unit.abstentions(0), Nil)), s"start $start")
        assert(unit.tried(0) == order.map(_ -> quota) && unit.drawn == List(0), s"start $start")
      }
      given unit: Play = new Play(single(Rr, 3), _ => 2)
      assert(unit.abstain(0, 2) == launch(0 -> 0) && unit.deliver(0, 0) == ended(UnitOutcome.Decided(delivered(0), Nil)))
      // A failure ends an rr seat as it ends a fallback seat.
      val failing = new Play(single(Rr, 3), _ => 1)
      assert(failing.fail(0, 1) == ended(UnitOutcome.Failed(failing.failed(0), Nil)))
      assert(intercept[IllegalArgumentException](new Play(single(Rr, 3), _ => 3)).getMessage.contains("Round-robin start 3"))
      // Only an rr seat draws a start.
      assert(play(single(Fallback, 3)).drawn == Nil && play(single(First, 3)).drawn == Nil)
    }
  }

  "An `all` panel (Behavioral Active Blackbox Atomic)" should {
    "start every seat at once and end when each has ended, deciding with min delivered although seats abstained" in {
      given unit: Play = play(role(PanelMode.All, 2, Fallback -> 1, Fallback -> 2, First -> 2))
      assert(unit.first == launch(0 -> 0, 1 -> 0, 2 -> 0))
      assert(unit.deliver(2, 0) == UnitStep.Wait && unit.abstain(1, 0) == launch(1 -> 1) && unit.abstain(0, 0) == UnitStep.Wait)
      assert(unit.state.outcome.isEmpty && unit.ends == List(Abstained, Pending, Delivered(2)))
      // Delivered seats are listed in the order they delivered.
      assert(unit.deliver(1, 1) == ended(UnitOutcome.Decided(delivered(2, 1), Nil)))
      assert(unit.ends == List(Abstained, Delivered(1), Delivered(2)))
    }
    "wait for the seats still in flight after min is met" in {
      given unit: Play = play(role(PanelMode.All, 1, Fallback -> 1, Fallback -> 1))
      assert(unit.deliver(0, 0) == UnitStep.Wait && unit.state.outcome.isEmpty)
      assert(unit.deliver(1, 0) == ended(UnitOutcome.Decided(delivered(0, 1), Nil)))
    }
    "abstain when abstentions alone leave it below min, naming the candidates of the seats that abstained" in {
      val unit = play(role(PanelMode.All, 2, Fallback -> 2, Fallback -> 2, First -> 2))
      assert(unit.abstain(0, 0) == launch(0 -> 1) && unit.deliver(0, 1) == UnitStep.Wait && unit.abstain(2, 0) == UnitStep.Wait)
      assert(unit.abstain(1, 0) == launch(1 -> 1))
      val last = unit.abstain(1, 1, AbstentionReason.RateLimit)
      // Seat 0 delivered after its first candidate abstained: that abstention is not why the unit has no result.
      val kept = List(DeliveredSeat(0, attempt(0, 1), result(0), ChildNext.ConsiderAcceptance))
      assert(last == ended(UnitOutcome.Abstained(unit.abstentions(1, 2), kept)))
      assert(unit.abstentions(1, 2).map(value => value.attempt -> value.abstained) ==
        List(attempt(1, 0) -> quota, attempt(1, 1) -> Some(AbstentionReason.RateLimit), attempt(2, 0) -> quota))
    }
    "tolerate a failed seat while min is met, listing it, and fail with the first failed seat's fault when min is not met" in {
      given met: Play = play(role(PanelMode.All, 2, Fallback -> 2, Fallback -> 1, Fallback -> 1))
      assert(met.fail(1, 0) == UnitStep.Wait && met.deliver(2, 0) == UnitStep.Wait)
      assert(met.deliver(0, 0) == ended(UnitOutcome.Decided(delivered(2, 0), met.failed(1))) && met.ends == List(Delivered(0), Failed(1, 0), Delivered(2)))
      val unmet = play(role(PanelMode.All, 2, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(unmet.fail(2, 0) == UnitStep.Wait && unmet.abstain(0, 0) == UnitStep.Wait)
      // Seat 2 failed before seat 1: its fault is the unit's.
      val end = unmet.fail(1, 0)
      assert(end == ended(UnitOutcome.Failed(unmet.failed(2, 1), Nil)))
      assert(end match { case UnitStep.Ended(UnitOutcome.Failed(first :: _, _)) => first.fault == "fault 2.0" && first.attempt == attempt(2, 0); case _ => false })
      // A failure outranks abstentions below min, and the seat that delivered is kept with the outcome.
      val both = play(role(PanelMode.All, 3, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(both.abstain(0, 0) == UnitStep.Wait && both.deliver(1, 0) == UnitStep.Wait)
      assert(both.fail(2, 0) == ended(UnitOutcome.Failed(both.failed(2), List(DeliveredSeat(1, attempt(1, 0), result(1), ChildNext.ConsiderAcceptance)))))
    }
  }

  "An `any` panel (Behavioral Active Blackbox Atomic)" should {
    "start only the seats min needs, in listed order, and never a later seat once min verdicts are in" in {
      given one: Play = play(role(PanelMode.Any, 1, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(one.first == launch(0 -> 0) && one.deliver(0, 0) == ended(UnitOutcome.Decided(delivered(0), Nil)))
      assert(one.ends == List(Delivered(0), Pending, Pending) && one.tried(1) == Nil && one.tried(2) == Nil)
      val two = play(role(PanelMode.Any, 2, Fallback -> 1, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(two.first == launch(0 -> 0, 1 -> 0))
      assert(two.deliver(1, 0) == UnitStep.Wait && two.ends == List(Pending, Delivered(1), Pending, Pending))
      assert(two.deliver(0, 0).isInstanceOf[UnitStep.Ended] && two.ends == List(Delivered(0), Delivered(1), Pending, Pending))
    }
    "start the next listed seat when one abstains or fails, after the seat's own candidates" in {
      given unit: Play = play(role(PanelMode.Any, 2, Fallback -> 2, Fallback -> 1, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(unit.first == launch(0 -> 0, 1 -> 0))
      // The seat's own next candidate comes before another seat.
      assert(unit.abstain(0, 0) == launch(0 -> 1) && unit.abstain(0, 1) == launch(2 -> 0))
      assert(unit.fail(1, 0) == launch(3 -> 0))
      assert(unit.deliver(3, 0) == UnitStep.Wait && unit.deliver(2, 0) == ended(UnitOutcome.Decided(delivered(3, 2), unit.failed(1))))
      assert(unit.ends == List(Abstained, Failed(1, 0), Delivered(2), Delivered(3), Pending) && unit.tried(4) == Nil)
    }
    "end below min when its seats are used up: failed when one failed, abstained when all that did not deliver abstained" in {
      val failed = play(role(PanelMode.Any, 2, Fallback -> 1, Fallback -> 1, Fallback -> 1))
      assert(failed.abstain(0, 0) == launch(2 -> 0) && failed.fail(2, 0) == UnitStep.Wait)
      assert(failed.deliver(1, 0) == ended(UnitOutcome.Failed(failed.failed(2), List(DeliveredSeat(1, attempt(1, 0), result(1), ChildNext.ConsiderAcceptance)))))
      val abstained = play(role(PanelMode.Any, 1, First -> 2, Fallback -> 2))
      assert(abstained.abstain(0, 0) == launch(1 -> 0) && abstained.abstain(1, 0) == launch(1 -> 1))
      assert(abstained.abstain(1, 1) == ended(UnitOutcome.Abstained(abstained.abstentions(0, 1), Nil)))
    }
    "draw the start of an rr seat when the seat is started, and none for a seat it never starts" in {
      val idle = new Play(role(PanelMode.Any, 1, Fallback -> 1, Rr -> 3), _ => 1)
      assert(idle.drawn == Nil && idle.deliver(0, 0).isInstanceOf[UnitStep.Ended] && idle.drawn == Nil)
      val used = new Play(role(PanelMode.Any, 1, Fallback -> 1, Rr -> 3), _ => 1)
      assert(used.abstain(0, 0) == launch(1 -> 1) && used.drawn == List(1))
      assert(used.abstain(1, 1) == launch(1 -> 2) && used.abstain(1, 2) == launch(1 -> 0) && used.drawn == List(1))
      val every = new Play(role(PanelMode.All, 1, Rr -> 2, Fallback -> 1, Rr -> 2), seat => seat / 2)
      assert(every.first == launch(0 -> 0, 1 -> 0, 2 -> 1) && every.drawn == List(0, 2))
    }
  }

  "A cancelled unit (Behavioral Active Blackbox Atomic)" should {
    "never count a cancellation as an abstention, nor go on to another candidate or seat" in {
      val seat = play(single(Fallback, 3))
      assert(seat.abstain(0, 0) == launch(0 -> 1) && seat.cancelled(0, 1) == ended(UnitOutcome.Cancelled))
      assert(seat.ends == List(Cancelled) && seat.tried(0) == List(0 -> quota))
      val panel = play(role(PanelMode.Any, 1, Fallback -> 2, Fallback -> 1))
      assert(panel.started(0, 0) == UnitStep.Wait && panel.cancelled(0, 0) == ended(UnitOutcome.Cancelled))
      assert(panel.ends == List(Cancelled, Pending) && panel.tried(0) == List(0 -> None) && panel.tried(1) == Nil)
    }
    "launch nothing after the unit's cancellation began, and end Cancelled once its candidates in flight ended, however they ended" in {
      val unit = play(role(PanelMode.All, 1, Fallback -> 2, Fallback -> 1, Fallback -> 2))
      assert(unit.cancel() == UnitStep.Wait && unit.state.outcome.isEmpty)
      // A candidate that abstains while the unit is cancelled ends its seat Cancelled; the abstention stays on the attempt.
      assert(unit.abstain(0, 0) == UnitStep.Wait && unit.deliver(1, 0) == UnitStep.Wait)
      assert(unit.cancelled(2, 0) == ended(UnitOutcome.Cancelled))
      assert(unit.ends == List(Cancelled, Delivered(1), Cancelled) && unit.tried(0) == List(0 -> quota))
      val topped = play(role(PanelMode.Any, 1, Fallback -> 1, Fallback -> 1))
      assert(topped.cancel() == UnitStep.Wait && topped.fail(0, 0) == ended(UnitOutcome.Cancelled) && topped.ends == List(Failed(0, 0), Pending))
    }
    "be Cancelled although a seat delivered, and keep the end of a unit that had ended" in {
      val late = play(role(PanelMode.All, 1, Fallback -> 1, Fallback -> 1))
      assert(late.deliver(0, 0) == UnitStep.Wait && late.cancelled(1, 0) == ended(UnitOutcome.Cancelled))
      given done: Play = play(single(Fallback, 1))
      val end = ended(UnitOutcome.Decided({ done.deliver(0, 0); delivered(0) }, Nil))
      assert(done.cancel() == end && done.state.outcome.contains(UnitOutcome.Decided(delivered(0), Nil)))
    }
  }

  "A unit (Behavioral Active Blackbox Atomic)" should {
    "refuse an event that does not belong to a candidate in flight, and a role that cannot be run" in {
      val unit = play(role(PanelMode.Any, 1, Fallback -> 2, Fallback -> 1))
      def refused(event: UnitEvent, fragment: String): Unit =
        assert(intercept[IllegalStateException](unit.raw(event)).getMessage.contains(fragment), event.toString)
      refused(UnitEvent.Failed(SeatCandidate(0, 0), "fault"), "ended before its attempt was reported")
      refused(UnitEvent.Delivered(SeatCandidate(0, 0), result(0), ChildNext.Review), "ended before its attempt was reported")
      refused(UnitEvent.Abstained(SeatCandidate(0, 0), AbstentionReason.Quota, "detail"), "ended before its attempt was reported")
      refused(UnitEvent.Started(SeatCandidate(0, 1), attempt(0, 1)), "Candidate 1 of seat 0")
      refused(UnitEvent.Started(SeatCandidate(1, 0), attempt(1, 0)), "Candidate 0 of seat 1")
      refused(UnitEvent.Started(SeatCandidate(2, 0), attempt(2, 0)), "is not in flight")
      refused(UnitEvent.Started(SeatCandidate(-1, 0), attempt(0, 0)), "is not in flight")
      assert(unit.started(0, 0) == UnitStep.Wait)
      refused(UnitEvent.Started(SeatCandidate(0, 0), attempt(0, 0)), "already has its attempt")
      assert(unit.deliver(0, 0).isInstanceOf[UnitStep.Ended])
      refused(UnitEvent.Failed(SeatCandidate(0, 0), "fault"), "is not in flight")
      refused(UnitEvent.Started(SeatCandidate(1, 0), attempt(1, 0)), "is not in flight")
      for (invalid <- List(role(PanelMode.All, 0, Fallback -> 1), role(PanelMode.Any, 3, Fallback -> 1, Fallback -> 1), role(PanelMode.All, 1),
          role(PanelMode.All, 1, Fallback -> 0)))
        assert(intercept[IllegalArgumentException](play(invalid)).getMessage.contains("resolved role"), invalid.toString)
    }

    // Every role of up to three seats with up to two candidates each, and every order and kind of end of its candidates.
    "keep its rules in every run of every small role" in {
      val strategies = List(Fallback, First, Rr)
      val shapes = for { strategy <- strategies; candidates <- 1 to 2 } yield strategy -> candidates
      def lists(size: Int): List[List[(SeatStrategy, Int)]] = if (size == 0) List(Nil) else for { head <- shapes; tail <- lists(size - 1) } yield head :: tail
      var runs = 0
      var cancelled = 0
      var outcomes = Set.empty[String]
      for { size <- 1 to 3; seats <- lists(size); mode <- List(PanelMode.All, PanelMode.Any); min <- 1 to size } {
        val plan = role(mode, min, seats*)
        def start(seat: Int): Int = seat % plan.seats(seat).candidates.size
        def expected(seat: Int): List[Int] = plan.seats(seat).strategy match {
          case SeatStrategy.Fallback => plan.seats(seat).candidates.indices.toList
          case SeatStrategy.First => List(0)
          case SeatStrategy.RoundRobin => plan.seats(seat).candidates.indices.toList.map(offset => (start(seat) + offset) % plan.seats(seat).candidates.size)
        }
        // `flying` are the candidates in flight; `began` the seats started, in the order they were.
        def check(state: UnitProgress, flying: Set[SeatCandidate], began: List[Int], context: String): Unit = {
          val seen = state.snapshot.seats
          val done = seen.count(_.end.isInstanceOf[SeatEnd.Delivered])
          val lost = seen.count(value => value.end == SeatEnd.Abstained() || value.end.isInstanceOf[SeatEnd.Failed])
          assert(began == began.sorted && began == (0 until began.size).toList, context)
          mode match {
            case PanelMode.All => assert(began.size == size, context)
            case PanelMode.Any =>
              assert(flying.size + done <= min && began.size <= min + lost, context)
              if (began.size < size) assert(flying.size + done == min, context)
          }
          seen.foreach { value =>
            val order = value.attempts.map(attempt => plan.seats(value.seat).candidates.indexOf(attempt.route))
            assert(order == expected(value.seat).take(order.size), context)
            assert(value.attempts.dropRight(1).forall(_.abstained.nonEmpty), context)
            value.end match {
              case SeatEnd.Abstained() => assert(order == expected(value.seat) && value.attempts.forall(_.abstained.nonEmpty), context)
              case _: SeatEnd.Delivered | _: SeatEnd.Failed => assert(value.attempts.last.abstained.isEmpty, context)
              case _ => assert(flying.exists(_.seat == value.seat) || !began.contains(value.seat), context)
            }
          }
          val outcome = state.outcome
          assert(outcome.isEmpty == flying.nonEmpty, context)
          outcome.foreach {
            case UnitOutcome.Decided(delivered, failed) =>
              assert(done >= min && delivered.size == done && failed.size == seen.count(_.end.isInstanceOf[SeatEnd.Failed]), context)
              if (mode == PanelMode.Any) assert(done == min, context)
            case UnitOutcome.Failed(failed, delivered) => assert(done < min && failed.nonEmpty && delivered.size == done, context)
            case UnitOutcome.Abstained(candidates, delivered) =>
              assert(done < min && delivered.size == done && seen.forall(!_.end.isInstanceOf[SeatEnd.Failed]), context)
              assert(candidates == seen.filter(_.end == SeatEnd.Abstained()).flatMap(_.attempts) && candidates.forall(_.abstained.nonEmpty), context)
            case UnitOutcome.Cancelled => fail(context)
          }
          outcome.foreach(value => outcomes += value.getClass.getSimpleName)
        }
        def step(state: UnitProgress, taken: UnitStep, flying: Set[SeatCandidate], began: List[Int], context: String): Unit = {
          val launched = taken match { case UnitStep.Launch(candidates) => candidates; case _ => Nil }
          val known = launched.foldLeft(state)((current, candidate) => current(UnitEvent.Started(candidate, attempt(candidate.seat, candidate.candidate)))._1)
          val now = flying ++ launched
          val seats = began ++ launched.map(_.seat).filterNot(began.contains)
          check(known, now, seats, context)
          assert(taken.isInstanceOf[UnitStep.Ended] == now.isEmpty && (taken match { case UnitStep.Ended(outcome) => known.outcome.contains(outcome); case _ => true }), context)
          if (now.isEmpty) runs += 1
          // A cancellation at this point: nothing is launched any more, whatever the candidates in flight then report, and the unit
          // ends Cancelled with the last of them.
          def stopping(state: UnitProgress, flying: Set[SeatCandidate], context: String): Unit = for { candidate <- flying; end <- 0 until 2 } {
            val event = if (end == 0) UnitEvent.Delivered(candidate, result(candidate.seat), ChildNext.ConsiderAcceptance) else UnitEvent.Cancelled(candidate)
            val (next, following) = state(event)
            val left = flying - candidate
            assert(following == (if (left.isEmpty) UnitStep.Ended(UnitOutcome.Cancelled) else UnitStep.Wait) && next.outcome.isEmpty == left.nonEmpty, s"$context; $event")
            assert(next.snapshot.seats.map(_.attempts.size) == state.snapshot.seats.map(_.attempts.size), s"$context; $event launched after the cancellation")
            if (left.isEmpty) cancelled += 1 else stopping(next, left, s"$context; $event")
          }
          if (now.nonEmpty) {
            val (stopped, waiting) = known.cancel
            assert(waiting == UnitStep.Wait && stopped.outcome.isEmpty, s"$context; cancel")
            stopping(stopped, now, s"$context; cancel")
          }
          for { candidate <- now; end <- 0 until 3 } {
            val event = end match {
              case 0 => UnitEvent.Delivered(candidate, result(candidate.seat), ChildNext.ConsiderAcceptance)
              case 1 => UnitEvent.Abstained(candidate, AbstentionReason.Quota, "detail")
              case _ => UnitEvent.Failed(candidate, "fault")
            }
            val (next, following) = known(event)
            step(next, following, now - candidate, seats, s"$context; $event")
          }
        }
        val (state, first) = UnitProgress.begin(request, plan, start)
        step(state, first, Set.empty, Nil, cq.core.AgentConfigText.render(plan))
      }
      assert(outcomes == Set("Decided", "Failed", "Abstained") && runs > 10000 && cancelled > 10000, s"$runs runs ended as $outcomes, $cancelled were cancelled")
    }
  }

  "A seat rotation (Behavioral Active Blackbox Atomic)" should {
    val candidates = (0 until 3).toList.map(route(0, _))
    def session(low: Long): SessionId = SessionId(new UUID(99L, low))
    "start at a position derived from the session and advance by one for each unit that draws the seat, wrapping" in {
      for (low <- List(0L, 1L, 2L, 3L, 4L, -1L, -2L, Long.MaxValue, Long.MinValue)) {
        val rotation = new SeatRotation(session(low))
        val first = Math.floorMod(low, 3L).toInt
        assert(List.fill(7)(rotation.next(AgentRole.Reviewer, 0, candidates)) == (0 until 7).toList.map(draw => (first + draw) % 3), s"session $low")
      }
      // Sessions differ in where they start.
      assert(List(0L, 1L, 2L).map(low => new SeatRotation(session(low)).next(AgentRole.Worker, 0, candidates)) == List(0, 1, 2))
      assert(SeatRotation.offset(session(5L)) == 5L)
    }
    "keep one position for each role, seat index and candidate list" in {
      val rotation = new SeatRotation(session(0L))
      assert(List.fill(2)(rotation.next(AgentRole.Reviewer, 0, candidates)) == List(0, 1))
      assert(rotation.next(AgentRole.Worker, 0, candidates) == 0 && rotation.next(AgentRole.Reviewer, 1, candidates) == 0)
      assert(rotation.next(AgentRole.Reviewer, 0, candidates.reverse) == 0 && rotation.next(AgentRole.Reviewer, 0, candidates.take(2)) == 0)
      assert(rotation.next(AgentRole.Reviewer, 0, candidates) == 2 && rotation.next(AgentRole.Reviewer, 0, candidates) == 0)
      assert(new SeatRotation(session(0L)).next(AgentRole.Reviewer, 0, candidates) == 0)
      assert(intercept[IllegalArgumentException](rotation.next(AgentRole.Reviewer, 0, Nil)).getMessage.contains("A seat has candidates"))
    }
    "give every draw of concurrent units its own position" in {
      val rotation = new SeatRotation(session(1L))
      val threads = 8
      val each = 300
      val pool = Executors.newFixedThreadPool(threads)
      val ready = new CountDownLatch(1)
      val drawn = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
      try {
        val tasks = (0 until threads).map(_ => pool.submit(new Runnable {
          def run(): Unit = { ready.await(); (0 until each).foreach(_ => drawn.add(rotation.next(AgentRole.Reviewer, 0, candidates))) }
        }))
        ready.countDown()
        tasks.foreach(_.get(60, TimeUnit.SECONDS))
      } finally pool.shutdown()
      val counts = scala.jdk.CollectionConverters.IterableHasAsScala(drawn).asScala.toList.groupBy(identity).view.mapValues(_.size).toMap
      assert(counts == Map(0 -> 800, 1 -> 800, 2 -> 800), counts.toString)
      assert(rotation.next(AgentRole.Reviewer, 0, candidates) == 1)
    }
  }

  "A review aggregate (Behavioral Active Blackbox Atomic)" should {
    val A = ReviewVerdict.Accepted
    val C = ReviewVerdict.ChangesRequested
    val B = ReviewVerdict.Blocked
    def item(number: Int): ItemId = ItemId(project, Ledger.Tasks, number.toLong)
    // As DispatchProjection.completed derives a review's counts and next from its report.
    def review(seat: Int, verdicts: ReviewVerdict*): ReviewSeat = {
      val members = verdicts.toList.zipWithIndex.map((verdict, index) => ReviewMember(item(index + 1), verdict, if (verdict == A) Nil else List(s"finding of seat $seat")))
      val counts = DispatchProjection.EmptyCounts.copy(accepted = verdicts.count(_ == A), changesRequested = verdicts.count(_ == C), blocked = verdicts.count(_ == B),
        validationIntermittent = seat)
      val next = if (verdicts.contains(C)) ChildNext.Revise else if (verdicts.contains(B)) ChildNext.ResolveBlocker else ChildNext.ConsiderAcceptance
      ReviewSeat(seat, DispatchStatus(request, attempt(seat, 0), DispatchPhase.Completed, None, members.map(_.item), counts, next,
        members.find(_.verdict != A).flatMap(_.findings.headOption), Some(result(seat)), None, true, false, None, None), members)
    }
    val handle = AttemptId(UUID.randomUUID())
    "take the worst verdict: Blocked over ChangesRequested over Accepted" in {
      val all = List(A, C, B)
      val rank = Map(A -> 0, C -> 1, B -> 2)
      for { first <- all; second <- all; third <- all } {
        val expected = List(first, second, third).maxBy(rank)
        assert(ReviewAggregate.worst(List(first, second, third)).contains(expected) && ReviewAggregate.worst(List(first, second)).contains(List(first, second).maxBy(rank)))
      }
      assert(all.forall(verdict => ReviewAggregate.worst(List(verdict)).contains(verdict)) && ReviewAggregate.worst(Nil).isEmpty)
      assert(ReviewAggregate.worst(List(A, B)).contains(B) && ReviewAggregate.worst(List(C, B)).contains(B) && ReviewAggregate.worst(List(C, A)).contains(C))
    }
    // seats in delivery order, then: aggregate verdicts, disputed members, representative seat, next
    val table = List(
      ("one accepting seat", List(review(0, A, A)), List(A, A), Nil, 0, ChildNext.ConsiderAcceptance),
      ("unanimous acceptance", List(review(1, A, A), review(0, A, A)), List(A, A), Nil, 1, ChildNext.ConsiderAcceptance),
      ("unanimous over three seats", List(review(2, A, A, A), review(0, A, A, A), review(1, A, A, A)), List(A, A, A), Nil, 2, ChildNext.ConsiderAcceptance),
      ("unanimous with different verdicts for different members", List(review(0, C, A, B), review(1, C, A, B)), List(C, A, B), Nil, 0, ChildNext.Revise),
      ("unanimous blocked", List(review(1, B), review(0, B)), List(B), Nil, 1, ChildNext.ResolveBlocker),
      ("the second seat dissents", List(review(0, A, A), review(1, C, A)), List(C, A), List(1), 1, ChildNext.Arbitrate),
      ("the first seat dissents", List(review(1, A, C), review(0, A, A)), List(A, C), List(2), 1, ChildNext.Arbitrate),
      ("each seat is the worse on one member", List(review(0, A, C), review(1, C, A)), List(C, C), List(1, 2), 0, ChildNext.Arbitrate),
      ("three verdicts for one member", List(review(0, A, A), review(1, C, A), review(2, B, A)), List(B, A), List(1), 2, ChildNext.Arbitrate),
      ("the worst of a disputed member, not of an agreed one, picks the seat", List(review(0, A, B), review(1, A, B), review(2, C, B)), List(C, B), List(1), 2, ChildNext.Arbitrate),
      ("blocked against changes requested", List(review(0, C, C), review(1, C, B), review(2, C, B)), List(C, B), List(2), 1, ChildNext.Arbitrate),
      ("two members disputed by different seats", List(review(0, A, A, A), review(1, A, C, A), review(2, B, A, A)), List(B, C, A), List(1, 2), 1, ChildNext.Arbitrate))
    "give each member its worst verdict, say whether the seats disagree and which seat stands for the unit" in {
      for ((name, seats, verdicts, disputed, representative, next) <- table) {
        val aggregate = ReviewAggregate(seats)
        assert(aggregate.verdicts == verdicts.zipWithIndex.map((verdict, index) => item(index + 1) -> verdict), name)
        assert(aggregate.disputed == disputed.map(item) && aggregate.mixed == disputed.nonEmpty, name)
        assert(aggregate.representative.seat == representative && aggregate.next == next, name)
        val standing = seats.find(_.seat == representative).get.status
        assert(aggregate.counts == standing.counts.copy(accepted = verdicts.count(_ == A), changesRequested = verdicts.count(_ == C), blocked = verdicts.count(_ == B)), name)
        // The checks of the host are counted for the representative's own run.
        assert(aggregate.counts.validationIntermittent == representative, name)
        val status = aggregate.status(handle)
        assert(status.attempt == handle && status.result.contains(result(representative)) && status.next == next && status.counts == aggregate.counts, name)
        assert(status.copy(attempt = standing.attempt, counts = standing.counts, next = standing.next, blocker = standing.blocker) == standing, name)
        if (disputed.isEmpty) assert(status == standing.copy(attempt = handle), name)
        else assert(status.blocker.contains(s"Reviewers disagree on ${disputed.map(number => s"T$number").mkString(",")}: read Seats"), name)
      }
    }
    "be mixed when seats that agree on every verdict advise different next steps, so that no seat's finding stands behind another's" in {
      // A seat whose host-run check failed advises Revise although its verdicts accept; one that carries a proposal advises ConsiderProposal.
      def advising(seat: Int, next: ChildNext, blocker: Option[String]): ReviewSeat = {
        val accepted = review(seat, A, A)
        accepted.copy(status = accepted.status.copy(next = next, blocker = blocker))
      }
      val failedCheck = advising(1, ChildNext.Revise, Some("Host check fast: Failed"))
      val proposing = advising(2, ChildNext.ConsiderProposal, None)
      for ((name, seats, representative, steps) <- List(
        ("the second seat's check failed", List(review(0, A, A), failedCheck), 1, List(ChildNext.ConsiderAcceptance, ChildNext.Revise)),
        ("the first seat's check failed", List(failedCheck, review(0, A, A)), 1, List(ChildNext.Revise, ChildNext.ConsiderAcceptance)),
        ("one seat carries a proposal", List(review(0, A, A), review(1, A, A), proposing), 2, List(ChildNext.ConsiderAcceptance, ChildNext.ConsiderProposal)),
        ("a failed check and a proposal", List(proposing, failedCheck), 2, List(ChildNext.ConsiderProposal, ChildNext.Revise)))) {
        val aggregate = ReviewAggregate(seats)
        assert(aggregate.disputed.isEmpty && aggregate.mixed && aggregate.steps == steps && aggregate.next == ChildNext.Arbitrate, name)
        assert(aggregate.representative.seat == representative && aggregate.verdicts.forall(_._2 == A), name)
        val status = aggregate.status(handle)
        assert(status.attempt == handle && status.result.contains(result(representative)) && status.next == ChildNext.Arbitrate, name)
        assert(status.blocker.contains(s"Reviewers agree on every verdict but their results advise different next steps (${steps.mkString(", ")}): read Seats"), name)
      }
      // Seats that advise the same step are unanimous, whatever that step is; disputed members are named before differing steps.
      val agreeing = ReviewAggregate(List(failedCheck, advising(0, ChildNext.Revise, Some("Host check fast: Failed"))))
      assert(!agreeing.mixed && agreeing.steps.isEmpty && agreeing.next == ChildNext.Revise && agreeing.representative.seat == 1)
      val disputed = ReviewAggregate(List(review(0, A, A), review(1, C, A), proposing))
      assert(disputed.disputed == List(item(1)) && disputed.representative.seat == 1 && disputed.status(handle).blocker.contains("Reviewers disagree on T1: read Seats"))
    }
    "refuse seats that do not cover the same members, and no seat at all" in {
      assert(intercept[IllegalArgumentException](ReviewAggregate(Nil)).getMessage.contains("needs a delivered seat"))
      assert(intercept[IllegalArgumentException](ReviewAggregate(List(review(0, A, A), review(1, A)))).getMessage.contains("cover the same members"))
      // The order in which a report lists its members does not matter.
      val reversed = review(1, C, A)
      assert(ReviewAggregate(List(review(0, A, A), reversed.copy(members = reversed.members.reverse))).disputed == List(item(1)))
    }
  }

  "The end of a unit, as its governing session and its drive read it (Behavioral Active Blackbox Atomic)" should {
    val handle = attempt(0, 0)
    val items = List(ItemId(project, Ledger.Tasks, 1), ItemId(project, Ledger.Tasks, 2))
    def status(id: AttemptId, phase: DispatchPhase, next: ChildNext, blocker: Option[String], handle: Option[ArtifactId]): DispatchStatus =
      DispatchStatus(RequestId(UUID.randomUUID()), id, phase, Some(JobPhase.Settled), items, DispatchProjection.EmptyCounts, next, blocker, handle, None, true, true, None, None)
    def reviewed(seat: Int, candidate: Int, verdicts: ReviewVerdict*): EndedAttempt = {
      val members = verdicts.toList.zip(items).map((verdict, item) => ReviewMember(item, verdict, if (verdict == ReviewVerdict.Accepted) Nil else List(s"finding of seat $seat")))
      val next = if (verdicts.contains(ReviewVerdict.ChangesRequested)) ChildNext.Revise else ChildNext.ConsiderAcceptance
      EndedAttempt(attempt(seat, candidate), status(attempt(seat, candidate), DispatchPhase.Completed, next, members.flatMap(_.findings).headOption, Some(result(seat))).copy(
        counts = DispatchProjection.EmptyCounts.copy(accepted = verdicts.count(_ == ReviewVerdict.Accepted), changesRequested = verdicts.count(_ == ReviewVerdict.ChangesRequested))), false, Some(members))
    }
    def worked(seat: Int, candidate: Int): EndedAttempt =
      EndedAttempt(attempt(seat, candidate), status(attempt(seat, candidate), DispatchPhase.Completed, ChildNext.Review, None, Some(result(seat))), false, None)
    def failed(seat: Int, candidate: Int, fault: String): EndedAttempt =
      EndedAttempt(attempt(seat, candidate), status(attempt(seat, candidate), DispatchPhase.Failed, ChildNext.Retry, Some(fault), None), false, None)
    def abstained(seat: Int, candidate: Int): EndedAttempt = EndedAttempt(attempt(seat, candidate),
      status(attempt(seat, candidate), DispatchPhase.Abstained, ChildNext.ResolveBlocker, Some(s"Abstained (Quota): detail $seat.$candidate"), None), false, None)
    def cancelled(seat: Int, candidate: Int): EndedAttempt = EndedAttempt(attempt(seat, candidate),
      status(attempt(seat, candidate), DispatchPhase.Cancelled, ChildNext.Retry, Some(DispatchUnits.Cancelled), None), true, None)
    def seat(value: EndedAttempt, index: Int): DeliveredSeat = DeliveredSeat(index, value.attempt, value.status.result.get, value.status.next)
    def fault(value: EndedAttempt, index: Int): FailedSeat = FailedSeat(index, value.attempt, value.status.blocker.get)
    def tried(value: EndedAttempt, seat: Int, candidate: Int): SeatAttempt = SeatAttempt(value.attempt, route(seat, candidate), Some(AbstentionReason.Quota), Some(s"detail $seat.$candidate"))
    def unit(outcome: UnitOutcome, ended: EndedAttempt*): DispatchStatus = DispatchUnits.status(request, handle, outcome, ended.toList, None)
    val A = ReviewVerdict.Accepted
    val C = ReviewVerdict.ChangesRequested

    "be the delivering attempt's status under the unit's handle, whichever candidate delivered" in {
      // The first candidate abstained and the second delivered: the handle is the first attempt, the result the second's.
      val (first, second) = (abstained(0, 0), worked(0, 1))
      val decided = unit(UnitOutcome.Decided(List(seat(second, 0)), Nil), first, second)
      assert(decided == second.status.copy(request = request, attempt = handle) && decided.attempt == first.attempt && decided.result.contains(result(0)))
      val outcomes = DispatchUnits.outcomes(UnitOutcome.Decided(List(seat(second, 0)), Nil), ChildOutcome(handle, items, ChildEnd.Admitted, Some("input"), None), List(first, second))
      assert(outcomes == Map(
        first.attempt -> ChildOutcome(first.attempt, items, ChildEnd.Abstained, Some("input"), Some("Abstained (Quota): detail 0.0")),
        second.attempt -> ChildOutcome(second.attempt, items, ChildEnd.Admitted, Some("input"), None)))
      outcomes.values.foreach(cq.core.DriverPolicy.outcome)
    }
    "stand for agreeing reviews with the first that delivered, and for disagreeing ones with the dissenting review and next Arbitrate" in {
      val (accepting, also, dissenting) = (reviewed(0, 0, A, A), reviewed(1, 0, A, A), reviewed(2, 0, A, C))
      val unanimous = unit(UnitOutcome.Decided(List(seat(also, 1), seat(accepting, 0)), Nil), accepting, also)
      assert(unanimous == also.status.copy(request = request, attempt = handle) && unanimous.next == ChildNext.ConsiderAcceptance && unanimous.result.contains(result(1)))
      val mixed = unit(UnitOutcome.Decided(List(seat(accepting, 0), seat(also, 1), seat(dissenting, 2)), Nil), accepting, also, dissenting)
      assert(mixed.attempt == handle && mixed.phase == DispatchPhase.Completed && mixed.next == ChildNext.Arbitrate && mixed.result.contains(result(2)))
      assert(mixed.blocker.contains("Reviewers disagree on T2: read Seats") && (mixed.counts.accepted, mixed.counts.changesRequested) == (1, 1))
      // One delivered review of a panel decides it as a single seat does.
      assert(unit(UnitOutcome.Decided(List(seat(dissenting, 2)), Nil), dissenting) == dissenting.status.copy(request = request, attempt = handle))
      // Only reviews are delivered by several seats.
      assert(intercept[IllegalStateException](unit(UnitOutcome.Decided(List(seat(worked(0, 0), 0), seat(worked(1, 0), 1)), Nil), worked(0, 0), worked(1, 0)))
        .getMessage.contains("Only reviews are delivered by several seats"))
    }
    "name a failed seat that the others made up for, and settle its attempt as failed without offering the input again (Q69)" in {
      val (broken, accepting) = (failed(0, 0, "Child report does not match its assigned role"), reviewed(1, 0, A, A))
      val outcome = UnitOutcome.Decided(List(seat(accepting, 1)), List(fault(broken, 0)))
      val decided = unit(outcome, broken, accepting)
      assert(decided.next == ChildNext.ConsiderAcceptance && decided.result.contains(result(1)) && decided.phase == DispatchPhase.Completed)
      assert(decided.blocker.contains("seat 0 failed and the other seats decided: Child report does not match its assigned role"))
      val mixed = unit(UnitOutcome.Decided(List(seat(accepting, 1), seat(reviewed(2, 0, C, A), 2)), List(fault(broken, 0))), broken, accepting, reviewed(2, 0, C, A))
      assert(mixed.blocker.contains("Reviewers disagree on T1: read Seats; seat 0 failed and the other seats decided: Child report does not match its assigned role"))
      val outcomes = DispatchUnits.outcomes(outcome, ChildOutcome(handle, items, ChildEnd.Admitted, Some("input"), None), List(broken, accepting))
      assert(outcomes(broken.attempt) == ChildOutcome(broken.attempt, items, ChildEnd.Failed, Some("input"), Some("Child report does not match its assigned role")))
      assert(outcomes(accepting.attempt).end == ChildEnd.Admitted)
      // A failure that is not offered again leaves the input no drive retries, and no repetition ends one.
      outcomes.values.foreach(cq.core.DriverPolicy.outcome)
    }
    "be the first failed seat's status when too few delivered, with the retry decided once for the unit" in {
      val (first, second, accepting) = (failed(1, 0, "first fault"), failed(0, 0, "second fault"), reviewed(2, 0, A, A))
      val outcome = UnitOutcome.Failed(List(fault(first, 1), fault(second, 0)), List(seat(accepting, 2)))
      val decided = unit(outcome, second, first, accepting)
      val named = s"first fault; 1 seat delivered, fewer than this work needs: seat 2 result ${result(2).value} (next ConsiderAcceptance); read each delivered result before deciding"
      assert(decided == first.status.copy(request = request, attempt = handle, blocker = Some(named)) && decided.phase == DispatchPhase.Failed && decided.next == ChildNext.Retry)
      def ends(reply: ChildEnd): Map[AttemptId, ChildEnd] =
        DispatchUnits.outcomes(outcome, ChildOutcome(handle, items, reply, Some("input"), Some("first fault")), List(second, first, accepting)).view.mapValues(_.end).toMap
      // Offered again: the retry is decided by the unit's end, not by one seat's admitted result. Every attempt of the unit, the
      // delivering one included, reports a retryable input, so the drive reads the input as retryable (Q53) and continues on it.
      assert(ends(ChildEnd.Retryable) == Map(first.attempt -> ChildEnd.Retryable, second.attempt -> ChildEnd.Retryable, accepting.attempt -> ChildEnd.Retryable))
      val offered = DispatchUnits.outcomes(outcome, ChildOutcome(handle, items, ChildEnd.Retryable, Some("input"), Some("first fault")), List(second, first, accepting))
      assert(offered(accepting.attempt) == ChildOutcome(accepting.attempt, items, ChildEnd.Retryable, Some("input"), Some("first fault")))
      offered.values.foreach(cq.core.DriverPolicy.outcome)
      // A candidate of that unit that abstained before another failed stays an abstention, which the drive does not count.
      val refused = abstained(1, 1)
      assert(DispatchUnits.outcomes(outcome, ChildOutcome(handle, items, ChildEnd.Retryable, Some("input"), Some("first fault")), List(refused, first, accepting))(refused.attempt).end == ChildEnd.Abstained)
      // The same fault as the unit before it: only the seat whose fault was compared is the repetition.
      assert(ends(ChildEnd.Repeated) == Map(first.attempt -> ChildEnd.Repeated, second.attempt -> ChildEnd.Failed, accepting.attempt -> ChildEnd.Admitted))
      // The fault could not be published: the input stays deferred.
      assert(ends(ChildEnd.Failed) == Map(first.attempt -> ChildEnd.Failed, second.attempt -> ChildEnd.Failed, accepting.attempt -> ChildEnd.Admitted))
      val retried = DispatchUnits.outcomes(outcome, ChildOutcome(handle, items, ChildEnd.Retryable, Some("input"), Some("first fault")), List(second, first, accepting))
      assert(retried(second.attempt).fault.contains("second fault") && retried(first.attempt).attempt == first.attempt)
      retried.values.foreach(cq.core.DriverPolicy.outcome)
    }
    "be Abstained with every candidate and reason when no model could run, and give each abstention that text" in {
      val (first, second) = (abstained(0, 0), abstained(0, 1))
      val candidates = List(tried(first, 0, 0), tried(second, 0, 1))
      val outcome = UnitOutcome.Abstained(candidates, Nil)
      val decided = unit(outcome, first, second)
      val text = "No configured model could run this work: pi:provider0/model0 Quota (detail 0.0); pi:provider0/model1 Quota (detail 0.1)"
      assert(decided.attempt == handle && decided.phase == DispatchPhase.Abstained && decided.next == ChildNext.ResolveBlocker && decided.result.isEmpty && decided.blocker.contains(text))
      // No fault is published for it, and nothing advises a retry.
      assert(cq.host.CohortFailure.fault(decided).isEmpty)
      val reply = cq.host.CohortFailure.outcome(decided, Some("input"), None)
      val outcomes = DispatchUnits.outcomes(outcome, reply, List(first, second))
      assert(outcomes == Map(first.attempt -> ChildOutcome(first.attempt, items, ChildEnd.Abstained, Some("input"), Some(text)),
        second.attempt -> ChildOutcome(second.attempt, items, ChildEnd.Abstained, Some("input"), Some(text))))
      outcomes.values.foreach(cq.core.DriverPolicy.outcome)
    }
    "I17: name every seat that delivered, with its result handle, when the unit ended with fewer seats than it needs" in {
      // `all` of two reviewers: one requests changes, the other finds no model. The review that was delivered and admitted is not lost.
      val (requesting, without) = (reviewed(0, 0, C, A), abstained(1, 0))
      val outcome = UnitOutcome.Abstained(List(tried(without, 1, 0)), List(seat(requesting, 0)))
      val decided = unit(outcome, requesting, without)
      val text = s"1 seat delivered, fewer than this work needs: seat 0 result ${result(0).value} (next Revise); read each delivered result before deciding. " +
        "No model could run the other seats: pi:provider1/model0 Quota (detail 1.0)"
      assert(decided.attempt == handle && decided.phase == DispatchPhase.Abstained && decided.next == ChildNext.ResolveBlocker && decided.result.isEmpty && decided.blocker.contains(text), decided.toString)
      // The counts are those of the delivered review, not of whichever attempt ended last.
      assert(decided.counts == requesting.status.counts && unit(outcome, without, requesting).counts == requesting.status.counts)
      // The drive reads the input as unserved, with the same text, whichever attempt it concludes last.
      val reply = cq.host.CohortFailure.outcome(decided, Some("input"), None)
      val outcomes = DispatchUnits.outcomes(outcome, reply, List(requesting, without))
      assert(outcomes == Map(requesting.attempt -> ChildOutcome(requesting.attempt, items, ChildEnd.Abstained, Some("input"), Some(text)),
        without.attempt -> ChildOutcome(without.attempt, items, ChildEnd.Abstained, Some("input"), Some(text))))
      outcomes.values.foreach(cq.core.DriverPolicy.outcome)
      // A seat that failed while another delivered and the unit needs both: the failure stands, and the delivered review is named with it.
      val broken = failed(1, 0, "Malformed report")
      val failing = unit(UnitOutcome.Failed(List(fault(broken, 1)), List(seat(requesting, 0))), requesting, broken)
      assert(failing.phase == DispatchPhase.Failed && failing.next == ChildNext.Retry && failing.result.isEmpty && failing.blocker.contains(
        s"Malformed report; 1 seat delivered, fewer than this work needs: seat 0 result ${result(0).value} (next Revise); read each delivered result before deciding"), failing.toString)
    }
    "be Cancelled as the cancelled attempt ended, and a cancellation of its own when the unit was stopped between two candidates" in {
      val (stopped, delivered) = (cancelled(1, 0), reviewed(0, 0, A, A))
      assert(unit(UnitOutcome.Cancelled, delivered, stopped) == stopped.status.copy(request = request, attempt = handle))
      // The first candidate abstained; the unit was cancelled before the second had an attempt.
      val between = unit(UnitOutcome.Cancelled, abstained(0, 0))
      assert(between.phase == DispatchPhase.Cancelled && between.blocker.contains(DispatchUnits.Cancelled) && between.result.isEmpty && between.attempt == handle)
      // The host could not start the next candidate: the unit says why.
      val refused = DispatchUnits.status(request, handle, UnitOutcome.Cancelled, List(abstained(0, 0)), Some("The host could not start the next model of this work: Dispatch admission is closed"))
      assert(refused.phase == DispatchPhase.Cancelled && refused.blocker.contains("The host could not start the next model of this work: Dispatch admission is closed"))
      val outcomes = DispatchUnits.outcomes(UnitOutcome.Cancelled, cq.host.CohortFailure.outcome(stopped.status, Some("input"), None), List(delivered, stopped))
      assert(outcomes.view.mapValues(_.end).toMap == Map(delivered.attempt -> ChildEnd.Admitted, stopped.attempt -> ChildEnd.Cancelled))
    }
    "read an attempt's end as the event of its candidate" in {
      val at = SeatCandidate(1, 2)
      assert(DispatchUnits.event(at, worked(1, 2).status, None) == UnitEvent.Delivered(at, result(1), ChildNext.Review))
      assert(DispatchUnits.event(at, abstained(1, 2).status, Some(cq.host.Abstention(AbstentionReason.RateLimit, "slow down"))) == UnitEvent.Abstained(at, AbstentionReason.RateLimit, "slow down"))
      assert(DispatchUnits.event(at, cancelled(1, 2).status, None) == UnitEvent.Cancelled(at))
      assert(DispatchUnits.event(at, failed(1, 2, "fault").status, None) == UnitEvent.Failed(at, "fault"))
      // An attempt whose outcome is not known, or whose result awaits delivery, did not deliver: it is a failure of its seat, never an abstention.
      for (phase <- List(DispatchPhase.Unknown, DispatchPhase.PublicationPending))
        assert(DispatchUnits.event(at, failed(1, 2, "fault").status.copy(phase = phase), None) == UnitEvent.Failed(at, "fault"))
      assert(DispatchUnits.event(at, failed(1, 2, "fault").status.copy(blocker = None), None) == UnitEvent.Failed(at, cq.host.CohortFailure.Unstated))
      assert(intercept[IllegalStateException](DispatchUnits.event(at, abstained(1, 2).status, None)).getMessage.contains("states no abstention"))
      assert(intercept[IllegalArgumentException](DispatchUnits.event(at, failed(1, 2, "fault").status.copy(phase = DispatchPhase.Running), None)).getMessage.contains("ended in phase Running"))
    }
    "say which role has no model and what to set, in the words of the configuration" in {
      assert(DispatchUnits.unresolved(Harness.Codex, AgentRole.Reviewer, None, List(AgentProblem.RoleUnassigned(Harness.Codex, AgentRole.Reviewer))) ==
        "no model is assigned to the reviewer role for governing harness codex: set defaults.roles.reviewer or harnesses.codex.roles.reviewer in the agent configuration " +
          "(the server's default or this project's); cq agents init --settings FILE writes a starting configuration from a settings file")
      assert(DispatchUnits.unresolved(Harness.Pi, AgentRole.Worker, Some(RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles)),
        List(AgentProblem.TierUndefined(Harness.Claude, ModelTier.Fast, AgentRole.Worker))) ==
        "the worker role for governing harness pi (defaults.roles.worker of the server's default agent configuration) refers to the fast tier of claude, which no layer defines: " +
          "set harnesses.claude.tiers.fast in the agent configuration")
      assert(DispatchUnits.unresolved(Harness.Pi, AgentRole.Planner, Some(RoleOrigin(AgentLayer.Project, RoleSource.HarnessRoles)),
        List(AgentProblem.ProviderRequired(TextPosition(3, 14), Harness.Pi))) ==
        "the planner role for governing harness pi cannot run as harnesses.pi.roles.planner of this project's agent configuration assigns it: 3:14: a pi model is written provider/model")
      assert(DispatchUnits.role(DispatchWork.Worker(WorkerMode.Probe)) == AgentRole.Worker && DispatchUnits.role(DispatchWork.Reviewer(ReviewerMode.Audit)) == AgentRole.Reviewer &&
        DispatchUnits.role(DispatchWork.Planner()) == AgentRole.Planner && DispatchUnits.role(DispatchWork.Explorer(ExplorerMode.Research)) == AgentRole.Explorer)
    }
  }
}
