package cq.core

import cq.api.*

// A ledger write attributed to an active driven cycle; `parent` is the lineage member the write was made under.
final case class WriteAttribution(key: DriverKey, cycle: CycleId, parent: LineageMember)

// The write-time boundary of driven cycles. Each call runs inside the writing transaction, so a rejection rolls the ledger back.
final class DriverBoundary(registry: DriverRegistry, planner: WorksetPlanner) {
  import DriverPolicy.{reference, references}

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

  // The Task and the milestone of a membership a Reference adds, in either direction the edge may be written.
  private def joined(mutation: Mutation): Option[(ItemId, ItemId)] = (mutation match {
    case Mutation.Reference(source, _, Relation.PartOf, target, _, true) => Some(source -> target)
    case Mutation.Reference(source, _, Relation.Contains, target, _, true) => Some(target -> source)
    case _ => None
  }).filter((task, _) => task.ledger == Ledger.Tasks)

  // The existing milestone a mutation assigns Tasks to. A drive plans Tasks under milestones that workset traversal never selects.
  private def assigned(mutation: Mutation): Option[ItemId] = mutation match {
    case Mutation.Produce(_, _, _, milestone) => milestone.collect { case MilestoneRef.Existing(id) => id }
    case other => joined(other).map((_, milestone) => milestone)
  }

  private def existing(mutation: Mutation): List[ItemId] = mutation match {
    case Mutation.Archive(members) => members.map(_.id)
    case Mutation.Produce(producer, _, _, _) => List(producer)
    case Mutation.Terminate(roots, _, _) => roots.toList
    case _: Mutation.Create => Nil
    case Mutation.Replace(id, _, _) => List(id)
    case Mutation.Reference(source, _, _, target, _, _) => joined(mutation).fold(List(source, target))((task, _) => List(task))
    case Mutation.Restore(id, _, _, neighbors) => id :: neighbors.map(_.id)
  }

  private def openMilestone(tx: LedgerTransaction, id: ItemId): Boolean = tx.get(id).exists(_.draft.content match {
    case milestone: Content.Milestone => milestone.status == MilestoneStatus.Open
    case _ => false
  })

  private def question(item: Item): Content.Question = item.draft.content match {
    case value: Content.Question => value
    case other => throw new IllegalStateException(s"Question ${reference(item.id)} holds ${other.getClass.getSimpleName}")
  }

  // Whether the applied write made the Question Answered or changed its answer.
  private def answers(tx: LedgerTransaction, written: ItemRevision): Boolean = {
    val after = question(tx.get(written.id).getOrElse(throw new IllegalStateException("A written Question disappeared inside its transaction")))
    val before = if (written.revision == Revision(1)) None
      else Some(question(tx.historical(written.id, Revision(written.revision.value - 1))
        .getOrElse(throw new IllegalStateException("A written Question has no previous revision")).item.item))
    (after.status == QuestionStatus.Answered && !before.exists(_.status == QuestionStatus.Answered)) || after.answer != before.flatMap(_.answer)
  }

  // Admission, before the write is applied: every existing item the request names is in the cycle's stored snapshot or was created by the cycle.
  // The one exception is the milestone of an assignment, which may be any Open milestone.
  def check(tx: LedgerTransaction, attribution: WriteAttribution, request: ChangeRequest, now: Long): Unit = {
    val (record, cycle) = current(tx.project.id, attribution)
    val named = request.mutations.flatMap(existing) ++ request.mutations.flatMap(assigned).filterNot(openMilestone(tx, _))
    val outside = named.filterNot(cycle.boundary).distinct
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
  }

  // After the write is applied and before it commits: the items it actually touched are in the boundary, every item it created is selected by
  // roots-bound enumeration of the frozen targets on the resulting state, and the write is stamped with the cycle.
  def verify(tx: LedgerTransaction, attribution: WriteAttribution, request: ChangeRequest, acknowledgement: ChangeAck, stamps: List[LineageMember], now: Long): Unit = {
    val (record, cycle) = current(tx.project.id, attribution)
    val (created, changed) = acknowledgement.items.partition(_.revision == Revision(1))
    // A batch changes an item once, so a milestone that passed `check` only as an assignment was revised for its new member alone.
    val milestones = request.mutations.flatMap(assigned).toSet
    val outside = changed.map(_.id).filterNot(id => cycle.boundary(id) || milestones(id))
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
    // Only a person answers a Question. The refusal leaves the driver on: the operator parks, the answer is recorded, and the drive starts again.
    val answered = acknowledgement.items.filter(item => item.id.ledger == Ledger.Questions && answers(tx, item)).map(_.id)
    if (answered.nonEmpty) throw DomainFailure(Fault.Denied(DriverPolicy.answerRefused(answered)))
    if (created.nonEmpty) {
      val selected = try planner.evaluate(tx, record.targets, record.through, record.workset).advanceable.map(_.item.id).toSet catch {
        case DomainFailure(fault) => registry.fail(record, s"the advanceable set cannot be recomputed after the write: $fault", now)
      }
      val produced = created.map(_.id).filter(selected).toSet
      // A Milestone created for the Tasks this write produced is reached from them as context, never selected.
      def planned(id: ItemId): Boolean = id.ledger == Ledger.Milestones && tx.refs(id).exists(ref => ref.relation == Relation.Contains && produced(ref.target))
      val unselected = created.map(_.id).filterNot(id => selected(id) || planned(id))
      if (unselected.nonEmpty)
        registry.fail(record, s"non-selected creation: ${references(unselected)} is not a selected descendant of ${references(record.targets)}", now)
    }
    stamp(tx, record, cycle, created.map(_.id), attribution.parent, stamps, now)
  }

  def claimed(tx: LedgerTransaction, session: SessionId, claim: ClaimId, now: Long): Unit =
    registry.bound(tx.project.id, session).foreach { record =>
      record.cycle.filter(_.active).foreach(cycle => stamp(tx, record, cycle, Nil, LineageMember.Run(cycle.run.get), List(LineageMember.Claim(claim)), now))
    }

  // The cycle records a write only once the write is committed: a transaction that fails leaves no created item or lineage member behind.
  private def stamp(tx: LedgerTransaction, record: DriverRecord, cycle: CycleRecord, created: List[ItemId], parent: LineageMember, stamps: List[LineageMember], now: Long): Unit = {
    val added = stamps.filterNot(member => cycle.lineage.exists(_.member == member)).map(LineageEntry(_, Some(parent), true))
    if (cycle.lineage.size + added.size > DriverPolicy.MaxLineage)
      registry.fail(record, s"cycle ${cycle.number} exceeds ${DriverPolicy.MaxLineage} lineage members", now)
    tx.afterCommit(() => registry.update(record.project, record.key) { current =>
      current.cycle.filter(_.id == cycle.id).fold(current) { live =>
        val entries = added.filterNot(entry => live.lineage.exists(_.member == entry.member))
        current.copy(cycle = Some(live.copy(created = live.created ++ created, lineage = live.lineage ++ entries)), touchedAt = now)
      }
    })
  }
}
