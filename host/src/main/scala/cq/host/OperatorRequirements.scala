package cq.host

import cq.api.DispatchWork

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

  def delivered(work: DispatchWork, text: String): Option[String] = work match {
    case _: DispatchWork.Planner | _: DispatchWork.Worker => Some(bounded(text))
    case _: DispatchWork.Explorer | _: DispatchWork.Reviewer => None
  }
}
