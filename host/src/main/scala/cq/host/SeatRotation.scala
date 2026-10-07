package cq.host

import cq.api.*

/**
 * Where the RoundRobin seats of one governing session stand. A seat is known by the key that assigns its role, its index and its
 * candidate list, so an edited configuration starts a seat of its own. The first position of a seat is derived from the session, so
 * that sessions that start few units do not all begin with the first candidate. The positions live in this host's memory only.
 */
final class SeatRotation(session: SessionId) {
  private var drawn = Map.empty[(RoleKey, Int, List[ModelRoute]), Long]

  /** The candidate the seat starts with in the unit that draws it; the unit after it starts with the next one. */
  def next(key: RoleKey, seat: Int, candidates: List[ModelRoute]): Int = synchronized {
    require(candidates.nonEmpty, "A seat has candidates")
    val known = (key, seat, candidates)
    val count = drawn.getOrElse(known, 0L)
    drawn = drawn.updated(known, count + 1)
    val size = candidates.size.toLong
    Math.floorMod(Math.floorMod(SeatRotation.offset(session), size) + count, size).toInt
  }
}

object SeatRotation {
  /** The session's share of every seat's first position: the low half of its identifier. */
  def offset(session: SessionId): Long = session.value.getLeastSignificantBits
}
