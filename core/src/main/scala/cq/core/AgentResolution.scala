package cq.core

import cq.api.*

/** Which models run a subagent role under a governing harness, from the installation's layer and the project's. */
object AgentResolution {
  /**
   * The effort values each harness takes. Claude 2.1.285 lists them for `--effort` and Pi 0.99.1 for `--thinking`. Codex 0.160.0
   * takes any text for `model_reasoning_effort`; its set here is the levels of this model that the release names, which leaves
   * out `off`, for which that release has no name.
   */
  def efforts(harness: Harness): Set[Effort] = harness match {
    case Harness.Claude => Set(Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max)
    case Harness.Codex => Set(Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max)
    case Harness.Pi => Set(Effort.Off, Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max)
  }

  def effortProblems(at: TextPosition, harness: Harness, effort: Option[Effort]): List[AgentProblem] =
    effort.filterNot(efforts(harness)).map(AgentProblem.EffortUnsupported(at, harness, _)).toList

  /** What makes one model of `harness` unusable whatever else is configured; `at` is where its reference or tier entry is written. */
  def routeProblems(at: TextPosition, harness: Harness, provider: Option[String], effort: Option[Effort]): List[AgentProblem] =
    (harness match {
      case Harness.Pi if provider.isEmpty => List(AgentProblem.ProviderRequired(at, harness))
      case Harness.Claude if provider.nonEmpty => List(AgentProblem.ProviderNotAllowed(at, harness))
      case _ => Nil
    }) ++ effortProblems(at, harness, effort)

  /**
   * The first layer and part that assigns the role decides it whole: the project's roles for the governing harness, the project's
   * defaults, the installation's roles for that harness, the installation's defaults. Tiers alone are merged: the project's list
   * of a tier replaces the installation's, for every reference whichever layer wrote it.
   */
  def resolve(installation: ParsedAgents, project: ParsedAgents, governing: Harness, role: AgentRole): RoleResolution = {
    val found = (for {
      (layer, document) <- List(AgentLayer.Project -> project, AgentLayer.Installation -> installation)
      (source, key, assigned) <- List(
        (RoleSource.HarnessRoles, AgentRoleKey(Some(governing), role), document.config.harnesses.get(governing).flatMap(_.roles.get(role))),
        (RoleSource.DefaultRoles, AgentRoleKey(None, role), document.config.defaults.get(role)))
      choice <- assigned
    } yield (RoleOrigin(layer, source), choice, document.references(key))).headOption
    found.fold[RoleResolution](RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(governing, role)))) { case (origin, choice, positions) =>
      def tier(harness: Harness, value: ModelTier): Option[List[TierEntry]] =
        List(project, installation).flatMap(_.config.harnesses.get(harness).flatMap(_.tiers.get(value))).headOption

      def expand(reference: ModelReference, at: TextPosition): Either[List[AgentProblem], List[ModelRoute]] = {
        val harness = reference.harness match {
          case HarnessSelector.Governing() => governing
          case HarnessSelector.Named(named) => named
        }
        val routes = reference.target match {
          case ModelTarget.Exact(model) => Right(List(ModelRoute(harness, model.provider, model.model, reference.effort)))
          case ModelTarget.Tier(value) => tier(harness, value).toRight(List[AgentProblem](AgentProblem.TierUndefined(harness, value, role)))
            .map(_.map(entry => ModelRoute(harness, entry.model.provider, entry.model.model, reference.effort.orElse(entry.effort))))
        }
        routes.flatMap { values =>
          val problems = values.flatMap(route => routeProblems(at, route.harness, route.provider, route.effort)).distinct
          if (problems.isEmpty) Right(values) else Left(problems)
        }
      }

      def seat(value: SeatChoice, at: List[TextPosition]): Either[List[AgentProblem], ResolvedSeat] = {
        // `first` runs the first candidate only, so the entries after the first are not read.
        val (strategy, entries) = value match {
          case SeatChoice.Single(reference) => SeatStrategy.Fallback -> List(reference)
          case SeatChoice.Strategy(SeatStrategy.First, listed) => SeatStrategy.First -> listed.take(1)
          case SeatChoice.Strategy(kind, listed) => kind -> listed
        }
        val expanded = entries.zip(at).map(expand.tupled)
        val problems = expanded.flatMap(_.left.getOrElse(Nil))
        val candidates = expanded.flatMap(_.getOrElse(Nil))
        if (problems.nonEmpty) Left(problems) else Right(ResolvedSeat(strategy, if (strategy == SeatStrategy.First) candidates.take(1) else candidates))
      }

      val (mode, min, seats) = choice match {
        case RoleChoice.Seat(single) => (PanelMode.All, 1, List(single))
        case RoleChoice.Panel(panel, listed, required) => (panel, required, listed)
      }
      val resolved = seats.zip(positions).map(seat.tupled)
      val problems = resolved.flatMap(_.left.getOrElse(Nil)).distinct
      if (problems.nonEmpty) RoleResolution.Unresolved(Some(origin), problems)
      else {
        val plan = resolved.flatMap(_.toOption)
        val selfReview = if (role == AgentRole.Reviewer) plan.zipWithIndex.collect { case (value, index) if value.candidates.exists(_.harness == governing) => index } else Nil
        RoleResolution.Resolved(ResolvedRole(mode, min, plan, origin, selfReview))
      }
    }
  }

  /** Every role under every governing harness. */
  def assignments(installation: ParsedAgents, project: ParsedAgents): List[ResolvedAssignment] = for {
    harness <- Harness.all
    role <- AgentRole.all
  } yield ResolvedAssignment(harness, role, resolve(installation, project, harness, role))
}
