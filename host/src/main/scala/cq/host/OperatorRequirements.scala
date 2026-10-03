package cq.host

import cq.api.*
import cq.core.DomainFailure

/** The operator's request text for the current session: the batch input file, or the text supplied at attached workflow activation. */
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

  val StandingHeading = "Standing project requirements (set by the operator; they apply to every session of this project):"
  def governing(instructions: String, standing: String): String =
    if (standing.trim.isEmpty) instructions else StandingHeading + "\n" + standing + "\n\n" + instructions

  val SessionHeading = "Session request (the operator's request for this session):"

  /** The project's standing requirements as the server holds them now. `call` raises a failed read, so no caller proceeds without them. */
  def standing(call: Command => Result, project: ProjectId): String =
    call(Command.Requirements(RequirementsInput(project, RequirementsAction.Read()))) match {
      case Result.Requirements(value) => value.text
      case _ => throw new IllegalStateException("Standing requirements read returned an unexpected result")
    }

  // A text without content states no requirement, so it is delivered to no child: an empty section would read as a request.
  // The server bounds the standing text when it is written; with standing requirements, each part is delivered under its own heading.
  // `standing` is evaluated only for a child that receives requirements.
  def delivered(work: DispatchWork, standing: => String, session: String): Option[String] = work match {
    case _: DispatchWork.Planner | _: DispatchWork.Worker | DispatchWork.Reviewer(ReviewerMode.Plan | ReviewerMode.Candidate) =>
      val request = Option.when(session.trim.nonEmpty)(bounded(session))
      val text = standing
      if (text.trim.isEmpty) request
      else Some(StandingHeading + "\n" + text + request.fold("")(own => "\n\n" + SessionHeading + "\n" + own))
    case _: DispatchWork.Explorer | DispatchWork.Reviewer(ReviewerMode.Audit) => None
  }
}
