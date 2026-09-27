package cq.core

import cq.api.*

object ClaimPolicy {
  def active(tx: LedgerTransaction, claim: Claim, now: Long): Boolean =
    !claim.released && claim.expiresAt > now && tx.claimMembers(claim.fence.claim) == claim.members

  def overlapping(tx: LedgerTransaction, members: Set[ItemId], now: Long): List[Claim] =
    members.toList.sortBy(LedgerPolicy.key).flatMap(tx.claim).groupBy(_.fence.claim).values.map { versions =>
      require(versions.distinct.size == 1, "Inconsistent claim membership observations")
      versions.head
    }.filter(active(tx, _, now)).toList.sortBy(_.fence.claim.value.toString)
}
