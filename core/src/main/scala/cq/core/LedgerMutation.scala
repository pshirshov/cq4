package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

final class LedgerMutation(terminationPlanner: TerminationPlanner, boundary: DriverBoundary) {
  import LedgerPolicy.*
  import LedgerAccess.*

  def apply(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long): ChangeAck =
    guarded(tx, scope, request, now, None, None, Nil)

  // A write that carries its driver cycle ID.
  def attributed(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long, cycle: CycleId): ChangeAck =
    guarded(tx, scope, request, now, Some(cycle), None, Nil)

  def proposal(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long, result: ArtifactId): ChangeAck =
    guarded(tx, scope, request, now, None, None, List(LineageMember.Proposal(result)))

  // Evaluates a change inside a transaction that is always rolled back; it is never a ledger write, so no driver boundary applies.
  def hypothetical(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long): ChangeAck =
    execute(tx, scope, request, now, None)

  def integrate(tx: LedgerTransaction, id: IntegrationId, now: Long): ChangeAck = {
    val record = tx.integration(id).getOrElse(throw new IllegalStateException("Integration reservation disappeared"))
    require(record.resolution == IntegrationResolution.Pending(), "Integration is not pending")
    guarded(tx, Scope(record.intent.project, record.intent.owner), record.intent.change, now, None, Some(record), List(LineageMember.Integration(id)))
  }

  // A committed request replays its acknowledgement without writing, so only a new write meets the driver boundary.
  private def guarded(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long, cycle: Option[CycleId],
    reservation: Option[IntegrationRecord], stamps: List[LineageMember]): ChangeAck = {
    write(scope)
    if (tx.request(scope.actor, request.request).nonEmpty) execute(tx, scope, request, now, reservation)
    else boundary.admit(scope.project, scope.actor.session, cycle, reservation.map(_.intent.id), now) match {
      case None => execute(tx, scope, request, now, reservation)
      case Some(attribution) =>
        boundary.check(tx, attribution, request, now)
        val acknowledgement = execute(tx, scope, request, now, reservation)
        boundary.verify(tx, attribution, request, acknowledgement, LineageMember.Change(request.request) :: stamps, now)
        acknowledgement
    }
  }

  private def execute(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long, reservation: Option[IntegrationRecord]): ChangeAck = {
    write(scope)
    invalid(request.mutations.nonEmpty && request.mutations.size <= MaxBatch, s"Mutation batch must contain 1–$MaxBatch operations")
    invalid(request.reason.trim.nonEmpty, "Mutation reason required")
    invalid(request.reason.length <= MaxTitle, s"Mutation reason must be at most $MaxTitle characters; received ${request.reason.length}")
    val wire = ChangeRequest_JsonCodec.encode(BaboonCodecContext.Default, request).noSpaces
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    tx.request(scope.actor, request.request) match {
      case Some(stored) =>
        if (stored.fingerprint != fingerprint) throw DomainFailure(Fault.Conflict("Request identity reused with different content"))
        stored.acknowledgement
      case None =>
        if (reservation.isEmpty) tx.integration(IntegrationId(request.request.value))
          .filter(record => record.intent.owner == scope.actor && record.resolution == IntegrationResolution.Pending())
          .foreach(record => throw DomainFailure(Fault.IntegrationPending(record.intent.id)))
        val provenance = Provenance(scope.actor, now, request.request)
        val touched = scala.collection.mutable.LinkedHashMap.empty[ItemId, Item]
        def validateDraft(draft: ItemDraft, recorded: List[ItemDraft]): Unit = {
          validate(draft)
          LedgerPolicy.provenance(scope.actor.role, draft, recorded ++ reservation.toList.map(_ => draft))
          LedgerPolicy.recommendation(scope.actor.role, draft, recorded ++ reservation.toList.map(_ => draft))
          draft.content match {
            case review: Content.Review => review.subjects.foreach { subject =>
              required(tx, scope, subject.item)
              if (tx.historical(subject.item, subject.revision).isEmpty) throw DomainFailure(Fault.Missing("Reviewed revision does not exist"))
            }
            case _ => ()
          }
        }
        def writable(id: ItemId): Item = {
          invalid(!touched.contains(id), "An item may be changed only once in a batch")
          val item = required(tx, scope, id)
          reservation match {
            case Some(record) => require(tx.pendingIntegration(id).contains(IntegrationPolicy.hold(record.intent)), "Integration reservation membership changed")
            case None => IntegrationPolicy.unreserved(tx, Set(id)); fenced(tx, scope, id, request.fences, now)
          }
          item
        }
        def check(id: ItemId, revision: Revision): Item = {
          val item = writable(id)
          expected(item, revision)
          item
        }
        def revise(item: Item, draft: ItemDraft, restored: Option[ItemDraft]): Unit = {
          validateDraft(draft, item.draft :: restored.toList)
          invalid(ledger(draft.content) == item.id.ledger, "An item's ledger cannot change")
          if (touched.size >= MaxTouchedItems) throw DomainFailure(Fault.Limit(s"A change touches at most $MaxTouchedItems items"))
          val next = item.copy(revision = Revision(Math.addExact(item.revision.value, 1L)), draft = draft, updatedAt = now, provenance = provenance)
          tx.put(next)
          touched.update(next.id, next)
        }
        def openMilestone(item: Item): Unit = item.draft.content match {
          case milestone: Content.Milestone => LedgerPolicy.openMilestone(milestone.status, name(item.id))
          case _ => invalid(false, "PartOf target must be a milestone")
        }
        def edgeChange(edge: CanonicalEdge, present: Boolean): Boolean = {
          endpoints(edge)
          if (present && !tx.refs(edge.source).contains(ItemRef(edge.relation, edge.target))) {
            if (List(edge.source, edge.target).exists(id => tx.refs(id).size >= MaxRefs))
              throw DomainFailure(Fault.Limit(s"An item supports at most $MaxRefs incident references"))
            if (edge.relation == Relation.PartOf) {
              invalid(!tx.refs(edge.source).exists(r => r.relation == Relation.PartOf && r.target != edge.target), "An item has at most one milestone")
              if (edge.source.ledger == Ledger.Tasks) openMilestone(touched.getOrElse(edge.target, required(tx, scope, edge.target)))
            }
          }
          tx.edge(edge, present)
        }
        def create(draft: ItemDraft): Item = {
          validateDraft(draft, Nil)
          if (touched.size >= MaxTouchedItems) throw DomainFailure(Fault.Limit(s"A change touches at most $MaxTouchedItems items"))
          val id = tx.allocate(ledger(draft.content))
          val item = Item(id, Revision(1), draft, now, now, provenance)
          tx.put(item)
          touched.update(id, item)
          item
        }
        val createdMilestones = scala.collection.mutable.Map.empty[Int, Item]
        val assignedMilestones = scala.collection.mutable.Set.empty[ItemId]
        // The assigned milestone carries no expected revision: it is fenced, must be Open, and is revised once per batch.
        def assign(milestone: MilestoneRef, parent: Item): ItemId = milestone match {
          case MilestoneRef.Existing(id) =>
            if (id == parent.id) openMilestone(parent)
            else if (!assignedMilestones(id)) {
              val item = writable(id)
              openMilestone(item)
              revise(item, item.draft, None)
              assignedMilestones += id
            }
            id
          case MilestoneRef.Created(mutation) =>
            val created = createdMilestones.getOrElse(mutation,
              throw DomainFailure(Fault.Invalid("Produce milestone must reference an earlier Create of a Milestone in this batch")))
            created.draft.content match {
              case milestone: Content.Milestone => LedgerPolicy.openMilestone(milestone.status, batchMilestone(mutation))
              case _ => throw new IllegalStateException("A created milestone holds another ledger's content")
            }
            created.id
        }
        // Milestones this batch moves to Complete or Cancelled by Replace or Restore; the closure gate judges them once the batch is applied.
        val closed = scala.collection.mutable.LinkedHashSet.empty[ItemId]
        def closing(item: Item, draft: ItemDraft): Unit = if (MilestonePolicy.closes(item.draft.content, draft.content)) closed += item.id
        invalid(!request.mutations.exists(_.isInstanceOf[Mutation.Terminate]) || request.mutations.size == 1,
          "Termination must be the only mutation in its request")
        request.mutations.zipWithIndex.foreach {
          case (Mutation.Archive(members), _) =>
            invalid(members.nonEmpty && members.size <= MaxTouchedItems && members.map(_.id).distinct.size == members.size,
              s"Archive requires 1–$MaxTouchedItems distinct item revisions")
            members.foreach { member =>
              val item = check(member.id, member.revision)
              invalid(!item.draft.archived, "Archive preview contains an already archived item")
              // Bulk archival selects finished work only; a settled record is archived explicitly through a revision, never here.
              invalid(outcome(item.draft.content).terminal, "Only terminal items may be archived; unarchive an item before reopening it")
              val open = LedgerPolicy.openRelated(tx, member.id)
              invalid(open.isEmpty, s"Archive excludes ${LedgerPolicy.prefix(member.id.ledger)}${member.id.number}: related open items ${open.map(o => LedgerPolicy.prefix(o.ledger) + o.number).mkString(", ")}")
              revise(item, item.draft.copy(archived = true), None)
            }
          case (Mutation.Terminate(roots, intent, snapshot), _) =>
            if (tx.cursor != snapshot.cursor) throw DomainFailure(Fault.Conflict("Termination graph changed; review a fresh preview"))
            val preview = terminationPlanner.preview(tx, scope, roots, intent, now)
            if (preview.snapshot != snapshot) throw DomainFailure(Fault.Conflict("Termination preview changed; review a fresh preview"))
            preview.plan.integrations.headOption.foreach(value => throw DomainFailure(Fault.IntegrationPending(value.id)))
            // A milestone has a generic outcome under both intents, so its only unsupported effect is the closure gate's.
            preview.plan.entries.collectFirst {
              case TerminationEntry(item, TerminationEffect.Unsupported(reason)) if item.id.ledger == Ledger.Milestones => reason
            }.foreach(reason => throw DomainFailure(Fault.Invalid(reason)))
            if (!preview.plan.canApply) throw DomainFailure(Fault.Conflict("Termination preview has unresolved outcome or claim-owner conflicts"))
            if (request.fences.toSet != preview.plan.claims.map(_.fence).toSet)
              throw DomainFailure(Fault.StaleFence("Termination requires exactly the reviewed active claim fences"))
            preview.plan.claims.foreach { reviewed =>
              val claim = tx.claimById(reviewed.fence.claim).getOrElse(throw new IllegalStateException("Previewed claim disappeared inside transaction"))
              tx.updateClaim(claim.copy(released = true))
            }
            preview.plan.entries.foreach { entry => entry.effect match {
              case TerminationEffect.Change(status) =>
                val item = required(tx, scope, entry.item.id)
                expected(item, entry.item.revision)
                revise(item, item.draft.copy(content = terminationPlanner.applyStatus(item.draft.content, status)), None)
              case _ => ()
            }}
          case (Mutation.Produce(producer, revision, drafts, milestone), _) =>
            val parent = check(producer, revision)
            invalid(drafts.nonEmpty && drafts.size <= MaxBatch, s"Production requires 1–$MaxBatch drafts")
            val claim = tx.claim(producer).filter(ClaimPolicy.active(tx, _, now))
              .getOrElse(throw DomainFailure(Fault.StaleFence("Production requires an active producer claim")))
            if (claim.owner != scope.actor || !request.fences.contains(claim.fence))
              throw DomainFailure(Fault.StaleFence("Production requires the current producer owner and fence"))
            val target = milestone.map { value =>
              invalid(drafts.exists(draft => ledger(draft.content) == Ledger.Tasks), "A Produce milestone requires a Task draft")
              assign(value, parent)
            }
            drafts.foreach { draft =>
              val child = create(draft)
              edgeChange(canonical(child.id, Relation.DerivedFrom, producer), true)
              if (child.id.ledger == Ledger.Tasks) target.foreach(id => edgeChange(canonical(child.id, Relation.PartOf, id), true))
            }
            revise(parent, parent.draft, None)
          case (Mutation.Create(draft), index) =>
            val item = create(draft)
            if (item.id.ledger == Ledger.Milestones) createdMilestones.update(index, item)
          case (Mutation.Replace(id, revision, draft), _) =>
            val item = check(id, revision)
            closing(item, draft)
            revise(item, draft, None)
          case (Mutation.Restore(id, revision, historical, neighbors), _) =>
            val item = check(id, revision)
            val previous = tx.historical(id, historical).getOrElse(throw DomainFailure(Fault.Missing("Historical revision not found")))
            val currentRefs = tx.refs(id).toSet
            val previousRefs = previous.item.refs.toSet
            val removed = currentRefs -- previousRefs
            val added = previousRefs -- currentRefs
            val targets = (removed ++ added).map(_.target)
            invalid(neighbors.size <= MaxRefs * 2 && neighbors.map(_.id).distinct.size == neighbors.size && neighbors.map(_.id).toSet == targets,
              "Restore requires expected revisions for exactly the changed relationship endpoints")
            val checked = neighbors.map(n => check(n.id, n.revision))
            closing(item, previous.item.item.draft)
            // The restored draft is written first: a re-added PartOf is checked against the milestone as this batch leaves it.
            revise(item, previous.item.item.draft, Some(previous.item.item.draft))
            removed.foreach(ref => edgeChange(canonical(id, ref.relation, ref.target), false))
            added.foreach(ref => edgeChange(canonical(id, ref.relation, ref.target), true))
            checked.foreach(other => revise(other, other.draft, None))
          case (Mutation.Reference(source, sourceRevision, relation, target, targetRevision, present), _) =>
            val left = check(source, sourceRevision)
            val right = check(target, targetRevision)
            val edge = canonical(source, relation, target)
            if (edgeChange(edge, present)) { revise(left, left.draft, None); revise(right, right.draft, None) }
        }
        closed.foreach { id =>
          val milestone = touched(id)
          val open = tx.refs(id).collect { case ItemRef(Relation.Contains, member) if member.ledger == Ledger.Tasks => touched.getOrElse(member, required(tx, scope, member)) }
            .map(task => task.id -> status(task.draft.content)).filter((task, state) => MilestonePolicy.nonTerminal(task, state))
          milestone.draft.content match {
            case Content.Milestone(target, _) => invalid(open.isEmpty, MilestonePolicy.closure(id, target, open))
            case _ => throw new IllegalStateException("A closing milestone holds another ledger's content")
          }
        }
        val items = touched.valuesIterator.map(i => ItemRevision(i.id, i.revision)).toList
        val cursor = if (items.nonEmpty) tx.publish(request.request, items) else tx.cursor
        touched.valuesIterator.foreach { item => tx.append(HistoryEntry(ItemView(item, tx.refs(item.id)), cursor, request.reason, Item.baboonDomainVersion)) }
        val acknowledgement = ChangeAck(request.request, cursor, items)
        tx.acknowledge(scope.actor, StoredRequest(fingerprint, acknowledgement))
        acknowledgement
    }
  }
}
