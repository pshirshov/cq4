package cq.core

import cq.api.*

/**
 * A configuration text to start from, derived from the harness entries of a settings file: each harness runs its settings model in
 * every tier, and the planner, worker and explorer roles run the standard tier of the governing harness. A review goes to the other
 * harnesses of the settings first, in their order, and to the governing harness only when those abstain; with one harness in the
 * settings there is no other, and every review is a self-review. An operator edits the text from there. It is written on request
 * only; no start reads the settings file for a model.
 */
object AgentStarter {
  private def harness(value: Harness): String = AgentReferenceText.text(AgentReferenceText.Harnesses, value)

  // A Pi model is written with its provider and a Claude model without one; a Codex model takes the provider of its settings entry.
  private def model(setting: HarnessSetting): String =
    AgentReferenceText.name(Option.when(setting.harness == Harness.Pi)(setting.provider), setting.model)

  private def standard(reference: String): String = s"$reference:@${AgentReferenceText.text(AgentReferenceText.Tiers, ModelTier.Standard)}"
  private def role(value: AgentRole): String = AgentReferenceText.text(AgentReferenceText.Roles, value)

  /** What the operator should know about the text beyond what it assigns. */
  def note(settings: List[HarnessSetting]): Option[String] = Option.when(settings.size == 1)(
    "The settings file holds one harness, so every review is a self-review by the governing harness until another harness is configured")

  def text(settings: List[HarnessSetting]): String = {
    require(settings.nonEmpty && settings.map(_.harness).distinct.size == settings.size, "The settings file names each harness once")
    val alone = settings.size == 1
    // Another harness reviews when the settings hold one; the reviewer role is then written for each harness and has no default.
    def reviewers(setting: HarnessSetting): List[String] = if (alone) Nil else List("    roles:",
      s"      ${role(AgentRole.Reviewer)}: { fallback: [${(settings.filterNot(_ == setting) :+ setting).map(other => standard(harness(other.harness))).mkString(", ")}] }")
    val value = (List(
      "# Starting agent configuration, derived from a settings file by cq agents init.",
      "# Each harness runs its settings model in every tier; the planner, worker and explorer run the standard tier of the governing harness.",
      if (alone) "# " + note(settings).get + "."
      else "# A review goes to the other harnesses first, in the order of the settings file, and to the governing harness when they abstain.",
      "defaults:",
      "  roles:") ++
      AgentRole.all.filter(value => alone || value != AgentRole.Reviewer).map(value => s"    ${role(value)}: ${standard(AgentReferenceText.Governing)}") ++
      List("harnesses:") ++
      settings.flatMap(setting => List(s"  ${harness(setting.harness)}:", "    tiers:") ++
        ModelTier.all.map(tier => s"      ${AgentReferenceText.text(AgentReferenceText.Tiers, tier)}: [${model(setting)}]") ++ reviewers(setting))).mkString("", "\n", "\n")
    // What is written must be what a session of each of these harnesses can start.
    val parsed = AgentConfigText.parse(value).fold(problems => throw new IllegalArgumentException(
      "The settings file yields no valid agent configuration: " + problems.map(AgentConfigText.describe).mkString("; ")), identity)
    val unresolved = for {
      setting <- settings
      role <- AgentRole.all
      problem <- AgentResolution.resolve(parsed, ParsedAgents.empty, setting.harness, role) match {
        case RoleResolution.Unresolved(_, problems) => problems
        case _: RoleResolution.Resolved => Nil
      }
    } yield s"${harness(setting.harness)}: ${AgentConfigText.describe(problem)}"
    require(unresolved.isEmpty, "The settings file yields no usable agent configuration: " + unresolved.distinct.mkString("; "))
    value
  }
}
