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
  private def invocation(harness: Harness, role: Role): HarnessInvocation = schemas.nativeInvocation(harness,
    HarnessInvocation(role, AttemptId(UUID.randomUUID()), "Role instructions", Json.obj("type" -> Json.fromString("object")),
      endpoints, Path.of("/test/assets")))
  private def launch(adapter: HarnessAdapter, role: Role): HarnessLaunch =
    adapter.launch(profile(adapter.harness), invocation(adapter.harness, role), environment)
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
        "cq" -> domain, "cq_host" -> local, "sandbox" -> List(CodexAdapter.Sandbox))
      case Harness.Pi => Map("tools" -> (policy.enabledBuiltin ++ domain.map("cq_" + _) ++ local.map("cq_host_" + _)), "cq" -> domain, "cq_host" -> local)
    }
  }

  /** Tool arguments recorded from the adapters at base 419f216e, restated as an independent oracle. The Codex sandbox mode is the one
    * exception: since Question 26 every role launches with `danger-full-access`, where 419f216e passed `workspace-write` or `read-only`. */
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
          "tools.update_plan.enabled=false")), "cq" -> domain, "cq_host" -> local, "sandbox" -> List("danger-full-access"))
      case Harness.Pi =>
        Map("tools" -> ((if (worker) List("read", "write", "edit", "bash") else Nil) ++ domain.map("cq_" + _) ++ local.map("cq_host_" + _)),
          "cq" -> domain, "cq_host" -> local)
    }
  }

  /** Complete ordered launch arguments as the adapters emitted them at base 419f216e, restated independently of HarnessTools,
    * except the Codex sandbox mode (Question 26: `danger-full-access` for every role). */
  private def baseArguments(profile: HarnessProfile, invocation: HarnessInvocation): List[String] = {
    val worker = invocation.role == Role.Worker
    val governor = invocation.role == Role.Governor
    def tools(target: McpTarget): List[String] = target match {
      case McpTarget.Domain => if (governor) List("search", "read", "graph", "change", "apply", "claim", "usage") else List("search", "read", "usage")
      case McpTarget.Local => if (governor) List("dispatch") else List("workspace")
    }
    def config(key: String, value: Json): List[String] = List("-c", s"$key=${value.noSpaces}")
    profile.harness match {
      case Harness.Claude =>
        val builtin = if (worker) List("Read", "Glob", "Grep", "Edit", "Write", "Bash") else Nil
        val mcp = invocation.endpoints.flatMap(endpoint => tools(endpoint.target).map(tool => s"mcp__${endpoint.name}__$tool"))
        List(profile.executable.toString, "--print", "--output-format", "stream-json", "--verbose", "--restricted",
          "--no-session-persistence", "--session-id", invocation.attempt.value.toString, "--model", profile.model,
          "--disable-slash-commands", "--strict-mcp-config", "--mcp-config", invocation.assets.resolve("claude-mcp.json").toString,
          "--tools", builtin.mkString(","), "--allowedTools", (builtin ++ mcp).mkString(","),
          "--disallowedTools", "Agent,Task,TaskOutput,TaskStop", "--system-prompt", invocation.system,
          "--json-schema", invocation.resultSchema.noSpaces)
      case Harness.Codex =>
        val disabled = List("multi_agent", "multi_agent_v2", "plugins", "plugin_hooks", "apps", "enable_mcp_apps", "memories",
          "skill_search", "skill_mcp_dependency_install")
        val restrictions = disabled.flatMap(name => config("features." + name, Json.False)) ++
          config("features.code_mode_host", Json.True) ++
          List("shell_tool", "unified_exec", "apply_patch_freeform").flatMap(name => config("features." + name, Json.fromBoolean(worker))) ++
          config("agents.enabled", Json.False) ++ config("approval_policy", Json.fromString("never")) ++
          config("model_provider", Json.fromString(profile.provider)) ++
          config("web_search", Json.fromString("disabled")) ++ config("tools.update_plan.enabled", Json.False) ++
          config("developer_instructions", Json.fromString(invocation.system))
        val mcp = invocation.endpoints.flatMap { endpoint =>
          val prefix = "mcp_servers." + endpoint.name + "."
          config(prefix + "url", Json.fromString(endpoint.endpoint.toString)) ++
            config(prefix + "bearer_token_env_var", Json.fromString(endpoint.environmentKey)) ++
            config(prefix + "enabled_tools", Json.arr(tools(endpoint.target).map(Json.fromString)*)) ++
            config(prefix + "required", Json.True) ++ config(prefix + "startup_timeout_sec", Json.fromInt(10)) ++
            config(prefix + "tool_timeout_sec", Json.fromInt(30)) ++ config(prefix + "default_tools_approval_mode", Json.fromString("approve"))
        }
        List(profile.executable.toString, "exec", "--json", "--ephemeral", "--ignore-user-config", "--ignore-rules", "--strict-config",
          "--model", profile.model, "--sandbox", "danger-full-access",
          "--output-schema", invocation.assets.resolve("result-schema.json").toString,
          "--output-last-message", invocation.assets.resolve("last-message.json").toString) ++ restrictions ++ mcp ++ List("-")
      case Harness.Pi =>
        val builtin = if (worker) List("read", "write", "edit", "bash") else Nil
        val mcp = invocation.endpoints.flatMap(endpoint => tools(endpoint.target).map(tool => s"${endpoint.name}_$tool"))
        List(profile.executable.toString, "--offline", "--mode", "json", "--print", "--no-session",
          "--session-id", invocation.attempt.value.toString, "--provider", profile.provider, "--model", profile.model,
          "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes", "--no-approve",
          "--tools", (builtin ++ mcp).mkString(","), "--extension", invocation.assets.resolve("pi-bridge.mjs").toString,
          "--system-prompt", invocation.system) ++ profile.providerExtensions.flatMap(path => List("--extension", path.toString))
    }
  }

  "Harness tool permissions (Behavioral Active Blackbox; Group / process Communication)" should {
    "launch with the complete ordered argument lists of base 419f216e, apart from the Codex sandbox mode, for each role mode and harness" in {
      for { (name, role) <- subjects; adapter <- adapters } withClue(s"$name on ${adapter.harness}: ") {
        val selected = profile(adapter.harness)
        val invoked = invocation(adapter.harness, role)
        assert(adapter.launch(selected, invoked, environment).arguments == baseArguments(selected, invoked))
      }
    }

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
