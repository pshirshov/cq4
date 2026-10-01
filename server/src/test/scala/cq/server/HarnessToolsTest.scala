package cq.server

import cq.api.*
import cq.host.*
import io.circe.{Json, parser}
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class HarnessToolsLocal extends AnyWordSpec {
  private val schemas = new McpSchemas()
  private val subjects: List[(String, Role)] = ("Governor" -> Role.Governor) ::
    (ExplorerMode.all.map(DispatchWork.Explorer.apply) ++ List(DispatchWork.Planner()) ++ WorkerMode.all.map(DispatchWork.Worker.apply) ++
      ReviewerMode.all.map(DispatchWork.Reviewer.apply)).map(work => work.toString -> ChildContracts.role(work))
  private val adapters: List[HarnessAdapter] = List(new ClaudeAdapter, new CodexAdapter, new PiAdapter)
  private val environment = Map("HOME" -> "/test/home", "PATH" -> "/test/bin")
  private val endpoints = List(
    HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("scoped-domain-token", 2000)),
    HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("scoped-local-token", 2000)))
  private def profile(harness: Harness): HarnessProfile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model",
    if (harness == Harness.Claude) "anthropic" else "selected-provider", HarnessUsage.version(harness), Nil, Set.empty)
  private def launch(adapter: HarnessAdapter, role: Role): HarnessLaunch = {
    val invocation = HarnessInvocation(role, AttemptId(UUID.randomUUID()), "Role instructions", Json.obj("type" -> Json.fromString("object")),
      endpoints, Path.of("/test/assets"))
    adapter.launch(profile(adapter.harness), schemas.nativeInvocation(adapter.harness, invocation), environment)
  }
  private def option(arguments: List[String], name: String): String = {
    require(arguments.count(_ == name) == 1, s"Expected one $name")
    arguments(arguments.indexOf(name) + 1)
  }
  private def configs(arguments: List[String]): List[(String, Json)] = {
    val pairs = arguments.sliding(2).collect { case List("-c", value) => value }.toList.map { value =>
      val (key, json) = value.splitAt(value.indexOf('='))
      key -> parser.parse(json.drop(1)).fold(throw _, identity)
    }
    require(pairs.map(_._1).distinct.size == pairs.size, "Duplicate Codex configuration key")
    pairs
  }
  private def split(value: String): List[String] = if (value.isEmpty) Nil else value.split(",", -1).toList
  private def checkBranch(role: Role): Boolean = schemas.workspace(role).hcursor.get[Vector[Json]]("oneOf").fold(throw _, identity)
    .exists(_.hcursor.get[List[String]]("required") == Right(List("Check")))

  /** Tool-related launch arguments observed from an adapter, independent of the pure policy. */
  private def observed(harness: Harness, launched: HarnessLaunch): Map[String, List[String]] = {
    val arguments = launched.arguments
    harness match {
      case Harness.Claude =>
        val servers = parser.parse(launched.assets.find(_.name == "claude-mcp.json").get.body).fold(throw _, identity)
          .hcursor.downField("mcpServers").keys.get.toList
        Map("tools" -> split(option(arguments, "--tools")), "allowedTools" -> split(option(arguments, "--allowedTools")),
          "disallowedTools" -> split(option(arguments, "--disallowedTools")), "servers" -> servers)
      case Harness.Codex =>
        val ordered = configs(arguments)
        val values = ordered.toMap
        val toolKeys = ordered.map(_._1).filter(key => key.startsWith("features.") || Set("agents.enabled", "web_search", "tools.update_plan.enabled")(key))
        Map("settings" -> toolKeys.map(key => key + "=" + values(key).noSpaces),
          "cq" -> values("mcp_servers.cq.enabled_tools").asArray.get.toList.map(_.asString.get),
          "cq_host" -> values("mcp_servers.cq_host.enabled_tools").asArray.get.toList.map(_.asString.get),
          "sandbox" -> List(option(arguments, "--sandbox")))
      case Harness.Pi =>
        val bridge = parser.parse(launched.assets.find(_.name == "pi-mcp.json").get.body).fold(throw _, identity)
          .hcursor.downField("endpoints").as[List[Json]].fold(throw _, identity)
          .map(endpoint => endpoint.hcursor.get[String]("name").toOption.get -> endpoint.hcursor.get[List[String]]("tools").toOption.get).toMap
        Map("tools" -> split(option(arguments, "--tools")), "cq" -> bridge("cq"), "cq_host" -> bridge("cq_host"))
    }
  }

  /** The same arguments as the adapter should derive them from the pure policy. */
  private def derived(policy: HarnessToolPolicy): Map[String, List[String]] = {
    val domain = policy.enabledMcp(McpTarget.Domain)
    val local = policy.enabledMcp(McpTarget.Local)
    policy.harness match {
      case Harness.Claude => Map("tools" -> policy.enabledBuiltin,
        "allowedTools" -> (policy.enabledBuiltin ++ domain.map("mcp__cq__" + _) ++ local.map("mcp__cq_host__" + _)),
        "disallowedTools" -> policy.deniedBuiltin, "servers" -> List("cq", "cq_host"))
      case Harness.Codex => Map("settings" -> policy.builtin.map(tool => tool.name + "=" +
          (if (tool.name == "web_search") Json.fromString(if (tool.enabled) "live" else "disabled") else Json.fromBoolean(tool.enabled)).noSpaces),
        "cq" -> domain, "cq_host" -> local, "sandbox" -> List(if (policy.edits) "workspace-write" else "read-only"))
      case Harness.Pi => Map("tools" -> (policy.enabledBuiltin ++ domain.map("cq_" + _) ++ local.map("cq_host_" + _)), "cq" -> domain, "cq_host" -> local)
    }
  }

  /** Tool arguments recorded from the adapters at base 419f216e, restated as an independent oracle. */
  private def base(harness: Harness, role: Role): Map[String, List[String]] = {
    val governor = role == Role.Governor
    val worker = role == Role.Worker
    val domain = if (governor) List("search", "read", "graph", "change", "apply", "claim", "usage") else List("search", "read", "usage")
    val local = if (governor) List("dispatch") else List("workspace")
    harness match {
      case Harness.Claude =>
        val builtin = if (worker) List("Read", "Glob", "Grep", "Edit", "Write", "Bash") else Nil
        Map("tools" -> builtin, "allowedTools" -> (builtin ++ domain.map("mcp__cq__" + _) ++ local.map("mcp__cq_host__" + _)),
          "disallowedTools" -> List("Agent", "Task", "TaskOutput", "TaskStop"), "servers" -> List("cq", "cq_host"))
      case Harness.Codex =>
        val off = List("multi_agent", "multi_agent_v2", "plugins", "plugin_hooks", "apps", "enable_mcp_apps", "memories",
          "skill_search", "skill_mcp_dependency_install").map("features." + _ + "=false")
        val edits = List("shell_tool", "unified_exec", "apply_patch_freeform").map("features." + _ + "=" + worker)
        Map("settings" -> (off ++ List("features.code_mode_host=true") ++ edits ++ List("agents.enabled=false", "web_search=\"disabled\"",
          "tools.update_plan.enabled=false")), "cq" -> domain, "cq_host" -> local, "sandbox" -> List(if (worker) "workspace-write" else "read-only"))
      case Harness.Pi =>
        Map("tools" -> ((if (worker) List("read", "write", "edit", "bash") else Nil) ++ domain.map("cq_" + _) ++ local.map("cq_host_" + _)),
          "cq" -> domain, "cq_host" -> local)
    }
  }

  "Harness tool permissions (Behavioral Active Blackbox; Group / process Communication)" should {
    "derive every adapter's tool arguments from the pure policy for each role mode and harness" in {
      assert(subjects.size == 1 + ExplorerMode.all.size + 1 + WorkerMode.all.size + ReviewerMode.all.size)
      for { (name, role) <- subjects; adapter <- adapters } withClue(s"$name on ${adapter.harness}: ") {
        val policy = HarnessTools.policy(role, adapter.harness)
        val observedArguments = observed(adapter.harness, launch(adapter, role))
        assert(observedArguments == derived(policy))
        assert(observedArguments == base(adapter.harness, role))
      }
    }

    "report complete enabled and disabled tool sets for each dispatched work and the governor" in {
      for { (name, role) <- subjects; harness <- Harness.all } withClue(s"$name on $harness: ") {
        val policy = HarnessTools.policy(role, harness)
        assert(policy == HarnessTools.policy(role, harness))
        List(McpTarget.Domain -> HarnessTools.DomainTools, McpTarget.Local -> HarnessTools.LocalTools).foreach { (target, all) =>
          assert((policy.enabledMcp(target) ++ policy.disabledMcp(target)).toSet == all.toSet)
          assert(policy.enabledMcp(target).intersect(policy.disabledMcp(target)).isEmpty)
        }
        assert(policy.enabledBuiltin.intersect(policy.disabledBuiltin).isEmpty && policy.deniedBuiltin.toSet.subsetOf(policy.disabledBuiltin.toSet))
        if (role == Role.Governor) {
          assert(policy.disabledMcp(McpTarget.Local) == List("workspace") && policy.disabledMcp(McpTarget.Domain).isEmpty)
        } else {
          assert(policy.disabledMcp(McpTarget.Domain) == List("graph", "change", "apply", "claim") && policy.disabledMcp(McpTarget.Local) == List("dispatch"))
        }
        assert(policy.edits == (role == Role.Worker) && (policy.enabledBuiltin.nonEmpty == policy.edits || harness == Harness.Codex))
      }
      val works = ExplorerMode.all.map(DispatchWork.Explorer.apply) ++ List(DispatchWork.Planner()) ++ WorkerMode.all.map(DispatchWork.Worker.apply) ++
        ReviewerMode.all.map(DispatchWork.Reviewer.apply)
      for { work <- works; harness <- Harness.all } assert(HarnessTools.policy(work, harness) == HarnessTools.policy(ChildContracts.role(work), harness))
      intercept[IllegalArgumentException](HarnessTools.policy(Role.Human, Harness.Claude))
    }

    "advertise the workspace Check branch only to reviewers, as the pure policy states" in {
      for { (name, role) <- subjects; harness <- Harness.all } withClue(s"$name on $harness: ") {
        val policy = HarnessTools.policy(role, harness)
        assert(policy.workspaceCheck == (role == Role.Reviewer))
        assert(checkBranch(role) == policy.workspaceCheck)
      }
    }
  }
}
