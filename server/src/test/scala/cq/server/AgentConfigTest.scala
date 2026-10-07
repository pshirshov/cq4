package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{AgentConfigText, AgentResolution, ParsedAgents, RoleKeys}
import org.scalatest.wordspec.AnyWordSpec

final class AgentConfigLocal extends AnyWordSpec {
  // The example of the design the operator approved on 2026-10-05, unchanged.
  private val Approved =
    """defaults:
      |  roles:
      |    planner:  $harness:@frontier
      |    worker:   { fallback: [$harness:@standard, pi:@standard] }
      |    explorer: $harness:@fast
      |    reviewer: { any: [claude:@standard, pi:@standard], min: 1 }
      |harnesses:
      |  claude: { tiers: { frontier: [opus], standard: [sonnet], fast: [haiku] } }
      |  codex:
      |    tiers: { frontier: [gpt-6.1-sol?effort=xhigh], standard: [gpt-6.1-sol], fast: [gpt-6-luna?effort=low] }
      |    roles: { reviewer: { all: [claude:@standard, pi:@standard], min: 1 } }
      |  pi:
      |    tiers: { frontier: [openai-codex/gpt-6.1-sol?effort=xhigh], standard: [zai/glm-5.3, xiaomi-token-plan-ams/mimo-v2.6-pro], fast: [xiaomi-token-plan-ams/mimo-v2.6-pro?effort=low] }
      |""".stripMargin

  private val Governing = HarnessSelector.Governing()
  private def named(harness: Harness): HarnessSelector = HarnessSelector.Named(harness)
  private def at(line: Int, column: Int): TextPosition = TextPosition(line, column)
  private def name(text: String): ModelName = text.split("/", 2) match {
    case Array(provider, model) => ModelName(Some(provider), model)
    case _ => ModelName(None, text)
  }
  private def entry(text: String): TierEntry = TierEntry(name(text), None)
  private def entry(text: String, effort: Effort): TierEntry = TierEntry(name(text), Some(effort))
  private def exact(harness: HarnessSelector, model: String): ModelReference = ModelReference(harness, ModelTarget.Exact(name(model)), None)
  private def tier(harness: HarnessSelector, value: ModelTier): ModelReference = ModelReference(harness, ModelTarget.Tier(value), None)
  private def single(reference: ModelReference): RoleChoice = RoleChoice.Seat(SeatChoice.Single(reference))
  private def route(harness: Harness, model: String): ModelRoute = ModelRoute(harness, name(model).provider, name(model).model, None)
  private def route(harness: Harness, model: String, effort: Effort): ModelRoute = route(harness, model).copy(effort = Some(effort))
  private def fallback(candidates: ModelRoute*): ResolvedSeat = ResolvedSeat(SeatStrategy.Fallback, candidates.toList)

  private def parsed(text: String): ParsedAgents =
    AgentConfigText.parse(text).fold(problems => fail(problems.map(AgentConfigText.describe).mkString("; ")), identity)
  private def problems(text: String): List[AgentProblem] = AgentConfigText.problems(text)
  private def syntax(text: String): AgentProblem.Syntax = problems(text) match {
    case List(problem: AgentProblem.Syntax) => problem
    case other => fail(s"Expected one syntax problem for <$text>, got $other")
  }
  private def refused(text: String, position: TextPosition, fragment: String): Unit = {
    val problem = syntax(text)
    assert(problem.at == position && problem.message.contains(fragment), s"<$text> gave $problem")
  }
  private def inRoles(value: String): String = s"defaults: { roles: { worker: $value } }"
  private val ValueColumn = 30
  private def plain(role: AgentRole): RoleKey = RoleKey.Plain(role)
  private def assigned(document: ParsedAgents, role: AgentRole): RoleChoice = document.config.defaults match {
    case List(RoleAssignment(RoleKey.Plain(`role`), choice)) => choice
    case other => fail(s"Expected the $role role alone, got $other")
  }
  private def worker(value: String): RoleChoice = assigned(parsed(inRoles(value)), AgentRole.Worker)
  // A role whose modes have no key of their own resolves alike in every mode, by the key of the role.
  private def resolution(installation: ParsedAgents, project: ParsedAgents, governing: Harness, role: AgentRole): RoleResolution =
    RoleKeys.works(role).map(AgentResolution.resolve(installation, project, governing, _)).distinct match {
      case List(ResolvedAssignment(`governing`, RoleKey.Plain(`role`), value)) => value
      case other => fail(s"Expected one resolution of $role by its own key, got $other")
    }
  private def resolution(installation: String, project: String, governing: Harness, role: AgentRole): RoleResolution =
    resolution(parsed(installation), parsed(project), governing, role)
  private def resolved(installation: String, project: String, governing: Harness, role: AgentRole): ResolvedRole =
    resolution(installation, project, governing, role) match {
      case RoleResolution.Resolved(plan) => plan
      case RoleResolution.Unresolved(_, found) => fail(found.map(AgentConfigText.describe).mkString("; "))
    }
  private def unresolved(installation: String, project: String, governing: Harness, role: AgentRole): RoleResolution.Unresolved =
    resolution(installation, project, governing, role) match {
      case value: RoleResolution.Unresolved => value
      case RoleResolution.Resolved(plan) => fail(s"Resolved to ${AgentConfigText.render(plan)}")
    }
  private def shown(installation: String, project: String, governing: Harness, role: AgentRole): String =
    AgentConfigText.render(resolved(installation, project, governing, role))

  private val Glm = "zai/glm-5.3"
  private val Mimo = "xiaomi-token-plan-ams/mimo-v2.6-pro"

  "The approved example (Behavioral Active Blackbox Atomic)" should {
    "parse unchanged into the configuration it states" in {
      val reviewers = List(SeatChoice.Single(tier(named(Harness.Claude), ModelTier.Standard)), SeatChoice.Single(tier(named(Harness.Pi), ModelTier.Standard)))
      assert(parsed(Approved).config == AgentConfig(
        List(
          RoleAssignment(plain(AgentRole.Planner), single(tier(Governing, ModelTier.Frontier))),
          RoleAssignment(plain(AgentRole.Worker), RoleChoice.Seat(SeatChoice.Strategy(SeatStrategy.Fallback, List(tier(Governing, ModelTier.Standard), tier(named(Harness.Pi), ModelTier.Standard))))),
          RoleAssignment(plain(AgentRole.Explorer), single(tier(Governing, ModelTier.Fast))),
          RoleAssignment(plain(AgentRole.Reviewer), RoleChoice.Panel(PanelMode.Any, reviewers, 1))),
        Map(
          Harness.Claude -> HarnessAgents(Map(ModelTier.Frontier -> List(entry("opus")), ModelTier.Standard -> List(entry("sonnet")), ModelTier.Fast -> List(entry("haiku"))), Nil),
          Harness.Codex -> HarnessAgents(
            Map(ModelTier.Frontier -> List(entry("gpt-6.1-sol", Effort.XHigh)), ModelTier.Standard -> List(entry("gpt-6.1-sol")), ModelTier.Fast -> List(entry("gpt-6-luna", Effort.Low))),
            List(RoleAssignment(plain(AgentRole.Reviewer), RoleChoice.Panel(PanelMode.All, reviewers, 1)))),
          Harness.Pi -> HarnessAgents(
            Map(ModelTier.Frontier -> List(entry("openai-codex/gpt-6.1-sol", Effort.XHigh)), ModelTier.Standard -> List(entry(Glm), entry(Mimo)), ModelTier.Fast -> List(entry(Mimo, Effort.Low))),
            Nil))))
      assert(problems(Approved).isEmpty)
    }

    "resolve every role under every governing harness" in {
      val installationDefaults = RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles)
      val piStandard = fallback(route(Harness.Pi, Glm), route(Harness.Pi, Mimo))
      def plan(seat: ResolvedSeat): ResolvedRole = ResolvedRole(PanelMode.All, 1, List(seat), installationDefaults, Nil)
      def expected(harness: Harness, role: AgentRole): ResolvedRole = (harness, role) match {
        case (Harness.Claude, AgentRole.Planner) => plan(fallback(route(Harness.Claude, "opus")))
        case (Harness.Claude, AgentRole.Worker) => plan(fallback(route(Harness.Claude, "sonnet"), route(Harness.Pi, Glm), route(Harness.Pi, Mimo)))
        case (Harness.Claude, AgentRole.Explorer) => plan(fallback(route(Harness.Claude, "haiku")))
        case (Harness.Claude, AgentRole.Reviewer) => ResolvedRole(PanelMode.Any, 1, List(fallback(route(Harness.Claude, "sonnet")), piStandard), installationDefaults, List(0))
        case (Harness.Codex, AgentRole.Planner) => plan(fallback(route(Harness.Codex, "gpt-6.1-sol", Effort.XHigh)))
        case (Harness.Codex, AgentRole.Worker) => plan(fallback(route(Harness.Codex, "gpt-6.1-sol"), route(Harness.Pi, Glm), route(Harness.Pi, Mimo)))
        case (Harness.Codex, AgentRole.Explorer) => plan(fallback(route(Harness.Codex, "gpt-6-luna", Effort.Low)))
        case (Harness.Codex, AgentRole.Reviewer) => ResolvedRole(PanelMode.All, 1, List(fallback(route(Harness.Claude, "sonnet")), piStandard),
          RoleOrigin(AgentLayer.Installation, RoleSource.HarnessRoles), Nil)
        case (Harness.Pi, AgentRole.Planner) => plan(fallback(route(Harness.Pi, "openai-codex/gpt-6.1-sol", Effort.XHigh)))
        // `$harness:@standard` and `pi:@standard` are one tier under a Pi governor: a fallback tries each of its models once.
        case (Harness.Pi, AgentRole.Worker) => plan(fallback(route(Harness.Pi, Glm), route(Harness.Pi, Mimo)))
        case (Harness.Pi, AgentRole.Explorer) => plan(fallback(route(Harness.Pi, Mimo, Effort.Low)))
        case (Harness.Pi, AgentRole.Reviewer) => ResolvedRole(PanelMode.Any, 1, List(fallback(route(Harness.Claude, "sonnet")), piStandard), installationDefaults, List(1))
      }
      val assignments = AgentResolution.assignments(parsed(Approved), ParsedAgents.empty)
      assert(assignments.map(value => value.harness -> value.key) == (for (harness <- Harness.all; role <- AgentRole.all) yield harness -> plain(role)))
      assignments.foreach(value => assert(value.resolution == RoleResolution.Resolved(expected(value.harness, RoleKeys.role(value.key))), s"${value.harness} ${value.key}"))
      assert(shown(Approved, "", Harness.Codex, AgentRole.Reviewer) == s"{ all: [claude:sonnet, { fallback: [pi:$Glm, pi:$Mimo] }], min: 1 }")
      assert(shown(Approved, "", Harness.Codex, AgentRole.Planner) == "codex:gpt-6.1-sol?effort=xhigh")
      assert(shown(Approved, "", Harness.Claude, AgentRole.Worker) == s"{ fallback: [claude:sonnet, pi:$Glm, pi:$Mimo] }")
    }

    "survive the generated JSON codec with its enumeration-keyed maps written as objects and its roles as a list of keys" in {
      val config = parsed(Approved).config
      val json = AgentConfig_JsonCodec.encode(BaboonCodecContext.Default, config)
      assert(AgentConfig_JsonCodec.decode(BaboonCodecContext.Default, json) == Right(config))
      assert(json.hcursor.downField("harnesses").downField("Codex").downField("tiers").downField("Frontier").downN(0).downField("effort").as[String] == Right("XHigh"))
      assert(json.hcursor.downField("defaults").values.map(_.toList.map(_.hcursor.downField("key").downField("Plain").downField("role").as[String])) ==
        Some(List("Planner", "Worker", "Explorer", "Reviewer").map(Right(_))))
    }
  }

  "The YAML subset of an agent configuration" should {
    "accept block and flow mappings, flow sequences, plain and double-quoted scalars and comments" in {
      val expected = parsed("defaults: { roles: { worker: claude:opus } }\nharnesses: { pi: { tiers: { fast: [zai/a, zai/b] } } }").config
      val block =
        """# the server default
          |
          |defaults:   # roles for every harness
          |  roles:
          |    "worker": "claude:opus"   # quoted key and value, with an escape
          |
          |harnesses:
          |  pi:
          |    tiers:
          |      fast: [
          |        zai/a,   # the first
          |        "zai/b",
          |      ]
          |""".stripMargin
      assert(parsed(block).config == expected)
      assert(parsed(block.replace("\n", "\r\n")).config == expected)
      assert(parsed("{ defaults: { roles: { worker: claude:opus } },\n  harnesses: { pi: { tiers: { fast: [zai/a, zai/b] } } } }\n").config == expected)
      assert(parsed("  defaults:\n    roles: {worker: claude:opus}\n  harnesses:\n    pi:\n      tiers:\n        fast:\n          [zai/a, zai/b]").config == expected)
      assert(parsed("defaults:\n  roles:\n    worker:\n      claude:opus\nharnesses: { pi: { tiers: { fast: [zai/a, zai/b] } } }").config == expected)
      // A closing bracket may stand in the column of the key that opened the collection.
      assert(parsed("defaults: { roles: { worker: claude:opus } }\nharnesses: {\n  pi: { tiers: { fast: [zai/a, zai/b] } }\n}").config == expected)
    }

    "read a text without content, and keys without content, as stating nothing" in {
      val nothing = AgentConfig(Nil, Map.empty)
      List("", "\n\n", "# nothing yet\n", "   \n# a\n  # b", "defaults:\nharnesses:\n", "defaults:\n  roles:\nharnesses: {}", "{}").foreach { text =>
        assert(parsed(text).config == nothing, text)
      }
      assert(parsed("harnesses:\n  pi:\n  codex: {}\n").config == AgentConfig(Nil, Map(
        Harness.Pi -> HarnessAgents(Map.empty, Nil), Harness.Codex -> HarnessAgents(Map.empty, Nil))))
      assert(ParsedAgents.empty.config == nothing)
    }

    "refuse every YAML feature outside the subset where it stands" in {
      refused("defaults: &base\n  roles: {}", at(1, 11), "anchors")
      refused("defaults:\n  roles: { worker: &w claude:opus }", at(2, 20), "anchors")
      refused("harnesses:\n  pi: *base", at(2, 7), "aliases")
      refused("defaults:\n  <<: *base", at(2, 7), "aliases")
      refused("defaults: !!map {}", at(1, 11), "tags")
      refused("!agents\ndefaults: {}", at(1, 1), "tags")
      refused("---\ndefaults: {}", at(1, 1), "document markers")
      refused("defaults: {}\n---\nharnesses: {}", at(2, 1), "document markers")
      refused("defaults: {}\n...\n", at(2, 1), "document markers")
      refused("%YAML 1.2\n---\ndefaults: {}", at(1, 1), "directives")
      refused("defaults: |\n  roles", at(1, 11), "block scalars")
      refused("defaults: >-\n  roles", at(1, 11), "block scalars")
      refused("defaults:\n  roles:\n    worker:\n      |\n      claude:opus", at(4, 7), "block scalars")
      refused("harnesses:\n  pi:\n    tiers:\n      fast:\n        - zai/a\n        - zai/b", at(5, 9), "block sequences")
      refused("- defaults", at(1, 1), "block sequences")
      refused("defaults: { roles: { worker: 'claude:opus' } }", at(1, ValueColumn), "single-quoted")
      refused("? defaults\n: {}", at(1, 1), "explicit keys")
      refused("defaults: {}\ndefaults: {}", at(2, 1), "duplicate key defaults")
      refused("defaults: { roles: { worker: claude:opus, worker: claude:opus } }", at(1, 43), "duplicate key worker")
      refused("defaults:\n  roles:\n    worker: claude:opus\n    \"worker\": claude:opus", at(4, 5), "duplicate key worker")
      refused("harnesses: { pi: { tiers: {}, tiers: {} } }", at(1, 31), "duplicate key tiers")
    }

    "refuse malformed layout with the position of the fault" in {
      refused("defaults:\n\troles: {}", at(2, 1), "tab")
      refused("defaults:\n  roles:\n    worker: claude:opus\n      continued", at(4, 7), "indented more")
      refused("defaults:\n    roles:\n  harnesses: {}", at(3, 3), "indented more")
      refused("  defaults: {}\nharnesses: {}", at(2, 1), "unexpected content")
      refused("defaults: roles: {}", at(1, 11), "nested mapping")
      refused("defaults: { roles: {", at(1, 21), "not closed")
      refused("defaults: { roles: { worker: [claude:opus } }", at(1, 43), "expected , or ]")
      refused("defaults: { roles: { worker: claude:opus ] }", at(1, 42), "expected , or }")
      refused("defaults: { roles }", at(1, 19), "colon")
      refused("defaults: { roles: }", at(1, 20), "no value")
      refused("defaults: { roles: { reviewer: { any: [claude:opus], min:1 } } }", at(1, 60), "colon")
      refused("defaults: { [a]: b }", at(1, 13), "key must be a scalar")
      refused("defaults: { roles: { worker: \"claude:opus } }", at(1, ValueColumn), "not closed")
      refused("defaults: { roles: { worker: \"claude:\\qopus\" } }", at(1, 38), "escape")
      refused("defaults: { roles: { worker: \"claude:\\u00zz\" } }", at(1, 38), "hexadecimal")
      refused("defaults: {}#comment", at(1, 13), "comment must follow a space")
      refused("defaults: {} harnesses: {}", at(1, 14), "unexpected text")
      refused("defaults:\u0001", at(1, 10), "control")
      refused("defaults:\rharnesses:", at(1, 10), "carriage return")
      refused("defaults: \ud83d", at(1, 11), "Unicode")
      refused("[[[[[[[[[[claude:opus]]]]]]]]]]", at(1, 10), "nesting")
      refused("defaults: { roles: { worker: @frontier } }", at(1, ValueColumn), "cannot start with @")
      refused("harnesses:\n  codex:\n    roles: { reviewer: { all: [claude:@standard,\n    pi:@standard], min: 1 } }", at(4, 5), "continued line")
    }
  }

  "The structure of an agent configuration" should {
    "report every unknown key with its position and go on" in {
      assert(problems("default: {}\nharness: {}") == List(AgentProblem.UnknownKey(at(1, 1), "default"), AgentProblem.UnknownKey(at(2, 1), "harness")))
      assert(problems("defaults:\n  role: {}") == List(AgentProblem.UnknownKey(at(2, 3), "role")))
      assert(problems("harnesses:\n  gemini: {}\n  Claude: {}") == List(AgentProblem.UnknownKey(at(2, 3), "gemini"), AgentProblem.UnknownKey(at(3, 3), "Claude")))
      assert(problems("harnesses:\n  pi:\n    tier: {}\n    model: zai/a") == List(AgentProblem.UnknownKey(at(3, 5), "tier"), AgentProblem.UnknownKey(at(4, 5), "model")))
      assert(problems("harnesses: { pi: { tiers: { best: [zai/a] } } }") == List(AgentProblem.UnknownKey(at(1, 29), "best")))
      assert(problems("defaults: { roles: { governor: claude:opus, Planner: claude:opus } }") ==
        List(AgentProblem.UnknownKey(at(1, 22), "governor"), AgentProblem.UnknownKey(at(1, 45), "Planner")))
      assert(problems(inRoles("{ fallback: [claude:opus], max: 1 }")) == List(AgentProblem.UnknownKey(at(1, 57), "max")))
    }

    "read a key that names one mode of a role, beside the key of the role" in {
      val text =
        """defaults:
          |  roles:
          |    reviewer: claude:opus
          |    reviewer/plan: codex:gpt
          |    worker/probe: { rr: [claude:haiku, codex:gpt] }
          |harnesses: { pi: { roles: { explorer/research: claude:opus, reviewer/audit: { any: [claude:opus, codex:gpt], min: 1 } } } }
          |""".stripMargin
      val opus = SeatChoice.Single(exact(named(Harness.Claude), "opus"))
      val gpt = SeatChoice.Single(exact(named(Harness.Codex), "gpt"))
      assert(parsed(text).config == AgentConfig(
        List(
          RoleAssignment(plain(AgentRole.Reviewer), RoleChoice.Seat(opus)),
          RoleAssignment(RoleKey.Reviewer(ReviewerMode.Plan), RoleChoice.Seat(gpt)),
          RoleAssignment(RoleKey.Worker(WorkerMode.Probe), RoleChoice.Seat(SeatChoice.Strategy(SeatStrategy.RoundRobin, List(exact(named(Harness.Claude), "haiku"), exact(named(Harness.Codex), "gpt")))))),
        Map(Harness.Pi -> HarnessAgents(Map.empty, List(
          RoleAssignment(RoleKey.Explorer(ExplorerMode.Research), RoleChoice.Seat(opus)),
          RoleAssignment(RoleKey.Reviewer(ReviewerMode.Audit), RoleChoice.Panel(PanelMode.Any, List(opus, gpt), 1)))))))
      // Every mode of the dispatch model has a key, spelled as the role, a slash and the mode in lower case.
      val keys = List[(String, RoleKey)](
        "worker/implement" -> RoleKey.Worker(WorkerMode.Implement), "worker/probe" -> RoleKey.Worker(WorkerMode.Probe), "worker/resolveconflict" -> RoleKey.Worker(WorkerMode.ResolveConflict),
        "explorer/investigate" -> RoleKey.Explorer(ExplorerMode.Investigate), "explorer/research" -> RoleKey.Explorer(ExplorerMode.Research),
        "reviewer/candidate" -> RoleKey.Reviewer(ReviewerMode.Candidate), "reviewer/plan" -> RoleKey.Reviewer(ReviewerMode.Plan), "reviewer/audit" -> RoleKey.Reviewer(ReviewerMode.Audit))
      assert(keys.size == WorkerMode.all.size + ExplorerMode.all.size + ReviewerMode.all.size)
      keys.foreach { case (key, expected) =>
        assert(parsed(s"defaults:\n  roles:\n    $key: claude:opus\n").config.defaults == List(RoleAssignment(expected, RoleChoice.Seat(opus))), key)
        assert(parsed(s"harnesses: { codex: { roles: { \"$key\": claude:opus } } }").config.harnesses(Harness.Codex).roles == List(RoleAssignment(expected, RoleChoice.Seat(opus))), key)
      }
    }

    "refuse a key whose mode its role does not have, naming the key and the modes of the role" in {
      def key(text: String): List[AgentProblem] = problems(s"defaults: { roles: { $text: claude:opus } }")
      val reviewer = "; its modes are candidate, plan, audit"
      assert(key("reviewer/probe") == List(AgentProblem.Syntax(at(1, 22), "the key 'reviewer/probe' names no mode of the reviewer role" + reviewer)))
      assert(key("reviewer/Plan") == List(AgentProblem.Syntax(at(1, 22), "the key 'reviewer/Plan' names no mode of the reviewer role" + reviewer)))
      assert(key("reviewer/") == List(AgentProblem.Syntax(at(1, 22), "the key 'reviewer/' names no mode of the reviewer role" + reviewer)))
      assert(key("reviewer/plan/audit") == List(AgentProblem.Syntax(at(1, 22), "the key 'reviewer/plan/audit' names no mode of the reviewer role" + reviewer)))
      assert(key("worker/plan") == List(AgentProblem.Syntax(at(1, 22), "the key 'worker/plan' names no mode of the worker role; its modes are implement, probe, resolveconflict")))
      assert(key("explorer/probe") == List(AgentProblem.Syntax(at(1, 22), "the key 'explorer/probe' names no mode of the explorer role; its modes are investigate, research")))
      assert(key("planner/plan") == List(AgentProblem.Syntax(at(1, 22), "the key 'planner/plan' names a mode, and the planner role has none")))
      assert(problems("harnesses:\n  pi:\n    roles:\n      worker/audit: pi:zai/a\n") ==
        List(AgentProblem.Syntax(at(4, 7), "the key 'worker/audit' names no mode of the worker role; its modes are implement, probe, resolveconflict")))
      // A key whose role is unknown is an unknown key, with or without a mode.
      assert(key("governor/plan") == List(AgentProblem.UnknownKey(at(1, 22), "governor/plan")))
      assert(key("/plan") == List(AgentProblem.UnknownKey(at(1, 22), "/plan")))
      assert(key("Reviewer/plan") == List(AgentProblem.UnknownKey(at(1, 22), "Reviewer/plan")))
      // The other keys of the mapping are read all the same, and a key is written once.
      assert(problems("defaults: { roles: { reviewer/probe: claude:opus, worker/implement: pi:glm } }") == List(
        AgentProblem.Syntax(at(1, 22), "the key 'reviewer/probe' names no mode of the reviewer role" + reviewer), AgentProblem.ProviderRequired(at(1, 69), Harness.Pi)))
      assert(syntax("defaults:\n  roles:\n    reviewer/plan: claude:opus\n    reviewer/plan: claude:opus\n").at == at(4, 5))
    }

    "report an empty list wherever a list is required" in {
      assert(problems("harnesses: { pi: { tiers: { fast: [] } } }") == List(AgentProblem.EmptyList(at(1, 35))))
      assert(problems(inRoles("{ rr: [] }")) == List(AgentProblem.EmptyList(at(1, 36))))
      assert(problems("defaults: { roles: { reviewer: { all: [], min: 1 } } }") == List(AgentProblem.EmptyList(at(1, 39))))
      assert(problems("defaults: { roles: { reviewer: { any: [{ first: [] }], min: 1 } } }") == List(AgentProblem.EmptyList(at(1, 49))))
    }

    "require a panel's min to be between one and its number of seats" in {
      def minimum(value: String): List[AgentProblem] = problems(s"defaults: { roles: { reviewer: { any: [claude:opus, codex:gpt], min: $value } } }")
      assert(minimum("3") == List(AgentProblem.InvalidMinimum(at(1, 70), 3, 2)))
      // A panel that starts more seats together than a session runs children at once could never start: every seat of `all`, `min` of `any`.
      val capacity = cq.core.ChildCapacity.MaxActiveChildren
      def panel(scope: String, mode: String, seats: Int, min: Int): List[String] =
        problems(scope.format(s"{ $mode: [${List.fill(seats)("claude:opus").mkString(", ")}], min: $min }")).map(AgentConfigText.describe)
      val defaults = "defaults: { roles: { reviewer: %s } }"
      val harness = "harnesses: { codex: { roles: { reviewer: %s } } }"
      assert(panel(defaults, "all", capacity + 1, 1) == List(s"1:32: defaults.roles.reviewer starts ${capacity + 1} seats together, and a session runs at most $capacity children at once"))
      assert(panel(harness, "any", capacity + 2, capacity + 1) == List(s"1:42: harnesses.codex.roles.reviewer starts ${capacity + 1} seats together, and a session runs at most $capacity children at once"))
      // A panel under the key of one mode is checked as any other, and named by that key.
      assert(panel("defaults: { roles: { reviewer/plan: %s } }", "all", capacity + 1, 1) ==
        List(s"1:37: defaults.roles.reviewer/plan starts ${capacity + 1} seats together, and a session runs at most $capacity children at once"))
      assert(panel("harnesses: { codex: { roles: { reviewer: claude:opus, reviewer/candidate: %s } } }", "any", capacity + 1, capacity + 1) ==
        List(s"1:75: harnesses.codex.roles.reviewer/candidate starts ${capacity + 1} seats together, and a session runs at most $capacity children at once"))
      assert(panel(defaults, "all", capacity, 1).isEmpty && panel(defaults, "any", capacity + 2, capacity).isEmpty && panel(harness, "all", capacity, capacity).isEmpty)
      assert(minimum("0") == List(AgentProblem.InvalidMinimum(at(1, 70), 0, 2)))
      assert(minimum("-1") == List(AgentProblem.InvalidMinimum(at(1, 70), -1, 2)))
      assert(minimum("1").isEmpty && minimum("2").isEmpty)
      List("one", "1.5", "\"1\"", "[1]", "99999999999").foreach { value =>
        assert(minimum(value).map { case AgentProblem.Syntax(position, _) => position; case other => fail(other.toString) } == List(at(1, 70)), value)
      }
      refused("defaults: { roles: { reviewer: { any: [claude:opus, codex:gpt] } } }", at(1, 32), "states min")
    }

    "admit a panel for the reviewer role only" in {
      List(AgentRole.Planner -> "planner", AgentRole.Worker -> "worker", AgentRole.Explorer -> "explorer").foreach { case (role, key) =>
        List("all", "any").foreach { mode =>
          val text = s"defaults: { roles: { $key: { $mode: [claude:opus], min: 1 } } }"
          assert(problems(text) == List(AgentProblem.PanelNotAllowed(at(1, 24 + key.length), role)), text)
          assert(problems(s"harnesses:\n  codex:\n    roles:\n      $key: { $mode: [claude:opus], min: 1 }") == List(AgentProblem.PanelNotAllowed(at(4, 9 + key.length), role)))
        }
      }
      assert(parsed("harnesses: { pi: { roles: { reviewer: { all: [claude:opus, codex:gpt], min: 2 } } } }").config.harnesses(Harness.Pi).roles == List(RoleAssignment(plain(AgentRole.Reviewer),
        RoleChoice.Panel(PanelMode.All, List(SeatChoice.Single(exact(named(Harness.Claude), "opus")), SeatChoice.Single(exact(named(Harness.Codex), "gpt"))), 2))))
      // A key of a mode takes what its role takes: a panel under every key of the reviewer role, and under no key of another.
      List("candidate" -> ReviewerMode.Candidate, "plan" -> ReviewerMode.Plan, "audit" -> ReviewerMode.Audit).foreach { case (mode, expected) =>
        assert(parsed(s"defaults: { roles: { reviewer/$mode: { any: [claude:opus, codex:gpt], min: 1 } } }").config.defaults == List(RoleAssignment(RoleKey.Reviewer(expected),
          RoleChoice.Panel(PanelMode.Any, List(SeatChoice.Single(exact(named(Harness.Claude), "opus")), SeatChoice.Single(exact(named(Harness.Codex), "gpt"))), 1))), mode)
      }
      List("worker/implement" -> AgentRole.Worker, "worker/probe" -> AgentRole.Worker, "worker/resolveconflict" -> AgentRole.Worker,
        "explorer/investigate" -> AgentRole.Explorer, "explorer/research" -> AgentRole.Explorer).foreach { case (key, role) =>
        assert(problems(s"defaults: { roles: { $key: { all: [claude:opus], min: 1 } } }") == List(AgentProblem.PanelNotAllowed(at(1, 24 + key.length), role)), key)
      }
    }

    "read a role value as a reference, a strategy, or a panel with one seat per entry" in {
      assert(worker("claude:opus") == single(exact(named(Harness.Claude), "opus")))
      List("fallback" -> SeatStrategy.Fallback, "rr" -> SeatStrategy.RoundRobin, "first" -> SeatStrategy.First).foreach { case (key, strategy) =>
        assert(worker(s"{ $key: [claude:opus, $$harness:@fast] }") ==
          RoleChoice.Seat(SeatChoice.Strategy(strategy, List(exact(named(Harness.Claude), "opus"), tier(Governing, ModelTier.Fast)))))
      }
      assert(assigned(parsed("defaults: { roles: { reviewer: { any: [claude:opus, { rr: [codex:gpt, $harness:@fast] }, { first: [pi:zai/a] }], min: 2 } } }"), AgentRole.Reviewer) ==
        RoleChoice.Panel(PanelMode.Any, List(
          SeatChoice.Single(exact(named(Harness.Claude), "opus")),
          SeatChoice.Strategy(SeatStrategy.RoundRobin, List(exact(named(Harness.Codex), "gpt"), tier(Governing, ModelTier.Fast))),
          SeatChoice.Strategy(SeatStrategy.First, List(exact(named(Harness.Pi), "zai/a")))), 2))
    }

    "refuse a value of the wrong shape where it stands" in {
      refused("defaults: claude:opus", at(1, 11), "expected a mapping")
      refused("harnesses: [pi]", at(1, 12), "expected a mapping")
      refused("harnesses: { pi: { tiers: { fast: zai/a } } }", at(1, 35), "expected a list of models")
      refused("harnesses: { pi: { tiers: { fast: [[zai/a]] } } }", at(1, 36), "expected a model name")
      refused("harnesses:\n  pi:\n    tiers:\n      fast:\n", at(4, 7), "expected a list of models")
      refused(inRoles("[claude:opus]"), at(1, ValueColumn), "expected a model reference, a strategy or a panel")
      refused("defaults:\n  roles:\n    worker:\n", at(3, 5), "expected a model reference, a strategy or a panel")
      refused(inRoles("{}"), at(1, ValueColumn), "expected one of the keys")
      refused(inRoles("{ min: 1 }"), at(1, ValueColumn), "expected one of the keys")
      refused(inRoles("{ fallback: [claude:opus], rr: [claude:opus] }"), at(1, 57), "not several")
      refused(inRoles("{ fallback: claude:opus }"), at(1, 42), "expected a list of model references")
      refused(inRoles("{ fallback: [{ rr: [claude:opus] }] }"), at(1, 43), "expected a model reference")
      refused(inRoles("{ fallback: [claude:opus], min: 1 }"), at(1, 57), "min belongs to a panel")
      refused("defaults: { roles: { reviewer: { all: claude:opus, min: 1 } } }", at(1, 39), "expected a list of seats")
      refused("defaults: { roles: { reviewer: { all: [{ any: [claude:opus], min: 1 }], min: 1 } } }", at(1, 42), "not another panel")
      refused("defaults: { roles: { reviewer: { all: [[claude:opus]], min: 1 } } }", at(1, 40), "expected a model reference or a strategy")
    }
  }

  "The model reference syntax" should {
    "read the harness, the provider, the model, the tier and the effort" in {
      assert(worker("claude:opus") == single(exact(named(Harness.Claude), "opus")))
      assert(worker("codex:gpt-6.1-sol?effort=xhigh") == single(ModelReference(named(Harness.Codex), ModelTarget.Exact(ModelName(None, "gpt-6.1-sol")), Some(Effort.XHigh))))
      assert(worker("pi:zai/glm-5.3?effort=off") == single(ModelReference(named(Harness.Pi), ModelTarget.Exact(ModelName(Some("zai"), "glm-5.3")), Some(Effort.Off))))
      assert(worker("$harness:zai/glm-5.3") == single(exact(Governing, "zai/glm-5.3")))
      ModelTier.all.zip(List("frontier", "standard", "fast")).foreach { case (value, text) =>
        assert(worker(s"pi:@$text") == single(tier(named(Harness.Pi), value)))
        assert(worker(s"$$harness:@$text?effort=high") == single(ModelReference(Governing, ModelTarget.Tier(value), Some(Effort.High))))
      }
      val efforts = List("off", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
      assert(efforts.size == Effort.all.size)
      Effort.all.zip(efforts).foreach { case (value, text) =>
        // Every level is read; a harness that does not take one refuses it by name, as Pi does `ultra`.
        if (AgentResolution.efforts(Harness.Pi)(value))
          assert(worker(s"pi:zai/a?effort=$text") == single(ModelReference(named(Harness.Pi), ModelTarget.Exact(ModelName(Some("zai"), "a")), Some(value))))
        else assert(problems(inRoles(s"pi:zai/a?effort=$text")) == List(AgentProblem.EffortUnsupported(at(1, ValueColumn), Harness.Pi, value)))
        if (AgentResolution.efforts(Harness.Codex)(value))
          assert(worker(s"codex:a?effort=$text") == single(ModelReference(named(Harness.Codex), ModelTarget.Exact(ModelName(None, "a")), Some(value))))
      }
    }

    "end the harness at the first colon and the provider at the first slash, and decode percent-encoding" in {
      def model(text: String): ModelName = worker(text) match {
        case RoleChoice.Seat(SeatChoice.Single(ModelReference(_, ModelTarget.Exact(value), _))) => value
        case other => fail(other.toString)
      }
      assert(model("$harness:openrouter/anthropic/claude-4:beta") == ModelName(Some("openrouter"), "anthropic/claude-4:beta"))
      assert(model("$harness:claude:opus") == ModelName(None, "claude:opus"))
      assert(model("$harness:gpt%2Fturbo") == ModelName(None, "gpt/turbo"))
      assert(model("$harness:a%2Fb/c%2fd") == ModelName(Some("a/b"), "c/d"))
      assert(model("$harness:%40home") == ModelName(None, "@home"))
      assert(model("$harness:x/%40home") == ModelName(Some("x"), "@home"))
      assert(model("$harness:a%20b%23c%2Cd%5Be%5D%7Bf%7D%3Fg") == ModelName(None, "a b#c,d[e]{f}?g"))
      assert(model("$harness:100%25") == ModelName(None, "100%"))
      assert(model("$harness:%CE%BB/%F0%9F%98%80") == ModelName(Some("λ"), "😀"))
      assert(model("$harness:λ/модель-😀") == ModelName(Some("λ"), "модель-😀"))
      assert(model("\"$harness:quoted/model\"") == ModelName(Some("quoted"), "model"))
      assert(model("$harness:" + "m" * 100) == ModelName(None, "m" * 100))
      assert(parsed("harnesses: { pi: { tiers: { fast: [a/b/c:d?effort=low, \"%40x/pi%3Ay\", x/claude%3Aopus] } } }").config.harnesses(Harness.Pi).tiers(ModelTier.Fast) ==
        List(TierEntry(ModelName(Some("a"), "b/c:d"), Some(Effort.Low)), TierEntry(ModelName(Some("@x"), "pi:y"), None), TierEntry(ModelName(Some("x"), "claude:opus"), None)))
    }

    "refuse a malformed reference where it stands" in {
      def reference(value: String, fragment: String): Unit = refused(inRoles(value), at(1, ValueColumn), fragment)
      reference("opus", "expected a model reference")
      reference("gemini:pro", "unknown harness 'gemini'")
      reference("Claude:opus", "unknown harness 'Claude'")
      reference("$Harness:opus", "unknown harness '$Harness'")
      reference("\"claude :opus\"", "unknown harness 'claude '")
      reference("\"claude:\"", "expected a model name")
      reference("claude:?effort=low", "expected a model name")
      reference("pi:/glm", "the provider is empty")
      reference("pi:zai/", "the model is empty")
      reference("claude:@best", "unknown tier 'best'")
      reference("claude:@Fast", "unknown tier 'Fast'")
      reference("claude:@", "unknown tier ''")
      reference("claude:opus?effort=extreme", "unknown effort 'extreme'; expected one of off, minimal, low, medium, high, xhigh, max, ultra")
      reference("claude:opus?effort=High", "unknown effort 'High'")
      reference("claude:opus?effort=", "unknown effort ''")
      reference("claude:opus?effort=low&effort=high", "effort is given twice")
      reference("claude:opus?speed=fast", "unknown parameter 'speed'")
      reference("claude:opus?effort=low&speed=fast", "unknown parameter 'speed'")
      reference("claude:opus?", "unknown parameter ''")
      reference("claude:opus?effort", "unknown parameter 'effort'")
      reference("claude:opus?effort=low?effort=high", "unknown effort 'low?effort=high'")
      reference("claude:a%2", "two hexadecimal digits")
      reference("claude:a%zz", "two hexadecimal digits")
      reference("claude:100%", "two hexadecimal digits")
      reference("claude:%FF", "not UTF-8")
      reference("claude:%00", "control character")
      reference("claude:" + "m" * 101, "longer than 100")
      reference("pi:" + "p" * 101 + "/m", "the provider is longer than 100")
      reference("\"claude:a b\"", "a space in a model name is written %20")
      reference("claude:a b", "a space in a model name is written %20")
      reference("\"claude:a#b\"", "'#' in a model name is written %23")
      reference("claude:a#b", "'#' in a model name is written %23")
      reference("\"claude:a,b\"", "written %2C")
      reference("\"claude:[a]\"", "written %5B")
      reference("\"claude:{a}\"", "written %7B")
    }

    "refuse a harness prefix, a tier and a malformed name in a tier entry" in {
      def entries(value: String, fragment: String): Unit = refused(s"harnesses: { pi: { tiers: { fast: [$value] } } }", at(1, 36), fragment)
      entries("pi:zai/a", "no harness prefix")
      entries("claude:opus", "no harness prefix")
      entries("\"$harness:zai/a\"", "no harness prefix")
      entries("\"@fast\"", "not tiers")
      entries("zai/", "the model is empty")
      entries("zai/a?effort=fastest", "unknown effort")
      entries("\"zai/a b\"", "written %20")
    }

    "render a reference, a route, a seat and a role value back to the text that reads as the same value" in {
      // No property-testing library is among the project's dependencies: a fixed seed over an alphabet of the characters the grammar treats specially.
      val random = new scala.util.Random(17)
      val alphabet = List("a", "b", "Z", "0", "-", ".", "_", "%", "?", "#", ",", "[", "]", "{", "}", " ", "@", ":", "/", "\"", "'", "\\", "!", "&", "*", "|", ">", "=", "$", "λ", "😀", "~", "`", "+")
      def text(): String = List.fill(1 + random.nextInt(8))(alphabet(random.nextInt(alphabet.size))).mkString
      def pick[A](values: List[A]): A = values(random.nextInt(values.size))
      def effort(): Option[Effort] = Option.when(random.nextBoolean())(pick(Effort.all))
      def reference(): ModelReference = ModelReference(Governing,
        if (random.nextInt(4) == 0) ModelTarget.Tier(pick(ModelTier.all)) else ModelTarget.Exact(ModelName(Option.when(random.nextBoolean())(text()), text())), effort())
      def seat(): SeatChoice = if (random.nextBoolean()) SeatChoice.Single(reference()) else SeatChoice.Strategy(pick(SeatStrategy.all), List.fill(1 + random.nextInt(3))(reference()))
      (1 to 2000).foreach { _ =>
        val value = reference()
        val rendered = AgentConfigText.render(value)
        assert(worker(rendered) == single(value), rendered)
        assert(assigned(parsed(s"defaults:\n  roles:\n    worker: $rendered   # trailing\n"), AgentRole.Worker) == single(value), rendered)
        assert(assigned(parsed(s"defaults: { roles: { worker: \"${rendered.replace("\\", "\\\\").replace("\"", "\\\"")}\" } }"), AgentRole.Worker) == single(value), rendered)
      }
      (1 to 500).foreach { _ =>
        val choice = RoleChoice.Seat(seat())
        assert(worker(AgentConfigText.render(choice)) == choice, AgentConfigText.render(choice))
        val seats = List.fill(1 + random.nextInt(3))(seat())
        val panel = RoleChoice.Panel(pick(PanelMode.all), seats, 1 + random.nextInt(seats.size))
        val rendered = AgentConfigText.render(panel)
        assert(assigned(parsed(s"defaults:\n  roles:\n    reviewer: $rendered\n"), AgentRole.Reviewer) == panel, rendered)
      }
      (1 to 500).foreach { _ =>
        val value = ModelRoute(Harness.Codex, Option.when(random.nextBoolean())(text()), text(), Option.when(random.nextBoolean())(Effort.High))
        val rendered = AgentConfigText.render(value)
        assert(worker(rendered) == single(ModelReference(named(Harness.Codex), ModelTarget.Exact(ModelName(value.provider, value.model)), value.effort)), rendered)
      }
      assert(AgentConfigText.render(ModelReference(Governing, ModelTarget.Exact(ModelName(Some("a/b"), "@c d:")), Some(Effort.XHigh))) == "$harness:a%2Fb/@c%20d%3A?effort=xhigh")
      assert(AgentConfigText.render(ModelReference(named(Harness.Claude), ModelTarget.Exact(ModelName(None, "@x/y")), None)) == "claude:%40x%2Fy")
      assert(AgentConfigText.render(ModelReference(named(Harness.Pi), ModelTarget.Tier(ModelTier.Frontier), Some(Effort.Off))) == "pi:@frontier?effort=off")
      assert(AgentConfigText.render(ModelRoute(Harness.Pi, Some("zai"), "glm-5.3", Some(Effort.Minimal))) == "pi:zai/glm-5.3?effort=minimal")
      assert(AgentConfigText.render(SeatChoice.Strategy(SeatStrategy.RoundRobin, List(tier(Governing, ModelTier.Fast), exact(named(Harness.Claude), "opus")))) == "{ rr: [$harness:@fast, claude:opus] }")
      assert(AgentConfigText.render(RoleChoice.Panel(PanelMode.Any, List(SeatChoice.Single(exact(named(Harness.Claude), "opus")),
        SeatChoice.Strategy(SeatStrategy.First, List(exact(named(Harness.Codex), "gpt")))), 1)) == "{ any: [claude:opus, { first: [codex:gpt] }], min: 1 }")
    }

    "render a resolved role as a role value that resolves to the same plan" in {
      val approved = parsed(Approved)
      for (harness <- Harness.all; role <- List("planner" -> AgentRole.Planner, "worker" -> AgentRole.Worker, "explorer" -> AgentRole.Explorer, "reviewer" -> AgentRole.Reviewer)) {
        val plan = resolution(approved, ParsedAgents.empty, harness, role._2) match {
          case RoleResolution.Resolved(value) => value
          case other => fail(other.toString)
        }
        val again = resolved(s"defaults:\n  roles:\n    ${role._1}: ${AgentConfigText.render(plan)}\n", "", harness, role._2)
        assert(again == plan.copy(origin = RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles)), s"$harness ${role._1}")
      }
      assert(AgentConfigText.render(ResolvedSeat(SeatStrategy.First, List(route(Harness.Claude, "opus")))) == "{ first: [claude:opus] }")
      assert(AgentConfigText.render(ResolvedSeat(SeatStrategy.RoundRobin, List(route(Harness.Claude, "opus"), route(Harness.Pi, "zai/a", Effort.Low)))) == "{ rr: [claude:opus, pi:zai/a?effort=low] }")
    }
  }

  "The problems that reject a text when it is saved" should {
    "include a Pi model without a provider and a Claude model with one, in a tier and in a reference with a known harness" in {
      assert(problems("harnesses: { pi: { tiers: { fast: [glm, zai/glm] } } }") == List(AgentProblem.ProviderRequired(at(1, 36), Harness.Pi)))
      assert(problems(inRoles("pi:glm")) == List(AgentProblem.ProviderRequired(at(1, ValueColumn), Harness.Pi)))
      assert(problems(inRoles("{ fallback: [claude:opus, pi:glm] }")) == List(AgentProblem.ProviderRequired(at(1, 56), Harness.Pi)))
      assert(problems("harnesses: { claude: { tiers: { fast: [anthropic/haiku] } } }") == List(AgentProblem.ProviderNotAllowed(at(1, 40), Harness.Claude)))
      assert(problems(inRoles("claude:anthropic/opus")) == List(AgentProblem.ProviderNotAllowed(at(1, ValueColumn), Harness.Claude)))
      assert(problems("defaults: { roles: { reviewer: { any: [codex:gpt, { rr: [claude:anthropic/opus] }], min: 1 } } }") ==
        List(AgentProblem.ProviderNotAllowed(at(1, 58), Harness.Claude)))
      // Codex takes a model with or without a provider.
      assert(problems("harnesses: { codex: { tiers: { fast: [gpt, openai/gpt] } } }").isEmpty && problems(inRoles("{ rr: [codex:gpt, codex:openai/gpt] }")).isEmpty)
    }

    "include an effort the harness does not take, by the set of each harness" in {
      val accepted = Map[Harness, Set[Effort]](
        Harness.Claude -> Set(Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max),
        Harness.Codex -> Set(Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max, Effort.Ultra),
        Harness.Pi -> Set(Effort.Off, Effort.Minimal, Effort.Low, Effort.Medium, Effort.High, Effort.XHigh, Effort.Max))
      val names = Map[Harness, (String, String)](Harness.Claude -> ("claude", "opus"), Harness.Codex -> ("codex", "gpt"), Harness.Pi -> ("pi", "zai/glm"))
      val texts = List("off", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
      assert(texts.size == Effort.all.size && Effort.all.map(AgentResolution.effortName) == texts)
      for (harness <- Harness.all; (effort, text) <- Effort.all.zip(texts)) {
        val (key, model) = names(harness)
        assert(AgentResolution.efforts(harness) == accepted(harness))
        val expected = if (accepted(harness)(effort)) Nil else List(AgentProblem.EffortUnsupported(at(1, ValueColumn), harness, effort))
        assert(problems(inRoles(s"$key:$model?effort=$text")) == expected, s"$key $text")
        assert(problems(inRoles(s"$key:@fast?effort=$text")) == expected, s"$key tier $text")
        val entry = s"harnesses: { $key: { tiers: { fast: [$model?effort=$text] } } }"
        assert(problems(entry) == expected.map(_ => AgentProblem.EffortUnsupported(at(1, 34 + key.length), harness, effort)), entry)
      }
    }

    "include a Pi model name that ends in a colon and one of Pi's thinking levels, in a tier and in a reference with a known harness" in {
      Effort.all.filter(AgentResolution.efforts(Harness.Pi)).map(AgentResolution.effortName).foreach { level =>
        assert(AgentResolution.piThinkingSuffix(s"glm:$level") && AgentResolution.piThinkingSuffix(s"openai/gpt-4o:extended:$level"), level)
        assert(problems(inRoles(s"pi:zai/glm:$level")) == List(AgentProblem.ModelAmbiguous(at(1, ValueColumn), Harness.Pi, s"glm:$level")), level)
        assert(problems(inRoles(s"pi:zai/glm:$level?effort=low")) == List(AgentProblem.ModelAmbiguous(at(1, ValueColumn), Harness.Pi, s"glm:$level")), level)
        assert(problems(s"harnesses: { pi: { tiers: { fast: [zai/glm:$level] } } }") == List(AgentProblem.ModelAmbiguous(at(1, 36), Harness.Pi, s"glm:$level")), level)
        // Claude Code and Codex pass a model name on as it is written.
        assert(problems(inRoles(s"{ rr: [claude:opus:$level, codex:gpt:$level] }")).isEmpty, level)
      }
      // A colon elsewhere, another ending, and a level Pi does not name leave the name whole.
      List("glm:exacto", "glm:high:exacto", "glm:High", "glm:ultra", "glm-high", "high").foreach { model =>
        assert(!AgentResolution.piThinkingSuffix(model) && problems(inRoles(s"pi:zai/$model")).isEmpty, model)
      }
      assert(!AgentResolution.piThinkingSuffix("glm:") && !AgentResolution.piThinkingSuffix(""))
    }

    "check a $harness reference under harnesses.<harness>.roles as a reference to that harness, and no other $harness reference" in {
      assert(problems("harnesses: { claude: { roles: { worker: $harness:zai/glm?effort=off } } }") ==
        List(AgentProblem.ProviderNotAllowed(at(1, 41), Harness.Claude), AgentProblem.EffortUnsupported(at(1, 41), Harness.Claude, Effort.Off)))
      assert(problems("harnesses: { pi: { roles: { worker: \"$harness:glm\" } } }") == List(AgentProblem.ProviderRequired(at(1, 37), Harness.Pi)))
      assert(problems("harnesses: { codex: { roles: { worker: $harness:@fast?effort=off } } }") == List(AgentProblem.EffortUnsupported(at(1, 40), Harness.Codex, Effort.Off)))
      assert(problems(inRoles("$harness:zai/glm?effort=off")).isEmpty)
      assert(problems(inRoles("{ rr: [$harness:glm, $harness:@fast?effort=off] }")).isEmpty)
    }

    "leave out what depends on the other layer: an unassigned role and an undefined tier" in {
      assert(problems("harnesses: { pi: { roles: { worker: pi:@fast } } }").isEmpty)
      assert(problems("defaults: { roles: { planner: claude:@frontier } }").isEmpty)
    }

    "list every problem of a text, in the order of the text" in {
      val text =
        """defaults:
          |  roles:
          |    planner: { all: [claude:opus], min: 1 }
          |    worker: pi:glm
          |    explorer: claude:opus?effort=off
          |    reviewer: { any: [claude:x/opus, nope], min: 5 }
          |    governor: claude:opus
          |harnesses:
          |  pi: { tiers: { fast: [], standard: [glm] }, models: [] }
          |""".stripMargin
      assert(problems(text) == List(
        AgentProblem.PanelNotAllowed(at(3, 14), AgentRole.Planner),
        AgentProblem.ProviderRequired(at(4, 13), Harness.Pi),
        AgentProblem.EffortUnsupported(at(5, 15), Harness.Claude, Effort.Off),
        AgentProblem.ProviderNotAllowed(at(6, 23), Harness.Claude),
        AgentProblem.Syntax(at(6, 38), "expected a model reference: harness:model, harness:provider/model, harness:@tier or $harness:…"),
        AgentProblem.InvalidMinimum(at(6, 50), 5, 2),
        AgentProblem.UnknownKey(at(7, 5), "governor"),
        AgentProblem.EmptyList(at(9, 24)),
        AgentProblem.ProviderRequired(at(9, 39), Harness.Pi),
        AgentProblem.UnknownKey(at(9, 47), "models")))
      assert(AgentConfigText.parse(text) == Left(problems(text)))
    }

    "describe every problem in one line" in {
      val position = at(3, 7)
      assert(List[AgentProblem](
        AgentProblem.Syntax(position, "anchors are not supported"),
        AgentProblem.UnknownKey(position, "role"),
        AgentProblem.EmptyList(position),
        AgentProblem.InvalidMinimum(position, 3, 2),
        AgentProblem.PanelNotAllowed(position, AgentRole.Worker),
        AgentProblem.ProviderRequired(position, Harness.Pi),
        AgentProblem.ProviderNotAllowed(position, Harness.Claude),
        AgentProblem.EffortUnsupported(position, Harness.Claude, Effort.Off),
        AgentProblem.EffortUnsupported(position, Harness.Pi, Effort.Ultra),
        AgentProblem.ModelAmbiguous(position, Harness.Pi, "glm:high"),
        AgentProblem.RoleUnassigned(Harness.Codex, AgentRole.Explorer),
        AgentProblem.TierUndefined(Harness.Pi, ModelTier.Fast, AgentRole.Reviewer)).map(AgentConfigText.describe) == List(
        "3:7: anchors are not supported",
        "3:7: unknown key 'role'",
        "3:7: the list is empty",
        "3:7: min is 3, and a panel of 2 seats takes a min from 1 to 2",
        "3:7: the worker role takes a model reference or a strategy; only the reviewer role takes a panel",
        "3:7: a pi model is written provider/model",
        "3:7: a claude model is written without a provider",
        "3:7: claude does not take effort off; it takes low, medium, high, xhigh, max",
        "3:7: pi does not take effort ultra; it takes off, minimal, low, medium, high, xhigh, max",
        "3:7: pi reads the ending of the model name 'glm:high' as a thinking level, so the name selects no one model; a level is written ?effort=…",
        "no layer assigns the explorer role when codex governs",
        "the reviewer role refers to the fast tier of pi, which no layer defines"))
    }
  }

  "Role resolution" should {
    val installation =
      """defaults: { roles: { worker: claude:installation-default, explorer: claude:installation-explorer } }
        |harnesses: { codex: { roles: { worker: claude:installation-codex } } }
        |""".stripMargin
    def origin(layer: AgentLayer, source: RoleSource): RoleOrigin = RoleOrigin(layer, source)
    def one(model: String, where: RoleOrigin): ResolvedRole = ResolvedRole(PanelMode.All, 1, List(fallback(route(Harness.Claude, model))), where, Nil)

    "look the role up in the project's harness roles, the project's defaults, the installation's harness roles, the installation's defaults" in {
      val projectDefaults = "defaults: { roles: { worker: claude:project-default } }"
      val projectCodex = "harnesses: { codex: { roles: { worker: claude:project-codex } } }"
      assert(resolved(installation, "", Harness.Codex, AgentRole.Worker) == one("installation-codex", origin(AgentLayer.Installation, RoleSource.HarnessRoles)))
      assert(resolved(installation, "", Harness.Claude, AgentRole.Worker) == one("installation-default", origin(AgentLayer.Installation, RoleSource.DefaultRoles)))
      // The lookup goes layer by layer, not through a merged map: the project's defaults beat the installation's roles of the harness.
      assert(resolved(installation, projectDefaults, Harness.Codex, AgentRole.Worker) == one("project-default", origin(AgentLayer.Project, RoleSource.DefaultRoles)))
      assert(resolved(installation, projectDefaults, Harness.Claude, AgentRole.Worker) == one("project-default", origin(AgentLayer.Project, RoleSource.DefaultRoles)))
      assert(resolved(installation, projectCodex, Harness.Codex, AgentRole.Worker) == one("project-codex", origin(AgentLayer.Project, RoleSource.HarnessRoles)))
      assert(resolved(installation, projectCodex, Harness.Claude, AgentRole.Worker) == one("installation-default", origin(AgentLayer.Installation, RoleSource.DefaultRoles)))
      assert(resolved(installation, projectDefaults + "\n" + projectCodex, Harness.Codex, AgentRole.Worker) == one("project-codex", origin(AgentLayer.Project, RoleSource.HarnessRoles)))
      assert(resolved(installation, projectDefaults + "\n" + projectCodex, Harness.Pi, AgentRole.Worker) == one("project-default", origin(AgentLayer.Project, RoleSource.DefaultRoles)))
      // A role the project does not assign is inherited key by key; the project's other keys do not hide it.
      assert(resolved(installation, projectDefaults, Harness.Codex, AgentRole.Explorer) == one("installation-explorer", origin(AgentLayer.Installation, RoleSource.DefaultRoles)))
      assert(resolved("", projectDefaults, Harness.Pi, AgentRole.Worker) == one("project-default", origin(AgentLayer.Project, RoleSource.DefaultRoles)))
    }

    val modes =
      """defaults: { roles: { reviewer: claude:base, reviewer/plan: claude:base-plan, worker/probe: claude:base-probe } }
        |harnesses: { codex: { roles: { reviewer/audit: claude:codex-audit } } }
        |""".stripMargin
    // Every model of these texts is one of Claude, so that under the other harnesses no review is a self-review.
    def decided(installation: String, project: String, governing: Harness, work: DispatchWork): (RoleKey, RoleResolution) = {
      val assignment = AgentResolution.resolve(parsed(installation), parsed(project), governing, work)
      assert(assignment.harness == governing)
      assignment.key -> assignment.resolution
    }
    def review(mode: ReviewerMode): DispatchWork = DispatchWork.Reviewer(mode)
    def by(key: RoleKey, model: String, layer: AgentLayer, source: RoleSource): (RoleKey, RoleResolution) = key -> RoleResolution.Resolved(one(model, origin(layer, source)))
    def base(key: RoleKey, model: String): (RoleKey, RoleResolution) = by(key, model, AgentLayer.Installation, RoleSource.DefaultRoles)

    "take the key of the mode over the key of the role in the place that holds both, and the key of the role for a mode without a key" in {
      assert(decided(modes, "", Harness.Pi, review(ReviewerMode.Plan)) == base(RoleKey.Reviewer(ReviewerMode.Plan), "base-plan"))
      assert(decided(modes, "", Harness.Pi, review(ReviewerMode.Candidate)) == base(plain(AgentRole.Reviewer), "base"))
      assert(decided(modes, "", Harness.Pi, review(ReviewerMode.Audit)) == base(plain(AgentRole.Reviewer), "base"))
      // The place of a harness that holds the key of a mode decides that mode alone.
      assert(decided(modes, "", Harness.Codex, review(ReviewerMode.Audit)) == by(RoleKey.Reviewer(ReviewerMode.Audit), "codex-audit", AgentLayer.Installation, RoleSource.HarnessRoles))
      assert(decided(modes, "", Harness.Codex, review(ReviewerMode.Plan)) == base(RoleKey.Reviewer(ReviewerMode.Plan), "base-plan"))
      assert(decided(modes, "", Harness.Codex, review(ReviewerMode.Candidate)) == base(plain(AgentRole.Reviewer), "base"))
      // A key of a mode assigns nothing to the other modes of its role.
      assert(decided(modes, "", Harness.Pi, DispatchWork.Worker(WorkerMode.Probe)) == base(RoleKey.Worker(WorkerMode.Probe), "base-probe"))
      List(WorkerMode.Implement, WorkerMode.ResolveConflict).foreach { mode =>
        assert(decided(modes, "", Harness.Pi, DispatchWork.Worker(mode)) ==
          (plain(AgentRole.Worker) -> RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(Harness.Pi, AgentRole.Worker)))), mode.toString)
      }
    }

    "let the first place that holds the key of the mode or the key of the role decide, in the order of the places" in {
      val projectCodex = "harnesses: { codex: { roles: { reviewer: claude:project-codex } } }"
      val projectDefault = "defaults: { roles: { reviewer: claude:project-default } }"
      val projectPlan = "defaults: { roles: { reviewer/plan: claude:project-plan } }"
      // The key of the role in an earlier place beats the key of the mode in a later one.
      ReviewerMode.all.foreach { mode =>
        assert(decided(modes, projectCodex, Harness.Codex, review(mode)) == by(plain(AgentRole.Reviewer), "project-codex", AgentLayer.Project, RoleSource.HarnessRoles), mode.toString)
        List(Harness.Codex, Harness.Pi).foreach(harness =>
          assert(decided(modes, projectDefault, harness, review(mode)) == by(plain(AgentRole.Reviewer), "project-default", AgentLayer.Project, RoleSource.DefaultRoles), s"$harness $mode"))
      }
      assert(decided(modes, projectCodex, Harness.Pi, review(ReviewerMode.Plan)) == by(RoleKey.Reviewer(ReviewerMode.Plan), "base-plan", AgentLayer.Installation, RoleSource.DefaultRoles))
      // The key of the mode in an earlier place decides its mode, and the other modes go on to the later places.
      assert(decided(modes, projectPlan, Harness.Codex, review(ReviewerMode.Plan)) == by(RoleKey.Reviewer(ReviewerMode.Plan), "project-plan", AgentLayer.Project, RoleSource.DefaultRoles))
      assert(decided(modes, projectPlan, Harness.Codex, review(ReviewerMode.Audit)) == by(RoleKey.Reviewer(ReviewerMode.Audit), "codex-audit", AgentLayer.Installation, RoleSource.HarnessRoles))
      assert(decided(modes, projectPlan, Harness.Codex, review(ReviewerMode.Candidate)) == by(plain(AgentRole.Reviewer), "base", AgentLayer.Installation, RoleSource.DefaultRoles))
      assert(decided(modes, projectPlan + "\n" + projectCodex, Harness.Codex, review(ReviewerMode.Plan)) == by(plain(AgentRole.Reviewer), "project-codex", AgentLayer.Project, RoleSource.HarnessRoles))
    }

    "list a role once for its own key and once for every key of a mode that decides that mode" in {
      def keys(installation: String, project: String, harness: Harness): List[RoleKey] =
        AgentResolution.assignments(parsed(installation), parsed(project)).filter(_.harness == harness).map(_.key)
      val others = List(plain(AgentRole.Planner), plain(AgentRole.Worker), RoleKey.Worker(WorkerMode.Probe), plain(AgentRole.Explorer))
      assert(keys(modes, "", Harness.Claude) == others ++ List(plain(AgentRole.Reviewer), RoleKey.Reviewer(ReviewerMode.Plan)))
      assert(keys(modes, "", Harness.Codex) == others ++ List(plain(AgentRole.Reviewer), RoleKey.Reviewer(ReviewerMode.Plan), RoleKey.Reviewer(ReviewerMode.Audit)))
      // A key of a mode that an earlier key of the role hides decides nothing and is not listed.
      assert(keys(modes, "defaults: { roles: { reviewer: claude:project-default } }", Harness.Codex) == others :+ plain(AgentRole.Reviewer))
      // The key of the role comes first whichever mode it decides, and is left out when every mode has a key of its own.
      assert(keys("defaults: { roles: { reviewer: claude:a, reviewer/candidate: claude:b } }", "", Harness.Pi).drop(3) == List(plain(AgentRole.Reviewer), RoleKey.Reviewer(ReviewerMode.Candidate)))
      assert(keys("defaults: { roles: { explorer/investigate: claude:a, explorer/research: claude:b, explorer: claude:c } }", "", Harness.Pi) ==
        List(plain(AgentRole.Planner), plain(AgentRole.Worker), RoleKey.Explorer(ExplorerMode.Investigate), RoleKey.Explorer(ExplorerMode.Research), plain(AgentRole.Reviewer)))
      val listed = AgentResolution.assignments(parsed(modes), ParsedAgents.empty)
      assert(listed.find(value => value.harness == Harness.Codex && value.key == RoleKey.Reviewer(ReviewerMode.Audit)).map(_.resolution).contains(
        RoleResolution.Resolved(one("codex-audit", origin(AgentLayer.Installation, RoleSource.HarnessRoles)))))
      assert(listed.find(value => value.harness == Harness.Codex && value.key == plain(AgentRole.Worker)).map(_.resolution).contains(
        RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(Harness.Codex, AgentRole.Worker)))))
    }

    "resolve the value of a key of a mode as the value of a role: its positions, its tiers and its self-review" in {
      val text =
        """defaults:
          |  roles:
          |    reviewer: claude:opus
          |    reviewer/plan: { all: [claude:opus, $harness:own], min: 1 }
          |    worker/probe: $harness:@fast
          |""".stripMargin
      val defaults = Some(origin(AgentLayer.Installation, RoleSource.DefaultRoles))
      assert(decided(text, "", Harness.Codex, review(ReviewerMode.Plan))._2 == RoleResolution.Resolved(ResolvedRole(PanelMode.All, 1,
        List(fallback(route(Harness.Claude, "opus")), fallback(route(Harness.Codex, "own"))), defaults.get, List(1))))
      assert(decided(text, "", Harness.Codex, review(ReviewerMode.Audit))._2 == RoleResolution.Resolved(one("opus", defaults.get)))
      // The problem of a reference stands where the key of the mode wrote it.
      assert(decided(text, "", Harness.Pi, review(ReviewerMode.Plan)) ==
        (RoleKey.Reviewer(ReviewerMode.Plan) -> RoleResolution.Unresolved(defaults, List(AgentProblem.ProviderRequired(at(4, 41), Harness.Pi)))))
      assert(decided(text, "", Harness.Pi, DispatchWork.Worker(WorkerMode.Probe)) ==
        (RoleKey.Worker(WorkerMode.Probe) -> RoleResolution.Unresolved(defaults, List(AgentProblem.TierUndefined(Harness.Pi, ModelTier.Fast, AgentRole.Worker)))))
      // A review alone is a self-review.
      assert(decided("defaults: { roles: { worker/probe: $harness:own } }", "", Harness.Codex, DispatchWork.Worker(WorkerMode.Probe))._2 ==
        RoleResolution.Resolved(ResolvedRole(PanelMode.All, 1, List(fallback(route(Harness.Codex, "own"))), defaults.get, Nil)))
    }

    "report a role that no layer assigns, for the governing harness" in {
      for (harness <- Harness.all; role <- AgentRole.all)
        assert(resolution("", "", harness, role) == RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(harness, role))))
      assert(resolution(installation, "", Harness.Pi, AgentRole.Reviewer) == RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(Harness.Pi, AgentRole.Reviewer))))
      assert(resolution("", "harnesses: { codex: { roles: { planner: claude:opus } } }", Harness.Claude, AgentRole.Planner) ==
        RoleResolution.Unresolved(None, List(AgentProblem.RoleUnassigned(Harness.Claude, AgentRole.Planner))))
    }

    val tiers =
      """defaults: { roles: { worker: pi:@standard, explorer: pi:@fast, planner: "$harness:@frontier" } }
        |harnesses:
        |  pi: { tiers: { standard: [zai/a?effort=low, zai/b], fast: [zai/c] } }
        |  codex: { tiers: { frontier: [gpt-big] } }
        |""".stripMargin

    "replace a tier's list whole with the project's, key by key, for references of either layer" in {
      val project = "harnesses: { pi: { tiers: { standard: [zai/x] } }, claude: { tiers: { frontier: [opus] } } }"
      assert(shown(tiers, "", Harness.Claude, AgentRole.Worker) == "{ fallback: [pi:zai/a?effort=low, pi:zai/b] }")
      assert(shown(tiers, project, Harness.Claude, AgentRole.Worker) == "pi:zai/x")
      assert(resolved(tiers, project, Harness.Claude, AgentRole.Worker).origin == origin(AgentLayer.Installation, RoleSource.DefaultRoles))
      assert(shown(tiers, project, Harness.Claude, AgentRole.Explorer) == "pi:zai/c")
      assert(shown(tiers, project, Harness.Claude, AgentRole.Planner) == "claude:opus")
      assert(shown(tiers, project, Harness.Codex, AgentRole.Planner) == "codex:gpt-big")
      // A project role reads the installation's tiers where the project defines none.
      assert(shown(tiers, "defaults: { roles: { reviewer: pi:@fast } }", Harness.Codex, AgentRole.Reviewer) == "pi:zai/c")
    }

    "report a tier that neither layer defines, when the role is resolved" in {
      assert(unresolved(tiers, "", Harness.Claude, AgentRole.Planner) ==
        RoleResolution.Unresolved(Some(origin(AgentLayer.Installation, RoleSource.DefaultRoles)), List(AgentProblem.TierUndefined(Harness.Claude, ModelTier.Frontier, AgentRole.Planner))))
      assert(unresolved("", "harnesses: { codex: { roles: { reviewer: { all: [pi:@fast, claude:@fast, claude:opus], min: 1 } } } }", Harness.Codex, AgentRole.Reviewer) ==
        RoleResolution.Unresolved(Some(origin(AgentLayer.Project, RoleSource.HarnessRoles)), List(
          AgentProblem.TierUndefined(Harness.Pi, ModelTier.Fast, AgentRole.Reviewer), AgentProblem.TierUndefined(Harness.Claude, ModelTier.Fast, AgentRole.Reviewer))))
    }

    "take the effort of the reference over the effort of the tier entry" in {
      def worker(reference: String): String = shown(tiers, s"defaults: { roles: { worker: \"$reference\" } }", Harness.Codex, AgentRole.Worker)
      assert(worker("pi:@standard") == "{ fallback: [pi:zai/a?effort=low, pi:zai/b] }")
      assert(worker("pi:@standard?effort=high") == "{ fallback: [pi:zai/a?effort=high, pi:zai/b?effort=high] }")
      assert(worker("pi:zai/a") == "pi:zai/a")
      assert(worker("pi:zai/a?effort=max") == "pi:zai/a?effort=max")
      assert(worker("$harness:gpt?effort=minimal") == "codex:gpt?effort=minimal")
    }

    "expand and flatten the entries of a strategy, and normalise a seat" in {
      def worker(value: String): ResolvedRole = resolved(tiers, s"defaults: { roles: { worker: $value } }", Harness.Codex, AgentRole.Worker)
      val a = route(Harness.Pi, "zai/a", Effort.Low)
      val b = route(Harness.Pi, "zai/b")
      val c = route(Harness.Pi, "zai/c")
      val opus = route(Harness.Claude, "opus")
      def seats(value: String): List[ResolvedSeat] = {
        val plan = worker(value)
        assert(plan.mode == PanelMode.All && plan.min == 1 && plan.selfReview.isEmpty)
        plan.seats
      }
      // One model is a fallback over one candidate; a bare tier of several models is a fallback over them.
      assert(seats("claude:opus") == List(fallback(opus)))
      assert(seats("pi:@standard") == List(fallback(a, b)))
      // A fallback tries a route once: a later occurrence of the same harness, provider, model and effort is dropped, the first kept.
      assert(seats("{ fallback: [claude:opus, pi:@standard, pi:@fast, claude:opus] }") == List(fallback(opus, a, b, c)))
      assert(seats("{ fallback: [pi:@standard, pi:zai/b, \"pi:zai/a?effort=low\", pi:zai/a, claude:opus?effort=high, claude:opus] }") ==
        List(fallback(a, b, route(Harness.Pi, "zai/a"), route(Harness.Claude, "opus", Effort.High), opus)))
      // The same holds for the implicit fallback of a bare tier that lists a model twice.
      def doubled(value: String): List[ResolvedSeat] =
        resolved(s"defaults: { roles: { worker: $value } }\nharnesses: { pi: { tiers: { fast: [zai/c, zai/c] } } }", "", Harness.Codex, AgentRole.Worker).seats
      assert(doubled("pi:@fast") == List(fallback(c)))
      // A round-robin keeps what is listed: a repeated route there takes more of the turns.
      assert(seats("{ rr: [pi:@standard, pi:zai/b] }") == List(ResolvedSeat(SeatStrategy.RoundRobin, List(a, b, b))))
      assert(doubled("{ rr: [pi:@fast] }") == List(ResolvedSeat(SeatStrategy.RoundRobin, List(c, c))))
      assert(seats("{ rr: [pi:@standard, claude:opus, pi:@fast] }") == List(ResolvedSeat(SeatStrategy.RoundRobin, List(a, b, opus, c))))
      // `first` runs the first candidate of the flattened list and reads nothing after its first entry.
      assert(seats("{ first: [pi:@standard, claude:opus] }") == List(ResolvedSeat(SeatStrategy.First, List(a))))
      assert(seats("{ first: [claude:opus, claude:@fast, \"$harness:zai/x?effort=off\"] }") == List(ResolvedSeat(SeatStrategy.First, List(opus))))
      assert(unresolved(tiers, "defaults: { roles: { worker: { first: [claude:@fast, claude:opus] } } }", Harness.Codex, AgentRole.Worker).problems ==
        List(AgentProblem.TierUndefined(Harness.Claude, ModelTier.Fast, AgentRole.Worker)))
    }

    "keep one seat per entry of a panel, with its mode and min" in {
      def reviewer(value: String): ResolvedRole = resolved(tiers, s"defaults: { roles: { reviewer: $value } }", Harness.Codex, AgentRole.Reviewer)
      val a = route(Harness.Pi, "zai/a", Effort.Low)
      val b = route(Harness.Pi, "zai/b")
      val c = route(Harness.Pi, "zai/c")
      val opus = route(Harness.Claude, "opus")
      val projectDefaults = RoleOrigin(AgentLayer.Project, RoleSource.DefaultRoles)
      assert(reviewer("{ all: [pi:@standard, claude:opus, { rr: [pi:@fast, pi:@standard] }, { first: [pi:@standard] }], min: 3 }") ==
        ResolvedRole(PanelMode.All, 3, List(fallback(a, b), fallback(opus), ResolvedSeat(SeatStrategy.RoundRobin, List(c, a, b)), ResolvedSeat(SeatStrategy.First, List(a))), projectDefaults, Nil))
      assert(reviewer("{ any: [claude:opus, pi:@fast], min: 2 }") == ResolvedRole(PanelMode.Any, 2, List(fallback(opus), fallback(c)), projectDefaults, Nil))
      assert(reviewer("{ any: [claude:opus], min: 1 }") == ResolvedRole(PanelMode.Any, 1, List(fallback(opus)), projectDefaults, Nil))
      assert(reviewer("claude:opus") == ResolvedRole(PanelMode.All, 1, List(fallback(opus)), projectDefaults, Nil))
    }

    "put the governing harness for $harness wherever a reference stands" in {
      val text =
        """defaults:
          |  roles:
          |    planner: $harness:@frontier?effort=high
          |    worker: { fallback: [$harness:@frontier, $harness:big/model] }
          |    explorer: { rr: [claude:haiku, $harness:big/model?effort=low] }
          |    reviewer: { all: [$harness:@frontier, { first: [$harness:big/model] }, { rr: [codex:gpt, $harness:@frontier] }], min: 2 }
          |harnesses:
          |  codex:
          |    tiers: { frontier: [openai/gpt-big, gpt-small?effort=low] }
          |    roles: { explorer: $harness:@frontier }
          |  pi:
          |    tiers: { frontier: [zai/glm] }
          |    roles: { planner: { first: [$harness:other/model] } }
          |""".stripMargin
      assert(shown(text, "", Harness.Codex, AgentRole.Planner) == "{ fallback: [codex:openai/gpt-big?effort=high, codex:gpt-small?effort=high] }")
      assert(shown(text, "", Harness.Pi, AgentRole.Planner) == "{ first: [pi:other/model] }")
      assert(shown(text, "", Harness.Codex, AgentRole.Worker) == "{ fallback: [codex:openai/gpt-big, codex:gpt-small?effort=low, codex:big/model] }")
      assert(shown(text, "", Harness.Pi, AgentRole.Worker) == "{ fallback: [pi:zai/glm, pi:big/model] }")
      assert(shown(text, "", Harness.Codex, AgentRole.Explorer) == "{ fallback: [codex:openai/gpt-big, codex:gpt-small?effort=low] }")
      assert(shown(text, "", Harness.Pi, AgentRole.Explorer) == "{ rr: [claude:haiku, pi:big/model?effort=low] }")
      assert(shown(text, "", Harness.Pi, AgentRole.Reviewer) == "{ all: [pi:zai/glm, { first: [pi:big/model] }, { rr: [codex:gpt, pi:zai/glm] }], min: 2 }")
      assert(shown(text, "", Harness.Codex, AgentRole.Reviewer) ==
        "{ all: [{ fallback: [codex:openai/gpt-big, codex:gpt-small?effort=low] }, { first: [codex:big/model] }, { rr: [codex:gpt, codex:openai/gpt-big, codex:gpt-small?effort=low] }], min: 2 }")
      // The same text names a provider that Claude does not take: reported when Claude governs, with the reference's place in its layer.
      assert(unresolved(text, "", Harness.Claude, AgentRole.Explorer) == RoleResolution.Unresolved(
        Some(RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles)), List(AgentProblem.ProviderNotAllowed(at(5, 36), Harness.Claude))))
    }

    "report, and not refuse at saving, what a $harness reference means under one governing harness" in {
      val text = "defaults:\n  roles:\n    worker: { fallback: [claude:opus, $harness:zai/glm?effort=off] }\n    planner: $harness:glm\n    explorer: $harness:@fast?effort=off\n" +
        "harnesses: { claude: { tiers: { fast: [haiku] } }, codex: { tiers: { fast: [gpt] } }, pi: { tiers: { fast: [zai/glm] } } }\n"
      assert(problems(text).isEmpty)
      val defaults = Some(RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles))
      assert(resolution(text, "", Harness.Claude, AgentRole.Worker) == RoleResolution.Unresolved(defaults,
        List(AgentProblem.ProviderNotAllowed(at(3, 39), Harness.Claude), AgentProblem.EffortUnsupported(at(3, 39), Harness.Claude, Effort.Off))))
      assert(resolution(text, "", Harness.Codex, AgentRole.Worker) == RoleResolution.Unresolved(defaults, List(AgentProblem.EffortUnsupported(at(3, 39), Harness.Codex, Effort.Off))))
      assert(shown(text, "", Harness.Pi, AgentRole.Worker) == "{ fallback: [claude:opus, pi:zai/glm?effort=off] }")
      assert(resolution(text, "", Harness.Pi, AgentRole.Planner) == RoleResolution.Unresolved(defaults, List(AgentProblem.ProviderRequired(at(4, 14), Harness.Pi))))
      assert(shown(text, "", Harness.Claude, AgentRole.Planner) == "claude:glm" && shown(text, "", Harness.Codex, AgentRole.Planner) == "codex:glm")
      assert(resolution(text, "", Harness.Claude, AgentRole.Explorer) == RoleResolution.Unresolved(defaults, List(AgentProblem.EffortUnsupported(at(5, 15), Harness.Claude, Effort.Off))))
      assert(shown(text, "", Harness.Pi, AgentRole.Explorer) == "pi:zai/glm?effort=off")
      // The position is that of the project's text when the project assigns the role.
      assert(resolution(text, "defaults: { roles: { planner: $harness:glm } }", Harness.Pi, AgentRole.Planner) ==
        RoleResolution.Unresolved(Some(RoleOrigin(AgentLayer.Project, RoleSource.DefaultRoles)), List(AgentProblem.ProviderRequired(at(1, 31), Harness.Pi))))
    }

    "note the reviewer seats that hold a model of the governing harness" in {
      val text =
        """defaults:
          |  roles:
          |    worker: $harness:model/x
          |    reviewer: { all: [claude:opus, $harness:model/x, { rr: [pi:zai/glm, codex:gpt] }, pi:@fast], min: 1 }
          |harnesses: { pi: { tiers: { fast: [zai/a] } } }
          |""".stripMargin
      assert(resolved(text, "", Harness.Codex, AgentRole.Reviewer).selfReview == List(1, 2))
      assert(resolved(text, "", Harness.Pi, AgentRole.Reviewer).selfReview == List(1, 2, 3))
      assert(resolved("defaults: { roles: { reviewer: { any: [claude:opus, codex:gpt], min: 1 } } }", "", Harness.Claude, AgentRole.Reviewer).selfReview == List(0))
      assert(resolved("defaults: { roles: { reviewer: { any: [claude:opus, codex:gpt], min: 1 } } }", "", Harness.Pi, AgentRole.Reviewer).selfReview.isEmpty)
      assert(resolved("defaults: { roles: { reviewer: $harness:model/x } }", "", Harness.Pi, AgentRole.Reviewer).selfReview == List(0))
      // Only a review of the governing harness's own work is noted.
      assert(resolved(text, "", Harness.Codex, AgentRole.Worker).selfReview.isEmpty)
    }
  }

  "A starting configuration (Behavioral Active Blackbox Atomic)" should {
    "write a starting configuration in which each harness of the settings runs its settings model and every role resolves" in {
      val settings = List(HarnessSetting(Harness.Claude, "/bin/claude", "opus", "anthropic", "1", Nil, Set.empty),
        HarnessSetting(Harness.Codex, "/bin/codex", "gpt-6.1", "openai", "1", Nil, Set.empty),
        HarnessSetting(Harness.Pi, "/bin/pi", "glm 5,max", "z ai", "1", Nil, Set.empty))
      val text = cq.core.AgentStarter.text(settings)
      val parsed = cq.core.AgentConfigText.parse(text).fold(problems => fail(problems.toString), identity)
      assert(text.contains("  codex:\n    tiers:\n      frontier: [gpt-6.1]\n      standard: [gpt-6.1]\n      fast: [gpt-6.1]\n"))
      // A review goes to another harness of the settings, in their order, and to the governing one only when the others abstain.
      assert(!text.contains("    reviewer: $harness") && cq.core.AgentStarter.note(settings).isEmpty &&
        text.contains("    roles:\n      reviewer: { fallback: [codex:@standard, pi:@standard, claude:@standard] }\n") &&
        text.contains("    roles:\n      reviewer: { fallback: [claude:@standard, pi:@standard, codex:@standard] }\n") &&
        text.contains("    roles:\n      reviewer: { fallback: [claude:@standard, codex:@standard, pi:@standard] }\n"))
      def own(setting: HarnessSetting): ModelRoute = ModelRoute(setting.harness, Option.when(setting.harness == Harness.Pi)("z ai"), setting.model, None)
      for (setting <- settings; role <- AgentRole.all) {
        val expected = if (role == AgentRole.Reviewer)
          ResolvedRole(PanelMode.All, 1, List(ResolvedSeat(SeatStrategy.Fallback, settings.filterNot(_ == setting).map(own) :+ own(setting))),
            RoleOrigin(AgentLayer.Installation, RoleSource.HarnessRoles), List(0))
        else ResolvedRole(PanelMode.All, 1, List(ResolvedSeat(SeatStrategy.Fallback, List(own(setting)))), RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles), Nil)
        assert(resolution(parsed, ParsedAgents.empty, setting.harness, role) == RoleResolution.Resolved(expected), s"${setting.harness} $role")
      }
      // With one harness there is no other to review: every role runs it, and the operator is told that reviews are self-reviews.
      val single = cq.core.AgentStarter.text(settings.take(1))
      assert(single.contains("    reviewer: $harness:@standard\n") && !single.contains("fallback") &&
        cq.core.AgentStarter.note(settings.take(1)).contains("The settings file holds one harness, so every review is a self-review by the governing harness until another harness is configured"))
      assert(resolution(cq.core.AgentConfigText.parse(single).toOption.get, ParsedAgents.empty, Harness.Claude, AgentRole.Reviewer) ==
        RoleResolution.Resolved(ResolvedRole(PanelMode.All, 1, List(ResolvedSeat(SeatStrategy.Fallback, List(own(settings.head)))), RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles), List(0))))
      // A harness the settings do not hold has no tier: its roles are unassigned in effect, and the refusal names the tier.
      assert(resolution(cq.core.AgentConfigText.parse(cq.core.AgentStarter.text(settings.take(1))).toOption.get, ParsedAgents.empty, Harness.Pi, AgentRole.Worker) ==
        RoleResolution.Unresolved(Some(RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles)), List(AgentProblem.TierUndefined(Harness.Pi, ModelTier.Standard, AgentRole.Worker))))
      assert(intercept[IllegalArgumentException](cq.core.AgentStarter.text(settings :+ settings.head)).getMessage.contains("names each harness once"))
      assert(intercept[IllegalArgumentException](cq.core.AgentStarter.text(Nil)).getMessage.contains("names each harness once"))
    }
    "save it as the configuration of a layer that holds none, leave a layer that holds it as it is, and replace no other configuration" in {
      val project = ProjectId(java.util.UUID.randomUUID())
      val text = cq.core.AgentStarter.text(List(HarnessSetting(Harness.Codex, "/bin/codex", "gpt-6.1", "openai", "1", Nil, Set.empty)))
      var stored = AgentsView(AgentsDocument(Revision(0), "", None, Nil), AgentsDocument(Revision(0), "", None, Nil), Nil)
      var replaced = List.empty[(AgentsScope, Revision)]
      val call: Command => Result = {
        case Command.Agents(AgentsInput(`project`, AgentsAction.Read())) => Result.Agents(stored)
        case Command.Agents(AgentsInput(`project`, AgentsAction.Replace(scope, expected, value))) =>
          replaced :+= scope -> expected
          val document = AgentsDocument(Revision(expected.value + 1), value, None, Nil)
          stored = scope match {
            case AgentsScope.Installation() => stored.copy(installation = document)
            case AgentsScope.Project() => stored.copy(project = document)
          }
          Result.Agents(stored)
        case other => fail(s"Unexpected command $other")
      }
      assert(AgentsInit.save(call, project, "installation", text) == AgentsDocument(Revision(1), text, None, Nil) && stored.project.text.isEmpty)
      // Saved again, it is found there and nothing is written.
      assert(AgentsInit.save(call, project, "installation", text).revision == Revision(1) && replaced == List(AgentsScope.Installation() -> Revision(0)))
      assert(AgentsInit.save(call, project, "project", text).revision == Revision(1) && replaced.size == 2)
      stored = stored.copy(project = AgentsDocument(Revision(4), "defaults:\n  roles:\n    worker: codex:other\n", None, Nil))
      val refused = intercept[IllegalArgumentException](AgentsInit.save(call, project, "project", text)).getMessage
      assert(refused.contains("The project already holds an agent configuration (revision 4); cq agents init starts one and replaces none") && replaced.size == 2, refused)
      assert(intercept[IllegalArgumentException](AgentsInit.save(call, project, "server", text)).getMessage.contains("--save takes installation or project"))
    }
  }
}
