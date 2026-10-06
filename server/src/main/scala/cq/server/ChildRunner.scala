package cq.server

import cq.api.*
import cq.core.{DomainFailure, WorkspaceService}
import cq.host.*
import java.nio.file.{Files, Path}
import java.time.Clock
import scala.util.Using
import zio.{IO, Ref, Task, ZIO}

final class ChildRunner(config: SupervisorConfig, authority: SupervisorAuthority, registry: HarnessRegistry, jobs: JobSupervisor,
  workspaces: WorkspaceService[IO], agents: AgentCatalog, output: HarnessOutput,
  candidates: CandidateWorkspace, reader: WorkspaceReader, access: LocalAccess, requirements: OperatorRequirements, renewal: ClaimRenewal, clock: Clock) {
  private val settlement = new AttemptSettlement(config, authority, jobs, workspaces, candidates, renewal, clock)
  private def directory(attempt: AttemptId): Path = config.directory.resolve("payload").resolve(attempt.value.toString)

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

  def run(entry: DispatchExecution): Task[Unit] = (for {
    trace <- Ref.make(AttemptTrace.Empty)
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
        // Input assembly renews the claim; the renewals that follow count their lease from before it.
        began <- zio.Clock.nanoTime
        input <- ZIO.attemptBlocking(new InputAssembler(authority.governor, config.owner, clock, requirements.current).assemble(entry.ticket.request))
        _ <- settlement.maintain(entry, began, ZIO.unit).forkScoped
        prepared <- ZIO.attemptBlocking {
          entry.check()
          val ticket = entry.ticket
          // The attempt carries its route. What refuses the route before a process exists is an abstention: another model may run the input.
          val setting = ticket.profile.getOrElse(throw Abstention(AbstentionReason.Unconfigured, s"The session settings have no entry for ${ticket.attempt.harness}"))
          val profile = Abstention.unless(AbstentionReason.Unconfigured)(HarnessProfile(setting,
            ModelRoute(ticket.attempt.harness, Some(ticket.attempt.provider), ticket.attempt.model, ticket.attempt.effort)))
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
          Abstention.unless(AbstentionReason.Launch)(SupervisorConfig.verifyProfile(config, profile))
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
          val native = Abstention.unless(AbstentionReason.Launch)(registry(profile.harness).launch(profile, invocation, config.environment))
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
        native <- settlement.launch(entry, entry.ticket.attempt.id, base, command)
        _ <- trace.update(_.copy(native = Some(native)))
        checks <- closeChecks(entry, trace)
        _ <- ZIO.attempt(require(!checks.pending && !checks.uncertain, "Reviewer exited without terminal published evidence for every requested check"))
        report <- ZIO.attemptBlocking {
          entry.check()
          val observed = JobOutcome.observed(native)
          Abstention.launch(native).foreach(throw _)
          val usage = settlement.collect(entry.ticket, clock.millis())
          // A provider's refusal counts only when the harness ended by itself: a stopped or uncertain job is judged by how it was stopped.
          if (observed.state != AttemptState.Unknown && native.exit.exists(_.reason == StopReason.Exited)) usage.abstention.foreach(throw _)
          require(observed.succeeded, observed.problem.getOrElse("Child process did not complete successfully"))
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
        evidence <- settlement.evidence(entry, report)
        _ <- trace.update(value => value.copy(extra = value.extra ++ evidence.uploads))
        candidate <- report match {
          case ChildReport.Work(members) => settlement.capture(entry, members, input, combination)
          case _: ChildReport.Review if entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) => ZIO.succeed(input.previous.flatMap(_.candidate))
          case _: ChildReport.Review | _: ChildReport.Plan | _: ChildReport.Evidence => ZIO.succeed(None)
        }
        validation <- if (entry.ticket.attempt.role == Role.Worker && candidate.nonEmpty) settlement.checked(entry, candidate.get, trace)
        else if (entry.ticket.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate)) ZIO.succeed {
          ReviewerValidation.overlay(inherited, checks.evidence)
        }
        else ZIO.succeed(Nil)
        stored <- settlement.stored(entry, base, candidate, report, validation, evidence.retained)
      } yield stored
    }.either
    _ <- closeChecks(entry, trace)
    _ <- if (result.isLeft || entry.stopReason.nonEmpty) settlement.quarantine(entry, "Child result failed, was cancelled or lost admission; inspect retained evidence")
      else ZIO.unit
    observed <- trace.get
    _ <- settlement.publish(entry, result, observed)
  } yield ()).ensuring(ZIO.succeed(access.revoke(entry.ticket.attempt.id)))

  private def closeChecks(entry: DispatchExecution, trace: Ref[AttemptTrace]): Task[ClosedReviewerChecks] = for {
    closed <- entry.reviewerChecks.fold(ZIO.succeed(ClosedReviewerChecks(Nil, false, false)))(_.close)
    _ <- trace.update(value => value.copy(uncertain = value.uncertain || closed.uncertain))
  } yield closed
}
