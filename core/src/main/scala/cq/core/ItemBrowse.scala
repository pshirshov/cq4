package cq.core

import cq.api.*

object ItemBrowse {
  final case class Key(missing: Int, text: String, number: Long, ledger: String, id: Long)

  def project(item: Item): BrowseItem = BrowseItem(LedgerPolicy.summary(item), item.draft.content match {
    case defect: Content.Defect => Some(defect.severity)
    case _ => None
  })

  def key(item: BrowseItem, field: ItemOrderField): Key = {
    val summary = item.summary
    val (text, number) = field match {
      case ItemOrderField.Id => (LedgerPolicy.prefix(summary.id.ledger), summary.id.number)
      case ItemOrderField.Type => (summary.id.ledger.toString, 0L)
      case ItemOrderField.Title => (summary.title, 0L)
      case ItemOrderField.Status => (summary.status, 0L)
      case ItemOrderField.Modified => ("", summary.updatedAt)
      case ItemOrderField.Severity => ("", item.severity.fold(0L) {
        case Severity.Critical => 0L
        case Severity.High => 1L
        case Severity.Medium => 2L
        case Severity.Low => 3L
      })
    }
    Key(if (field == ItemOrderField.Severity && item.severity.isEmpty) 1 else 0, text, number, summary.id.ledger.toString, summary.id.number)
  }

  def ordering(order: ItemOrder): Ordering[BrowseItem] = (left, right) => {
    val a = key(left, order.field); val b = key(right, order.field)
    val missing = Integer.compare(a.missing, b.missing)
    val text = SearchPrefix.ordering.compare(a.text, b.text)
    val primary = if (text != 0) text else java.lang.Long.compare(a.number, b.number)
    if (missing != 0) missing
    else if (primary != 0) { if (order.direction == SortDirection.Ascending) primary else -primary }
    else Ordering[(String, Long)].compare((a.ledger, a.id), (b.ledger, b.id))
  }
}
