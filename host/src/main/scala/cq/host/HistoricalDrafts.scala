package cq.host

import cq.api.*
import cq.core.RevisionEquivalence

// Reads the draft recorded at an exact item revision through bounded history pages.
final class HistoricalDrafts(call: Command => Result, project: ProjectId) {
  private val cache = scala.collection.mutable.Map.empty[ItemRevision, ItemDraft]

  def draft(reference: ItemRevision): ItemDraft = cache.getOrElseUpdate(reference, {
    val before = Revision(Math.addExact(reference.revision.value, 1L))
    call(Command.Read(ReadInput(project, ReadSelection.History(reference.id, before, 1)))) match {
      case Result.History(page) =>
        val entry = page.entries.headOption.getOrElse(throw new IllegalArgumentException("Recorded member revision has no history"))
        require(entry.item.item.id == reference.id && entry.item.item.revision == reference.revision, "Recorded member revision has no history")
        entry.item.item.draft
      case _ => throw new IllegalStateException("Member history read returned an unexpected result")
    }
  })

  def unchanged(recorded: ItemRevision, current: ItemRevision): Boolean = RevisionEquivalence.unchanged(recorded, current, draft)
  def unchanged(recorded: List[ItemRevision], current: List[ItemRevision]): Boolean = RevisionEquivalence.unchanged(recorded, current, draft)
}
