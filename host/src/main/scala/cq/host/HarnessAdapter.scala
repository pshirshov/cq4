package cq.host

import cq.api.*
import cq.core.AgentResolution
import io.circe.Json
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Using

/** `effort` absent leaves the harness's own default level. */
final case class HarnessProfile(harness: Harness, executable: Path, model: String, provider: String, effort: Option[Effort], version: String,
  providerExtensions: List[Path], providerEnvironment: Set[String]) {
  require(executable.isAbsolute && executable.normalize() == executable, "Harness executable must be absolute and normalized")
  require(List(model, provider).forall(s => s.nonEmpty && s.length <= 100 && !s.exists(_.isControl)), "Explicit harness model/provider required")
  require(HarnessUsage.verified(harness, version), HarnessProfile.Unverified)
  require(providerExtensions.forall(p => p.isAbsolute && p.normalize() == p), "Provider extension paths must be absolute and normalized")
  require(providerExtensions.isEmpty || harness == Harness.Pi, "Only Pi accepts explicit provider extensions")
  require(providerExtensions.distinct == providerExtensions && providerExtensions.size <= 8, "Invalid provider extension inventory")
  require(providerEnvironment.forall(name => name.matches("[A-Z][A-Z0-9_]{0,99}") && !name.startsWith("CQ_") &&
    !Set("CLAUDECODE", "CLAUDE_CODE_SIMPLE", "CLAUDE_CODE_SAFE_MODE")(name)), "Provider environment cannot carry CQ or harness-control authority")
}
object HarnessProfile {
  val Unverified = "Unverified harness version"
  /** The settings entry of a harness with the model, provider and effort of one route; a route that names no provider takes the entry's. */
  def apply(setting: HarnessSetting, route: ModelRoute): HarnessProfile = {
    require(setting.harness == route.harness, "Model route and settings entry name different harnesses")
    HarnessProfile(setting.harness, Path.of(setting.executable), route.model, route.provider.getOrElse(setting.provider), route.effort, setting.version,
      setting.providerExtensions.map(Path.of(_)), setting.providerEnvironment)
  }
  /** The route a settings entry states by itself: its own model and provider, and the harness's default effort. */
  def route(setting: HarnessSetting): ModelRoute = ModelRoute(setting.harness, Some(setting.provider), setting.model, None)
}

enum McpTarget {
  case Domain, Local
  /** MCP server name a harness reaches this endpoint under. */
  def server: String = this match { case McpTarget.Domain => "cq"; case McpTarget.Local => "cq_host" }
}
final case class HarnessMcp(target: McpTarget, endpoint: URI, token: AccessToken) {
  require(Set("http", "https")(endpoint.getScheme) && endpoint.getHost != null && endpoint.getUserInfo == null &&
    endpoint.getRawQuery == null && endpoint.getRawFragment == null, "MCP endpoint must be an HTTP(S) URL without credentials/query/fragment")
  require(token.value.nonEmpty && token.value.length <= 8192, "Scoped MCP credential required")
  def name: String = target.server
  def environmentKey: String = "CQ_MCP_" + name.toUpperCase + "_TOKEN"
}

object HarnessInvocation {
  /** Whether the calls of `target` made by `role` may wait for dispatched work: only a Governor's dispatch does. */
  def waits(role: Role, target: McpTarget): Boolean = role == Role.Governor && target == McpTarget.Local

  // The Codex Governor's instructions carry the generated argument guide for its tools (about 33 KB with the current model).
  // Claude and Pi receive them as one argument unchanged. Codex receives them JSON-encoded in one `developer_instructions=`
  // argument, where a quote, backslash or newline occupies two bytes (at most 96 KiB) and any other control character six
  // (up to 288 KiB), so this bound alone does not keep that argument inside HarnessLaunch.MaxArgumentBytes.
  val MaxSystemBytes = 49152
}

final case class HarnessInvocation(role: Role, attempt: AttemptId, system: String, resultSchema: Json, endpoints: List[HarnessMcp], assets: Path) {
  require(Set(Role.Governor, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer)(role), "Harness role must be a governing or dispatched agent")
  require(assets.isAbsolute && assets.normalize() == assets, "Harness asset directory must be absolute and normalized")
  require(UTF_8.newEncoder().canEncode(system) && system.getBytes(UTF_8).length <= HarnessInvocation.MaxSystemBytes, "Harness system instructions exceed bounds")
  require(resultSchema.isObject && resultSchema.noSpaces.getBytes(UTF_8).length <= 32768, "Harness result schema exceeds bounds")
  require(endpoints.map(_.target).distinct.size == endpoints.size, "Duplicate MCP target")
  def tools(target: McpTarget): List[String] = HarnessTools.mcp(role, target)
}

final case class HarnessAsset(name: String, body: String) {
  require(name.matches("[a-z][a-z0-9.-]{0,80}"), "Harness asset must have a flat filename")
  require(UTF_8.newEncoder().canEncode(body) && body.getBytes(UTF_8).length <= 262144, "Harness asset exceeds bounds")
}
object HarnessLaunch {
  // Linux refuses an exec whose single argument, with its terminating NUL, exceeds 32 pages of 4 KiB (MAX_ARG_STRLEN).
  val MaxArgumentBytes = 131071
}
final case class HarnessLaunch(arguments: List[String], environment: Map[String, String], assets: List[HarnessAsset]) {
  require(arguments.forall(_.getBytes(UTF_8).length <= HarnessLaunch.MaxArgumentBytes), "Harness launch argument exceeds the operating system's per-argument limit")
  def install(directory: Path): Unit = {
    require(directory.isAbsolute && directory.normalize() == directory && assets.map(_.name).distinct.size == assets.size, "Invalid private launch directory/assets")
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    require(!Files.isSymbolicLink(directory) && Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString("rwx------"), "Launch directory must be private and not a symbolic link")
    assets.foreach { asset =>
      val file = directory.resolve(asset.name)
      val bytes = asset.body.getBytes(UTF_8)
      if (Files.exists(file)) {
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file) &&
          Files.getPosixFilePermissions(file) == PosixFilePermissions.fromString("rw-------"), "Launch asset must be a private regular file")
        val existing = Using.resource(Files.newInputStream(file))(_.readNBytes(bytes.length + 1))
        require(java.util.Arrays.equals(existing, bytes), "Launch asset identity changed; do not overwrite an uncertain launch")
      } else {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(file, bytes, StandardOpenOption.WRITE)
        Using.resource(java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE))(_.force(true))
      }
    }
    Using.resource(java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ))(_.force(true))
  }
}

trait HarnessAdapter {
  def harness: Harness
  def launch(profile: HarnessProfile, invocation: HarnessInvocation, environment: Map[String, String]): HarnessLaunch
}
object HarnessAdapter {
  val EffortUnsupported = "Harness does not take the requested effort"
  /** The effort as the harness's command line spells it; a level the harness does not name is refused, not passed on. */
  def effort(profile: HarnessProfile): Option[String] = profile.effort.map { value =>
    require(AgentResolution.efforts(profile.harness)(value), EffortUnsupported)
    AgentResolution.effortName(value)
  }
}

object HarnessEnvironment {
  private val Runtime = Set("HOME", "PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
    "XDG_RUNTIME_DIR", "SSL_CERT_FILE", "SSL_CERT_DIR", "NIX_SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS", "CURL_CA_BUNDLE",
    "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "all_proxy", "no_proxy",
    "CODEX_HOME", "CLAUDE_CONFIG_DIR", "PI_CODING_AGENT_DIR", "__NIXOS_SET_ENVIRONMENT_DONE")
  def isolated(profile: HarnessProfile, environment: Map[String, String]): Map[String, String] = {
    val allowed = Runtime ++ profile.providerEnvironment
    val selected = environment.filter((name, _) => allowed(name))
    require(selected.contains("HOME") && selected.contains("PATH"), "Harness execution requires explicit HOME and PATH")
    require(profile.providerEnvironment.subsetOf(environment.keySet), "Configured provider environment is unavailable")
    selected
  }
}

object ClaudeAdapter {
  /** The one provider Claude Code is launched for; a Claude route names none. */
  val Provider = "anthropic"
}

final class ClaudeAdapter extends HarnessAdapter {
  override val harness: Harness = Harness.Claude
  override def launch(profile: HarnessProfile, invocation: HarnessInvocation, environment: Map[String, String]): HarnessLaunch = {
    require(profile.harness == harness)
    require(profile.provider == ClaudeAdapter.Provider, "Claude adapter supports only the verified Anthropic provider route")
    val policy = HarnessTools.policy(invocation.role, harness)
    val builtin = policy.enabledBuiltin
    val mcp = invocation.endpoints.flatMap(endpoint => policy.enabledMcp(endpoint.target).map(tool => s"mcp__${endpoint.name}__$tool"))
    // Claude Code gives an HTTP MCP request 60 s for its first byte and reads a longer bound from the server's `timeout` (milliseconds),
    // which is also its tool-call limit; without one its defaults apply, as they did before a dispatch call could wait longer.
    val servers = Json.obj(invocation.endpoints.map { endpoint => endpoint.name -> Json.fromFields(List(
      "type" -> Json.fromString("http"), "url" -> Json.fromString(endpoint.endpoint.toString),
      "headers" -> Json.obj("Authorization" -> Json.fromString("Bearer " + endpoint.token.value))) ++
      Option.when(HarnessInvocation.waits(invocation.role, endpoint.target))("timeout" -> Json.fromLong(DispatchWaits.ManagedGovernorSeconds * 1000))
    ) }*)
    val arguments = List(profile.executable.toString, "--print", "--output-format", "stream-json", "--verbose", "--restricted",
      "--no-session-persistence", "--session-id", invocation.attempt.value.toString, "--model", profile.model) ++
      HarnessAdapter.effort(profile).toList.flatMap(value => List("--effort", value)) ++ List(
      "--disable-slash-commands", "--strict-mcp-config", "--mcp-config", invocation.assets.resolve("claude-mcp.json").toString,
      "--tools", builtin.mkString(","), "--allowedTools", (builtin ++ mcp).mkString(","),
      "--disallowedTools", policy.deniedBuiltin.mkString(","), "--system-prompt", invocation.system,
      "--json-schema", HarnessSchema.result(harness, invocation.resultSchema).noSpaces)
    HarnessLaunch(arguments, HarnessEnvironment.isolated(profile, environment), List(HarnessAsset("claude-mcp.json", Json.obj("mcpServers" -> servers).noSpaces)))
  }
}

object CodexAdapter {
  /** Sandbox mode of every managed Codex launch, whatever the role. */
  val Sandbox = "danger-full-access"
}

final class CodexAdapter extends HarnessAdapter {
  override val harness: Harness = Harness.Codex
  private def config(key: String, value: Json): List[String] = List("-c", s"$key=${value.noSpaces}")
  private def setting(tool: HarnessTool): List[String] = tool.name match {
    case "web_search" => config(tool.name, Json.fromString(if (tool.enabled) "live" else "disabled"))
    case name => config(name, Json.fromBoolean(tool.enabled))
  }
  override def launch(profile: HarnessProfile, invocation: HarnessInvocation, environment: Map[String, String]): HarnessLaunch = {
    require(profile.harness == harness)
    val policy = HarnessTools.policy(invocation.role, harness)
    // Approval and provider settings stay between agents.enabled and web_search, where the launch has always placed them.
    val (leading, trailing) = policy.builtin.span(_.name != "web_search")
    val restrictions = leading.flatMap(setting) ++ config("approval_policy", Json.fromString("never")) ++
      config("model_provider", Json.fromString(profile.provider)) ++
      HarnessAdapter.effort(profile).toList.flatMap(value => config("model_reasoning_effort", Json.fromString(value))) ++ trailing.flatMap(setting) ++
      config("developer_instructions", Json.fromString(invocation.system))
    val mcp = invocation.endpoints.flatMap { endpoint =>
      val prefix = "mcp_servers." + endpoint.name + "."
      config(prefix + "url", Json.fromString(endpoint.endpoint.toString)) ++
        config(prefix + "bearer_token_env_var", Json.fromString(endpoint.environmentKey)) ++
        config(prefix + "enabled_tools", Json.arr(policy.enabledMcp(endpoint.target).map(Json.fromString)*)) ++
        config(prefix + "required", Json.True) ++ config(prefix + "startup_timeout_sec", Json.fromInt(10)) ++
        config(prefix + "tool_timeout_sec", Json.fromLong(
          if (HarnessInvocation.waits(invocation.role, endpoint.target)) DispatchWaits.ManagedGovernorSeconds else DispatchWaits.RequestSeconds)) ++ config(prefix + "default_tools_approval_mode", Json.fromString("approve"))
    }
    // Every role runs without the Codex sandbox (operator decision, Question 26): Codex's Linux sandbox refuses the Nix daemon
    // socket in both of its restricted modes, so no child could run `nix develop` (Defect 104). Three things stay separate:
    //  - Filesystem boundary: the operator's outer sandbox and nothing else. A Codex child of any role, including Governor,
    //    Explorer, Planner and Reviewer, can write wherever that sandbox lets the host process write.
    //  - Workflow constraints: the tool policy above and the role instructions say what a role is meant to do. They do not
    //    prevent a write: a live reviewer reached a patch handler with freeform patch presentation disabled.
    //  - Candidate capture: decides what the host integrates. It prevents no write and is not filesystem protection.
    val arguments = List(profile.executable.toString, "exec", "--json", "--ephemeral", "--ignore-user-config", "--ignore-rules", "--strict-config",
      "--model", profile.model, "--sandbox", CodexAdapter.Sandbox,
      "--output-schema", invocation.assets.resolve("result-schema.json").toString,
      "--output-last-message", invocation.assets.resolve("last-message.json").toString) ++ restrictions ++ mcp ++ List("-")
    val scoped = invocation.endpoints.map(endpoint => endpoint.environmentKey -> endpoint.token.value).toMap
    HarnessLaunch(arguments, HarnessEnvironment.isolated(profile, environment) ++ scoped,
      List(HarnessAsset("result-schema.json", HarnessSchema.result(harness, invocation.resultSchema).noSpaces),
        HarnessAsset("canonical-result-schema.json", invocation.resultSchema.noSpaces)))
  }
}

object PiAdapter {
  val AmbiguousModel = "Pi reads the ending of this model name as a thinking level; the name selects no one model"
}

final class PiAdapter extends HarnessAdapter {
  override val harness: Harness = Harness.Pi
  override def launch(profile: HarnessProfile, invocation: HarnessInvocation, environment: Map[String, String]): HarnessLaunch = {
    require(profile.harness == harness)
    require(!AgentResolution.piThinkingSuffix(profile.model), PiAdapter.AmbiguousModel)
    val policy = HarnessTools.policy(invocation.role, harness)
    val builtin = policy.enabledBuiltin
    val mcp = invocation.endpoints.flatMap(endpoint => policy.enabledMcp(endpoint.target).map(tool => s"${endpoint.name}_$tool"))
    val configuration = Json.obj("endpoints" -> Json.arr(invocation.endpoints.map { endpoint => Json.obj(
      "name" -> Json.fromString(endpoint.name), "url" -> Json.fromString(endpoint.endpoint.toString),
      "token" -> Json.fromString(endpoint.token.value), "tools" -> Json.arr(policy.enabledMcp(endpoint.target).map(Json.fromString)*),
    ) }*))
    val extension = Using.resource(getClass.getResourceAsStream("/cq/pi-bridge.mjs")) { stream =>
      require(stream != null, "CQ Pi bridge resource is missing")
      new String(stream.readAllBytes(), UTF_8)
    }
    val arguments = List(profile.executable.toString, "--offline", "--mode", "json", "--print", "--no-session",
      "--session-id", invocation.attempt.value.toString, "--provider", profile.provider, "--model", profile.model) ++
      HarnessAdapter.effort(profile).toList.flatMap(value => List("--thinking", value)) ++ List(
      "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes", "--no-approve",
      "--tools", (builtin ++ mcp).mkString(","), "--extension", invocation.assets.resolve("pi-bridge.mjs").toString,
      "--system-prompt", invocation.system) ++ profile.providerExtensions.flatMap(path => List("--extension", path.toString))
    HarnessLaunch(arguments, HarnessEnvironment.isolated(profile, environment),
      List(HarnessAsset("pi-mcp.json", configuration.noSpaces), HarnessAsset("pi-bridge.mjs", extension)))
  }
}
