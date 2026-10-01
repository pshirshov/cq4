package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import cq.host.{WorkflowCatalog, WorkflowName, WorkflowOption}
import java.util.UUID

object WorkflowArguments {
  val Options: Set[String] = WorkflowCatalog.flags
  private val MaxRoots = 64

  def parse(project: ProjectId, options: Map[String, String]): Option[WorkflowRequest] = {
    def roots(value: String): Set[ItemId] = {
      val parts = value.split(",", -1).toList
      require(parts.nonEmpty && parts.size <= MaxRoots && parts.distinct == parts, "Workflow roots must contain 1–64 distinct references")
      parts.map { text =>
        val ledger = Ledger.all.toList.find(value => text.matches(LedgerPolicy.prefix(value) + "[1-9][0-9]*"))
          .getOrElse(throw new IllegalArgumentException("Invalid workflow root reference"))
        ItemId(project, ledger, text.drop(LedgerPolicy.prefix(ledger).length).toLong)
      }.toSet
    }
    def choice[A](values: Iterable[A], option: WorkflowOption): A = values.find(_.toString.toLowerCase == options(option.flag))
      .getOrElse(throw new IllegalArgumentException(s"Invalid workflow value for ${option.flag}"))
    options.get(WorkflowCatalog.WorkflowFlag).map { name =>
      val command = WorkflowCatalog.named(name).getOrElse(throw new IllegalArgumentException("Unknown CQ workflow"))
      val allowed = command.arguments.map(_.option.flag).toSet
      val required = command.arguments.filter(_.required).map(_.option.flag).toSet
      require(options.keySet.subsetOf(allowed + WorkflowCatalog.WorkflowFlag) && required.subsetOf(options.keySet),
        "Workflow options do not match the selected command")
      command.name match {
        case WorkflowName.Begin =>
          WorkflowRequest.Begin(options.get(WorkflowCatalog.Roots.flag).map(roots).getOrElse(Set.empty))
        case WorkflowName.Advance =>
          WorkflowRequest.Advance(roots(options(WorkflowCatalog.Roots.flag)), choice(WorkflowPhase.all, WorkflowCatalog.Through))
        case WorkflowName.Review =>
          WorkflowRequest.Review(ArtifactId(UUID.fromString(options(WorkflowCatalog.Result.flag))), choice(ReviewerMode.all, WorkflowCatalog.Mode))
        case WorkflowName.Upstream =>
          WorkflowRequest.Upstream(roots(options(WorkflowCatalog.Roots.flag)), choice(UpstreamAction.all, WorkflowCatalog.Action))
      }
    }.orElse {
      require(options.isEmpty, "Workflow arguments require --workflow")
      None
    }
  }
}
