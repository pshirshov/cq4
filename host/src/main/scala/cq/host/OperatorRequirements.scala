package cq.host

import cq.api.{CycleToken, DispatchWork, Fault, ReviewerMode}
import cq.core.DomainFailure

/** The operator's governing request text for the current session: the batch input file, or the text supplied at attached workflow activation. */
final class OperatorRequirements(initial: String) {
  @volatile private var text: String = initial
  def current: String = text
  def replace(value: String): Unit = { text = value }
}

object OperatorRequirements {
  val MaxCodePoints = 16384

  def bounded(text: String): String = {
    val count = text.codePointCount(0, text.length)
    if (count <= MaxCodePoints) text
    else text.substring(0, text.offsetByCodePoints(0, MaxCodePoints)) +
      s"\n[operator requirements truncated by the host: $MaxCodePoints of $count code points delivered]"
  }

  val TokenLeak = "operatorRequirements carries this activation's CQ driver token; pass the token only in Workflow.token and leave the driver flags out of operatorRequirements"

  // A driver token authorizes one activation and travels only in `Workflow.token`. The requirements text is delivered to children, so a
  // text that carries the activation's token is refused before the token is presented to the server; the session can activate again without it.
  def admitted(text: String, token: Option[CycleToken]): String = {
    val value = token.map {
      case CycleToken.Start(value) => value
      case CycleToken.Resume(value) => value
    }
    if (value.exists(found => text.toLowerCase(java.util.Locale.ROOT).contains(found.value.toString))) throw DomainFailure(Fault.Invalid(TokenLeak))
    text
  }

  // A text without content states no requirement, so it is delivered to no child: an empty section would read as a request.
  def delivered(work: DispatchWork, text: String): Option[String] = work match {
    case _: DispatchWork.Planner | _: DispatchWork.Worker | DispatchWork.Reviewer(ReviewerMode.Plan | ReviewerMode.Candidate) =>
      Option.when(text.trim.nonEmpty)(bounded(text))
    case _: DispatchWork.Explorer | DispatchWork.Reviewer(ReviewerMode.Audit) => None
  }
}
