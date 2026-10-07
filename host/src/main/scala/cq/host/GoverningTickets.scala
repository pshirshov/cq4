package cq.host

import cq.api.*

/** The dispatch tickets that record work of the governing session itself instead of a child: an attempt of the Governor role under
  * the governing attempt, for which the host launched no process. */
object GoverningTickets {
  def own(ticket: DispatchTicket): Boolean = ticket.attempt.role == Role.Governor && ticket.attempt.parent.nonEmpty

  /** Such a ticket opened a workspace, which no job of the session's journal names. */
  def workspace(ticket: DispatchTicket): Boolean = own(ticket) && ChildContracts.role(ticket.request.work) == Role.Worker

  /** Why the workspace of an ended session is kept: its host ended before the session submitted or cancelled it. */
  val Abandoned = "The governing session's host ended with this workspace open; its edits were not captured and are retained here"

  /** What the outcome of such an attempt says when its host ended before it published one. */
  val Interrupted = "The governing session's host ended before this work of the session was published; a workspace it left is quarantined with its edits, and nothing of it became a candidate"
}
