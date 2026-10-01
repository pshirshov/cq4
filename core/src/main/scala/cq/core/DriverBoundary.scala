package cq.core

import cq.api.*

// A ledger write attributed to an active driven cycle; `parent` is the lineage member the write was made under.
final case class WriteAttribution(key: DriverKey, cycle: CycleId, parent: LineageMember)

// The write-time boundary of driven cycles. Each call runs inside the writing transaction, so a rejection rolls the ledger back.
final class DriverBoundary(registry: DriverRegistry, planner: WorksetPlanner) {
  import DriverPolicy.references

  private def current(project: ProjectId, attribution: WriteAttribution): (DriverRecord, CycleRecord) = {
    val record = registry.get(project, attribution.key).getOrElse(throw new IllegalStateException("Attributed driver disappeared inside its transaction"))
    (record, record.cycle.filter(cycle => cycle.id == attribution.cycle && cycle.active)
      .getOrElse(throw new IllegalStateException("Attributed cycle ended inside its transaction")))
  }

  // Bound-session rule: a write from an on driver's bound session belongs to its active cycle whether or not it names one.
  // A write from any other session is attributed only when it carries the ID of a cycle that delegated to it.
  def admit(project: ProjectId, session: SessionId, explicit: Option[CycleId], now: Long): Option[WriteAttribution] =
    registry.bound(project, session) match {
      case Some(record) =>
        val cycle = record.cycle.filter(_.active)
          .getOrElse(registry.fail(record, "untracked mutation: the bound attached session changed the ledger while no driven cycle was active", now))
        if (explicit.exists(_ != cycle.id)) registry.fail(record, s"a write named a cycle other than the active cycle ${cycle.number}", now)
        Some(WriteAttribution(record.key, cycle.id, LineageMember.Run(cycle.run.get)))
      case None => explicit.map { id =>
        val (record, _) = registry.lineage(project, id, session, now)
        WriteAttribution(record.key, id, LineageMember.Session(session))
      }
    }

  private def existing(mutation: Mutation): List[ItemId] = mutation match {
    case Mutation.Archive(members) => members.map(_.id)
    case Mutation.Produce(producer, _, _, milestone) => producer :: milestone.collect { case MilestoneRef.Existing(id) => id }.toList
    case Mutation.Terminate(roots, _, _) => roots.toList
    case _: Mutation.Create => Nil
    case Mutation.Replace(id, _, _) => List(id)
    case Mutation.Reference(source, _, _, target, _, _) => List(source, target)
    case Mutation.Restore(id, _, _, neighbors) => id :: neighbors.map(_.id)
  }

  // Admission, before the write is applied: every existing item the request names is in the cycle's stored snapshot or was created by the cycle.
  def check(project: ProjectId, attribution: WriteAttribution, request: ChangeRequest, now: Long): Unit = {
    val (record, cycle) = current(project, attribution)
    val outside = request.mutations.flatMap(existing).filterNot(cycle.boundary).distinct
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
  }

  // After the write is applied and before it commits: the items it actually touched are in the boundary, every item it created is selected by
  // roots-bound enumeration of the frozen targets on the resulting state, and the write is stamped with the cycle.
  def verify(tx: LedgerTransaction, attribution: WriteAttribution, acknowledgement: ChangeAck, stamps: List[LineageMember], now: Long): Unit = {
    val (record, cycle) = current(tx.project.id, attribution)
    val (created, changed) = acknowledgement.items.partition(_.revision == Revision(1))
    val outside = changed.map(_.id).filterNot(cycle.boundary)
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
    if (created.nonEmpty) {
      val selected = try planner.evaluate(tx, record.targets, record.through, record.workset).advanceable.map(_.item.id).toSet catch {
        case DomainFailure(fault) => registry.fail(record, s"the advanceable set cannot be recomputed after the write: $fault", now)
      }
      val unselected = created.map(_.id).filterNot(selected)
      if (unselected.nonEmpty)
        registry.fail(record, s"non-selected creation: ${references(unselected)} is not a selected descendant of ${references(record.targets)}", now)
    }
    stamp(record, cycle.copy(created = cycle.created ++ created.map(_.id)), attribution.parent, stamps, now)
  }

  def claimed(project: ProjectId, session: SessionId, claim: ClaimId, now: Long): Unit =
    registry.bound(project, session).foreach { record =>
      record.cycle.filter(_.active).foreach(cycle => stamp(record, cycle, LineageMember.Run(cycle.run.get), List(LineageMember.Claim(claim)), now))
    }

  private def stamp(record: DriverRecord, cycle: CycleRecord, parent: LineageMember, stamps: List[LineageMember], now: Long): Unit = {
    val added = stamps.filterNot(member => cycle.lineage.exists(_.member == member)).map(LineageEntry(_, Some(parent), true))
    if (cycle.lineage.size + added.size > DriverPolicy.MaxLineage)
      registry.fail(record, s"cycle ${cycle.number} exceeds ${DriverPolicy.MaxLineage} lineage members", now)
    registry.put(record.copy(cycle = Some(cycle.copy(lineage = cycle.lineage ++ added)), touchedAt = now))
  }
}
