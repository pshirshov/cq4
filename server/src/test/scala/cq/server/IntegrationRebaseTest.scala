package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import zio.{IO, Runtime, Semaphore, Task, Unsafe, ZIO}

/** Runs the real IntegrationController against an in-process server, real Git and guardian-supervised check and checkout jobs. */
final class IntegrationRebaseProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, GuardianTestPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource] },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]], DIKey[IntegrationService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Target = "refs/heads/integration"
  private val BothSides = ValidationCheck("both-sides", List("sh", "-c", "test -f left.txt && test -f right.txt"), 10000, 65536, 1)
  private val CandidateOnly = ValidationCheck("candidate-only", List("sh", "-c", "echo target change present >&2; test ! -f left.txt"), 10000, 65536, 1)
  private val Slow = ValidationCheck("slow", List("sleep", "60"), 90000, 65536, 1)

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any],
    renewals: AtomicInteger) extends ServerApi {
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrowFiberFailure() }
    override def call(command: Command): Result = {
      command match { case Command.ClaimWork(ClaimInput(_, _: ClaimAction.Renew)) => renewals.incrementAndGet(); case _ => () }
      execute(application.execute(authority, command))
    }
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = execute(application.ingest(authority, value))
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final case class Fixture(local: LocalWorkspaceFixture, owner: Scope, config: SupervisorConfig, controller: IntegrationController, jobs: JobSupervisor,
    governor: AttemptId, reviewer: ArtifactId, reviewed: GitCommit, head: GitCommit, members: List[ItemRevision], fence: Fence, renewals: AtomicInteger) {
    def target: GitCommit = GitCommit(local.git(local.source, "show-ref", "--verify", "--hash", Target))
    def commit(name: String, base: GitCommit, files: Map[String, String]): GitCommit = {
      val directory = local.directory.resolve(name + "-" + UUID.randomUUID())
      local.git(local.source, "worktree", "add", "--detach", directory.toString, base.value)
      files.foreach((file, text) => Files.writeString(directory.resolve(file), text))
      local.git(directory, "add", "--all")
      local.git(directory, "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "--quiet", "-m", name)
      GitCommit(local.git(directory, "rev-parse", "HEAD"))
    }
    def settled(id: IntegrationId): Task[IntegrationStatus] = controller.status(id, 20000)
      .repeatUntil(status => !Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(status.phase))
      .timeoutFail(new IllegalStateException("Integration did not settle"))(zio.Duration.fromSeconds(90))
    def prepare: Task[IntegrationStatus] = {
      val id = IntegrationId(UUID.randomUUID())
      controller.prepare(IntegrationTicket(id, reviewer)) *> settled(id)
    }
    def integrate(id: IntegrationId): Task[IntegrationStatus] = controller.apply(id) *> settled(id)
  }

  /** The integration target advanced from the session base to a commit adding `left.txt`; the reviewed candidate adds `right.txt` on the base.
    * With `conflict` both also rewrite `tracked.txt`. */
  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], checks: List[ValidationCheck], conflict: Boolean)(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Integration rebase")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "integration-rebase-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
    val limits = HostLimits(5000, 30000, 900, 100, 1000, 262144)
    val renewals = new AtomicInteger(0)
    def publish(value: ChildResult): Task[ArtifactId] = for {
      artifact <- artifacts.upload(collector, ArtifactUpload(owner.project, ArtifactId(uuid), value.attempt, ArtifactKind.Result,
        "application/json", Wire.encode(ChildResult_JsonCodec, value)))
      admitted <- admissions.admit(collector, HostAdmissionInput(owner.project, artifact.id, owner.actor))
      _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
    } yield artifact.id
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      created <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil))), Nil, "Fixture"))
      claim <- ledger.acquire(owner, ClaimId(uuid), created.items.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis(), UsagePhase.Govern))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "integration-rebase-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", "0.156.1", Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, checks, None, Some(Target))
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime, new AtomicInteger(0)),
        new Receiver(application, auth, root, collectorAuthority, runtime, new AtomicInteger(0)),
        new Receiver(application, auth, root, governorAuthority, runtime, renewals), AccessToken("governor", expires), expires)
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        local.fixture.service, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      admission <- Semaphore.make(1)
      controller <- ZIO.acquireRelease(ZIO.succeed(new IntegrationController(config, authority, jobs, new CandidateWorkspace(config), clock, admission)))(_.shutdown.orDie)
      empty = Fixture(local, owner, config, controller, jobs, governor.id, ArtifactId(uuid), local.base, local.base, created.items, claim.fence, renewals)
      commits <- ZIO.attemptBlocking {
        local.git(local.source, "branch", "integration", local.base.value)
        val reviewed = empty.commit("candidate", local.base, Map("right.txt" -> "right\n") ++ (if (conflict) Map("tracked.txt" -> "candidate line\n") else Map.empty))
        val head = empty.commit("target", local.base, Map("left.txt" -> "left\n") ++ (if (conflict) Map("tracked.txt" -> "target line\n") else Map.empty))
        (reviewed, head)
      }
      (reviewed, head) = commits
      workerAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, claim.members, Attribution.Direct, None, None))
      workerAttempt <- usage.start(collector, governor.copy(id = AttemptId(uuid), assignment = workerAssignment.id, parent = Some(governor.id), role = Role.Worker, phase = UsagePhase.Work))
      _ <- ZIO.attemptBlocking {
        local.git(local.source, "update-ref", "refs/cq/candidates/" + workerAttempt.id.value, reviewed.value)
        local.git(local.source, "update-ref", Target, head.value, local.base.value)
      }
      validation <- ZIO.foreach(checks) { check =>
        val job = JobRecord(WorkspaceSpec(owner.project, owner.actor.session, AttemptId(uuid), local.source.toString, reviewed), "fixture", JobTarget.Run,
          JobPhase.Settled, Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1000, 1001)
        artifacts.upload(collector, ArtifactUpload(owner.project, ArtifactId(uuid), workerAttempt.id, ArtifactKind.Validation, "application/json",
          Wire.encode(ValidationObservation_JsonCodec, ValidationObservation(check, reviewed, job, ArtifactId(uuid), ArtifactId(uuid)))))
          .map(metadata => ValidationEvidence(check.name, ValidationState.Passed, metadata.id, Nil))
      }
      request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, created.items, Nil, Nil, None, claim.fence, limits)
      worker = ChildResult(workerAttempt.id, request, local.base, Some(reviewed),
        ChildReport.Work(created.items.map(ref => WorkMember(ref.id, WorkDisposition.CandidateReady, "Ready", Nil))), validation, RetainedEvidence(Nil, Nil))
      workerArtifact <- publish(worker)
      reviewAssignment <- usage.assign(collector, workerAssignment.copy(id = AssignmentId(uuid)))
      reviewAttempt <- usage.start(collector, workerAttempt.copy(id = AttemptId(uuid), assignment = reviewAssignment.id, role = Role.Reviewer, phase = UsagePhase.Review))
      review = ChildResult(reviewAttempt.id, request.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate), previous = Some(workerArtifact)),
        reviewed, Some(reviewed), ChildReport.Review(created.items.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), validation, RetainedEvidence(Nil, Nil))
      reviewArtifact <- publish(review)
      _ <- test(empty.copy(reviewer = reviewArtifact, reviewed = reviewed, head = head))
    } yield ()
  }

  private def blocker(f: Fixture, cause: String): Option[String] = Some(s"Target advanced to ${f.head.value}; $cause; Integrate records NotApplied, then Combine")
  private def refused(f: Fixture, reason: String): Task[Unit] = for {
    ready <- f.prepare
    records <- f.jobs.records(f.config.owner)
    _ <- ZIO.attemptBlocking {
      println(s"Refused rebase: $ready")
      assert(ready.phase == IntegrationPhase.Ready && ready.blocker == blocker(f, "host rebase refused: " + reason), ready.toString)
      assert(ready.preview.exists(value => value.rebase == RebaseOutcome.Refused(f.head, reason) && value.candidate == f.reviewed && value.expected == f.local.base))
      assert(f.local.git(f.local.source, "for-each-ref", "--format=%(refname)", "refs/cq/candidates/" + ready.id.value).isEmpty && records.isEmpty)
    }
  } yield ()
  private def text(artifacts: ArtifactService[IO], owner: Scope, id: ArtifactId): Task[String] = artifacts.metadata(owner, id).flatMap { metadata =>
    def loop(offset: Int, body: StringBuilder): Task[String] =
      if (offset >= metadata.codePoints) ZIO.succeed(body.toString)
      else artifacts.page(owner, id, offset, ArtifactService.MaxPageCodePoints).flatMap(page => loop(page.next, body.append(page.text)))
    loop(0, new StringBuilder)
  }

  "Integration onto an advanced target (Behavioral Active Blackbox; real Git, supervised processes and in-process server Communication)" should {
    "rebase the reviewed candidate, rerun the configured check on the rebased commit and record it without a child attempt" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides), false) { f => for {
        attemptsBefore <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
        governing <- ZIO.attemptBlocking {
          Files.writeString(local.source.resolve("staged.txt"), "governing staged\n")
          local.git(local.source, "add", "staged.txt")
          (local.git(local.source, "rev-parse", "HEAD"), Files.readAllBytes(local.source.resolve(".git/index")).toList)
        }
        ready <- f.prepare
        preview = ready.preview.get
        merged = preview.candidate
        _ <- ZIO.attemptBlocking {
          println(s"Rebased preparation: phase=${ready.phase} rebase=${preview.rebase} expected=${preview.expected.value.take(7)} head=${f.head.value.take(7)} " +
            s"candidate=${merged.value.take(7)} reviewed=${f.reviewed.value.take(7)} blocker=${ready.blocker}")
          assert(ready.phase == IntegrationPhase.Ready && ready.next == IntegrationNext.Confirm && ready.blocker.isEmpty, ready.toString)
          assert(preview.rebase == RebaseOutcome.Applied(f.reviewed) && preview.expected == f.head && merged != f.reviewed)
          assert(local.git(local.source, "rev-list", "--parents", "-n", "1", merged.value).split(" ").toList == List(merged.value, f.head.value, f.reviewed.value))
          assert(local.git(local.source, "show-ref", "--verify", "--hash", "refs/cq/candidates/" + ready.id.value) == merged.value)
          // The rebased commit keeps the reviewed candidate's subject, so first-parent history of the target still names the work.
          assert(local.git(local.source, "log", "-1", "--format=%s", merged.value) == local.git(local.source, "log", "-1", "--format=%s", f.reviewed.value))
          assert(local.git(local.source, "log", "-1", s"--format=%(trailers:key=${CandidateMessage.IntegrationTrailer},valueonly)", merged.value) == ready.id.value.toString)
          assert(f.target == f.head)
        }
        recorded <- f.integrate(ready.id)
        record <- integrations.get(f.owner, ready.id)
        rebase = record.intent.rebase.get
        observation <- text(artifacts, f.owner, rebase.validation.head.artifact).map(Wire.decode(ValidationObservation_JsonCodec, _))
        metadata <- artifacts.metadata(f.owner, rebase.validation.head.artifact)
        task <- ledger.get(f.owner, f.members.head.id).map(_.item.draft.content.asInstanceOf[Content.Task])
        attemptsAfter <- usage.attempts(f.owner, UsageFilter.SessionOnly(f.owner.actor.session), None, None, 100)
        records <- f.jobs.records(f.config.owner)
        workspaces <- ZIO.foreach(records)(record => local.fixture.service.get(f.config.owner, record.workspace.attempt))
        _ <- ZIO.attempt(assert(workspaces.forall(_.admission == WorkspaceAdmission.Removed), s"Check or Git job workspace was retained: $workspaces"))
        _ <- ZIO.attemptBlocking {
          println(s"Rebased integration: phase=${recorded.phase} target=${f.target.value.take(7)} attempts=${attemptsAfter.entries.size} jobs=${records.size} " +
            s"evidence=${rebase.validation} citations=${task.validation.last.citations}")
          assert(recorded.phase == IntegrationPhase.Recorded && recorded.preview == ready.preview && f.target == merged, recorded.toString)
          assert(record.resolution.isInstanceOf[IntegrationResolution.Recorded] && record.intent.candidate == merged && record.intent.expected == f.head)
          assert(rebase.reviewed == f.reviewed && rebase.author == f.governor && rebase.validation.map(v => (v.check, v.state)) == List((BothSides.name, ValidationState.Passed)))
          assert(metadata.attempt == f.governor && metadata.kind == ArtifactKind.Validation && observation.check == BothSides &&
            observation.candidate == merged && observation.job.workspace.base == merged && JobOutcome.observed(observation.job).succeeded)
          assert(task.status == TaskStatus.Done && task.validation.last.citations.take(2) == List(
            Citation.Commit(local.source.toString, merged.value), Citation.Commit(local.source.toString, f.reviewed.value)) &&
            task.validation.last.citations.contains(Citation.Artifact(rebase.validation.head.artifact)))
          // No child attempt ran: the session's attempts are unchanged and the host ran one check job and one Git job.
          assert(attemptsAfter.entries.map(_.attempt.id) == attemptsBefore.entries.map(_.attempt.id) && !Files.exists(f.config.directory.resolve("children")))
          assert(records.size == 2 && records.map(_.workspace.base).toSet == Set(merged))
          assert((local.git(local.source, "rev-parse", "HEAD"), Files.readAllBytes(local.source.resolve(".git/index")).toList) == governing)
          assert(List("left.txt", "right.txt").forall(name => local.git(local.source, "show", Target + ":" + name) == name.stripSuffix(".txt")))
        }
      } yield () }
    }

    "I19: rerun a check that fails once on the rebased commit and cite the failed run in the Task evidence" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      val counter = local.directory.resolve("flaky-" + uuid)
      val flaky = ValidationCheck("flaky", List("sh", "-c",
        s"test -f left.txt && test -f right.txt || exit 2; n=$$(cat $counter 2>/dev/null || echo 0); echo $$((n + 1)) > $counter; test $$n -ge 1"), 10000, 65536, 2)
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(flaky), false) { f => for {
        ready <- f.prepare
        _ <- ZIO.attempt(assert(ready.phase == IntegrationPhase.Ready && ready.blocker.isEmpty &&
          ready.preview.exists(_.rebase == RebaseOutcome.Applied(f.reviewed)), ready.toString))
        recorded <- f.integrate(ready.id)
        record <- integrations.get(f.owner, ready.id)
        evidence = record.intent.rebase.get.validation.head
        observations <- ZIO.foreach(evidence.failures :+ evidence.artifact)(id => text(artifacts, f.owner, id).map(Wire.decode(ValidationObservation_JsonCodec, _)))
        task <- ledger.get(f.owner, f.members.head.id).map(_.item.draft.content.asInstanceOf[Content.Task])
        records <- f.jobs.records(f.config.owner)
        _ <- ZIO.attemptBlocking {
          val merged = ready.preview.get.candidate
          println(s"Intermittent check on the rebased commit: evidence=$evidence runs=${Files.readString(counter).trim} citations=${task.validation.takeRight(2).map(_.citations)}")
          assert(recorded.phase == IntegrationPhase.Recorded && f.target == merged, recorded.toString)
          assert(evidence.state == ValidationState.Passed && evidence.failures.size == 1 && Files.readString(counter).trim == "2", evidence.toString)
          assert(observations.forall(value => value.candidate == merged && value.job.workspace.base == merged) &&
            observations.map(value => JobOutcome.observed(value.job).succeeded) == List(false, true))
          // Two check jobs and one Git job, each on the rebased commit.
          assert(records.size == 3 && records.map(_.workspace.base).toSet == Set(merged))
          val cited = task.validation.takeRight(2)
          assert(cited.head.citations.contains(Citation.Artifact(evidence.artifact)) && cited.last.citations == List(Citation.Artifact(evidence.failures.head)), cited.toString)
        }
      } yield () }
    }

    "record NotApplied when the target advances again after the rebase, and rebase again under a new identity" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides), false) { f => for {
        first <- f.prepare
        _ <- assertIO(first.preview.exists(_.rebase == RebaseOutcome.Applied(f.reviewed)))
        further <- ZIO.attemptBlocking {
          val further = f.commit("further", f.head, Map("further.txt" -> "further\n"))
          local.git(local.source, "update-ref", Target, further.value, f.head.value)
          further
        }
        stale <- f.integrate(first.id)
        _ <- ZIO.attempt(assert(stale.phase == IntegrationPhase.NotApplied && f.target == further, stale.toString))
        second <- f.prepare
        _ <- ZIO.attempt(assert(second.phase == IntegrationPhase.Ready && second.preview.exists(value =>
          value.rebase == RebaseOutcome.Applied(f.reviewed) && value.expected == further && value.candidate != first.preview.get.candidate), second.toString))
        recorded <- f.integrate(second.id)
        _ <- ZIO.attemptBlocking {
          val merged = second.preview.get.candidate
          println(s"Second advance: first=${stale.phase} (${stale.blocker}) second=${recorded.phase} target=${f.target.value.take(7)}")
          assert(recorded.phase == IntegrationPhase.Recorded && f.target == merged)
          assert(local.git(local.source, "rev-list", "--parents", "-n", "1", merged.value).split(" ").toList == List(merged.value, further.value, f.reviewed.value))
        }
      } yield () }
    }

    "freeze the reviewed candidate with a Conflicted preview when the merge conflicts, so Integrate records NotApplied" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides), true) { f => for {
        ready <- f.prepare
        _ <- ZIO.attemptBlocking {
          println(s"Conflicting preparation: $ready")
          assert(ready.phase == IntegrationPhase.Ready && ready.next == IntegrationNext.Confirm && ready.blocker == blocker(f, "conflict"), ready.toString)
          assert(ready.preview.exists(value => value.rebase == RebaseOutcome.Conflicted(f.head) && value.candidate == f.reviewed && value.expected == local.base))
          assert(local.git(local.source, "for-each-ref", "--format=%(refname)", "refs/cq/candidates/" + ready.id.value).isEmpty)
        }
        applied <- f.integrate(ready.id)
        record <- integrations.get(f.owner, ready.id)
        records <- f.jobs.records(f.config.owner)
        _ <- ZIO.attempt(assert(applied.phase == IntegrationPhase.NotApplied && applied.preview == ready.preview && f.target == f.head &&
          record.intent.rebase.isEmpty && record.intent.candidate == f.reviewed && record.resolution.isInstanceOf[IntegrationResolution.NotApplied] && records.isEmpty,
          applied.toString))
      } yield () }
    }

    "freeze the reviewed candidate with a ChecksFailed preview and the failing evidence when a configured check fails on the rebased commit" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides, CandidateOnly), false) { f => for {
        ready <- f.prepare
        evidence = ready.preview.get.rebase match { case RebaseOutcome.ChecksFailed(_, validation) => validation; case other => fail(s"Unexpected outcome $other") }
        observation <- text(artifacts, f.owner, evidence.last.artifact).map(Wire.decode(ValidationObservation_JsonCodec, _))
        stderr <- text(artifacts, f.owner, observation.stderr).map(Wire.decode(NativeManifest_JsonCodec, _))
        _ <- ZIO.attemptBlocking {
          println(s"Failing check on the rebased commit: $ready")
          assert(ready.phase == IntegrationPhase.Ready && ready.blocker == blocker(f, s"check ${CandidateOnly.name} failed on the rebased commit"), ready.toString)
          assert(ready.preview.exists(value => value.rebase == RebaseOutcome.ChecksFailed(f.head, evidence) && value.candidate == f.reviewed && value.expected == local.base))
          assert(evidence.map(value => (value.check, value.state)) == List((BothSides.name, ValidationState.Passed), (CandidateOnly.name, ValidationState.Failed)))
          assert(observation.check == CandidateOnly && observation.job.workspace.base == observation.candidate && observation.candidate != f.reviewed &&
            local.git(local.source, "rev-list", "--parents", "-n", "1", observation.candidate.value).split(" ").toList.tail == List(f.head.value, f.reviewed.value))
          assert(stderr.bytes == "target change present\n".length)
        }
        applied <- f.integrate(ready.id)
        record <- integrations.get(f.owner, ready.id)
        _ <- ZIO.attempt(assert(applied.phase == IntegrationPhase.NotApplied && f.target == f.head && record.intent.rebase.isEmpty &&
          record.intent.candidate == f.reviewed, applied.toString))
      } yield () }
    }

    "refuse the host rebase without configured checks, leaving the reviewed candidate to Combine" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, false)(
        refused(_, "No configured check would verify the rebased commit"))
    }

    "refuse the host rebase in a repository that defines a merge driver, leaving the reviewed candidate to Combine" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides), false) { f =>
        ZIO.attemptBlocking(local.git(local.source, "config", "merge.fixture.driver", "true")) *>
          refused(f, "Host rebase refuses repository-defined merge drivers")
      }
    }

    "stop a running check and fail the preparation when the claim is lost" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(Slow), false) { f =>
        val id = IntegrationId(uuid)
        for {
          _ <- f.controller.prepare(IntegrationTicket(id, f.reviewer))
          running <- (ZIO.sleep(zio.Duration.fromMillis(100)) *> f.jobs.records(f.config.owner)).repeatUntil(_.exists(_.phase == JobPhase.Running))
            .timeoutFail(new IllegalStateException("Check did not start"))(zio.Duration.fromSeconds(30))
          _ <- ledger.release(f.owner, f.fence)
          status <- f.settled(id)
          record <- f.jobs.await(f.config.owner, running.head.workspace.attempt)
            .timeoutFail(new IllegalStateException("Check was not stopped"))(zio.Duration.fromSeconds(20))
          _ <- ZIO.attempt {
            println(s"Claim lost during a check: status=$status job=${record.phase} ${record.exit}")
            assert(status.phase == IntegrationPhase.Failed && record.phase == JobPhase.Settled && record.exit.exists(_.reason == StopReason.Cancelled), status.toString)
          }
        } yield ()
      }
    }

    "renew the claim while a check runs and cancel the check on shutdown" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(Slow), false) { f =>
        val id = IntegrationId(uuid)
        for {
          _ <- f.controller.prepare(IntegrationTicket(id, f.reviewer))
          running <- (ZIO.sleep(zio.Duration.fromMillis(100)) *> f.jobs.records(f.config.owner)).repeatUntil(_.exists(_.phase == JobPhase.Running))
            .timeoutFail(new IllegalStateException("Check did not start"))(zio.Duration.fromSeconds(30))
          initial = f.renewals.get()
          _ <- ZIO.attempt(assert(!f.controller.quiescent))
          _ <- ZIO.sleep(zio.Duration.fromMillis(500)).repeatUntil(_ => f.renewals.get() > initial)
            .timeoutFail(new IllegalStateException("Claim was not renewed while the check ran"))(zio.Duration.fromSeconds(40))
          began <- ZIO.succeed(System.nanoTime())
          _ <- f.controller.shutdown
          status <- f.controller.status(id, 0)
          record <- f.jobs.status(f.config.owner, running.head.workspace.attempt)
          _ <- ZIO.attempt {
            println(s"Shutdown during a check: renewals=$initial→${f.renewals.get()} status=$status job=${record.phase} ${record.exit} " +
              s"after ${(System.nanoTime() - began) / 1000000} ms")
            assert(status.phase == IntegrationPhase.Failed && record.phase == JobPhase.Settled && !JobOutcome.observed(record).succeeded, status.toString)
            assert(System.nanoTime() - began < zio.Duration.fromSeconds(20).toNanos)
          }
          missing <- integrations.get(f.owner, id).either
          _ <- assertIO(missing.left.exists { case DomainFailure(_: Fault.Missing) => true; case _ => false })
        } yield ()
      }
    }
  }
}
