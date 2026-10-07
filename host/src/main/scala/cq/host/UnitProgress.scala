package cq.host

import cq.api.*
import cq.core.AgentConfigText

/** One candidate of one seat of a unit: the seat's index in the role and the candidate's index in the seat's list. */
final case class SeatCandidate(seat: Int, candidate: Int)

/** What the unit layer observed about a candidate the unit asked it to launch. */
sealed trait UnitEvent { def at: SeatCandidate }
object UnitEvent {
  /** The candidate's attempt exists. It precedes every end of the candidate but a cancellation. */
  final case class Started(at: SeatCandidate, attempt: AttemptId) extends UnitEvent
  final case class Delivered(at: SeatCandidate, result: ArtifactId, next: ChildNext) extends UnitEvent
  final case class Abstained(at: SeatCandidate, reason: AbstentionReason, detail: String) extends UnitEvent
  final case class Failed(at: SeatCandidate, fault: String) extends UnitEvent
  final case class Cancelled(at: SeatCandidate) extends UnitEvent
}

final case class DeliveredSeat(seat: Int, attempt: AttemptId, result: ArtifactId, next: ChildNext)
final case class FailedSeat(seat: Int, attempt: AttemptId, fault: String)

sealed trait UnitOutcome
object UnitOutcome {
  /** At least `min` seats delivered. Both lists are in the order the seats ended; `failed` holds the seats the others made up for. */
  final case class Decided(delivered: List[DeliveredSeat], failed: List[FailedSeat]) extends UnitOutcome
  /** Fewer than `min` seats delivered and a seat failed; `failed` is in the order the seats ended, so its head is the unit's fault. */
  final case class Failed(failed: List[FailedSeat], delivered: List[DeliveredSeat]) extends UnitOutcome
  /** Fewer than `min` seats delivered and none failed: every candidate of the seats that abstained, by seat and in the order tried. */
  final case class Abstained(candidates: List[SeatAttempt], delivered: List[DeliveredSeat]) extends UnitOutcome
  case object Cancelled extends UnitOutcome
}

sealed trait UnitStep
object UnitStep {
  final case class Launch(candidates: List[SeatCandidate]) extends UnitStep
  case object Wait extends UnitStep
  final case class Ended(outcome: UnitOutcome) extends UnitStep
}

/**
 * The attempts of one unit (one started choice) under the role its agent configuration resolved to. A value is one state; `apply`
 * gives the state after an event and what the unit layer does next. `start` gives the candidate a RoundRobin seat begins with and is
 * asked once for such a seat, when the seat's first candidate is launched: a seat that is never started draws no position.
 */
final class UnitProgress private (request: RequestId, role: UnitProgress.Panel, start: Int => Int, seats: Vector[UnitProgress.Seat],
  ended: Vector[Int], cancelling: Boolean) {
  import UnitProgress.*

  private def copy(seats: Vector[Seat], ended: Vector[Int], cancelling: Boolean): UnitProgress =
    new UnitProgress(request, role, start, seats, ended, cancelling)

  private def delivered: List[DeliveredSeat] = ended.toList.flatMap(index => seats(index).end match {
    case SeatEnd.Delivered(result, next) => Some(DeliveredSeat(index, seats(index).tried.last.attempt.get, result, next))
    case _ => None
  })
  private def failed: List[FailedSeat] = ended.toList.flatMap(index => seats(index).end match {
    case SeatEnd.Failed(fault) => Some(FailedSeat(index, seats(index).tried.last.attempt.get, fault))
    case _ => None
  })
  private def flying: Int = seats.count(_.open)

  /** Present once no candidate is in flight and none is left to launch. */
  def outcome: Option[UnitOutcome] = Option.when(flying == 0) {
    if (cancelling) UnitOutcome.Cancelled
    else if (delivered.size >= role.min) UnitOutcome.Decided(delivered, failed)
    else if (failed.nonEmpty) UnitOutcome.Failed(failed, delivered)
    else UnitOutcome.Abstained(snapshot.seats.filter(_.end == SeatEnd.Abstained()).flatMap(_.attempts), delivered)
  }

  /** The seats as `Seats` shows them. A seat an `any` panel has not started, or never needed, is Pending without attempts. */
  def snapshot: UnitSeats = UnitSeats(request, role.mode, role.min, seats.toList.zipWithIndex.map { (seat, index) =>
    SeatStatus(index, seat.tried.toList.flatMap(tried => tried.attempt.map(attempt =>
      SeatAttempt(attempt, role.seats(index).candidates(tried.candidate), tried.abstained.map(_._1), tried.abstained.map(_._2)))), seat.end)
  })

  // Starts as many unstarted seats, in listed order, as the mode still needs: every seat of an `all` panel, and for `any` the seats
  // that bring those in flight and those delivered to `min`.
  private def topUp: (UnitProgress, UnitStep) = {
    val unstarted = seats.indices.filter(index => seats(index).order.isEmpty).toList
    val wanted = if (cancelling) 0 else role.mode match {
      case PanelMode.All => unstarted.size
      case PanelMode.Any => role.min - delivered.size - flying
    }
    val launched = unstarted.take(wanted).map { index =>
      val candidates = role.seats(index).candidates.size
      val order = role.seats(index).strategy match {
        case SeatStrategy.Fallback => (0 until candidates).toList
        case SeatStrategy.First => List(0)
        case SeatStrategy.RoundRobin =>
          val first = start(index)
          require(first >= 0 && first < candidates, s"Round-robin start $first is outside the $candidates candidates of seat $index")
          (0 until candidates).toList.map(offset => (first + offset) % candidates)
      }
      index -> Seat(order, Vector(Tried(order.head, None, None)), SeatEnd.Pending(), true)
    }
    val next = copy(launched.foldLeft(seats) { case (all, (index, seat)) => all.updated(index, seat) }, ended, cancelling)
    if (launched.nonEmpty) next -> UnitStep.Launch(launched.map((index, seat) => SeatCandidate(index, seat.order.head)))
    else next -> next.outcome.fold[UnitStep](UnitStep.Wait)(UnitStep.Ended(_))
  }

  /** The unit is being cancelled: nothing more is launched, and it ends Cancelled once every candidate in flight has ended, however it ended. */
  def cancel: (UnitProgress, UnitStep) = outcome match {
    // A unit that has ended keeps its end.
    case Some(value) => this -> UnitStep.Ended(value)
    case None => copy(seats, ended, true) -> UnitStep.Wait
  }

  def apply(event: UnitEvent): (UnitProgress, UnitStep) = {
    val index = event.at.seat
    if (index < 0 || index >= seats.size || !seats(index).open || seats(index).tried.last.candidate != event.at.candidate)
      throw new IllegalStateException(s"Candidate ${event.at.candidate} of seat $index of unit ${request.value} is not in flight")
    val seat = seats(index)
    val tried = seat.tried.last
    def known(): Unit = if (tried.attempt.isEmpty)
      throw new IllegalStateException(s"Candidate ${event.at.candidate} of seat $index of unit ${request.value} ended before its attempt was reported")
    def closed(end: SeatEnd, last: Tried, cancelled: Boolean): (UnitProgress, UnitStep) =
      copy(seats.updated(index, seat.copy(tried = seat.tried.init :+ last, end = end, open = false)), ended :+ index, cancelling || cancelled).topUp
    event match {
      case UnitEvent.Started(_, attempt) =>
        if (tried.attempt.nonEmpty) throw new IllegalStateException(s"Candidate ${event.at.candidate} of seat $index of unit ${request.value} already has its attempt")
        copy(seats.updated(index, seat.copy(tried = seat.tried.init :+ tried.copy(attempt = Some(attempt)))), ended, cancelling) -> UnitStep.Wait
      case UnitEvent.Delivered(_, result, next) =>
        known()
        closed(SeatEnd.Delivered(result, next), tried, false)
      case UnitEvent.Failed(_, fault) =>
        known()
        closed(SeatEnd.Failed(fault), tried, false)
      case UnitEvent.Cancelled(_) => closed(SeatEnd.Cancelled(), tried, true)
      case UnitEvent.Abstained(_, reason, detail) =>
        known()
        val abstained = tried.copy(abstained = Some(reason -> detail))
        seat.order.drop(seat.tried.size).headOption match {
          // A cancellation is never an abstention: the seat of a unit being cancelled does not go on to its next candidate.
          case _ if cancelling => closed(SeatEnd.Cancelled(), abstained, false)
          case None => closed(SeatEnd.Abstained(), abstained, false)
          case Some(candidate) =>
            copy(seats.updated(index, seat.copy(tried = seat.tried.init :+ abstained :+ Tried(candidate, None, None))), ended, cancelling) ->
              UnitStep.Launch(List(SeatCandidate(index, candidate)))
        }
    }
  }
}

object UnitProgress {
  private[host] final case class Tried(candidate: Int, attempt: Option[AttemptId], abstained: Option[(AbstentionReason, String)])
  // `order` is empty until the seat is started; `open` while its last candidate is in flight.
  private[host] final case class Seat(order: List[Int], tried: Vector[Tried], end: SeatEnd, open: Boolean)

  /** The seats a unit runs and how many of them decide it: what the progress of a unit reads of the role it was resolved to. */
  private[host] final case class Panel(mode: PanelMode, min: Int, seats: List[ResolvedSeat])

  /** The state of a unit that has launched the first candidates the step names. */
  def begin(request: RequestId, role: ResolvedRole, start: Int => Int): (UnitProgress, UnitStep) = {
    require(role.seats.nonEmpty && role.seats.forall(_.candidates.nonEmpty), "A resolved role has seats, and each seat has candidates")
    require(role.min >= 1 && role.min <= role.seats.size, s"A resolved role's min is between 1 and its ${role.seats.size} seats")
    new UnitProgress(request, Panel(role.mode, role.min, role.seats), start, Vector.fill(role.seats.size)(Seat(Nil, Vector.empty, SeatEnd.Pending(), false)), Vector.empty, false).topUp
  }

  /** The state of a unit of one seat with the one candidate `route`, which no agent configuration resolved: the governing session's
    * own work. It has launched that candidate. */
  def single(request: RequestId, route: ModelRoute): (UnitProgress, UnitStep) =
    new UnitProgress(request, Panel(PanelMode.All, 1, List(ResolvedSeat(SeatStrategy.First, List(route)))), _ => 0,
      Vector(Seat(Nil, Vector.empty, SeatEnd.Pending(), false)), Vector.empty, false).topUp

  /** Which candidates abstained and why, as the blocker of a unit no model could run and the fault of its outcome under a drive. */
  def abstention(candidates: List[SeatAttempt]): String = "No configured model could run this work: " + tried(candidates)
  private def tried(candidates: List[SeatAttempt]): String = candidates.map(value =>
    s"${AgentConfigText.render(value.route)} ${value.abstained.get}" + value.detail.fold("")(detail => s" ($detail)")).mkString("; ")

  /** The seats that delivered in a unit that ended with fewer than it needs, each with the handle of its result: what was delivered
    * and admitted is read before the unit's end is acted on. It comes first in a blocker, which is cut at its bound. */
  def delivered(seats: List[DeliveredSeat]): String =
    s"${seats.size} ${if (seats.size == 1) "seat" else "seats"} delivered, fewer than this work needs: " +
      seats.map(seat => s"seat ${seat.seat} result ${seat.result.value} (next ${seat.next})").mkString("; ") + "; read each delivered result before deciding"

  /** The blocker of a unit that ended by abstention: every candidate and its reason, after the seats that delivered when some did. */
  def abstention(candidates: List[SeatAttempt], seats: List[DeliveredSeat]): String =
    if (seats.isEmpty) abstention(candidates) else delivered(seats) + ". No model could run the other seats: " + tried(candidates)
}
