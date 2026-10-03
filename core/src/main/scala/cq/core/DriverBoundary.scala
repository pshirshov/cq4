package cq.core

import cq.api.*
import DriverRecords.*

// A ledger write attributed to an active driven cycle; `parent` is the lineage member the write was made under.
final case class WriteAttribution(key: DriverKey, cycle: CycleId, parent: LineageMember) {
  // The bound session writes under the run of its cycle; a session the cycle delegated to writes under its own lineage member.
  def bound: Boolean = parent.isInstanceOf[LineageMember.Run]
}

// The write-time boundary of driven cycles. Each call runs inside the writing transaction, so a rejection rolls the ledger back.
final class DriverBoundary(registry: DriverRegistry, planner: WorksetPlanner) {
  import DriverPolicy.{reference, references}

  private def current(project: ProjectId, attribution: WriteAttribution)(using tx: LedgerTransaction): (DriverRecord, CycleRecord) = {
    val record = registry.get(project, attribution.key).getOrElse(throw new IllegalStateException("Attributed driver disappeared inside its transaction"))
    (record, record.cycle.filter(cycle => cycle.id == attribution.cycle && cycle.active)
      .getOrElse(throw new IllegalStateException("Attributed cycle ended inside its transaction")))
  }

  // Bound-session rule: a write from an on driver's bound session belongs to its active cycle whether or not it names one.
  // A write from any other session is attributed only when it carries the ID of a cycle that delegated to it.
  // `completes` is the integration whose reserved completion the write records. One write is admitted without an active cycle and belongs
  // to none: the completion of an integration an earlier drive of the bound session left unsettled, while the start directive is pending.
  // The attached host starts no workflow before that integration settles, so the cycle could not start otherwise.
  def admit(tx: LedgerTransaction, project: ProjectId, session: SessionId, explicit: Option[CycleId], completes: Option[IntegrationId], now: Long): Option[WriteAttribution] = {
    given LedgerTransaction = tx
    registry.bound(project, session) match {
      case Some(record) if explicit.isEmpty && completes.exists(record.carries(session, _)) => None
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

  }

  // The Task and the milestone of a membership a Reference adds (`present`) or removes, in either direction the edge may be written.
  private def membership(mutation: Mutation, present: Boolean): Option[(ItemId, ItemId)] = (mutation match {
    case Mutation.Reference(source, _, Relation.PartOf, target, _, `present`) => Some(source -> target)
    case Mutation.Reference(source, _, Relation.Contains, target, _, `present`) => Some(target -> source)
    case _ => None
  }).filter((task, _) => task.ledger == Ledger.Tasks)

  // The existing milestone a mutation assigns Tasks to. A drive plans Tasks under milestones that workset traversal never selects.
  private def assigned(mutation: Mutation): Option[ItemId] = mutation match {
    case Mutation.Produce(_, _, _, milestone) => milestone.collect { case MilestoneRef.Existing(id) => id }
    case other => membership(other, true).map((_, milestone) => milestone)
  }

  // The milestone a Reference takes a Task out of. Work admission refuses a Task under a milestone that is not Open, and a Task has one
  // milestone, so a drive that does not select such a milestone reassigns the Task: this removal, then an assignment.
  private def released(mutation: Mutation): Option[ItemId] = membership(mutation, false).map((_, milestone) => milestone)

  // The item and the Defect of a BlockedBy edge a Reference adds, in either direction the edge may be written. A drive that meets a
  // defect it does not work records it and links the items it blocks, which leave the ready set until someone else resolves the Defect.
  private def blocking(mutation: Mutation): Option[(ItemId, ItemId)] = (mutation match {
    case Mutation.Reference(source, _, Relation.BlockedBy, target, _, true) => Some(source -> target)
    case Mutation.Reference(source, _, Relation.Blocks, target, _, true) => Some(target -> source)
    case _ => None
  }).filter((_, prerequisite) => prerequisite.ledger == Ledger.Defects)
  private def blocker(mutation: Mutation): Option[ItemId] = blocking(mutation).map((_, defect) => defect)
  private def openDefect(tx: LedgerTransaction, id: ItemId): Boolean = tx.get(id).map(_.draft.content).exists {
    case value: Content.Defect => value.status == DefectStatus.Open
    case _ => false
  }

  // A membership change names its Task, and a blocking link the item it blocks. The other endpoint is judged in `check`: a milestone by
  // status, Open to gain a member and not Open to lose one; a blocking Defect by being Open and linked by the bound session.
  private def existing(mutation: Mutation): List[ItemId] = mutation match {
    case Mutation.Archive(members) => members.map(_.id)
    case Mutation.Produce(producer, _, _, _) => List(producer)
    case Mutation.Terminate(roots, _, _) => roots.toList
    case _: Mutation.Create => Nil
    case Mutation.Replace(id, _, _) => List(id)
    case Mutation.Reference(source, _, _, target, _, present) =>
      membership(mutation, present).orElse(blocking(mutation)).fold(List(source, target))((item, _) => List(item))
    case Mutation.Restore(id, _, _, neighbors) => id :: neighbors.map(_.id)
  }

  private def milestone(tx: LedgerTransaction, id: ItemId): Option[MilestoneStatus] = tx.get(id).map(_.draft.content).collect {
    case value: Content.Milestone => value.status
  }
  private def openMilestone(tx: LedgerTransaction, id: ItemId): Boolean = milestone(tx, id).contains(MilestoneStatus.Open)
  private def closedMilestone(tx: LedgerTransaction, id: ItemId): Boolean = milestone(tx, id).exists(_ != MilestoneStatus.Open)

  private def question(item: Item): Content.Question = item.draft.content match {
    case value: Content.Question => value
    case other => throw new IllegalStateException(s"Question ${reference(item.id)} holds ${other.getClass.getSimpleName}")
  }

  // The item as the applied write left it, and as it was before the write unless the write created it.
  private def written(tx: LedgerTransaction, revision: ItemRevision): (Option[Item], Item) = {
    val after = tx.get(revision.id).getOrElse(throw new IllegalStateException(s"Written ${reference(revision.id)} disappeared inside its transaction"))
    val before = if (revision.revision == Revision(1)) None
      else Some(tx.historical(revision.id, Revision(revision.revision.value - 1))
        .getOrElse(throw new IllegalStateException(s"Written ${reference(revision.id)} has no previous revision")).item.item)
    (before, after)
  }

  // Whether the applied write made the Question Answered or changed its answer.
  private def answers(before: Option[Item], after: Item): Boolean = {
    val (previous, current) = (before.map(question), question(after))
    (current.status == QuestionStatus.Answered && !previous.exists(_.status == QuestionStatus.Answered)) || current.answer != previous.flatMap(_.answer)
  }

  // Whether the item awaits the user, by the predicate the stop decision uses: an unarchived Open Question or Requested Operator Action.
  private def waiting(item: Item): Boolean = DriverPolicy.awaitsUser(item.id.ledger, LedgerPolicy.status(item.draft.content), item.draft.archived)

  // Whether the applied write ended the item's wait for the user: by Replace, Restore or Cancel termination, whatever status it leaves.
  // For a Question that is a withdrawal, since an answer is refused first; for an Operator Action it is any exit from Requested.
  private def settles(before: Option[Item], after: Item): Boolean = before.exists(waiting) && !waiting(after)

  // Admission, before the write is applied: every existing item the request names is in the cycle's stored snapshot or was created by the cycle.
  // The exceptions are the milestone of an assignment, which may be any Open milestone, the milestone a Reference takes an in-set
  // Task out of, which may be any Complete or Cancelled milestone, and the Defect the bound session links an in-set item BlockedBy, which
  // may be any Open Defect. None admits another change of that item: its Replace, Restore, Archive or Terminate names it, and so does the
  // removal of a membership in an Open milestone or of a blocking link.
  def check(tx: LedgerTransaction, attribution: WriteAttribution, request: ChangeRequest, now: Long): Unit = {
    given LedgerTransaction = tx
    val (record, cycle) = current(tx.project.id, attribution)
    val named = request.mutations.flatMap(existing) ++ request.mutations.flatMap(assigned).filterNot(openMilestone(tx, _)) ++
      request.mutations.flatMap(released).filterNot(closedMilestone(tx, _)) ++
      request.mutations.flatMap(blocker).filterNot(defect => attribution.bound && openDefect(tx, defect))
    val outside = named.filterNot(cycle.boundary).distinct
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
  }

  // After the write is applied and before it commits: the items it actually touched are in the boundary, every item it created is selected by
  // roots-bound enumeration of the frozen targets on the resulting state, and the write is stamped with the cycle.
  def verify(tx: LedgerTransaction, attribution: WriteAttribution, request: ChangeRequest, acknowledgement: ChangeAck, stamps: List[LineageMember], now: Long): Unit = {
    given LedgerTransaction = tx
    val (record, cycle) = current(tx.project.id, attribution)
    val (created, changed) = acknowledgement.items.partition(_.revision == Revision(1))
    // A batch changes an item once, so a milestone that passed `check` only as an assignment or as a removal was revised for that one
    // membership alone, with its draft unchanged, and a Defect that passed it only as a blocker for that one link alone.
    val linked = request.mutations.flatMap(mutation => assigned(mutation) ++ released(mutation) ++ blocker(mutation)).toSet
    val outside = changed.map(_.id).filterNot(id => cycle.boundary(id) || linked(id))
    if (outside.nonEmpty)
      registry.fail(record, s"out-of-set change: ${references(outside)} is outside the advanceable set stored for cycle ${cycle.number}", now)
    // Only a person settles a Question, by an answer or by withdrawing it. The refusal leaves the driver on: the operator parks, the item is
    // settled by hand, and the drive starts again.
    val questions = acknowledgement.items.filter(_.id.ledger == Ledger.Questions).map(item => item.id -> written(tx, item))
    val answered = questions.collect { case (id, (before, after)) if answers(before, after) => id }
    if (answered.nonEmpty) throw DomainFailure(Fault.Denied(DriverPolicy.answerRefused(answered)))
    val withdrawn = questions.collect { case (id, (before, after)) if settles(before, after) => id }
    if (withdrawn.nonEmpty) throw DomainFailure(Fault.Denied(DriverPolicy.withdrawalRefused(withdrawn)))
    // Nor does a driven write end an Operator Action's wait for the operator: a move out of Requested to any status is an inferred approval
    // or an inferred refusal. An action the write creates never waited, and one already Confirmed no longer waits, so both stay writable.
    val settled = acknowledgement.items.filter(_.id.ledger == Ledger.OperatorActions).map(item => item.id -> written(tx, item))
      .collect { case (id, (before, after)) if settles(before, after) => id }
    if (settled.nonEmpty) throw DomainFailure(Fault.Denied(DriverPolicy.settlementRefused(settled)))
    val owned = if (created.isEmpty) Nil else {
      val selected = try planner.evaluate(tx, record.targets, record.through, record.workset).advanceable.map(_.item.id).toSet catch {
        case DomainFailure(fault) => registry.fail(record, s"the advanceable set cannot be recomputed after the write: $fault", now)
      }
      val produced = created.map(_.id).filter(selected).toSet
      // A Milestone created for the Tasks this write produced is reached from them as context, never selected.
      def planned(id: ItemId): Boolean = id.ledger == Ledger.Milestones && tx.refs(id).exists(ref => ref.relation == Relation.Contains && produced(ref.target))
      // A Defect the bound session records with a plain Create reports a problem the drive does not work. It is admitted and stays out of
      // the cycle's created items, so no boundary of this drive contains it and no later driven write may change it.
      def reported(id: ItemId): Boolean = attribution.bound && id.ledger == Ledger.Defects
      val (inside, others) = created.map(_.id).partition(id => selected(id) || planned(id))
      val unselected = others.filterNot(reported)
      if (unselected.nonEmpty)
        registry.fail(record, s"non-selected creation: ${references(unselected)} is not a selected descendant of ${references(record.targets)}", now)
      inside
    }
    stamp(tx, record, cycle, owned, attribution.parent, stamps, now)
  }

  def claimed(tx: LedgerTransaction, session: SessionId, claim: ClaimId, now: Long): Unit = {
    given LedgerTransaction = tx
    registry.bound(tx.project.id, session).foreach { record =>
      record.cycle.filter(_.active).foreach(cycle => stamp(tx, record, cycle, Nil, LineageMember.Run(cycle.run.get), List(LineageMember.Claim(claim)), now))
    }

  }

  // The cycle records a write in the transaction: a transaction that fails leaves no created item or lineage member behind.
  private def stamp(tx: LedgerTransaction, record: DriverRecord, cycle: CycleRecord, created: List[ItemId], parent: LineageMember, stamps: List[LineageMember], now: Long): Unit = {
    given LedgerTransaction = tx
    val added = stamps.filterNot(member => cycle.lineage.exists(_.member == member)).map(LineageEntry(_, Some(parent), true))
    if (cycle.lineage.size + added.size > DriverPolicy.MaxLineage)
      registry.fail(record, s"cycle ${cycle.number} exceeds ${DriverPolicy.MaxLineage} lineage members", now)
    registry.update(record.project, record.key) { current =>
      current.cycle.filter(_.id == cycle.id).fold(current) { live =>
        val entries = added.filterNot(entry => live.lineage.exists(_.member == entry.member))
        current.copy(cycle = Some(live.copy(created = live.created ++ created, lineage = live.lineage ++ entries)), touchedAt = now)
      }
    }
  }
}
