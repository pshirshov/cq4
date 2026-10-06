package cq.server

import cq.api.*
import cq.host.{ChildInstructions, DriverAssets, DriverCommand, PiAssets, HarnessTool, HarnessToolPolicy, McpTarget, ProcessModeEntry, ProcessModes, ToolAccess, WorkflowArgument, WorkflowAssets, WorkflowCatalog, WorkflowCommand}

/** The typed `ReadSelection.Catalog` view. It only projects the workflow and driver command catalogs, their installed assets,
  * the process modes and the dispatched agent catalog into the generated cq-api model; it holds no descriptions, aliases, argument docs, prompts, schemas,
  * examples or tool lists of its own. */
final class CatalogRead(agents: AgentCatalog, workflows: WorkflowAssets) {
  def this(schemas: McpSchemas) = this(new AgentCatalog(schemas, new ChildInstructions()), new WorkflowAssets())

  lazy val value: HelpCatalog = HelpCatalog(WorkflowCatalog.commands.map(command) ++ DriverAssets.catalog.map(driver), agents.entries.map(agent),
    ProcessModes.all.map(mode), ProcessModes.Effect)

  private def prompt(resource: String): CatalogPrompt = CatalogPrompt(resource, workflows.resource(resource))

  private def mode(value: ProcessModeEntry): CatalogMode =
    CatalogMode(value.mode, value.label, value.hint, value.description, prompt(value.instructions), value.unavailable)

  private def argument(value: WorkflowArgument): CatalogArgument = CatalogArgument(value.option.field, value.option.flag, value.option.value,
    value.option.summary, value.option.choices, value.required, value.note)

  private def command(value: WorkflowCommand): CatalogCommand = {
    val aliases = value.aliases.map { alias =>
      val written = workflows.commands(alias.harness).find(_.path == alias.path)
        .getOrElse(throw new IllegalStateException(s"Workflow ${value.command} writes no ${alias.harness} asset"))
      CatalogAlias(alias.harness, alias.alias, alias.path.toString, written.body)
    }
    CatalogCommand(value.command, value.variant, value.description, value.arguments.map(argument), prompt(value.template),
      value.instructions.map(prompt), aliases)
  }

  private def driver(value: DriverCommand): CatalogCommand = {
    val aliases = Harness.all.map { harness =>
      val written = if (harness == Harness.Pi) PiAssets.extension else {
        val index = DriverAssets.catalog.indexOf(value)
        require(index >= 0, s"Driver ${value.command} is absent from its catalog")
        DriverAssets.commands(harness)(index)
      }
      CatalogAlias(harness, DriverAssets.alias(harness, value.name), written.path.toString, written.body)
    }
    CatalogCommand(value.command, value.name.toString, value.description, value.arguments.map(argument), prompt(value.template), Nil, aliases)
  }

  private def tool(value: HarnessTool): CatalogTool = CatalogTool(value.name, value.access match {
    case ToolAccess.Enabled => CatalogToolAccess.Enabled
    case ToolAccess.Unselected => CatalogToolAccess.Unselected
    case ToolAccess.Denied => CatalogToolAccess.Denied
  })

  private def tools(value: HarnessToolPolicy): CatalogTools = CatalogTools(value.mcp.map { toolset =>
    val target = toolset.target match {
      case McpTarget.Domain => CatalogMcpTarget.Domain
      case McpTarget.Local => CatalogMcpTarget.Local
    }
    CatalogToolset(target, toolset.target.server, toolset.tools.map(tool))
  }, value.builtin.map(tool), value.edits, value.workspaceCheck)

  private def agent(value: AgentEntry): CatalogAgent = CatalogAgent(value.work, value.role, value.mode,
    CatalogPrompt(value.promptResource, value.prompt), value.inputType, value.inputSchema.noSpaces, value.report, value.outputSchema.noSpaces,
    value.workspaceSchema.noSpaces, value.harnesses.map(harness => CatalogAgentHarness(harness.harness, harness.prompt, harness.outputSchema.noSpaces,
      tools(harness.tools))), value.inputExample, value.outputExample)
}
