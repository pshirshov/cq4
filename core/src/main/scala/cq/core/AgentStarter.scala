package cq.core

import cq.api.*

/**
 * A configuration text to start from, derived from the harness entries of a settings file. The planner role runs the frontier tier of
 * the governing harness, the worker its standard tier and the explorer its fast tier. A harness whose settings entry names a model of
 * a known lineup, by a model name of that lineup without an effort, gets the lineup as its tiers; the lineup of Pi is that of Codex and
 * holds for the provider `openai-codex` alone. Any other harness runs its settings model in every tier, since nothing here knows the
 * other models of its provider. A review goes to the other harnesses of the settings first, in their order and at their frontier
 * tier, and to the governing harness only when those abstain; with one harness in the settings there is no other, and every review is
 * a self-review. An operator edits the text from there. It is written on request only; no start reads the settings file for a model.
 */
object AgentStarter {
  private final case class TierModel(model: String, effort: Option[Effort])
  private final case class Lineup(frontier: TierModel, standard: TierModel, fast: TierModel) {
    def models: List[TierModel] = List(frontier, standard, fast)
    def of(tier: ModelTier): TierModel = tier match {
      case ModelTier.Frontier => frontier
      case ModelTier.Standard => standard
      case ModelTier.Fast => fast
    }
  }

  // Starting points that an operator edits, not a catalogue that is kept current; last set 2026-10-08.
  private val ClaudeLineup = Lineup(TierModel("claude-opus-5-5", None), TierModel("claude-sonnet-5-5", None), TierModel("claude-haiku-5-5", None))
  private val CodexLineup = Lineup(TierModel("gpt-6.1-sol", Some(Effort.High)), TierModel("gpt-6.1-sol", None), TierModel("gpt-6-luna", Some(Effort.Medium)))
  private val PiCodexProvider = "openai-codex"

  private def lineup(setting: HarnessSetting): Option[Lineup] = (setting.harness match {
    case Harness.Claude => Some(ClaudeLineup)
    case Harness.Codex => Some(CodexLineup)
    case Harness.Pi => Option.when(setting.provider == PiCodexProvider)(CodexLineup)
  }).filter(_.models.exists(_.model == setting.model))

  private def harness(value: Harness): String = AgentReferenceText.text(AgentReferenceText.Harnesses, value)
  private def tier(value: ModelTier): String = AgentReferenceText.text(AgentReferenceText.Tiers, value)
  private def role(value: AgentRole): String = AgentReferenceText.text(AgentReferenceText.Roles, value)
  private def at(reference: String, value: ModelTier): String = s"$reference:@${tier(value)}"

  // A Pi model is written with its provider and a Claude model without one; a Codex model takes the provider of its settings entry.
  private def entry(setting: HarnessSetting, value: TierModel): String =
    AgentReferenceText.name(Option.when(setting.harness == Harness.Pi)(setting.provider), value.model) + AgentReferenceText.effort(value.effort)

  private def aligned(indent: String, entries: List[(String, String)]): List[String] = {
    val width = entries.map(_._1.length).max + 1
    entries.map { case (key, value) => s"$indent${(key + ":").padTo(width, ' ')} $value" }
  }

  private def assigned(value: AgentRole): ModelTier = value match {
    case AgentRole.Planner | AgentRole.Reviewer => ModelTier.Frontier
    case AgentRole.Worker => ModelTier.Standard
    case AgentRole.Explorer => ModelTier.Fast
  }

  /** What the operator should know about the text beyond what it assigns. */
  def note(settings: List[HarnessSetting]): Option[String] = Option.when(settings.size == 1)(
    "The settings file holds one harness, so every review is a self-review by the governing harness until another harness is configured")

  def text(settings: List[HarnessSetting]): String = {
    require(settings.nonEmpty && settings.map(_.harness).distinct.size == settings.size, "The settings file names each harness once")
    val alone = settings.size == 1
    val lineups = settings.map(setting => setting -> lineup(setting)).toMap
    // Another harness reviews when the settings hold one; the reviewer role is then written for each harness and has no default.
    def reviewers(setting: HarnessSetting): List[String] = if (alone) Nil else List("    roles:",
      s"      ${role(AgentRole.Reviewer)}: { fallback: [${(settings.filterNot(_ == setting) :+ setting).map(other => at(harness(other.harness), assigned(AgentRole.Reviewer))).mkString(", ")}] }")
    def tiers(setting: HarnessSetting): List[String] =
      lineups(setting).fold(List(s"    # ${harness(setting.harness)}: the settings model in every tier; edit the tiers to use others"))(_ => Nil) ++
        List("    tiers:") ++
        aligned("      ", ModelTier.all.map(value => tier(value) -> s"[${entry(setting, lineups(setting).fold(TierModel(setting.model, None))(_.of(value)))}]"))
    // The models are said to differ only when every harness has a lineup: the tiers of any other harness are one model.
    val roles = if (lineups.values.forall(_.nonEmpty)) List(
      "# Roles, whichever harness governs: the planner thinks with the best model, the worker and",
      "# the explorer run cheaper ones.")
    else List(
      "# Roles, whichever harness governs: the planner runs its frontier tier, the worker its standard",
      "# tier and the explorer its fast tier.")
    val review = if (alone) List(roles.last, "# " + note(settings).get + ".") else List(
      roles.last + " A review goes to another harness first, so that the reviewer",
      "# is not the model that made the work, and to the governing harness only when the others abstain.")
    val names = ModelTier.all.map(tier)
    val value = (List("# Server defaults for agent models.", "#") ++ roles.init ++ review ++ List("defaults:", "  roles:") ++
      aligned("    ", AgentRole.all.filter(value => alone || value != AgentRole.Reviewer).map(value => role(value) -> at(AgentReferenceText.Governing, assigned(value)))) ++
      List("", s"# What ${names.init.mkString(", ")} and ${names.last} mean for each harness${if (alone) "" else ", and who reviews when it governs"}.", "harnesses:") ++
      settings.flatMap(setting => List(s"  ${harness(setting.harness)}:") ++ tiers(setting) ++ reviewers(setting))).mkString("", "\n", "\n")
    // What is written must be what a session of each of these harnesses can start.
    val parsed = AgentConfigText.parse(value).fold(problems => throw new IllegalArgumentException(
      "The settings file yields no valid agent configuration: " + problems.map(AgentConfigText.describe).mkString("; ")), identity)
    val unresolved = for {
      setting <- settings
      role <- AgentRole.all
      work <- RoleKeys.works(role)
      problem <- AgentResolution.resolve(parsed, ParsedAgents.empty, setting.harness, work).resolution match {
        case RoleResolution.Unresolved(_, problems) => problems
        case _: RoleResolution.Resolved => Nil
      }
    } yield s"${harness(setting.harness)}: ${AgentConfigText.describe(problem)}"
    require(unresolved.isEmpty, "The settings file yields no usable agent configuration: " + unresolved.distinct.mkString("; "))
    value
  }
}
