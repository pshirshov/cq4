package cq.core

import cq.api.*

/** The registration of a span of the phase Check, Integrate or Combine, which the host registers Running when the work starts and
  * finishes once. Its fixed fields are identity, assignment, session, phase, check name and start time. For a span that exists
  * already the rules apply in this order:
  *  1. a registration whose fixed fields differ from the stored span is refused;
  *  2. identical content returns the stored span (replay);
  *  3. a terminal registration for a Running span finishes it, once;
  *  4. a Running registration for a terminal span returns the stored span unchanged: a start replayed after the finish is no conflict;
  *  5. a terminal registration for a terminal span with another finish time or state is refused as a conflict.
  * Only a first registration and the finish advance the audit cursor. */
object HostSpans {
  def admit(stored: Option[PhaseSpan], value: PhaseSpan): AwaitedSpans.Admission = stored match {
    case None => AwaitedSpans.Store
    case Some(old) =>
      if ((old.assignment, old.session, old.phase, old.check, old.startedAt) != (value.assignment, value.session, value.phase, value.check, value.startedAt))
        AwaitedSpans.Refused(Fault.Invalid("A span's assignment, session, phase, check name and start time cannot change"))
      else if (old == value) AwaitedSpans.Existing(old)
      else if (old.state == AttemptState.Running && value.state != AttemptState.Running) AwaitedSpans.Store
      else if (old.state != AttemptState.Running && value.state == AttemptState.Running) AwaitedSpans.Existing(old)
      else AwaitedSpans.Refused(Fault.Conflict("Span identity reused with different immutable content"))
  }
}
