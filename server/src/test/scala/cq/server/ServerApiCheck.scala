package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.{HarnessUsage, HttpServerApi, UsageCollectionRequest, UsageOrigin}
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID
import scala.util.Using

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
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Work)
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
    val evaluation = EvaluationScope("retained-observability-probes", "collector-replay", false)
    val measured = assignment.copy(id = AssignmentId(UUID.randomUUID()), evaluation = Some(evaluation))
    require(collector.usage(HostUsageInput(project, HostUsage.Assign(measured))) == HostUsageResult.Assigned(measured))
    val parser = new HarnessUsage()
    Harness.all.foreach { harness =>
      val native = Using.resource(getClass.getResourceAsStream(s"/harness-usage/${harness.toString.toLowerCase}.jsonl")) { stream =>
        require(stream != null)
        new String(stream.readAllBytes(), UTF_8)
      }
      val run = attempt.copy(id = AttemptId(UUID.randomUUID()), assignment = measured.id, harness = harness, collector = "CQ native collector 0.1.0")
      require(collector.usage(HostUsageInput(project, HostUsage.Start(run))) == HostUsageResult.Started(run))
      val evidence = collector.artifact(ArtifactUpload(project, ArtifactId(UUID.randomUUID()), run.id, ArtifactKind.Transcript, "application/x-ndjson", native))
      val context = UsageCollectionRequest(run.id, harness, HarnessUsage.version(harness), UsageOrigin.Fresh, 2000, evidence.id)
      val report = parser.collect(new ByteArrayInputStream(native.getBytes(UTF_8)), context)
      for (_ <- 1 to 2; batch <- report.meters) {
        require(collector.usage(HostUsageInput(project, HostUsage.Meter(batch.meter))) == HostUsageResult.Metered(batch.meter))
        batch.observations.foreach { value =>
          require(collector.usage(HostUsageInput(project, HostUsage.Ingest(value))).isInstanceOf[HostUsageResult.Ingested])
        }
      }
      val outcome = AttemptOutcome(RequestId(UUID.randomUUID()), run.id, AttemptState.Completed, 2000, report.gaps, None)
      require(collector.usage(HostUsageInput(project, HostUsage.Finish(outcome))) == HostUsageResult.Finished(outcome))
    }
    val filter = UsageFilter.EvaluationOnly(evaluation.run, Some(evaluation.scenario))
    root.call(Command.Usage(UsageInput(project, UsageSelection.Summary(filter)))) match {
      case Result.UsageSummary(value) =>
        require(value.unattributed.total.known == 18729 && value.unattributed.input.known == 18700 && value.unattributed.output.known == 29)
        require(value.unattributed.cacheRead.unknown == 2 && value.unattributed.reasoning.known == 13 && value.unattributed.unknownCosts == 1)
        require(value.costs.entries.size == 1 && value.costs.entries.head.amount.value == "0.0026" && value.costs.entries.head.group.basis == CostBasis.ProviderEstimate)
        require(value.incompleteMeters == 3 && value.attempts.withGaps == 1)
      case _ => throw new IllegalStateException("Expected collector evaluation usage summary")
    }
    root.call(Command.Usage(UsageInput(project, UsageSelection.Audit(filter, 0, 20)))) match {
      case Result.UsageAudit(page) => require(page.entries.size == 3 && !page.hasMore && page.entries.forall(_.upload.observation.evidence.nonEmpty))
      case _ => throw new IllegalStateException("Expected collector evaluation audit")
    }
    println("Scala host client: typed grants, scoped artifact access, and Claude/Codex/Pi collector replay through the shared audit passed (18729 reported tokens, 0.0026 estimated USD, explicit coverage gaps)")
  }
}
