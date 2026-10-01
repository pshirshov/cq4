package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8

object WorksetPlanner {
  val MaxSubgraphs = 32
}

// One evaluation of a workset against a ledger transaction. The driver's per-cycle advanceable set, the operator preview and
// write-time admission checks all use `evaluate`; admission runs it inside the writing transaction to see the state after the write.
final class WorksetPlanner(traversal: WorksetTraversal) {
  import LedgerPolicy.invalid
  import WorksetPlanner.*

  def evaluate(tx: LedgerTransaction, targets: Set[ItemId], through: WorkflowPhase, workset: Option[WorksetId]): WorksetPreview = {
    invalid(targets.nonEmpty, "Workset targets must be non-empty; empty targets never select the whole project")
    val graph = traversal.collect(tx, targets, None)
    val (selected, context) = graph.entries.partition(_.role == WorksetRole.Selected)
    val preview = WorksetPreview(workset, targets, through, graph.snapshot,
      selected.map(entry => WorksetMember(entry.item, entry.root)),
      context.map(entry => WorksetContextItem(entry.item, entry.reasons)),
      selected.map(entry => WorksetReadiness(entry.item.id, entry.ready, entry.reasons)))
    val bytes = WorksetPreview_JsonCodec.encode(BaboonCodecContext.Default, preview).noSpaces.getBytes(UTF_8).length
    if (bytes > ReadPage.MaxBytes) throw DomainFailure(Fault.Limit(s"Workset preview exceeds ${ReadPage.MaxBytes} bytes; choose narrower targets"))
    preview
  }

  def stored(tx: LedgerTransaction, id: WorksetId): StoredWorkset =
    tx.workset(id).getOrElse(throw DomainFailure(Fault.Missing(s"Missing workset ${id.value}")))

  def resolve(tx: LedgerTransaction, target: WorksetTarget): WorksetPreview = target match {
    case WorksetTarget.Stored(id) =>
      val workset = stored(tx, id)
      evaluate(tx, workset.targets, workset.through, Some(id))
    case WorksetTarget.Inline(targets, through) => evaluate(tx, targets, through, None)
  }

  def create(tx: LedgerTransaction, actor: Actor, id: WorksetId, targets: Set[ItemId], through: WorkflowPhase, now: Long): StoredWorkset = {
    evaluate(tx, targets, through, Some(id))
    val workset = StoredWorkset(id, targets, through, actor, now)
    tx.insertWorkset(workset)
    workset
  }

  // Candidate roots are open items that no open producer or containing milestone selects; each carries its descendant summary.
  def discover(tx: LedgerTransaction, after: Option[ItemId], limit: Int): SubgraphPage = {
    invalid(limit > 0 && limit <= MaxSubgraphs, s"Subgraph page size must be 1–$MaxSubgraphs")
    after.foreach { id =>
      if (id.project != tx.project.id) throw DomainFailure(Fault.Denied("Subgraph continuation belongs to another project"))
      invalid(id.number > 0, "Item number must be positive")
    }
    val roots = tx.candidateRoots(after, limit)
    val entries = roots.entries.map { root =>
      val extent = try {
        val graph = traversal.collect(tx, Set(root.id), None)
        val descendants = graph.entries.filter(entry => entry.role == WorksetRole.Selected && !entry.root)
        val ledgers = descendants.groupMapReduce(_.item.id.ledger)(_ => 1L)(_ + _)
        SubgraphExtent.Measured(descendants.size, descendants.count(_.ready), graph.entries.count(_.role == WorksetRole.Context),
          Ledger.all.filter(ledgers.contains).map(ledger => LedgerCount(ledger, ledgers(ledger))))
      } catch {
        case DomainFailure(_: Fault.Limit) => SubgraphExtent.Oversized(WorksetTraversal.MaxItems)
      }
      Subgraph(root, extent)
    }
    SubgraphPage(entries, tx.cursor, roots.entries.lastOption.map(_.id), roots.hasMore)
  }
}
