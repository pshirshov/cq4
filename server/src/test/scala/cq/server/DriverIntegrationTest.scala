package cq.server

import cq.api.*
import cq.core.*
import cq.core.DriverRecords.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import io.circe.{Json, parser}
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import zio.{IO, Runtime, Semaphore, Task, Unsafe, ZIO}

/** Drives the workflow, integration controller and lineage tracker of a real attached host against an in-process server with the driver
  * core, real Git and a guardian-supervised Git job (D101). Dispatch commands take the gateway's path: authorize, execute, observe. */
final class DriverIntegrationProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin, GuardianTestPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource]; make[DriverInspector] },
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]],
      DIKey[IntegrationService[IO]], DIKey[DriverInspector]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Target = "refs/heads/integration"

  /** What the host's collector does before an integration request reaches the server: a test holds or drops the request here. */
  private final class Collector {
    @volatile var before: HostIntegrationInput => Unit = _ => ()
    @volatile var upload: ArtifactUpload => Unit = _ => ()
    val spans = new java.util.concurrent.CopyOnWriteArrayList[PhaseSpan]()
  }

  private final class Receiver(application: Application, auth: Authorization, root: Authority, authority: Authority, runtime: Runtime[Any],
    collector: Collector) extends ServerApi {
    // As the HTTP client does, a refusal is thrown as the DomainFailure itself.
    private def execute[A](value: Task[A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(value).getOrThrow() }
    override def call(command: Command): Result = execute(application.execute(authority, command))
    override def artifact(value: ArtifactUpload): ArtifactMetadata = { collector.upload(value); execute(application.upload(authority, value)) }
    override def usage(value: HostUsageInput): HostUsageResult = {
      value.operation match { case HostUsage.Span(span) => collector.spans.add(span); case _ => () }
      execute(application.ingest(authority, value))
    }
    override def admit(value: HostAdmissionInput): ResultAdmission = execute(application.admit(authority, value))
    override def integrate(value: HostIntegrationInput): IntegrationRecord = { collector.before(value); execute(application.integrate(authority, value)) }
    override def grant(value: GrantRequest): AccessToken = auth.grant(root, value)
  }

  private final case class Fixture(local: LocalWorkspaceFixture, owner: Scope, authority: SupervisorAuthority, controller: IntegrationController,
    combinations: CombinationController, workflow: AttachedWorkflow, driver: AttachedDriver, registry: DriverInspector, collector: Collector,
    task: ItemId, reviewer: ArtifactId, candidate: GitCommit, fence: Fence, session: java.nio.file.Path, gateway: Json => Task[Json]) {
    /** What the host wrote for waiters about one unit, in order: its starts and the phases it ended in. */
    def unitEvents(id: UUID): List[String] = SessionUnits.read(session).collect {
      case SessionUnitEvent.Started(unit) if unit.id == id => "Started"
      case SessionUnitEvent.Ended(end) if end.unit.id == id => end.phase
    }
    /** One `session` tool call through the attached gateway: the reply its owner receives. */
    def sessionTool(arguments: String): Task[Json] = gateway(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(1), "method" -> Json.fromString("tools/call"),
      "params" -> Json.obj("name" -> Json.fromString("session"), "arguments" -> parser.parse(arguments).fold(throw _, identity))))
      .map(_.hcursor.downField("result").downField("structuredContent").focus.get)
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
        case DispatchCommand.DiscardIntegration(id) => controller.discard(id).map(DispatchReply.Integration.apply)
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
    def combined(id: RequestId): Task[CombinationStatus] = combinations.status(id, 20000).repeatUntil(_.phase != CombinationPhase.Preparing)
      .timeoutFail(new IllegalStateException("Combination did not settle"))(zio.Duration.fromSeconds(90))

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
    proposals: ProposalService[IO], registry: DriverInspector)(test: Fixture => Task[Unit]): Task[Unit] = ZIO.scoped {
    val clock = Clock.systemUTC()
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Driver integration")
    val owner = Scope(project.project, Actor("CQ governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "CQ host collector", role = Role.Collector))
    val token = "driver-integration-root-token-" + uuid
    val auth = new Authorization(AccessConfig(token, "http://localhost"), clock)
    val root = auth.authenticate(token, Some(owner.actor.session.value.toString))
    val expires = clock.millis() + 60L * 60 * 1000
    val application = new Application(ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
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
      // The gateway of a Claude Code session: its Context reads no native Codex usage.
      attached = config.copy(run = run.copy(attempt = governor.copy(harness = Harness.Claude)))
      served = new AttachedGateway(attached, authority, new McpSchemas, null, workflow, null, null, driver, new SessionClaims(config.owner, authority.governor, logstage.IzLogger.NullLogger),
        WaitCommand(Some("/opt/cq/bin/cq")))
      idle = java.time.Duration.ofMinutes(10)
      peer <- ZIO.acquireRelease(ZIO.attempt(new StdioPeer(new java.io.PipedInputStream(new java.io.PipedOutputStream()), java.io.OutputStream.nullOutputStream(),
        new OwnerLiveness { override def alive: Boolean = true }, PeerLimits(idle, idle, idle, idle, AttachedGateway.FrameBytes, 8), () => ())))(peer => ZIO.succeed(peer.close()))
      gateway = (request: Json) => served.handle(peer, request).map(_.get)
      _ <- gateway(parser.parse("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").fold(throw _, identity))
      empty = Fixture(local, owner, authority, controller, combinations, workflow, driver, registry, hook, created.head.id, ArtifactId(uuid), local.base, claim.fence, directory, gateway)
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

  "An attached session's workflow replies (Behavioral Active Blackbox; in-process server Communication)" should {
    "I33: name the active workflow in Context, send each instruction text once, echo no requirements and return the whole activation on request" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f =>
        val Requirements = "Operator requirements of the fixture: keep every change inside the selected scope."
        val item = s"""{"project":{"value":"${f.owner.project.value}"},"ledger":"${f.task.ledger}","number":"${f.task.number}"}"""
        def activation(id: UUID, request: String): String =
          s"""{"Workflow":{"id":{"value":"$id"},"request":$request,"operatorRequirements":${Json.fromString(Requirements).noSpaces},"token":null}}"""
        val advance = s"""{"Advance":{"roots":[$item],"through":"Integrate"}}"""
        val (first, second, third) = (uuid, uuid, uuid)
        def stored(id: UUID): Json = parser.parse(Files.readString(f.session.resolve("workflows").resolve(s"$id.json"))).fold(throw _, identity)
        def size(value: Json): Int = value.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        for {
          before <- f.sessionTool("""{"Context":{}}""")
          _ <- assertIO(before.hcursor.downField("Context").downField("value").downField("workflow").focus.contains(Json.Null))
          missing <- f.sessionTool("""{"Instructions":{}}""")
          _ <- assertIO(missing.hcursor.downField("Failed").downField("fault").downField("Missing").get[String]("message").exists(_.contains("No workflow is active")))
          one <- f.sessionTool(activation(first, advance)).map(_.hcursor.downField("Workflow").downField("value"))
          text = one.downField("instructions").downField("Text").get[String]("value").fold(throw _, identity)
          _ <- ZIO.attempt {
            // The receipt names the activation and carries its text; what the session wrote in the call is not sent back.
            assert(text == stored(first).hcursor.downField("context").get[String]("instructions").fold(throw _, identity) && text.length > 8000, text.take(200))
            assert(one.keys.map(_.toSet).contains(Set("id", "request", "instructions", "subject", "cycle")), one.focus.get.noSpaces.take(300))
            assert(!one.focus.get.noSpaces.contains(Requirements) && one.downField("id").get[UUID]("value") == Right(first))
          }
          context <- f.sessionTool("""{"Context":{}}""")
          active = context.hcursor.downField("Context").downField("value").downField("workflow").focus.get
          _ <- ZIO.attempt {
            assert(active == parser.parse(s"""{"id":{"value":"$first"},"request":$advance,"cycle":null}""").fold(throw _, identity), active.noSpaces)
            // The base governing instructions stay in every Context; the workflow's text and the requirements do not come with it.
            assert(context.hcursor.downField("Context").downField("value").get[String]("instructions").exists(_.contains(SupervisorProgram.Guidance)))
            assert(!context.noSpaces.contains(Requirements) && size(context) < text.length, s"${size(context)} bytes")
          }
          again <- f.sessionTool(activation(second, advance)).map(_.hcursor.downField("Workflow").downField("value"))
          retried <- f.sessionTool(activation(first, advance)).map(_.hcursor.downField("Workflow").downField("value"))
          _ <- ZIO.attempt {
            val marker = parser.parse(s"""{"Unchanged":{"since":{"value":"$first"}}}""").fold(throw _, identity)
            // The same text again is named by the activation whose reply carried it. That activation's own call, repeated, gets the text:
            // a session that repeats it may not have received the first reply, and cannot hold what it would be referred to.
            assert(again.downField("instructions").focus.contains(marker) && again.downField("id").get[UUID]("value") == Right(second), again.focus.get.noSpaces.take(300))
            assert(retried.focus == one.focus && retried.downField("instructions").downField("Text").get[String]("value") == Right(text))
            assert(size(again.focus.get) < 1000 && stored(second).hcursor.downField("context").get[String]("instructions") == Right(text))
          }
          whole <- f.sessionTool("""{"Instructions":{}}""")
          // A session that no longer holds the text gets the stored activation as it is, requirements included.
          _ <- assertIO(whole.hcursor.downField("Instructions").downField("value").focus.contains(stored(second)) && whole.noSpaces.contains(Requirements))
          other <- f.sessionTool(activation(third, s"""{"Begin":{"roots":[$item]}}""")).map(_.hcursor.downField("Workflow").downField("value"))
          // A different text is sent in full.
          _ <- assertIO(other.downField("instructions").downField("Text").get[String]("value").exists(value => value != text && value.length > 8000))
          _ <- ZIO.attempt(println(s"I33 session replies: first Workflow ${size(one.focus.get)} bytes, identical-text Workflow ${size(again.focus.get)} bytes, Context with an active workflow ${size(context)} bytes"))
        } yield ()
      }
    }
  }

  "A driven session's integrations (Behavioral Active Blackbox; real Git, supervised processes and in-process server Communication)" should {
    "D101: settle the integration an earlier drive left Ready while the next drive's start directive is pending, then start that directive" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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

    "D144: settle an integration whose reservation the server refuses as NotApplied, admit the next workflow and integrate the candidate under a new claim" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        _ <- f.workflow.activate(RequestId(uuid), f.advance, "Integrate the task", None)
        ready <- f.prepared
        // The session releases the claim the integration was frozen with before it applies the integration.
        _ <- ledger.release(f.owner, f.fence)
        refused <- f.integrate(ready.id)
        quiescent <- ZIO.succeed(f.controller.quiescent)
        retained <- ZIO.attemptBlocking(HostFiles.read(f.session.resolve("integrations").resolve(ready.id.value.toString + ".json"), IntegrationLocal_JsonCodec,
          IntegrationEntries.MaxRecordBytes))
        server <- integrations.get(f.owner, ready.id).either
        git <- ZIO.attemptBlocking(f.target)
        next <- f.workflow.activate(RequestId(uuid), f.advance, "Integrate the task again", None).either
        _ <- ZIO.attempt {
          println(s"Refused reservation: integration phase ${refused.phase}, next ${refused.next}, blocker ${refused.blocker}; quiescent $quiescent; " +
            s"journal attempted=${retained.attempted} observation=${retained.observation}; server ${refusal(server)}; next workflow '${refusal(next.map(_.id))}'")
          assert(refused.phase == IntegrationPhase.NotApplied && refused.blocker.exists(_.contains("Integration reservation requires the current full claim")), refused.toString)
          assert(quiescent && !retained.attempted && retained.observation.exists(_.isInstanceOf[IntegrationObservation.NotApplied]), retained.toString)
          assert(server.left.exists { case DomainFailure(_: Fault.Missing) => true; case _ => false } && git == local.base, server.toString)
          assert(next.isRight, next.toString)
        }
        _ <- ledger.acquire(f.owner, ClaimId(uuid), Set(f.task), 300000)
        second <- f.prepared
        recorded <- f.integrate(second.id)
        task <- ledger.get(f.owner, f.task).map(_.item.draft.content.asInstanceOf[Content.Task])
        _ <- ZIO.attemptBlocking(assert(second.phase == IntegrationPhase.Ready && recorded.phase == IntegrationPhase.Recorded && f.target == f.candidate &&
          task.status == TaskStatus.Done && f.controller.quiescent, s"$second $recorded"))
      } yield () }
    }

    "D144: settle a prepared integration as NotApplied once another integration of the session has recorded its members, and name unsettled work in a workflow refusal" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        _ <- f.workflow.activate(RequestId(uuid), f.advance, "Integrate the task", None)
        first <- f.prepared
        second <- f.prepared
        recorded <- f.integrate(second.id)
        busy <- f.workflow.activate(RequestId(uuid), f.advance, "Next", None).either
        superseded <- f.integrate(first.id)
        next <- f.workflow.activate(RequestId(uuid), f.advance, "Next", None).either
        _ <- ZIO.attempt {
          println(s"Superseded integration: recorded ${recorded.phase}; workflow refused with '${refusal(busy)}'; first ${superseded.phase}, blocker ${superseded.blocker}; " +
            s"next workflow '${refusal(next.map(_.id))}'")
          assert(recorded.phase == IntegrationPhase.Recorded, recorded.toString)
          assert(busy.left.exists(_.getMessage == "requirement failed: Settle active child/check/integration/combination work before changing workflow: " +
            s"integration ${first.id.value} (Ready)"), busy.toString)
          assert(superseded.phase == IntegrationPhase.NotApplied && f.controller.quiescent && next.isRight, s"$superseded $next")
        }
      } yield () }
    }

    "Q52: discard a prepared integration as NotApplied, admit the next workflow and integrate the same candidate under a fresh identity" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        _ <- f.workflow.activate(RequestId(uuid), f.advance, "Integrate the task", None)
        ready <- f.prepared
        busy <- f.workflow.activate(RequestId(uuid), f.advance, "Next", None).either
        discarded <- f.dispatch(DispatchCommand.DiscardIntegration(ready.id))
        repeated <- f.dispatch(DispatchCommand.DiscardIntegration(ready.id))
        quiescent <- ZIO.succeed(f.controller.quiescent)
        retained <- ZIO.attemptBlocking(HostFiles.read(f.session.resolve("integrations").resolve(ready.id.value.toString + ".json"), IntegrationLocal_JsonCodec,
          IntegrationEntries.MaxRecordBytes))
        server <- integrations.get(f.owner, ready.id).either
        git <- ZIO.attemptBlocking(f.target)
        spans <- ZIO.succeed(f.collector.spans.toArray(Array.empty[PhaseSpan]).toList.filter(_.phase == UsagePhase.Integrate))
        applied <- f.integrate(ready.id)
        next <- f.workflow.activate(RequestId(uuid), f.advance, "Integrate the task again", None).either
        _ <- ZIO.attempt {
          println(s"Discarded integration: workflow before '${refusal(busy)}'; reply $discarded; quiescent $quiescent; journal attempted=${retained.attempted} " +
            s"observation=${retained.observation}; server ${refusal(server)}; Integrate afterwards ${applied.phase}; spans ${spans.map(_.state)}; next workflow '${refusal(next.map(_.id))}'")
          assert(busy.left.exists(_.getMessage.contains(s"integration ${ready.id.value} (Ready)")), busy.toString)
          val status = discarded match { case DispatchReply.Integration(value) => value; case other => fail(other.toString) }
          assert(status.phase == IntegrationPhase.NotApplied && status.blocker.contains("Discarded by the governing session") && repeated == discarded, s"$discarded $repeated")
          assert(quiescent && retained == IntegrationLocal(retained.intent, false, Some(IntegrationObservation.NotApplied("Discarded by the governing session"))), retained.toString)
          assert(server.left.exists { case DomainFailure(_: Fault.Missing) => true; case _ => false } && git == local.base, server.toString)
          assert(spans.map(_.state) == List(AttemptState.Cancelled), spans.toString)
          assert(applied.phase == IntegrationPhase.NotApplied && next.isRight, s"$applied $next")
          // A waiter that named the integration is told when it is prepared and again when it is discarded.
          assert(f.unitEvents(ready.id.value) == List("Started", "Ready", "NotApplied"), f.unitEvents(ready.id.value).toString)
        }
        second <- f.prepared
        recorded <- f.integrate(second.id)
        late <- f.dispatch(DispatchCommand.DiscardIntegration(second.id)).either
        task <- ledger.get(f.owner, f.task).map(_.item.draft.content.asInstanceOf[Content.Task])
        _ <- ZIO.attemptBlocking {
          println(s"Discard of a recorded integration: '${refusal(late)}'")
          assert(second.phase == IntegrationPhase.Ready && recorded.phase == IntegrationPhase.Recorded && f.target == f.candidate &&
            task.status == TaskStatus.Done && f.controller.quiescent, s"$second $recorded")
          assert(late.left.exists { case DomainFailure(Fault.Conflict(message)) => message.contains("Recorded"); case _ => false }, late.toString)
          assert(f.target == f.candidate)
        }
      } yield () }
    }

    "Q52: refuse to discard an integration that is being applied, and count a discarded one as settled for its drive's cycle" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        (ready, start) <- f.carried
        member = LineageMember.Integration(ready.id)
        refused <- f.activate(start).either
        discarded <- f.dispatch(DispatchCommand.DiscardIntegration(ready.id))
        after <- f.status
        git <- ZIO.attemptBlocking(f.target)
        started <- f.activate(start).either
        running <- f.status
        // In the cycle that started, a prepared integration rests on the session until it is discarded and is then settled in the cycle.
        resting <- f.prepared
        _ <- f.eventually("the prepared integration rests on the session")(f.cycle.exists(_.held == Set(LineageMember.Integration(resting.id))))
        _ <- f.dispatch(DispatchCommand.DiscardIntegration(resting.id))
        _ <- f.eventually("the discarded integration is settled in its cycle")(f.cycle.exists(value => value.held.isEmpty &&
          value.lineage.exists(entry => entry.member == LineageMember.Integration(resting.id) && entry.settled)))
        driving <- f.status
        second <- f.prepared
        _ <- f.dispatch(DispatchCommand.Integrate(second.id))
        active <- f.dispatch(DispatchCommand.DiscardIntegration(second.id)).either
        recorded <- f.settled(second.id)
        _ <- ZIO.attempt {
          assert(driving.state == DriverState.On && driving.stopped.isEmpty, driving.toString)
          println(s"Carried integration, discarded: start directive refused with '${refusal(refused)}'; reply $discarded; after it ${f.describe(after)}; " +
            s"start directive then '${refusal(started.map(_.cycle))}'; finally ${f.describe(running)}; discard while applying '${refusal(active)}'; then ${recorded.phase}")
          assert(refused.left.exists(_.getMessage.contains("Settle active child/check/integration/combination work before changing workflow")), refused.toString)
          assert(discarded == DispatchReply.Integration(ready.copy(phase = IntegrationPhase.NotApplied, next = IntegrationNext.Complete,
            blocker = Some("Discarded by the governing session"))) && git == local.base, discarded.toString)
          assert(after.state == DriverState.On && after.stopped.isEmpty && after.cycle.exists(_.state == CycleState.Pending), after.toString)
          assert(started.exists(_.cycle.contains(start.cycle)) && running.state == DriverState.On && running.cycle.exists(_.state == CycleState.Active), s"$started $running")
          // The Git job normally still runs here; had it finished, the integration would be Recorded and refused as well.
          assert(active.left.exists { case DomainFailure(Fault.Conflict(message)) => message.contains("is Running") || message.contains("is Recorded"); case _ => false }, active.toString)
          assert(recorded.phase == IntegrationPhase.Recorded, recorded.toString)
        }
      } yield () }
    }

    "D101: settle a carried-over integration that Git refuses as NotApplied without stopping the next drive" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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

    "D101: refuse to apply an integration no drive carried over while the next drive's start directive is pending, before Git is touched" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f => for {
        // The session prepares the integration after its drive was parked: the parked drive's activation is still the host's current one,
        // so the host owns the integration, and no cycle registered it, so the next drive does not carry it.
        _ <- f.driven
        _ <- f.park
        ready <- f.prepared
        _ <- f.drive
        start <- f.directive
        owned <- ZIO.attemptBlocking(f.driver.session.settleable)
        integrate <- f.dispatch(DispatchCommand.Integrate(ready.id)).either
        left <- f.settled(ready.id)
        git <- ZIO.attemptBlocking(f.target)
        after <- f.status
        refused <- f.activate(start).either
        _ <- ZIO.attempt {
          println(s"Prepared, not carried-over integration under a pending cycle: prepared ${ready.phase}; integrations the server lets the session settle $owned; " +
            s"Integrate '${refusal(integrate.map(_ => "admitted"))}'; integration phase ${left.phase}; Git target ${git.value.take(7)} " +
            s"(candidate ${f.candidate.value.take(7)}, base ${local.base.value.take(7)}); then ${f.describe(after)}; start directive '${refusal(refused.map(_.cycle))}'")
          assert(ready.phase == IntegrationPhase.Ready && owned.contains(Set.empty), s"$ready $owned")
          assert(integrate.left.exists { case DomainFailure(Fault.Denied(message)) => message.contains("start directive is pending") && message.contains(ready.id.value.toString); case _ => false },
            integrate.toString)
          assert(left.phase == IntegrationPhase.Ready && git == local.base, s"$left $git")
          assert(after.state == DriverState.On && after.stopped.isEmpty && after.cycle.exists(cycle => cycle.id == start.cycle && cycle.state == CycleState.Pending), after.toString)
          assert(refused.left.exists(_.getMessage.contains("Settle active child/check/integration/combination work before changing workflow")), refused.toString)
        }
        // The turn ends with the directive unused, which stops the drive; the session then applies the integration as an undriven session does.
        stopped <- f.continuation
        applied <- f.integrate(ready.id)
        gitAfter <- ZIO.attemptBlocking(f.target)
        _ <- ZIO.attempt {
          println(s"Prepared, not carried-over integration after the drive: continuation query ${brief(stopped)}; Integrate then ${applied.phase}; " +
            s"Git target ${gitAfter.value.take(7)}")
          assert(stopped match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) => detail.contains("directive not started"); case _ => false }, stopped.toString)
          assert(applied.phase == IntegrationPhase.Recorded && gitAfter == f.candidate, applied.toString)
        }
      } yield () }
    }

    "D101: refuse to publish again a combination an earlier drive left pending while the next drive's start directive is pending" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
      fixture(local, guardian, ledger, ledgerRepository, usage, artifacts, admissions, integrations, proposals, registry) { f =>
        val combination = RequestId(uuid)
        val uploads = new AtomicInteger(0)
        val failing = new AtomicBoolean(true)
        for {
          // The earlier drive: Git refuses the integration, and the combination that follows freezes its plan but cannot publish it.
          _ <- f.driven
          ready <- f.prepared
          _ <- ZIO.attemptBlocking(local.git(local.source, "update-ref", Target, f.commit("target", Map("left.txt" -> "left\n")).value, local.base.value))
          source <- f.integrate(ready.id)
          _ <- f.eventually("the refused integration is settled in its cycle")(f.cycle.exists(_.lineage.exists(entry =>
            entry.member == LineageMember.Integration(ready.id) && entry.settled)))
          _ <- ZIO.succeed { f.collector.upload = value => if (value.kind == ArtifactKind.Combination) {
            uploads.incrementAndGet()
            if (failing.get) throw new IOException("Connection reset")
          }}
          command = DispatchCommand.Combine(combination, ready.id, f.fence)
          _ <- f.dispatch(command)
          left <- f.combined(combination)
          // The integration ended twice for a waiter, once prepared and once refused by Git; each end follows its own start.
          _ <- f.eventually("the unit events of the integration and the combination")(f.unitEvents(ready.id.value) == List("Started", "Ready", "Started", "NotApplied") &&
            f.unitEvents(combination.value) == List("Started", "PublicationPending"))
          _ <- f.eventually("the unpublished combination rests on the session")(f.cycle.exists(_.held == Set(LineageMember.Combination(combination))))
          _ <- f.park
          _ <- f.drive
          start <- f.directive
          // The server would now accept the publication. The source integration is settled, so no drive carried it over.
          _ <- ZIO.succeed(failing.set(false))
          owned <- ZIO.attemptBlocking(f.driver.session.settleable)
          attempts = uploads.get
          replay <- f.dispatch(command).either
          under <- f.combined(combination)
          after <- f.status
          refused <- f.activate(start).either
          _ <- ZIO.attempt {
            println(s"Publication-pending combination under a pending cycle: source integration ${source.phase}; combination left ${left.phase}, blocker ${left.blocker}; " +
              s"integrations the server lets the session settle $owned; repeated Combine '${refusal(replay.map(_ => "admitted"))}'; combination phase ${under.phase}; " +
              s"publication attempts ${uploads.get} (before the repeated Combine $attempts); then ${f.describe(after)}; start directive '${refusal(refused.map(_.cycle))}'")
            assert(source.phase == IntegrationPhase.NotApplied && left.phase == CombinationPhase.PublicationPending && attempts == 1 && owned.contains(Set.empty), s"$source $left $attempts $owned")
            assert(replay.left.exists { case DomainFailure(Fault.Denied(message)) => message.contains("start directive is pending") && message.contains(combination.value.toString); case _ => false },
              replay.toString)
            assert(under.phase == CombinationPhase.PublicationPending && uploads.get == attempts, s"$under ${uploads.get}")
            assert(after.state == DriverState.On && after.stopped.isEmpty && after.cycle.exists(cycle => cycle.id == start.cycle && cycle.state == CycleState.Pending), after.toString)
            assert(refused.left.exists(_.getMessage.contains("Settle active child/check/integration/combination work before changing workflow")), refused.toString)
          }
          // The turn ends with the directive unused, which stops the drive; the session then publishes the combination as an undriven session does.
          stopped <- f.continuation
          _ <- f.dispatch(command)
          // The repeated Combine has written its start when it returns: a waiter named the combination is not answered by the end before it.
          restarted <- ZIO.attemptBlocking(f.unitEvents(combination.value))
          _ <- ZIO.attempt(assert(restarted.take(3) == List("Started", "PublicationPending", "Started"), restarted.toString))
          published <- f.combined(combination)
          _ <- f.eventually("the end of the repeated combination")(f.unitEvents(combination.value) == List("Started", "PublicationPending", "Started", "Ready"))
          _ <- ZIO.attempt {
            println(s"Publication-pending combination after the drive: continuation query ${brief(stopped)}; repeated Combine then ${published.phase}; " +
              s"publication attempts ${uploads.get}")
            assert(stopped match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) => detail.contains("directive not started"); case _ => false }, stopped.toString)
            assert(published.phase == CombinationPhase.Ready && published.preview.nonEmpty && uploads.get == attempts + 1, published.toString)
          }
        } yield ()
      }
    }

    "D101: count an integration applied as the last action of a turn as in flight at the next continuation query" in {
      (local: LocalWorkspaceFixture, guardian: GuardianFixture, ledger: LedgerService[IO], ledgerRepository: LedgerRepository[IO], usage: UsageService[IO],
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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
        artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], registry: DriverInspector) =>
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
