package cq.core

import cq.api.*

/**
 * A configuration text to start from, derived from the harness entries of a settings file: each harness runs its settings model in
 * every tier, and every role runs the standard tier of the governing harness. It states what a session ran before roles were
 * configured, so an operator edits it from there. It is written on request only; no start reads the settings file for a model.
 */
object AgentStarter {
  private def harness(value: Harness): String = AgentReferenceText.text(AgentReferenceText.Harnesses, value)

  // A Pi model is written with its provider and a Claude model without one; a Codex model takes the provider of its settings entry.
  private def model(setting: HarnessSetting): String =
    AgentReferenceText.name(Option.when(setting.harness == Harness.Pi)(setting.provider), setting.model)

  def text(settings: List[HarnessSetting]): String = {
    require(settings.nonEmpty && settings.map(_.harness).distinct.size == settings.size, "The settings file names each harness once")
    val value = (List(
      "# Starting agent configuration, derived from a settings file by cq agents init.",
      "# Each harness runs its settings model in every tier; every role runs the standard tier of the governing harness.",
      "defaults:",
      "  roles:") ++
      AgentRole.all.map(role => s"    ${AgentReferenceText.text(AgentReferenceText.Roles, role)}: ${AgentReferenceText.Governing}:@${AgentReferenceText.text(AgentReferenceText.Tiers, ModelTier.Standard)}") ++
      List("harnesses:") ++
      settings.flatMap(setting => List(s"  ${harness(setting.harness)}:", "    tiers:") ++
        ModelTier.all.map(tier => s"      ${AgentReferenceText.text(AgentReferenceText.Tiers, tier)}: [${model(setting)}]"))).mkString("", "\n", "\n")
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
