package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, Runtime, Unsafe, ZIO}

abstract class CohortSelectionTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]]))
  private def uuid: UUID = UUID.randomUUID()
  private def owner: Scope = Scope(ProjectId(uuid), Actor("cohort governor", SessionId(uuid), Role.Governor))
  private def task: ItemDraft = ItemDraft("Work", "Required behavior", Set("shared-label"), false,
    Content.Task(TaskStatus.Ready, List("Independent acceptance"), None, Nil), Nil)
  private def request(roots: Set[ItemId], work: DispatchWork): CohortRequest = CohortRequest(RequestId(uuid), roots, work, Nil, Nil, None,
    HostLimits(3000, 10000, 1000, 300, 2000, 262144))
  private def link(ledger: LedgerService[IO], scope: Scope, source: ItemId, relation: Relation, target: ItemId): IO[Throwable, Unit] = for {
    a <- ledger.get(scope, source)
    b <- ledger.get(scope, target)
    _ <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Reference(source, a.item.revision, relation, target, b.item.revision, true)), Nil, "Context"))
  } yield ()
  private def api(ledger: LedgerService[IO], scope: Scope, runtime: Runtime[Any]): ServerApi = new ServerApi {
    override def call(command: Command): Result = {
      val effect = command match {
        case Command.Graph(input) => ledger.workset(scope, input.roots, input.after, input.snapshot, input.limit).map(Result.Workset.apply)
        case Command.Read(ReadInput(_, ReadSelection.ItemDetails(members, bytes))) => ledger.details(scope, members, bytes).map(Result.Details.apply)
        case Command.Read(ReadInput(_, ReadSelection.Claims(members))) => ledger.claimPreview(scope, members).map(Result.Claims.apply)
        case Command.Read(ReadInput(_, ReadSelection.ItemDetail(id))) => ledger.get(scope, id).map(Result.Detail.apply)
        case _ => ZIO.fail(new IllegalStateException("Unexpected selection read"))
      }
      Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect.either).getOrThrowFiberFailure() } match {
        case Right(value) => value
        case Left(DomainFailure(fault)) => Result.Failed(fault)
        case Left(error) => throw error
      }
    }
    override def usage(input: HostUsageInput): HostUsageResult = throw new IllegalStateException("Selection cannot write usage")
    override def artifact(input: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Planner cannot publish directly")
    override def grant(input: GrantRequest): AccessToken = throw new IllegalStateException("Selection cannot issue credentials")
    override def admit(input: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Selection cannot admit a child")
    override def integrate(input: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Selection cannot integrate")
  }

  private final class EvidenceApi(underlying: ServerApi, artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], scope: Scope, runtime: Runtime[Any]) extends ServerApi {
    override def call(command: Command): Result = {
      val read = command match {
        case Command.Read(ReadInput(_, ReadSelection.ArtifactInfo(id))) => Some(artifacts.metadata(scope, id).map(Result.ArtifactInfo.apply))
        case Command.Read(ReadInput(_, ReadSelection.ArtifactText(id, offset, limit))) => Some(artifacts.page(scope, id, offset, limit).map(Result.ArtifactText.apply))
        case Command.Read(ReadInput(_, ReadSelection.Admission(attempt))) => Some(admissions.get(scope, attempt).map(Result.Admission.apply))
        case _ => None
      }
      read.fold(underlying.call(command))(effect => Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect).getOrThrowFiberFailure() })
    }
    override def usage(input: HostUsageInput): HostUsageResult = underlying.usage(input)
    override def artifact(input: ArtifactUpload): ArtifactMetadata = underlying.artifact(input)
    override def grant(input: GrantRequest): AccessToken = underlying.grant(input)
    override def admit(input: HostAdmissionInput): ResultAdmission = underlying.admit(input)
    override def integrate(input: HostIntegrationInput): IntegrationRecord = underlying.integrate(input)
  }

  private final case class Assessed(scope: Scope, members: List[ItemRevision], artifact: ArtifactId, checks: List[ValidationCheck], base: GitCommit,
    collector: Scope, parent: AttemptId, fence: Fence)
  private def assessed(ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], count: Int, compatibility: CohortCompatibility): IO[Throwable, Assessed] = {
    val scope = owner
    val collector = scope.copy(actor = scope.actor.copy(subject = "host", role = Role.Collector))
    val checks = List(ValidationCheck("acceptance", List("verify"), 5000, 4096))
    val base = GitCommit("a" * 40)
    for {
      _ <- ledger.initialize(scope, "Assessment selection")
      created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(count)(Mutation.Create(task)), Nil, "Candidates"))
      claim <- ledger.acquire(scope, ClaimId(uuid), created.items.map(_.id).toSet, 300000)
      governing <- usage.assign(collector, Assignment(AssignmentId(uuid), scope.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, scope.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000))
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), scope.project, claim.members, Attribution.Shared, Some(uuid), None))
      attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), scope.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001))
      dispatch = DispatchRequest(RequestId(uuid), DispatchWork.Planner(), Harness.Codex, created.items, Nil, Nil, None, claim.fence,
        HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      views <- ZIO.foreach(created.items)(ref => ledger.get(scope, ref.id))
      input = ChildExecutionInput(ChildInput(scope.project, dispatch, views, Nil, Nil, None), base, checks)
      _ <- artifacts.upload(collector, ArtifactUpload(scope.project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input,
        "application/json", Wire.encode(ChildExecutionInput_JsonCodec, input)))
      groups = created.items.grouped(2).map(members => CohortAssessment(compatibility, "Share implementation", "No dependency conflict", "Separate acceptance",
        members.map(member => CohortMemberAssessment(member, List(CohortCriterion(0, Set("acceptance"), "Inspect this task")))))).toList
      report = ChildReport.Plan(created.items.map(ref => PlanMember(ref.id, PlanDisposition.Assessed, "Assessed")), None, groups)
      result = ChildResult(attempt.id, dispatch, base, None, report, Nil, RetainedEvidence(Nil, Nil))
      stored <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
      admitted <- admissions.admit(collector, HostAdmissionInput(scope.project, stored.id, scope.actor))
      _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
    } yield Assessed(scope, created.items, stored.id, checks, base, collector, parent.id, claim.fence)
  }

  private final case class Published(result: ChildResult, id: ArtifactId)
  private def goal: ItemDraft = task.copy(title = "Goal", content = Content.Goal(GoalStatus.Open, "Outcome", List("Goal acceptance"), "Scope"))
  private def milestone: ItemDraft = task.copy(title = "Milestone", content = Content.Milestone(MilestoneStatus.Open, "Release"))
  private final case class Organised(scope: Scope, goal: ItemId, milestone: ItemId, tasks: List[ItemId])
  // D74 fixture: Goal G Produces T4, T5, T6; T5 BlockedBy T4 and, when chained, T6 BlockedBy T5; Milestone M is separate.
  private def organised(ledger: LedgerService[IO], chained: Boolean): IO[Throwable, Organised] = {
    val scope = owner
    for {
      _ <- ledger.initialize(scope, "D74 organisation")
      created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal), Mutation.Create(milestone)) ++
        List.fill(3)(Mutation.Create(task)), Nil, "Goal, milestone and tasks"))
      ids = created.items.map(_.id)
      tasks = ids.drop(2)
      _ <- ZIO.foreachDiscard(tasks)(id => link(ledger, scope, ids.head, Relation.Produces, id))
      _ <- link(ledger, scope, tasks(1), Relation.BlockedBy, tasks.head)
      _ <- ZIO.when(chained)(link(ledger, scope, tasks(2), Relation.BlockedBy, tasks(1)))
    } yield Organised(scope, ids.head, ids(1), tasks)
  }
  private def publish(f: Assessed, work: DispatchWork, report: ChildReport, previous: Option[Published], validation: List[ValidationEvidence], ledger: LedgerService[IO], usage: UsageService[IO],
    artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]): IO[Throwable, Published] = for {
    assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.scope.project, f.members.map(_.id).toSet, Attribution.Shared, Some(uuid), None))
    attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.parent), f.scope.actor.session, ChildContracts.role(work), Harness.Codex, "fixture", "fixture", "fixture", 1002))
    dispatch = DispatchRequest(RequestId(uuid), work, Harness.Codex, f.members, Nil, Nil, previous.map(_.id), f.fence,
      HostLimits(3000, 10000, 1000, 300, 2000, 262144))
    base = previous.flatMap(_.result.candidate).getOrElse(f.base)
    views <- ZIO.foreach(f.members)(ref => ledger.get(f.scope, ref.id))
    input = ChildExecutionInput(ChildInput(f.scope.project, dispatch, views, Nil, Nil, previous.map(_.result)), base, f.checks)
    _ <- artifacts.upload(f.collector, ArtifactUpload(f.scope.project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input,
      "application/json", Wire.encode(ChildExecutionInput_JsonCodec, input)))
    result = ChildResult(attempt.id, dispatch, base, if (work == DispatchWork.Planner()) None else Some(GitCommit("b" * 40)), report, validation, RetainedEvidence(Nil, Nil))
    stored <- artifacts.upload(f.collector, ArtifactUpload(f.scope.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
    admitted <- admissions.admit(f.collector, HostAdmissionInput(f.scope.project, stored.id, f.scope.actor))
    _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
  } yield Published(result, stored.id)

  "Automatic cohort selection (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "offer an explicit completed root for planning its reopening without executing or rediscovering completed work" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      val completed = task.copy(content = Content.Task(TaskStatus.Done, List("Independent acceptance"), None, Nil))
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "Reviewed reopening")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(completed), Mutation.Create(completed)), Nil, "Completed work"))
        root = created.items.head.id
        descendant = created.items.last.id
        _ <- link(ledger, scope, root, Relation.Produces, descendant)
        _ <- link(ledger, scope, root, Relation.BlockedBy, descendant)
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(Set(root), DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(selected.evidence.decision.choices.flatMap(_.members.map(_.id)) == List(root))
        _ <- assertIO(selected.evidence.decision.counts.notReady == 1)
        choice = selected.evidence.decision.choices.head
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        worker <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Worker(WorkerMode.Implement)), ArtifactId(uuid)))
        _ <- assertIO(worker.evidence.decision.choices.isEmpty && worker.evidence.decision.counts.notReady == 2)
        foreign = scope.copy(actor = scope.actor.copy(session = SessionId(uuid)))
        claim <- ledger.acquire(foreign, ClaimId(uuid), Set(root), 300000)
        held <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(held.evidence.decision.choices.isEmpty && held.evidence.considered.exists(_.reason == CohortReason.Claimed))
        _ <- ledger.release(foreign, claim.fence)
        dependency <- ledger.get(scope, descendant)
        changed <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(descendant, dependency.item.revision, task)), Nil, "Reopen prerequisite"))
        blocked <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(!blocked.evidence.decision.choices.flatMap(_.members.map(_.id)).contains(root))
        unavailable <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id))).either
        _ <- assertIO(unavailable.left.exists(_.getMessage.contains("ready")))
        _ <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(descendant, changed.items.head.revision, completed)), Nil, "Complete prerequisite"))
        current <- ledger.get(scope, root)
        _ <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(root, current.item.revision,
          current.item.draft.copy(archived = true))), Nil, "Archive completed root"))
        hidden <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(hidden.evidence.decision.choices.isEmpty)
        stale <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id))).either
        _ <- assertIO(stale.isLeft)
      } yield ()
    }

    "refresh exact assessment revisions without retrying an unchanged unsuccessful worker" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, progress)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement))
        initial <- ZIO.attemptBlocking(planner.plan(input.copy(artifacts = List(fixture.artifact)), ArtifactId(uuid)))
        _ <- assertIO(initial.evidence.decision.choices.size == 1 && initial.evidence.decision.choices.head.members.size == 2)
        _ <- ZIO.attempt(progress.started(initial.fingerprints(initial.evidence.decision.choices.head.id)))
        worker <- publish(fixture, input.work, ChildReport.Work(fixture.members.map(ref => WorkMember(ref.id, WorkDisposition.Failed, "Observed failure", Nil))),
          None, Nil, ledger, usage, artifacts, admissions)
        old <- ZIO.attemptBlocking(new ArtifactReader(reads.call, fixture.scope.project).result(fixture.artifact).value.report.asInstanceOf[ChildReport.Plan])
        views <- ZIO.foreach(fixture.members)(ref => ledger.get(fixture.scope, ref.id))
        changed <- ledger.change(fixture.scope, ChangeRequest(RequestId(uuid), views.map(view => Mutation.Replace(view.item.id, view.item.revision,
          view.item.draft.copy(labels = Set("first cosmetic revision")))), List(fixture.fence), "Change labels only"))
        refreshInput = input.copy(request = RequestId(uuid), artifacts = List(fixture.artifact, worker.id))
        refresh <- ZIO.attemptBlocking(planner.plan(refreshInput, ArtifactId(uuid)))
        _ <- assertIO(refresh.evidence.decision.choices.size == 1 && refresh.evidence.decision.choices.head.work == DispatchWork.Planner())
        choice = refresh.evidence.decision.choices.head
        _ <- assertIO(choice.reason == CohortReason.AssessmentRequired && choice.members.toSet == changed.items.toSet)
        _ <- ZIO.attemptBlocking(planner.verify(refreshInput, choice, refresh.fingerprints(choice.id)))
        _ <- ZIO.attempt(progress.started(refresh.fingerprints(choice.id)))
        repeated <- ZIO.attemptBlocking(planner.plan(refreshInput.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(repeated.evidence.decision.choices.isEmpty)
        revisions = changed.items.map(ref => ref.id -> ref).toMap
        freshReport = old.copy(assessments = old.assessments.map(group => group.copy(members = group.members.map(member =>
          member.copy(member = revisions(member.member.id))))))
        assessedAgain <- publish(fixture.copy(members = changed.items), DispatchWork.Planner(), freshReport, None, Nil, ledger, usage, artifacts, admissions)
        reconsider = input.copy(request = RequestId(uuid), artifacts = List(assessedAgain.id, worker.id))
        deferred <- ZIO.attemptBlocking(planner.plan(reconsider, ArtifactId(uuid)))
        _ <- assertIO(deferred.evidence.decision.choices.isEmpty && deferred.evidence.considered.exists(_.reason == CohortReason.Deferred))
        current <- ZIO.foreach(fixture.members)(ref => ledger.get(fixture.scope, ref.id))
        changedAgain <- ledger.change(fixture.scope, ChangeRequest(RequestId(uuid), current.take(1).map(view => Mutation.Replace(view.item.id, view.item.revision,
          view.item.draft.copy(labels = Set("second cosmetic revision")))), List(fixture.fence), "Change labels again"))
        revisedMembers = current.map(view => changedAgain.items.find(_.id == view.item.id).getOrElse(ItemRevision(view.item.id, view.item.revision)))
        second <- ZIO.attemptBlocking(planner.plan(reconsider.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(second.evidence.decision.choices.size == 1 && second.evidence.decision.choices.head.work == DispatchWork.Planner() &&
          second.evidence.decision.choices.head.members.toSet == revisedMembers.toSet)
      } yield ()
    }

    "retain deferral across republished validation but reconsider changed output" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        worker <- publish(fixture, DispatchWork.Worker(WorkerMode.Implement), ChildReport.Work(fixture.members.map(ref =>
          WorkMember(ref.id, WorkDisposition.Failed, "Observed failure", Nil))), None, Nil, ledger, usage, artifacts, admissions)
        observed <- ZIO.foreach(List(("original", "same failure"), ("replay", "same failure"), ("changed", "new failure"))) { (name, body) =>
          val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          val (out, uploads) = NativeArtifacts.binary(fixture.scope.project, worker.result.attempt, name, "text/plain", bytes)
          val spec = WorkspaceSpec(fixture.scope.project, fixture.scope.actor.session, AttemptId(uuid), "/consumer", GitCommit("b" * 40))
          val job = JobRecord(spec, uuid.toString, JobTarget.Run, JobPhase.Settled,
            Some(JobExit(Some(1), None, StopReason.Exited, bytes.length.toLong, bytes.length.toLong, true, false)), None, 1, 1, 2)
          val value = ValidationObservation(fixture.checks.head, spec.base, job, out, out)
          ZIO.foreachDiscard(uploads)(artifacts.upload(fixture.collector, _)) *>
            artifacts.upload(fixture.collector, ArtifactUpload(fixture.scope.project, ArtifactId(uuid), worker.result.attempt, ArtifactKind.Validation,
              "application/json", Wire.encode(ValidationObservation_JsonCodec, value))).map(_.id)
        }
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, progress)
        input = request(Set(fixture.members.head.id), DispatchWork.Explorer(ExplorerMode.Investigate))
        first <- ZIO.attemptBlocking(planner.plan(input.copy(artifacts = List(observed.head)), ArtifactId(uuid)))
        _ <- ZIO.attempt(progress.started(first.fingerprints(first.evidence.decision.choices.head.id)))
        replay <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(observed(1))), ArtifactId(uuid)))
        changed <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(observed(2))), ArtifactId(uuid)))
        _ <- assertIO(replay.evidence.decision.choices.isEmpty && changed.evidence.decision.choices.size == 1)
        reviews <- ZIO.foreach(observed)(id => publish(fixture, DispatchWork.Reviewer(ReviewerMode.Candidate),
          ChildReport.Review(fixture.members.map(ref => ReviewMember(ref.id, ReviewVerdict.ChangesRequested, List("Correct the failure"))), None),
          Some(worker), List(ValidationEvidence(fixture.checks.head.name, ValidationState.Failed, id)), ledger, usage, artifacts, admissions))
        nestedProgress = new CohortProgress
        nested = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, nestedProgress)
        initial <- ZIO.attemptBlocking(nested.plan(input.copy(artifacts = List(reviews.head.id)), ArtifactId(uuid)))
        _ <- ZIO.attempt(nestedProgress.started(initial.fingerprints(initial.evidence.decision.choices.head.id)))
        repeated <- ZIO.attemptBlocking(nested.plan(input.copy(request = RequestId(uuid), artifacts = List(reviews(1).id)), ArtifactId(uuid)))
        revised <- ZIO.attemptBlocking(nested.plan(input.copy(request = RequestId(uuid), artifacts = List(reviews(2).id)), ArtifactId(uuid)))
        _ <- assertIO(repeated.evidence.decision.choices.isEmpty)
        _ <- assertIO(revised.evidence.decision.choices.size == 1)
      } yield ()
    }

    "offer every singleton from a large unknown prior plan before repeating its first eight" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 16, CohortCompatibility.Unknown)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, progress)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(previous = Some(fixture.artifact))
        rounds <- ZIO.foreach(1 to 2) { _ => ZIO.attemptBlocking {
          val choices = planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)).evidence.decision.choices
          progress.offered(choices.flatMap(_.members.map(_.id)))
          choices
        }}
        _ <- assertIO(rounds.forall(_.size == 8) && rounds.flatten.flatMap(_.members.map(_.id)).distinct.size == 16)
      } yield ()
    }

    "separate accepted members from corrections and require explicit fresh selection to abandon a candidate" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        prepared <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        fixture = prepared.copy(checks = Nil)
        worker <- publish(fixture, DispatchWork.Worker(WorkerMode.Implement), ChildReport.Work(fixture.members.map(ref =>
          WorkMember(ref.id, WorkDisposition.CandidateReady, "Candidate", Nil))), None, Nil, ledger, usage, artifacts, admissions)
        reviewer <- publish(fixture, DispatchWork.Reviewer(ReviewerMode.Candidate), ChildReport.Review(fixture.members.zipWithIndex.map { (ref, index) =>
          if (index == 0) ReviewMember(ref.id, ReviewVerdict.ChangesRequested, List("Fix this task"))
          else ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)
        }, None), Some(worker), Nil, ledger, usage, artifacts, admissions)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        planner = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, new CohortProgress)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement))
        fresh <- ZIO.attemptBlocking(planner.plan(input.copy(artifacts = List(reviewer.id)), ArtifactId(uuid)))
        exact <- ZIO.attemptBlocking(planner.plan(input.copy(previous = Some(reviewer.id)), ArtifactId(uuid)))
        _ <- assertIO(exact.evidence.decision.choices.isEmpty && exact.evidence.decision.counts.excluded == fixture.members.size)
        _ <- assertIO(fresh.evidence.decision.choices.flatMap(_.members.map(_.id)) == List(fixture.members.head.id) &&
          fresh.evidence.decision.choices.head.reason == CohortReason.FreshFromBase)
        changed <- ZIO.attemptBlocking(new CohortPlanner(reads, fixture.scope, fixture.base, prepared.checks, new CohortProgress)
          .plan(input.copy(artifacts = List(reviewer.id)), ArtifactId(uuid)))
        _ <- assertIO(changed.evidence.decision.choices.flatMap(_.members.map(_.id)).toSet == fixture.members.map(_.id).toSet)
      } yield ()
    }

    "use exact whole-group assessment evidence without requiring a common producer" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(artifacts = List(fixture.artifact))
        choices <- ZIO.attemptBlocking {
          def select(base: GitCommit, checks: List[ValidationCheck]) = new CohortPlanner(reads, fixture.scope, base, checks, new CohortProgress)
            .plan(input, ArtifactId(uuid)).evidence.decision.choices
          val compatible = select(fixture.base, fixture.checks)
          val changedBase = select(GitCommit("b" * 40), fixture.checks)
          val changedCheck = select(fixture.base, fixture.checks.map(_.copy(command = List("different-verifier"))))
          (compatible, changedBase, changedCheck)
        }
        _ <- assertIO(choices._1.map(_.members.toSet).toSet == fixture.members.grouped(2).map(_.toSet).toSet &&
          choices._1.forall(_.reason == CohortReason.CompatibleAssessment))
        _ <- assertIO(choices._2.size == 6 && choices._3.size == 6 && (choices._2 ++ choices._3).forall(_.members.size == 1))
      } yield ()
    }

    "partition a larger prior plan through its assessments while preserving the original artifact" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        planner = new CohortPlanner(reads, fixture.scope, fixture.base, fixture.checks, new CohortProgress)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(previous = Some(fixture.artifact))
        result <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        choices = result.evidence.decision.choices
        _ <- assertIO(choices.map(_.members.toSet).toSet == fixture.members.grouped(2).map(_.toSet).toSet &&
          choices.forall(choice => choice.work == input.work && choice.previous.isEmpty && choice.artifacts == List(fixture.artifact)))
      } yield ()
    }

    "require one common producer for the complete group and exclude contextual siblings" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "whole group")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(6)(Mutation.Create(task)) :+
          Mutation.Create(task.copy(content = Content.Milestone(MilestoneStatus.Open, "Release"))), Nil, "Candidates and contexts"))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard(List((4, 0), (4, 1), (5, 1), (5, 2), (5, 3))) { (a, b) => link(ledger, scope, ids(a), Relation.Produces, ids(b)) }
        _ <- link(ledger, scope, ids(6), Relation.Contains, ids(0))
        _ <- link(ledger, scope, ids(6), Relation.Contains, ids(3))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        result <- ZIO.attemptBlocking(planner.plan(request(ids.take(3).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        choices = result.evidence.decision.choices
        _ <- assertIO(choices.map(_.members.map(_.id).toSet) == List(ids.take(2).toSet, Set(ids(2))) &&
          choices.head.witness.contains(ids(4)) && result.evidence.decision.counts.selected == 3)
      } yield ()
    }

    "keep fresh audit reviews separate without an exact prior joint result" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "audit grouping")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(3)(Mutation.Create(task)), Nil, "Candidates"))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard(ids.tail)(id => link(ledger, scope, ids.head, Relation.Produces, id))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        result <- ZIO.attemptBlocking(planner.plan(request(ids.tail.toSet, DispatchWork.Reviewer(ReviewerMode.Audit)), ArtifactId(uuid)))
        _ <- assertIO(result.evidence.decision.choices.size == 2 && result.evidence.decision.choices.forall(_.members.size == 1))
      } yield ()
    }

    "defer unchanged members when a later round narrows or regroups an executed assignment" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "member progress")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(4)(Mutation.Create(task)), Nil, "Candidates"))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard(ids.tail)(id => link(ledger, scope, ids.head, Relation.Produces, id))
        progress = new CohortProgress
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, progress)
        initial <- ZIO.attemptBlocking(planner.plan(request(ids.slice(1, 3).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        choice = initial.evidence.decision.choices.head
        _ <- ZIO.attempt(progress.started(initial.fingerprints(choice.id)))
        narrowed <- ZIO.attemptBlocking(planner.plan(request(Set(ids(1)), DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        regrouped <- ZIO.attemptBlocking(planner.plan(request(ids.tail.toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        _ <- assertIO(narrowed.evidence.decision.choices.isEmpty &&
          regrouped.evidence.decision.choices.flatMap(_.members.map(_.id)) == List(ids(3)))
      } yield ()
    }

    "recheck prerequisite readiness before starting an otherwise unchanged choice" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "readiness drift")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(task),
          Mutation.Create(task.copy(content = Content.Task(TaskStatus.Done, List("Independent acceptance"), None, Nil)))), Nil, "Candidates"))
        member = created.items.head.id
        dependency = created.items.last.id
        _ <- link(ledger, scope, member, Relation.BlockedBy, dependency)
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(Set(member), DispatchWork.Explorer(ExplorerMode.Investigate))
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        choice = selected.evidence.decision.choices.head
        before <- ledger.get(scope, member)
        current <- ledger.get(scope, dependency)
        _ <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(dependency, current.item.revision, task)), Nil, "Prerequisite reopened"))
        after <- ledger.get(scope, member)
        _ <- assertIO(after == before)
        checked <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id))).either
        _ <- assertIO(checked.left.exists(_.getMessage.contains("ready")))
      } yield ()
    }

    "offer all 32 independent candidates before repeats as the graph changes" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "fairness")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(32)(Mutation.Create(task)), Nil, "Candidates"))
        progress = new CohortProgress
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, progress)
        first <- ZIO.attemptBlocking(planner.plan(request(created.items.map(_.id).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        _ <- ZIO.attempt(progress.offered(first.evidence.decision.choices.flatMap(_.members.map(_.id))))
        extra <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(task)), Nil, "Later arrival"))
        roots = (created.items ++ extra.items).map(_.id).toSet
        next <- ZIO.foreach(1 to 3) { _ => ZIO.attemptBlocking {
          val value = planner.plan(request(roots, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid))
          progress.offered(value.evidence.decision.choices.flatMap(_.members.map(_.id)))
          value
        }}
        offered = (first :: next.toList).flatMap(_.evidence.decision.choices.flatMap(_.members.map(_.id)))
        _ <- assertIO(offered.size == 32 && offered.toSet == created.items.map(_.id).toSet &&
          (first :: next.toList).forall(_.evidence.decision.choices.forall(_.members.size == 1)))
      } yield ()
    }

    "advance beyond a full inspected pool claimed by another governor" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "excluded pool")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(33)(Mutation.Create(task)), Nil, "Candidates"))
        foreign = scope.copy(actor = scope.actor.copy(session = SessionId(uuid)))
        _ <- ledger.acquire(foreign, ClaimId(uuid), created.items.take(32).map(_.id).toSet, 300000)
        progress = new CohortProgress
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, progress)
        decisions <- ZIO.foreach(1 to 2) { _ => ZIO.attemptBlocking(planner.plan(
          request(created.items.map(_.id).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid))) }
        offered = decisions.flatMap(_.evidence.decision.choices.flatMap(_.members.map(_.id)))
        _ <- assertIO(offered.contains(created.items.last.id))
      } yield ()
    }

    "D74: offer a rooted milestone with its Tasks and blocked Tasks to a Planner only" in { (ledger: LedgerService[IO]) =>
      for {
        runtime <- ZIO.runtime[Any]
        f <- organised(ledger, chained = true)
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(Set(f.goal, f.milestone), DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        groups = selected.evidence.decision.choices.map(_.members.map(_.id).toSet)
        _ <- ZIO.succeed(println("D74 milestone planner groups=" + selected.evidence.decision.choices.map(choice =>
          (choice.members.map(_.id.number), choice.reason))))
        choice = selected.evidence.decision.choices.find(_.members.exists(_.id == f.milestone)).get
        _ <- assertIO(groups.contains((f.milestone :: f.tasks).toSet) && choice.reason == CohortReason.PlannerOrganisation &&
          choice.witness.isEmpty && choice.cohort.nonEmpty && groups.contains(Set(f.goal)))
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        claim <- ledger.acquire(f.scope, ClaimId(uuid), choice.members.map(_.id).toSet, 300000)
        _ <- ledger.release(f.scope, claim.fence)
        explorer <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        explored = explorer.evidence.decision.choices.flatMap(_.members.map(_.id))
        _ <- assertIO(!explored.contains(f.tasks(1)) && !explored.contains(f.tasks(2)) &&
          explorer.evidence.decision.choices.forall(value => value.members.size == 1 || !value.members.exists(_.id == f.milestone)) &&
          explorer.evidence.decision.choices.forall(_.reason != CohortReason.PlannerOrganisation))
      } yield ()
    }

    "D74: group Planner Tasks derived from different Goals within the roots" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "D74 cross goal")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal), Mutation.Create(goal), Mutation.Create(task),
          Mutation.Create(task)), Nil, "Two goals"))
        ids = created.items.map(_.id)
        _ <- link(ledger, scope, ids.head, Relation.Produces, ids(2))
        _ <- link(ledger, scope, ids(1), Relation.Produces, ids(3))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(ids.take(2).toSet, DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- ZIO.succeed(println("D74 cross planner groups=" + selected.evidence.decision.choices.map(choice =>
          (choice.members.map(_.id.number), choice.reason))))
        choice = selected.evidence.decision.choices.find(_.members.size > 1).get
        _ <- assertIO(choice.members.map(_.id).toSet == Set(ids(2), ids(3)) && choice.reason == CohortReason.PlannerOrganisation)
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        explorer <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        worker <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Worker(WorkerMode.Implement)), ArtifactId(uuid)))
        _ <- assertIO((explorer.evidence.decision.choices ++ worker.evidence.decision.choices).forall(_.members.size == 1))
      } yield ()
    }

    "D74: group dependent Planner Tasks sharing a producer outside the roots" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "D74 unrooted producer")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal), Mutation.Create(task), Mutation.Create(task)),
          Nil, "Goal outside the roots"))
        ids = created.items.map(_.id)
        _ <- link(ledger, scope, ids.head, Relation.Produces, ids(1))
        _ <- link(ledger, scope, ids.head, Relation.Produces, ids(2))
        _ <- link(ledger, scope, ids(2), Relation.BlockedBy, ids(1))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(ids.tail.toSet, DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- ZIO.succeed(println("D74 unrooted producer planner groups=" + selected.evidence.decision.choices.map(choice =>
          (choice.members.map(_.id.number), choice.reason, choice.witness.map(_.number)))))
        choice = selected.evidence.decision.choices.find(_.members.size > 1)
        _ <- assertIO(choice.exists(value => value.members.map(_.id).toSet == Set(ids(1), ids(2)) &&
          value.reason == CohortReason.CommonProducer && value.witness.contains(ids.head)))
        _ <- ZIO.attemptBlocking(planner.verify(input, choice.get, selected.fingerprints(choice.get.id)))
        claim <- ledger.acquire(scope, ClaimId(uuid), choice.get.members.map(_.id).toSet, 300000)
        _ <- ledger.release(scope, claim.fence)
        explorer <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        worker <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Worker(WorkerMode.Implement)), ArtifactId(uuid)))
        _ <- assertIO((explorer.evidence.decision.choices ++ worker.evidence.decision.choices).forall(_.members.size == 1) &&
          !(explorer.evidence.decision.choices ++ worker.evidence.decision.choices).exists(_.members.exists(_.id == ids(2))))
      } yield ()
    }

    "D74: apply a reviewed Plan linking an organised Planner choice to its rooted milestone and blocker" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        f <- organised(ledger, chained = false)
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, GitCommit("a" * 40), Nil, new CohortProgress)
        input = request(Set(f.goal, f.milestone), DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        choice = selected.evidence.decision.choices.find(_.members.exists(_.id == f.milestone)).get
        _ <- assertIO(choice.members.map(_.id).toSet == (f.milestone :: f.tasks).toSet && choice.reason == CohortReason.PlannerOrganisation)
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        collector = f.scope.copy(actor = f.scope.actor.copy(subject = "host", role = Role.Collector))
        claim <- ledger.acquire(f.scope, ClaimId(uuid), choice.members.map(_.id).toSet, 300000)
        governing <- usage.assign(collector, Assignment(AssignmentId(uuid), f.scope.project, Set.empty, Attribution.Unattributed, None, None))
        parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, f.scope.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000))
        assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), f.scope.project, claim.members, Attribution.Shared, Some(uuid), None))
        attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), f.scope.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001))
        dispatch = DispatchRequest(choice.id, choice.work, Harness.Codex, choice.members, Nil, Nil, None, claim.fence, choice.limits)
        // The ledger changes each item at most once per batch, so one proposal links one Task to the milestone.
        mutations = List(ProposedMutation.Reference(f.tasks.head, Relation.PartOf, f.milestone, true),
          ProposedMutation.Reference(f.tasks(2), Relation.BlockedBy, f.tasks(1), true))
        report = ChildReport.Plan(choice.members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Organise under the milestone")),
          Some(LedgerProposal(mutations, "Link tasks to the milestone and order them")), Nil)
        prepared <- ZIO.attempt(ProposalPolicy.prepare(dispatch.work, dispatch.members, report))
        _ <- assertIO(prepared.exists(_.mutations.size == 2))
        result = ChildResult(attempt.id, dispatch, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
        stored <- artifacts.upload(collector, ArtifactUpload(f.scope.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json",
          Wire.encode(ChildResult_JsonCodec, result)))
        admitted <- admissions.admit(collector, HostAdmissionInput(f.scope.project, stored.id, f.scope.actor))
        _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
        // Plan review: a Reviewer(Plan) child reviews the admitted Planner result before the governor applies its proposal.
        reads = new EvidenceApi(api(ledger, f.scope, runtime), artifacts, admissions, f.scope, runtime)
        reviewWork = DispatchWork.Reviewer(ReviewerMode.Plan)
        reviewDispatch = DispatchRequest(RequestId(uuid), reviewWork, Harness.Codex, choice.members, Nil, Nil, Some(stored.id), claim.fence, choice.limits)
        _ <- assertIO(CohortAssessmentPolicy.reviewable(dispatch.work, dispatch.members, report))
        subject <- ZIO.attemptBlocking(new WorkflowAssembly(reads, f.scope.project, new WorkflowAssets).assemble(WorkflowRequest.Review(stored.id, ReviewerMode.Plan)))
        _ <- assertIO(subject.subject.contains(WorkflowSubject(stored.id, DispatchWork.Planner(), choice.members, None)))
        _ <- ZIO.attemptBlocking(new WorkflowExecution(reads, f.scope.project, f.scope.actor.session, Some(WorkflowRequest.Review(stored.id, ReviewerMode.Plan)))
          .authorize(DispatchCommand.Start(reviewDispatch)))
        reviewAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), f.scope.project, claim.members, Attribution.Shared, Some(uuid), None))
        reviewAttempt <- usage.start(collector, Attempt(AttemptId(uuid), reviewAssignment.id, Some(parent.id), f.scope.actor.session, Role.Reviewer, Harness.Codex, "fixture", "fixture", "fixture", 1002))
        review = ChildResult(reviewAttempt.id, reviewDispatch, GitCommit("a" * 40), None,
          ChildReport.Review(choice.members.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), Nil, RetainedEvidence(Nil, Nil))
        reviewed <- artifacts.upload(collector, ArtifactUpload(f.scope.project, ArtifactId(uuid), reviewAttempt.id, ArtifactKind.Result, "application/json",
          Wire.encode(ChildResult_JsonCodec, review)))
        reviewAdmitted <- admissions.admit(collector, HostAdmissionInput(f.scope.project, reviewed.id, f.scope.actor))
        _ <- assertIO(reviewAdmitted.decision == AdmissionDecision.Accepted())
        verdicts <- ZIO.attemptBlocking(new ArtifactReader(reads.call, f.scope.project).result(reviewed.id).value)
        _ <- assertIO(verdicts.request.previous.contains(stored.id) && (verdicts.report match {
          case ChildReport.Review(members, None) => members.map(_.item).toSet == claim.members && members.forall(_.verdict == ReviewVerdict.Accepted)
          case _ => false
        }))
        preview <- proposals.preview(f.scope, stored.id)
        _ <- assertIO(preview.role == Role.Planner && preview.operations.size == 2)
        _ <- proposals(f.scope, stored.id)
        linked <- ledger.get(f.scope, f.milestone)
        last <- ledger.get(f.scope, f.tasks(2))
        first <- ledger.get(f.scope, f.tasks.head)
        _ <- assertIO(linked.refs.contains(ItemRef(Relation.Contains, f.tasks.head)) && first.refs.contains(ItemRef(Relation.PartOf, f.milestone)) &&
          last.refs.contains(ItemRef(Relation.BlockedBy, f.tasks(1))))
      } yield ()
    }

    "D74: keep refusing a proposed link to a milestone outside the workflow roots" in { (ledger: LedgerService[IO]) =>
      for {
        runtime <- ZIO.runtime[Any]
        f <- organised(ledger, chained = true)
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, GitCommit("a" * 40), Nil, new CohortProgress)
        selected <- ZIO.attemptBlocking(planner.plan(request(Set(f.goal), DispatchWork.Planner()), ArtifactId(uuid)))
        choices = selected.evidence.decision.choices
        _ <- assertIO(!choices.exists(_.members.exists(_.id == f.milestone)))
        choice = choices.find(_.members.exists(_.id == f.tasks.head)).get
        // Dependent Tasks sharing the rooted Goal group by their common producer; the Planner skips the independence check.
        _ <- assertIO(choice.members.map(_.id).toSet == f.tasks.toSet && choice.reason == CohortReason.CommonProducer && choice.witness.contains(f.goal))
        report = ChildReport.Plan(choice.members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Organise")),
          Some(LedgerProposal(List(ProposedMutation.Reference(f.tasks.head, Relation.PartOf, f.milestone, true)), "Link to an unrooted milestone")), Nil)
        refused <- ZIO.attempt(ProposalPolicy.prepare(choice.work, choice.members, report)).either
        _ <- assertIO(refused.left.exists {
          case DomainFailure(Fault.Invalid(message)) => message == "Proposal endpoint is outside its eligible assignment"
          case _ => false
        })
      } yield ()
    }
  }
}

final class CohortSelectionDummy extends CohortSelectionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class CohortSelectionPostgres extends CohortSelectionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
