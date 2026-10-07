package cq.server

import cq.api.*
import cq.core.{AgentConfigText, RoleKeys}
import cq.host.{ClaudeAdapter, HarnessProfile, HostFiles, HttpServerApi}
import java.net.URI
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import scala.util.{Failure, Success, Try}

trait AgentsReader {
  def read(config: ProjectConfig, token: String): AgentsView
}
final class HttpAgentsReader extends AgentsReader {
  private val Deadline = Duration.ofSeconds(10)
  override def read(config: ProjectConfig, token: String): AgentsView =
    new HttpServerApi(URI.create(config.endpoint), token, SessionId(UUID.randomUUID()), Deadline)
      .call(Command.Agents(AgentsInput(config.project, AgentsAction.Read()))) match {
      case Result.Agents(value) => value
      case other => throw new IllegalStateException(s"Unexpected agent configuration reply ${other.getClass.getSimpleName}")
    }
}

/**
 * Whether the agent configuration a server holds for a project can run the four subagent roles under one governing harness with
 * the harness entries of a session's settings file. It reads; it launches nothing and asks no provider about a model.
 */
final class AgentsDoctor(reader: AgentsReader) {
  private val MaxBytes = cq.core.LedgerPolicy.MaxConfigBytes
  private val Editing = "the server defaults or the project override (Agent models in the web UI; see `cq help`)"
  private def check(name: String, current: Boolean, detail: String): InstallationCheck =
    InstallationCheck(name, if (current) InstallationState.Current else InstallationState.Failed, detail)
  private def lower(value: Any): String = value.toString.toLowerCase(java.util.Locale.ROOT)
  private def layer(value: AgentLayer): String = value match {
    case AgentLayer.Installation => "server defaults"
    case AgentLayer.Project => "project override"
  }
  private def source(origin: RoleOrigin, governing: Harness): String = layer(origin.layer) + ", " + (origin.source match {
    case RoleSource.HarnessRoles => s"harnesses.${lower(governing)}.roles"
    case RoleSource.DefaultRoles => "defaults.roles"
  })

  private def document(name: String, value: AgentsDocument): InstallationCheck = value.problems match {
    case Nil => check(name, true, if (value.revision.value == 0) "Not written; revision 0" else s"Revision ${value.revision.value}; no problems")
    case problems => check(name, false, s"Revision ${value.revision.value}; ${problems.size} ${if (problems.size == 1) "problem" else "problems"} (line:column): " +
      problems.map(AgentConfigText.describe).mkString("; "))
  }

  /** What the operator sets to remove a problem that depends on the governing harness or on the other layer. */
  private def remedy(problem: AgentProblem, origin: Option[RoleOrigin]): String = problem match {
    case AgentProblem.RoleUnassigned(harness, role) =>
      s"${AgentConfigText.describe(problem)}; set defaults.roles.${lower(role)} or harnesses.${lower(harness)}.roles.${lower(role)} in $Editing, " +
        "or write a starting configuration with cq agents init --settings FILE"
    case AgentProblem.TierUndefined(harness, tier, _) =>
      s"${AgentConfigText.describe(problem)}; set harnesses.${lower(harness)}.tiers.${lower(tier)} in $Editing"
    case positioned => origin.fold("")(value => layer(value.layer) + " ") + AgentConfigText.describe(positioned)
  }

  private def role(governing: Harness, role: AgentRole, view: AgentsView): (InstallationCheck, List[ModelRoute]) = {
    val name = s"Role ${lower(role)}"
    view.assignments.find(value => value.harness == governing && RoleKeys.role(value.key) == role).map(_.resolution) match {
      case None => check(name, false, "Not resolved: a layer of the configuration has problems") -> Nil
      case Some(RoleResolution.Unresolved(origin, problems)) => check(name, false, problems.map(remedy(_, origin)).mkString("; ")) -> Nil
      case Some(RoleResolution.Resolved(plan)) =>
        val selfReview = if (plan.selfReview.isEmpty) "" else
          s"; self-review: ${if (plan.selfReview.size == 1) "seat" else "seats"} ${plan.selfReview.map(_ + 1).mkString(", ")} of ${plan.seats.size} can run a model of ${lower(governing)}, the governing harness"
        check(name, true, s"${AgentConfigText.render(plan)} (${source(plan.origin, governing)})$selfReview") -> plan.seats.flatMap(_.candidates)
    }
  }

  def inspect(governing: Harness, projectFile: Path, settingsFile: Path, environment: Map[String, String]): InstallationReport = {
    val project = Try(HostFiles.read(projectFile.toRealPath(), ProjectConfig_JsonCodec, MaxBytes))
    val token = Try(ServerCredentials.token(environment))
    val identity = List(
      check("Project", project.isSuccess, project.fold(_ => s"Project configuration $projectFile is missing or unreadable; run cq init in the checkout or name it with --directory",
        value => s"Project ${value.project.value} at ${value.endpoint}")),
      check("Credential", token.isSuccess, if (token.isSuccess) "Readable operator credential; server acceptance is checked separately"
        else "Credential is missing, unreadable or invalid; contents are not shown"))
    val checks = (for { config <- project; credential <- token } yield Try(reader.read(config, credential))) match {
      case Failure(_) => Nil
      case Success(Failure(_)) => List(check("Configuration", false,
        "Cannot read the agent configuration; verify the endpoint and project of the project file, the operator credential and server availability"))
      case Success(Success(view)) =>
        val roles = AgentRole.all.map(value => value -> role(governing, value, view))
        val used = roles.flatMap { case (value, (_, routes)) => routes.map(_ -> value) }
        check("Configuration", true, "Read from the server") :: document("Server defaults", view.installation) :: document("Project override", view.project) ::
          roles.map(_._2._1) ::: settings(settingsFile, used)
    }
    InstallationReport("agents", identity ::: checks)
  }

  /** The settings entry of every harness a resolved route runs on, and the provider a route without one takes from it. */
  private def settings(file: Path, used: List[(ModelRoute, AgentRole)]): List[InstallationCheck] = {
    val read = Try {
      val value = HostFiles.read(file.toRealPath(), SupervisorSettings_JsonCodec, MaxBytes)
      require(value.harnesses.map(_.harness).distinct.size == value.harnesses.size, "two entries name one harness")
      value
    }
    val entries = read.toOption.fold(Map.empty[Harness, HarnessSetting])(_.harnesses.map(value => value.harness -> value).toMap)
    def referrers(harness: Harness): String = used.collect { case (route, value) if route.harness == harness => lower(value) }.distinct.mkString(", ")
    val harnesses = Harness.all.filter(harness => used.exists(_._1.harness == harness))
    val entryChecks = harnesses.map { harness =>
      val name = s"Settings entry ${lower(harness)}"
      entries.get(harness) match {
        case None => check(name, false, s"${lower(harness)} is referenced by ${referrers(harness)} but not in the session settings $file")
        case Some(entry) =>
          val valid = Try(SupervisorConfig.profile(entry)).isSuccess
          val executable = Try(Files.isExecutable(Path.of(entry.executable))).getOrElse(false)
          check(name, valid && executable, if (valid && executable) s"Executable present; version ${entry.version} is package-verified; referenced by ${referrers(harness)}"
            else if (!valid) s"${lower(harness)} is referenced by ${referrers(harness)}, and its entry in $file does not declare a package-verified version and a valid executable, model, provider and environment names"
            else s"${lower(harness)} is referenced by ${referrers(harness)}, and the executable of its entry in $file is missing or not executable")
      }
    }
    val incomplete = used.map(_._1).distinct.filter(_.provider.isEmpty)
    val completion = incomplete.flatMap { route =>
      entries.get(route.harness).flatMap { entry =>
        val rendered = AgentConfigText.render(route)
        if (Try(HarnessProfile(entry, route)).isFailure) Some(s"$rendered cannot take the provider of the ${lower(route.harness)} settings entry: the entry is not valid")
        else if (route.harness == Harness.Claude && entry.provider != ClaudeAdapter.Provider)
          Some(s"$rendered would take the provider ${entry.provider} of the claude settings entry, and Claude Code is launched for ${ClaudeAdapter.Provider} only")
        else None
      }
    }
    val missing = incomplete.map(_.harness).distinct.filterNot(entries.contains)
    val providers = if (used.isEmpty) check("Providers", true, "No role is resolved, so there is no route to complete")
      else if (incomplete.isEmpty) check("Providers", true, "Every resolved route names its provider")
      else if (completion.nonEmpty || missing.nonEmpty) check("Providers", false,
        (completion ++ missing.map(harness => s"a ${lower(harness)} route names no provider, and the session settings have no ${lower(harness)} entry to take one from")).mkString("; "))
      else check("Providers", true, "A route without a provider takes the provider of its harness's settings entry: " +
        harnesses.filter(harness => incomplete.exists(_.harness == harness)).map(harness => s"${lower(harness)} → ${entries(harness).provider}").mkString(", "))
    if (read.isFailure) List(check("Session settings", false, s"Session settings $file are missing, unreadable or name a harness twice; contents are not shown"))
    else check("Session settings", true, s"Read $file") :: entryChecks ::: List(providers)
  }
}
object AgentsDoctor {
  val Scope = "Model names are not verified against providers. cq doctor harness verifies executables by their version probe, generated assets and trust."
}
