package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

final class LedgerMutation(terminationPlanner: TerminationPlanner) {
  import LedgerPolicy.*
  import LedgerAccess.*

  def apply(tx: LedgerTransaction, scope: Scope, request: ChangeRequest, now: Long): ChangeAck =
    execute(tx, scope, request, now, None)

  def integrate(tx: LedgerTransaction, id: IntegrationId, now: Long): ChangeAck = {
    val record = tx.integration(id).getOrElse(throw new IllegalStateException("Integration reservation disappeared"))
    require(record.resolution == IntegrationResolution.Pending(), "Integration is not pending")
    execute(tx, Scope(record.intent.project, record.intent.owner), record.intent.change, now, Some(record))
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
          draft.content match {
            case review: Content.Review => review.subjects.foreach { subject =>
              required(tx, scope, subject.item)
              if (tx.historical(subject.item, subject.revision).isEmpty) throw DomainFailure(Fault.Missing("Reviewed revision does not exist"))
            }
            case _ => ()
          }
        }
        def check(id: ItemId, revision: Revision): Item = {
          invalid(!touched.contains(id), "An item may be changed only once in a batch")
          val item = required(tx, scope, id)
          reservation match {
            case Some(record) => require(tx.pendingIntegration(id).contains(IntegrationPolicy.hold(record.intent)), "Integration reservation membership changed")
            case None => IntegrationPolicy.unreserved(tx, Set(id)); fenced(tx, scope, id, request.fences, now)
          }
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
        def edgeChange(edge: CanonicalEdge, present: Boolean): Boolean = {
          endpoints(edge)
          if (present && !tx.refs(edge.source).contains(ItemRef(edge.relation, edge.target))) {
            if (List(edge.source, edge.target).exists(id => tx.refs(id).size >= MaxRefs))
              throw DomainFailure(Fault.Limit(s"An item supports at most $MaxRefs incident references"))
            if (edge.relation == Relation.PartOf)
              invalid(!tx.refs(edge.source).exists(r => r.relation == Relation.PartOf && r.target != edge.target), "An item has at most one milestone")
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
        invalid(!request.mutations.exists(_.isInstanceOf[Mutation.Terminate]) || request.mutations.size == 1,
          "Termination must be the only mutation in its request")
        request.mutations.foreach {
          case Mutation.Archive(members) =>
            invalid(members.nonEmpty && members.size <= MaxTouchedItems && members.map(_.id).distinct.size == members.size,
              s"Archive requires 1–$MaxTouchedItems distinct item revisions")
            members.foreach { member =>
              val item = check(member.id, member.revision)
              invalid(!item.draft.archived, "Archive preview contains an already archived item")
              revise(item, item.draft.copy(archived = true), None)
            }
          case Mutation.Terminate(roots, intent, snapshot) =>
            if (tx.cursor != snapshot.cursor) throw DomainFailure(Fault.Conflict("Termination graph changed; review a fresh preview"))
            val preview = terminationPlanner.preview(tx, scope, roots, intent, now)
            if (preview.snapshot != snapshot) throw DomainFailure(Fault.Conflict("Termination preview changed; review a fresh preview"))
            preview.plan.integrations.headOption.foreach(value => throw DomainFailure(Fault.IntegrationPending(value.id)))
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
          case Mutation.Produce(producer, revision, drafts) =>
            val parent = check(producer, revision)
            invalid(drafts.nonEmpty && drafts.size <= MaxBatch, s"Production requires 1–$MaxBatch drafts")
            val claim = tx.claim(producer).filter(ClaimPolicy.active(tx, _, now))
              .getOrElse(throw DomainFailure(Fault.StaleFence("Production requires an active producer claim")))
            if (claim.owner != scope.actor || !request.fences.contains(claim.fence))
              throw DomainFailure(Fault.StaleFence("Production requires the current producer owner and fence"))
            drafts.foreach { draft =>
              val child = create(draft)
              edgeChange(canonical(child.id, Relation.DerivedFrom, producer), true)
            }
            revise(parent, parent.draft, None)
          case Mutation.Create(draft) => create(draft); ()
          case Mutation.Replace(id, revision, draft) => revise(check(id, revision), draft, None)
          case Mutation.Restore(id, revision, historical, neighbors) =>
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
            removed.foreach(ref => edgeChange(canonical(id, ref.relation, ref.target), false))
            added.foreach(ref => edgeChange(canonical(id, ref.relation, ref.target), true))
            revise(item, previous.item.item.draft, Some(previous.item.item.draft))
            checked.foreach(other => revise(other, other.draft, None))
          case Mutation.Reference(source, sourceRevision, relation, target, targetRevision, present) =>
            val left = check(source, sourceRevision)
            val right = check(target, targetRevision)
            val edge = canonical(source, relation, target)
            if (edgeChange(edge, present)) { revise(left, left.draft, None); revise(right, right.draft, None) }
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
