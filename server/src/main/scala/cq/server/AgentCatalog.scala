package cq.server

import cq.api.*
import cq.host.{ChildContracts, ChildInstructions, HarnessInvocation, HarnessMcp, HarnessSchema, HarnessToolPolicy, HarnessTools, McpTarget}
import io.circe.Json
import java.nio.file.Path

/** What one harness effectively receives for a dispatched role mode: its system prompt, output schema and tool permissions. */
final case class AgentHarness(harness: Harness, prompt: String, outputSchema: Json, tools: HarnessToolPolicy)

/** One dispatchable role mode. `prompt` is the canonical text of `promptResource`; `inputSchema` and `outputSchema` are the canonical
  * generated schemas of the child's input document and of its report; `workspaceSchema` is the input schema the local workspace tool
  * advertises to this role. */
final case class AgentEntry(work: DispatchWork, role: Role, mode: Option[String], promptResource: String, prompt: String,
  inputType: String, inputSchema: Json, report: String, outputSchema: Json, workspaceSchema: Json, harnesses: List[AgentHarness],
  inputExample: ChildExecutionInput, outputExample: ChildReport) {
  def on(harness: Harness): AgentHarness = harnesses.find(_.harness == harness)
    .getOrElse(throw new IllegalStateException(s"Agent $work has no $harness entry"))
}

/** Index of the dispatched agents: one flat entry per `DispatchWork` role and mode. It holds no prompt, schema or permission data of
  * its own. Every value comes from the definition child launch uses: `ChildInstructions` prompt resources, `ChildContracts` report
  * selection, the generated cq-api schemas selected by `McpSchemas`, and the `HarnessTools` permission functions. */
final class AgentCatalog(schemas: McpSchemas, instructions: ChildInstructions) {
  val entries: List[AgentEntry] = ChildContracts.Works.map { work =>
    val role = ChildContracts.role(work)
    val prompt = instructions(work)
    val output = schemas.childReport(work)
    val harnesses = Harness.all.map { harness =>
      AgentHarness(harness, schemas.nativeSystem(harness, role, prompt, output, AgentCatalog.Targets), HarnessSchema.result(harness, output),
        HarnessTools.policy(work, harness))
    }
    AgentEntry(work, role, AgentCatalog.mode(work), instructions.resource(work), prompt, AgentCatalog.InputType, schemas.schema(AgentCatalog.InputType),
      ChildContracts.reportTag(work), output, schemas.workspace(role), harnesses, AgentExamples.input(work), AgentExamples.output(work))
  }

  def entry(work: DispatchWork): AgentEntry = entries.find(_.work == work)
    .getOrElse(throw new IllegalStateException(s"Agent $work is missing from the catalog"))

  /** The invocation a child of this role mode is launched with on `harness`. `endpoint` supplies the launch's own address and
    * scoped credential for each MCP target; everything the agent is told and permitted comes from its catalog entry. */
  def invocation(work: DispatchWork, harness: Harness, attempt: AttemptId, endpoint: McpTarget => HarnessMcp, assets: Path): HarnessInvocation = {
    val agent = entry(work)
    val endpoints = AgentCatalog.Targets.map { target =>
      val value = endpoint(target)
      require(value.target == target, "MCP endpoint does not serve its requested target")
      value
    }
    HarnessInvocation(agent.role, attempt, agent.on(harness).prompt, agent.outputSchema, endpoints, assets)
  }
}

object AgentCatalog {
  /** The MCP targets every dispatched child is connected to, in launch order. */
  val Targets: List[McpTarget] = McpTarget.values.toList
  /** Generated cq-api type of the document a child receives on its standard input. */
  val InputType: String = "ChildExecutionInput"

  def mode(work: DispatchWork): Option[String] = work match {
    case DispatchWork.Explorer(mode) => Some(mode.toString)
    case _: DispatchWork.Planner => None
    case DispatchWork.Worker(mode) => Some(mode.toString)
    case DispatchWork.Reviewer(mode) => Some(mode.toString)
  }
}
