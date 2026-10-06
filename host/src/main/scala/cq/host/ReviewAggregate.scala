package cq.host

import cq.api.*
import cq.core.{DriverPolicy, LedgerPolicy}

/** One delivered review seat of a unit: the status of its attempt and the verdicts of its report. */
final case class ReviewSeat(seat: Int, status: DispatchStatus, members: List[ReviewMember])

/**
 * What the delivered review seats of one unit say together. `verdicts` holds each member's worst verdict, in the order of the first
 * seat's report; `disputed` the members that received different verdicts from different seats. `representative` is the seat whose
 * result stands for the unit: the first that delivered when no member is disputed, and otherwise the first that gave a disputed
 * member its worst verdict.
 */
final case class ReviewAggregate(verdicts: List[(ItemId, ReviewVerdict)], disputed: List[ItemId], representative: ReviewSeat) {
  def mixed: Boolean = disputed.nonEmpty
  /** The representative's counts with the verdict counts of the aggregate; the host-check counts are that seat's own. */
  def counts: ChildCounts = representative.status.counts.copy(
    accepted = verdicts.count(_._2 == ReviewVerdict.Accepted),
    changesRequested = verdicts.count(_._2 == ReviewVerdict.ChangesRequested),
    blocked = verdicts.count(_._2 == ReviewVerdict.Blocked))
  def next: ChildNext = if (mixed) ChildNext.Arbitrate else representative.status.next
  /** The unit's status: the representative's, under the unit's handle. */
  def status(handle: AttemptId): DispatchStatus = representative.status.copy(attempt = handle, counts = counts, next = next,
    blocker = if (mixed) Some(DispatchProjection.concise(s"Reviewers disagree on ${disputed.map(DriverPolicy.reference).mkString(",")}: read Seats"))
      else representative.status.blocker)
}

object ReviewAggregate {
  private val Severity: Map[ReviewVerdict, Int] = Map(ReviewVerdict.Accepted -> 0, ReviewVerdict.ChangesRequested -> 1, ReviewVerdict.Blocked -> 2)

  /** Blocked is worse than ChangesRequested, which is worse than Accepted. */
  def worst(verdicts: List[ReviewVerdict]): Option[ReviewVerdict] = verdicts.maxByOption(Severity)

  /** `seats` are in the order they delivered. Every review of one unit covers the same members. */
  def apply(seats: List[ReviewSeat]): ReviewAggregate = {
    require(seats.nonEmpty, "A review aggregate needs a delivered seat")
    val items = seats.head.members.map(_.item)
    require(seats.forall(seat => seat.members.map(_.item).sortBy(LedgerPolicy.key) == items.sortBy(LedgerPolicy.key)),
      "The review seats of one unit cover the same members")
    def received(item: ItemId): List[ReviewVerdict] = seats.flatMap(_.members.filter(_.item == item).map(_.verdict))
    val verdicts = items.map(item => item -> worst(received(item)).get)
    val disputed = items.filter(item => received(item).distinct.size > 1)
    val representative = if (disputed.isEmpty) seats.head
      else seats.find(seat => seat.members.exists(member => disputed.contains(member.item) && verdicts.contains(member.item -> member.verdict))).get
    ReviewAggregate(verdicts, disputed, representative)
  }
}
