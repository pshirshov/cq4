package cq.host

import cq.api.*
import cq.core.LedgerPolicy

/** Commit message of a captured candidate: the subject names the assigned members, the body their producers, trailers the provenance. */
object CandidateMessage {
  val AttemptTrailer = "CQ-Attempt"
  val IntegrationTrailer = "CQ-Integration"
  private val MaxTitle = 72
  private val MaxSubject = 200

  private def id(value: ItemId): String = LedgerPolicy.prefix(value.ledger) + value.number
  private def title(value: String): String = {
    val flat = value.trim.replaceAll("\\s+", " ")
    if (flat.length <= MaxTitle) flat else flat.take(MaxTitle - 3) + "..."
  }
  private def named(value: ItemId, known: Map[ItemId, ItemView]): String =
    known.get(value).fold(id(value))(view => id(value) + " " + title(view.item.draft.title))

  def apply(attempt: AttemptId, members: List[ItemView], context: List[ItemView], combination: Option[CombinationPlan]): String = {
    require(members.nonEmpty, "Candidate message requires assigned members")
    val known = (members ++ context).map(view => view.item.id -> view).toMap
    val joined = members.map(view => named(view.item.id, known)).mkString("; ")
    val subject = if (joined.length <= MaxSubject) joined else joined.take(MaxSubject - 3) + "..."
    val assignment = members.map { view =>
      val producers = view.refs.collect { case ItemRef(Relation.DerivedFrom, target) => named(target, known) }
      "- " + named(view.item.id, known) + (if (producers.isEmpty) "" else producers.mkString(" (derived from ", ", ", ")"))
    }
    val combined = combination.toList.flatMap(plan => List(
      s"Combines integration target ${plan.observedTarget.value} with candidate ${plan.candidate.value} after integration ${plan.request.source.value} was not applied.", ""))
    val trailers = (s"$AttemptTrailer: ${attempt.value}" :: combination.toList.map(plan => s"$IntegrationTrailer: ${plan.request.source.value}"))
    (List(subject, "") ++ combined ++ List("Assignment:") ++ assignment ++ List("") ++ trailers).mkString("\n") + "\n"
  }
}
