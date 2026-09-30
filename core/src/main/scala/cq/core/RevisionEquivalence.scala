package cq.core

import cq.api.*

// A recorded member remains the same assignment while its draft is unchanged: reference and provenance changes revise
// an item without changing its content (D80). The caller supplies the draft stored at each revision.
object RevisionEquivalence {
  def unchanged(recorded: ItemRevision, current: ItemRevision, draft: ItemRevision => ItemDraft): Boolean =
    recorded.id == current.id && (recorded.revision == current.revision || draft(recorded) == draft(current))

  def unchanged(recorded: List[ItemRevision], current: List[ItemRevision], draft: ItemRevision => ItemDraft): Boolean = {
    val currentById = current.map(ref => ref.id -> ref).toMap
    recorded.size == current.size && currentById.size == current.size &&
      recorded.forall(ref => currentById.get(ref.id).exists(unchanged(ref, _, draft)))
  }
}
