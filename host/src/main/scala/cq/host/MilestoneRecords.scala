package cq.host

import cq.api.*

/** Current milestone records for work admission, read once per selection or assembly. */
final class MilestoneRecords(call: Command => Result, project: ProjectId) extends (ItemId => Item) {
  private val records = scala.collection.mutable.Map.empty[ItemId, Item]
  override def apply(id: ItemId): Item = records.getOrElseUpdate(id, call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(id)))) match {
    case Result.Detail(value) => value.item
    case _ => throw new IllegalStateException("Milestone read returned an unexpected result")
  })
}
