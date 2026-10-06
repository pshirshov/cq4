package cq.server

import cq.api.*
import cq.core.{AgentStarter, LedgerPolicy}
import cq.host.HostFiles
import java.nio.file.Path

/**
 * `cq agents init`: writes an agent configuration to start from, derived from a settings file, and saves it on request as the
 * configuration of the installation or of the project. It is the operator's action: a session whose roles are not assigned starts no
 * child, and nothing reads a settings file for a model in its place.
 */
object AgentsInit {
  val Layers: Map[String, AgentsScope] = Map("installation" -> AgentsScope.Installation(), "project" -> AgentsScope.Project())

  /** The starting configuration of a settings file and what the operator should know about it beyond what it assigns. */
  def starting(settings: Path): (String, Option[String]) = {
    val harnesses = HostFiles.read(settings, SupervisorSettings_JsonCodec, LedgerPolicy.MaxConfigBytes).harnesses
    AgentStarter.text(harnesses) -> AgentStarter.note(harnesses)
  }

  /** Saves `text` as the configuration of the layer when the layer holds none; a layer that holds this text already is left as it is. */
  def save(call: Command => Result, project: ProjectId, layer: String, text: String): AgentsDocument = {
    val scope = Layers.getOrElse(layer, throw new IllegalArgumentException("--save takes installation or project"))
    def document(result: Result): AgentsDocument = result match {
      case Result.Agents(view) => scope match {
        case AgentsScope.Installation() => view.installation
        case AgentsScope.Project() => view.project
      }
      case other => throw new IllegalStateException(s"Unexpected agent configuration result: $other")
    }
    val stored = document(call(Command.Agents(AgentsInput(project, AgentsAction.Read()))))
    if (stored.text == text) stored
    else {
      require(stored.text.trim.isEmpty, s"The $layer already holds an agent configuration (revision ${stored.revision.value}); cq agents init starts one and replaces none: edit that configuration instead")
      document(call(Command.Agents(AgentsInput(project, AgentsAction.Replace(scope, stored.revision, text)))))
    }
  }
}
