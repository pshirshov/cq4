package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, Promise, Runtime, Unsafe, ZIO}

abstract class DriverContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ProposalService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private final case class World(operator: Scope, governor: Scope) {
    val project: ProjectId = operator.project
    def other(role: Role): Scope = Scope(project, Actor("another session", SessionId(UUID.randomUUID()), role))
  }
  private def world: World = {
    val project = ProjectId(uuid)
    World(Scope(project, Actor("operator", SessionId(uuid), Role.Human)), Scope(project, Actor("attached governor", SessionId(uuid), Role.Governor)))
  }
  private def claude(name: String): DriverKey = DriverKey(Harness.Claude, name)
  private def task(title: String): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil), Nil)
  private def goal(title: String): ItemDraft = task(title).copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"))
  private def question(title: String): ItemDraft = task(title).copy(content = Content.Question(QuestionStatus.Open, "Prompt", "Context", Nil, None))
  private def request(mutations: List[Mutation], fences: List[Fence]): ChangeRequest = ChangeRequest(RequestId(uuid), mutations, fences, "Driver scenario")
  private def create(service: LedgerService[IO], scope: Scope, draft: ItemDraft): IO[Throwable, ItemId] =
    service.change(scope, request(List(Mutation.Create(draft)), Nil)).map(_.items.head.id)
  private def replace(service: LedgerService[IO], scope: Scope, id: ItemId, title: String): IO[Throwable, ChangeRequest] =
    service.get(scope, id).map(view => request(List(Mutation.Replace(id, view.item.revision, view.item.draft.copy(title = title))), Nil))
  private def reference(service: LedgerService[IO], scope: Scope, source: ItemId, relation: Relation, target: ItemId, present: Boolean): IO[Throwable, ChangeRequest] = for {
    a <- service.get(scope, source)
    b <- service.get(scope, target)
  } yield request(List(Mutation.Reference(source, a.item.revision, relation, target, b.item.revision, present)), Nil)
  // Produces one descendant under `producer`, holding the producer claim only for the write.
  private def produce(service: LedgerService[IO], scope: Scope, producer: ItemId, title: String): IO[Throwable, (ChangeRequest, ChangeAck)] = for {
    claim <- service.acquire(scope, ClaimId(uuid), Set(producer), 600000L)
    current <- service.get(scope, producer)
    change = request(List(Mutation.Produce(producer, current.item.revision, List(task(title)))), List(claim.fence))
    ack <- service.change(scope, change).ensuring(service.release(scope, claim.fence).ignore)
  } yield (change, ack)
  private def cursor(service: LedgerService[IO], w: World): IO[Throwable, ChangeCursor] = service.counts(w.operator).map(_.cursor)

  private def control(service: LedgerService[IO], w: World, key: DriverKey, origin: DriverOrigin, action: DriverControl): IO[Throwable, DriverReply] =
    service.drive(w.operator, DriverRequest.Control(key, origin, action))
  private def start(service: LedgerService[IO], w: World, key: DriverKey, target: WorksetTarget): IO[Throwable, DriverReply.Started] =
    control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Start(target, None)).map(_.asInstanceOf[DriverReply.Started])
  private def park(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, DriverReply] =
    control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Park())
  private def query(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, DriverReply] =
    control(service, w, key, DriverOrigin.Stop, DriverControl.Continue())
  private def status(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, Option[DriverStatus]] =
    control(service, w, key, DriverOrigin.StatusLine, DriverControl.Status()).map { case DriverReply.Status(value) => value; case other => throw new IllegalStateException(other.toString) }
  private def act(service: LedgerService[IO], scope: Scope, action: DriverSession): IO[Throwable, DriverReply] = service.drive(scope, DriverRequest.Session(action))
  private def activate(service: LedgerService[IO], scope: Scope, run: RequestId, request: WorkflowRequest, token: Option[CycleToken]): IO[Throwable, DriverActivation] =
    act(service, scope, DriverSession.Activate(run, request, token)).map { case DriverReply.Activation(value) => value; case other => throw new IllegalStateException(other.toString) }
  private def workset(targets: ItemId*): WorksetTarget = WorksetTarget.Inline(targets.toSet, WorkflowPhase.Work)

  // Turns a driver on for the world's governor: drive-start from the hook, then the bind operation from the attached session.
  private def on(service: LedgerService[IO], w: World, key: DriverKey, target: WorksetTarget): IO[Throwable, DriverReply.Started] = for {
    started <- start(service, w, key, target)
    _ <- act(service, w.governor, DriverSession.Bind(started.bind.get))
  } yield started
  private def directive(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, DriverReply.Continue] =
    query(service, w, key).map { case value: DriverReply.Continue => value; case other => throw new IllegalStateException("Expected a directive: " + other) }
  // What a harness session does with a directive: submits the text unchanged; the advance command reads its roots, phase and token.
  private def submitted(project: ProjectId, text: String, invocation: String): (WorkflowRequest, CycleToken) = {
    val words = text.split(" ").toList
    require(words.head == invocation && words.size == 7, text)
    val options = words.tail.grouped(2).map(pair => pair.head -> pair(1)).toMap
    val workflow = WorkflowArguments.parse(project, Map(WorkflowCatalog.WorkflowFlag -> "advance",
      WorkflowCatalog.Roots.flag -> options(WorkflowCatalog.Roots.flag), WorkflowCatalog.Through.flag -> options(WorkflowCatalog.Through.flag))).get
    val token = options.get(DriverPolicy.StartFlag).map(value => CycleToken.Start(DriverToken(UUID.fromString(value))))
      .getOrElse(CycleToken.Resume(DriverToken(UUID.fromString(options(DriverPolicy.ResumeFlag)))))
    (workflow, token)
  }
  private final case class Driven(cycle: CycleId, run: RequestId, workflow: WorkflowRequest, token: CycleToken)
  private def submit(service: LedgerService[IO], w: World, scope: Scope, text: String): IO[Throwable, Driven] = {
    val (workflow, token) = submitted(w.project, text, "/cq:advance")
    val run = RequestId(uuid)
    activate(service, scope, run, workflow, Some(token)).map {
      case DriverActivation.Started(cycle) => Driven(cycle, run, workflow, token)
      case other => throw new IllegalStateException("Expected a started cycle: " + other)
    }
  }
  // An on driver with one accepted cycle.
  private def driven(service: LedgerService[IO], w: World, key: DriverKey, target: WorksetTarget): IO[Throwable, Driven] = for {
    _ <- on(service, w, key, target)
    issued <- directive(service, w, key)
    cycle <- submit(service, w, w.governor, issued.directive.text)
  } yield cycle

  private def fault[A](result: Either[Throwable, A]): Option[Fault] = result.left.toOption.collect { case DomainFailure(value) => value }
  private def invalid[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Invalid])
  private def missing[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Missing])
  private def denied[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Denied])
  private def conflict[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Conflict])
  private def stopped(value: Option[DriverStatus], reason: DriverStop): Boolean =
    value.exists(found => found.state == DriverState.Off && found.attached.isEmpty && found.stopped.exists(_.reason == reason))
  private def failed(service: LedgerService[IO], w: World, key: DriverKey, detail: String): IO[Throwable, Unit] =
    status(service, w, key).flatMap(value => assertIO(stopped(value, DriverStop.Failure) && value.get.stopped.get.detail.contains(detail))).unit
  private def lineage(value: Option[DriverStatus]): List[LineageEntry] = value.flatMap(_.cycle).toList.flatMap(_.lineage)
  // The operation is rejected, nothing is written and the driver stops with reason failure.
  private def rejects[A](service: LedgerService[IO], w: World, key: DriverKey, detail: String)(operation: IO[Throwable, A]): IO[Throwable, Unit] = for {
    before <- cursor(service, w)
    result <- operation.either
    after <- cursor(service, w)
    _ <- assertIO(denied(result) && before == after)
    _ <- failed(service, w, key, detail)
  } yield ()

  private final class ApplicationApi(application: Application, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    override def call(command: Command): Result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(application.execute(authority, command)).getOrThrowFiberFailure() }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("The driver publishes no usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("The driver publishes no artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("The driver admits no results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("The driver integrates no candidates")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("The driver issues no credentials")
  }

  "The CQ driver core (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "validate drive-start again, return the preview, reject invalid requests with the driver off, and park" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("drive-start")
      for {
        _ <- service.initialize(w.operator, "drive-start")
        root <- create(service, w.operator, goal("Goal"))
        (_, produced) <- produce(service, w.operator, root, "Descendant")
        child = produced.items.find(_.id != root).get.id
        stranger <- create(service, w.operator, task("Unrelated"))
        stored <- service.createWorkset(w.operator, Set(root), WorkflowPhase.Plan)
        expected <- service.previewWorkset(w.operator, workset(root))
        started <- start(service, w, key, workset(root))
        _ <- assertIO(started.preview == expected && started.preview.advanceable.map(_.item.id).toSet == Set(root, child))
        _ <- assertIO(started.status.state == DriverState.Binding && started.status.targets == Set(root) && started.status.through == WorkflowPhase.Work &&
          started.bind.nonEmpty && started.status.cycle.isEmpty && started.status.attached.isEmpty && started.status.line == "CQ driver binding: G1 through work")
        changed <- start(service, w, key, workset(stranger)).either
        _ <- assertIO(conflict(changed))
        _ <- act(service, w.governor, DriverSession.Bind(started.bind.get))
        whileOn <- start(service, w, key, workset(stranger)).either
        _ <- assertIO(conflict(whileOn))
        frozen <- status(service, w, key)
        _ <- assertIO(frozen.exists(value => value.state == DriverState.On && value.targets == Set(root) && value.attached.contains(w.governor.actor.session)))
        issued <- directive(service, w, key)
        parked <- park(service, w, key)
        _ <- assertIO(parked match { case DriverReply.Parked(Some(value), _) => stopped(Some(value), DriverStop.Parked) && value.cycle.exists(_.state == CycleState.Ended); case _ => false })
        cancelled <- activate(service, w.governor, RequestId(uuid), WorkflowRequest.Advance(Set(root), WorkflowPhase.Work), Some(issued.directive.token)).either
        _ <- assertIO(denied(cancelled))
        released <- replace(service, w.governor, stranger, "Changed after park").flatMap(service.change(w.governor, _))
        _ <- assertIO(released.items.map(_.id) == List(stranger))
        off <- query(service, w, key)
        _ <- assertIO(off match { case DriverReply.Stop(DriverStopped(DriverStop.Off, _), _, Nil) => true; case _ => false })
        again <- park(service, w, key)
        _ <- assertIO(again match { case DriverReply.Parked(Some(value), "CQ driver is already off") => stopped(Some(value), DriverStop.Parked); case _ => false })
        byId <- start(service, w, key, WorksetTarget.Stored(stored.id))
        _ <- assertIO(byId.status.workset.contains(stored.id) && byId.status.targets == Set(root) && byId.status.through == WorkflowPhase.Plan &&
          byId.status.stopped.isEmpty && byId.preview.workset.contains(stored.id))
        fresh = claude("drive-start-rejected")
        rejected <- ZIO.foreach(List(
          start(service, w, fresh, WorksetTarget.Inline(Set.empty, WorkflowPhase.Work)),
          start(service, w, fresh, workset(root.copy(number = 999))),
          start(service, w, fresh, WorksetTarget.Stored(WorksetId(uuid))),
          start(service, w, claude(""), workset(root)),
          start(service, w, claude("two words"), workset(root)),
          start(service, w, claude("k" * (DriverPolicy.MaxSessionKey + 1)), workset(root)),
        ))(_.either)
        _ <- assertIO(invalid(rejected.head) && missing(rejected(1)) && missing(rejected(2)) && rejected.drop(3).forall(invalid))
        _ <- assertIO(fault(rejected(3)).contains(Fault.Invalid("Driver session key is missing; no default session is used")))
        unchanged <- status(service, w, fresh)
        stillOff <- query(service, w, fresh)
        _ <- assertIO(unchanged.isEmpty && (stillOff match { case DriverReply.Stop(DriverStopped(DriverStop.Off, _), None, Nil) => true; case _ => false }))
        text = ZIO.foreach(List("", "   ", "X1 through=work", "G1", "G1 through=ship", "G1 through=work through=plan", "G1 G1 through=work",
          "workset=not-a-uuid", s"workset=${stored.id.value} G1"))(arguments => ZIO.attempt(DriverArguments.parse(w.project, arguments)).either)
        malformed <- text
        _ <- assertIO(malformed.forall(invalid) && fault(malformed.head).exists(_.toString.contains("never mean the whole project")) &&
          fault(malformed(4)).exists(_.toString.contains("Unknown through phase ship")) && fault(malformed(2)).exists(_.toString.contains("Unknown item ID X1")))
        _ <- assertIO(DriverArguments.parse(w.project, "G1, T2 through=review") == WorksetTarget.Inline(Set(root, child.copy(number = 2)), WorkflowPhase.Review) &&
          DriverArguments.parse(w.project, s"workset=${stored.id.value}") == WorksetTarget.Stored(stored.id))
      } yield ()
    }

    "turn on only when exactly one attached session binds with the hook-minted single-use token" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO], registry: DriverRegistry) =>
        val w = world
        val key = claude("binding")
        val second = w.other(Role.Governor)
        for {
          _ <- service.initialize(w.operator, "binding")
          root <- create(service, w.operator, goal("Goal"))
          started <- start(service, w, key, workset(root))
          unknown <- act(service, w.governor, DriverSession.Bind(DriverToken(uuid))).either
          byHuman <- act(service, w.operator, DriverSession.Bind(started.bind.get)).either
          byWorker <- act(service, w.other(Role.Worker), DriverSession.Bind(started.bind.get)).either
          binding <- status(service, w, key)
          _ <- assertIO(denied(unknown) && denied(byHuman) && denied(byWorker) && binding.exists(_.state == DriverState.Binding))
          none <- act(service, w.governor, DriverSession.Status())
          _ <- assertIO(none == DriverReply.Status(None))
          bound <- act(service, w.governor, DriverSession.Bind(started.bind.get))
          _ <- assertIO(bound match {
            case DriverReply.Bound(value, "CQ driver on: G1 through work") =>
              value.state == DriverState.On && value.attached.contains(w.governor.actor.session) && value.line == "CQ driver on: G1 through work; 0 active children"
            case _ => false
          })
          own <- act(service, w.governor, DriverSession.Status())
          _ <- assertIO(own match { case DriverReply.Status(Some(value)) => value.key == key && value.state == DriverState.On; case _ => false })
          reused <- act(service, second, DriverSession.Bind(started.bind.get)).either
          _ <- assertIO(denied(reused))
          other <- start(service, w, claude("binding-other"), workset(root))
          twice <- act(service, w.governor, DriverSession.Bind(other.bind.get)).either
          _ <- assertIO(conflict(twice))
          _ <- act(service, second, DriverSession.Bind(other.bind.get))
          expiring = claude("binding-expired")
          offer <- start(service, w, expiring, workset(root))
          late = FixedLedger.service(repository, Clock.fixed(Instant.now().plusMillis(DriverPolicy.BindMillis + 60000), ZoneOffset.UTC), registry)
          third = w.other(Role.Governor)
          expired <- act(late, third, DriverSession.Bind(offer.bind.get)).either
          _ <- assertIO(denied(expired) && fault(expired).exists(_.toString.contains("expired")))
          skipped <- query(service, w, expiring)
          _ <- assertIO(skipped match {
            case DriverReply.Stop(DriverStopped(DriverStop.NotBound, detail), Some(value), List(message)) =>
              detail.startsWith("failure: no attached CQ session presented the bind token") && stopped(Some(value), DriverStop.NotBound) &&
                message.startsWith("CQ driver stopped (not bound)")
            case _ => false
          })
          never <- act(service, third, DriverSession.Bind(offer.bind.get)).either
          after <- status(service, w, expiring)
          _ <- assertIO(denied(never) && stopped(after, DriverStop.NotBound))
          undriven <- replace(service, third, root, "Unbound sessions write as before").flatMap(service.change(third, _))
          _ <- assertIO(undriven.items.map(_.id) == List(root))
          pi = DriverKey(Harness.Pi, "pi-session")
          attached = w.other(Role.Governor)
          extension <- control(service, w, pi, DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session)))
          _ <- assertIO(extension match {
            case DriverReply.Started(value, _, None, "CQ driver on: G1 through work") => value.state == DriverState.On && value.attached.contains(attached.actor.session)
            case _ => false
          })
          taken <- control(service, w, DriverKey(Harness.Pi, "pi-other"), DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session))).either
          _ <- assertIO(conflict(taken))
          wrong <- ZIO.foreach(List(
            control(service, w, DriverKey(Harness.Pi, "pi-hook"), DriverOrigin.UserPromptSubmit, DriverControl.Start(workset(root), None)),
            control(service, w, DriverKey(Harness.Pi, "pi-unattached"), DriverOrigin.Extension, DriverControl.Start(workset(root), None)),
            control(service, w, claude("claude-attached"), DriverOrigin.UserPromptSubmit, DriverControl.Start(workset(root), Some(attached.actor.session))),
            control(service, w, claude("claude-extension"), DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session))),
            control(service, w, claude("claude-stop-start"), DriverOrigin.Stop, DriverControl.Start(workset(root), None)),
            control(service, w, key, DriverOrigin.Stop, DriverControl.Park()),
            control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Continue()),
            control(service, w, key, DriverOrigin.StatusLine, DriverControl.Park()),
          ))(_.either)
          kept <- status(service, w, key)
          _ <- assertIO(wrong.forall(invalid) && kept.exists(_.state == DriverState.On))
        } yield ()
    }

    "hold a bounded number of drivers per project and displace only off or silent ones" in { (repository: LedgerRepository[IO]) =>
      val w = world
      val registry = new DriverRegistry
      val begin = 1000000L
      def at(millis: Long): LedgerService[IO] = FixedLedger.service(repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), registry)
      val service = at(begin)
      for {
        _ <- service.initialize(w.operator, "capacity")
        root <- create(service, w.operator, goal("Goal"))
        live <- start(service, w, claude("driver-1"), workset(root))
        _ <- act(service, w.governor, DriverSession.Bind(live.bind.get))
        _ <- ZIO.foreachDiscard(2 to DriverPolicy.MaxDrivers)(index => start(service, w, claude(s"driver-$index"), workset(root)))
        full <- start(service, w, claude("one-too-many"), workset(root)).either
        _ <- assertIO(fault(full).exists(_.isInstanceOf[Fault.Limit]))
        _ <- park(service, w, claude("driver-7"))
        _ <- start(service, w, claude("replaces-parked"), workset(root))
        parked <- status(service, w, claude("driver-7"))
        again <- start(service, w, claude("still-too-many"), workset(root)).either
        _ <- assertIO(parked.isEmpty && fault(again).exists(_.isInstanceOf[Fault.Limit]))
        // Only driver-1 keeps querying; the others fall silent.
        _ <- directive(at(begin + DriverPolicy.IdleMillis), w, claude("driver-1"))
        _ <- start(at(begin + DriverPolicy.IdleMillis + 1), w, claude("replaces-silent"), workset(root))
        kept <- status(service, w, claude("driver-1"))
        _ <- assertIO(kept.exists(_.state == DriverState.On) && registry.all(w.project).size == DriverPolicy.MaxDrivers &&
          registry.all(w.project).count(_.touchedAt == begin) == DriverPolicy.MaxDrivers - 2)
      } yield ()
    }

    "expose drive-start and park only to the hook and extension entry points and keep sessions isolated by their trusted key" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val clock = Clock.systemUTC()
        val operatorToken = "driver-contract-test-operator-token"
        val authorization = new Authorization(AccessConfig(operatorToken, "http://localhost"), clock)
        val root = authorization.authenticate(operatorToken, Some(uuid.toString))
        val project = ProjectId(uuid)
        def governor(): Authority =
          authorization.authenticate(authorization.grant(root, GrantRequest(project, Actor("attached governor", SessionId(uuid), Role.Governor), clock.millis() + 60000)).value, None)
        val authorityA = governor()
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization)
        // What the hook of each session receives from its harness: sessions A and B each supply their own key.
        val a = DriverCall("claude", "UserPromptSubmit", Some("session-a"))
        val b = DriverCall("claude", "UserPromptSubmit", Some("session-b"))
        def stop(call: DriverCall): DriverCall = call.copy(event = "Stop")
        for {
          runtime <- ZIO.runtime[Any]
          operator = root.scope(project)
          _ <- ledger.initialize(operator, "session trust")
          first <- create(ledger, operator, goal("Goal of A"))
          second <- create(ledger, operator, goal("Goal of B"))
          entry = new DriverEntry(new ApplicationApi(application, root, runtime), project)
          sessionA = new DriverSessionClient(new ApplicationApi(application, authorityA, runtime), project)
          sessionB = new DriverSessionClient(new ApplicationApi(application, governor(), runtime), project)
          statusOf = (value: DriverCall) => ZIO.attemptBlocking(entry.status(value.copy(event = "StatusLine"))).map { case DriverReply.Status(found) => found; case other => throw new IllegalStateException(other.toString) }
          bound = (call: DriverCall, arguments: String, session: DriverSessionClient) => ZIO.attemptBlocking {
            entry.start(call, arguments, None) match {
              case DriverReply.Started(_, _, Some(token), _) => session.bind(token)
              case other => throw new IllegalStateException(other.toString)
            }
          }
          _ <- bound(a, "G1 through=plan", sessionA)
          _ <- bound(b, "G2 through=work", sessionB)
          beforeA <- statusOf(a)
          beforeB <- statusOf(b)
          _ <- assertIO(beforeA.exists(value => value.key == DriverKey(Harness.Claude, "session-a") && value.state == DriverState.On && value.targets == Set(first) && value.through == WorkflowPhase.Plan) &&
            beforeB.exists(value => value.key == DriverKey(Harness.Claude, "session-b") && value.state == DriverState.On && value.targets == Set(second) && value.through == WorkflowPhase.Work))
          continued <- ZIO.attemptBlocking(entry.continuation(stop(a)))
          afterDirective <- statusOf(b)
          _ <- assertIO(continued.isInstanceOf[DriverReply.Continue] && afterDirective == beforeB)
          _ <- ZIO.attemptBlocking(entry.park(a))
          parkedA <- statusOf(a)
          afterPark <- statusOf(b)
          _ <- assertIO(stopped(parkedA, DriverStop.Parked) && afterPark == beforeB)
          _ <- bound(a, "G1 through=plan", sessionA)
          runningA <- statusOf(a)
          directive <- ZIO.attemptBlocking(entry.continuation(stop(b)))
          afterB <- statusOf(a)
          _ <- assertIO(directive.isInstanceOf[DriverReply.Continue] && afterB == runningA && runningA.exists(_.state == DriverState.On))
          // Decision 2 limitation: the key is trusted, not authenticated. B's hook supplying A's key changes A's driver, and not B's.
          runningB <- statusOf(b)
          forged <- ZIO.attemptBlocking(entry.park(b.copy(session = a.session)))
          forgedA <- statusOf(a)
          forgedB <- statusOf(b)
          _ <- assertIO(forged.isInstanceOf[DriverReply.Parked] && stopped(forgedA, DriverStop.Parked) && forgedB == runningB)
          malformed <- ZIO.foreach(List[() => DriverReply](
            () => entry.park(b.copy(session = None)),
            () => entry.park(b.copy(session = Some(""))),
            () => entry.park(b.copy(session = Some("session b"))),
            () => entry.park(b.copy(event = "PreToolUse")),
            () => entry.park(b.copy(event = "")),
            () => entry.park(b.copy(harness = "emacs")),
            () => entry.continuation(b.copy(event = "SessionStart")),
            () => entry.start(b.copy(session = None), "G1 through=plan", None),
            () => entry.start(DriverCall("claude", "Notification", Some("session-c")), "G1 through=plan", None),
            () => entry.start(DriverCall("claude", "UserPromptSubmit", Some("session-c")), "through=plan", None),
          ))(operation => ZIO.attemptBlocking(operation()).either)
          _ <- assertIO(malformed.forall(invalid))
          _ <- assertIO(fault(malformed.head).contains(Fault.Invalid("Driver session key is missing; no default session is used")) &&
            fault(malformed(1)) == fault(malformed.head) && fault(malformed(7)) == fault(malformed.head) &&
            fault(malformed(3)).contains(Fault.Invalid("Unknown CQ hook event PreToolUse")) && fault(malformed(5)).contains(Fault.Invalid("Unknown harness identifier emacs")))
          unchangedA <- statusOf(a)
          unchangedB <- statusOf(b)
          created <- statusOf(DriverCall("claude", "StatusLine", Some("session-c")))
          _ <- assertIO(unchangedA == forgedA && unchangedB == runningB && created.isEmpty)
          // Attached sessions hold a governor credential: it reaches the bind and the status only, never a state-changing entry point.
          modelFacing = new DriverEntry(new ApplicationApi(application, authorityA, runtime), project)
          refused <- ZIO.foreach(List[() => DriverReply](() => modelFacing.start(a, "G1 through=plan", None), () => modelFacing.park(b),
            () => modelFacing.continuation(stop(b)), () => modelFacing.status(b.copy(event = "StatusLine"))))(operation => ZIO.attemptBlocking(operation()).either)
          stillA <- statusOf(a)
          stillB <- statusOf(b)
          _ <- assertIO(refused.forall(denied) && stillA == forgedA && stillB == runningB)
          ownStatus <- ZIO.attemptBlocking(sessionA.status)
          _ <- assertIO(ownStatus match { case DriverReply.Status(Some(value)) => value.key == DriverKey(Harness.Claude, "session-a") && value.state == DriverState.Off; case _ => false })
          schemas = new McpSchemas()
          sessionTool = schemas.attachedTools.find(_.hcursor.get[String]("name").contains("session")).get
          variants = sessionTool.hcursor.downField("inputSchema").get[List[io.circe.Json]]("oneOf").toOption.get.flatMap(_.hcursor.get[List[String]]("required").toOption.get)
          _ <- assertIO(variants.toSet == Set("Context", "Workflow", "Bind", "Driver"))
          _ <- assertIO(!schemas.tools.exists(_.inputType.startsWith("Driver")) && schemas.attachedTools.size == schemas.tools.size + 2)
          wire = s"""{"Driver":{"input":{"project":{"value":"${project.value}"},"request":{"Control":{"key":{"harness":"Claude","session":"s"},"origin":"%s","action":{"Start":{"target":{"Inline":{"targets":[],"through":"%s"}},"attached":null}}}}}}}"""
          decode = (origin: String, phase: String) => Command_JsonCodec.decode(BaboonCodecContext.Default, parse(wire.format(origin, phase)).toOption.get)
          _ <- assertIO(decode("UserPromptSubmit", "Work").isRight && decode("UserPromptSubmit", "Ship").isLeft && decode("PreToolUse", "Work").isLeft)
        } yield ()
    }

    "drive two consecutive cycles from the issued directives and make a descendant created in one advanceable in the next" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("cycles")
      for {
        _ <- service.initialize(w.operator, "cycles")
        root <- create(service, w.operator, goal("Goal"))
        _ <- on(service, w, key, workset(root))
        before <- service.previewWorkset(w.operator, workset(root))
        first <- directive(service, w, key)
        token = first.directive.token.asInstanceOf[CycleToken.Start].token
        _ <- assertIO(first.directive.text == s"/cq:advance --roots G1 --through work --start-token ${token.value}" && first.messages.isEmpty)
        _ <- assertIO(first.status.cycle.exists(cycle => cycle.id == first.directive.cycle && cycle.number == 1 && cycle.state == CycleState.Pending &&
          cycle.roots == Set(root) && cycle.through == WorkflowPhase.Work && cycle.snapshot == before.snapshot &&
          cycle.advanceable == before.advanceable.map(member => ItemRevision(member.item.id, member.item.revision)) && cycle.run.isEmpty) && first.status.directives == 1)
        one <- submit(service, w, w.governor, first.directive.text)
        _ <- assertIO(one.cycle == first.directive.cycle && one.workflow == WorkflowRequest.Advance(Set(root), WorkflowPhase.Work))
        replayed <- activate(service, w.governor, one.run, one.workflow, Some(one.token))
        _ <- assertIO(replayed == DriverActivation.Started(one.cycle))
        (change, produced) <- produce(service, w.governor, root, "Descendant created by cycle 1")
        child = produced.items.find(_.id != root).get.id
        running <- status(service, w, key)
        _ <- assertIO(running.exists(value => value.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.run.contains(one.run) &&
          cycle.created == List(child) && !cycle.advanceable.exists(_.id == child))))
        _ <- assertIO(lineage(running).contains(LineageEntry(LineageMember.Change(change.request), Some(LineageMember.Run(one.run)), true)) &&
          lineage(running).exists(entry => entry.member.isInstanceOf[LineageMember.Claim] && entry.parent.contains(LineageMember.Run(one.run))) &&
          lineage(running).head == LineageEntry(LineageMember.Run(one.run), None, false))
        again <- replace(service, w.governor, child, "A created item stays inside its cycle").flatMap(service.change(w.governor, _))
        _ <- assertIO(again.items.map(_.id) == List(child))
        second <- directive(service, w, key)
        _ <- assertIO(second.directive.cycle != one.cycle && second.directive.token != first.directive.token &&
          second.messages == List("CQ driver: the advanceable set changed to 2 items; added T1"))
        _ <- assertIO(second.status.cycle.exists(cycle => cycle.number == 2 && cycle.state == CycleState.Pending && cycle.roots == Set(root) &&
          cycle.advanceable.map(_.id).toSet == Set(root, child) && cycle.created.isEmpty && cycle.lineage.isEmpty) && second.status.directives == 2)
        stale <- activate(service, w.other(Role.Governor), RequestId(uuid), one.workflow, Some(one.token)).either
        _ <- assertIO(denied(stale))
        two <- submit(service, w, w.governor, second.directive.text)
        advanced <- replace(service, w.governor, child, "Advanced in cycle 2").flatMap(service.change(w.governor, _))
        _ <- assertIO(two.cycle == second.directive.cycle && advanced.items.map(_.id) == List(child))
        third <- directive(service, w, key)
        _ <- assertIO(third.messages.isEmpty && third.status.cycle.exists(_.number == 3))
        _ <- submit(service, w, w.governor, third.directive.text)
        quiet <- query(service, w, key)
        _ <- assertIO(quiet match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, detail), Some(value), List(message)) =>
            detail.startsWith("The previous cycle changed nothing") && stopped(Some(value), DriverStop.Quiescent) && value.directives == 3 &&
              value.line == "CQ driver off: G1 through work; stopped (quiescent): " + detail && message == "CQ driver stopped (quiescent): " + detail
          case _ => false
        })
        off <- query(service, w, key)
        _ <- assertIO(off match { case DriverReply.Stop(DriverStopped(DriverStop.Off, _), Some(_), Nil) => true; case _ => false })
        after <- replace(service, w.governor, root, "Driver off: the session writes as before").flatMap(service.change(w.governor, _))
        _ <- assertIO(after.items.map(_.id) == List(root))
        pi = DriverKey(Harness.Pi, "pi-cycles")
        attached = w.other(Role.Governor)
        _ <- control(service, w, pi, DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session)))
        extension <- control(service, w, pi, DriverOrigin.Extension, DriverControl.Continue())
        _ <- assertIO(extension match { case DriverReply.Continue(value, _, _) => value.text.startsWith("/cq:advance --roots G1 --through work --start-token "); case _ => false })
        codex = DriverKey(Harness.Codex, "codex-cycles")
        codexSession = w.copy(governor = w.other(Role.Governor))
        _ <- on(service, codexSession, codex, workset(root))
        skill <- directive(service, codexSession, codex)
        _ <- assertIO(skill.directive.text == s"$$cq-advance --roots G1 --through work --start-token ${skill.directive.token.asInstanceOf[CycleToken.Start].token.value}")
        _ <- assertIO(DriverPolicy.RootsFlag == WorkflowCatalog.Roots.flag && DriverPolicy.ThroughFlag == WorkflowCatalog.Through.flag &&
          DriverPolicy.invocation(Harness.Codex) == "$" + WorkflowCatalog.of(WorkflowName.Advance).alias(Harness.Codex).alias &&
          DriverPolicy.invocation(Harness.Claude) == WorkflowCatalog.of(WorkflowName.Advance).alias(Harness.Claude).alias &&
          DriverPolicy.invocation(Harness.Pi) == WorkflowCatalog.of(WorkflowName.Advance).alias(Harness.Pi).alias)
      } yield ()
    }

    "reattach a running cycle with a resume directive, keep exactly one run and reject swapped start and resume tokens" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("resume")
      val attempt = LineageMember.Attempt(AttemptId(uuid))
      val dispatch = LineageMember.Request(RequestId(uuid))
      for {
        _ <- service.initialize(w.operator, "resume")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        run = LineageMember.Run(one.run)
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, run, dispatch))
        child <- act(service, w.governor, DriverSession.Inherit(one.cycle, dispatch, attempt))
        _ <- assertIO(child == DriverReply.Lineage(one.cycle, LineageEntry(attempt, Some(dispatch), false)))
        _ <- act(service, w.governor, DriverSession.Settle(one.cycle, dispatch))
        resumed <- directive(service, w, key)
        resume = resumed.directive.token.asInstanceOf[CycleToken.Resume].token
        _ <- assertIO(resumed.directive.cycle == one.cycle && resumed.directive.text == s"/cq:advance --roots G1 --through work --resume-token ${resume.value}" &&
          CycleToken.Start(resume) != one.token && resumed.status.directives == 2 && resumed.status.activeChildren == 1 &&
          resumed.status.line == "CQ driver on: G1 through work; 1 active child" && resumed.status.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.run.contains(one.run)))
        (workflow, token) = submitted(w.project, resumed.directive.text, "/cq:advance")
        call = RequestId(uuid)
        reattached <- activate(service, w.governor, call, workflow, Some(token))
        retried <- activate(service, w.governor, call, workflow, Some(token))
        _ <- assertIO(reattached == DriverActivation.Resumed(one.cycle, one.run) && retried == reattached)
        next <- directive(service, w, key)
        _ <- assertIO(next.directive.cycle == one.cycle && next.directive.token.isInstanceOf[CycleToken.Resume] && next.directive.token != token && next.status.directives == 3)
        _ <- act(service, w.governor, DriverSession.Settle(one.cycle, attempt))
        done <- query(service, w, key)
        _ <- assertIO(done match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, _), Some(value), _) =>
            value.cycle.exists(cycle => cycle.id == one.cycle && cycle.state == CycleState.Ended && cycle.run.contains(one.run) &&
              cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1 && cycle.lineage.forall(_.settled)) && value.activeChildren == 0
          case _ => false
        })
        // A start token presented as a resume token.
        startAsResume = claude("resume-swapped-start")
        swapping = w.copy(governor = w.other(Role.Governor))
        _ <- on(service, swapping, startAsResume, workset(root))
        pending <- directive(service, swapping, startAsResume)
        start = pending.directive.token.asInstanceOf[CycleToken.Start].token
        _ <- rejects(service, swapping, startAsResume, "a resume token was presented for a cycle that has not started")(
          activate(service, swapping.governor, RequestId(uuid), workflow, Some(CycleToken.Resume(start))))
        noRun <- status(service, swapping, startAsResume)
        _ <- assertIO(noRun.exists(_.cycle.exists(cycle => cycle.run.isEmpty && cycle.state == CycleState.Ended)))
        // A resume token presented as a start token, and a reused resume token.
        resumeAsStart = claude("resume-swapped-resume")
        running = w.copy(governor = w.other(Role.Governor))
        active <- driven(service, running, resumeAsStart, workset(root))
        _ <- act(service, running.governor, DriverSession.Inherit(active.cycle, LineageMember.Run(active.run), attempt))
        issued <- directive(service, running, resumeAsStart)
        swapped = issued.directive.token.asInstanceOf[CycleToken.Resume].token
        _ <- rejects(service, running, resumeAsStart, "unknown start token")(
          activate(service, running.governor, RequestId(uuid), workflow, Some(CycleToken.Start(swapped))))
        single <- status(service, running, resumeAsStart)
        _ <- assertIO(single.exists(_.cycle.exists(cycle => cycle.run.contains(active.run) && cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1)))
        reuse = claude("resume-reused")
        reusing = w.copy(governor = w.other(Role.Governor))
        held <- driven(service, reusing, reuse, workset(root))
        _ <- act(service, reusing.governor, DriverSession.Inherit(held.cycle, LineageMember.Run(held.run), attempt))
        once <- directive(service, reusing, reuse)
        _ <- activate(service, reusing.governor, RequestId(uuid), workflow, Some(once.directive.token))
        _ <- rejects(service, reusing, reuse, "unknown or already used resume token")(
          activate(service, reusing.governor, RequestId(uuid), workflow, Some(once.directive.token)))
      } yield ()
    }

    "reject an activation that differs from the issued directive, start no run and stop with reason failure" in { (service: LedgerService[IO]) =>
      val w = world
      def scenario(name: String, detail: String)(attempt: (World, DriverKey, DriverReply.Continue, ItemId) => IO[Throwable, Any]): IO[Throwable, Unit] = {
        val session = w.copy(governor = w.other(Role.Governor))
        val key = claude(name)
        for {
          root <- create(service, w.operator, goal(name))
          _ <- on(service, session, key, workset(root))
          issued <- directive(service, session, key)
          _ <- rejects(service, session, key, detail)(attempt(session, key, issued, root))
          ended <- status(service, session, key)
          _ <- assertIO(ended.exists(_.cycle.exists(cycle => cycle.run.isEmpty && cycle.lineage.isEmpty && cycle.state == CycleState.Ended)))
          announced <- query(service, session, key)
          _ <- assertIO(announced match {
            case DriverReply.Stop(DriverStopped(DriverStop.Failure, text), Some(_), List(message)) => text.contains(detail) && message == "CQ driver stopped (failure): " + text
            case _ => false
          })
          silent <- query(service, session, key)
          _ <- assertIO(silent match { case DriverReply.Stop(DriverStopped(DriverStop.Off, _), Some(_), Nil) => true; case _ => false })
        } yield ()
      }
      def advance(root: ItemId): WorkflowRequest = WorkflowRequest.Advance(Set(root), WorkflowPhase.Work)
      for {
        _ <- service.initialize(w.operator, "activation")
        extra <- create(service, w.operator, task("Extra root"))
        _ <- scenario("altered-roots", "roots or phase differ from the directive of cycle 1")((s, _, issued, root) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Advance(Set(root, extra), WorkflowPhase.Work), Some(issued.directive.token)))
        _ <- scenario("altered-phase", "roots or phase differ from the directive of cycle 1")((s, _, issued, root) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Advance(Set(root), WorkflowPhase.Integrate), Some(issued.directive.token)))
        _ <- scenario("other-workflow", "workflow, roots or phase differ")((s, _, issued, root) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Begin(Set(root)), Some(issued.directive.token)))
        _ <- scenario("unknown-token", "unknown start token")((s, _, _, root) =>
          activate(service, s.governor, RequestId(uuid), advance(root), Some(CycleToken.Start(DriverToken(uuid)))))
        _ <- scenario("non-bound-caller", "presented by a session other than the bound attached session")((s, _, issued, root) =>
          activate(service, s.other(Role.Governor), RequestId(uuid), advance(root), Some(issued.directive.token)))
        _ <- scenario("omitted-token", "untracked activation: the bound attached session activated a workflow without a start or resume token")((s, _, _, root) =>
          activate(service, s.governor, RequestId(uuid), advance(root), None))
        _ <- scenario("untracked-begin", "untracked activation")((s, _, _, _) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Begin(Set.empty), None))
        _ <- scenario("untracked-review", "untracked activation")((s, _, _, _) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Review(ArtifactId(uuid), ReviewerMode.Candidate), None))
        _ <- scenario("untracked-upstream", "untracked activation")((s, _, _, root) =>
          activate(service, s.governor, RequestId(uuid), WorkflowRequest.Upstream(Set(root), UpstreamAction.Prepare), None))
        // A reused start token: the cycle already has its one run.
        reused = w.copy(governor = w.other(Role.Governor))
        root <- create(service, w.operator, goal("reused"))
        one <- driven(service, reused, claude("reused-token"), workset(root))
        _ <- rejects(service, reused, claude("reused-token"), "the start token of cycle 1 was already used")(
          activate(service, reused.governor, RequestId(uuid), one.workflow, Some(one.token)))
        kept <- status(service, reused, claude("reused-token"))
        _ <- assertIO(kept.exists(_.cycle.exists(cycle => cycle.run.contains(one.run) && cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1)))
        // The start directive was skipped: the next query finds the cycle unstarted.
        skipping = w.copy(governor = w.other(Role.Governor))
        _ <- on(service, skipping, claude("skipped"), workset(root))
        _ <- directive(service, skipping, claude("skipped"))
        skipped <- query(service, skipping, claude("skipped"))
        _ <- assertIO(skipped match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), Some(value), _) =>
            detail == "directive not started: the start directive of cycle 1 was not submitted" && stopped(Some(value), DriverStop.Failure) && value.cycle.exists(_.run.isEmpty)
          case _ => false
        })
        // Without a driver the same activations are not driven, and a token is not valid.
        free = w.other(Role.Governor)
        undriven <- activate(service, free, RequestId(uuid), WorkflowRequest.Begin(Set.empty), None)
        unowned <- activate(service, free, RequestId(uuid), advance(root), Some(CycleToken.Start(DriverToken(uuid)))).either
        _ <- assertIO(undriven == DriverActivation.Undriven() && denied(unowned))
      } yield ()
    }

    "govern readiness and the boundary by the snapshot stored when the directive was issued" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("snapshot")
      for {
        _ <- service.initialize(w.operator, "snapshot")
        root <- create(service, w.operator, goal("Goal"))
        _ <- on(service, w, key, workset(root))
        issued <- directive(service, w, key)
        // The graph changes between directive issue and activation: another session attaches a new descendant to the target.
        (_, grown) <- produce(service, w.operator, root, "Descendant attached after the directive was issued")
        late = grown.items.find(_.id != root).get.id
        current <- service.previewWorkset(w.operator, workset(root))
        _ <- assertIO(current.advanceable.map(_.item.id).toSet == Set(root, late) && current.snapshot != issued.status.cycle.get.snapshot)
        one <- submit(service, w, w.governor, issued.directive.text)
        active <- status(service, w, key)
        _ <- assertIO(active.exists(_.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.snapshot == issued.status.cycle.get.snapshot &&
          cycle.advanceable == issued.status.cycle.get.advanceable && !cycle.advanceable.exists(_.id == late))) && one.cycle == issued.directive.cycle)
        inside <- replace(service, w.governor, root, "The stored snapshot admits its own member").flatMap(service.change(w.governor, _))
        _ <- assertIO(inside.items.map(_.id) == List(root))
        outside <- replace(service, w.governor, late, "Selected now, absent from the stored snapshot")
        _ <- rejects(service, w, key, "out-of-set change: T1 is outside the advanceable set stored for cycle 1")(service.change(w.governor, outside))
        untouched <- service.get(w.operator, late)
        _ <- assertIO(untouched.item.revision == Revision(1))
      } yield ()
    }

    "attribute every mutation of the bound session to its active cycle and reject the untracked and the out-of-set ones" in { (service: LedgerService[IO]) =>
      val w = world
      val unbound = w.other(Role.Governor)
      def begun(name: String, target: ItemId): IO[Throwable, (World, DriverKey, Driven)] = {
        val bound = w.copy(governor = w.other(Role.Governor))
        driven(service, bound, claude(name), workset(target)).map(cycle => (bound, claude(name), cycle))
      }
      def idle(name: String, target: ItemId): IO[Throwable, (World, DriverKey)] = {
        val bound = w.copy(governor = w.other(Role.Governor))
        on(service, bound, claude(name), workset(target)).as((bound, claude(name)))
      }
      for {
        _ <- service.initialize(w.operator, "bound session")
        root <- create(service, w.operator, goal("Goal"))
        stranger <- create(service, w.operator, task("Out-of-set item"))
        // During an active cycle: an in-set change is admitted and stamped with the cycle's ID.
        (inSet, inKey, cycle) <- begun("in-set", root)
        change <- replace(service, inSet.governor, root, "In-set change")
        admitted <- service.change(inSet.governor, change)
        stamped <- status(service, inSet, inKey)
        _ <- assertIO(admitted.items.map(_.id) == List(root) && stamped.exists(value => value.state == DriverState.On && value.cycle.exists(_.id == cycle.cycle)) &&
          lineage(stamped).contains(LineageEntry(LineageMember.Change(change.request), Some(LineageMember.Run(cycle.run)), true)))
        replay <- service.change(inSet.governor, change)
        _ <- assertIO(replay == admitted)
        // During an active cycle: an out-of-set change without a cycle ID.
        outside <- replace(service, inSet.governor, stranger, "Out-of-set change")
        _ <- rejects(service, inSet, inKey, "out-of-set change: T1 is outside the advanceable set stored for cycle 1")(service.change(inSet.governor, outside))
        // A direct Reference with one endpoint out of set.
        (linking, linkKey, _) <- begun("reference", root)
        link <- reference(service, linking.governor, root, Relation.RelatesTo, stranger, true)
        _ <- rejects(service, linking, linkKey, "out-of-set change: T1 is outside")(service.change(linking.governor, link))
        (reversed, reverseKey, _) <- begun("reference-reversed", root)
        back <- reference(service, reversed.governor, stranger, Relation.RelatesTo, root, true)
        _ <- rejects(service, reversed, reverseKey, "out-of-set change: T1 is outside")(service.change(reversed.governor, back))
        // A creation that roots-bound enumeration of the frozen targets does not select.
        (creating, createKey, _) <- begun("creation", root)
        _ <- rejects(service, creating, createKey, "non-selected creation: T2 is not a selected descendant of G1")(create(service, creating.governor, task("Unattached creation")))
        absent <- service.get(w.operator, stranger.copy(number = 2)).either
        _ <- assertIO(missing(absent))
        // Between cycles: the driver is on but no directive has been accepted.
        (waiting, idleKey) <- idle("between-cycles", root)
        early <- replace(service, waiting.governor, stranger, "Before any directive")
        _ <- rejects(service, waiting, idleKey, "untracked mutation")(service.change(waiting.governor, early))
        (pending, pendingKey) <- idle("pending-directive", root)
        _ <- directive(service, pending, pendingKey)
        inSetEarly <- replace(service, pending.governor, root, "Before the start directive is accepted")
        _ <- rejects(service, pending, pendingKey, "untracked mutation")(service.change(pending.governor, inSetEarly))
        // A write that names another cycle than the active one.
        (naming, namingKey, named) <- begun("named-cycle", root)
        carried <- replace(service, naming.governor, root, "Carries its cycle ID")
        accepted <- act(service, naming.governor, DriverSession.Change(named.cycle, carried))
        _ <- assertIO(accepted match { case DriverReply.Changed(id, ack) => id == named.cycle && ack.items.map(_.id) == List(root); case _ => false })
        wrong <- replace(service, naming.governor, root, "Carries another cycle ID")
        _ <- rejects(service, naming, namingKey, "a write named a cycle other than the active cycle 1")(act(service, naming.governor, DriverSession.Change(CycleId(uuid), wrong)))
        // The same direct calls from a session that is not bound to an on driver behave as before.
        (_, watchKey, _) <- begun("unbound-witness", root)
        free <- replace(service, unbound, stranger, "Unbound out-of-set change").flatMap(service.change(unbound, _))
        linked <- reference(service, unbound, root, Relation.RelatesTo, stranger, true).flatMap(service.change(unbound, _))
        made <- create(service, unbound, task("Unbound creation"))
        watching <- status(service, w, watchKey)
        _ <- assertIO(free.items.map(_.id) == List(stranger) && linked.items.map(_.id).toSet == Set(root, stranger) && made.ledger == Ledger.Tasks &&
          watching.exists(value => value.state == DriverState.On && value.cycle.exists(_.lineage.size == 1)))
        unknown <- replace(service, unbound, stranger, "Unknown lineage").flatMap(change => act(service, unbound, DriverSession.Change(CycleId(uuid), change)).either)
        _ <- assertIO(denied(unknown) && fault(unknown).exists(_.toString.contains("unknown lineage")))
      } yield ()
    }

    "check a proposal application from the bound session against the active cycle" in {
      (service: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) =>
        def proposal(name: String, member: Int, begun: Boolean): IO[Throwable, (World, DriverKey, Option[Driven], ArtifactId, List[ItemRevision])] = {
          val w = world
          val key = claude(name)
          val collector = w.governor.copy(actor = w.governor.actor.copy(subject = "host", role = Role.Collector))
          for {
            _ <- service.initialize(w.operator, name)
            created <- service.change(w.governor, request(List.fill(2)(Mutation.Create(task("Proposal member"))), Nil))
            members = created.items
            claim <- service.acquire(w.governor, ClaimId(uuid), members.map(_.id).toSet, 300000)
            governing <- usage.assign(collector, Assignment(AssignmentId(uuid), w.project, Set.empty, Attribution.Unattributed, None, None))
            parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, w.governor.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern))
            assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), w.project, claim.members, Attribution.Shared, Some(uuid), None))
            attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), w.governor.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Plan))
            dispatch = DispatchRequest(RequestId(uuid), DispatchWork.Planner(), Harness.Codex, members, Nil, Nil, None, claim.fence, HostLimits(3000, 1000, 300, 2000, 262144))
            report = ChildReport.Plan(members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Proposed next step")),
              Some(LedgerProposal(List(ProposedMutation.Replace(members(member).id, task("Proposed title"))), "Apply the proposed next step")), Nil)
            result = ChildResult(attempt.id, dispatch, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
            artifact <- artifacts.upload(collector, ArtifactUpload(w.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
            admission <- admissions.admit(collector, HostAdmissionInput(w.project, artifact.id, w.governor.actor))
            _ <- assertIO(admission.decision == AdmissionDecision.Accepted())
            cycle <- if (begun) driven(service, w, key, workset(members.head.id)).map(Some(_)) else on(service, w, key, workset(members.head.id)).as(None)
          } yield (w, key, cycle, artifact.id, members)
        }
        for {
          (inside, insideKey, begun, applicable, members) <- proposal("proposal-in-set", 0, true)
          cycle = begun.get
          applied <- proposals(inside.governor, applicable)
          stamped <- status(service, inside, insideKey)
          _ <- assertIO(applied.items.map(_.id) == List(members.head.id) && stamped.exists(_.state == DriverState.On) &&
            lineage(stamped).contains(LineageEntry(LineageMember.Proposal(applicable), Some(LineageMember.Run(cycle.run)), true)) &&
            lineage(stamped).contains(LineageEntry(LineageMember.Change(applied.request), Some(LineageMember.Run(cycle.run)), true)))
          (outside, outsideKey, _, foreign, _) <- proposal("proposal-out-of-set", 1, true)
          _ <- rejects(service, outside, outsideKey, "out-of-set change: T2 is outside the advanceable set stored for cycle 1")(proposals(outside.governor, foreign))
          (between, betweenKey, _, early, _) <- proposal("proposal-between-cycles", 1, false)
          _ <- rejects(service, between, betweenKey, "untracked mutation")(proposals(between.governor, early))
        } yield ()
    }

    "carry the cycle ID through nested delegation and reject a delegated child's out-of-set writes" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("lineage")
      val child = w.other(Role.Governor)
      val grandchild = w.other(Role.Human)
      val outsider = w.other(Role.Governor)
      def member(scope: Scope): LineageMember = LineageMember.Session(scope.actor.session)
      for {
        _ <- service.initialize(w.operator, "lineage")
        root <- create(service, w.operator, goal("Goal"))
        stranger <- create(service, w.operator, task("Out-of-set item"))
        one <- driven(service, w, key, workset(root))
        run = LineageMember.Run(one.run)
        premature <- act(service, child, DriverSession.Inherit(one.cycle, member(child), member(grandchild))).either
        _ <- assertIO(denied(premature))
        restarted <- status(service, w, key)
        _ <- assertIO(stopped(restarted, DriverStop.Failure))
        _ <- start(service, w, key, workset(root)).flatMap(started => act(service, w.governor, DriverSession.Bind(started.bind.get)))
        issued <- directive(service, w, key)
        two <- submit(service, w, w.governor, issued.directive.text)
        again = LineageMember.Run(two.run)
        _ <- act(service, w.governor, DriverSession.Inherit(two.cycle, again, member(child)))
        orphan <- act(service, w.governor, DriverSession.Inherit(two.cycle, LineageMember.Request(RequestId(uuid)), LineageMember.Attempt(AttemptId(uuid)))).either
        _ <- assertIO(invalid(orphan))
        nested <- act(service, child, DriverSession.Inherit(two.cycle, member(child), member(grandchild)))
        _ <- assertIO(nested == DriverReply.Lineage(two.cycle, LineageEntry(member(grandchild), Some(member(child)), false)))
        repeated <- act(service, child, DriverSession.Inherit(two.cycle, member(child), member(grandchild)))
        moved <- act(service, w.governor, DriverSession.Inherit(two.cycle, again, member(grandchild))).either
        _ <- assertIO(repeated == nested && conflict(moved))
        work = LineageMember.Attempt(AttemptId(uuid))
        _ <- act(service, grandchild, DriverSession.Inherit(two.cycle, member(grandchild), work))
        inherited <- status(service, w, key)
        _ <- assertIO(lineage(inherited).map(entry => entry.member -> entry.parent) == List(again -> None, member(child) -> Some(again),
          member(grandchild) -> Some(member(child)), work -> Some(member(grandchild))) && inherited.exists(_.activeChildren == 1))
        change <- replace(service, grandchild, root, "In-set change by a nested delegate")
        admitted <- act(service, grandchild, DriverSession.Change(two.cycle, change))
        stamped <- status(service, w, key)
        _ <- assertIO(admitted match { case DriverReply.Changed(id, ack) => id == two.cycle && ack.items.map(_.id) == List(root); case _ => false })
        _ <- assertIO(lineage(stamped).contains(LineageEntry(LineageMember.Change(change.request), Some(member(grandchild)), true)))
        // Without a cycle ID a delegated session is not attributed; with one, its out-of-set write is rejected at admission.
        plain <- replace(service, child, stranger, "Carries no cycle ID").flatMap(service.change(child, _))
        _ <- assertIO(plain.items.map(_.id) == List(stranger))
        foreign <- replace(service, outsider, root, "A session outside the lineage").flatMap(change => act(service, outsider, DriverSession.Change(two.cycle, change)).either)
        _ <- assertIO(denied(foreign))
        _ <- failed(service, w, key, "a session outside the lineage of cycle 1 presented its cycle ID")
        ended <- replace(service, grandchild, root, "Ended lineage").flatMap(change => act(service, grandchild, DriverSession.Change(two.cycle, change)).either)
        _ <- assertIO(denied(ended) && fault(ended).exists(_.toString.contains("ended lineage")))
        settled <- act(service, grandchild, DriverSession.Settle(two.cycle, work))
        _ <- assertIO(settled == DriverReply.Lineage(two.cycle, LineageEntry(work, Some(member(grandchild)), true)))
        // A delegated child's out-of-set change and a non-selected creation, each carrying the cycle ID.
        _ <- start(service, w, key, workset(root)).flatMap(started => act(service, w.governor, DriverSession.Bind(started.bind.get)))
        third <- directive(service, w, key).flatMap(value => submit(service, w, w.governor, value.directive.text))
        _ <- act(service, w.governor, DriverSession.Inherit(third.cycle, LineageMember.Run(third.run), member(child)))
        outside <- replace(service, child, stranger, "Out-of-set change by a delegated child")
        _ <- rejects(service, w, key, "out-of-set change: T1 is outside the advanceable set stored for cycle 1")(act(service, child, DriverSession.Change(third.cycle, outside)))
        _ <- start(service, w, key, workset(root)).flatMap(started => act(service, w.governor, DriverSession.Bind(started.bind.get)))
        fourth <- directive(service, w, key).flatMap(value => submit(service, w, w.governor, value.directive.text))
        _ <- act(service, w.governor, DriverSession.Inherit(fourth.cycle, LineageMember.Run(fourth.run), member(child)))
        _ <- rejects(service, w, key, "non-selected creation")(act(service, child, DriverSession.Change(fourth.cycle, request(List(Mutation.Create(task("Unattached"))), Nil))))
      } yield ()
    }

    "confirm after a cycle that everything it created is in the recomputed set" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("post-cycle")
      for {
        _ <- service.initialize(w.operator, "post-cycle")
        root <- create(service, w.operator, goal("Goal"))
        _ <- driven(service, w, key, workset(root))
        (_, produced) <- produce(service, w.governor, root, "Created by the cycle")
        child = produced.items.find(_.id != root).get.id
        // Another session detaches the created item before the cycle ends.
        _ <- reference(service, w.operator, child, Relation.DerivedFrom, root, false).flatMap(service.change(w.operator, _))
        after <- query(service, w, key)
        _ <- assertIO(after match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, "T1 created by cycle 1 is not in the recomputed advanceable set"), Some(value), messages) =>
            stopped(Some(value), DriverStop.Failure) && messages.size == 1
          case _ => false
        })
      } yield ()
    }

    "stop with each explicit reason and never answer for the user" in { (service: LedgerService[IO]) =>
      val w = world
      def decision(name: String, targets: ItemId*): IO[Throwable, DriverReply] = {
        val session = w.copy(governor = w.other(Role.Governor))
        on(service, session, claude(name), workset(targets*)) *> query(service, session, claude(name))
      }
      def reason(reply: DriverReply): Option[DriverStop] = reply match {
        case DriverReply.Stop(value, Some(found), messages) if stopped(Some(found), value.reason) && messages.lastOption.contains(DriverPolicy.stopMessage(value)) => Some(value.reason)
        case _ => None
      }
      for {
        _ <- service.initialize(w.operator, "stop reasons")
        done <- create(service, w.operator, task("Done").copy(content = Content.Task(TaskStatus.Done, List("Acceptance"), None, Nil)))
        asked <- create(service, w.operator, question("Open question"))
        blocked <- create(service, w.operator, task("Blocked by the question"))
        _ <- reference(service, w.operator, blocked, Relation.BlockedBy, asked, true).flatMap(service.change(w.operator, _))
        ready <- create(service, w.operator, task("Ready"))
        quiescent <- decision("quiescent", done)
        direct <- decision("question", asked)
        indirect <- decision("blocked", blocked)
        mixed <- decision("mixed", ready, asked)
        _ <- assertIO(reason(quiescent).contains(DriverStop.Quiescent) && reason(direct).contains(DriverStop.UserInputRequired) &&
          reason(indirect).contains(DriverStop.UserInputRequired) && mixed.isInstanceOf[DriverReply.Continue])
        _ <- assertIO(direct match { case DriverReply.Stop(DriverStopped(_, detail), _, _) => detail == "Awaiting the user on Q1; the driver never answers questions or infers approval"; case _ => false })
        open <- service.get(w.operator, asked)
        _ <- assertIO(open.item.revision == Revision(2) && LedgerPolicy.status(open.item.draft.content) == QuestionStatus.Open.toString)
        // Limit: every start and resume directive counts.
        limited = w.copy(governor = w.other(Role.Governor))
        one <- driven(service, limited, claude("limit"), workset(ready))
        _ <- act(service, limited.governor, DriverSession.Inherit(one.cycle, LineageMember.Run(one.run), LineageMember.Attempt(AttemptId(uuid))))
        resumes <- ZIO.foreach((2 to DriverPolicy.MaxDirectives).toList)(_ => query(service, limited, claude("limit")))
        _ <- assertIO(resumes.forall { case DriverReply.Continue(value, _, _) => value.token.isInstanceOf[CycleToken.Resume]; case _ => false } &&
          resumes.map { case DriverReply.Continue(value, _, _) => value.token; case other => other }.distinct.size == resumes.size)
        exhausted <- query(service, limited, claude("limit"))
        _ <- assertIO(reason(exhausted).contains(DriverStop.LimitReached) && (exhausted match { case DriverReply.Stop(_, Some(value), _) => value.directives == DriverPolicy.MaxDirectives; case _ => false }))
        notBound <- start(service, w, claude("not-bound"), workset(ready)) *> query(service, w, claude("not-bound"))
        skipped <- on(service, w, claude("failure"), workset(ready)) *> directive(service, w, claude("failure")) *> query(service, w, claude("failure"))
        parked <- start(service, w, claude("parked"), workset(ready)) *> park(service, w, claude("parked"))
        unknown <- query(service, w, claude("unknown-session"))
        none <- status(service, w, claude("unknown-session"))
        _ <- assertIO(reason(notBound).contains(DriverStop.NotBound) && reason(skipped).contains(DriverStop.Failure) &&
          (parked match { case DriverReply.Parked(Some(value), "CQ driver parked: T3 through work") => stopped(Some(value), DriverStop.Parked); case _ => false }) &&
          unknown == DriverReply.Stop(DriverStopped(DriverStop.Off, "No CQ driver is on for this session"), None, Nil) && none.isEmpty)
        _ <- assertIO(DriverStop.all.map(DriverPolicy.reason) == List("quiescent", "user input required", "limit reached", "not bound", "failure", "parked", "off"))
      } yield ()
    }

    "register work dispatched from a driven run under its cycle and settle it when the host reports it done" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
        integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val clock = Clock.systemUTC()
        val operatorToken = "driver-lineage-test-operator-token"
        val authorization = new Authorization(AccessConfig(operatorToken, "http://localhost"), clock)
        val root = authorization.authenticate(operatorToken, Some(uuid.toString))
        val w = world.copy(operator = root.scope(ProjectId(uuid)))
        val session = w.copy(governor = Scope(w.project, Actor("attached governor", SessionId(uuid), Role.Governor)))
        val authority = authorization.authenticate(authorization.grant(root, GrantRequest(w.project, session.governor.actor, clock.millis() + 60000)).value, None)
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization)
        val key = claude("tracker")
        val attempt = LineageMember.Attempt(AttemptId(uuid))
        val dispatch = LineageMember.Request(RequestId(uuid))
        for {
          runtime <- ZIO.runtime[Any]
          _ <- ledger.initialize(session.operator, "tracker")
          target <- create(ledger, session.operator, goal("Goal"))
          one <- driven(ledger, session, key, workset(target))
          reports <- zio.Ref.make(List.empty[String])
          tracker = new LineageTracker(new DriverSessionClient(new ApplicationApi(application, authority, runtime), w.project),
            message => Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(reports.update(message :: _)).getOrThrowFiberFailure() })
          finished <- Promise.make[Nothing, Unit]
          run = LineageMember.Run(one.run)
          _ <- tracker.record(one.cycle, run, dispatch)
          _ <- tracker.track(one.cycle, dispatch, attempt, finished.await)
          _ <- tracker.track(one.cycle, dispatch, attempt, ZIO.unit)
          running <- status(ledger, session, key)
          _ <- assertIO(lineage(running).contains(LineageEntry(attempt, Some(dispatch), false)) && running.exists(_.activeChildren == 1))
          resumed <- query(ledger, session, key)
          _ <- assertIO(resumed match { case DriverReply.Continue(value, _, _) => value.token.isInstanceOf[CycleToken.Resume]; case _ => false })
          _ <- finished.succeed(())
          settled <- (ZIO.sleep(zio.Duration.fromMillis(50)) *> status(ledger, session, key)).repeatUntil(value => lineage(value).filter(_.member != run).forall(_.settled)).timeoutFail(new IllegalStateException("Lineage was not settled"))(zio.Duration.fromSeconds(20))
          _ <- assertIO(settled.exists(_.activeChildren == 0) && lineage(settled).map(_.member).toSet == Set(run, dispatch, attempt))
          _ <- tracker.track(CycleId(uuid), run, LineageMember.Attempt(AttemptId(uuid)), ZIO.unit)
          problems <- reports.get
          _ <- assertIO(problems.size == 1 && problems.head.startsWith("Driver lineage registration failed"))
        } yield ()
    }
  }
}

final class DriverContractDummy extends DriverContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class DriverContractPostgres extends DriverContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
