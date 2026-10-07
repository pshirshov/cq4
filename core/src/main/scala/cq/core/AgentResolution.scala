package cq.core

import cq.api.*

/** Which models run a subagent role under a governing harness, from the installation's layer and the project's. */
object AgentResolution {
  /**
   * The effort values each harness takes. Claude 2.1.285 lists them for `--effort` and Pi 0.99.1 and 1.0.0 for `--thinking`. Codex 0.160.0
   * takes any text for `model_reasoning_effort`; its set here is the levels of this model that the release names, which leaves
   * out `off`, for which that release has no name. `ultra` is a level of that release alone.
   */
  def efforts(harness: Harness): Set[Effort] = harness match {
    case Harness.Claude => Set(Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max)
    case Harness.Codex => Set(Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max, Effort.Ultra)
    case Harness.Pi => Set(Effort.Off, Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max)
  }

  /** The value as a configuration and each harness's command line spell it. */
  def effortName(value: Effort): String = AgentReferenceText.text(AgentReferenceText.Efforts, value)

  /**
   * Pi (0.99.1 and 1.0.0) reads the text after the last colon of `--model` as a thinking level when it is one of its levels, unless its catalogue
   * holds the whole name: `x:high` runs as `x` when Pi knows `x`, or when no `--thinking` is passed. Such a name has no one meaning.
   */
  def piThinkingSuffix(model: String): Boolean = model.lastIndexOf(':') match {
    case -1 => false
    case at => efforts(Harness.Pi).map(effortName)(model.substring(at + 1))
  }

  def effortProblems(at: TextPosition, harness: Harness, effort: Option[Effort]): List[AgentProblem] =
    effort.filterNot(efforts(harness)).map(AgentProblem.EffortUnsupported(at, harness, _)).toList

  /** What makes one model of `harness` unusable whatever else is configured; `at` is where its reference or tier entry is written. */
  def routeProblems(at: TextPosition, harness: Harness, model: ModelName, effort: Option[Effort]): List[AgentProblem] =
    (harness match {
      case Harness.Pi if model.provider.isEmpty => List(AgentProblem.ProviderRequired(at, harness))
      case Harness.Claude if model.provider.nonEmpty => List(AgentProblem.ProviderNotAllowed(at, harness))
      case _ => Nil
    }) ++ Option.when(harness == Harness.Pi && piThinkingSuffix(model.model))(AgentProblem.ModelAmbiguous(at, harness, model.model)) ++
      effortProblems(at, harness, effort)

  /**
   * The first layer and part that assigns the role decides it whole: the project's roles for the governing harness, the project's
   * defaults, the installation's roles for that harness, the installation's defaults. A part assigns the role to this work by the key
   * of the work's mode or, without that key, by the key of the role: the key of a mode wins within its part only, and the key of the
   * role in an earlier part decides before the key of a mode in a later one. Tiers alone are merged: the project's list of a tier
   * replaces the installation's, for every reference whichever layer wrote it.
   */
  def resolve(installation: ParsedAgents, project: ParsedAgents, governing: Harness, work: DispatchWork): ResolvedAssignment = {
    val role = RoleKeys.role(work)
    val plain: RoleKey = RoleKey.Plain(role)
    val found = (for {
      (layer, document) <- List(AgentLayer.Project -> project, AgentLayer.Installation -> installation)
      (source, scope, assigned) <- List(
        (RoleSource.HarnessRoles, Some(governing), document.config.harnesses.get(governing).fold(List.empty[RoleAssignment])(_.roles)),
        (RoleSource.DefaultRoles, None, document.config.defaults))
      key <- RoleKeys.qualified(work).toList :+ plain
      assignment <- assigned.find(_.key == key)
    } yield (RoleOrigin(layer, source), key, assignment.choice, document.references(AgentRoleKey(scope, key)))).headOption
    found.fold(ResolvedAssignment(governing, plain, RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(governing, role))))) { case (origin, key, choice, positions) =>
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
          val problems = values.flatMap(route => routeProblems(at, route.harness, ModelName(route.provider, route.model), route.effort)).distinct
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
        // A fallback tries a route once: a model that abstained is not tried again, so later occurrences of a route are dropped.
        // A round-robin keeps its repetitions, which give a route more of the turns.
        if (problems.nonEmpty) Left(problems) else Right(ResolvedSeat(strategy, strategy match {
          case SeatStrategy.First => candidates.take(1)
          case SeatStrategy.Fallback => candidates.distinct
          case SeatStrategy.RoundRobin => candidates
        }))
      }

      val (mode, min, seats) = choice match {
        case RoleChoice.Seat(single) => (PanelMode.All, 1, List(single))
        case RoleChoice.Panel(panel, listed, required) => (panel, required, listed)
      }
      val resolved = seats.zip(positions).map(seat.tupled)
      val problems = resolved.flatMap(_.left.getOrElse(Nil)).distinct
      ResolvedAssignment(governing, key, if (problems.nonEmpty) RoleResolution.Unresolved(Some(origin), problems)
      else {
        val plan = resolved.flatMap(_.toOption)
        val selfReview = if (role == AgentRole.Reviewer) plan.zipWithIndex.collect { case (value, index) if value.candidates.exists(_.harness == governing) => index } else Nil
        RoleResolution.Resolved(ResolvedRole(mode, min, plan, origin, selfReview))
      })
    }
  }

  /**
   * Every role under every governing harness: one assignment by the key of the role, for the modes that no key of their own decides,
   * and after it one for each mode that a key of its own decides.
   */
  def assignments(installation: ParsedAgents, project: ParsedAgents): List[ResolvedAssignment] = for {
    harness <- Harness.all
    role <- AgentRole.all
    (plain, modes) = RoleKeys.works(role).map(resolve(installation, project, harness, _)).distinct.partition(_.key == RoleKey.Plain(role))
    assignment <- plain ++ modes
  } yield assignment
}
