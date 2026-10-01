package cq.core

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.mutable

object WorksetTraversal {
  val MaxRoots = 64
  val MaxItems = 1024
}

final case class WorksetGraph(entries: List[WorksetEntry], snapshot: WorksetSnapshot, references: Map[ItemId, List[ItemRef]])

final class WorksetTraversal {
  import LedgerPolicy.{invalid, key}
  import WorksetTraversal.*

  def collect(tx: LedgerTransaction, roots: Set[ItemId], snapshot: Option[WorksetSnapshot]): WorksetGraph = {
    def inScope(id: ItemId): Unit = {
      if (id.project != tx.project.id) throw DomainFailure(Fault.Denied("Workset item belongs to another project"))
      invalid(id.number > 0, "Item number must be positive")
    }
    invalid(roots.size <= MaxRoots, s"A workset accepts at most $MaxRoots roots")
    roots.foreach(inScope)
    val identity = tx.project.id.value.toString + roots.toList.sortBy(key).map(id => s"\n${id.ledger}/${id.number}").mkString
    val hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    val current = WorksetSnapshot(tx.cursor, hash)
    snapshot.foreach { previous =>
      invalid(previous.rootsHash == current.rootsHash, "Workset roots differ from the continuation snapshot")
      if (previous.cursor != current.cursor) throw DomainFailure(Fault.Resync("Workset changed; restart traversal"))
    }

    val visited = mutable.Set.from(roots)
    val selected = mutable.Set.from(roots)
    val pending = mutable.Queue.from(roots.toList.sortBy(key))
    val summaries = mutable.Map.empty[ItemId, ItemSummary]
    val references = mutable.Map.empty[ItemId, List[ItemRef]]
    def summary(id: ItemId): ItemSummary = summaries.getOrElseUpdate(id, tx.summary(id).getOrElse {
      if (roots.contains(id)) throw DomainFailure(Fault.Missing(s"Missing workset root ${LedgerPolicy.prefix(id.ledger)}${id.number}"))
      else throw new IllegalStateException("Workset reference target is missing")
    })
    while (pending.nonEmpty) {
      val id = pending.dequeue()
      summary(id)
      val refs = tx.refs(id)
      require(refs.size <= LedgerPolicy.MaxRefs, "Workset item exceeds the incident-reference invariant")
      references.update(id, refs)
      refs.foreach { ref =>
        require(ref.target.project == tx.project.id, "Workset reference crosses the transaction scope")
        if (visited.add(ref.target) && visited.size > MaxItems)
          throw DomainFailure(Fault.Limit(s"Workset exceeds $MaxItems selected/context items; choose narrower roots"))
        if (Set[Relation](Relation.Produces, Relation.Contains).contains(ref.relation) && selected.add(ref.target)) pending.enqueue(ref.target)
      }
    }
    visited.foreach(summary)
    val context = mutable.Map.empty[ItemId, List[WorksetReason]]
    selected.toList.sortBy(key).foreach { id =>
      references(id).foreach { ref =>
        if (!selected.contains(ref.target)) context.update(ref.target,
          context.getOrElse(ref.target, Nil) :+ WorksetReason.Context(id, ref.relation))
      }
    }
    val entries = visited.toList.sortBy(key).map { id =>
      val item = summaries(id)
      if (!selected.contains(id)) WorksetEntry(item, WorksetRole.Context, false, false, context(id))
      else {
        val state = (if (item.archived) List(WorksetReason.Archived()) else Nil) ++
          (if (item.outcome.terminal) List(WorksetReason.Terminal()) else Nil) ++
          (if (LedgerPolicy.settled(item)) List(WorksetReason.Settled()) else Nil)
        val blocked = references(id).collect {
          case ItemRef(Relation.BlockedBy, prerequisite) if !summaries(prerequisite).outcome.satisfiesDependency => WorksetReason.Blocked(prerequisite)
        }
        val shared = references(id).collect {
          case ItemRef(Relation.DerivedFrom, producer) if !selected.contains(producer) => WorksetReason.Shared(producer)
        }
        WorksetEntry(item, WorksetRole.Selected, roots.contains(id), state.isEmpty && blocked.isEmpty, state ++ blocked ++ shared)
      }
    }
    WorksetGraph(entries, current, references.toMap)
  }

  def page(tx: LedgerTransaction, roots: Set[ItemId], after: Option[ItemId], snapshot: Option[WorksetSnapshot], limit: Int): WorksetPage = {
    invalid(limit > 0 && limit <= LedgerPolicy.MaxPage, s"Page size must be 1–${LedgerPolicy.MaxPage}")
    after.foreach { id =>
      if (id.project != tx.project.id) throw DomainFailure(Fault.Denied("Workset item belongs to another project"))
      invalid(id.number > 0, "Item number must be positive")
    }
    invalid(after.isEmpty || snapshot.nonEmpty, "Workset continuation requires its snapshot")
    val graph = collect(tx, roots, snapshot)
    invalid(after.forall(id => graph.entries.exists(_.item.id == id)), "Workset continuation item is outside this selection")
    val page = ReadPage.select(graph.entries.iterator.filter(entry => after.forall(id => Ordering[(String, Long)].gt(key(entry.item.id), key(id)))), limit, WorksetEntry_JsonCodec)
    val selected = graph.entries.count(_.role == WorksetRole.Selected)
    WorksetPage(page.entries, graph.snapshot, page.entries.lastOption.map(_.item.id), page.hasMore,
      selected, graph.entries.size - selected, graph.entries.count(_.ready))
  }
}
