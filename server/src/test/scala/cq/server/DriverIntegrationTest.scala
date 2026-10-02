package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import zio.{IO, Runtime, Semaphore, Task, Unsafe, ZIO}

/** Drives the workflow, integration controller and lineage tracker of a real attached host against an in-process server with the driver
  * core, real Git and a guardian-supervised Git job (D101). Dispatch commands take the gateway's path: authorize, execute, observe. */
final class DriverIntegrationProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, GuardianTestPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource] },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]],
      DIKey[IntegrationService[IO]], DIKey[DriverRegistry]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Target = "refs/heads/integration"

  /** What the host's collector does before an integration request reaches the server: a test holds or drops the request here. */
  private final class Collector { @volatile var before: HostIntegrationInput => Unit = _ => () }

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any],
    collector: Collector) extends ServerApi {
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrowFiberFailure() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = execute(application.upload(authority, value))
    override def usage(value: HostUsageInput): HostUsageResult = execute(application.ingest(authority, value))
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = { collector.before(value); execute(application.integrate(authority, value)) }
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final case class Fixture(local: LocalWorkspaceFixture, owner: Scope, authority: SupervisorAuthority, controller: IntegrationController,
    combinations: CombinationController, workflow: AttachedWorkflow, driver: AttachedDriver, registry: DriverRegistry, collector: Collector,
    task: ItemId, reviewer: ArtifactId, candidate: GitCommit, fence: Fence) {
    val key: DriverKey = DriverKey(Harness.Codex, "driver-integration-" + UUID.randomUUID())
    val advance: WorkflowRequest = WorkflowRequest.Advance(Set(task), WorkflowPhase.Integrate)
    def target: GitCommit = GitCommit(local.git(local.source, "show-ref", "--verify", "--hash", Target))
    def commit(name: String, files: Map[String, String]): GitCommit = {
      val directory = local.directory.resolve(name + "-" + UUID.randomUUID())
      local.git(local.source, "worktree", "add", "--detach", directory.toString, local.base.value)
      files.foreach((file, text) => Files.writeString(directory.resolve(file), text))
      local.git(directory, "add", "--all")
      local.git(directory, "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "--quiet", "-m", name)
      GitCommit(local.git(directory, "rev-parse", "HEAD"))
    }

    private def control(origin: DriverOrigin, action: DriverControl): Task[DriverReply] = ZIO.attemptBlocking {
      authority.root.call(Command.Driver(DriverInput(owner.project, DriverRequest.Control(key, origin, action)))) match {
        case Result.Driver(reply) => reply
        case Result.Failed(fault) => throw DomainFailure(fault)
        case other => throw new IllegalStateException("Unexpected driver result " + other)
      }
    }
    /** The operator's drive command and the session's bind, as the hook and the command body make them. */
    def drive: Task[Unit] = control(DriverOrigin.UserPromptSubmit, DriverControl.Start(WorksetTarget.Inline(Set(task), WorkflowPhase.Integrate), None)).flatMap {
      case DriverReply.Started(_, _, Some(token), _) => ZIO.attemptBlocking(driver.session.bind(token)).unit
      case other => ZIO.fail(new IllegalStateException("Expected a binding driver: " + other))
    }
    def park: Task[DriverReply] = control(DriverOrigin.UserPromptSubmit, DriverControl.Park())
    /** The continuation query the Stop hook makes when a turn ends. */
    def continuation: Task[DriverReply] = control(DriverOrigin.Stop, DriverControl.Continue())
    def directive: Task[DriverDirective] = continuation.flatMap {
      case DriverReply.Continue(value, _, _) => ZIO.succeed(value)
      case other => ZIO.fail(new IllegalStateException("Expected a directive: " + other))
    }
    def status: Task[DriverStatus] = control(DriverOrigin.StatusLine, DriverControl.Status()).flatMap {
      case DriverReply.Status(Some(value)) => ZIO.succeed(value)
      case other => ZIO.fail(new IllegalStateException("Expected a driver status: " + other))
    }
    /** The session submitting a directive: the advance command activates the workflow with the directive's token. */
    def activate(value: DriverDirective): Task[WorkflowActivation] = workflow.activate(RequestId(UUID.randomUUID()), advance, "Drive the task", Some(value.token))
    /** A drive whose first cycle has started. */
    def driven: Task[WorkflowActivation] = drive *> directive.flatMap(activate)

    /** One `dispatch` tool call as the attached gateway makes it: authorization, the host operation, then the driver's observation of the reply. */
    def dispatch(command: DispatchCommand): Task[DispatchReply] = for {
      _ <- ZIO.attemptBlocking(workflow.authorize(command))
      reply <- command match {
        case DispatchCommand.PrepareIntegration(id, result) => controller.prepare(IntegrationTicket(id, result)).map(DispatchReply.Integration.apply)
        case DispatchCommand.Integrate(id) => controller(id).map(DispatchReply.Integration.apply)
        case DispatchCommand.Combine(id, source, held) => combinations.prepare(CombinationTicket(id, source, held)).map(DispatchReply.Combination.apply)
        case other => ZIO.fail(new IllegalStateException("Unexpected dispatch command " + other))
      }
      _ <- driver.observe(workflow.current, command, reply)
    } yield reply
    def settled(id: IntegrationId): Task[IntegrationStatus] = controller.status(id, 20000)
      .repeatUntil(status => !Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(status.phase))
      .timeoutFail(new IllegalStateException("Integration did not settle"))(zio.Duration.fromSeconds(90))
    def prepared: Task[IntegrationStatus] = {
      val id = IntegrationId(UUID.randomUUID())
      dispatch(DispatchCommand.PrepareIntegration(id, reviewer)) *> settled(id)
    }
    def integrate(id: IntegrationId): Task[IntegrationStatus] = dispatch(DispatchCommand.Integrate(id)) *> settled(id)

    /** The server's record of the latest cycle, which holds what the status does not show: the members resting on the session. */
    def cycle: Option[CycleRecord] = registry.get(owner.project, key).flatMap(_.cycle)
    def lineage: String = cycle.fold("no cycle")(value => s"cycle ${value.number} ${value.state}: in flight ${value.inFlight.map(entry => DriverPolicy.member(entry.member))}, " +
      s"resting ${value.held.toList.map(DriverPolicy.member)}, settled ${value.lineage.filter(_.settled).map(entry => DriverPolicy.member(entry.member))}")
    def eventually(what: String)(holds: => Boolean): Task[Unit] = (ZIO.sleep(zio.Duration.fromMillis(50)) *> ZIO.attempt(holds)).repeatUntil(identity)
      .timeoutFail(new IllegalStateException(s"Not reached: $what; $lineage"))(zio.Duration.fromSeconds(30)).unit
    /** An earlier drive that prepared an integration and was parked before applying it, followed by the next drive up to its start directive. */
    def carried: Task[(IntegrationStatus, DriverDirective)] = for {
      _ <- driven
      ready <- prepared
      _ <- ZIO.attempt(assert(ready.phase == IntegrationPhase.Ready && ready.blocker.isEmpty, ready.toString))
      _ <- eventually("the prepared integration rests on the session")(cycle.exists(_.held == Set(LineageMember.Integration(ready.id))))
      _ <- park
      _ <- drive
      start <- directive
    } yield (ready, start)
    def describe(value: DriverStatus): String = s"driver ${value.state}, cycle ${value.cycle.map(cycle => s"${cycle.number} ${cycle.state}")}, stopped ${value.stopped}"
  }

  /** One Ready Task claimed by the governing session, with an admitted worker result and an accepting review of a candidate that fast-forwards the target. */
  private def fixture(local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO],
    usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO],
    proposals: ProposalService[IO], registry: DriverRegistry)(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Driver integration")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "driver-integration-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth)
    val limits = HostLimits(5000, 900, 100, 1000, 262144)
    val hook = new Collector
    def publish(value: ChildResult): Task[ArtifactId] = for {
      artifact <- artifacts.upload(collector, ArtifactUpload(owner.project, NativeArtifacts.id(value.attempt, "result"), value.attempt, ArtifactKind.Result,
        "application/json", Wire.encode(ChildResult_JsonCodec, value)))
      admitted <- admissions.admit(collector, HostAdmissionInput(owner.project, artifact.id, owner.actor))
      _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
    } yield artifact.id
    for {
      runtime <- ZIO.runtime[Any]
      _ <- ledger.initialize(owner, project.name)
      created <- MilestoneFixture.assigned(ledger, owner, List(ItemDraft("Task", "Implement", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Verified"), None, Nil), Nil)))
      claim <- ledger.acquire(owner, ClaimId(uuid), created.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governor <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture-provider", "fixture-model", "fixture", clock.millis(), UsagePhase.Govern))
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "driver-integration-"))
      profile = HarnessSetting(Harness.Codex, directory.resolve("fixture-harness").toString, "fixture-model", "fixture-provider", "0.156.1", Nil, Set.empty)
      settings = SupervisorSettings(directory.toString, guardian.binary.toString, List(profile), limits, Nil, None, Some(Target))
      run = SupervisorRun(project, assignment, governor, profile.version, local.source.toString, local.base, SessionOwnership.Attached)
      config = SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, directory, "", None, guardian.environment)
      collectorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, collector.actor, expires)).value, None)
      governorAuthority = auth.authenticate(auth.grant(root, GrantRequest(owner.project, owner.actor, expires)).value, None)
      quiet = new Collector
      authority = SupervisorAuthority(new Receiver(application, auth, root, root, runtime, quiet),
        new Receiver(application, auth, root, collectorAuthority, runtime, hook), new Receiver(application, auth, root, governorAuthority, runtime, quiet),
        AccessToken("governor", expires))
      jobs <- JobSupervisor.acquire(config.owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project.project, owner.actor.session)),
        local.fixture.service, new GuardianDriver(guardian.binary), directory.resolve("payload"), clock)
      renewal = new ClaimRenewal(ClaimRenewal.Default, logstage.IzLogger.NullLogger)
      candidates = new CandidateWorkspace(config)
      admission <- Semaphore.make(1)
      controller <- ZIO.acquireRelease(ZIO.succeed(new IntegrationController(config, authority, jobs, candidates, renewal, clock, admission)))(_.shutdown.orDie)
      access = new LocalAccess
      _ <- ZIO.succeed(access.bind(URI.create("http://127.0.0.1:1")))
      runner = new ChildRunner(config, authority, new HarnessRegistry(Set(new ClaudeAdapter, new CodexAdapter, new PiAdapter)), jobs, local.fixture.service,
        new AgentCatalog(new McpSchemas, new ChildInstructions), new HarnessOutput, candidates, new WorkspaceReader, access, new OperatorRequirements(""), renewal, clock)
      children <- ZIO.acquireRelease(ZIO.succeed(new DispatchController(config, runner, jobs, clock)))(_.shutdown.orDie)
      combinations <- ZIO.acquireRelease(ZIO.succeed(new CombinationController(config, authority, candidates, clock)))(_.shutdown.orDie)
      requests <- Semaphore.make(1)
      revalidating <- Semaphore.make(1)
      revalidations <- ZIO.acquireRelease(ZIO.succeed(new RevalidationController(config, authority, jobs, children, renewal, clock, requests, revalidating)))(_.shutdown.orDie)
      driver = new AttachedDriver(config, authority, children, controller, combinations, logstage.IzLogger.NullLogger)
      workflow = new AttachedWorkflow(config, authority, new WorkflowAssets, new WorkflowExecution(authority.governor, owner.project, owner.actor.session, None),
        new OperatorRequirements(""), children, controller, combinations, revalidations, driver)
      empty = Fixture(local, owner, authority, controller, combinations, workflow, driver, registry, hook, created.head.id, ArtifactId(uuid), local.base, claim.fence)
      candidate <- ZIO.attemptBlocking {
        local.git(local.source, "branch", "integration", local.base.value)
        empty.commit("candidate", Map("right.txt" -> "right\n"))
      }
      workerAssignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, claim.members, Attribution.Direct, None, None))
      workerAttempt <- usage.start(collector, governor.copy(id = AttemptId(uuid), assignment = workerAssignment.id, parent = Some(governor.id), role = Role.Worker, phase = UsagePhase.Work))
      _ <- ZIO.attemptBlocking(local.git(local.source, "update-ref", "refs/cq/candidates/" + workerAttempt.id.value, candidate.value))
      request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, created, Nil, Nil, None, claim.fence, limits)
      worker = ChildResult(workerAttempt.id, request, local.base, Some(candidate),
        ChildReport.Work(created.map(ref => WorkMember(ref.id, WorkDisposition.CandidateReady, "Ready", Nil))), Nil, RetainedEvidence(Nil, Nil))
      workerArtifact <- publish(worker)
      // The worker was a child of this session: its dispatch ticket names the assignment its host spans belong to.
      _ <- ZIO.attemptBlocking {
        val child = directory.resolve("children").resolve(workerAttempt.id.value.toString)
        HostFiles.directory(child)
        HostFiles.immutable(child.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, DispatchTicket(request, workerAssignment, workerAttempt, profile, None)), 65536)
      }
      reviewAssignment <- usage.assign(collector, workerAssignment.copy(id = AssignmentId(uuid)))
      reviewAttempt <- usage.start(collector, workerAttempt.copy(id = AttemptId(uuid), assignment = reviewAssignment.id, role = Role.Reviewer, phase = UsagePhase.Review))
      review = ChildResult(reviewAttempt.id, request.copy(request = RequestId(uuid), work = DispatchWork.Reviewer(ReviewerMode.Candidate), previous = Some(workerArtifact)),
        candidate, Some(candidate), ChildReport.Review(created.map(ref => ReviewMember(ref.id, ReviewVerdict.Accepted, Nil)), None), Nil, RetainedEvidence(Nil, Nil))
      reviewArtifact <- publish(review)
      _ <- test(empty.copy(reviewer = reviewArtifact, candidate = candidate))
    } yield ()
  }

  private def refusal[A](result: Either[Throwable, A]): String = result.fold(error => Option(error.getMessage).getOrElse(error.toString), value => "accepted: " + value)
  private def held(reply: DriverReply): Boolean = reply match {
    case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) => detail.contains("a resume directive did not resolve it")
    case _ => false
  }
  private def brief(reply: DriverReply): String = reply match {
    case DriverReply.Continue(directive, _, _) => "Continue with " + directive.token.getClass.getSimpleName + " token"
    case DriverReply.Stop(stopped, _, _) => s"Stop ${stopped.reason}: ${stopped.detail}"
    case other => other.toString
  }

  /** Up to the one resume directive for the prepared integration, which the session accepts. */
  private def resumed(f: Fixture): Task[LineageMember] = for {
    _ <- f.driven
    ready <- f.prepared
    member = LineageMember.Integration(ready.id)
    _ <- f.eventually("the prepared integration rests on the session")(f.cycle.exists(_.held == Set(member)))
    prompted <- f.directive
    _ <- ZIO.attempt(assert(prompted.token.isInstanceOf[CycleToken.Resume], prompted.toString))
    _ <- f.activate(prompted)
  } yield member
  private def finished(f: Fixture, member: LineageMember.Integration, label: String): Task[Unit] = for {
    recorded <- f.settled(member.id)
    _ <- f.eventually("the applied integration is settled in its cycle")(f.cycle.exists(_.lineage.exists(entry => entry.member == member && entry.settled)))
    last <- f.continuation
    _ <- ZIO.attempt {
      println(s"$label: integration finished ${recorded.phase}; ${f.lineage}; the next continuation query: ${brief(last)}")
      assert(recorded.phase == IntegrationPhase.Recorded && !held(last), s"$recorded $last")
    }
  } yield ()

  "A driven session's integrations (Behavioral Active Blackbox; real Git, supervised processes and in-process server Communication)" should {
    "D101: settle the integration an earlier drive left Ready while the next drive's start directive is pending, then start that directive" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        (ready, start) <- f.carried
        refused <- f.activate(start).either
        waiting <- f.status
        authorized <- ZIO.attemptBlocking(f.workflow.authorize(DispatchCommand.Integrate(ready.id))).either
        applied <- f.integrate(ready.id)
        after <- f.status
        git <- ZIO.attemptBlocking(f.target)
        record <- integrations.get(f.owner, ready.id)
        started <- f.activate(start).either
        running <- f.status
        task <- ledger.get(f.owner, f.task).map(_.item.draft.content.asInstanceOf[Content.Task])
        _ <- ZIO.attempt {
          println(s"Carried integration, Recorded path: start directive refused with '${refusal(refused)}'; before Integrate ${f.describe(waiting)}; " +
            s"Integrate authorization ${refusal(authorized.map(_ => "admitted"))}; integration phase ${applied.phase}, blocker ${applied.blocker}, " +
            s"server resolution ${record.resolution.getClass.getSimpleName}; Git target ${git.value.take(7)} (candidate ${f.candidate.value.take(7)}, base ${local.base.value.take(7)}); " +
            s"after Integrate ${f.describe(after)}; start directive then '${refusal(started.map(_.cycle))}'; finally ${f.describe(running)}; task ${task.status}")
          assert(refused.left.exists(_.getMessage.contains("Settle active child/check/integration/combination work before changing workflow")), refused.toString)
          assert(waiting.state == DriverState.On && waiting.cycle.exists(cycle => cycle.id == start.cycle && cycle.state == CycleState.Pending), waiting.toString)
          assert(authorized.isRight, authorized.toString)
          assert(applied.phase == IntegrationPhase.Recorded && applied.blocker.isEmpty && record.resolution.isInstanceOf[IntegrationResolution.Recorded], applied.toString)
          assert(git == f.candidate && task.status == TaskStatus.Done)
          assert(after.state == DriverState.On && after.stopped.isEmpty && after.cycle.exists(_.state == CycleState.Pending), after.toString)
          assert(started.exists(_.cycle.contains(start.cycle)), started.toString)
          assert(running.state == DriverState.On && running.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.run.nonEmpty), running.toString)
        }
      } yield () }
    }

    "D101: settle a carried-over integration that Git refuses as NotApplied without stopping the next drive" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        (ready, start) <- f.carried
        // The target advances before the carried-over integration is applied: Git refuses the update and nothing is written to the ledger.
        advanced <- ZIO.attemptBlocking {
          val other = f.commit("target", Map("left.txt" -> "left\n"))
          local.git(local.source, "update-ref", Target, other.value, local.base.value)
          other
        }
        refused <- f.integrate(ready.id)
        after <- f.status
        git <- ZIO.attemptBlocking(f.target)
        // Combining it is new work: it waits for the workflow the start directive starts, and that workflow did not prepare this integration.
        early <- f.dispatch(DispatchCommand.Combine(RequestId(uuid), ready.id, f.fence)).either
        started <- f.activate(start).either
        running <- f.status
        late <- f.dispatch(DispatchCommand.Combine(RequestId(uuid), ready.id, f.fence)).either
        _ <- ZIO.attempt {
          println(s"Carried integration, NotApplied path: integration phase ${refused.phase}, blocker ${refused.blocker}; Git target ${git.value.take(7)} " +
            s"(advanced to ${advanced.value.take(7)}, candidate ${f.candidate.value.take(7)}); after Integrate ${f.describe(after)}; " +
            s"Combine before the start directive '${refusal(early)}'; start directive then '${refusal(started.map(_.cycle))}'; finally ${f.describe(running)}; " +
            s"Combine after it '${refusal(late)}'")
          assert(refused.phase == IntegrationPhase.NotApplied && git == advanced, refused.toString)
          assert(after.state == DriverState.On && after.cycle.exists(_.state == CycleState.Pending), after.toString)
          assert(early.left.exists(_.getMessage.contains("start directive is pending: a new combination")), early.toString)
          assert(started.exists(_.cycle.contains(start.cycle)) && running.cycle.exists(_.state == CycleState.Active), s"$started $running")
          assert(late.left.exists(_.getMessage.contains("Combination belongs to a previous workflow activation")), late.toString)
        }
      } yield () }
    }

    "D101: leave a carried-over integration Pending when its acknowledgement is lost and record it when it is applied again" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        (ready, start) <- f.carried
        // The first acknowledgement of the Git observation is lost in transit: the integration stays Pending and is applied again.
        dropped = new AtomicBoolean(false)
        _ <- ZIO.succeed { f.collector.before = input => input.operation match {
          case _: HostIntegration.Observe if dropped.compareAndSet(false, true) => throw new IOException("Connection reset")
          case _ => ()
        }}
        pending <- f.integrate(ready.id)
        between <- f.status
        gitBetween <- ZIO.attemptBlocking(f.target)
        recorded <- f.integrate(ready.id)
        after <- f.status
        started <- f.activate(start).either
        running <- f.status
        _ <- ZIO.attempt {
          println(s"Carried integration, Pending path: first Integrate phase ${pending.phase}, blocker ${pending.blocker}, Git target ${gitBetween.value.take(7)} " +
            s"(candidate ${f.candidate.value.take(7)}), ${f.describe(between)}; second Integrate phase ${recorded.phase}, blocker ${recorded.blocker}, ${f.describe(after)}; " +
            s"start directive then '${refusal(started.map(_.cycle))}'; finally ${f.describe(running)}")
          assert(pending.phase == IntegrationPhase.Pending && pending.blocker.exists(_.contains("IOException")) && gitBetween == f.candidate, pending.toString)
          assert(between.state == DriverState.On && between.cycle.exists(_.state == CycleState.Pending), between.toString)
          assert(recorded.phase == IntegrationPhase.Recorded && after.state == DriverState.On && after.cycle.exists(_.state == CycleState.Pending), s"$recorded $after")
          assert(started.exists(_.cycle.contains(start.cycle)) && running.cycle.exists(_.state == CycleState.Active), s"$started $running")
        }
      } yield () }
    }

    "D101: keep everything but the carried-over integration's settlement refused while the next drive's start directive is pending" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f =>
        val other = IntegrationId(uuid)
        for {
          (ready, start) <- f.carried
          // Integration work that is not the carried-over integration: a new preparation, another integration's application and combinations.
          prepare <- f.dispatch(DispatchCommand.PrepareIntegration(other, f.reviewer)).either
          unknown <- f.controller.status(other, 0).either
          integrate <- f.dispatch(DispatchCommand.Integrate(other)).either
          combine <- f.dispatch(DispatchCommand.Combine(RequestId(uuid), other, f.fence)).either
          combineCarried <- f.dispatch(DispatchCommand.Combine(RequestId(uuid), ready.id, f.fence)).either
          still <- f.status
          carried <- f.controller.status(ready.id, 0)
          _ <- ZIO.attempt {
            println(s"Pending cycle restrictions: PrepareIntegration of another integration '${refusal(prepare)}'; Integrate of it '${refusal(integrate)}'; " +
              s"Combine of it '${refusal(combine)}'; a new Combine of the carried-over integration '${refusal(combineCarried)}'; then ${f.describe(still)}, " +
              s"carried-over integration ${carried.phase}")
            assert(List(prepare, integrate, combine, combineCarried).forall(_.isLeft) && unknown.isLeft, s"$prepare $integrate $combine $combineCarried $unknown")
            assert(prepare.left.exists(_.getMessage.contains("start directive")) && integrate.left.exists(_.getMessage.contains("Integration is not prepared in this workflow activation")))
            assert(still.state == DriverState.On && still.cycle.exists(cycle => cycle.id == start.cycle && cycle.state == CycleState.Pending) && carried.phase == IntegrationPhase.Ready)
          }
          // Any other ledger write of the bound session is still an untracked mutation: nothing is written and the drive stops.
          before <- ledger.counts(f.owner).map(_.cursor)
          current <- ledger.get(f.owner, f.task)
          write <- ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(f.task, current.item.revision,
            current.item.draft.copy(title = "Written while the start directive is pending"))), List(f.fence), "Untracked write")).either
          cursor <- ledger.counts(f.owner).map(_.cursor)
          stopped <- f.status
          left <- f.controller.status(ready.id, 0)
          git <- ZIO.attemptBlocking(f.target)
          _ <- ZIO.attempt {
            println(s"Pending cycle restrictions: another ledger write '${refusal(write)}'; then ${f.describe(stopped)}; carried-over integration ${left.phase}; " +
              s"Git target ${git.value.take(7)} (base ${local.base.value.take(7)})")
            assert(write.left.exists { case DomainFailure(_: Fault.Denied) => true; case _ => false } && cursor == before, write.toString)
            assert(stopped.state == DriverState.Off && stopped.stopped.exists(value => value.reason == DriverStop.Failure && value.detail.startsWith("untracked mutation")), stopped.toString)
            assert(left.phase == IntegrationPhase.Ready && git == local.base)
          }
        } yield ()
      }
    }

    "D101: count an integration applied as the last action of a turn as in flight at the next continuation query" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      // The turn ends at once after Integrate: the query arrives while the Git job runs and before the tracker polls again.
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        member <- resumed(f).map(_.asInstanceOf[LineageMember.Integration])
        applied <- f.dispatch(DispatchCommand.Integrate(member.id))
        observed = f.lineage
        decision <- f.continuation
        phase <- f.controller.status(member.id, 0).map(_.phase)
        _ <- ZIO.attempt(println(s"Integrate as the last action: Integrate returned ${applied match { case DispatchReply.Integration(value) => value.phase; case other => other }}; " +
          s"at the query $observed, integration phase $phase; continuation query: ${brief(decision)}"))
        _ <- ZIO.attempt(assert(decision.isInstanceOf[DriverReply.Continue] && !observed.contains("resting List(integration"), s"$decision; $observed"))
        _ <- finished(f, member, "Integrate as the last action")
      } yield () }
    }

    "D101: keep a slowly applied integration in flight at every continuation query until it settles" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverRegistry) =>
      // A slow application: the server's reservation of the integration is held, so the integration stays Running past the tracker's poll pause.
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f =>
        val release = new CountDownLatch(1)
        (for {
          member <- resumed(f).map(_.asInstanceOf[LineageMember.Integration])
          _ <- ZIO.succeed { f.collector.before = _ => release.await() }
          applied <- f.dispatch(DispatchCommand.Integrate(member.id))
          observed = f.lineage
          decision <- f.continuation
          phase <- f.controller.status(member.id, 0).map(_.phase)
          _ <- ZIO.sleep(zio.Duration.fromMillis(2500))
          later = f.lineage
          again <- f.continuation
          phaseLater <- f.controller.status(member.id, 0).map(_.phase)
          _ <- ZIO.attempt(println(s"Slow application: Integrate returned ${applied match { case DispatchReply.Integration(value) => value.phase; case other => other }}; " +
            s"at the query $observed, integration phase $phase; continuation query: ${brief(decision)}; 2.5 s later $later, integration phase $phaseLater; " +
            s"continuation query: ${brief(again)}"))
          _ <- ZIO.succeed(release.countDown())
          _ <- ZIO.attempt(assert(phase == IntegrationPhase.Running && phaseLater == IntegrationPhase.Running && decision.isInstanceOf[DriverReply.Continue] &&
            again.isInstanceOf[DriverReply.Continue] && !observed.contains("resting List(integration") && !later.contains("resting List(integration"),
            s"$decision; $again; $observed; $later"))
          _ <- finished(f, member, "Slow application")
        } yield ()).ensuring(ZIO.succeed(release.countDown()))
      }
    }
  }
}
