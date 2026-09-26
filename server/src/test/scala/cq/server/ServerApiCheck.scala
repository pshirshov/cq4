package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.HttpServerApi
import java.net.URI
import java.time.Duration
import java.util.UUID

object ServerApiCheck {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty)
    val endpoint = URI.create(sys.env("CQ_ORIGIN"))
    val session = SessionId(UUID.randomUUID())
    val project = ProjectId(UUID.randomUUID())
    val timeout = Duration.ofSeconds(10)
    val root = new HttpServerApi(endpoint, sys.env("CQ_TOKEN"), session, timeout)
    require(root.call(Command.Initialize(ProjectConfig(project, endpoint.toString, "host API fixture"))).isInstanceOf[Result.Initialized])
    val grant = GrantRequest(project, Actor("host client", session, Role.Collector), System.currentTimeMillis() + 60000)
    val token = root.grant(grant)
    val collector = new HttpServerApi(endpoint, token.value, session, timeout)
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project, Set.empty, Attribution.Unattributed, None, None)
    require(collector.usage(HostUsageInput(project, HostUsage.Assign(assignment))) == HostUsageResult.Assigned(assignment))
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", 1000)
    require(collector.usage(HostUsageInput(project, HostUsage.Start(attempt))) == HostUsageResult.Started(attempt))
    val upload = ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Input, "text/plain", "assembled host input λ😀")
    val metadata = collector.artifact(upload)
    require(collector.artifact(upload) == metadata)
    require(root.call(Command.Read(ReadInput(project, ReadSelection.ArtifactInfo(upload.id)))) == Result.ArtifactInfo(metadata))
    val worker = new HttpServerApi(endpoint, root.grant(grant.copy(actor = grant.actor.copy(role = Role.Worker))).value, session, timeout)
    val denied = try { worker.artifact(upload); false } catch { case DomainFailure(_: Fault.Denied) => true }
    require(denied, "Worker HTTP publication must be denied")
    val text = worker.call(Command.Read(ReadInput(project, ReadSelection.ArtifactText(upload.id, 0, 8192))))
    require(text match { case Result.ArtifactText(page) => page.text == upload.body && !page.hasMore; case _ => false })
    println("Scala host client: typed grants, host usage, immutable artifact publication and scoped model reads/denial passed")
  }
}
