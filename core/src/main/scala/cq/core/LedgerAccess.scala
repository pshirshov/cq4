package cq.core

import cq.api.*

object LedgerAccess {
  import LedgerPolicy.*

  def write(scope: Scope): Unit =
    if (!Set[Role](Role.Human, Role.Governor).contains(scope.actor.role)) throw DomainFailure(Fault.Denied("Role cannot mutate ledgers or claims"))

  def inScope(scope: Scope, id: ItemId): Unit = {
    if (scope.project != id.project) throw DomainFailure(Fault.Denied("Item belongs to another project"))
    invalid(id.number > 0, "Item number must be positive")
  }

  def required(tx: LedgerTransaction, scope: Scope, id: ItemId): Item = {
    inScope(scope, id)
    tx.get(id).getOrElse(throw DomainFailure(Fault.Missing(s"Missing ${prefix(id.ledger)}${id.number}")))
  }

  def expected(item: Item, revision: Revision): Unit =
    if (item.revision != revision) throw DomainFailure(Fault.Conflict(s"Expected revision ${revision.value}, actual ${item.revision.value}"))

  def fenced(tx: LedgerTransaction, scope: Scope, item: ItemId, fences: List[Fence], now: Long): Unit = {
    val active = tx.claim(item).filter(ClaimPolicy.active(tx, _, now))
    active.foreach { c =>
      if (c.owner != scope.actor || !fences.contains(c.fence)) throw DomainFailure(Fault.StaleFence("Item has an active claim; current owner and fence required"))
    }
    fences.foreach { fence =>
      val c = tx.claimById(fence.claim).getOrElse(throw DomainFailure(Fault.StaleFence("Unknown claim")))
      if (c.members.contains(item) && (c.fence != fence || !ClaimPolicy.active(tx, c, now) || c.owner != scope.actor))
        throw DomainFailure(Fault.StaleFence("Claim is expired, released, replaced, or belongs to another actor"))
    }
  }

}
