package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.io.IOException
import java.nio.file.Files
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, Runtime, Task, Unsafe, ZIO}

abstract class IntegrationContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[IntegrationService[IO]]))
  private def uuid: UUID = UUID.randomUUID()
  private val recordedResult = "Worker evidence: failing reproduction, then passing checks"
  private def task: ItemDraft = ItemDraft("Integration task", "Preserved narrative", Set("consumer"), false,
    Content.Task(TaskStatus.Ready, List("Exact reviewed behavior"), Some(recordedResult), Nil), Nil)
  private def integrated(intent: IntegrationIntent): String = s"Integrated ${intent.candidate.value} into ${intent.target}"
  /** An integration target that never advanced: every candidate is expected at its worker's recorded base. */
  private val recordedBases: ExecutionBase = new ExecutionBase {
    override def fresh(): GitCommit = throw new IllegalStateException("Fresh work is not started by these cases")
    override def expected(base: GitCommit, candidate: GitCommit): GitCommit = base
  }
  private def prepare(preparation: IntegrationPreparation, ticket: IntegrationTicket): IntegrationIntent = preparation.freeze(preparation.review(ticket), None)
  private final class ServiceApi(scope: Scope, ledger: LedgerService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], runtime: Runtime[Any]) extends ServerApi {
    override def call(command: Command): Result = {
      val effect: IO[Throwable, Result] = command match {
        case Command.Read(ReadInput(_, ReadSelection.ItemDetail(id))) => ledger.get(scope, id).map(Result.Detail.apply)
        case Command.Read(ReadInput(_, ReadSelection.History(id, before, limit))) => ledger.history(scope, id, before, limit).map(Result.History.apply)
        case Command.Read(ReadInput(_, ReadSelection.ArtifactInfo(id))) => artifacts.metadata(scope, id).map(Result.ArtifactInfo.apply)
        case Command.Read(ReadInput(_, ReadSelection.ArtifactText(id, offset, limit))) => artifacts.page(scope, id, offset, limit).map(Result.ArtifactText.apply)
        case Command.Read(ReadInput(_, ReadSelection.Admission(attempt))) => admissions.get(scope, attempt).map(Result.Admission.apply)
        case Command.ClaimWork(ClaimInput(_, ClaimAction.Renew(fence, millis))) => ledger.renew(scope, fence, millis).map(Result.Claimed.apply)
        case _ => ZIO.fail(new IllegalStateException("Unexpected integration preparation command"))
      }
      Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect.either).getOrThrowFiberFailure() } match {
        case Right(value) => value
        case Left(DomainFailure(fault)) => Result.Failed(fault)
        case Left(error) => throw error
      }
    }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Preparation cannot publish usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Preparation cannot publish artifacts")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Preparation cannot grant authority")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Preparation cannot admit a result")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Preparation cannot integrate")
  }
  private final case class Fixture(owner: Scope, collector: Scope, governor: AttemptId, claim: Claim, items: List[Item], worker: ChildResult,
    reviewer: ChildResult, intent: IntegrationIntent) {
    def fresh: IntegrationIntent = {
      val id = IntegrationId(uuid)
      intent.copy(id = id, change = IntegrationPolicy.completion(id, intent.repository, intent.target, intent.candidate,
        intent.rebase, intent.worker, intent.reviewer, IntegrationValidation.citations(worker, reviewer), intent.fence, items))
    }
  }
  private def publish(scope: Scope, value: ChildResult, artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]): IO[Throwable, ArtifactId] = for {
    artifact <- artifacts.upload(scope, ArtifactUpload(scope.project, ArtifactId(uuid), value.attempt, ArtifactKind.Result,
      "application/json", Wire.encode(ChildResult_JsonCodec, value)))
    admitted <- admissions.admit(scope, HostAdmissionInput(scope.project, artifact.id, scope.actor.copy(subject = "integration governor", role = Role.Governor)))
    _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
  } yield artifact.id

  private def begin(ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]): IO[Throwable, Fixture] = {
    val owner = Scope(ProjectId(uuid), Actor("integration governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "host collector", role = Role.Collector))
    val candidate = GitCommit("b" * 40)
    val check = ValidationCheck("consumer", List("consumer-check"), 1000, 65536)
    for {
      _ <- ledger.initialize(owner, "Integration")
      members <- MilestoneFixture.assigned(ledger, owner, List.fill(2)(task))
      items <- ZIO.foreach(members)(ref => ledger.get(owner, ref.id).map(_.item))
      claim <- ledger.acquire(owner, ClaimId(uuid), members.map(_.id).toSet, 300000)
      parentAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(uuid), parentAssignment.id, None, owner.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern))
      workerAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, claim.members, Attribution.Shared, Some(uuid), None))
      workerAttempt <- usage.start(collector, parent.copy(id = AttemptId(uuid), assignment = workerAssignment.id, parent = Some(parent.id), role = Role.Worker))
      job = JobRecord(WorkspaceSpec(owner.project, owner.actor.session, AttemptId(uuid), "/consumer", candidate), "fixture", JobTarget.Run,
        JobPhase.Settled, Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1000, 1001)
      validation <- artifacts.upload(collector, ArtifactUpload(owner.project, ArtifactId(uuid), workerAttempt.id, ArtifactKind.Validation, "application/json",
        Wire.encode(ValidationObservation_JsonCodec, ValidationObservation(check, candidate, job, ArtifactId(uuid), ArtifactId(uuid)))))
      request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, members, Nil, Nil, None,
        claim.fence, HostLimits(3000, 1000, 300, 2000, 262144))
      worker = ChildResult(workerAttempt.id, request, GitCommit("a" * 40), Some(candidate),
        ChildReport.Work(members.map(ref => WorkMember(ref.id, WorkDisposition.CandidateReady, "Ready", Nil))), List(ValidationEvidence(check.name, ValidationState.Passed, validation.id)), RetainedEvidence(Nil, Nil))
      workerArtifact <- publish(collector, worker, artifacts, admissions)
      reviewAssignment <- usage.assign(collector, workerAssignment.copy(id = AssignmentId(uuid), cohort = Some(uuid)))
      reviewAttempt <- usage.start(collector, workerAttempt.copy(id = AttemptId(uuid), assignment = reviewAssignment.id, role = Role.Reviewer))
      reviewer = ChildResult(reviewAttempt.id, request.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate), previous = Some(workerArtifact)),
        candidate, Some(candidate), ChildReport.Review(members.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), worker.validation, RetainedEvidence(Nil, Nil))
      reviewArtifact <- publish(collector, reviewer, artifacts, admissions)
      id = IntegrationId(uuid)
      change = IntegrationPolicy.completion(id, "/consumer", "refs/heads/integration", candidate, None, workerArtifact, reviewArtifact, List(validation.id), claim.fence, items)
      intent = IntegrationIntent(id, owner.project, owner.actor, "/consumer", "refs/heads/integration", worker.base, candidate,
        workerArtifact, reviewArtifact, List(check), claim.fence, members, change, None)
    } yield Fixture(owner, collector, parent.id, claim, items, worker, reviewer, intent)
  }
  private def reject[A](operation: IO[Throwable, A], accepts: Fault => Boolean): IO[Throwable, Unit] = operation.either.flatMap { value =>
    assertIO(value match { case Left(DomainFailure(fault)) => accepts(fault); case _ => false }).unit
  }
  private final case class ReviewValidation(author: AttemptId, observation: ValidationObservation, evidence: List[ValidationEvidence])
  private def checkedReview(f: Fixture, usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
    alter: ReviewValidation => ReviewValidation): Task[Fixture] = for {
    assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.owner.project, f.claim.members, Attribution.Shared, Some(uuid), None))
    attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.governor), f.owner.actor.session, Role.Reviewer,
      Harness.Pi, "fixture", "fixture", "fixture", 1000, UsagePhase.Review))
    artifact = ArtifactId(uuid)
    job = JobRecord(WorkspaceSpec(f.owner.project, f.owner.actor.session, AttemptId(uuid), f.intent.repository, f.intent.candidate),
      "reviewer-check", JobTarget.Run, JobPhase.Settled, Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1000, 1001)
    value = alter(ReviewValidation(attempt.id, ValidationObservation(f.intent.checks.head, f.intent.candidate, job, ArtifactId(uuid), ArtifactId(uuid)),
      List(ValidationEvidence(f.intent.checks.head.name, ValidationState.Passed, artifact))))
    _ <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, artifact, value.author, ArtifactKind.Validation, "application/json",
      Wire.encode(ValidationObservation_JsonCodec, value.observation)))
    review = f.reviewer.copy(attempt = attempt.id, validation = value.evidence,
      request = f.reviewer.request.copy(request = RequestId(uuid), harness = Harness.Pi))
    handle <- publish(f.collector, review, artifacts, admissions)
    id = IntegrationId(uuid)
    change = IntegrationPolicy.completion(id, f.intent.repository, f.intent.target, f.intent.candidate, None, f.intent.worker, handle,
      IntegrationValidation.citations(f.worker, review), f.intent.fence, f.items)
  } yield f.copy(reviewer = review, intent = f.intent.copy(id = id, reviewer = handle, change = change))
  private val rebasedRule = Fault.Invalid("Rebased integration requires passing host checks on the exact rebased commit")
  private val advancedHead = GitCommit("c" * 40)
  private val rebasedCommit = GitCommit("e" * 40)
  /** A host rebase of the reviewed candidate onto an advanced head, checked on the rebased commit under the governor attempt. */
  private def rebased(f: Fixture, artifacts: ArtifactService[IO], alter: ReviewValidation => ReviewValidation): Task[Fixture] = {
    val artifact = ArtifactId(uuid)
    val job = JobRecord(WorkspaceSpec(f.owner.project, f.owner.actor.session, AttemptId(uuid), f.intent.repository, rebasedCommit),
      "host-rebase-check", JobTarget.Run, JobPhase.Settled, Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1000, 1001)
    val value = alter(ReviewValidation(f.governor, ValidationObservation(f.intent.checks.head, rebasedCommit, job, ArtifactId(uuid), ArtifactId(uuid)),
      List(ValidationEvidence(f.intent.checks.head.name, ValidationState.Passed, artifact))))
    val id = IntegrationId(uuid)
    val rebase = IntegrationRebase(f.intent.candidate, value.author, value.evidence)
    val change = IntegrationPolicy.completion(id, f.intent.repository, f.intent.target, rebasedCommit, Some(rebase), f.intent.worker, f.intent.reviewer,
      IntegrationValidation.citations(f.worker, f.reviewer), f.intent.fence, f.items)
    artifacts.upload(f.collector, ArtifactUpload(f.owner.project, artifact, f.governor, ArtifactKind.Validation, "application/json",
      Wire.encode(ValidationObservation_JsonCodec, value.observation)))
      .as(f.copy(intent = f.intent.copy(id = id, expected = advancedHead, candidate = rebasedCommit, change = change, rebase = Some(rebase))))
  }
  private def pending(id: IntegrationId)(fault: Fault): Boolean = fault == Fault.IntegrationPending(id)
  private def fixed(repository: LedgerRepository[IO], millis: Long): LedgerService[IO] = FixedLedger.at(repository, millis)

  "Integration reservations (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "retain distinct worker and independently executed reviewer checks in exact completion" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => for {
        original <- begin(ledger, usage, artifacts, admissions)
        f <- checkedReview(original, usage, artifacts, admissions, identity)
        reserved <- integrations.reserve(f.collector, f.intent)
        _ <- assertIO(reserved.resolution == IntegrationResolution.Pending() && f.worker.validation != f.reviewer.validation)
        _ <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))
        completed <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id).map(_.item))
        _ <- assertIO(completed.forall { item =>
          val validation = item.draft.content.asInstanceOf[Content.Task].validation.last
          validation.citations.collect { case Citation.Artifact(id) => id }.toSet == (List(f.intent.worker, f.intent.reviewer) ++
            IntegrationValidation.citations(f.worker, f.reviewer)).toSet
        })
        // D80: integration appends its record to the worker's recorded result instead of replacing it.
        _ <- assertIO(completed.forall(item => item.draft.content.asInstanceOf[Content.Task].result.contains(recordedResult + "\n\n" + integrated(f.intent))))
      } yield ()
    }

    "D80: integrate a candidate reviewed after a derived record revised a member without changing its content" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => {
        val research = ItemDraft("Reproduction evidence", "Observed the failure, then the pass", Set.empty, false,
          Content.Research(ResearchStatus.Open, "Does the candidate hold?", Nil, None, None), Nil)
        def reviewCurrent(f: Fixture): IO[Throwable, (Fixture, List[Item])] = for {
          current <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id).map(_.item))
          members = current.map(item => ItemRevision(item.id, item.revision))
          assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.owner.project, f.claim.members, Attribution.Shared, Some(uuid), None))
          attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.governor), f.owner.actor.session, Role.Reviewer,
            Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Review))
          review = f.reviewer.copy(attempt = attempt.id, request = f.reviewer.request.copy(request = RequestId(uuid), members = members))
          handle <- publish(f.collector, review, artifacts, admissions)
          id = IntegrationId(uuid)
          change = IntegrationPolicy.completion(id, f.intent.repository, f.intent.target, f.intent.candidate, None, f.intent.worker, handle,
            IntegrationValidation.citations(f.worker, review), f.claim.fence, current)
        } yield (f.copy(reviewer = review, intent = f.intent.copy(id = id, reviewer = handle, members = members, change = change)), current)
        for {
          runtime <- ZIO.runtime[Any]
          original <- begin(ledger, usage, artifacts, admissions)
          producer = original.items.head
          _ <- ledger.change(original.owner, ChangeRequest(RequestId(uuid), List(Mutation.Produce(producer.id, producer.revision, List(research), None)),
            List(original.claim.fence), "Record evidence under the task"))
          reviewed <- reviewCurrent(original)
          (f, current) = reviewed
          _ <- assertIO(f.intent.members != original.intent.members && current.map(_.draft) == original.items.map(_.draft))
          prepared <- ZIO.attemptBlocking(prepare(new IntegrationPreparation(new ServiceApi(f.owner, ledger, artifacts, admissions, runtime), f.owner,
            f.intent.repository, f.intent.target, f.intent.checks, Clock.systemUTC(), recordedBases), IntegrationTicket(f.intent.id, f.intent.reviewer)))
          _ <- assertIO(prepared == f.intent)
          reserved <- integrations.reserve(f.collector, f.intent)
          _ <- assertIO(reserved.resolution == IntegrationResolution.Pending())
          recorded <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))
          _ <- assertIO(recorded.resolution.isInstanceOf[IntegrationResolution.Recorded])
          completed <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id))
          _ <- assertIO(completed.forall(view => view.item.draft.content match {
            case value: Content.Task => value.status == TaskStatus.Done && value.result.contains(recordedResult + "\n\n" + integrated(f.intent))
            case _ => false
          }) && completed.head.refs.exists(_.relation == Relation.Produces))
          changed <- begin(ledger, usage, artifacts, admissions)
          edited = changed.items.head
          _ <- ledger.change(changed.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(edited.id, edited.revision,
            edited.draft.copy(body = "Changed requirements"))), List(changed.claim.fence), "Change the task content"))
          stale <- reviewCurrent(changed)
          _ <- reject(integrations.reserve(stale._1.collector, stale._1.intent), _.isInstanceOf[Fault.Invalid])
          refused <- ZIO.attemptBlocking(prepare(new IntegrationPreparation(new ServiceApi(stale._1.owner, ledger, artifacts, admissions, runtime), stale._1.owner,
            stale._1.intent.repository, stale._1.intent.target, stale._1.intent.checks, Clock.systemUTC(), recordedBases), IntegrationTicket(stale._1.intent.id, stale._1.intent.reviewer))).either
          _ <- assertIO(refused.isLeft)
        } yield ()
      }
    }

    "reject reviewer checks with foreign authors, changed candidates or declarations, incomplete inventory and unsuccessful jobs" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => for {
        original <- begin(ledger, usage, artifacts, admissions)
        changes = List[ReviewValidation => ReviewValidation](
          value => value.copy(author = original.worker.attempt),
          value => value.copy(observation = value.observation.copy(candidate = GitCommit("c" * 40))),
          value => value.copy(observation = value.observation.copy(check = value.observation.check.copy(command = List("different-command")))),
          value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(
            workspace = value.observation.job.workspace.copy(base = GitCommit("c" * 40))))),
          value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(
            workspace = value.observation.job.workspace.copy(owner = SessionId(uuid))))),
          value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(
            exit = value.observation.job.exit.map(_.copy(code = Some(1)))))),
          value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(phase = JobPhase.Uncertain))),
          value => value.copy(evidence = Nil),
          value => value.copy(evidence = value.evidence.map(_.copy(check = "another-check"))),
          value => value.copy(evidence = value.evidence.map(_.copy(state = ValidationState.Failed))),
          value => value.copy(evidence = value.evidence.map(_.copy(state = ValidationState.Unknown))))
        _ <- ZIO.foreachDiscard(changes) { alter => for {
          f <- checkedReview(original, usage, artifacts, admissions, alter)
          _ <- reject(integrations.reserve(f.collector, f.intent), _.isInstanceOf[Fault.Invalid])
          _ <- reject(integrations.get(f.owner, f.intent.id), _.isInstanceOf[Fault.Missing])
        } yield () }
        unchanged <- ledger.claimPreview(original.owner, original.claim.members)
        _ <- assertIO(unchanged.integrations.isEmpty)
        _ <- integrations.reserve(original.collector, original.intent)
      } yield ()
    }

    "I18: reserve a host-rebased intent on the reviewed candidate and cite the landed commit, the reviewed commit and the host's checks" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => for {
        original <- begin(ledger, usage, artifacts, admissions)
        f <- rebased(original, artifacts, identity)
        rebase = f.intent.rebase.get
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(rebase = Some(rebase.copy(reviewed = GitCommit("d" * 40))))), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(rebase = Some(rebase.copy(reviewed = rebasedCommit)))), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(change = original.fresh.change.copy(request = f.intent.change.request))), _.isInstanceOf[Fault.Invalid])
        reserved <- integrations.reserve(f.collector, f.intent)
        stored <- integrations.get(f.owner, f.intent.id)
        _ <- ZIO.attempt(assert(reserved.resolution == IntegrationResolution.Pending() && stored == reserved && stored.intent == f.intent))
        recorded <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(rebasedCommit))
        _ <- assertIO(recorded.resolution.isInstanceOf[IntegrationResolution.Recorded])
        completed <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id).map(_.item.draft.content.asInstanceOf[Content.Task]))
        _ <- ZIO.attempt(completed.foreach { task =>
          println(s"Rebased completion: result=${task.result.map(_.linesIterator.toList.last)} citations=${task.validation.last.citations}")
          assert(task.status == TaskStatus.Done && task.validation.last.citations == List(
            Citation.Commit(f.intent.repository, rebasedCommit.value), Citation.Commit(f.intent.repository, original.intent.candidate.value),
            Citation.Artifact(f.intent.worker), Citation.Artifact(f.intent.reviewer)) ++
            (IntegrationValidation.citations(f.worker, f.reviewer) ++ rebase.validation.map(_.artifact)).map(Citation.Artifact.apply))
          assert(task.result.contains(recordedResult + "\n\n" + integrated(f.intent) + s" (host rebase of reviewed candidate ${original.intent.candidate.value})"))
        })
      } yield ()
    }

    "I18: reject a host-rebased intent whose host checks have a foreign author, another commit, failed, missing or mismatched evidence, or no checks" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => for {
        original <- begin(ledger, usage, artifacts, admissions)
        reviewed = original.intent.candidate
        changes = List[(String, ReviewValidation => ReviewValidation)](
          "foreign author" -> (value => value.copy(author = original.worker.attempt)),
          "observation of the reviewed commit" -> (value => value.copy(observation = value.observation.copy(candidate = reviewed))),
          "job based on the reviewed commit" -> (value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(
            workspace = value.observation.job.workspace.copy(base = reviewed))))),
          "unsuccessful job" -> (value => value.copy(observation = value.observation.copy(job = value.observation.job.copy(
            exit = value.observation.job.exit.map(_.copy(code = Some(1))))))),
          "failed evidence" -> (value => value.copy(evidence = value.evidence.map(_.copy(state = ValidationState.Failed)))),
          "missing evidence" -> (value => value.copy(evidence = Nil)),
          "evidence of another check" -> (value => value.copy(evidence = value.evidence.map(_.copy(check = "another-check")))))
        _ <- ZIO.foreachDiscard(changes) { case (name, alter) => for {
          f <- rebased(original, artifacts, alter)
          result <- integrations.reserve(f.collector, f.intent).either
          _ <- ZIO.attempt(assert(result == Left(DomainFailure(rebasedRule)), s"$name: $result"))
          _ <- reject(integrations.get(f.owner, f.intent.id), _.isInstanceOf[Fault.Missing])
        } yield () }
        // Without configured checks nothing verifies the rebased commit: the reviewed candidate integrates, a host rebase of it does not.
        assignment <- usage.assign(original.collector, Assignment(AssignmentId(uuid), original.owner.project, original.claim.members, Attribution.Shared, Some(uuid), None))
        workerAttempt <- usage.start(original.collector, Attempt(AttemptId(uuid), assignment.id, Some(original.governor), original.owner.actor.session, Role.Worker,
          Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Work))
        worker = original.worker.copy(attempt = workerAttempt.id, validation = Nil, request = original.worker.request.copy(request = RequestId(uuid)))
        workerArtifact <- publish(original.collector, worker, artifacts, admissions)
        reviewAttempt <- usage.start(original.collector, Attempt(AttemptId(uuid), assignment.id, Some(original.governor), original.owner.actor.session, Role.Reviewer,
          Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Review))
        reviewer = original.reviewer.copy(attempt = reviewAttempt.id, validation = Nil,
          request = original.reviewer.request.copy(request = RequestId(uuid), previous = Some(workerArtifact)))
        reviewArtifact <- publish(original.collector, reviewer, artifacts, admissions)
        plain = IntegrationId(uuid)
        unchecked = original.intent.copy(id = plain, worker = workerArtifact, reviewer = reviewArtifact, checks = Nil,
          change = IntegrationPolicy.completion(plain, original.intent.repository, original.intent.target, reviewed, None, workerArtifact, reviewArtifact,
            Nil, original.intent.fence, original.items))
        id = IntegrationId(uuid)
        rebase = IntegrationRebase(reviewed, original.governor, Nil)
        result <- integrations.reserve(original.collector, unchecked.copy(id = id, expected = advancedHead, candidate = rebasedCommit, rebase = Some(rebase),
          change = IntegrationPolicy.completion(id, original.intent.repository, original.intent.target, rebasedCommit, Some(rebase), workerArtifact, reviewArtifact,
            Nil, original.intent.fence, original.items))).either
        _ <- ZIO.attempt(assert(result == Left(DomainFailure(rebasedRule)), s"no configured checks: $result"))
        preview <- ledger.claimPreview(original.owner, original.claim.members)
        _ <- assertIO(preview.integrations.isEmpty)
        reserved <- integrations.reserve(original.collector, unchecked)
        _ <- assertIO(reserved.resolution == IntegrationResolution.Pending())
      } yield ()
    }

    "reserve an intent whose expected target is the observed head rather than the worker's base" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        head = GitCommit("c" * 40)
        intent = f.fresh.copy(expected = head)
        _ <- assertIO(f.worker.base != head)
        reserved <- integrations.reserve(f.collector, intent)
        _ <- assertIO(reserved.resolution == IntegrationResolution.Pending() && reserved.intent.expected == head)
      } yield ()
    }

    "prepare a continuation candidate against the observed current head instead of its worker base" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        runtime <- ZIO.runtime[Any]
        continuation = GitCommit("d" * 40)
        assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.owner.project, f.claim.members, Attribution.Shared, Some(uuid), None))
        workerAttempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.governor), f.owner.actor.session, Role.Worker,
          Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Work))
        worker = f.worker.copy(attempt = workerAttempt.id, base = f.intent.candidate, candidate = Some(continuation), validation = Nil,
          request = f.worker.request.copy(request = RequestId(uuid), previous = Some(f.intent.reviewer)))
        workerArtifact <- publish(f.collector, worker, artifacts, admissions)
        reviewAttempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.governor), f.owner.actor.session, Role.Reviewer,
          Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Review))
        reviewer = f.reviewer.copy(attempt = reviewAttempt.id, base = continuation, candidate = Some(continuation), validation = Nil,
          request = f.reviewer.request.copy(request = RequestId(uuid), previous = Some(workerArtifact)))
        reviewArtifact <- publish(f.collector, reviewer, artifacts, admissions)
        _ <- ZIO.attemptBlocking {
          val clock = Clock.systemUTC()
          val auth = new Authorization(AccessConfig("preparation-contract-root-token", "http://localhost"), clock)
          val root = auth.authenticate("preparation-contract-root-token", Some(f.owner.actor.session.value.toString))
          val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth)
          val authority = auth.authenticate(auth.grant(root, GrantRequest(f.owner.project, f.owner.actor, clock.millis() + 300000)).value, None)
          val governor = new ServerApi {
            override def call(value: Command): Result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(application.execute(authority, value)).getOrThrowFiberFailure() }
            override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Preparation cannot publish artifacts")
            override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Preparation cannot publish usage")
            override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Preparation cannot grant authority")
            override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Preparation cannot admit a result")
            override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Preparation cannot integrate")
          }
          val head = f.intent.expected
          var observed = List.empty[(GitCommit, GitCommit)]
          val bases = new ExecutionBase {
            override def fresh(): GitCommit = head
            override def expected(base: GitCommit, candidate: GitCommit): GitCommit = { observed :+= (base, candidate); head }
          }
          val preparation = new IntegrationPreparation(governor, f.owner, f.intent.repository, f.intent.target, Nil, clock, bases)
          val intent = prepare(preparation, IntegrationTicket(IntegrationId(uuid), reviewArtifact))
          println(s"Continuation preparation: worker base=${worker.base.value.take(7)} head=${head.value.take(7)} expected=${intent.expected.value.take(7)} observed=$observed")
          assert(observed == List((worker.base, continuation)))
          assert(intent.expected == head && intent.candidate == continuation && intent.worker == workerArtifact && intent.reviewer == reviewArtifact)
        }
      } yield ()
    }

    "prevent create-only proposal application while any assigned member has a pending integration" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.owner.project, f.claim.members, Attribution.Shared, Some(uuid), None))
        attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.governor), f.owner.actor.session, Role.Planner,
          Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Plan))
        report = ChildReport.Plan(f.intent.members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Follow-up")),
          Some(LedgerProposal(List(ProposedMutation.Create(task.copy(content = Content.Research(ResearchStatus.Open, "What remains?", Nil, None, None)))),
            "Create after integration settles")), Nil)
        result = f.worker.copy(attempt = attempt.id, candidate = None, report = report, validation = Nil,
          request = f.worker.request.copy(request = RequestId(uuid), work = DispatchWork.Planner()))
        handle <- publish(f.collector, result, artifacts, admissions)
        _ <- integrations.reserve(f.collector, f.intent)
        _ <- reject(proposals(f.owner, handle), pending(f.intent.id))
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(before.events.size == 3)
        _ <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.NotApplied("Target unchanged; executor settled"))
        ack <- proposals(f.owner, handle)
        _ <- assertIO(ack.items.map(_.id) == List(ItemId(f.owner.project, Ledger.Researches, 1)))
      } yield ()
    }

    "freeze combination publication across lost acknowledgement and admit only the exact resolver under current authority" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        runtime <- ZIO.runtime[Any]
        _ <- integrations.reserve(f.collector, f.intent)
        _ <- ZIO.attemptBlocking {
          val clock = Clock.systemUTC()
          val auth = new Authorization(AccessConfig("combination-contract-root-token", "http://localhost"), clock)
          val root = auth.authenticate("combination-contract-root-token", Some(f.owner.actor.session.value.toString))
          val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth)
          def execute[A](effect: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect).getOrThrowFiberFailure() }
          final class Api(scope: Scope, lose: Boolean) extends ServerApi {
            private val authority = auth.authenticate(auth.grant(root, GrantRequest(scope.project, scope.actor, clock.millis() + 300000)).value, None)
            private var lost = !lose
            override def call(value: Command): Result = execute(application.execute(authority, value))
            override def artifact(value: ArtifactUpload): ArtifactMetadata = {
              val result = execute(application.upload(authority, value))
              if (!lost) { lost = true; throw new IOException("Lost combination publication acknowledgement") }
              result
            }
            override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Combination cannot publish usage")
            override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Combination cannot grant authority")
            override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Combination cannot admit a result")
            override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Combination cannot integrate")
          }
          val governor = new Api(f.owner, false)
          val collector = new Api(f.collector, false)
          val prepare = new CombinationPreparation(governor, f.owner, f.governor, f.intent.repository, f.intent.target, clock)
          val ticket = CombinationTicket(RequestId(uuid), f.intent.id, f.claim.fence)
          var observations = 0
          def observe(candidate: GitCommit): GitCommit = { assert(candidate == f.intent.candidate); observations += 1; GitCommit("c" * 40) }
          intercept[IllegalArgumentException](prepare.prepare(ticket, observe))
          assert(observations == 0)
          execute(integrations.observe(f.collector, f.intent.id, IntegrationObservation.NotApplied("Target advanced; old executor settled")))
          val directory = Files.createTempDirectory("cq-combination-").resolve("combinations")
          val publication = new CombinationPublication(directory, f.owner, f.governor, f.intent.repository, f.intent.target)
          publication.retain(ticket)
          val plan = publication.freeze(ticket)(prepare.prepare(ticket, observe))
          assert(observations == 1 && plan.observedTarget == GitCommit("c" * 40))
          intercept[IOException](publication.publish(ticket.id, new Api(f.collector, true)))
          val originalMetadata = execute(artifacts.metadata(f.owner, CombinationPlans.artifact(plan)))
          assert(publication.freeze(ticket)(throw new AssertionError("Replay must not observe a new target")) == plan)
          assert(publication.publish(ticket.id, collector) == CombinationPlans.preview(plan))
          assert(execute(artifacts.metadata(f.owner, CombinationPlans.artifact(plan))) == originalMetadata)
          val reopened = new CombinationPublication(directory, f.owner, f.governor, f.intent.repository, f.intent.target)
          assert(reopened.inventory == List(ticket.id) && reopened.publish(ticket.id, collector) == CombinationPlans.preview(plan))
          intercept[IllegalArgumentException](reopened.retain(ticket.copy(source = IntegrationId(uuid))))
          val request = f.worker.request.copy(request = RequestId(uuid), work = DispatchWork.Worker(WorkerMode.ResolveConflict),
            previous = Some(f.intent.worker), artifacts = List(CombinationPlans.artifact(plan)))
          val assembler = new InputAssembler(governor, f.owner, clock, "")
          val input = assembler.assemble(request)
          assert(prepare.consume(input).contains(plan))
          List(DispatchWork.Worker(WorkerMode.Implement), DispatchWork.Worker(WorkerMode.Probe), DispatchWork.Reviewer(ReviewerMode.Candidate)).foreach { work =>
            intercept[IllegalArgumentException](prepare.consume(input.copy(request = request.copy(work = work))))
          }
          intercept[IllegalArgumentException](prepare.consume(input.copy(request = request.copy(previous = Some(f.intent.reviewer)))))
          intercept[IllegalArgumentException](prepare.consume(input.copy(request = request.copy(members = request.members.take(1)))))
          intercept[IllegalArgumentException](prepare.consume(input.copy(artifacts = input.artifacts ++ input.artifacts)))
          val stored = input.artifacts.head
          intercept[IllegalArgumentException](prepare.consume(input.copy(artifacts = List(stored.copy(metadata = stored.metadata.copy(
            actor = stored.metadata.actor.copy(role = Role.Human)))))))
          val anotherSession = f.owner.copy(actor = f.owner.actor.copy(session = SessionId(uuid)))
          val foreign = new CombinationPreparation(new Api(anotherSession, false), anotherSession, AttemptId(uuid), f.intent.repository, f.intent.target, clock)
          intercept[IllegalArgumentException](foreign.consume(input))
          intercept[IllegalArgumentException](foreign.prepare(ticket, observe))
          assert(prepare.consume(assembler.assemble(request.copy(artifacts = Nil))).isEmpty)
          execute(ledger.release(f.owner, f.claim.fence))
          assert(reopened.publish(ticket.id, collector) == CombinationPlans.preview(plan))
          intercept[DomainFailure](prepare.consume(input))
          val newer = execute(ledger.acquire(f.owner, ClaimId(uuid), f.claim.members, 300000))
          intercept[DomainFailure](prepare.consume(input))
          intercept[IllegalArgumentException](prepare.consume(input.copy(request = request.copy(fence = newer.fence))))
          val newTicket = ticket.copy(id = RequestId(uuid), fence = newer.fence)
          publication.retain(newTicket)
          val fresh = publication.freeze(newTicket)(prepare.prepare(newTicket, _ => GitCommit("d" * 40)))
          publication.publish(newTicket.id, collector)
          val current = assembler.assemble(request.copy(fence = newer.fence, artifacts = List(CombinationPlans.artifact(fresh))))
          assert(prepare.consume(current).contains(fresh) && fresh.observedTarget != plan.observedTarget && fresh.request.fence == newer.fence)
          val item = f.items.head
          execute(ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(item.id, item.revision,
            item.draft.copy(body = "Changed requirements"))), List(newer.fence), "Revise")))
          intercept[IllegalArgumentException](prepare.prepare(newTicket.copy(id = RequestId(uuid)), _ => throw new AssertionError("Changed revisions must precede target observation")))
          intercept[IllegalArgumentException](assembler.assemble(current.request))
        }
      } yield ()
    }

    "reserve the domain request identity even when an ordinary request touches only unrelated new items" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        _ <- integrations.reserve(f.collector, f.intent)
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        ordinary = ChangeRequest(f.intent.change.request, List(Mutation.Create(task.copy(title = "Unrelated identity reuse"))), Nil, "Ordinary creation")
        result <- ledger.change(f.owner, ordinary).either
        _ <- ZIO.succeed(println(s"Ordinary change using reserved integration request identity: $result"))
        _ <- assertIO(result == Left(DomainFailure(Fault.IntegrationPending(f.intent.id))))
        unchanged <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(unchanged == before)
        independent <- ledger.change(f.owner, ordinary.copy(request = RequestId(uuid)))
        _ <- assertIO(independent.items.map(_.id.number) == List(3L))
        recorded <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))
        _ <- assertIO(recorded.resolution.isInstanceOf[IntegrationResolution.Recorded])
      } yield ()
    }

    "freeze exact task membership and exclude ordinary edits, release, takeover and termination while preserving unrelated work" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        records <- ZIO.collectAllPar(List.fill(2)(integrations.reserve(f.collector, f.intent)))
        _ <- assertIO(records.distinct.size == 1 && records.head.resolution == IntegrationResolution.Pending())
        after <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(after == before)
        _ <- reject(ledger.change(f.owner, f.intent.change), pending(f.intent.id))
        member = f.items.head
        _ <- reject(ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(member.id, member.revision,
          member.draft.copy(title = "Forbidden"))), List(f.claim.fence), "Same owner edit")), pending(f.intent.id))
        _ <- reject(ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Restore(member.id, member.revision, member.revision, Nil)), List(f.claim.fence), "Restore")), pending(f.intent.id))
        _ <- reject(ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Produce(member.id, member.revision, List(task), None)), List(f.claim.fence), "Produce")), pending(f.intent.id))
        _ <- reject(ledger.release(f.owner, f.claim.fence), pending(f.intent.id))
        human = f.owner.copy(actor = f.owner.actor.copy(role = Role.Human))
        preview <- ledger.claimPreview(human, Set(member.id))
        _ <- assertIO(preview.integrations == List(IntegrationPolicy.hold(f.intent)))
        _ <- reject(ledger.takeover(human, ClaimId(uuid), human.actor, Set(member.id), 300000, preview.snapshot), pending(f.intent.id))
        termination <- ledger.termination(human, f.claim.members, TerminationIntent.Cancel)
        _ <- assertIO(!termination.plan.canApply && termination.plan.integrations == preview.integrations)
        _ <- reject(ledger.change(human, ChangeRequest(RequestId(uuid), List(Mutation.Terminate(f.claim.members, TerminationIntent.Cancel, termination.snapshot)),
          List(f.claim.fence), "Terminate")), pending(f.intent.id))
        separate <- ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(task.copy(title = "Independent"))), Nil, "Independent work"))
        other = f.owner.copy(actor = f.owner.actor.copy(session = SessionId(uuid)))
        _ <- ledger.acquire(other, ClaimId(uuid), separate.items.map(_.id).toSet, 300000)
        _ <- reject(ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Reference(member.id, member.revision, Relation.RelatesTo,
          separate.items.head.id, separate.items.head.revision, true)), List(f.claim.fence), "Reserved endpoint")), pending(f.intent.id))
      } yield ()
    }

    "record one exact completion after claim expiry without reviving the lease or duplicating history" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], artifactRepository: ArtifactRepository[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], mutations: LedgerMutation) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        first <- integrations.reserve(f.collector, f.intent)
        expired = fixed(repository, f.claim.expiresAt + 1)
        _ <- reject(expired.acquire(f.owner, ClaimId(uuid), f.claim.members, 300000), pending(f.intent.id))
        _ <- reject(expired.change(f.owner, f.intent.change), pending(f.intent.id))
        _ <- reject(expired.renew(f.owner, f.claim.fence, 300000), _.isInstanceOf[Fault.StaleFence])
        late = new IntegrationServiceImpl[IO](repository, artifactRepository, mutations, Clock.fixed(Instant.ofEpochMilli(f.claim.expiresAt + 1), ZoneOffset.UTC))
        replay <- late.reserve(f.collector, f.intent)
        _ <- assertIO(replay == first)
        _ <- reject(late.reserve(f.collector, f.fresh), _.isInstanceOf[Fault.StaleFence])
        recorded <- ZIO.collectAllPar(List.fill(2)(late.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))))
        _ <- assertIO(recorded.distinct.size == 1 && recorded.head.resolution.isInstanceOf[IntegrationResolution.Recorded])
        acknowledgement = recorded.head.resolution.asInstanceOf[IntegrationResolution.Recorded].acknowledgement
        ordinaryReplay <- expired.change(f.owner, f.intent.change)
        _ <- assertIO(ordinaryReplay == acknowledgement)
        items <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id))
        histories <- ZIO.foreach(f.items)(item => ledger.history(f.owner, item.id, Revision(Long.MaxValue), 20))
        _ <- assertIO(items.forall(item => item.item.revision == Revision(3) && item.item.draft.body == task.body && item.item.draft.labels == task.labels &&
          item.item.draft.content.asInstanceOf[Content.Task].status == TaskStatus.Done && item.item.draft.content.asInstanceOf[Content.Task].validation.last.origin == EvidenceOrigin.HostObserved))
        _ <- assertIO(histories.forall(page => page.entries.size == 3 && page.entries.head.cursor == acknowledgement.cursor))
        preview <- expired.claimPreview(f.owner, f.claim.members)
        _ <- assertIO(preview.claims.isEmpty && preview.integrations.isEmpty)
        _ <- expired.acquire(f.owner, ClaimId(uuid), f.claim.members, 300000)
        _ <- reject(late.observe(f.collector, f.intent.id, IntegrationObservation.NotApplied("Changed observation")), _.isInstanceOf[Fault.Conflict])
      } yield ()
    }

    "retain settled non-application, reject changed intent and permit replacement only after resolution" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        candidates <- ZIO.collectAllPar(List(f.intent, f.fresh).map(intent => integrations.reserve(f.collector, intent).either))
        _ <- assertIO(candidates.count(_.isRight) == 1 && candidates.exists { case Left(DomainFailure(_: Fault.IntegrationPending)) => true; case _ => false })
        kept = candidates.collectFirst { case Right(value) => value }.get
        _ <- reject(integrations.reserve(f.collector, kept.intent.copy(target = "refs/heads/changed")), _.isInstanceOf[Fault.Conflict])
        observed = IntegrationObservation.NotApplied("Conditional update lost; executor settled")
        resolved <- integrations.observe(f.collector, kept.intent.id, observed)
        replay <- integrations.observe(f.collector, kept.intent.id, observed)
        reservedReplay <- integrations.reserve(f.collector, kept.intent)
        _ <- assertIO(resolved == replay && replay == reservedReplay && resolved.resolution == IntegrationResolution.NotApplied(observed.reason))
        unchanged <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id).map(_.item))
        _ <- assertIO(unchanged == f.items)
        _ <- reject(integrations.observe(f.collector, kept.intent.id, IntegrationObservation.Incorporated(f.intent.candidate)), _.isInstanceOf[Fault.Conflict])
        _ <- ledger.release(f.owner, f.claim.fence)
        _ <- ledger.acquire(f.owner.copy(actor = f.owner.actor.copy(session = SessionId(uuid))), ClaimId(uuid), f.claim.members, 300000)
      } yield ()
    }

    "serialize reservation against release and takeover in both orders" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO]) => for {
        released <- begin(ledger, usage, artifacts, admissions)
        _ <- ledger.release(released.owner, released.claim.fence)
        _ <- reject(integrations.reserve(released.collector, released.intent), _.isInstanceOf[Fault.StaleFence])
        taken <- begin(ledger, usage, artifacts, admissions)
        human = taken.owner.copy(actor = taken.owner.actor.copy(role = Role.Human))
        preview <- ledger.claimPreview(human, taken.claim.members)
        _ <- ledger.takeover(human, ClaimId(uuid), human.actor, taken.claim.members, 300000, preview.snapshot)
        _ <- reject(integrations.reserve(taken.collector, taken.intent), _.isInstanceOf[Fault.StaleFence])
        raced <- begin(ledger, usage, artifacts, admissions)
        results <- integrations.reserve(raced.collector, raced.intent).either.zipPar(ledger.release(raced.owner, raced.claim.fence).either)
        _ <- assertIO(results match {
          case (Right(_), Left(DomainFailure(_: Fault.IntegrationPending))) => true
          case (Left(DomainFailure(_: Fault.StaleFence)), Right(_)) => true
          case _ => false
        })
      } yield ()
    }

    "deny model roles and foreign owners and reject altered domain requests and validation evidence before reservation" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO]) => for {
        f <- begin(ledger, usage, artifacts, admissions)
        _ <- ZIO.foreachDiscard(List(Role.Human, Role.Governor, Role.Worker, Role.Reviewer, Role.Explorer, Role.Planner)) { role =>
          reject(integrations.reserve(f.collector.copy(actor = f.collector.actor.copy(role = role)), f.intent), _.isInstanceOf[Fault.Denied])
        }
        _ <- reject(integrations.reserve(f.collector.copy(actor = f.collector.actor.copy(session = SessionId(uuid))), f.intent), _.isInstanceOf[Fault.Denied])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(project = ProjectId(uuid))), _.isInstanceOf[Fault.Denied])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(change = f.intent.change.copy(mutations = List(Mutation.Create(task))))), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(checks = Nil)), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(checks = f.intent.checks.map(_.copy(command = List("another-check"))))), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.reserve(f.collector, f.intent.copy(candidate = GitCommit("c" * 40))), _.isInstanceOf[Fault.Invalid])
        _ <- reject(integrations.get(f.owner, f.intent.id), _.isInstanceOf[Fault.Missing])
        preview <- ledger.claimPreview(f.owner, f.claim.members)
        _ <- assertIO(preview.integrations.isEmpty)
        _ <- integrations.reserve(f.collector, f.intent)
        _ <- reject(integrations.observe(f.owner, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate)), _.isInstanceOf[Fault.Denied])
        _ <- reject(integrations.observe(f.collector.copy(actor = f.collector.actor.copy(session = SessionId(uuid))), f.intent.id,
          IntegrationObservation.NotApplied("Unauthorized")), _.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "roll back failed recording and replay a lost acknowledgement without duplicate completion" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], artifactRepository: ArtifactRepository[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], mutations: LedgerMutation) =>
        ZIO.foreachDiscard(List(true, false)) { abort =>
          val failing = new LedgerRepository[IO] {
            override def initialize(value: Project): IO[Throwable, Project] = repository.initialize(value)
            override def projects(after: Option[ProjectId], limit: Int): IO[Throwable, ProjectPage] = repository.projects(after, limit)
            override def catalogueCursor: IO[Throwable, CatalogueCursor] = repository.catalogueCursor
            override def itemCursor(project: ProjectId): IO[Throwable, ChangeCursor] = repository.itemCursor(project)
            override def transact[A](project: ProjectId)(operation: LedgerTransaction => A): IO[Throwable, A] =
              if (abort) repository.transact(project) { tx => operation(tx); throw new IOException("Recording transaction failed after applying changes") }
              else repository.transact(project)(operation).flatMap(_ => ZIO.fail(new IOException("Recording acknowledgement lost after commit")))
          }
          val lossy = new IntegrationServiceImpl[IO](failing, artifactRepository, mutations, Clock.systemUTC())
          for {
            f <- begin(ledger, usage, artifacts, admissions)
            _ <- integrations.reserve(f.collector, f.intent)
            result <- lossy.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate)).either
            _ <- assertIO(result.left.exists(_.isInstanceOf[IOException]))
            before <- integrations.get(f.owner, f.intent.id)
            _ <- assertIO(before.resolution.isInstanceOf[IntegrationResolution.Pending] == abort)
            items <- ZIO.foreach(f.items)(item => ledger.get(f.owner, item.id))
            _ <- assertIO(items.forall(_.item.revision == Revision(if (abort) 2 else 3)))
            recorded <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))
            replay <- integrations.observe(f.collector, f.intent.id, IntegrationObservation.Incorporated(f.intent.candidate))
            _ <- assertIO(recorded == replay)
            history <- ledger.history(f.owner, f.items.head.id, Revision(Long.MaxValue), 20)
            _ <- assertIO(history.entries.size == 3)
          } yield ()
        }
    }
  }
}

final class IntegrationContractDummy extends IntegrationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class IntegrationContractPostgres extends IntegrationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
