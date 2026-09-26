package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import izumi.functional.bio.Error2
import java.time.Clock
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

trait LedgerService[F[_, _]] {
  def initialize(scope: Scope, name: String): F[Throwable, Project]
  def rename(scope: Scope, expected: Revision, name: String): F[Throwable, Project]
  def change(scope: Scope, request: ChangeRequest): F[Throwable, ChangeAck]
  def get(scope: Scope, id: ItemId): F[Throwable, ItemView]
  def search(scope: Scope, filter: ItemFilter, after: Option[ItemId], limit: Int): F[Throwable, ItemPage]
  def history(scope: Scope, id: ItemId, before: Revision, limit: Int): F[Throwable, HistoryPage]
  def changes(scope: Scope, after: ChangeCursor, limit: Int): F[Throwable, ChangePage]
  def acquire(scope: Scope, id: ClaimId, members: Set[ItemId], durationMillis: Long): F[Throwable, Claim]
  def renew(scope: Scope, fence: Fence, durationMillis: Long): F[Throwable, Claim]
  def release(scope: Scope, fence: Fence): F[Throwable, Claim]
}

object LedgerService {
  final class Impl[F[+_, +_]: Error2](repository: LedgerRepository[F], clock: Clock) extends LedgerService[F] {
    import LedgerPolicy.*

    private def write(scope: Scope): Unit =
      if (!Set[Role](Role.Human, Role.Governor).contains(scope.actor.role)) throw DomainFailure(Fault.Denied("Role cannot mutate ledgers or claims"))

    private def inScope(scope: Scope, id: ItemId): Unit = {
      if (scope.project != id.project) throw DomainFailure(Fault.Denied("Item belongs to another project"))
      invalid(id.number > 0, "Item number must be positive")
    }

    private def required(tx: LedgerTransaction, scope: Scope, id: ItemId): Item = {
      inScope(scope, id)
      tx.get(id).getOrElse(throw DomainFailure(Fault.Missing(s"Missing ${prefix(id.ledger)}${id.number}")))
    }

    private def expected(item: Item, revision: Revision): Unit =
      if (item.revision != revision) throw DomainFailure(Fault.Conflict(s"Expected revision ${revision.value}, actual ${item.revision.value}"))

    private def fenced(tx: LedgerTransaction, scope: Scope, item: ItemId, fences: List[Fence], now: Long): Unit = {
      val active = tx.claim(item).filter(c => !c.released && c.expiresAt > now)
      active.foreach { c =>
        if (c.owner != scope.actor || !fences.contains(c.fence)) throw DomainFailure(Fault.StaleFence("Item has an active claim; current owner and fence required"))
      }
      fences.foreach { fence =>
        val c = tx.claimById(fence.claim).getOrElse(throw DomainFailure(Fault.StaleFence("Unknown claim")))
        if (c.members.contains(item) && (c.fence != fence || c.released || c.expiresAt <= now || c.owner != scope.actor))
          throw DomainFailure(Fault.StaleFence("Claim is expired, released, replaced, or belongs to another actor"))
      }
    }

    override def initialize(scope: Scope, name: String): F[Throwable, Project] = {
      import izumi.functional.bio.{F, *}
      for {
        _ <- F.fromEither(scala.util.Try { write(scope); invalid(name.trim.nonEmpty && name.length <= MaxTitle, "Invalid project name") }.toEither)
        project <- repository.initialize(Project(scope.project, name, Revision(1), clock.millis()))
      } yield project
    }

    override def rename(scope: Scope, expected: Revision, name: String): F[Throwable, Project] = repository.transact(scope.project) { tx =>
      if (scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Project rename requires human authority"))
      invalid(name.trim.nonEmpty && name.length <= MaxTitle, "Invalid project name")
      if (tx.project.revision != expected) throw DomainFailure(Fault.Conflict("Project revision changed; refresh before renaming"))
      if (tx.project.name == name) tx.project
      else {
        val next = tx.project.copy(name = name, revision = Revision(Math.addExact(expected.value, 1L)))
        tx.renameProject(next)
        next
      }
    }

    override def change(scope: Scope, request: ChangeRequest): F[Throwable, ChangeAck] = repository.transact(scope.project) { tx =>
      write(scope)
      invalid(request.mutations.nonEmpty && request.mutations.size <= MaxBatch, s"Mutation batch must contain 1–$MaxBatch operations")
      invalid(request.reason.trim.nonEmpty && request.reason.length <= MaxTitle, "Mutation reason required")
      val wire = ChangeRequest_JsonCodec.encode(BaboonCodecContext.Default, request).noSpaces
      val fingerprint = MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
      tx.request(scope.actor, request.request) match {
        case Some(stored) =>
          if (stored.fingerprint != fingerprint) throw DomainFailure(Fault.Conflict("Request identity reused with different content"))
          stored.acknowledgement
        case None =>
          val now = clock.millis()
          val provenance = Provenance(scope.actor, now, request.request)
          val touched = scala.collection.mutable.LinkedHashMap.empty[ItemId, Item]
          def validateDraft(draft: ItemDraft, recorded: List[ItemDraft]): Unit = {
            validate(draft)
            LedgerPolicy.provenance(scope.actor.role, draft, recorded)
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
            expected(item, revision)
            fenced(tx, scope, id, request.fences, now)
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
          request.mutations.foreach {
            case Mutation.Create(draft) =>
              validateDraft(draft, Nil)
              if (touched.size >= MaxTouchedItems) throw DomainFailure(Fault.Limit(s"A change touches at most $MaxTouchedItems items"))
              val id = tx.allocate(ledger(draft.content))
              val item = Item(id, Revision(1), draft, now, now, provenance)
              tx.put(item)
              touched.update(id, item)
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

    override def get(scope: Scope, id: ItemId): F[Throwable, ItemView] = repository.transact(scope.project) { tx =>
      ItemView(required(tx, scope, id), tx.refs(id))
    }

    private def page(limit: Int): Unit = invalid(limit > 0 && limit <= MaxPage, s"Page size must be 1–$MaxPage")

    override def search(scope: Scope, filter: ItemFilter, after: Option[ItemId], limit: Int): F[Throwable, ItemPage] = repository.transact(scope.project) { tx =>
      page(limit)
      after.foreach(inScope(scope, _))
      val found = tx.scan(filter, after, limit)
      ItemPage(found.entries, tx.cursor, found.entries.lastOption.map(_.id), found.hasMore)
    }

    override def history(scope: Scope, id: ItemId, before: Revision, limit: Int): F[Throwable, HistoryPage] = repository.transact(scope.project) { tx =>
      page(limit)
      required(tx, scope, id)
      val entries = tx.history(id, before, limit)
      HistoryPage(entries.entries, entries.hasMore)
    }

    override def changes(scope: Scope, after: ChangeCursor, limit: Int): F[Throwable, ChangePage] = repository.transact(scope.project) { tx =>
      page(limit)
      if (after.value < 0 || after.value > tx.cursor.value) throw DomainFailure(Fault.Resync("Cursor outside retained stream"))
      val entries = tx.changes(after, limit)
      ChangePage(entries.entries.lastOption.map(_.cursor).getOrElse(after), entries.entries, entries.hasMore)
    }

    private def duration(value: Long): Unit = invalid(value > 0 && value <= MaxClaimMillis, s"Claim duration must be 1–$MaxClaimMillis ms")

    override def acquire(scope: Scope, id: ClaimId, members: Set[ItemId], durationMillis: Long): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      write(scope)
      duration(durationMillis)
      invalid(members.nonEmpty && members.size <= MaxBatch, "Invalid claim membership")
      val now = clock.millis()
      tx.claimById(id) match {
        case Some(c) =>
          if (c.owner != scope.actor || c.members != members) throw DomainFailure(Fault.Conflict("Claim identity reused with different scope"))
          if (c.released || c.expiresAt <= now) throw DomainFailure(Fault.StaleFence("Acquire a new claim identity after expiry or release"))
          c
        case None =>
          members.foreach { item =>
            required(tx, scope, item)
            if (tx.claim(item).exists(c => !c.released && c.expiresAt > now)) throw DomainFailure(Fault.Conflict("Claim overlaps active work"))
          }
          val claim = Claim(Fence(id, tx.nextFence()), scope.actor, members, Math.addExact(now, durationMillis), false)
          tx.saveClaim(claim)
          claim
      }
    }

    private def owned(tx: LedgerTransaction, scope: Scope, fence: Fence): Claim = {
      write(scope)
      val c = tx.claimById(fence.claim).getOrElse(throw DomainFailure(Fault.StaleFence("Unknown claim")))
      if (c.owner != scope.actor || c.fence != fence || c.expiresAt <= clock.millis()) throw DomainFailure(Fault.StaleFence("Claim no longer owned"))
      c
    }

    override def renew(scope: Scope, fence: Fence, durationMillis: Long): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      duration(durationMillis)
      val c = owned(tx, scope, fence)
      if (c.released) throw DomainFailure(Fault.StaleFence("Claim released"))
      val next = c.copy(expiresAt = Math.addExact(clock.millis(), durationMillis))
      tx.saveClaim(next)
      next
    }

    override def release(scope: Scope, fence: Fence): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      val current = owned(tx, scope, fence)
      val next = current.copy(released = true)
      if (!current.released) tx.saveClaim(next)
      next
    }
  }
}
