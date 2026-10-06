package cq.host

import cq.api.*
import cq.core.ProcessModePolicy

/** What is said about one process mode: `hint` is the line beside its option, `description` its Help text, and `instructions` the
  * classpath resource of the section that opens a governing session's workflow instructions. */
final case class ProcessModeEntry(mode: ProcessMode, label: String, hint: String, description: String, instructions: String)

/** The one description of the process modes: Help, the mode dialog and the governing instructions all read it. */
object ProcessModes {
  private val Resources = "cq/workflows"

  private val Shared = "The roots and the phase limit of a request bound the work in every mode, and capturing intake never starts implementation. " +
    "Every piece of work has an item with acceptance criteria under an Open milestone, the configured checks run on every candidate, " +
    "only the host integrates, and a Task becomes Done only by recorded integration."

  private def entry(mode: ProcessMode, name: String, hint: String, description: String): ProcessModeEntry =
    ProcessModeEntry(mode, ProcessModePolicy.label(mode), hint, description, s"$Resources/mode-$name.md")

  val all: List[ProcessModeEntry] = List(
    entry(ProcessMode.Rigorous, "rigorous",
      "A Planner plans and a Plan review approves before work starts; phases run in order; every candidate is independently reviewed.",
      "The full process. Goals, Tasks and Milestones are proposed by a Planner and applied only after an independent Plan review. " +
        "Work passes through the phases in order up to the requested limit. Every change is made by a Worker in an isolated workspace, " +
        "checked by the host, reviewed by an independent Reviewer and integrated by the host. " + Shared),
    entry(ProcessMode.CrossCutting, "crosscutting",
      "The Governor may plan itself, write the acceptance criteria on the items and take them in any order; isolated Workers, independent review, host checks and host integration stay.",
      "For changes that cut across many items, where a Planner round for each would cost more than it gives. " +
        "The Governor may skip the Planner and the Plan review, write each Task and its acceptance criteria itself before a Worker starts, " +
        "create a Milestone when no Open one fits, and take the selected items and phases in any order within the requested limit. " +
        "Every change is still made by a Worker in an isolated workspace, checked by the host, reviewed by an independent Reviewer and integrated by the host, " +
        "and the Governor keeps the items and ledgers current. Memories still require a reviewed proposal. " + Shared),
    entry(ProcessMode.Yolo, "yolo",
      "As Cross-cutting, and the Governor may also make a change itself and review any candidate itself; host checks and host integration stay.",
      "Cross-cutting with the two remaining safeguards of authorship relaxed. An interactive Governor may implement a Task itself in an isolated workspace the host gives it, " +
        "and may review any candidate itself, its own or a Worker's, instead of dispatching an independent Reviewer. " +
        "The ledger records each such integration as self-reviewed. No second agent has then read the change: the configured checks are the only independent gate, " +
        "so a self-reviewed integration requires at least one configured check unless the operator exempts the project. " +
        "A batch run in such a project works as in Cross-cutting. " + Shared),
  )

  /** When a change of a project's mode reaches its sessions. */
  val Effect: String = "A change applies from the next workflow activation of a session: a workflow that is already active keeps the mode it started with, " +
    "and a drive takes the new mode with its next cycle."

  /** The project's mode as the server holds it now. `call` raises a failed read, so no workflow is assembled without it. */
  def current(call: Command => Result, project: ProjectId): ProcessMode = setting(call, project).value

  /** The mode with the exemption stored beside it, as the server's rules for a self-review read them. */
  def setting(call: Command => Result, project: ProjectId): ProjectSetting.Mode =
    call(Command.Mode(ModeInput(project, ModeAction.Read()))) match {
      case Result.Mode(value) => ProjectSetting.Mode(value.mode, value.selfReviewWithoutChecks)
      case _ => throw new IllegalStateException("Process mode read returned an unexpected result")
    }

  // Only an attached session implements and reviews itself: the host launches a batch Governor without edit tools, so it works a YOLO
  // project as a Cross-cutting one.
  def governing(mode: ProcessMode, ownership: SessionOwnership): ProcessMode = (mode, ownership) match {
    case (ProcessMode.Yolo, SessionOwnership.Managed) => ProcessMode.CrossCutting
    case _ => mode
  }

  def of(mode: ProcessMode): ProcessModeEntry = all.find(_.mode == mode)
    .getOrElse(throw new IllegalStateException(s"Process mode $mode is missing from the catalog"))
}
