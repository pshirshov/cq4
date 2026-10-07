package cq.server

import cq.api.*
import cq.core.{AgentStarter, DomainFailure}
import cq.host.ServerApi

/** What the suites that start child units share: the work of a request, and the agent configuration a unit's models come from. */
object UnitFixture {
  def work(request: DispatchRequest): AssignedWork =
    AssignedWork(request.request, request.work, request.members, request.guidance, request.artifacts, request.previous, request.fence, request.limits)

  /** Replaces the project's layer of the agent configuration with `text`, whatever it held. `api` acts for a person. */
  def configure(api: ServerApi, project: ProjectId, text: String): Unit = {
    def agents(action: AgentsAction): AgentsView = api.call(Command.Agents(AgentsInput(project, action))) match {
      case Result.Agents(view) => view
      case Result.Failed(fault) => throw DomainFailure(fault)
      case other => throw new IllegalStateException("Unexpected agent configuration result " + other)
    }
    val saved = agents(AgentsAction.Replace(AgentsScope.Project(), agents(AgentsAction.Read()).project.revision, text))
    require(saved.project.text == text && saved.project.problems.isEmpty, s"The fixture's agent configuration was not saved: ${saved.project.problems}")
  }

  /** What `cq agents init` writes for these settings; with one harness in them every role runs the settings model of the governing harness. */
  def starting(api: ServerApi, project: ProjectId, settings: SupervisorSettings): Unit = configure(api, project, AgentStarter.text(settings.harnesses))
}
