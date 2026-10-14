package cq.core

import cq.api.*

/** The registration of a span of the phase `Await`, which is observed more than once while it runs. The rules apply in this order:
  * a registration whose fixed fields (assignment, session, phase, awaited item, parent, start time) differ from the stored span is refused;
  * the same observation number returns the stored span for identical content and is a conflict for other content; a lower number
  * returns the stored span; a higher number replaces a Running span whose finish time it does not lower, whose blocked items it keeps
  * and whose truncation flag it does not clear, and is refused otherwise or when the stored span has ended. */
object AwaitedSpans {
  /** The most blocked items a wait names. */
  val MaxBlocked = 200

  sealed trait Admission
  /** The value is the first registration or replaces the stored span. */
  case object Store extends Admission
  /** The stored span is the answer; nothing changes. */
  final case class Existing(span: PhaseSpan) extends Admission
  final case class Refused(fault: Fault) extends Admission

  def admit(stored: Option[PhaseSpan], value: PhaseSpan): Admission = (stored, value.await) match {
    case (None, _) => Store
    case (Some(old), Some(wait)) if old.await.isDefined =>
      val before = old.await.get
      if ((old.assignment, old.session, old.phase, before.item, old.parent, old.startedAt) != (value.assignment, value.session, value.phase, wait.item, value.parent, value.startedAt))
        Refused(Fault.Invalid("A wait's assignment, session, phase, awaited item, parent and start time cannot change"))
      else if (wait.observation == before.observation) {
        if (old == value) Existing(old) else Refused(Fault.Conflict("Observation number reused with different content"))
      } else if (wait.observation < before.observation) Existing(old)
      else if (old.state != AttemptState.Running) Refused(Fault.Invalid("A wait that has ended cannot be observed again"))
      else if (value.finishedAt < old.finishedAt || !before.blocked.subsetOf(wait.blocked) || (before.truncated && !wait.truncated))
        Refused(Fault.Invalid("A later observation does not lower the finish time, drop a blocked item or clear the truncation flag"))
      else Store
    case _ => Refused(Fault.Conflict("Span identity reused with different immutable content"))
  }
}
