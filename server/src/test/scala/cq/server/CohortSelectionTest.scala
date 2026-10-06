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
    HostLimits(3000, 1000, 300, 2000, 262144))
  private final class MutableBase(var head: GitCommit, ancestors: Set[GitCommit]) extends ExecutionBase {
    override def fresh(): GitCommit = head
    override def expected(base: GitCommit, candidate: GitCommit): GitCommit = base
    override def ancestor(earlier: GitCommit, later: GitCommit): Boolean = earlier == later || (later == head && ancestors(earlier))
  }
  private def fixed(base: GitCommit): ExecutionBase = new MutableBase(base, Set.empty)
  private def unreadable(head: GitCommit): ExecutionBase = new ExecutionBase {
    override def fresh(): GitCommit = head
    override def expected(base: GitCommit, candidate: GitCommit): GitCommit = base
    override def ancestor(earlier: GitCommit, later: GitCommit): Boolean = throw new IllegalStateException("Repository is unreadable")
  }
  private def assessment(members: List[ItemRevision], compatibility: CohortCompatibility): CohortAssessment =
    CohortAssessment(compatibility, "Share implementation", "No dependency conflict", "Separate acceptance",
      members.map(member => CohortMemberAssessment(member, List(CohortCriterion(0, Set("acceptance"), "Inspect this task")))))
  // A Planner report that assesses `groups` and abstains on every other assigned member.
  private def assessing(members: List[ItemRevision], groups: List[CohortAssessment]): ChildReport.Plan = {
    val assessed = groups.flatMap(_.members.map(_.member.id)).toSet
    ChildReport.Plan(members.map(ref => if (assessed(ref.id)) PlanMember(ref.id, PlanDisposition.Assessed, "Assessed")
      else PlanMember(ref.id, PlanDisposition.Abstained, "Not assessed")), None, groups)
  }
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
        case Command.Read(ReadInput(_, ReadSelection.History(id, before, limit))) => ledger.history(scope, id, before, limit).map(Result.History.apply)
        case Command.ClaimWork(ClaimInput(_, ClaimAction.Renew(fence, millis))) => ledger.renew(scope, fence, millis).map(Result.Claimed.apply)
        case Command.Requirements(RequirementsInput(_, RequirementsAction.Read())) => ledger.requirements(scope).map(Result.Requirements.apply)
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
      read.fold(underlying.call(command))(effect => Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect.either).getOrThrowFiberFailure() } match {
        case Right(value) => value
        case Left(DomainFailure(fault)) => Result.Failed(fault)
        case Left(error) => throw error
      })
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
    val checks = List(ValidationCheck("acceptance", List("verify"), 5000, 4096, 1, 0))
    val base = GitCommit("a" * 40)
    for {
      _ <- ledger.initialize(scope, "Assessment selection")
      members <- MilestoneFixture.assigned(ledger, scope, List.fill(count)(task))
      claim <- ledger.acquire(scope, ClaimId(uuid), members.map(_.id).toSet, 300000)
      governing <- usage.assign(collector, Assignment(AssignmentId(uuid), scope.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, scope.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None))
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), scope.project, claim.members, Attribution.Shared, Some(uuid), None))
      attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), scope.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Plan, None))
      dispatch = DispatchRequest(RequestId(uuid), DispatchWork.Planner(), Harness.Codex, members, Nil, Nil, None, claim.fence,
        HostLimits(3000, 1000, 300, 2000, 262144))
      views <- ZIO.foreach(members)(ref => ledger.get(scope, ref.id))
      input = ChildExecutionInput(ChildInput(scope.project, dispatch, views, Nil, Nil, None, None), base, checks)
      _ <- artifacts.upload(collector, ArtifactUpload(scope.project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input,
        "application/json", Wire.encode(ChildExecutionInput_JsonCodec, input)))
      groups = members.grouped(2).map(members => CohortAssessment(compatibility, "Share implementation", "No dependency conflict", "Separate acceptance",
        members.map(member => CohortMemberAssessment(member, List(CohortCriterion(0, Set("acceptance"), "Inspect this task")))))).toList
      report = ChildReport.Plan(members.map(ref => PlanMember(ref.id, PlanDisposition.Assessed, "Assessed")), None, groups)
      result = ChildResult(attempt.id, dispatch, base, None, report, Nil, RetainedEvidence(Nil, Nil))
      stored <- artifacts.upload(collector, ArtifactUpload(scope.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
      admitted <- admissions.admit(collector, HostAdmissionInput(scope.project, stored.id, scope.actor))
      _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
    } yield Assessed(scope, members, stored.id, checks, base, collector, parent.id, claim.fence)
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
    artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]): IO[Throwable, Published] =
    published(f, work, report, previous, validation, if (work == DispatchWork.Planner()) None else Some(GitCommit("b" * 40)), ledger, usage, artifacts, admissions)
  private def published(f: Assessed, work: DispatchWork, report: ChildReport, previous: Option[Published], validation: List[ValidationEvidence], candidate: Option[GitCommit],
    ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]): IO[Throwable, Published] = for {
    assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.scope.project, f.members.map(_.id).toSet,
      if (f.members.size == 1) Attribution.Direct else Attribution.Shared, if (f.members.size == 1) None else Some(uuid), None))
    attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.parent), f.scope.actor.session, ChildContracts.role(work), Harness.Codex, "fixture", "fixture", "fixture", 1002, ChildContracts.phase(work), None))
    dispatch = DispatchRequest(RequestId(uuid), work, Harness.Codex, f.members, Nil, Nil, previous.map(_.id), f.fence,
      HostLimits(3000, 1000, 300, 2000, 262144))
    base = previous.flatMap(_.result.candidate).getOrElse(f.base)
    views <- ZIO.foreach(f.members)(ref => ledger.get(f.scope, ref.id))
    input = ChildExecutionInput(ChildInput(f.scope.project, dispatch, views, Nil, Nil, previous.map(_.result), None), base, f.checks)
    _ <- artifacts.upload(f.collector, ArtifactUpload(f.scope.project, NativeArtifacts.id(attempt.id, "input"), attempt.id, ArtifactKind.Input,
      "application/json", Wire.encode(ChildExecutionInput_JsonCodec, input)))
    result = ChildResult(attempt.id, dispatch, base, candidate, report, validation, RetainedEvidence(Nil, Nil))
    stored <- artifacts.upload(f.collector, ArtifactUpload(f.scope.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
    admitted <- admissions.admit(f.collector, HostAdmissionInput(f.scope.project, stored.id, f.scope.actor))
    _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
  } yield Published(result, stored.id)

  private val Implement: DispatchWork = DispatchWork.Worker(WorkerMode.Implement)
  private def alone(plan: CohortPlan): Set[(DispatchWork, Set[ItemId], CohortReason)] =
    plan.evidence.decision.choices.map(choice => (choice.work, choice.members.map(_.id).toSet, choice.reason)).toSet
  // M1: the assessment of `members` starts and no verdict follows (the Planner failed or abstained, or its result was not admitted). The
  // next selection must offer every member to the Worker alone, and each such choice must pass start verification.
  private def unassessed(planner: CohortPlanner, progress: CohortProgress, input: CohortRequest, members: Set[ItemId], witness: Option[ItemId]): IO[Throwable, CohortPlan] = for {
    required <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
    _ <- ZIO.attempt(assert(required.evidence.decision.choices.map(choice => (choice.work, choice.members.map(_.id).toSet, choice.reason, choice.witness)) ==
      List((DispatchWork.Planner(), members, CohortReason.AssessmentRequired, witness)), required.evidence.toString))
    group = required.fingerprints(required.evidence.decision.choices.head.id)
    _ <- ZIO.attempt(progress.started(group))
    running <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
    _ <- ZIO.attempt(assert(running.evidence.decision.choices.isEmpty && running.evidence.considered.exists(_.reason == CohortReason.Deferred),
      "Running assessment must defer its group: " + running.evidence.toString.take(4000)))
    _ <- ZIO.attempt(progress.finished(group, None))
    retry = input.copy(request = RequestId(uuid))
    again <- ZIO.attemptBlocking(planner.plan(retry, ArtifactId(uuid)))
    _ <- ZIO.attempt(assert(alone(again) == members.map(id => (Implement, Set(id), CohortReason.UnknownAssessment)) && again.evidence.decision.counts.excluded == 0 &&
      again.evidence.considered.exists(value => value.reason == CohortReason.Deferred && value.members.map(_.id).toSet == members && value.fingerprint.contains(group.group)),
      again.evidence.toString.take(4000)))
    _ <- ZIO.foreachDiscard(again.evidence.decision.choices)(choice => ZIO.attemptBlocking(planner.verify(retry, choice, again.fingerprints(choice.id))))
  } yield again

  // C2 fixture: four Tasks assessed as the Compatible pairs {0, 1} and {2, 3} at the fixture base, and a target that has since moved to a
  // descendant. `decide` selects implementation with the carried handles in both orders and requires one decision.
  private final case class Carried(fixture: Assessed, moved: Assessed, target: ExecutionBase,
    decide: (ExecutionBase, List[ArtifactId]) => IO[Throwable, Set[(DispatchWork, Set[ItemRevision])]])
  private def carried(ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
    runtime: Runtime[Any]): IO[Throwable, Carried] = assessed(ledger, usage, artifacts, admissions, 4, CohortCompatibility.Compatible).map { fixture =>
    val reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
    val head = GitCommit("b" * 40)
    def select(bases: ExecutionBase, handles: List[ArtifactId]) = ZIO.attemptBlocking(
      new CohortPlanner(reads, fixture.scope, bases, fixture.checks, new CohortProgress, new OperatorRequirements(""))
        .plan(request(fixture.members.map(_.id).toSet, Implement).copy(artifacts = handles), ArtifactId(uuid)).evidence.decision.choices
        .map(choice => (choice.work, choice.members.toSet)).toSet)
    Carried(fixture, fixture.copy(base = head), new MutableBase(head, Set(fixture.base)), (bases, handles) =>
      select(bases, handles).zip(select(bases, handles.reverse)).flatMap((forward, backward) =>
        ZIO.attempt(assert(forward == backward, (forward, backward).toString)).as(forward)))
  }

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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        input = request(Set(fixture.members.head.id), DispatchWork.Explorer(ExplorerMode.Investigate))
        first <- ZIO.attemptBlocking(planner.plan(input.copy(artifacts = List(observed.head)), ArtifactId(uuid)))
        _ <- ZIO.attempt(progress.started(first.fingerprints(first.evidence.decision.choices.head.id)))
        replay <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(observed(1))), ArtifactId(uuid)))
        changed <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(observed(2))), ArtifactId(uuid)))
        _ <- assertIO(replay.evidence.decision.choices.isEmpty && changed.evidence.decision.choices.size == 1)
        reviews <- ZIO.foreach(observed)(id => publish(fixture, DispatchWork.Reviewer(ReviewerMode.Candidate),
          ChildReport.Review(fixture.members.map(ref => ReviewMember(ref.id, ReviewVerdict.ChangesRequested, List("Correct the failure"))), None),
          Some(worker), List(ValidationEvidence(fixture.checks.head.name, ValidationState.Failed, id, Nil)), ledger, usage, artifacts, admissions))
        nestedProgress = new CohortProgress
        nested = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, nestedProgress, new OperatorRequirements(""))
        initial <- ZIO.attemptBlocking(nested.plan(input.copy(artifacts = List(reviews.head.id)), ArtifactId(uuid)))
        _ <- ZIO.attempt(nestedProgress.started(initial.fingerprints(initial.evidence.decision.choices.head.id)))
        repeated <- ZIO.attemptBlocking(nested.plan(input.copy(request = RequestId(uuid), artifacts = List(reviews(1).id)), ArtifactId(uuid)))
        revised <- ZIO.attemptBlocking(nested.plan(input.copy(request = RequestId(uuid), artifacts = List(reviews(2).id)), ArtifactId(uuid)))
        _ <- assertIO(repeated.evidence.decision.choices.isEmpty)
        _ <- assertIO(revised.evidence.decision.choices.size == 1)
      } yield ()
    }

    "D145: offer an input again after its attempt left no result, with the fault as context, and keep the deferral after an admitted result" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        member = fixture.members.head
        input = request(Set(member.id), DispatchWork.Planner())
        selecting = () => { val fresh = input.copy(request = RequestId(uuid)); ZIO.attemptBlocking(planner.plan(fresh, ArtifactId(uuid))).map(fresh -> _) }
        deferred = (plan: CohortPlan) => plan.evidence.decision.choices.isEmpty &&
          plan.evidence.considered.map(value => value.members -> value.reason) == List(List(member) -> CohortReason.Deferred)
        // The receipt of a child whose report violates a shape rule: Failed, no result, and the host's own advice to retry.
        failed = (fault: String) => DispatchStatus(RequestId(uuid), AttemptId(uuid), DispatchPhase.Failed, None, List(member.id), DispatchProjection.EmptyCounts,
          ChildNext.Retry, Some(fault), None, None, true, true, None, None)
        conclude = (execution: CohortExecutionFingerprint, fault: String) => for {
          found <- ZIO.attempt(CohortFailure.fault(failed(fault)).get)
          note <- artifacts.upload(fixture.collector, ArtifactUpload(fixture.scope.project, ArtifactId(uuid), fixture.parent, ArtifactKind.Evidence, "text/plain", found))
          repeated <- ZIO.attempt(progress.finished(execution, Some(CohortFailure(note.id, found))))
        } yield note.id -> repeated
        first <- selecting()
        _ <- assertIO(first._2.evidence.decision.choices.map(choice => choice.members -> choice.artifacts) == List(List(member) -> Nil))
        execution = first._2.fingerprints(first._2.evidence.decision.choices.head.id)
        _ <- ZIO.attempt(progress.started(execution))
        running <- selecting()
        _ <- assertIO(deferred(running._2))
        shape <- conclude(execution, "Invalid(The report violates a shape rule)")
        again <- selecting()
        retry = again._2.evidence.decision.choices
        _ <- ZIO.attempt(assert(retry.map(choice => choice.members -> choice.artifacts) == List(List(member) -> List(shape._1)) && !shape._2 &&
          again._2.fingerprints(retry.head.id) == execution, "An attempt without a result must not defer its input: " + again._2.evidence.considered))
        _ <- ZIO.attemptBlocking(planner.verify(again._1, retry.head, execution))
        _ <- ZIO.attempt(progress.started(execution))
        malformed <- conclude(execution, "expected ] or , got '}' (line 1, column 12)")
        third <- selecting()
        _ <- assertIO(third._2.evidence.decision.choices.map(_.artifacts) == List(List(malformed._1)) && !malformed._2)
        _ <- ZIO.attemptBlocking(planner.verify(third._1, third._2.evidence.decision.choices.head, execution))
        _ <- ZIO.attempt(progress.started(execution))
        same <- conclude(execution, "expected ] or , got '}' (line 1, column 12)")
        unchanged <- selecting()
        _ <- ZIO.attempt(assert(same._2 && deferred(unchanged._2), "The same fault twice in a row must stay deferred: " + unchanged._2.evidence))
        admitted = new CohortProgress
        other = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, admitted, new OperatorRequirements(""))
        offered <- ZIO.attemptBlocking(other.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        result = offered.fingerprints(offered.evidence.decision.choices.head.id)
        _ <- ZIO.attempt(admitted.started(result))
        _ <- assertIO(!admitted.finished(result, None))
        settled <- ZIO.attemptBlocking(other.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(deferred(settled))
      } yield ()
    }

    "I19: offer a candidate review again once a revalidation round amends its subject, with the round in its operative input" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        candidate = GitCommit("b" * 40)
        observations <- ZIO.foreach(List(("failed", 1), ("passed", 0))) { (name, code) =>
          val (out, uploads) = NativeArtifacts.binary(fixture.scope.project, fixture.parent, name, "text/plain", name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          val spec = WorkspaceSpec(fixture.scope.project, fixture.scope.actor.session, AttemptId(uuid), "/consumer", candidate)
          val job = JobRecord(spec, uuid.toString, JobTarget.Run, JobPhase.Settled, Some(JobExit(Some(code), None, StopReason.Exited, name.length.toLong, name.length.toLong, true, false)), None, 1, 1, 2)
          ZIO.foreachDiscard(uploads)(artifacts.upload(fixture.collector, _)) *>
            artifacts.upload(fixture.collector, ArtifactUpload(fixture.scope.project, ArtifactId(uuid), fixture.parent, ArtifactKind.Validation,
              "application/json", Wire.encode(ValidationObservation_JsonCodec, ValidationObservation(fixture.checks.head, candidate, job, out, out)))).map(_.id)
        }
        worker <- publish(fixture, DispatchWork.Worker(WorkerMode.Implement), ChildReport.Work(fixture.members.map(ref =>
          WorkMember(ref.id, WorkDisposition.CandidateReady, "Implemented", Nil))), None,
          List(ValidationEvidence(fixture.checks.head.name, ValidationState.Failed, observations.head, Nil)), ledger, usage, artifacts, admissions)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Reviewer(ReviewerMode.Candidate)).copy(previous = Some(worker.id))
        first <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- ZIO.attempt(assert(first.evidence.decision.choices.size == 1, first.evidence.toString))
        _ <- ZIO.attempt(progress.started(first.fingerprints(first.evidence.decision.choices.head.id)))
        unchanged <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- ZIO.attempt(assert(unchanged.evidence.decision.choices.isEmpty && unchanged.evidence.considered.exists(_.reason == CohortReason.Deferred), unchanged.evidence.toString))
        amendment = ValidationAmendment(worker.id, candidate, fixture.parent, 1, List(ValidationEvidence(fixture.checks.head.name, ValidationState.Passed, observations.last, Nil)))
        _ <- artifacts.upload(fixture.collector, ArtifactUpload(fixture.scope.project, IntegrationValidation.amendmentId(worker.id, 1), fixture.parent,
          ArtifactKind.Amendment, "application/json", Wire.encode(ValidationAmendment_JsonCodec, amendment)))
        amended <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- ZIO.attempt {
          println(s"Review after revalidation: ${amended.evidence.decision.choices.map(_.reason)} considered=${amended.evidence.considered.map(_.reason)}")
          assert(amended.evidence.decision.choices.size == 1 && amended.evidence.decision.choices.head.previous.contains(worker.id) &&
            amended.evidence.decision.choices.head.work == DispatchWork.Reviewer(ReviewerMode.Candidate), amended.evidence.toString)
          assert(amended.fingerprints(amended.evidence.decision.choices.head.id) != first.fingerprints(first.evidence.decision.choices.head.id))
        }
        _ <- ZIO.attemptBlocking(planner.verify(input, amended.evidence.decision.choices.head, amended.fingerprints(amended.evidence.decision.choices.head.id)))
      } yield ()
    }

    "offer every singleton from a large unknown prior plan before repeating its first eight" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 16, CohortCompatibility.Unknown)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(previous = Some(fixture.artifact))
        rounds <- ZIO.foreach(1 to 2) { _ => ZIO.attemptBlocking {
          val choices = planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)).evidence.decision.choices
          progress.offered(choices.flatMap(_.members.map(_.id)))
          choices
        }}
        _ <- assertIO(rounds.forall(_.size == 8) && rounds.flatten.flatMap(_.members.map(_.id)).distinct.size == 16)
      } yield ()
    }

    "D113: continue a mixed-review candidate as one whole group and require explicit fresh selection to abandon it" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement))
        initial <- ZIO.attemptBlocking(planner.plan(input.copy(artifacts = List(fixture.artifact)), ArtifactId(uuid)))
        _ <- assertIO(initial.evidence.decision.choices.map(_.members) == List(fixture.members))
        _ <- ZIO.attempt(progress.started(initial.fingerprints(initial.evidence.decision.choices.head.id)))
        worker <- publish(fixture, input.work, ChildReport.Work(fixture.members.map(ref =>
          WorkMember(ref.id, WorkDisposition.CandidateReady, "Candidate", Nil))), None, Nil, ledger, usage, artifacts, admissions)
        passed <- {
          val bytes = "passed".getBytes(java.nio.charset.StandardCharsets.UTF_8)
          val (out, uploads) = NativeArtifacts.binary(fixture.scope.project, worker.result.attempt, "passed", "text/plain", bytes)
          val spec = WorkspaceSpec(fixture.scope.project, fixture.scope.actor.session, AttemptId(uuid), "/consumer", GitCommit("b" * 40))
          val job = JobRecord(spec, uuid.toString, JobTarget.Run, JobPhase.Settled,
            Some(JobExit(Some(0), None, StopReason.Exited, bytes.length.toLong, bytes.length.toLong, true, false)), None, 1, 1, 2)
          ZIO.foreachDiscard(uploads)(artifacts.upload(fixture.collector, _)) *>
            artifacts.upload(fixture.collector, ArtifactUpload(fixture.scope.project, ArtifactId(uuid), worker.result.attempt, ArtifactKind.Validation,
              "application/json", Wire.encode(ValidationObservation_JsonCodec, ValidationObservation(fixture.checks.head, spec.base, job, out, out)))).map(_.id)
        }
        review = (verdicts: List[ReviewVerdict]) => publish(fixture, DispatchWork.Reviewer(ReviewerMode.Candidate),
          ChildReport.Review(fixture.members.zip(verdicts).map((ref, verdict) =>
            ReviewMember(ref.id, verdict, if (verdict == ReviewVerdict.Accepted) Nil else List("Fix this task"))), None),
          Some(worker), List(ValidationEvidence(fixture.checks.head.name, ValidationState.Passed, passed, Nil)), ledger, usage, artifacts, admissions)
        mixed <- review(List(ReviewVerdict.ChangesRequested, ReviewVerdict.Accepted))
        accepted <- review(List(ReviewVerdict.Accepted, ReviewVerdict.Accepted))
        blocked <- review(List(ReviewVerdict.ChangesRequested, ReviewVerdict.Blocked))
        continuation = input.copy(request = RequestId(uuid), previous = Some(mixed.id))
        exact <- ZIO.attemptBlocking(planner.plan(continuation, ArtifactId(uuid)))
        // The accepted member stays in the group: its own execution history does not defer the correction of the shared candidate.
        _ <- ZIO.attempt(assert(exact.evidence.decision.choices.map(choice => (choice.work, choice.members, choice.previous, choice.reason)) ==
          List((input.work, fixture.members, Some(mixed.id), CohortReason.ExactPrevious)) && exact.evidence.decision.counts.excluded == 0, exact.evidence.toString))
        choice = exact.evidence.decision.choices.head
        _ <- ZIO.attemptBlocking(planner.verify(continuation, choice, exact.fingerprints(choice.id)))
        // The same continuation named by the worker result, with the review as context, is the same operative input.
        viaWorker <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), previous = Some(worker.id), artifacts = List(mixed.id)), ArtifactId(uuid)))
        _ <- ZIO.attempt(assert(viaWorker.evidence.decision.choices.map(choice => (choice.members, choice.previous, choice.reason)) ==
          List((fixture.members, Some(worker.id), CohortReason.ExactPrevious)) &&
          viaWorker.fingerprints(viaWorker.evidence.decision.choices.head.id) == exact.fingerprints(choice.id), viaWorker.evidence.toString))
        _ <- ZIO.attempt(progress.started(exact.fingerprints(choice.id)))
        repeated <- ZIO.attemptBlocking(planner.plan(continuation.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(repeated.evidence.decision.choices.isEmpty && repeated.evidence.considered.map(value => (value.members, value.reason)) ==
          List((fixture.members, CohortReason.Deferred)))
        whole <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), previous = Some(accepted.id)), ArtifactId(uuid)))
        _ <- assertIO(whole.evidence.decision.choices.isEmpty && whole.evidence.decision.counts.excluded == 2 &&
          whole.evidence.considered.forall(_.reason == CohortReason.ReviewAccepted))
        refused <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), previous = Some(blocked.id)), ArtifactId(uuid)))
        _ <- assertIO(refused.evidence.decision.choices.isEmpty && refused.evidence.considered.map(value => (value.members, value.reason)).toSet ==
          Set((fixture.members.take(1), CohortReason.CandidateContinuity), (fixture.members.drop(1), CohortReason.ReviewBlocked)))
        fresh <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(mixed.id)), ArtifactId(uuid)))
        _ <- assertIO(fresh.evidence.decision.choices.map(choice => (choice.members, choice.previous, choice.reason)) ==
          List((fixture.members.take(1), None, CohortReason.FreshFromBase)))
        changed <- ZIO.attemptBlocking(new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks.map(_.copy(command = List("different-verifier"))),
          new CohortProgress, new OperatorRequirements("")).plan(input.copy(request = RequestId(uuid), artifacts = List(mixed.id)), ArtifactId(uuid)))
        _ <- assertIO(changed.evidence.decision.choices.flatMap(_.members.map(_.id)).toSet == fixture.members.map(_.id).toSet)
      } yield ()
    }

    "offer a started member again once the integration target advances and reject a start after it moves further" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        target = new MutableBase(fixture.base, Set.empty)
        planner = new CohortPlanner(reads, fixture.scope, target, fixture.checks, progress, new OperatorRequirements(""))
        input = request(Set(fixture.members.head.id), DispatchWork.Worker(WorkerMode.Implement))
        initial <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(initial.evidence.decision.choices.size == 1)
        _ <- ZIO.attempt(progress.started(initial.fingerprints(initial.evidence.decision.choices.head.id)))
        repeated <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(repeated.evidence.decision.choices.isEmpty && repeated.evidence.considered.exists(_.reason == CohortReason.Deferred))
        _ <- ZIO.succeed { target.head = GitCommit("b" * 40) }
        advanced <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(advanced.evidence.decision.choices.size == 1)
        choice = advanced.evidence.decision.choices.head
        _ <- ZIO.attemptBlocking(planner.verify(input.copy(request = advanced.evidence.request.request), choice, advanced.fingerprints(choice.id)))
        _ <- ZIO.succeed { target.head = GitCommit("c" * 40) }
        stale <- ZIO.attemptBlocking(planner.verify(input.copy(request = advanced.evidence.request.request), choice, advanced.fingerprints(choice.id))).either
        _ <- assertIO(stale.left.exists(_.getMessage.contains("Cohort operative input changed")))
      } yield ()
    }

    "D80: keep an exact previous candidate applicable after a derived record revises a member without changing its content" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        scope = owner
        collector = scope.copy(actor = scope.actor.copy(subject = "host", role = Role.Collector))
        _ <- ledger.initialize(scope, "D80 continuation")
        members <- MilestoneFixture.assigned(ledger, scope, List(task))
        producer = members.head
        claim <- ledger.acquire(scope, ClaimId(uuid), Set(producer.id), 300000)
        governing <- usage.assign(collector, Assignment(AssignmentId(uuid), scope.project, Set.empty, Attribution.Unattributed, None, None))
        parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, scope.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None))
        fixture = Assessed(scope, members, ArtifactId(uuid), Nil, GitCommit("a" * 40), collector, parent.id, claim.fence)
        worker <- publish(fixture, DispatchWork.Worker(WorkerMode.Implement), ChildReport.Work(fixture.members.map(ref =>
          WorkMember(ref.id, WorkDisposition.CandidateReady, "Candidate", Nil))), None, Nil, ledger, usage, artifacts, admissions)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, new CohortProgress, new OperatorRequirements(""))
        continuation = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(previous = Some(worker.id))
        review = continuation.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate))
        original <- ledger.get(fixture.scope, producer.id)
        research = ItemDraft("Reproduction evidence", "Observed the failure, then the pass", Set.empty, false,
          Content.Research(ResearchStatus.Open, "Does the candidate hold?", Nil, None, None), Nil)
        _ <- ledger.change(fixture.scope, ChangeRequest(RequestId(uuid), List(Mutation.Produce(producer.id, producer.revision, List(research), None)),
          List(fixture.fence), "Record evidence under the task"))
        revised <- ledger.get(fixture.scope, producer.id)
        _ <- assertIO(revised.item.revision == Revision(producer.revision.value + 1) && revised.item.draft == original.item.draft &&
          revised.refs.exists(_.relation == Relation.Produces))
        expected = List(ItemRevision(producer.id, revised.item.revision))
        continued <- ZIO.attemptBlocking(planner.plan(continuation, ArtifactId(uuid)))
        _ <- assertIO(continued.evidence.decision.choices.map(choice => (choice.members, choice.previous, choice.reason)) ==
          List((expected, Some(worker.id), CohortReason.ExactPrevious)))
        choice = continued.evidence.decision.choices.head
        _ <- ZIO.attemptBlocking(planner.verify(continuation, choice, continued.fingerprints(choice.id)))
        reviewed <- ZIO.attemptBlocking(planner.plan(review, ArtifactId(uuid)))
        _ <- assertIO(reviewed.evidence.decision.choices.map(choice => (choice.members, choice.previous, choice.work)) ==
          List((expected, Some(worker.id), review.work)))
        dispatch = DispatchRequest(choice.id, choice.work, Harness.Codex, choice.members, choice.guidance, choice.artifacts, choice.previous, fixture.fence, choice.limits)
        assembled <- ZIO.attemptBlocking(new InputAssembler(reads, fixture.scope, java.time.Clock.systemUTC(), "").assemble(dispatch))
        _ <- assertIO(assembled.previous.contains(worker.result) && assembled.members.map(view => ItemRevision(view.item.id, view.item.revision)) == expected)
        subject <- ZIO.attemptBlocking(new WorkflowAssembly(reads, fixture.scope.project, new WorkflowAssets).assemble(WorkflowRequest.Review(worker.id, ReviewerMode.Candidate)))
        _ <- assertIO(subject.subject.exists(_.members == fixture.members))
        _ <- ZIO.attemptBlocking(new WorkflowExecution(reads, fixture.scope.project, fixture.scope.actor.session, Some(WorkflowRequest.Review(worker.id, ReviewerMode.Candidate)))
          .authorize(DispatchCommand.Start(dispatch.copy(work = review.work))))
        _ <- ledger.change(fixture.scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(producer.id, revised.item.revision,
          revised.item.draft.copy(body = "Changed requirements"))), List(fixture.fence), "Change the task content"))
        changed <- ledger.get(fixture.scope, producer.id)
        stale <- ZIO.attemptBlocking(planner.plan(continuation.copy(request = RequestId(uuid)), ArtifactId(uuid))).either
        _ <- assertIO(stale.left.exists(_.getMessage.contains("stale")))
        staleAssembly <- ZIO.attemptBlocking(new InputAssembler(reads, fixture.scope, java.time.Clock.systemUTC(), "")
          .assemble(dispatch.copy(members = List(ItemRevision(producer.id, changed.item.revision))))).either
        _ <- assertIO(staleAssembly.left.exists(_.getMessage.contains("another assignment revision")))
      } yield ()
    }

    "use exact whole-group assessment evidence without requiring a common producer and keep it across a moved target" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(artifacts = List(fixture.artifact))
        select = (bases: ExecutionBase, checks: List[ValidationCheck]) => ZIO.attemptBlocking(
          new CohortPlanner(reads, fixture.scope, bases, checks, new CohortProgress, new OperatorRequirements("")).plan(input, ArtifactId(uuid)).evidence.decision.choices)
        head = GitCommit("b" * 40)
        compatible <- select(fixed(fixture.base), fixture.checks)
        // F6: the target moved by an integration, so the assessed base is an ancestor of the new head.
        advanced <- select(new MutableBase(head, Set(fixture.base)), fixture.checks)
        diverged <- select(fixed(head), fixture.checks)
        changedCheck <- select(new MutableBase(head, Set(fixture.base)), fixture.checks.map(_.copy(command = List("different-verifier"))))
        pairs = fixture.members.grouped(2).map(_.toSet).toSet
        _ <- ZIO.attempt(assert(List(compatible, advanced).forall(choices => choices.map(_.members.toSet).toSet == pairs &&
          choices.forall(choice => choice.work == input.work && choice.reason == CohortReason.CompatibleAssessment)), advanced.toString))
        _ <- assertIO(List(diverged, changedCheck).forall(choices => choices.map(_.members.size) == List(4, 2) &&
          choices.forall(choice => choice.work == DispatchWork.Planner() && choice.reason == CohortReason.AssessmentRequired)))
        // A member whose draft changed since the assessment needs a fresh one for its group; the untouched groups stay fused.
        view <- ledger.get(fixture.scope, fixture.members.head.id)
        revised <- ledger.change(fixture.scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(view.item.id, view.item.revision,
          view.item.draft.copy(body = "Changed requirements"))), List(fixture.fence), "Change the task content"))
        redrafted <- select(new MutableBase(head, Set(fixture.base)), fixture.checks)
        _ <- ZIO.attempt(assert(redrafted.map(choice => (choice.work, choice.members.toSet, choice.reason)).toSet ==
          fixture.members.drop(2).grouped(2).map(pair => (input.work, pair.toSet, CohortReason.CompatibleAssessment)).toSet +
            ((DispatchWork.Planner(), Set(revised.items.head, fixture.members(1)), CohortReason.AssessmentRequired)), redrafted.toString))
      } yield ()
    }

    "offer one assessment across producers and fuse on Compatible, split on Unknown" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        outcomes <- ZIO.foreach(List(CohortCompatibility.Compatible, CohortCompatibility.Unknown)) { compatibility => for {
          fixture <- assessed(ledger, usage, artifacts, admissions, 4, compatibility)
          views <- ZIO.foreach(fixture.members)(ref => ledger.get(fixture.scope, ref.id))
          _ <- assertIO(!views.exists(_.refs.exists(_.relation == Relation.DerivedFrom)))
          reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
          planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, new CohortProgress, new OperatorRequirements(""))
          input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement))
          required <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
          _ <- ZIO.attempt(assert(required.evidence.decision.choices.map(choice => (choice.work, choice.members.toSet, choice.reason, choice.witness)) ==
            List((DispatchWork.Planner(), fixture.members.toSet, CohortReason.AssessmentRequired, None)), required.evidence.toString))
          choice = required.evidence.decision.choices.head
          _ <- ZIO.attemptBlocking(planner.verify(input, choice, required.fingerprints(choice.id)))
          decided <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), artifacts = List(fixture.artifact)), ArtifactId(uuid)))
        } yield (fixture.members, decided.evidence.decision.choices) }
        (fused, compatible) = outcomes.head
        _ <- ZIO.attempt(assert(compatible.map(choice => (choice.work, choice.members.toSet, choice.reason)).toSet ==
          fused.grouped(2).map(pair => (DispatchWork.Worker(WorkerMode.Implement), pair.toSet, CohortReason.CompatibleAssessment)).toSet, compatible.toString))
        // An Unknown verdict leaves each member alone: a member whose group was split is not offered for another assessment.
        (separate, unknown) = outcomes.last
        _ <- ZIO.attempt(assert(unknown.map(choice => (choice.work, choice.members)) ==
          separate.map(member => (DispatchWork.Worker(WorkerMode.Implement), List(member))), unknown.toString))
        // The producer witness is kept only while every member shares it.
        scope = owner
        _ <- ledger.initialize(scope, "Witness")
        tasks <- MilestoneFixture.assigned(ledger, scope, List.fill(3)(task))
        producer <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal)), Nil, "Producer"))
        _ <- ZIO.foreachDiscard(tasks.take(2))(member => link(ledger, scope, producer.items.head.id, Relation.Produces, member.id))
        witnessed = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        selections <- ZIO.foreach(List(tasks.take(2), tasks))(members => ZIO.attemptBlocking(
          witnessed.plan(request(members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)), ArtifactId(uuid)).evidence.decision.choices))
        _ <- ZIO.attempt(assert(selections.map(_.map(choice => (choice.work, choice.members.map(_.id).toSet, choice.reason, choice.witness))) == List(
          List((DispatchWork.Planner(), tasks.take(2).map(_.id).toSet, CohortReason.AssessmentRequired, Some(producer.items.head.id))),
          List((DispatchWork.Planner(), tasks.map(_.id).toSet, CohortReason.AssessmentRequired, None))), selections.toString))
      } yield ()
    }

    "M1: offer unrelated Tasks alone once their assessment ended without a verdict, deferring only a member whose own Worker ran" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "Assessment without a verdict")
        tasks <- MilestoneFixture.assigned(ledger, scope, List.fill(4)(task))
        ids = tasks.map(_.id).toSet
        progress = new CohortProgress
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, progress, new OperatorRequirements(""))
        input = request(ids, Implement)
        again <- unassessed(planner, progress, input, ids, None)
        ran = again.evidence.decision.choices.head
        _ <- ZIO.attempt(progress.started(again.fingerprints(ran.id)))
        rest <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- ZIO.attempt(assert(alone(rest) == (ids -- ran.members.map(_.id)).map(id => (Implement, Set(id), CohortReason.UnknownAssessment)) &&
          rest.evidence.decision.counts.excluded == 1, rest.evidence.toString))
      } yield ()
    }

    "M1: offer the Tasks of one producer alone once their witnessed assessment ended without a verdict" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "Assessment of one producer without a verdict")
        pair <- MilestoneFixture.assigned(ledger, scope, List.fill(2)(task))
        producer <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal)), Nil, "Producer"))
        _ <- ZIO.foreachDiscard(pair)(member => link(ledger, scope, producer.items.head.id, Relation.Produces, member.id))
        progress = new CohortProgress
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, progress, new OperatorRequirements(""))
        _ <- unassessed(planner, progress, request(pair.map(_.id).toSet, Implement), pair.map(_.id).toSet, Some(producer.items.head.id))
      } yield ()
    }

    "M1: start the members of an exact previous result without a candidate afresh once their assessment ended without a verdict" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        failed <- published(fixture, Implement, ChildReport.Work(fixture.members.map(ref => WorkMember(ref.id, WorkDisposition.Failed, "Observed failure", Nil))),
          None, Nil, None, ledger, usage, artifacts, admissions)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        progress = new CohortProgress
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, progress, new OperatorRequirements(""))
        again <- unassessed(planner, progress, request(fixture.members.map(_.id).toSet, Implement).copy(previous = Some(failed.id)), fixture.members.map(_.id).toSet, None)
        // The members no longer continue that result: it is carried as context.
        _ <- ZIO.attempt(assert(again.evidence.decision.choices.forall(choice => choice.previous.isEmpty && choice.artifacts == List(failed.id)), again.evidence.toString))
      } yield ()
    }

    "C1: name the assessment and its base when their ancestry cannot be inspected" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 2, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(artifacts = List(fixture.artifact))
        head = GitCommit("b" * 40)
        select = (bases: ExecutionBase) => ZIO.attemptBlocking(
          new CohortPlanner(reads, fixture.scope, bases, fixture.checks, new CohortProgress, new OperatorRequirements("")).plan(input, ArtifactId(uuid)))
        // A base the repository does not hold is no ancestor (CandidateWorkspace.ancestor): the assessment does not apply and a new one is asked for.
        absent <- select(fixed(head))
        _ <- ZIO.attempt(assert(absent.evidence.decision.choices.map(choice => (choice.work, choice.reason)) ==
          List((DispatchWork.Planner(), CohortReason.AssessmentRequired)), absent.evidence.toString))
        failure <- select(unreadable(head)).either
        _ <- ZIO.attempt(assert(failure.left.exists(error => error.getMessage.contains(fixture.artifact.value.toString) &&
          error.getMessage.contains(fixture.base.value) && error.getMessage.contains(head.value) && error.getMessage.contains("Repository is unreadable")), failure.toString))
      } yield ()
    }

    "C2: let the assessment at the newest base, and at one base the later result, decide between conflicting carried assessments" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        f <- carried(ledger, usage, artifacts, admissions, runtime)
        members = f.fixture.members
        split = Set[(DispatchWork, Set[ItemRevision])]((Implement, Set(members.head)), (Implement, Set(members(1))), (Implement, members.drop(2).toSet))
        // The first pair is Compatible at the earlier base and Unknown at the later one.
        conflicting <- publish(f.moved, DispatchWork.Planner(), assessing(members, List(assessment(members.take(2), CohortCompatibility.Unknown))),
          None, Nil, ledger, usage, artifacts, admissions)
        newest <- f.decide(f.target, List(f.fixture.artifact, conflicting.id))
        _ <- ZIO.attempt(assert(newest == split, newest.toString))
        // At one base the later result decides.
        tied <- publish(f.fixture, DispatchWork.Planner(), assessing(members, List(assessment(members.take(2), CohortCompatibility.Incompatible))),
          None, Nil, ledger, usage, artifacts, admissions)
        received <- ZIO.foreach(List(f.fixture.artifact, tied.id))(id => artifacts.metadata(f.fixture.scope, id).map(_.receivedAt))
        later <- f.decide(fixed(f.fixture.base), List(f.fixture.artifact, tied.id))
        _ <- ZIO.attempt(assert(received.head <= received.last && (received.head == received.last || later == split), (received, later).toString))
      } yield ()
    }

    "C2: let the assessment at the newest base decide between overlapping carried groups and ask again for the members it leaves" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        f <- carried(ledger, usage, artifacts, admissions, runtime)
        members = f.fixture.members
        // The later base groups Task 0 with Task 2, so both earlier pairs give way and their other members need a new assessment.
        overlapping <- publish(f.moved, DispatchWork.Planner(), assessing(members, List(assessment(List(members.head, members(2)), CohortCompatibility.Compatible))),
          None, Nil, ledger, usage, artifacts, admissions)
        regrouped <- f.decide(f.target, List(f.fixture.artifact, overlapping.id))
        _ <- ZIO.attempt(assert(regrouped == Set[(DispatchWork, Set[ItemRevision])]((Implement, Set(members.head, members(2))),
          (DispatchWork.Planner(), Set(members(1), members(3)))), regrouped.toString))
      } yield ()
    }

    "D118: discard stale overlapping assessments before they displace an exact carried group" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        f <- carried(ledger, usage, artifacts, admissions, runtime)
        members = f.fixture.members
        overlapping <- publish(f.moved, DispatchWork.Planner(), assessing(members,
          List(assessment(List(members.head, members(2)), CohortCompatibility.Compatible))), None, Nil, ledger, usage, artifacts, admissions)
        current <- ledger.get(f.fixture.scope, members.head.id)
        _ <- ledger.change(f.fixture.scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(current.item.id, current.item.revision,
          current.item.draft.copy(body = "Revised assessment input"))), List(f.fixture.fence), "Revise one assessment member"))
        selected <- f.decide(f.target, List(f.fixture.artifact, overlapping.id))
        _ <- ZIO.attempt(assert(selected.contains((Implement, members.drop(2).toSet)), selected.toString))
      } yield ()
    }

    "partition a larger prior plan through its assessments while preserving the original artifact" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        runtime <- ZIO.runtime[Any]
        fixture <- assessed(ledger, usage, artifacts, admissions, 6, CohortCompatibility.Compatible)
        reads = new EvidenceApi(api(ledger, fixture.scope, runtime), artifacts, admissions, fixture.scope, runtime)
        planner = new CohortPlanner(reads, fixture.scope, fixed(fixture.base), fixture.checks, new CohortProgress, new OperatorRequirements(""))
        input = request(fixture.members.map(_.id).toSet, DispatchWork.Worker(WorkerMode.Implement)).copy(previous = Some(fixture.artifact))
        result <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        choices = result.evidence.decision.choices
        _ <- assertIO(choices.map(_.members.toSet).toSet == fixture.members.grouped(2).map(_.toSet).toSet &&
          choices.forall(choice => choice.work == input.work && choice.previous.isEmpty && choice.artifacts == List(fixture.artifact)))
      } yield ()
    }

    "exclude a task without a milestone from implementation and offer it once assigned" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      def offered(plan: CohortPlan): Set[ItemId] = plan.evidence.decision.choices.flatMap(_.members.map(_.id)).toSet
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "Milestone admission")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(milestone), Mutation.Create(task), Mutation.Create(task)), Nil, "Milestone and tasks"))
        target = created.items.head.id
        assigned = created.items(1).id
        unassigned = created.items(2).id
        _ <- link(ledger, scope, assigned, Relation.PartOf, target)
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        input = request(Set(assigned, unassigned), DispatchWork.Worker(WorkerMode.Implement))
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(offered(selected) == Set(assigned) && selected.evidence.decision.counts.excluded == 1 &&
          selected.evidence.considered.filter(_.reason == CohortReason.NoMilestone).map(_.members.map(_.id)) == List(List(unassigned)))
        others <- ZIO.foreach(List[DispatchWork](DispatchWork.Explorer(ExplorerMode.Investigate), DispatchWork.Planner(), DispatchWork.Worker(WorkerMode.Probe)))(work =>
          ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), work = work), ArtifactId(uuid))))
        _ <- assertIO(others.forall(plan => offered(plan) == Set(assigned, unassigned) && plan.evidence.decision.counts.excluded == 0))
        _ <- link(ledger, scope, unassigned, Relation.PartOf, target)
        organised <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(offered(organised) == Set(assigned, unassigned) && !organised.evidence.considered.exists(_.reason == CohortReason.NoMilestone))
      } yield ()
    }

    "exclude a task whose milestone is closed from implementation and refuse its start" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      def offered(plan: CohortPlan): Set[ItemId] = plan.evidence.decision.choices.flatMap(_.members.map(_.id)).toSet
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "Closed milestone admission")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(milestone), Mutation.Create(milestone), Mutation.Create(task), Mutation.Create(task)),
          Nil, "Milestones and tasks"))
        kept = created.items.head.id
        closing = created.items(1).id
        current = created.items(2).id
        stranded = created.items(3).id
        _ <- link(ledger, scope, current, Relation.PartOf, kept)
        _ <- link(ledger, scope, stranded, Relation.PartOf, closing)
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        input = request(Set(current, stranded), DispatchWork.Worker(WorkerMode.Implement))
        together <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- assertIO(offered(together) == Set(current, stranded))
        // Both Tasks together are offered for assessment first; the stranded Task alone yields the Worker choice this case verifies.
        alone = input.copy(request = RequestId(uuid), roots = Set(stranded))
        both <- ZIO.attemptBlocking(planner.plan(alone, ArtifactId(uuid)))
        choice = both.evidence.decision.choices.head
        _ <- assertIO(choice.work == input.work && choice.members.map(_.id) == List(stranded))
        // A milestone closes only over terminal Tasks, so the Task is finished, the milestone completed and the Task then reopened.
        replace = (id: ItemId, draft: ItemDraft, reason: String) => ledger.get(scope, id).flatMap(view =>
          ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Replace(id, view.item.revision, draft)), Nil, reason)))
        _ <- replace(stranded, task.copy(content = Content.Task(TaskStatus.Done, List("Independent acceptance"), None, Nil)), "Finish the task")
        _ <- replace(closing, milestone.copy(content = Content.Milestone(MilestoneStatus.Complete, "Deliver the tasks")), "Complete the milestone")
        reopened <- replace(stranded, task, "Reopen the task")
        // The reopened Task has the selected content at a later revision. The choice is pinned to that revision to reach the milestone check:
        // the earlier one fails verification as no longer current.
        stale <- ZIO.attemptBlocking(intercept[IllegalArgumentException](planner.verify(alone, choice, both.fingerprints(choice.id))))
        _ <- assertIO(stale.getMessage.contains("Selected cohort is no longer selected, ready or current"))
        refused <- ZIO.attemptBlocking(intercept[DomainFailure](planner.verify(alone, choice.copy(members = reopened.items), both.fingerprints(choice.id))))
        _ <- assertIO(refused.fault == Fault.Invalid(
          s"Work refused: T${stranded.number}'s milestone M${closing.number} is Complete. Reopen M${closing.number} or reassign T${stranded.number} to an Open milestone before work starts"))
        selected <- ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid)), ArtifactId(uuid)))
        _ <- assertIO(offered(selected) == Set(current) && selected.evidence.decision.counts.excluded == 1 &&
          selected.evidence.considered.filter(_.reason == CohortReason.ClosedMilestone).map(_.members.map(_.id)) == List(List(stranded)))
        others <- ZIO.foreach(List[DispatchWork](DispatchWork.Explorer(ExplorerMode.Investigate), DispatchWork.Planner(), DispatchWork.Worker(WorkerMode.Probe)))(work =>
          ZIO.attemptBlocking(planner.plan(input.copy(request = RequestId(uuid), work = work), ArtifactId(uuid))))
        _ <- assertIO(others.forall(plan => offered(plan) == Set(current, stranded) && plan.evidence.decision.counts.excluded == 0))
      } yield ()
    }

    "never offer a settled decision or memory while a proposed decision stays selectable" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      def decision(status: DecisionStatus): ItemDraft = task.copy(title = s"Decision $status", content = Content.Decision(status, "Choice", "Rationale", Nil))
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "settled records")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(goal), Mutation.Create(decision(DecisionStatus.Adopted)),
          Mutation.Create(decision(DecisionStatus.Proposed)), Mutation.Create(task.copy(title = "Memory", content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil)))),
          Nil, "Goal with reference records"))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard(ids.tail)(id => link(ledger, scope, ids.head, Relation.Produces, id))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        explorer <- ZIO.attemptBlocking(planner.plan(request(Set(ids.head), DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        offered = explorer.evidence.decision.choices.flatMap(_.members.map(_.id))
        _ <- assertIO(offered.toSet == Set(ids.head, ids(2)) && explorer.evidence.decision.counts.notReady == 2)
        planning <- ZIO.attemptBlocking(planner.plan(request(Set(ids.head), DispatchWork.Planner()), ArtifactId(uuid)))
        planned = planning.evidence.decision.choices.flatMap(_.members.map(_.id))
        _ <- assertIO(planned.toSet == Set(ids.head, ids(2)) && planning.evidence.decision.counts.notReady == 2)
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        result <- ZIO.attemptBlocking(planner.plan(request(ids.take(3).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        choices = result.evidence.decision.choices
        _ <- assertIO(choices.map(_.members.map(_.id).toSet) == List(ids.take(2).toSet, Set(ids(2))) &&
          choices.head.witness.contains(ids(4)) && result.evidence.decision.counts.selected == 3)
      } yield ()
    }

    "Q32: measure the project's standing requirements in the input budget of an offered group and of its start" in { (ledger: LedgerService[IO]) =>
      val scope = owner
      val operator = scope.copy(actor = scope.actor.copy(subject = "operator", role = Role.Human))
      val large = task.copy(body = "x" * 60000)
      val standing = "😀" * LedgerPolicy.MaxRequirementsCodePoints
      for {
        runtime <- ZIO.runtime[Any]
        _ <- ledger.initialize(scope, "standing budget")
        created <- ledger.change(scope, ChangeRequest(RequestId(uuid), List.fill(3)(Mutation.Create(large)) :+ Mutation.Create(task), Nil, "Candidates and producer"))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard(List(0, 1, 2))(index => link(ledger, scope, ids(3), Relation.Produces, ids(index)))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements("Session request"))
        selection = request(ids.take(3).toSet, DispatchWork.Planner())
        whole <- ZIO.attemptBlocking(planner.plan(selection, ArtifactId(uuid)))
        choice = whole.evidence.decision.choices.head
        _ <- assertIO(whole.evidence.decision.choices.map(_.members.size) == List(3))
        // An Explorer receives no requirements, so its group is not measured with them.
        _ <- ledger.replaceRequirements(operator, Revision(0), standing)
        explorers <- ZIO.attemptBlocking(new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements("Session request"))
          .plan(request(ids.take(3).toSet, DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        _ <- assertIO(explorers.evidence.decision.choices.map(_.members.size) == List(3))
        narrowed <- ZIO.attemptBlocking(new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements("Session request"))
          .plan(request(ids.take(3).toSet, DispatchWork.Planner()), ArtifactId(uuid)))
        _ <- assertIO(narrowed.evidence.decision.choices.map(_.members.size) == List(2, 1))
        // The group offered before the text grew no longer fits when it is started.
        stale <- ZIO.attemptBlocking(planner.verify(selection, choice, whole.fingerprints(choice.id))).either
        _ <- assertIO(stale.left.exists(_.getMessage.contains("Cohort operative input changed; select again")))
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, progress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, progress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, progress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
          Mutation.Create(task), Mutation.Create(milestone)), Nil, "Two goals"))
        ids = created.items.map(_.id)
        _ <- link(ledger, scope, ids.head, Relation.Produces, ids(2))
        _ <- link(ledger, scope, ids(1), Relation.Produces, ids(3))
        _ <- ZIO.foreachDiscard(List(ids(2), ids(3)))(id => link(ledger, scope, id, Relation.PartOf, ids(4)))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        input = request(ids.take(2).toSet, DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        _ <- ZIO.succeed(println("D74 cross planner groups=" + selected.evidence.decision.choices.map(choice =>
          (choice.members.map(_.id.number), choice.reason))))
        choice = selected.evidence.decision.choices.find(_.members.size > 1).get
        _ <- assertIO(choice.members.map(_.id).toSet == Set(ids(2), ids(3)) && choice.reason == CohortReason.PlannerOrganisation)
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        explorer <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Explorer(ExplorerMode.Investigate)), ArtifactId(uuid)))
        worker <- ZIO.attemptBlocking(planner.plan(input.copy(work = DispatchWork.Worker(WorkerMode.Implement)), ArtifactId(uuid)))
        _ <- assertIO(explorer.evidence.decision.choices.forall(_.members.size == 1))
        // Implementation offers the same Tasks to a Planner for assessment, without a producer witness.
        _ <- assertIO(worker.evidence.decision.choices.map(choice => (choice.work, choice.members.map(_.id).toSet, choice.reason, choice.witness)) ==
          List((DispatchWork.Planner(), Set(ids(2), ids(3)), CohortReason.AssessmentRequired, None)))
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
        organising <- ledger.change(scope, ChangeRequest(RequestId(uuid), List(Mutation.Create(milestone)), Nil, "Milestone outside the roots"))
        _ <- ZIO.foreachDiscard(ids.tail)(id => link(ledger, scope, id, Relation.PartOf, organising.items.head.id))
        _ <- link(ledger, scope, ids(2), Relation.BlockedBy, ids(1))
        planner = new CohortPlanner(api(ledger, scope, runtime), scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
        input = request(Set(f.goal, f.milestone), DispatchWork.Planner())
        selected <- ZIO.attemptBlocking(planner.plan(input, ArtifactId(uuid)))
        choice = selected.evidence.decision.choices.find(_.members.exists(_.id == f.milestone)).get
        _ <- assertIO(choice.members.map(_.id).toSet == (f.milestone :: f.tasks).toSet && choice.reason == CohortReason.PlannerOrganisation)
        _ <- ZIO.attemptBlocking(planner.verify(input, choice, selected.fingerprints(choice.id)))
        collector = f.scope.copy(actor = f.scope.actor.copy(subject = "host", role = Role.Collector))
        claim <- ledger.acquire(f.scope, ClaimId(uuid), choice.members.map(_.id).toSet, 300000)
        governing <- usage.assign(collector, Assignment(AssignmentId(uuid), f.scope.project, Set.empty, Attribution.Unattributed, None, None))
        parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, f.scope.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None))
        assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), f.scope.project, claim.members, Attribution.Shared, Some(uuid), None))
        attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), f.scope.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Plan, None))
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
        reviewAttempt <- usage.start(collector, Attempt(AttemptId(uuid), reviewAssignment.id, Some(parent.id), f.scope.actor.session, Role.Reviewer, Harness.Codex, "fixture", "fixture", "fixture", 1002, UsagePhase.Review, None))
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
        planner = new CohortPlanner(api(ledger, f.scope, runtime), f.scope, fixed(GitCommit("a" * 40)), Nil, new CohortProgress, new OperatorRequirements(""))
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
