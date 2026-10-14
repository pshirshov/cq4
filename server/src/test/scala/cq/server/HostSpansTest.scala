package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import zio.{IO, Ref, Runtime, Semaphore, Task, Unsafe, ZIO}

/** A Check, Integrate and Combine span as the host records it: registered Running when the work starts and finished once, read from
  * an in-process server through its usage service. Jobs run on a driver that holds them until the test releases them. */
final class HostSpansLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource] },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]], DIKey[IntegrationService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Target = "refs/heads/integration"
  private val BothSides = ValidationCheck("both-sides", List("sh", "-c", "test -f left.txt && test -f right.txt"), 10000, 65536, 1, 0)

  /** Runs no process: a job started while `holding` stays running until `release`, and any other settles at once with exit 0. */
  private final class HeldDriver extends ExecutionDriver {
    @volatile var holding = false
    private val held = new ConcurrentLinkedQueue[Held]()
    private final class Held extends ManagedExecution {
      private val exit = GuardianExit(Some(0), None, StopReason.Exited, 0, 0, true, false)
      @volatile var open = true
      def settle(): Unit = open = false
      override def status: ProcessObservation =
        if (open) ProcessObservation(ProcessPhase.Running, false, Some(1), Some(2), None, None)
        else ProcessObservation(ProcessPhase.Settled, false, Some(1), Some(2), Some(exit), None)
      override def cancel(): ProcessObservation = { settle(); status }
      override def await(timeout: Duration): ProcessObservation = status
      override def close(): Unit = ()
    }
    override def start(spec: ExecutionSpec): ManagedExecution = {
      Files.createFile(spec.stdout)
      Files.createFile(spec.stderr)
      val value = new Held
      if (holding) held.add(value) else value.settle()
      value
    }
    def release(): Unit = { holding = false; held.forEach(_.settle()); held.clear() }
  }

  /** The host's collector, with the server's answer to each call and the faults a test injects. */
  private final class Hooks {
    @volatile var spansDown = false
    /** While set, the delivery of a Running span blocks on it, as a server that does not answer. */
    @volatile var holdStarts: java.util.concurrent.CountDownLatch = null
    @volatile var call: Command => Unit = _ => ()
    val held = new AtomicInteger(0)
    @volatile var artifact: ArtifactUpload => Unit = _ => ()
    val failed = new AtomicInteger(0)
    /** Every span the server acknowledged, in delivery order. */
    val delivered = new ConcurrentLinkedQueue[PhaseSpan]()
  }
  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any], hooks: Hooks) extends ServerApi {
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrow() }
    override def call(command: Command): Result = { hooks.call(command); execute(application.execute(authority, command)) }
    override def artifact(value: ArtifactUpload): ArtifactMetadata = { hooks.artifact(value); execute(application.upload(authority, value)) }
    override def usage(value: HostUsageInput): HostUsageResult = {
      value.operation match {
        case HostUsage.Span(span) if span.state == AttemptState.Running && hooks.holdStarts != null =>
          hooks.held.incrementAndGet(); hooks.holdStarts.await(60, java.util.concurrent.TimeUnit.SECONDS); ()
        case _ => ()
      }
      value.operation match {
        case HostUsage.Span(_) if hooks.spansDown => hooks.failed.incrementAndGet(); throw new IOException("The server cannot be reached")
        case _ => ()
      }
      try {
        val result = execute(application.ingest(authority, value))
        value.operation match { case HostUsage.Span(span) => hooks.delivered.add(span); case _ => () }
        result
      } catch { case error: Throwable => println(s"Host span delivery refused: $value: $error"); throw error }
    }
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = execute(application.integrate(authority, value))
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final class FixedClock(var now: Long) extends Clock {
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = Instant.ofEpochMilli(now)
    override def millis(): Long = now
  }

  private final case class World(local: LocalWorkspaceFixture, owner: Scope, config: SupervisorConfig, authority: SupervisorAuthority, controller: IntegrationController,
    combinations: CombinationController, jobs: JobSupervisor, driver: HeldDriver, hooks: Hooks, calls: Hooks, usage: UsageService[IO], fence: Fence, reviewer: ArtifactId,
    reviewed: GitCommit, head: GitCommit, worker: Assignment, governing: (Assignment, Attempt), request: DispatchRequest, workerAttempt: Attempt, profile: HarnessSetting,
    admission: Semaphore) {
    def collector: ServerApi = authority.collector
    def session: UsageFilter = UsageFilter.SessionOnly(owner.actor.session)
    /** spans, running host spans, running attempts and wall time of a phase row for the session. */
    def row(phase: UsagePhase): Task[(Long, Long, Long, Long)] = usage.phases(owner, session).map(_.phases.find(_.phase == phase)
      .map(value => (value.spans, value.runningSpans, value.running, value.wallMillis)).getOrElse((0L, 0L, 0L, 0L)))
    /** The spans the host delivered, one per identity as the server holds them: each delivered record is replayed, which returns the
      * stored span, whose parent is read. */
    def stored: Task[List[PhaseSpan]] = {
      val host = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
      ZIO.foreach(hooks.delivered.toArray.toList.collect { case span: PhaseSpan => span }.groupBy(_.id).values.map(_.head).toList)(span => usage.span(host, span))
    }
    def checks: Task[List[(Option[String], AttemptState, Long, Long)]] = usage.checks(owner, session).map(_.checks.map(value => (value.check, value.state, value.runs, value.wallMillis)))
    def eventually(what: String)(holds: Task[Boolean]): Task[Unit] = (ZIO.sleep(zio.Duration.fromMillis(25)) *> holds).repeatUntil(identity)
      .timeoutFail(new IllegalStateException("Not reached: " + what))(zio.Duration.fromSeconds(60)).unit
    def settled(id: IntegrationId): Task[IntegrationStatus] = controller.status(id, 120000)
      .repeatUntil(status => !Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(status.phase))
      .timeoutFail(new IllegalStateException("Integration did not settle"))(zio.Duration.fromSeconds(90))
    def command(execution: Long = 10000): JobCommand = JobCommand(List("true"), sys.env, "", ExecutionLimits(Duration.ofSeconds(5), Some(Duration.ofMillis(execution)),
      Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 65536))
  }

  /** The integration target advanced from the session base to a commit adding `left.txt` when `advanced`; the reviewed candidate adds `right.txt`
    * on the base. One claimed Task with an admitted worker result and an accepting review. */
  private def world(local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
    admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], checks: List[ValidationCheck], advanced: Boolean, conflict: Boolean = false)
    (test: World => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Host spans")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "host-spans-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
    val limits = HostLimits(5000, 900, 100, 1000, 262144)
    val hooks = new Hooks
    val driver = new HeldDriver
    def publish(value: ChildResult): Task[ArtifactId] = for {
      artifact <- artifacts.upload(collector, ArtifactUpload(owner.project, NativeArtifacts.id(value.attempt, "result"), value.attempt, ArtifactKind.Result,
        "application/json", Wire.encode(ChildResult_JsonCodec, value)))
      admitted <- admissions.admit(collector, HostAdmissionInput(owner.project, artifact.id, owner.actor))
      _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
    } yield artifact.id
    def commit(name: String, files: Map[String, String]): GitCommit = {
      val directory = local.directory.resolve(name + "-" + UUID.randomUUID())
      local.git(local.source, "worktree", "add", "--detach", directory.toString, local.base.value)
      files.foreach((file, text) => Files.writeString(directory.resolve(file), text))
      local.git(directory, "add", "--all")
      local.git(directory, "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "--quiet", "-m", name)
      GitCommit(local.git(directory, "rev-parse", "HEAD"))
    }
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      created <- ledger.change(owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil, None), Nil))), Nil, "Fixture"))
      claim <- ledger.acquire(owner, ClaimId(uuid), created.items.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis(), UsagePhase.Govern, None))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "host-spans-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", "0.156.1", Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, "/unused/guardian", List(profile), limits, checks, None, Some(Target))
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, sys.env, new DeliverySync)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      quiet = new Hooks
      calls = new Hooks
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime, quiet), new Receiver(application, auth, root, collectorAuthority, runtime, hooks),
        new Receiver(application, auth, root, governorAuthority, runtime, calls), AccessToken("governor", expires))
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        local.fixture.service, driver, directory.resolve("payload"), clock)
      admission <- Semaphore.make(1)
      renewal = new ClaimRenewal(ClaimRenewal.Default, logstage.IzLogger.NullLogger)
      candidates = new CandidateWorkspace(config)
      controller <- ZIO.acquireRelease(ZIO.succeed(new IntegrationController(config, authority, jobs, candidates, renewal, clock, admission)))(_.shutdown.orDie)
      combinations <- ZIO.acquireRelease(ZIO.succeed(new CombinationController(config, authority, candidates, clock)))(_.shutdown.orDie)
      commits <- ZIO.attemptBlocking {
        local.git(local.source, "branch", "integration", local.base.value)
        val reviewed = commit("candidate", Map("right.txt" -> "right\n") ++ (if (conflict) Map("tracked.txt" -> "candidate line\n") else Map.empty))
        val head = if (advanced) commit("target", Map("left.txt" -> "left\n") ++ (if (conflict) Map("tracked.txt" -> "target line\n") else Map.empty)) else local.base
        (reviewed, head)
      }
      (reviewed, head) = commits
      workerAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, claim.members, Attribution.Direct, None, None))
      workerAttempt <- usage.start(collector, governor.copy(id = AttemptId(uuid), assignment = workerAssignment.id, parent = Some(governor.id), role = Role.Worker, phase = UsagePhase.Work))
      _ <- ZIO.attemptBlocking {
        local.git(local.source, "update-ref", "refs/cq/candidates/" + workerAttempt.id.value, reviewed.value)
        if (advanced) local.git(local.source, "update-ref", Target, head.value, local.base.value)
      }
      // Every configured check was observed passing at the worker's admission, so the preparation finds the inventory covered.
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
      // The worker was a child of this session: its dispatch ticket names the assignment its host spans belong to.
      _ <- ZIO.attemptBlocking {
        val child = directory.resolve("children").resolve(workerAttempt.id.value.toString)
        HostFiles.directory(child)
        HostFiles.immutable(child.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, DispatchTicket(request, workerAssignment, workerAttempt, Some(profile), None)), 65536)
      }
      reviewAssignment <- usage.assign(collector, workerAssignment.copy(id = AssignmentId(uuid)))
      reviewAttempt <- usage.start(collector, workerAttempt.copy(id = AttemptId(uuid), assignment = reviewAssignment.id, role = Role.Reviewer, phase = UsagePhase.Review))
      review = ChildResult(reviewAttempt.id, request.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate), previous = Some(workerArtifact)),
        reviewed, Some(reviewed), ChildReport.Review(created.items.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), validation, RetainedEvidence(Nil, Nil))
      reviewArtifact <- publish(review)
      _ <- test(World(local, owner, config, authority, controller, combinations, jobs, driver, hooks, calls, usage, claim.fence, reviewArtifact, reviewed, head, workerAssignment,
        (assignment, governor), request, workerAttempt, profile, admission))
    } yield ()
  }

  private def finishedWall(record: JobRecord): Long = record.updatedAt - record.createdAt

  "Host spans (Behavioral Active Blackbox; real Git, a job driver the test holds and in-process server Communication)" should {
    "register the check of a rebased commit and its integration Running while they run and finish each once" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(BothSides), advanced = true) { w => for {
        _ <- ZIO.succeed { w.driver.holding = true }
        id = IntegrationId(uuid)
        _ <- w.controller.prepare(IntegrationTicket(id, w.reviewer))
        _ <- w.eventually("the check and the integration are Running on the server")(w.row(UsagePhase.Check).zipWith(w.row(UsagePhase.Integrate))((check, integrate) => check._2 == 1 && integrate._2 == 1))
        during <- w.checks
        checkRow <- w.row(UsagePhase.Check)
        integrateRow <- w.row(UsagePhase.Integrate)
        _ <- ZIO.attempt {
          assert(during == List((Some("both-sides"), AttemptState.Running, 1L, 0L)), during.toString)
          assert(checkRow == ((1L, 1L, 0L, 0L)) && integrateRow == ((1L, 1L, 0L, 0L)), s"$checkRow $integrateRow")
        }
        _ <- ZIO.succeed(w.driver.release())
        ready <- w.settled(id)
        records <- w.jobs.records(w.config.owner)
        after <- w.checks
        checkAfter <- w.row(UsagePhase.Check)
        integrateReady <- w.row(UsagePhase.Integrate)
        _ <- ZIO.attempt {
          val record = records.head
          assert(ready.phase == IntegrationPhase.Ready && records.size == 1, ready.toString)
          assert(after == List((Some("both-sides"), JobOutcome.observed(record).state, 1L, finishedWall(record))), after.toString)
          assert(checkAfter == ((1L, 0L, 0L, finishedWall(record))), checkAfter.toString)
          // A prepared integration that is not applied is still open: it ends with its apply or its discard.
          assert(integrateReady == ((1L, 1L, 0L, 0L)), integrateReady.toString)
        }
        held <- w.stored
        _ <- ZIO.attempt {
          val parent = SpanParent.Attempt(w.governing._2.id)
          val integrate = held.filter(_.phase == UsagePhase.Integrate)
          val check = held.filter(_.phase == UsagePhase.Check)
          assert(integrate.map(_.parent) == List(Some(parent)) && integrate.head.id == PhaseSpans.integrationId(id), held.toString)
          assert(check.map(_.parent) == List(Some(SpanParent.Span(integrate.head.id))), held.toString)
        }
        discarded <- w.controller.discard(id)
        integrateAfter <- w.row(UsagePhase.Integrate)
        again <- w.controller.discard(id)
        integrateTwice <- w.row(UsagePhase.Integrate)
        _ <- ZIO.attempt {
          assert(discarded.phase == IntegrationPhase.NotApplied && again.phase == IntegrationPhase.NotApplied, discarded.toString)
          assert(integrateAfter._1 == 1 && integrateAfter._2 == 0 && integrateAfter._3 == 0 && integrateAfter._4 >= 0, integrateAfter.toString)
          assert(integrateTwice == integrateAfter)
        }
      } yield () }
    }

    "register a combination preparation Running while it publishes and finish it once" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = true, conflict = true) { w => for {
        id = IntegrationId(uuid)
        _ <- w.controller.prepare(IntegrationTicket(id, w.reviewer))
        prepared <- w.settled(id)
        // The target moved under the candidate and the rebase conflicts: applying records the integration as not applied, which a combination needs.
        resolved <- w.controller(id).flatMap(_ => w.settled(id))
        entered = new java.util.concurrent.CountDownLatch(1)
        release = new java.util.concurrent.CountDownLatch(1)
        _ <- ZIO.succeed { w.hooks.artifact = value => if (value.kind == ArtifactKind.Combination) { entered.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS); () } }
        request = RequestId(uuid)
        _ <- w.combinations.prepare(CombinationTicket(request, id, w.fence))
        _ <- ZIO.attemptBlocking(assert(entered.await(60, java.util.concurrent.TimeUnit.SECONDS), "The combination was not published"))
        _ <- w.eventually("the combination is Running on the server")(w.row(UsagePhase.Combine).map(_._2 == 1))
        during <- w.row(UsagePhase.Combine)
        _ <- ZIO.succeed(release.countDown())
        status <- w.combinations.status(request, 120000).repeatUntil(_.phase != CombinationPhase.Preparing)
          .timeoutFail(new IllegalStateException("Combination did not settle"))(zio.Duration.fromSeconds(90))
        _ <- w.eventually("the combination is finished on the server")(w.row(UsagePhase.Combine).map(_._2 == 0))
        after <- w.row(UsagePhase.Combine)
        held <- w.stored
        _ <- ZIO.attempt {
          assert(held.filter(_.phase == UsagePhase.Combine).map(_.parent) == List(Some(SpanParent.Attempt(w.governing._2.id))), held.toString)
          assert(held.filter(_.phase == UsagePhase.Integrate).map(_.parent) == List(Some(SpanParent.Attempt(w.governing._2.id))), held.toString)
          assert(prepared.phase == IntegrationPhase.Ready && resolved.phase == IntegrationPhase.NotApplied, s"$prepared $resolved")
          assert(during == ((1L, 1L, 0L, 0L)), during.toString)
          assert(status.phase == CombinationPhase.Ready, status.toString)
          assert(after._1 == 1 && after._2 == 0 && after._3 == 0 && after._4 >= 0, after.toString)
        }
      } yield () }
    }

    "register a combination preparation Running before it claims or observes the target" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = true, conflict = true) { w => for {
        id = IntegrationId(uuid)
        _ <- w.controller.prepare(IntegrationTicket(id, w.reviewer))
        _ <- w.settled(id)
        _ <- w.controller(id).flatMap(_ => w.settled(id))
        reached = new java.util.concurrent.CountDownLatch(1)
        release = new java.util.concurrent.CountDownLatch(1)
        // The preparation's first claim renewal does not return until the test lets it: nothing was frozen or observed yet.
        _ <- ZIO.succeed { w.calls.call = {
          case Command.ClaimWork(_) => reached.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS); ()
          case _ => ()
        } }
        request = RequestId(uuid)
        fiber <- w.combinations.prepare(CombinationTicket(request, id, w.fence)).fork
        _ <- ZIO.attemptBlocking(assert(reached.await(60, java.util.concurrent.TimeUnit.SECONDS), "The preparation did not claim"))
        _ <- w.eventually("the combination is Running on the server")(w.row(UsagePhase.Combine).map(_._2 == 1))
        during <- w.row(UsagePhase.Combine)
        _ <- ZIO.succeed { w.calls.call = _ => (); release.countDown() }
        _ <- fiber.join
        status <- w.combinations.status(request, 120000).repeatUntil(_.phase != CombinationPhase.Preparing)
          .timeoutFail(new IllegalStateException("Combination did not settle"))(zio.Duration.fromSeconds(90))
        _ <- w.eventually("the combination is finished on the server")(w.row(UsagePhase.Combine).map(_._2 == 0))
        after <- w.row(UsagePhase.Combine)
        _ <- ZIO.attempt {
          assert(during == ((1L, 1L, 0L, 0L)), during.toString)
          assert(status.phase == CombinationPhase.Ready && after._1 == 1 && after._2 == 0, s"$status $after")
        }
      } yield () }
    }

    "finish a started combination preparation as Failed when it fails before its plan is frozen" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = true, conflict = true) { w => for {
        id = IntegrationId(uuid)
        _ <- w.controller.prepare(IntegrationTicket(id, w.reviewer))
        _ <- w.settled(id)
        _ <- w.controller(id).flatMap(_ => w.settled(id))
        // The preparation's claim renewal fails after its span was registered Running.
        _ <- ZIO.succeed { w.calls.call = {
          case Command.ClaimWork(_) => throw new IllegalStateException("claim renewal refused")
          case _ => ()
        } }
        request = RequestId(uuid)
        _ <- w.combinations.prepare(CombinationTicket(request, id, w.fence)).either
        status <- w.combinations.status(request, 120000).repeatUntil(_.phase != CombinationPhase.Preparing)
          .timeoutFail(new IllegalStateException("Combination did not settle"))(zio.Duration.fromSeconds(90))
        // Asserted before the controller shuts down: the span is terminal, not left Running for the shutdown to close as Unknown.
        _ <- w.eventually("the failed combination is finished on the server")(w.row(UsagePhase.Combine).map(_._2 == 0))
        after <- w.row(UsagePhase.Combine)
        listed <- w.usage.phases(w.owner, w.session).map(_.phases.find(_.phase == UsagePhase.Combine))
        _ <- ZIO.attempt {
          assert(status.phase == CombinationPhase.Failed, status.toString)
          assert(after._1 == 1 && after._2 == 0, after.toString)
          assert(listed.exists(_.runningSpans == 0), listed.toString)
        }
      } yield () }
    }

    "return a check's result and retain its finish while the delivery of its start is held" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = false) { w =>
        val spans = new SessionSpans(w.config, w.authority)
        val execution = new GovernedIntegrationJobs(w.config.owner, w.jobs, w.admission)
        val gate = new java.util.concurrent.CountDownLatch(1)
        for {
          _ <- ZIO.succeed { w.hooks.holdStarts = gate; w.driver.holding = true }
          id = AttemptId(uuid)
          fiber <- spans.check(w.jobs, execution, w.worker.id, None)("unit", id, w.local.base, w.command()).fork
          _ <- w.eventually("the start request is held")(ZIO.succeed(w.hooks.held.get >= 1))
          _ <- ZIO.succeed(w.driver.release())
          // The result comes back and the finish reaches the server although the start request has not returned.
          record <- fiber.join.timeoutFail(new IllegalStateException("The check result was delayed by its start"))(zio.Duration.fromSeconds(30))
          _ <- w.eventually("the finish is on the server")(w.row(UsagePhase.Check).map(_ == ((1L, 0L, 0L, finishedWall(record)))))
          held <- ZIO.succeed(w.hooks.held.get)
          _ <- ZIO.succeed(gate.countDown())
          // The released start is a replay after the finish: nothing changes.
          _ <- ZIO.sleep(zio.Duration.fromMillis(500))
          after <- w.checks
          row <- w.row(UsagePhase.Check)
          _ <- ZIO.attempt {
            assert(held == 1, s"The start was sent again while held: $held")
            assert(after == List((Some("unit"), JobOutcome.observed(record).state, 1L, finishedWall(record))), after.toString)
            assert(row == ((1L, 0L, 0L, finishedWall(record))), row.toString)
          }
        } yield ()
      }
    }

    "register a configured check Running at its job's registration on the session's check path and finish it with the job's state and settlement time" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = false) { w =>
        val spans = new SessionSpans(w.config, w.authority)
        val execution = new GovernedIntegrationJobs(w.config.owner, w.jobs, w.admission)
        for {
          _ <- ZIO.succeed { w.driver.holding = true }
          id = AttemptId(uuid)
          fiber <- spans.check(w.jobs, execution, w.worker.id, Some(SpanParent.Attempt(w.governing._2.id)))("unit", id, w.local.base, w.command()).fork
          _ <- w.eventually("the check is Running on the server")(w.row(UsagePhase.Check).map(_._2 == 1))
          during <- w.checks
          started <- w.jobs.status(w.config.owner, id)
          startedParent <- w.stored
          _ <- ZIO.succeed(w.driver.release())
          record <- fiber.join
          after <- w.checks
          row <- w.row(UsagePhase.Check)
          finishedParent <- w.stored
          _ <- ZIO.attempt {
            // The Running registration and the finish carry the same parent, as the revalidation round names it.
            assert(startedParent.map(_.parent) == List(Some(SpanParent.Attempt(w.governing._2.id))) && finishedParent.map(_.parent) == startedParent.map(_.parent), startedParent.toString)
            assert(during == List((Some("unit"), AttemptState.Running, 1L, 0L)), during.toString)
            assert(started.createdAt == record.createdAt && record.phase == JobPhase.Settled)
            assert(after == List((Some("unit"), JobOutcome.observed(record).state, 1L, finishedWall(record))), after.toString)
            assert(row == ((1L, 0L, 0L, finishedWall(record))), row.toString)
          }
        } yield ()
      }
    }

    "run a check whose start cannot be delivered to its result and leave one terminal span on the server" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = false) { w =>
        val spans = new SessionSpans(w.config, w.authority)
        val execution = new GovernedIntegrationJobs(w.config.owner, w.jobs, w.admission)
        for {
          _ <- ZIO.succeed { w.hooks.spansDown = true; w.driver.holding = true }
          id = AttemptId(uuid)
          fiber <- spans.check(w.jobs, execution, w.worker.id, None)("unit", id, w.local.base, w.command()).fork
          _ <- w.eventually("the start was refused")(ZIO.succeed(w.hooks.failed.get >= 1))
          nothing <- w.row(UsagePhase.Check)
          // The server comes back before the job ends: the finish delivers the retained start and itself.
          _ <- ZIO.succeed { w.hooks.spansDown = false; w.driver.release() }
          record <- fiber.join
          after <- w.checks
          row <- w.row(UsagePhase.Check)
          _ <- ZIO.attempt {
            assert(nothing == ((0L, 0L, 0L, 0L)), nothing.toString)
            assert(record.phase == JobPhase.Settled && JobOutcome.observed(record).succeeded, record.toString)
            assert(after == List((Some("unit"), AttemptState.Completed, 1L, finishedWall(record))), after.toString)
            assert(row == ((1L, 0L, 0L, finishedWall(record))), row.toString)
          }
        } yield ()
      }
    }

    "not retry a refused start on the path that finishes the check, so a retry that blocks does not delay its result" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = false) { w =>
        val spans = new SessionSpans(w.config, w.authority)
        val execution = new GovernedIntegrationJobs(w.config.owner, w.jobs, w.admission)
        val gate = new java.util.concurrent.CountDownLatch(1)
        for {
          _ <- ZIO.succeed { w.hooks.spansDown = true; w.driver.holding = true }
          id = AttemptId(uuid)
          fiber <- spans.check(w.jobs, execution, w.worker.id, None)("unit", id, w.local.base, w.command()).fork
          // The first start request failed and returned; the server then answers a start only after the test lets it.
          _ <- w.eventually("the start was refused")(ZIO.succeed(w.hooks.failed.get >= 1))
          _ <- ZIO.succeed { w.hooks.spansDown = false; w.hooks.holdStarts = gate; w.driver.release() }
          record <- fiber.join.timeoutFail(new IllegalStateException("The check result waited for the retry of its start"))(zio.Duration.fromSeconds(30))
          _ <- w.eventually("the finish is on the server")(w.row(UsagePhase.Check).map(_ == ((1L, 0L, 0L, finishedWall(record)))))
          held <- ZIO.succeed(w.hooks.held.get)
          _ <- ZIO.succeed(gate.countDown())
          after <- w.checks
          _ <- ZIO.attempt {
            assert(held == 0, s"The finish path retried the start: $held")
            assert(after == List((Some("unit"), AttemptState.Completed, 1L, finishedWall(record))), after.toString)
          }
        } yield ()
      }
    }

    "register a worker's candidate check Running at its job's registration and finish it as it publishes the attempt" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(ValidationCheck("unit", List("true"), 10000, 65536, 1, 0)), advanced = false) { w =>
        val clock = Clock.systemUTC()
        val settlement = new AttemptSettlement(w.config, w.authority, w.jobs, w.local.fixture.service, new CandidateWorkspace(w.config),
          new ClaimRenewal(ClaimRenewal.Default, logstage.IzLogger.NullLogger), clock)
        for {
          entry <- ZIO.succeed(new DispatchExecution(DispatchTicket(w.request, w.worker, w.workerAttempt, Some(w.profile), None),
            w.config.directory.resolve("children").resolve(w.workerAttempt.id.value.toString), null, null))
          trace <- Ref.make(AttemptTrace.Empty)
          _ <- ZIO.succeed { w.driver.holding = true }
          fiber <- settlement.checked(entry, w.reviewed, trace).fork
          _ <- w.eventually("the check is Running on the server")(w.row(UsagePhase.Check).map(_._2 == 1))
          during <- w.checks
          _ <- ZIO.succeed(w.driver.release())
          evidence <- fiber.join
          traced <- trace.get
          after <- w.checks
          held <- w.stored
          records <- w.jobs.records(w.config.owner)
          _ <- ZIO.attempt {
            val record = records.head
            val governing = Some(SpanParent.Attempt(w.governing._2.id))
            assert(held.map(_.parent) == List(governing) && traced.spans.map(_.parent) == List(governing), held.toString)
            assert(during == List((Some("unit"), AttemptState.Running, 1L, 0L)), during.toString)
            assert(evidence.map(_.state) == List(ValidationState.Passed), evidence.toString)
            assert(traced.spans == List(PhaseSpans.check(record, w.worker.id, "unit", governing)), traced.spans.toString)
            assert(after == List((Some("unit"), AttemptState.Completed, 1L, finishedWall(record))), after.toString)
          }
        } yield ()
      }
    }

    "register a reviewer-declared check with the reviewer's attempt as parent, at its start and at its finish, through the reviewer's own request path" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, List(ValidationCheck("verify", List("true"), 10000, 65536, 1, 0)), advanced = false) { w =>
        val collector = w.owner.copy(actor = w.owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
        for {
          reviewer <- usage.start(collector, w.workerAttempt.copy(id = AttemptId(uuid), assignment = w.worker.id, parent = Some(w.governing._2.id), role = Role.Reviewer, phase = UsagePhase.Review))
          ticket = DispatchTicket(w.request.copy(work = DispatchWork.Reviewer(ReviewerMode.Candidate)), w.worker, reviewer, Some(w.profile), None)
          entry <- ZIO.attemptBlocking {
            val value = new DispatchExecution(ticket, w.config.directory.resolve("children").resolve(reviewer.id.value.toString), null, null)
            HostFiles.directory(value.directory)
            value.active(reviewer.id)
            value
          }
          checks = new ReviewerChecks(entry, w.reviewed, w.config, w.collector, w.jobs)
          _ <- ZIO.attemptBlocking(entry.installChecks(checks))
          _ <- ZIO.succeed { w.driver.holding = true }
          _ <- checks.request("verify", 100)
          _ <- w.eventually("the declared check is Running on the server")(w.row(UsagePhase.Check).map(_._2 == 1))
          started <- w.stored
          _ <- ZIO.succeed(w.driver.release())
          status <- checks.request("verify", 1000).repeatUntil(value => Set(DeclaredCheckPhase.Completed, DeclaredCheckPhase.Failed, DeclaredCheckPhase.Unknown)(value.phase))
            .timeoutFail(new IllegalStateException("Declared check did not terminate"))(zio.Duration.fromSeconds(60))
          _ <- checks.close
          _ <- w.eventually("the declared check is finished on the server")(w.row(UsagePhase.Check).map(_._2 == 0))
          finished <- w.stored
          _ <- ZIO.attempt {
            val reviewing = Some(SpanParent.Attempt(reviewer.id))
            assert(started.map(_.parent) == List(reviewing) && started.map(_.assignment) == List(w.worker.id), started.toString)
            assert(finished.map(_.parent) == List(reviewing) && finished.map(_.state) != List(AttemptState.Running), finished.toString)
            assert(status.phase == DeclaredCheckPhase.Completed, status.toString)
          }
        } yield ()
      }
    }

    "close a span whose host ended before its finish as Unknown at recovery and leave a finished one as it is" in {
      (local: LocalWorkspaceFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
      world(local, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, Nil, advanced = false) { w =>
        val project = w.config.project.project
        val session = w.owner.actor.session
        def started(at: Long, name: String): PhaseSpan = PhaseSpan(RequestId(uuid), w.worker.id, session, UsagePhase.Check, at, at, AttemptState.Running, Some(name), None, None)
        val parent = Some(SpanParent.Attempt(w.governing._2.id))
        val unfinished = started(5000, "unfinished").copy(parent = parent)
        val finished = started(6000, "finished")
        val undelivered = started(7000, "undelivered")
        val integration = PhaseSpan(RequestId(uuid), w.worker.id, session, UsagePhase.Integrate, 8000, 8000, AttemptState.Running, None, None, parent)
        val recovery = new FixedClock(100000)
        def stored(span: PhaseSpan): Task[PhaseSpan] = usage.span(Scope(project, Actor("CQ host collector", session, Role.Collector)), span.copy(finishedAt = span.startedAt, state = AttemptState.Running))
        ZIO.scoped { for {
          directory <- ZIO.attemptBlocking(Files.createTempDirectory(w.local.directory, "interrupted-"))
          delivery = new SpanDelivery(directory.resolve("spans"), project, new DeliverySync)
          journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project, session)))(value => ZIO.attemptBlocking(value.close()).orDie)
          run = SupervisorRun(w.config.project, w.governing._1, w.governing._2, "0.156.1", w.local.source.toString, w.local.base, SessionOwnership.Attached)
          // A host that died after the start of three spans was delivered; one of them was finished, and one start never reached the server.
          _ <- ZIO.attemptBlocking {
            List(unfinished, finished, integration).foreach { span => delivery.retain(span); delivery.send(span, w.collector) }
            delivery.retain(undelivered)
            val done = finished.copy(finishedAt = 6400, state = AttemptState.Failed)
            delivery.retain(done)
            delivery.send(done, w.collector)
          }
          before <- ZIO.foreach(List(unfinished, finished, integration))(stored)
          pending = SessionDelivery.remainder(directory, run)
          cursor <- usage.cursor(w.owner)
          first <- new SessionDelivery(journal, w.local.fixture.service, recovery).flush(directory, run, w.collector)
          after <- ZIO.foreach(List(unfinished, finished, undelivered, integration))(stored)
          settled = SessionDelivery.remainder(directory, run)
          cursorAfter <- usage.cursor(w.owner)
          second <- new SessionDelivery(journal, w.local.fixture.service, recovery).flush(directory, run, w.collector)
          again <- ZIO.foreach(List(unfinished, finished, undelivered, integration))(stored)
          cursorTwice <- usage.cursor(w.owner)
          _ <- ZIO.attempt {
            assert(before.map(_.state) == List(AttemptState.Running, AttemptState.Failed, AttemptState.Running), before.toString)
            assert(pending.undelivered, "A started span without a finish is still undelivered")
            assert(cursorAfter > cursor && first.acknowledged > 0, s"$first")
            assert(after.map(_.state) == List(AttemptState.Unknown, AttemptState.Failed, AttemptState.Unknown, AttemptState.Unknown), after.toString)
            assert(after.map(_.finishedAt) == List(100000L, 6400L, 100000L, 100000L), after.toString)
            // Recovery closes a span with the parent of its Running registration.
            assert(after.map(_.parent) == List(parent, None, None, parent), after.toString)
            assert(!settled.undelivered, "Nothing is left to deliver once recovery closed the spans")
            assert(again == after && cursorTwice == cursorAfter && second.acknowledged >= 0)
          }
          check <- usage.checks(w.owner, w.session)
          _ <- ZIO.attempt {
            assert(check.checks.map(value => (value.check, value.state, value.runs)) == List((Some("finished"), AttemptState.Failed, 1L),
              (Some("undelivered"), AttemptState.Unknown, 1L), (Some("unfinished"), AttemptState.Unknown, 1L)), check.checks.toString)
          }
        } yield () }
      }
    }
  }
}
