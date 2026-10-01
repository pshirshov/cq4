package cq.server

import cq.api.*
import cq.core.{DomainFailure, WorkspaceService}
import cq.host.*
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import scala.util.{Try, Using}
import zio.{IO, Ref, Task, ZIO}

final class ChildRunner(config: SupervisorConfig, authority: SupervisorAuthority, registry: HarnessRegistry, jobs: JobSupervisor,
  workspaces: WorkspaceService[IO], agents: AgentCatalog, output: HarnessOutput,
  candidates: CandidateWorkspace, reader: WorkspaceReader, access: LocalAccess, requirements: OperatorRequirements, clock: Clock) {
  private val MaxGaps = 32
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val RenewalSeconds = 20L
  private val partials = new PartialWorkCapture(config)
  private val validation = new HostValidation(config)
  private final case class Trace(native: Option[JobRecord], extra: List[ArtifactUpload], uncertain: Boolean)
  private def directory(attempt: AttemptId): Path = config.directory.resolve("payload").resolve(attempt.value.toString)
  private def transcript(attempt: AttemptId, name: String, bound: Int): Array[Byte] = NativeTranscript.retained(directory(attempt).resolve(name), bound)
  private def collect(attempt: Attempt, version: String, collectedAt: Long): CollectedUsage =
    Using.resource(NativeTranscript.stream(directory(attempt.id).resolve("stdout")))(new HarnessUsage().collect(_, UsageCollectionRequest(attempt.id,
      attempt.harness, version, UsageOrigin.Fresh, collectedAt, NativeArtifacts.id(attempt.id, "stdout"))))
  /** A failed removal leaves the record open; the next host startup retries it and reports the outcome in its cleanup receipt. */
  private def release(attempt: AttemptId): Task[Option[WorkspaceRecord]] = jobs.release(config.owner, attempt)
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
        ZIO.foreachDiscard(entry.ownedJobs)(id => jobs.cancel(config.owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit })
    }
    (ZIO.sleep(zio.Duration.fromSeconds(RenewalSeconds)) *> refresh).forever
  }
  private def launch(entry: DispatchExecution, id: AttemptId, base: GitCommit, command: JobCommand): Task[JobRecord] = for {
    _ <- ZIO.attempt { entry.check(); entry.active(id) }
    _ <- jobs.start(config.owner, WorkspaceSpec(config.project.project, config.run.attempt.session, id, config.run.repository, base), command)
    _ <- if (entry.stopReason.nonEmpty) jobs.cancel(config.owner, id).unit else ZIO.unit
    record <- jobs.await(config.owner, id)
  } yield record

  def workspace(entry: DispatchExecution, command: WorkspaceCommand): Task[WorkspaceReply] = command match {
    case WorkspaceCommand.Check(name, waitMillis) =>
      if (entry.ticket.request.work != DispatchWork.Reviewer(ReviewerMode.Candidate))
        ZIO.fail(DomainFailure(Fault.Denied("Only a candidate reviewer may execute declared checks")))
      else ZIO.fromOption(entry.reviewerChecks).orElseFail(new IllegalStateException("Reviewer check owner is unavailable"))
        .flatMap(_.request(name, waitMillis)).map(WorkspaceReply.Check.apply)
    case _ => for {
    record <- workspaces.get(config.owner, entry.ticket.attempt.id)
    result <- ZIO.attemptBlocking {
      require(record.admission == WorkspaceAdmission.Open && record.observed.nonEmpty, "Workspace is unavailable or quarantined")
      command match {
        case WorkspaceCommand.MergeReport(offset, limit) =>
          require(entry.ticket.request.work == DispatchWork.Worker(WorkerMode.ResolveConflict), "Only a combination resolver has a merge report")
          val plan = HostFiles.read(entry.directory.resolve("combination.json"), CombinationPlan_JsonCodec, CombinationPlans.MaxBytes)
          require(plan.request.fence == entry.ticket.request.fence && plan.worker == entry.ticket.request.previous.get &&
            plan.members == entry.ticket.request.members, "Merge report belongs to another assignment")
          val assets = entry.directory.resolve("assets")
          require(Set("0\n", "1\n")(HostFiles.text(assets.resolve("merge-ready"), 2)), "Merge diagnostics are unavailable or incomplete")
          reader(assets, WorkspaceCommand.Read("merge.log", offset, limit))
        case value => reader(Path.of(record.directory), value)
      }
    }
    } yield result
  }

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
        input <- ZIO.attemptBlocking(new InputAssembler(authority.governor, config.owner, clock, requirements.current).assemble(entry.ticket.request))
        _ <- maintain(entry).forkScoped
        prepared <- ZIO.attemptBlocking {
          entry.check()
          val ticket = entry.ticket
          val profile = SupervisorConfig.profile(ticket.profile)
          // A candidate reviewer inherits the worker's validation as its revalidation rounds left it; the worker result itself is unchanged.
          val inherited = if (ticket.request.work != DispatchWork.Reviewer(ReviewerMode.Candidate)) Nil else {
            ReviewerValidation.inventory(input.previous.toList.flatMap(_.validation), config.settings.checks)
            val subject = ticket.request.previous.get
            val reader = new ArtifactReader(command => authority.governor.call(command) match {
              case Result.Failed(fault) => throw DomainFailure(fault)
              case value => value
            }, config.project.project)
            IntegrationValidation.effective(config.project.project, config.owner.actor.session, subject, input.previous.get, config.settings.checks,
              reader.amendments(subject)).current
          }
          SupervisorConfig.verifyProfile(config, profile)
          val combination = if (input.artifacts.exists(_.metadata.kind == ArtifactKind.Combination)) {
            val target = config.settings.integrationTarget.getOrElse(throw new IllegalArgumentException("No integration target configured"))
            new CombinationPreparation(authority.governor, config.owner, config.run.attempt.id, config.run.repository, target, clock).consume(input)
          } else None
          val base = combination.map(_.observedTarget).orElse(input.previous.flatMap(_.candidate)).getOrElse(candidates.fresh())
          if (combination.isEmpty) candidates.verifyBase(base)
          val body = HostFiles.encode(ChildExecutionInput_JsonCodec, ChildExecutionInput(input, base, config.settings.checks))
          val domain = SupervisorAuthority.harnessGrant(authority.root, config.project.project,
            Actor("CQ child " + ticket.attempt.id.value, ticket.attempt.session, ticket.attempt.role), clock)
          val local = access.issue(ticket.attempt.id, ticket.attempt.role)
          val assets = entry.directory.resolve("assets")
          val invocation = agents.invocation(ticket.request.work, profile.harness, ticket.attempt.id, {
            case McpTarget.Domain => HarnessMcp(McpTarget.Domain, config.endpoint.resolve("/mcp"), domain)
            case McpTarget.Local => HarnessMcp(McpTarget.Local, access.endpoint, local)
          }, assets)
          val native = registry(profile.harness).launch(profile, invocation, config.environment)
          val launched = combination match {
            case None => native
            case Some(plan) =>
              val prepared = new MergePreparation(Path.of(config.settings.guardian)).wrap(native, assets, candidates.mergeInputs(plan, ticket.attempt.id))
              HostFiles.immutable(entry.directory.resolve("combination.json"), HostFiles.encode(CombinationPlan_JsonCodec, plan), CombinationPlans.MaxBytes)
              prepared
          }
          launched.install(assets)
          val artifacts = List(
            ArtifactUpload(config.project.project, NativeArtifacts.id(ticket.attempt.id, "input"), ticket.attempt.id, ArtifactKind.Input, "application/json", body),
            ArtifactUpload(config.project.project, NativeArtifacts.id(ticket.attempt.id, "prompt"), ticket.attempt.id, ArtifactKind.Prompt, "text/markdown", invocation.system))
          queue.enqueue(1, DeliveryBatch(artifacts.map(HostDelivery.Artifact.apply)))
          queue.flush(authority.collector)
          (base, JobCommand(launched.arguments, launched.environment, body, SupervisorConfig.limits(ticket.request.limits)), combination, inherited)
        }
        (base, command, combination, inherited) = prepared
        _ <- ZIO.succeed {
          if (entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate))
            entry.installChecks(new ReviewerChecks(entry, base, config, authority.collector, jobs))
        }
        _ <- ZIO.succeed(entry.phase(DispatchPhase.Running))
        native <- launch(entry, entry.ticket.attempt.id, base, command)
        _ <- trace.update(_.copy(native = Some(native)))
        checks <- closeChecks(entry, trace)
        _ <- ZIO.attempt(require(!checks.pending && !checks.uncertain, "Reviewer exited without terminal published evidence for every requested check"))
        report <- ZIO.attemptBlocking {
          entry.check()
          val observed = JobOutcome.observed(native)
          require(observed.succeeded, observed.problem.getOrElse("Child process did not complete successfully"))
          val usage = collect(entry.ticket.attempt, entry.ticket.profile.version, clock.millis())
          require(usage.terminalSeen && !usage.nativeFailure, "Child native output did not complete successfully")
          val report = ChildContracts.report(entry.ticket.request.work, entry.ticket.request.members,
            Using.resource(NativeTranscript.stream(directory(entry.ticket.attempt.id).resolve("stdout")))(output.result(entry.ticket.attempt.harness, _, entry.directory.resolve("assets"))))
          report match {
            case plan: ChildReport.Plan =>
              val members = input.members.map(value => value.item.id -> value.item).toMap
              cq.core.CohortAssessmentPolicy.criteria(plan, members.apply)
              cq.core.CohortAssessmentPolicy.checks(plan, config.settings.checks)
            case _ => ()
          }
          report
        }
        evidence <- if (entry.ticket.attempt.role != Role.Worker) ZIO.succeed(CollectedEvidence(RetainedEvidence(Nil, Nil), Nil))
          else workspaces.get(config.owner, entry.ticket.attempt.id).flatMap { workspace => ZIO.attemptBlocking {
            val named = report match { case ChildReport.Work(members) => members.flatMap(_.evidence); case _ => Nil }
            new WorkspaceEvidence(config.project.project, entry.ticket.attempt.id, "evidence").collect(Path.of(workspace.directory), named)
          }}
        _ <- trace.update(value => value.copy(extra = value.extra ++ evidence.uploads))
        candidate <- report match {
          case ChildReport.Work(members) if members.exists(_.disposition == WorkDisposition.CandidateReady) =>
            workspaces.get(config.owner, entry.ticket.attempt.id).flatMap { workspace => ZIO.attemptBlocking {
              entry.check()
              combination.foreach { _ =>
                require(Set("0\n", "1\n")(HostFiles.text(entry.directory.resolve("assets/merge-ready"), 2)), "Merge preparation was not confirmed")
              }
              val commit = candidates.capture(workspace, combination, CandidateMessage(entry.ticket.attempt.id, input.members, input.guidance, combination))
              HostFiles.immutable(entry.directory.resolve("candidate.json"), HostFiles.encode(GitCommit_JsonCodec, commit), 1024)
              Some(commit)
            }}
          case _: ChildReport.Work => ZIO.succeed(None)
          case _: ChildReport.Review if entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) => ZIO.succeed(input.previous.flatMap(_.candidate))
          case _: ChildReport.Review | _: ChildReport.Plan | _: ChildReport.Evidence => ZIO.succeed(None)
        }
        validation <- if (entry.ticket.attempt.role == Role.Worker && candidate.nonEmpty) {
          ZIO.succeed(entry.phase(DispatchPhase.Validating)) *> ZIO.foreach(config.settings.checks.zipWithIndex) { case (check, index) =>
            validate(entry, candidate.get, check, index, trace)
          }
        } else if (entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate)) ZIO.succeed {
          ReviewerValidation.overlay(inherited, checks.evidence)
        }
        else ZIO.succeed(Nil)
        stored <- ZIO.attemptBlocking {
          entry.check()
          claim(entry, true)
          val value = ChildResult(entry.ticket.attempt.id, entry.ticket.request, base, candidate, report, validation, evidence.retained)
          ChildContracts.result(config.project.project, value)
          value
        }
      } yield stored
    }.either
    _ <- closeChecks(entry, trace)
    _ <- if (result.isLeft || entry.stopReason.nonEmpty) {
      workspaces.quarantine(config.owner, entry.ticket.attempt.id, "Child result failed, was cancelled or lost admission; inspect retained evidence").unit
        .catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }
    } else ZIO.unit
    observed <- trace.get
    _ <- publish(entry, result, observed).ensuring(ZIO.succeed(access.revoke(entry.ticket.attempt.id)))
  } yield ()

  private def closeChecks(entry: DispatchExecution, trace: Ref[Trace]): Task[ClosedReviewerChecks] = for {
    closed <- entry.reviewerChecks.fold(ZIO.succeed(ClosedReviewerChecks(Nil, false, false)))(_.close)
    _ <- trace.update(value => value.copy(uncertain = value.uncertain || closed.uncertain))
  } yield closed

  private def validate(entry: DispatchExecution, candidate: GitCommit, check: ValidationCheck, index: Int, trace: Ref[Trace]): Task[ValidationEvidence] = for {
    validated <- validation(entry.ticket.attempt.id, s"check-$index", candidate, check, (id, base, command) => launch(entry, id, base, command).ensuring(release(id).ignore))
    evidence = validated.evidence
    _ <- trace.update(value => value.copy(extra = value.extra ++ validated.artifacts, uncertain = value.uncertain || evidence.state == ValidationState.Unknown))
    _ <- ZIO.attempt(require(evidence.state != ValidationState.Unknown, "Host validation cleanup is unconfirmed"))
  } yield evidence

  private def publish(entry: DispatchExecution, result: Either[Throwable, ChildResult], trace: Trace): Task[Unit] = {
    val attempt = entry.ticket.attempt
    for {
      job <- trace.native match {
        case Some(value) => ZIO.succeed(Some(value))
        case None => jobs.await(config.owner, attempt.id).map(Some(_)).catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
      }
      workspace <- workspaces.get(config.owner, attempt.id).map(Some(_)).catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
      _ <- ZIO.attemptBlocking {
        val cancelled = entry.freeze()
        val project = config.project.project
        val stdout = transcript(attempt.id, "stdout", entry.ticket.request.limits.retainedOutputBytes)
        val stderr = transcript(attempt.id, "stderr", entry.ticket.request.limits.retainedOutputBytes)
        val (_, outParts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
        val (_, errParts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
        val collectedAt = math.max(attempt.startedAt, clock.millis())
        val usage = collect(attempt, entry.ticket.profile.version, collectedAt)
        val problem = cancelled.orElse(result.left.toOption.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName))).map(DispatchProjection.concise)
        val valid = if (cancelled.nonEmpty) None else result.toOption
        val observed = job.map(JobOutcome.observed)
        val state = if (trace.uncertain || observed.exists(_.state == AttemptState.Unknown)) AttemptState.Unknown
          else if (cancelled.nonEmpty) AttemptState.Cancelled
          else observed.map(_.withResult(valid.nonEmpty)).getOrElse(AttemptState.Failed)
        // A worker that failed or was cancelled leaves no result; its workspace state is retained so a following attempt can continue from it.
        val partial = workspace.filter(_ => attempt.role == Role.Worker && Set(AttemptState.Failed, AttemptState.Cancelled)(state))
          .map(record => Try(partials.capture(attempt.id, state, Path.of(record.directory), stdout, stderr)).toEither)
        val partialGap = partial.flatMap(_.left.toOption).map(error => "Partial work collection failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
        val allArtifacts = outParts ++ errParts ++ trace.extra ++ partial.flatMap(_.toOption).toList.flatMap(_._2)
        val observations = usage.meters.flatMap(batch => HostDelivery.Usage(HostUsageInput(project, HostUsage.Meter(batch.meter))) ::
          batch.observations.map(value => HostDelivery.Usage(HostUsageInput(project, HostUsage.Ingest(value)))))
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id, state, collectedAt,
          (problem.toList ++ observed.toList.flatMap(_.problem).map(DispatchProjection.concise) ++ partialGap.map(DispatchProjection.concise) ++ usage.gaps).take(MaxGaps), None)
        val entries = allArtifacts.map(HostDelivery.Artifact.apply) ++ observations
        val base = entry.status.copy(process = job.map(_.phase), blocker = problem, partial = partial.flatMap(_.toOption).map(_._1),
          usageDelivered = false, detailsOmitted = true, workspace = workspace.map(DispatchProjection.workspace))
        val publication = new ChildPublicationDelivery(entry.directory, entry.ticket)
        publication.seal(ChildPublication(project, config.owner.actor, valid, base, outcome), entries)
        val retained = Try(publication.finish(authority.collector)).toOption.map(_.status).getOrElse(base.copy(
          phase = DispatchPhase.PublicationPending, next = ChildNext.RetryDelivery, result = None,
          blocker = Some("Result admission or operational publication is pending; retain the session directory and run cq job upload")))
        entry.finish(retained)
      }
      _ <- if (entry.status.phase == DispatchPhase.Failed && result.isRight)
        workspaces.quarantine(config.owner, entry.ticket.attempt.id, "Server result admission rejected; inspect retained evidence")
          .map(record => entry.finish(entry.status.copy(workspace = Some(DispatchProjection.workspace(record)))))
      // The candidate is a commit under refs/cq/candidates and the evidence is published: nothing reads a completed attempt's tree again.
      else if (entry.status.phase == DispatchPhase.Completed) release(attempt.id)
        .map(_.foreach(record => entry.finish(entry.status.copy(workspace = Some(DispatchProjection.workspace(record)))))).ignore
      else ZIO.unit
    } yield ()
  }
}
