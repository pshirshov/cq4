package cq.server

import cq.api.*
import cq.core.{DomainFailure, WorkspaceService}
import cq.host.*
import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.util.Try
import zio.{IO, Ref, Task, ZIO}

final class ChildRunner(config: SupervisorConfig, authority: SupervisorAuthority, registry: HarnessRegistry, jobs: JobSupervisor,
  workspaces: WorkspaceService[IO], schemas: McpSchemas, output: HarnessOutput, instructions: ChildInstructions,
  candidates: CandidateWorkspace, reader: WorkspaceReader, access: LocalAccess, clock: Clock) {
  private val MaxOutputBytes = 32 * 1024 * 1024
  private val DeliveryEntriesPerBatch = 32
  private val MaxGaps = 32
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val RenewalSeconds = 20L
  private final case class Trace(native: Option[JobRecord], extra: List[ArtifactUpload], uncertain: Boolean)
  private def directory(attempt: AttemptId): Path = config.directory.resolve("payload").resolve(attempt.value.toString)
  private def bytes(attempt: AttemptId, name: String): Array[Byte] = {
    val path = directory(attempt).resolve(name)
    if (Files.exists(path)) HostFiles.bytes(path, MaxOutputBytes) else Array.emptyByteArray
  }
  private def claim(entry: DispatchExecution, revisions: Boolean): Unit = {
    val request = entry.ticket.request
    authority.governor.call(Command.ClaimWork(ClaimInput(config.project.project, ClaimAction.Renew(request.fence, ClaimMillis)))) match {
      case Result.Claimed(value) => require(value.owner == config.owner.actor && value.members == request.members.map(_.id).toSet &&
        value.fence == request.fence && !value.released && value.expiresAt > clock.millis(), "Dispatch claim no longer owns its assignment")
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Claim refresh returned an unexpected result")
    }
    if (revisions) request.members.foreach { reference =>
      authority.governor.call(Command.Read(ReadInput(config.project.project, ReadSelection.ItemDetail(reference.id)))) match {
        case Result.Detail(value) => require(value.item.id == reference.id && value.item.revision == reference.revision, "Assignment changed before result admission")
        case Result.Failed(fault) => throw DomainFailure(fault)
        case _ => throw new IllegalStateException("Admission read returned an unexpected result")
      }
    }
  }
  private def maintain(entry: DispatchExecution): Task[Unit] = {
    val refresh = ZIO.attemptBlocking(claim(entry, false)).catchAll { failure =>
      ZIO.succeed(entry.requestStop("Work claim refresh failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))) *>
        ZIO.foreachDiscard(entry.activeJob)(id => jobs.cancel(config.owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit })
    }
    (ZIO.sleep(zio.Duration.fromSeconds(RenewalSeconds)) *> refresh).forever
  }
  private def launch(entry: DispatchExecution, id: AttemptId, base: GitCommit, command: JobCommand): Task[JobRecord] = for {
    _ <- ZIO.attempt { entry.check(); entry.active(id) }
    _ <- jobs.start(config.owner, WorkspaceSpec(config.project.project, config.run.attempt.session, id, config.run.repository, base), command)
    _ <- if (entry.stopReason.nonEmpty) jobs.cancel(config.owner, id).unit else ZIO.unit
    record <- jobs.await(config.owner, id)
  } yield record

  def workspace(entry: DispatchExecution, command: WorkspaceCommand): Task[WorkspaceReply] = for {
    record <- workspaces.get(config.owner, entry.ticket.attempt.id)
    result <- ZIO.attemptBlocking {
      require(record.admission == WorkspaceAdmission.Open && record.observed.nonEmpty, "Workspace is unavailable or quarantined")
      reader(Path.of(record.directory), command)
    }
  } yield result

  def run(entry: DispatchExecution): Task[Unit] = for {
    trace <- Ref.make(Trace(None, Nil, false))
    queue <- ZIO.attemptBlocking(new DeliveryQueue(entry.directory.resolve("delivery")))
    result <- ZIO.scoped {
      for {
        _ <- ZIO.attemptBlocking {
          val ticket = entry.ticket
          queue.enqueue(0, DeliveryBatch(List(HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Assign(ticket.assignment))),
            HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Start(ticket.attempt))))))
          queue.flush(authority.collector)
          entry.check()
        }
        input <- ZIO.attemptBlocking(new InputAssembler(authority.governor, config.owner, clock).assemble(entry.ticket.request))
        _ <- maintain(entry).forkScoped
        prepared <- ZIO.attemptBlocking {
          entry.check()
          val ticket = entry.ticket
          val profile = SupervisorConfig.profile(ticket.profile)
          SupervisorConfig.verifyProfile(config, profile)
          val base = input.previous.flatMap(_.candidate).getOrElse(config.run.base)
          candidates.verifyBase(base)
          val prompt = instructions(ticket.request.work)
          val body = HostFiles.encode(ChildExecutionInput_JsonCodec, ChildExecutionInput(input, base, config.settings.checks))
          val domain = authority.root.grant(GrantRequest(config.project.project,
            Actor("CQ child " + ticket.attempt.id.value, ticket.attempt.session, ticket.attempt.role), authority.expiresAt))
          val local = access.issue(ticket.attempt.id, ticket.attempt.role)
          val assets = entry.directory.resolve("assets")
          val invocation = schemas.nativeInvocation(profile.harness,
            HarnessInvocation(ticket.attempt.role, ticket.attempt.id, prompt, schemas.childReport(ticket.request.work),
              List(HarnessMcp(McpTarget.Domain, config.endpoint.resolve("/mcp"), domain), HarnessMcp(McpTarget.Local, access.endpoint, local)), assets))
          val launched = registry(profile.harness).launch(profile, invocation, config.environment)
          launched.install(assets)
          val artifacts = List(
            ArtifactUpload(config.project.project, NativeArtifacts.id(ticket.attempt.id, "input"), ticket.attempt.id, ArtifactKind.Input, "application/json", body),
            ArtifactUpload(config.project.project, NativeArtifacts.id(ticket.attempt.id, "prompt"), ticket.attempt.id, ArtifactKind.Prompt, "text/markdown", invocation.system))
          queue.enqueue(1, DeliveryBatch(artifacts.map(HostDelivery.Artifact.apply)))
          queue.flush(authority.collector)
          (base, JobCommand(launched.arguments, launched.environment, body, SupervisorConfig.limits(ticket.request.limits)))
        }
        (base, command) = prepared
        _ <- ZIO.succeed(entry.phase(DispatchPhase.Running))
        native <- launch(entry, entry.ticket.attempt.id, base, command)
        _ <- trace.update(_.copy(native = Some(native)))
        report <- ZIO.attemptBlocking {
          entry.check()
          val observed = JobOutcome.observed(native)
          require(observed.succeeded, observed.problem.getOrElse("Child process did not complete successfully"))
          val stdout = bytes(entry.ticket.attempt.id, "stdout")
          val usage = new HarnessUsage().collect(new ByteArrayInputStream(stdout), UsageCollectionRequest(entry.ticket.attempt.id,
            entry.ticket.attempt.harness, entry.ticket.profile.version, UsageOrigin.Fresh, clock.millis(), NativeArtifacts.id(entry.ticket.attempt.id, "stdout")))
          require(usage.terminalSeen && !usage.nativeFailure, "Child native output did not complete successfully")
          ChildContracts.report(entry.ticket.request.work, entry.ticket.request.members,
            output.result(entry.ticket.attempt.harness, stdout, entry.directory.resolve("assets")))
        }
        candidate <- report match {
          case ChildReport.Work(members) if members.exists(_.disposition == WorkDisposition.CandidateReady) =>
            workspaces.get(config.owner, entry.ticket.attempt.id).flatMap { workspace => ZIO.attemptBlocking {
              entry.check()
              val commit = candidates.capture(workspace)
              HostFiles.immutable(entry.directory.resolve("candidate.json"), HostFiles.encode(GitCommit_JsonCodec, commit), 1024)
              Some(commit)
            }}
          case _: ChildReport.Work => ZIO.succeed(None)
          case _: ChildReport.Review => ZIO.succeed(input.previous.flatMap(_.candidate))
        }
        validation <- if (entry.ticket.attempt.role == Role.Worker && candidate.nonEmpty) {
          ZIO.succeed(entry.phase(DispatchPhase.Validating)) *> ZIO.foreach(config.settings.checks.zipWithIndex) { case (check, index) =>
            validate(entry, candidate.get, check, index, trace)
          }
        } else ZIO.succeed(input.previous.toList.flatMap(_.validation))
        stored <- ZIO.attemptBlocking {
          entry.check()
          claim(entry, true)
          val value = ChildResult(entry.ticket.attempt.id, entry.ticket.request, base, candidate, report, validation)
          ChildContracts.result(config.project.project, value)
          value
        }
      } yield stored
    }.either
    _ <- if (result.isLeft || entry.stopReason.nonEmpty) {
      workspaces.quarantine(config.owner, entry.ticket.attempt.id, "Child result failed, was cancelled or lost admission; inspect retained evidence").unit
        .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }
    } else ZIO.unit
    observed <- trace.get
    _ <- publish(entry, queue, result, observed).ensuring(ZIO.succeed(access.revoke(entry.ticket.attempt.id)))
  } yield ()

  private def validate(entry: DispatchExecution, candidate: GitCommit, check: ValidationCheck, index: Int, trace: Ref[Trace]): Task[ValidationEvidence] = for {
    id <- ZIO.succeed(AttemptId(UUID.randomUUID()))
    source = config.limits
    limits = ExecutionLimits(source.startup, Duration.ofMillis(check.executionMillis), source.heartbeat, source.grace, source.kill, check.outputBytes)
    record <- launch(entry, id, candidate, JobCommand(check.command, HostEnvironment.runtime(config.environment), "", limits))
    captured <- ZIO.attemptBlocking {
      val parent = entry.ticket.attempt.id
      val project = config.project.project
      val (stdout, outParts) = NativeArtifacts.binary(project, parent, s"check-$index-stdout", "application/octet-stream", bytes(id, "stdout"))
      val (stderr, errParts) = NativeArtifacts.binary(project, parent, s"check-$index-stderr", "application/octet-stream", bytes(id, "stderr"))
      val observation = ValidationObservation(check, candidate, record, stdout, stderr)
      val artifact = ArtifactUpload(project, NativeArtifacts.id(parent, s"check-$index"), parent, ArtifactKind.Validation,
        "application/json", HostFiles.encode(ValidationObservation_JsonCodec, observation))
      val outcome = JobOutcome.observed(record)
      val state = if (outcome.succeeded) ValidationState.Passed else if (outcome.state == AttemptState.Unknown) ValidationState.Unknown else ValidationState.Failed
      (ValidationEvidence(check.name, state, artifact.id), outParts ++ errParts :+ artifact)
    }
    (evidence, artifacts) = captured
    _ <- trace.update(value => value.copy(extra = value.extra ++ artifacts, uncertain = value.uncertain || evidence.state == ValidationState.Unknown))
    _ <- ZIO.attempt(require(evidence.state != ValidationState.Unknown, "Host validation cleanup is unconfirmed"))
  } yield evidence

  private def publish(entry: DispatchExecution, queue: DeliveryQueue, result: Either[Throwable, ChildResult], trace: Trace): Task[Unit] = {
    val attempt = entry.ticket.attempt
    for {
      job <- trace.native match {
        case Some(value) => ZIO.succeed(Some(value))
        case None => jobs.await(config.owner, attempt.id).map(Some(_)).catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
      }
      _ <- ZIO.attemptBlocking {
        val cancelled = entry.freeze()
        val project = config.project.project
        val stdout = bytes(attempt.id, "stdout")
        val stderr = bytes(attempt.id, "stderr")
        val (nativeId, outParts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
        val (_, errParts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
        val collectedAt = math.max(attempt.startedAt, clock.millis())
        val usage = new HarnessUsage().collect(new ByteArrayInputStream(stdout), UsageCollectionRequest(attempt.id, attempt.harness,
          entry.ticket.profile.version, UsageOrigin.Fresh, collectedAt, nativeId))
        val problem = cancelled.orElse(result.left.toOption.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName))).map(DispatchProjection.concise)
        val valid = if (cancelled.nonEmpty) None else result.toOption
        val observed = job.map(JobOutcome.observed)
        val state = if (trace.uncertain || observed.exists(_.state == AttemptState.Unknown)) AttemptState.Unknown
          else if (cancelled.nonEmpty) AttemptState.Cancelled
          else observed.map(_.withResult(valid.nonEmpty)).getOrElse(AttemptState.Failed)
        val resultArtifact = valid.map(value => ArtifactUpload(project, NativeArtifacts.id(attempt.id, "result"), attempt.id,
          ArtifactKind.Result, "application/json", HostFiles.encode(ChildResult_JsonCodec, value)))
        val allArtifacts = outParts ++ errParts ++ trace.extra ++ resultArtifact.toList
        val observations = usage.meters.flatMap(batch => HostDelivery.Usage(HostUsageInput(project, HostUsage.Meter(batch.meter))) ::
          batch.observations.map(value => HostDelivery.Usage(HostUsageInput(project, HostUsage.Ingest(value)))))
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id, state, collectedAt,
          (problem.toList ++ observed.toList.flatMap(_.problem).map(DispatchProjection.concise) ++ usage.gaps).take(MaxGaps), None)
        val entries = allArtifacts.map(HostDelivery.Artifact.apply) ++ observations :+ HostDelivery.Usage(HostUsageInput(project, HostUsage.Finish(outcome)))
        entries.grouped(DeliveryEntriesPerBatch).zipWithIndex.foreach { case (batch, index) => queue.enqueue(index + 2, DeliveryBatch(batch)) }
        val delivered = Try(queue.flush(authority.collector)).isSuccess
        val base = entry.status.copy(process = job.map(_.phase), blocker = problem, usageDelivered = delivered, detailsOmitted = true)
        val finalStatus = (valid, resultArtifact) match {
          case (Some(value), Some(artifact)) => DispatchProjection.completed(base, value, artifact.id)
          case _ => base.copy(phase = state match {
            case AttemptState.Cancelled => DispatchPhase.Cancelled
            case AttemptState.Unknown => DispatchPhase.Unknown
            case _ => DispatchPhase.Failed
          }, next = if (state == AttemptState.Unknown) ChildNext.InspectEvidence else ChildNext.Retry)
        }
        val retained = if (delivered) finalStatus else finalStatus.copy(phase = DispatchPhase.PublicationPending,
          next = ChildNext.RetryDelivery, result = None, blocker = Some("Operational publication is pending; retain the session directory and run cq job upload"))
        HostFiles.immutable(entry.directory.resolve("receipt.json"), HostFiles.encode(DispatchStatus_JsonCodec, retained), 16384)
        entry.finish(retained)
      }
    } yield ()
  }
}
