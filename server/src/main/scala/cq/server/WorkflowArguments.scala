package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import java.util.UUID

object WorkflowArguments {
  val Options: Set[String] = Set("--workflow", "--roots", "--through", "--result", "--mode", "--action")
  private val MaxRoots = 64

  def parse(project: ProjectId, options: Map[String, String]): Option[WorkflowRequest] = {
    def exact(allowed: Set[String], required: Set[String]): Unit =
      require(options.keySet.subsetOf(allowed + "--workflow") && required.subsetOf(options.keySet), "Workflow options do not match the selected command")
    def roots(value: String): Set[ItemId] = {
      val parts = value.split(",", -1).toList
      require(parts.nonEmpty && parts.size <= MaxRoots && parts.distinct == parts, "Workflow roots must contain 1–64 distinct references")
      parts.map { text =>
        val ledger = Ledger.all.toList.find(value => text.matches(LedgerPolicy.prefix(value) + "[1-9][0-9]*"))
          .getOrElse(throw new IllegalArgumentException("Invalid workflow root reference"))
        ItemId(project, ledger, text.drop(LedgerPolicy.prefix(ledger).length).toLong)
      }.toSet
    }
    def choice[A](values: Iterable[A], key: String): A = values.find(_.toString.toLowerCase == options(key))
      .getOrElse(throw new IllegalArgumentException(s"Invalid workflow value for $key"))
    options.get("--workflow").map {
      case "begin" =>
        exact(Set("--roots"), Set.empty)
        WorkflowRequest.Begin(options.get("--roots").map(roots).getOrElse(Set.empty))
      case "advance" =>
        exact(Set("--roots", "--through"), Set("--roots", "--through"))
        WorkflowRequest.Advance(roots(options("--roots")), choice(WorkflowPhase.all, "--through"))
      case "review" =>
        exact(Set("--result", "--mode"), Set("--result", "--mode"))
        WorkflowRequest.Review(ArtifactId(UUID.fromString(options("--result"))), choice(ReviewerMode.all, "--mode"))
      case "upstream" =>
        exact(Set("--roots", "--action"), Set("--roots", "--action"))
        WorkflowRequest.Upstream(roots(options("--roots")), choice(UpstreamAction.all, "--action"))
      case _ => throw new IllegalArgumentException("Unknown CQ workflow")
    }.orElse {
      require(options.isEmpty, "Workflow arguments require --workflow")
      None
    }
  }
}
