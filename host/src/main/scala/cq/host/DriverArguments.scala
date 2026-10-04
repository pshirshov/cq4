package cq.host

import cq.api.*
import cq.core.{DomainFailure, LedgerPolicy, WorksetTraversal}
import java.util.UUID
import scala.util.Try

/** The drive command's argument text: `<target IDs> through=<phase>` or `workset=<id>`. */
object DriverArguments {
  val Targets: WorkflowOption = WorkflowOption("targets", "", "IDS", "Item IDs separated by spaces or commas", Nil)
  val Through: WorkflowOption = WorkflowOption("through", "through=", "PHASE", "Last phase to advance through", WorkflowPhase.all.map(_.toString.toLowerCase))
  val Workset: WorkflowOption = WorkflowOption("workset", "workset=", "UUID", "An existing stored workset", Nil)
  val arguments: List[WorkflowArgument] = List(
    WorkflowArgument(Targets, false, Some("Required with through; at least one item ID. Omit when using workset.")),
    WorkflowArgument(Through, false, Some("Required with targets. Omit when using workset.")),
    WorkflowArgument(Workset, false, Some("Alternative to targets and through; must stand alone.")),
  )
  private val MaxText = 4096

  private def invalid(message: String): Nothing = throw DomainFailure(Fault.Invalid(message))

  def parse(project: ProjectId, text: String): WorksetTarget = {
    if (text.length > MaxText) invalid(s"Drive arguments exceed $MaxText characters")
    val words = text.split("[\\s,]+").toList.filter(_.nonEmpty)
    val (worksets, others) = words.partition(_.startsWith(Workset.flag))
    val (phases, references) = others.partition(_.startsWith(Through.flag))
    if (worksets.nonEmpty) {
      if (worksets.size != 1 || others.nonEmpty) invalid("workset=<id> stands alone; park and drive again to change targets or phase")
      val id = worksets.head.stripPrefix(Workset.flag)
      WorksetTarget.Stored(WorksetId(Try(UUID.fromString(id)).filter(_.toString == id.toLowerCase).getOrElse(invalid(s"Unknown workset ID $id"))))
    } else {
      if (references.isEmpty) invalid("Drive targets are empty; name at least one item ID. Empty targets never mean the whole project")
      if (references.size > WorksetTraversal.MaxRoots) invalid(s"A workset accepts at most ${WorksetTraversal.MaxRoots} targets")
      val targets = references.map { value =>
        val ledger = Ledger.all.toList.find(ledger => value.matches(LedgerPolicy.prefix(ledger) + "[1-9][0-9]{0,17}"))
          .getOrElse(invalid(s"Unknown item ID $value"))
        ItemId(project, ledger, value.drop(LedgerPolicy.prefix(ledger).length).toLong)
      }
      if (targets.distinct.size != targets.size) invalid("Drive targets repeat an item ID")
      if (phases.size != 1) invalid("Drive requires exactly one through=<phase>: explore, plan, work, review or integrate")
      val name = phases.head.stripPrefix(Through.flag)
      val phase = WorkflowPhase.all.find(_.toString.toLowerCase == name)
        .getOrElse(invalid(s"Unknown through phase $name; expected explore, plan, work, review or integrate"))
      WorksetTarget.Inline(targets.toSet, phase)
    }
  }
}
