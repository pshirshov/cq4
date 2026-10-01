package cq.host

import cq.api.*

/** How a launched agent can reach one tool. `Unselected` tools are left out of the harness's selected tool set;
  * `Denied` tools are explicitly switched off or disallowed by a launch argument. */
enum ToolAccess { case Enabled, Unselected, Denied }

final case class HarnessTool(name: String, access: ToolAccess) {
  def enabled: Boolean = access == ToolAccess.Enabled
}

final case class McpToolset(target: McpTarget, tools: List[HarnessTool]) {
  def enabled: List[String] = tools.filter(_.enabled).map(_.name)
  def disabled: List[String] = tools.filterNot(_.enabled).map(_.name)
}

/** Effective tool permissions of one agent role on one harness. Pure data: no credentials, paths or process state. */
final case class HarnessToolPolicy(harness: Harness, role: Role, mcp: List[McpToolset], builtin: List[HarnessTool], edits: Boolean, workspaceCheck: Boolean) {
  def enabledMcp(target: McpTarget): List[String] = toolset(target).enabled
  def disabledMcp(target: McpTarget): List[String] = toolset(target).disabled
  def enabledBuiltin: List[String] = builtin.filter(_.enabled).map(_.name)
  def disabledBuiltin: List[String] = builtin.filterNot(_.enabled).map(_.name)
  def deniedBuiltin: List[String] = builtin.filter(_.access == ToolAccess.Denied).map(_.name)
  private def toolset(target: McpTarget): McpToolset = mcp.find(_.target == target).get
}

object HarnessTools {
  val Roles: List[Role] = List(Role.Governor, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer)
  val DomainTools: List[String] = List("search", "read", "graph", "change", "apply", "claim", "usage")
  val LocalTools: List[String] = List("dispatch", "workspace")
  private val ClaudeEdits = List("Read", "Glob", "Grep", "Edit", "Write", "Bash")
  private val ClaudeDenied = List("Agent", "Task", "TaskOutput", "TaskStop")
  private val CodexDenied = List("features.multi_agent", "features.multi_agent_v2", "features.plugins", "features.plugin_hooks",
    "features.apps", "features.enable_mcp_apps", "features.memories", "features.skill_search", "features.skill_mcp_dependency_install")
  private val CodexHost = List("features.code_mode_host")
  private val CodexEdits = List("features.shell_tool", "features.unified_exec", "features.apply_patch_freeform")
  private val CodexDeniedServices = List("agents.enabled", "web_search", "tools.update_plan.enabled")
  private val PiEdits = List("read", "write", "edit", "bash")

  private def governing(role: Role): Role = {
    require(Roles.contains(role), "Harness role must be a governing or dispatched agent")
    role
  }

  def edits(role: Role): Boolean = role == Role.Worker

  /** Whether the local workspace tool advertises its reviewer-only `Check` branch. */
  def workspaceCheck(role: Role): Boolean = role == Role.Reviewer

  def mcp(role: Role, target: McpTarget): List[String] = {
    val governor = governing(role) == Role.Governor
    target match {
      case McpTarget.Domain => if (governor) DomainTools else List("search", "read", "usage")
      case McpTarget.Local => if (governor) List("dispatch") else List("workspace")
    }
  }

  def builtin(role: Role, harness: Harness): List[HarnessTool] = {
    def switched(names: List[String], on: Boolean, off: ToolAccess): List[HarnessTool] =
      names.map(HarnessTool(_, if (on) ToolAccess.Enabled else off))
    val editor = edits(governing(role))
    harness match {
      case Harness.Claude => switched(ClaudeEdits, editor, ToolAccess.Unselected) ++ switched(ClaudeDenied, false, ToolAccess.Denied)
      case Harness.Codex => switched(CodexDenied, false, ToolAccess.Denied) ++ switched(CodexHost, true, ToolAccess.Denied) ++
          switched(CodexEdits, editor, ToolAccess.Denied) ++ switched(CodexDeniedServices, false, ToolAccess.Denied)
      case Harness.Pi => switched(PiEdits, editor, ToolAccess.Unselected)
    }
  }

  def policy(role: Role, harness: Harness): HarnessToolPolicy = {
    val toolsets = List(McpTarget.Domain -> DomainTools, McpTarget.Local -> LocalTools).map { (target, all) =>
      val enabled = mcp(role, target)
      McpToolset(target, all.map(name => HarnessTool(name, if (enabled.contains(name)) ToolAccess.Enabled else ToolAccess.Unselected)))
    }
    HarnessToolPolicy(harness, role, toolsets, builtin(role, harness), edits(role), workspaceCheck(role))
  }

  def policy(work: DispatchWork, harness: Harness): HarnessToolPolicy = policy(ChildContracts.role(work), harness)
}
