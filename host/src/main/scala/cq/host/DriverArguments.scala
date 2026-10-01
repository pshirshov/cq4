package cq.host

import cq.api.*
import cq.core.{DomainFailure, LedgerPolicy, WorksetTraversal}
import java.util.UUID
import scala.util.Try

/** The drive command's argument text: `<target IDs> through=<phase>` or `workset=<id>`. */
object DriverArguments {
  private val Workset = "workset="
  private val Through = "through="
  private val MaxText = 4096

  private def invalid(message: String): Nothing = throw DomainFailure(Fault.Invalid(message))

  def parse(project: ProjectId, text: String): WorksetTarget = {
    if (text.length > MaxText) invalid(s"Drive arguments exceed $MaxText characters")
    val words = text.split("[\\s,]+").toList.filter(_.nonEmpty)
    val (worksets, others) = words.partition(_.startsWith(Workset))
    val (phases, references) = others.partition(_.startsWith(Through))
    if (worksets.nonEmpty) {
      if (worksets.size != 1 || others.nonEmpty) invalid("workset=<id> stands alone; park and drive again to change targets or phase")
      val id = worksets.head.stripPrefix(Workset)
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
      val name = phases.head.stripPrefix(Through)
      val phase = WorkflowPhase.all.find(_.toString.toLowerCase == name)
        .getOrElse(invalid(s"Unknown through phase $name; expected explore, plan, work, review or integrate"))
      WorksetTarget.Inline(targets.toSet, phase)
    }
  }
}
