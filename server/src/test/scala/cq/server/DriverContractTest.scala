package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.core.DriverRecords.*
import cq.host.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, Promise, Runtime, Unsafe, ZIO}

abstract class DriverContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[DriverInspector] },
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ProposalService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Pause = zio.Duration.fromMillis(5)
  private final case class World(operator: Scope, governor: Scope) {
    val project: ProjectId = operator.project
    def other(role: Role): Scope = Scope(project, Actor("another session", SessionId(UUID.randomUUID()), role))
  }
  private def world: World = {
    val project = ProjectId(uuid)
    World(Scope(project, Actor("operator", SessionId(uuid), Role.Human)), Scope(project, Actor("attached governor", SessionId(uuid), Role.Governor)))
  }
  private def claude(name: String): DriverKey = DriverKey(Harness.Claude, name)
  // The harness whose idle session nothing wakes: work in flight is answered with a resume directive there.
  private def codex(name: String): DriverKey = DriverKey(Harness.Codex, name)
  private val Invocations = Set("/cq:advance", "$cq-advance")
  private def invocation(text: String): String = text.takeWhile(_ != ' ').ensuring(Invocations, text)
  private def task(title: String): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil), Nil)
  private def goal(title: String): ItemDraft = task(title).copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"))
  private def question(title: String): ItemDraft = task(title).copy(content = Content.Question(QuestionStatus.Open, "Prompt", "Context", Nil, None, None))
  private def defect(title: String): ItemDraft = task(title).copy(content = Content.Defect(DefectStatus.Open, Severity.Medium, "Observed", "Expected", "Reproduction", None, Nil))
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
    change = request(List(Mutation.Produce(producer, current.item.revision, List(task(title)), None)), List(claim.fence))
    ack <- service.change(scope, change).ensuring(service.release(scope, claim.fence).ignore)
  } yield (change, ack)
  private def milestone(title: String, status: MilestoneStatus): ItemDraft = task(title).copy(content = Content.Milestone(status, "Deliver the planned tasks"))
  // A change made under a producer claim that is held only for the write.
  private def producing(service: LedgerService[IO], scope: Scope, producer: ItemId)(mutations: Revision => List[Mutation]): IO[Throwable, ChangeAck] = for {
    claim <- service.acquire(scope, ClaimId(uuid), Set(producer), 600000L)
    current <- service.get(scope, producer)
    ack <- service.change(scope, request(mutations(current.item.revision), List(claim.fence))).ensuring(service.release(scope, claim.fence).ignore)
  } yield ack
  private def assigning(producer: ItemId, title: String, target: MilestoneRef)(revision: Revision): List[Mutation] =
    List(Mutation.Produce(producer, revision, List(task(title)), Some(target)))
  private def cursor(service: LedgerService[IO], w: World): IO[Throwable, ChangeCursor] = service.counts(w.operator).map(_.cursor)

  private def control(service: LedgerService[IO], w: World, key: DriverKey, origin: DriverOrigin, action: DriverControl): IO[Throwable, DriverReply] =
    service.drive(w.operator, DriverRequest.Control(key, origin, action))
  private def start(service: LedgerService[IO], w: World, key: DriverKey, target: WorksetTarget): IO[Throwable, DriverReply.Started] =
    control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Start(target, None)).map(_.asInstanceOf[DriverReply.Started])
  private def park(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, DriverReply] =
    control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Park())
  private def query(service: LedgerService[IO], w: World, key: DriverKey): IO[Throwable, DriverReply] =
    // As the callers of the query do: a session of a harness that is woken, with its waiter running, accepts Waiting; a Codex session never does.
    control(service, w, key, DriverOrigin.Stop, DriverControl.Continue(key.harness != Harness.Codex))
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
    val (workflow, token) = submitted(w.project, text, invocation(text))
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

  // What the attached host reports for one child attempt of a driven run: the dispatch request, the attempt, and how the attempt ended.
  private def concluded(service: LedgerService[IO], w: World, cycle: Driven, members: List[ItemId], end: ChildEnd, input: Option[String], fault: Option[String]): IO[Throwable, ChildOutcome] = {
    val request = LineageMember.Request(RequestId(uuid))
    val outcome = ChildOutcome(AttemptId(uuid), members, end, input, fault)
    for {
      _ <- act(service, w.governor, DriverSession.Inherit(cycle.cycle, LineageMember.Run(cycle.run), request))
      _ <- act(service, w.governor, DriverSession.Settle(cycle.cycle, request))
      _ <- act(service, w.governor, DriverSession.Inherit(cycle.cycle, request, LineageMember.Attempt(outcome.attempt)))
      _ <- act(service, w.governor, DriverSession.Conclude(cycle.cycle, outcome))
    } yield outcome
  }
  private def fault[A](result: Either[Throwable, A]): Option[Fault] = result.left.toOption.collect { case DomainFailure(value) => value }
  private def invalid[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Invalid])
  private def missing[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Missing])
  private def denied[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Denied])
  private def conflict[A](result: Either[Throwable, A]): Boolean = fault(result).exists(_.isInstanceOf[Fault.Conflict])
  private def stopped(value: Option[DriverStatus], reason: DriverStop): Boolean =
    value.exists(found => found.state == DriverState.Off && found.attached.isEmpty == (reason != DriverStop.UserInputRequired) && found.stopped.exists(_.reason == reason))
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

  private val WithdrawalRefused =
    "The CQ driver never settles Questions: Q1 would be withdrawn by a driven session; park the driver before withdrawing a Question"
  private def refused(result: Either[Throwable, ChangeAck]): IO[Throwable, Unit] = result match {
    case Left(DomainFailure(Fault.Denied(WithdrawalRefused))) => ZIO.unit
    case Left(other) => ZIO.fail(new AssertionError(s"The driven withdrawal failed otherwise: $other"))
    case Right(admitted) => ZIO.fail(new AssertionError(s"The driven withdrawal was admitted: $admitted"))
  }
  // An active driven cycle over a ready Task and an open Question. `attempt` receives the Question at its current revision and must write nothing:
  // afterwards the ledger is unchanged, the Question is Open, the driver is on, and once the Task is done its next continuation awaits the user.
  private def waiting(service: LedgerService[IO], name: String, prepare: (World, ItemId) => IO[Throwable, Unit] = (_, _) => ZIO.unit)(
    attempt: (World, ItemId, Item) => IO[Throwable, Unit]): IO[Throwable, Unit] = {
    val w = world
    val key = claude(name)
    for {
      _ <- service.initialize(w.operator, name)
      asked <- create(service, w.operator, question("Open question"))
      ready <- create(service, w.operator, task("Ready"))
      _ <- prepare(w, asked)
      _ <- driven(service, w, key, workset(ready, asked))
      open <- service.get(w.operator, asked).map(_.item)
      before <- cursor(service, w)
      _ <- attempt(w, asked, open)
      after <- cursor(service, w)
      current <- service.get(w.operator, asked).map(_.item)
      kept <- status(service, w, key)
      _ <- assertIO(before == after && current.revision == open.revision && !current.draft.archived &&
        LedgerPolicy.status(current.draft.content) == QuestionStatus.Open.toString && kept.exists(_.state == DriverState.On))
      // The operator, outside the drive, finishes the only other ready work.
      task <- service.get(w.operator, ready).map(_.item)
      _ <- service.change(w.operator, request(List(Mutation.Replace(ready, task.revision, task.draft.copy(content = Content.Task(TaskStatus.Done, List("Observed outcome"), None, Nil)))), Nil))
      continued <- query(service, w, key)
      _ <- assertIO(continued match {
        case DriverReply.Stop(DriverStopped(DriverStop.UserInputRequired, "Awaiting the user on Q1; the driver never answers questions or infers approval"), Some(value), _) =>
          stopped(Some(value), DriverStop.UserInputRequired)
        case _ => false
      })
    } yield ()
  }

  private def action(title: String, status: OperatorActionStatus = OperatorActionStatus.Requested, confirmation: Option[String] = None): ItemDraft =
    task(title).copy(content = Content.OperatorAction(status, "Rotate the credential", "The new credential's fingerprint", confirmation, Nil))
  private def settlementRefused(actions: String): String =
    s"The CQ driver never settles Operator Actions: $actions would be taken out of Requested by a driven session; park the driver before settling an Operator Action"
  // The outcome of one driven write that would take OA1 out of Requested. Anything but the driver's refusal fails with what happened instead.
  private def kept(path: String, result: Either[Throwable, Any]): IO[Throwable, Unit] = result match {
    case Left(DomainFailure(Fault.Denied(detail))) if detail == settlementRefused("OA1") => ZIO.unit
    case Left(DomainFailure(other)) => ZIO.fail(new AssertionError(s"$path: refused with another fault: $other"))
    case Left(other) => ZIO.fail(new AssertionError(s"$path: failed otherwise: $other"))
    case Right(admitted) => ZIO.fail(new AssertionError(s"$path: ADMITTED: $admitted"))
  }
  private final case class Requested(w: World, key: DriverKey, cycle: Driven, ready: ItemId, action: ItemId, item: Item)
  // An active driven cycle over a ready Task and a Requested Operator Action. `prepare` runs before the drive and returns further workset
  // targets. `attempt` must write nothing: afterwards the ledger is unchanged, the action is Requested at the same revision, the driver is on,
  // and once the Task is done its next continuation awaits the user on the action. A further target that `prepare` returns stays ready work,
  // so there the drive continues with one more cycle, and the stop follows that cycle changing nothing.
  private def requested(service: LedgerService[IO], name: String, prepare: (World, ItemId, ItemId) => IO[Throwable, List[ItemId]] = (_, _, _) => ZIO.succeed(Nil))(
    attempt: Requested => IO[Throwable, Unit]): IO[Throwable, Unit] = {
    val w = world
    val key = claude(name)
    for {
      _ <- service.initialize(w.operator, name)
      asked <- create(service, w.operator, action("Requested action"))
      ready <- create(service, w.operator, task("Ready"))
      targets <- prepare(w, ready, asked)
      cycle <- driven(service, w, key, workset((ready :: asked :: targets)*))
      open <- service.get(w.operator, asked).map(_.item)
      _ <- assertIO(!open.draft.archived && LedgerPolicy.status(open.draft.content) == OperatorActionStatus.Requested.toString)
      before <- cursor(service, w)
      _ <- attempt(Requested(w, key, cycle, ready, asked, open))
      after <- cursor(service, w)
      current <- service.get(w.operator, asked).map(_.item)
      on <- status(service, w, key)
      _ <- assertIO(before == after)
      _ <- assertIO(current.revision == open.revision && !current.draft.archived &&
        LedgerPolicy.status(current.draft.content) == OperatorActionStatus.Requested.toString)
      _ <- assertIO(on.exists(_.state == DriverState.On))
      // The operator, outside the drive, finishes the only other ready work.
      task <- service.get(w.operator, ready).map(_.item)
      _ <- service.change(w.operator, request(List(Mutation.Replace(ready, task.revision, task.draft.copy(content = Content.Task(TaskStatus.Done, List("Observed outcome"), None, Nil)))), Nil))
      _ <- ZIO.when(targets.nonEmpty)(directive(service, w, key).flatMap(issued => submit(service, w, w.governor, issued.directive.text)))
      continued <- query(service, w, key)
      _ <- continued match {
        case DriverReply.Stop(DriverStopped(DriverStop.UserInputRequired, "Awaiting the user on OA1; the driver never answers questions or infers approval"), Some(value), _)
          if stopped(Some(value), DriverStop.UserInputRequired) => ZIO.unit
        case other => ZIO.fail(new AssertionError(s"The continuation did not await the user on OA1: $other"))
      }
    } yield ()
  }
  private def moved(item: Item, status: OperatorActionStatus, confirmation: Option[String] = None): ItemDraft =
    item.draft.copy(content = Content.OperatorAction(status, "Rotate the credential", "The new credential's fingerprint", confirmation, Nil))

  // An admitted Planner result whose proposal the world's governor may apply; the driver's target is the first dispatch member.
  private def proposed(service: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO],
    name: String, drafts: List[ItemDraft], begun: Boolean)(mutations: List[ItemRevision] => List[ProposedMutation]): IO[Throwable, (World, DriverKey, Option[Driven], ArtifactId, List[ItemRevision])] = {
    val w = world
    val key = claude(name)
    val collector = w.governor.copy(actor = w.governor.actor.copy(subject = "host", role = Role.Collector))
    for {
      _ <- service.initialize(w.operator, name)
      created <- service.change(w.governor, request(drafts.map(Mutation.Create.apply), Nil))
      members = created.items
      claim <- service.acquire(w.governor, ClaimId(uuid), members.map(_.id).toSet, 300000)
      governing <- usage.assign(collector, Assignment(AssignmentId(uuid), w.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(uuid), governing.id, None, w.governor.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None))
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), w.project, claim.members, Attribution.Shared, Some(uuid), None))
      attempt <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, Some(parent.id), w.governor.actor.session, Role.Planner, Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Plan, None))
      dispatch = DispatchRequest(RequestId(uuid), DispatchWork.Planner(), Harness.Codex, members, Nil, Nil, None, claim.fence, HostLimits(3000, 1000, 300, 2000, 262144))
      report = ChildReport.Plan(members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Proposed next step")),
        Some(LedgerProposal(mutations(members), "Apply the proposed next step")), Nil)
      result = ChildResult(attempt.id, dispatch, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
      artifact <- artifacts.upload(collector, ArtifactUpload(w.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
      admission <- admissions.admit(collector, HostAdmissionInput(w.project, artifact.id, w.governor.actor))
      _ <- assertIO(admission.decision == AdmissionDecision.Accepted())
      cycle <- if (begun) driven(service, w, key, workset(members.head.id)).map(Some(_)) else on(service, w, key, workset(members.head.id)).as(None)
    } yield (w, key, cycle, artifact.id, members)
  }
  private def set(service: LedgerService[IO], scope: Scope, id: ItemId, content: Content): IO[Throwable, ChangeAck] =
    service.get(scope, id).flatMap(view => service.change(scope, request(List(Mutation.Replace(id, view.item.revision, view.item.draft.copy(content = content))), Nil)))
  private def tasked(status: TaskStatus): Content = Content.Task(status, List("Observed outcome"), None, Nil)
  private final case class Stranded(w: World, key: DriverKey, old: ItemId, open: ItemId, member: ItemId, sibling: ItemId, placed: ItemId, free: ItemId)
  // An active driven cycle over `roots` in a project where the Ready Task T1 (`member`) is PartOf M1 (`old`), which is `closed`. The state is
  // reached the way the closure gate permits: M1 is closed over Done Tasks and T1 is reopened afterwards. T2 (`sibling`) stays Done under M1,
  // T3 (`placed`) is Ready under the Open M2 (`open`) and T4 (`free`) is Ready without a milestone. The operator builds all of it before the drive.
  private def stranded(service: LedgerService[IO], name: String, closed: MilestoneStatus)(roots: Stranded => List[ItemId]): IO[Throwable, (Stranded, Driven)] = {
    val w = world
    val key = claude(name)
    def link(task: ItemId, target: ItemId) = reference(service, w.operator, task, Relation.PartOf, target, true).flatMap(service.change(w.operator, _))
    for {
      _ <- service.initialize(w.operator, name)
      old <- create(service, w.operator, milestone("Reassigned milestone", MilestoneStatus.Open))
      open <- create(service, w.operator, milestone("Open milestone", MilestoneStatus.Open))
      member <- create(service, w.operator, task("Reopened member"))
      sibling <- create(service, w.operator, task("Done member"))
      placed <- create(service, w.operator, task("Member of the Open milestone"))
      free <- create(service, w.operator, task("Task without a milestone"))
      _ <- link(member, old) *> link(sibling, old) *> link(placed, open)
      _ <- set(service, w.operator, member, tasked(TaskStatus.Done)) *> set(service, w.operator, sibling, tasked(TaskStatus.Done))
      _ <- set(service, w.operator, old, Content.Milestone(closed, "Deliver the planned tasks"))
      _ <- set(service, w.operator, member, tasked(TaskStatus.Ready))
      at = Stranded(w, key, old, open, member, sibling, placed, free)
      cycle <- driven(service, w, key, workset(roots(at)*))
    } yield (at, cycle)
  }
  // What Worker Implement admission answers for the Task on the current ledger state.
  private def admission(service: LedgerService[IO], scope: Scope, id: ItemId): IO[Throwable, Option[MilestoneRefusal]] = for {
    view <- service.get(scope, id)
    milestones <- ZIO.foreach(view.refs.collect { case ItemRef(Relation.PartOf, target) => target })(target => service.get(scope, target).map(found => target -> found.item))
    work = DispatchWork.Worker(WorkerMode.Implement)
    refusal = MilestonePolicy.refusal(work, view, milestones.toMap)
    // `admit` is the entry point selection verification and input assembly call; it throws the refusal.
    thrown <- ZIO.attempt(MilestonePolicy.admit(work, List(view), milestones.toMap)).either
    _ <- assertIO(fault(thrown) == refusal.map(value => Fault.Invalid(value.message)))
  } yield refusal
  // Runs independent scenarios to their end and reports every one that failed, by a fault or by a failed assertion.
  private def each(scenarios: (String, IO[Throwable, Any])*): IO[Throwable, Unit] =
    ZIO.foreach(scenarios.toList) { case (label, scenario) => scenario.absorb.either.map(_.left.toOption.map(error => s"$label: $error")) }.flatMap { outcomes =>
      ZIO.when(outcomes.flatten.nonEmpty)(ZIO.fail(new AssertionError(outcomes.flatten.mkString("\n")))).unit
    }

  // The driver operations of one session as its attached host calls them; `dropped` requests fail in transit before they reach the server.
  private final class SessionApi(service: LedgerService[IO], scope: Scope, runtime: Runtime[Any], dropped: DriverSession => Boolean) extends ServerApi {
    override def call(command: Command): Result = command match {
      case Command.Driver(DriverInput(_, DriverRequest.Session(action))) if dropped(action) => throw new java.io.IOException("Connection reset")
      case Command.Driver(DriverInput(_, request)) => Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.run(service.drive(scope, request).map(Result.Driver.apply).catchSome { case DomainFailure(fault) => ZIO.succeed(Result.Failed(fault)) }).getOrThrowFiberFailure()
      }
      case other => throw new IllegalStateException("Unexpected command " + other)
    }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("The driver publishes no usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("The driver publishes no artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("The driver admits no results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("The driver integrates no candidates")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("The driver issues no credentials")
  }
  private def host(service: LedgerService[IO], w: World, runtime: Runtime[Any], quiescent: () => Boolean): WorkflowActivations =
    new WorkflowActivations(new DriverSessionClient(new SessionApi(service, w.governor, runtime, _ => false), w.project),
      () => if (quiescent()) Nil else List("child attempt fixture (Running)"),
      (id, request, requirements, cycle) => WorkflowActivation(id, WorkflowContext(request, "Fixture instructions", None, ProcessMode.Rigorous), requirements, cycle))
  private def activation(text: String, project: ProjectId): (RequestId, WorkflowRequest, Option[CycleToken]) = {
    val (workflow, token) = submitted(project, text, invocation(text))
    (RequestId(uuid), workflow, Some(token))
  }

  private final class ApplicationApi(application: Application, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    override def call(command: Command): Result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(application.execute(authority, command)).getOrThrowFiberFailure() }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("The driver publishes no usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("The driver publishes no artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("The driver admits no results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("The driver integrates no candidates")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("The driver issues no credentials")
  }

  "The CQ driver core (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {

    "preserve committed driver and consumed tokens when service instances restart" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("durable-restart")
      for {
        _ <- service.initialize(w.operator, "durable restart")
        root <- create(service, w.operator, goal("Goal"))
        cycle <- driven(service, w, key, workset(root))
        before <- status(service, w, key)
        restarted = FixedLedger.at(repository, System.currentTimeMillis())
        after <- status(restarted, w, key)
        _ <- assertIO(after == before && after.exists(_.state == DriverState.On))
        replay <- activate(restarted, w.governor, cycle.run, cycle.workflow, Some(cycle.token))
        _ <- assertIO(replay == DriverActivation.Started(cycle.cycle))
        reused <- activate(restarted, w.governor, RequestId(uuid), cycle.workflow, Some(cycle.token)).either
        _ <- assertIO(denied(reused))
        _ <- failed(restarted, w, key, "already used")
      } yield ()
    }
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

    "roll back rejected writes and callbacks while committing only the driver's failure stop" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("durable-failure")
      val callbacks = new java.util.concurrent.atomic.AtomicInteger(0)
      for {
        project <- service.initialize(w.operator, "original")
        root <- create(service, w.operator, goal("Goal"))
        _ <- driven(service, w, key, workset(root))
        before <- repository.driverRecords(w.project)
        aborted <- repository.transact(w.project) { tx =>
          tx.putDriver(before.head.copy(touchedAt = before.head.touchedAt + 1))
          tx.afterCommit(() => { callbacks.incrementAndGet(); () })
          throw new java.io.IOException("ordinary abort")
        }.either
        unchanged <- repository.driverRecords(w.project)
        _ <- assertIO(aborted.isLeft && unchanged == before && callbacks.get() == 0)
        rejected <- repository.transact(w.project) { tx =>
          tx.renameProject(project.copy(name = "rejected"))
          tx.allocate(Ledger.Tasks)
          tx.afterCommit(() => { callbacks.incrementAndGet(); () })
          new DriverRegistry().fail(tx.driver(key).get, "durable rejection", System.currentTimeMillis())
        }.either
        after <- repository.driverRecords(w.project)
        metadata <- repository.transact(w.project)(_.project)
        first <- create(service, w.operator, task("First task"))
        _ <- assertIO(denied(rejected) && metadata == project && callbacks.get() == 0 && first.number == 1 &&
          after.head.state == DriverState.Off && after.head.stopped.exists(_.detail == "durable rejection") &&
          after.head.revision.value == before.head.revision.value + 1)
      } yield ()
    }

    "require the current driver revision and Human authority for browser park" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("browser-park")
      def listed: IO[Throwable, List[DriverSummary]] = service.drive(w.operator, DriverRequest.Summaries()).map {
        case DriverReply.Listed(values) => values
        case other => throw new IllegalStateException(other.toString)
      }
      for {
        _ <- service.initialize(w.operator, "browser park")
        root <- create(service, w.operator, goal("Goal"))
        _ <- on(service, w, key, workset(root))
        before <- listed
        wrong <- service.drive(w.governor, DriverRequest.Park(key, before.head.revision)).either
        _ <- directive(service, w, key)
        stale <- service.drive(w.operator, DriverRequest.Park(key, before.head.revision)).either
        current <- listed
        _ <- assertIO(denied(wrong) && conflict(stale) && current.head.state == DriverState.On && current.head.revision != before.head.revision)
        _ <- service.drive(w.operator, DriverRequest.Park(key, current.head.revision))
        after <- listed
        _ <- assertIO(after.head.state == DriverState.Off && after.head.stoppedAt.nonEmpty && after.head.attached.contains(w.governor.actor.session))
      } yield ()
    }

    "store the exact preview and browse only selected members in server order at one snapshot" in { (service: LedgerService[IO]) =>
      val w = world
      val order = ItemOrder(ItemOrderField.Id, SortDirection.Ascending, false)
      for {
        _ <- service.initialize(w.operator, "browser workset")
        root <- create(service, w.operator, goal("Goal"))
        produced <- produce(service, w.operator, root, "Child")
        child = produced._2.items.find(_.id.ledger == Ledger.Tasks).get.id
        outside <- create(service, w.operator, task("Outside"))
        preview <- service.previewWorkset(w.operator, workset(root))
        stored <- service.storeWorksetPreview(w.operator, preview)
        first <- service.browseWorkset(w.operator, "", order, None, None, 1, stored.id)
        second <- service.browseWorkset(w.operator, "", order, first.after, Some(first.cursor), 1, stored.id)
        _ <- assertIO(first.hasMore && !second.hasMore && (first.items ++ second.items).map(_.summary.id).toSet == Set(root, child))
        tasks <- service.browseWorkset(w.operator, "ledger:Tasks", order, None, None, 10, stored.id)
        _ <- assertIO(tasks.items.map(_.summary.id) == List(child) && !tasks.items.exists(_.summary.id == outside))
        _ <- create(service, w.operator, task("Later"))
        stale <- service.storeWorksetPreview(w.operator, preview).either
        moved <- service.browseWorkset(w.operator, "", order, first.after, Some(first.cursor), 1, stored.id).either
        _ <- assertIO(fault(stale).exists(_.isInstanceOf[Fault.Resync]) && fault(moved).exists(_.isInstanceOf[Fault.Resync]))
      } yield ()
    }

    "never reuse a driver revision after idle eviction and recreation of the same key" in { (repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("evicted")
      val begin = 1000000L
      def at(now: Long): LedgerService[IO] = FixedLedger.at(repository, now)
      def summary(service: LedgerService[IO]): IO[Throwable, DriverSummary] = service.drive(w.operator, DriverRequest.Summaries()).map {
        case DriverReply.Listed(values) => values.find(_.key == key).get
        case other => throw new IllegalStateException(other.toString)
      }
      for {
        _ <- at(begin).initialize(w.operator, "eviction revision")
        root <- create(at(begin), w.operator, goal("Goal"))
        _ <- start(at(begin), w, key, workset(root))
        before <- summary(at(begin))
        _ <- ZIO.foreachDiscard(2 to DriverPolicy.MaxDrivers)(index => start(at(begin + 1), w, claude(s"idle-$index"), workset(root)))
        late = at(begin + DriverPolicy.IdleMillis + 2)
        _ <- start(late, w, claude("displaces-oldest"), workset(root))
        _ <- start(late, w, key, workset(root))
        after <- summary(late)
        stale <- late.drive(w.operator, DriverRequest.Park(key, before.revision)).either
        _ <- assertIO(after.revision != before.revision && conflict(stale))
      } yield ()
    }

    "turn on only when exactly one attached session binds with the hook-minted single-use token" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
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
          _ <- act(service, second, DriverSession.Bind(other.bind.get))
          expiring = claude("binding-expired")
          offer <- start(service, w, expiring, workset(root))
          late = FixedLedger.service(repository, Clock.fixed(Instant.now().plusMillis(DriverPolicy.BindMillis + 60000), ZoneOffset.UTC))
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
          wrong <- ZIO.foreach(List(
            control(service, w, DriverKey(Harness.Pi, "pi-hook"), DriverOrigin.UserPromptSubmit, DriverControl.Start(workset(root), None)),
            control(service, w, DriverKey(Harness.Pi, "pi-unattached"), DriverOrigin.Extension, DriverControl.Start(workset(root), None)),
            control(service, w, claude("claude-attached"), DriverOrigin.UserPromptSubmit, DriverControl.Start(workset(root), Some(attached.actor.session))),
            control(service, w, claude("claude-extension"), DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session))),
            control(service, w, claude("claude-stop-start"), DriverOrigin.Stop, DriverControl.Start(workset(root), None)),
            control(service, w, key, DriverOrigin.UserPromptSubmit, DriverControl.Continue(false)),
            control(service, w, key, DriverOrigin.StatusLine, DriverControl.Park()),
          ))(_.either)
          kept <- status(service, w, key)
          _ <- assertIO(wrong.forall(invalid) && kept.exists(_.state == DriverState.On))
        } yield ()
    }

    "hold a bounded number of drivers per project and displace only off or silent ones" in { (repository: LedgerRepository[IO]) =>
      val w = world
      val begin = 1000000L
      def at(millis: Long): LedgerService[IO] = FixedLedger.service(repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC))
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
        records <- repository.driverRecords(w.project)
        _ <- assertIO(kept.exists(_.state == DriverState.On) && records.size == DriverPolicy.MaxDrivers &&
          records.count(_.touchedAt == begin) == DriverPolicy.MaxDrivers - 2)
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
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization, new CatalogRead(new McpSchemas()))
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
          continued <- ZIO.attemptBlocking(entry.continuation(stop(a), false))
          afterDirective <- statusOf(b)
          _ <- assertIO(continued.isInstanceOf[DriverReply.Continue] && afterDirective == beforeB)
          _ <- ZIO.attemptBlocking(entry.park(a))
          parkedA <- statusOf(a)
          afterPark <- statusOf(b)
          _ <- assertIO(stopped(parkedA, DriverStop.Parked) && afterPark == beforeB)
          _ <- bound(a, "G1 through=plan", sessionA)
          runningA <- statusOf(a)
          directive <- ZIO.attemptBlocking(entry.continuation(stop(b), false))
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
            () => entry.continuation(b.copy(event = "SessionStart"), false),
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
            () => modelFacing.continuation(stop(b), false), () => modelFacing.status(b.copy(event = "StatusLine"))))(operation => ZIO.attemptBlocking(operation()).either)
          stillA <- statusOf(a)
          stillB <- statusOf(b)
          _ <- assertIO(refused.forall(denied) && stillA == forgedA && stillB == runningB)
          ownStatus <- ZIO.attemptBlocking(sessionA.status)
          _ <- assertIO(ownStatus match { case DriverReply.Status(Some(value)) => value.key == DriverKey(Harness.Claude, "session-a") && value.state == DriverState.Off; case _ => false })
          schemas = new McpSchemas()
          sessionTool = schemas.attachedTools.find(_.hcursor.get[String]("name").contains("session")).get
          variants = sessionTool.hcursor.downField("inputSchema").get[List[io.circe.Json]]("oneOf").toOption.get.flatMap(_.hcursor.get[List[String]]("required").toOption.get)
          _ <- assertIO(variants.toSet == Set("Context", "Workflow", "Instructions", "Bind", "Driver"))
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
        extension <- control(service, w, pi, DriverOrigin.Extension, DriverControl.Continue(false))
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

    "Q53: continue a drive whose cycle changed nothing but left a failed attempt retryable, and stop it when the next cycle fails on the same input" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("retried")
      val retry = "CQ driver: cycle 1 changed nothing, and its work on G1 failed without a result; the same input is offered again with the fault"
      for {
        _ <- service.initialize(w.operator, "retried")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        first <- concluded(service, w, one, List(root), ChildEnd.Retryable, Some("input-a"), Some("Malformed report at line 3, column 14"))
        running <- status(service, w, key)
        _ <- assertIO(running.exists(_.activeChildren == 0) && lineage(running).contains(LineageEntry(LineageMember.Attempt(first.attempt), lineage(running).lift(1).map(_.member), true)))
        second <- directive(service, w, key)
        _ <- assertIO(second.messages == List(retry) && second.status.cycle.exists(_.number == 2) && second.status.directives == 2)
        // The stored record carries the outcomes: a server that restarts between the two cycles decides the same.
        stored <- repository.driverRecords(w.project).map(_.find(_.key == key).get)
        _ <- assertIO(stored.cycle.exists(cycle => cycle.retried == List(first) && cycle.outcomes.isEmpty) &&
          Wire.decode(DriverRecord_JsonCodec, Wire.encode(DriverRecord_JsonCodec, stored)) == stored)
        restarted = FixedLedger.at(repository, System.currentTimeMillis())
        two <- submit(restarted, w, w.governor, second.directive.text)
        again <- concluded(restarted, w, two, List(root), ChildEnd.Retryable, Some("input-a"), Some("Malformed report at line 9, column 2"))
        listed <- restarted.drive(w.operator, DriverRequest.Summaries()).map { case DriverReply.Listed(values) => values.find(_.key == key).get; case other => throw new IllegalStateException(other.toString) }
        snapshot <- restarted.drive(w.operator, DriverRequest.Snapshot(key, listed.revision))
        _ <- assertIO(snapshot match { case DriverReply.Snapshot(_, outcomes) => outcomes == List(again); case _ => false })
        stop <- query(restarted, w, key)
        detail = s"G1 failed without a result twice on the same input while no cycle in between changed anything: " +
          s"attempt ${first.attempt.value}: Malformed report at line 3, column 14; attempt ${again.attempt.value} of cycle 2: Malformed report at line 9, column 2"
        _ <- assertIO(stop match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, found), Some(value), List(message)) =>
            found == detail && stopped(Some(value), DriverStop.Failure) && value.directives == 2 && message == "CQ driver stopped (failure): " + detail
          case _ => false
        })
        after <- repository.driverRecords(w.project).map(_.find(_.key == key).get)
        _ <- assertIO(after.cycle.exists(cycle => cycle.state == CycleState.Ended && cycle.outcomes == List(again) && cycle.retried == List(first)) &&
          Wire.decode(DriverRecord_JsonCodec, Wire.encode(DriverRecord_JsonCodec, after)) == after)
      } yield ()
    }
    "Q53: retry a failed input once per unchanged cycle, and stay quiescent after a cycle whose failed input is not offered again" in { (service: LedgerService[IO]) =>
      val w = world
      val unchanged = "The previous cycle changed nothing in the advanceable set, its context or its readiness"
      // One driven cycle that changes nothing in the ledger and ends with `attempts`, then the next continuation query.
      def after(name: String, attempts: List[(ChildEnd, Option[String], Option[String])]): IO[Throwable, DriverReply] = {
        val session = w.copy(governor = w.other(Role.Governor))
        for {
          root <- create(service, w.operator, goal(name))
          one <- driven(service, session, claude(name), workset(root))
          _ <- ZIO.foreachDiscard(attempts)((end, input, fault) => concluded(service, session, one, List(root), end, input, fault))
          reply <- query(service, session, claude(name))
        } yield reply
      }
      def quiescent(reply: DriverReply): Boolean = reply match { case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, detail), _, _) => detail == unchanged; case _ => false }
      val fault = Some("Process exited with status 1")
      for {
        _ <- service.initialize(w.operator, "retry-classes")
        none <- after("no-attempt", Nil)
        admitted <- after("admitted", List((ChildEnd.Admitted, Some("input"), None)))
        cancelled <- after("cancelled", List((ChildEnd.Cancelled, Some("input"), None)))
        unknown <- after("unknown", List((ChildEnd.Unknown, Some("input"), None)))
        deferred <- after("deferred", List((ChildEnd.Failed, Some("input"), fault)))
        unselected <- after("unselected", List((ChildEnd.Failed, None, fault)))
        // The input failed and was then attempted again in the same cycle with an admitted result or a cancellation: the host keeps it deferred.
        resolved <- after("resolved", List((ChildEnd.Retryable, Some("input"), fault), (ChildEnd.Admitted, Some("input"), None)))
        abandoned <- after("abandoned", List((ChildEnd.Retryable, Some("input"), fault), (ChildEnd.Cancelled, Some("input"), None)))
        _ <- assertIO(List(none, admitted, cancelled, unknown, deferred, unselected, resolved, abandoned).forall(quiescent))
        // An abstained attempt is recorded, and is no failed input to retry: the drive does not continue on it.
        abstained <- after("abstained", List((ChildEnd.Abstained, Some("input"), Some("Abstained (Quota): Quota exceeded. Check your plan and billing details."))))
        _ <- assertIO(abstained match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) =>
            detail.startsWith("No configured model could run ") && detail.endsWith(": Abstained (Quota): Quota exceeded. Check your plan and billing details.")
          case _ => false
        })
        // One retryable input among others continues the drive, naming it.
        mixed <- after("mixed", List((ChildEnd.Admitted, Some("other"), None), (ChildEnd.Retryable, Some("input"), fault)))
        _ <- assertIO(mixed match { case DriverReply.Continue(_, value, List(message)) => value.cycle.exists(_.number == 2) && message.contains("its work on G"); case _ => false })
        // A failure on another input in the next cycle is a first failure of that input: the drive continues, and stops quiescent after a cycle without attempts.
        session = w.copy(governor = w.other(Role.Governor))
        key = claude("other-input")
        root <- create(service, w.operator, goal("other-input"))
        one <- driven(service, session, key, workset(root))
        _ <- concluded(service, session, one, List(root), ChildEnd.Retryable, Some("input-a"), fault)
        second <- directive(service, session, key)
        two <- submit(service, session, session.governor, second.directive.text)
        _ <- concluded(service, session, two, List(root), ChildEnd.Retryable, Some("input-b"), fault)
        third <- directive(service, session, key)
        _ <- assertIO(third.status.cycle.exists(_.number == 3) && third.messages.exists(_.startsWith("CQ driver: cycle 2 changed nothing")))
        // The first input fails again in the third cycle: alternating inputs do not keep the drive going, since no cycle between changed anything.
        three <- submit(service, session, session.governor, third.directive.text)
        _ <- concluded(service, session, three, List(root), ChildEnd.Retryable, Some("input-a"), fault)
        alternated <- query(service, session, key)
        _ <- assertIO(alternated match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) =>
            detail.startsWith(s"${DriverPolicy.references(List(root))} failed without a result twice on the same input while no cycle in between changed anything: attempt ") &&
              detail.contains(" of cycle 3: Process exited with status 1")
          case _ => false
        })
        // The host reports a repeated fault with the attempt's outcome: the drive stops at once, and a repeated report changes nothing.
        repeating = w.copy(governor = w.other(Role.Governor))
        repeatKey = claude("repeated-fault")
        target <- create(service, w.operator, goal("repeated-fault"))
        cycle <- driven(service, repeating, repeatKey, workset(target))
        request = LineageMember.Request(RequestId(uuid))
        attempt = AttemptId(uuid)
        _ <- act(service, repeating.governor, DriverSession.Inherit(cycle.cycle, LineageMember.Run(cycle.run), request))
        _ <- act(service, repeating.governor, DriverSession.Inherit(cycle.cycle, request, LineageMember.Attempt(attempt)))
        partial <- act(service, repeating.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(attempt, List(target), ChildEnd.Retryable, None, fault))).either
        unnamed <- act(service, repeating.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(attempt, Nil, ChildEnd.Admitted, None, None))).either
        foreign <- act(service, repeating.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(AttemptId(uuid), List(target), ChildEnd.Admitted, None, None))).either
        _ <- assertIO(invalid(partial) && invalid(unnamed) && missing(foreign))
        outcome = ChildOutcome(attempt, List(target), ChildEnd.Repeated, Some("input"), fault)
        reported <- act(service, repeating.governor, DriverSession.Conclude(cycle.cycle, outcome))
        expected = DriverStopped(DriverStop.Failure, s"attempt ${attempt.value} of cycle 1 failed on ${DriverPolicy.reference(target)} with the same fault as the attempt before it on the same input: Process exited with status 1")
        _ <- assertIO(reported match { case DriverReply.Stop(value, Some(found), Nil) => value == expected && stopped(Some(found), DriverStop.Failure); case _ => false })
        late <- act(service, repeating.governor, DriverSession.Conclude(cycle.cycle, outcome))
        off <- status(service, repeating, repeatKey)
        _ <- assertIO(late.isInstanceOf[DriverReply.Lineage] && off.flatMap(_.stopped).contains(expected))
      } yield ()
    }
    "I17: stop a drive with Failure when an unchanged cycle left an input no configured model could run, and count no abstention as a failure" in { (service: LedgerService[IO]) =>
      val w = world
      val unchanged = "The previous cycle changed nothing in the advanceable set, its context or its readiness"
      // One driven cycle that changes nothing in the ledger and ends with `attempts`, then the next continuation query.
      def after(name: String, attempts: List[(ChildEnd, Option[String], Option[String])]): IO[Throwable, (ItemId, DriverReply)] = {
        val session = w.copy(governor = w.other(Role.Governor))
        for {
          root <- create(service, w.operator, goal(name))
          one <- driven(service, session, claude(name), workset(root))
          _ <- ZIO.foreachDiscard(attempts)((end, input, fault) => concluded(service, session, one, List(root), end, input, fault))
          reply <- query(service, session, claude(name))
        } yield root -> reply
      }
      def quiescent(reply: DriverReply): Boolean = reply match { case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, detail), _, _) => detail == unchanged; case _ => false }
      def failure(reply: DriverReply): Option[String] = reply match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, List(message)) if message == "CQ driver stopped (failure): " + detail => Some(detail); case _ => None }
      def continued(reply: DriverReply): Boolean = reply match { case DriverReply.Continue(_, value, List(message)) => value.cycle.exists(_.number == 2) && message.contains("failed without a result"); case _ => false }
      val quota = Some("claude sonnet: Quota (usage limit reached); pi zai/glm-5.3: Unavailable (HTTP 529)")
      val launch = Some("codex gpt-6.1-sol: Launch (version mismatch)")
      val fault = Some("Process exited with status 1")
      for {
        _ <- service.initialize(w.operator, "abstentions")
        // An abstention followed by an admitted result, a cancellation or an unknown end on the same input is no failure of that input.
        resolved <- after("abstained-admitted", List((ChildEnd.Abstained, Some("input"), quota), (ChildEnd.Admitted, Some("input"), None)))
        cancelled <- after("abstained-cancelled", List((ChildEnd.Abstained, Some("input"), quota), (ChildEnd.Cancelled, Some("input"), None)))
        deferred <- after("abstained-failed", List((ChildEnd.Abstained, Some("input"), quota), (ChildEnd.Failed, Some("input"), fault)))
        _ <- assertIO(List(resolved, cancelled, deferred).map(_._2).forall(quiescent))
        // Every attempt on the input abstained: the stop names the items and why no model ran, the last report for the input.
        alone <- after("abstained", List((ChildEnd.Abstained, Some("input"), quota)))
        _ <- assertIO(failure(alone._2).contains(s"No configured model could run ${DriverPolicy.reference(alone._1)}: ${quota.get}"))
        twice <- after("abstained-twice", List((ChildEnd.Abstained, Some("input"), quota), (ChildEnd.Abstained, Some("input"), launch)))
        _ <- assertIO(failure(twice._2).contains(s"No configured model could run ${DriverPolicy.reference(twice._1)}: ${launch.get}"))
        several <- after("abstained-inputs", List((ChildEnd.Abstained, Some("input-a"), quota), (ChildEnd.Admitted, Some("other"), None), (ChildEnd.Abstained, Some("input-b"), launch)))
        _ <- assertIO(failure(several._2).contains(s"No configured model could run ${DriverPolicy.reference(several._1)}: ${quota.get}; ${DriverPolicy.reference(several._1)}: ${launch.get}"))
        // Abstentions are ignored when an input is judged retryable (Q53), in either order, and a retryable input continues the drive
        // although another input found no model.
        before <- after("abstained-retryable", List((ChildEnd.Abstained, Some("input"), quota), (ChildEnd.Retryable, Some("input"), fault)))
        behind <- after("retryable-abstained", List((ChildEnd.Retryable, Some("input"), fault), (ChildEnd.Abstained, Some("input"), quota)))
        beside <- after("retryable-beside", List((ChildEnd.Abstained, Some("input-a"), quota), (ChildEnd.Retryable, Some("input-b"), fault)))
        _ <- assertIO(List(before, behind, beside).map(_._2).forall(continued))
        // The input that failed once and then found no model is not counted as failing a second time: the stop says no model could run it.
        session = w.copy(governor = w.other(Role.Governor))
        key = claude("retried-abstained")
        root <- create(service, w.operator, goal("retried-abstained"))
        one <- driven(service, session, key, workset(root))
        _ <- concluded(service, session, one, List(root), ChildEnd.Retryable, Some("input"), fault)
        second <- directive(service, session, key)
        two <- submit(service, session, session.governor, second.directive.text)
        _ <- concluded(service, session, two, List(root), ChildEnd.Abstained, Some("input"), quota)
        ended <- query(service, session, key)
        _ <- assertIO(failure(ended).contains(s"No configured model could run ${DriverPolicy.reference(root)}: ${quota.get}"))
        // An abstained outcome carries its input fingerprint and the abstention text.
        other = w.copy(governor = w.other(Role.Governor))
        target <- create(service, w.operator, goal("abstained-shape"))
        cycle <- driven(service, other, claude("abstained-shape"), workset(target))
        request = LineageMember.Request(RequestId(uuid))
        attempt = AttemptId(uuid)
        _ <- act(service, other.governor, DriverSession.Inherit(cycle.cycle, LineageMember.Run(cycle.run), request))
        _ <- act(service, other.governor, DriverSession.Inherit(cycle.cycle, request, LineageMember.Attempt(attempt)))
        unnamed <- act(service, other.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(attempt, List(target), ChildEnd.Abstained, None, quota))).either
        silent <- act(service, other.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(attempt, List(target), ChildEnd.Abstained, Some("input"), None))).either
        _ <- assertIO(invalid(unnamed) && invalid(silent))
        // Unlike a repeated fault, an abstention reported for the active cycle does not stop the drive when it is reported.
        reported <- act(service, other.governor, DriverSession.Conclude(cycle.cycle, ChildOutcome(attempt, List(target), ChildEnd.Abstained, Some("input"), quota)))
        running <- status(service, other, claude("abstained-shape"))
        _ <- assertIO(reported.isInstanceOf[DriverReply.Lineage] && running.exists(_.stopped.isEmpty))
      } yield ()
    }
    "answer Waiting for work in flight only to a caller that accepts it, issuing nothing, and decide the cycle at the stop after it" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("waiting")
      val attempt = LineageMember.Attempt(AttemptId(uuid))
      def continuation(waiting: Boolean) = control(service, w, key, DriverOrigin.Stop, DriverControl.Continue(waiting))
      for {
        _ <- service.initialize(w.operator, "waiting")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, LineageMember.Run(one.run), attempt))
        before <- status(service, w, key)
        stops <- ZIO.foreach(List.fill(3)(()))(_ => continuation(true))
        after <- status(service, w, key)
        _ <- assertIO(stops.forall {
          case DriverReply.Waiting(value, message) => value.state == DriverState.On && value.directives == 1 && value.activeChildren == 1 &&
            message == s"CQ driver waiting: cycle 1 has attempt ${attempt.id.value} in flight; the session continues when it ends"
          case _ => false
        } && before == after && after.exists(value => value.cycle.exists(cycle => cycle.id == one.cycle && cycle.state == CycleState.Active)))
        // A caller that cannot vouch for a wake-up is not left waiting: the same session, whatever its harness, gets a resume directive.
        kept <- continuation(false)
        _ <- assertIO(kept match { case DriverReply.Continue(value, status, _) => value.token.isInstanceOf[CycleToken.Resume] && status.directives == 2; case _ => false })
        // The woken turn works inside the same cycle; with nothing in flight, its stop is decided as any other, whatever the caller accepts.
        _ <- act(service, w.governor, DriverSession.Settle(one.cycle, attempt))
        decided <- continuation(true)
        _ <- assertIO(decided match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, _), Some(value), _) => value.directives == 2 && value.cycle.exists(_.state == CycleState.Ended)
          case _ => false
        })
      } yield ()
    }
    "reattach a running cycle with a resume directive, keep exactly one run and reject swapped start and resume tokens" in { (service: LedgerService[IO]) =>
      val w = world
      val key = codex("resume")
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
        _ <- assertIO(resumed.directive.cycle == one.cycle && resumed.directive.text == s"$$cq-advance --roots G1 --through work --resume-token ${resume.value}" &&
          CycleToken.Start(resume) != one.token && resumed.status.directives == 2 && resumed.status.activeChildren == 1 &&
          resumed.status.line == "CQ driver on: G1 through work; 1 active child" && resumed.status.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.run.contains(one.run)))
        (workflow, token) = submitted(w.project, resumed.directive.text, "$cq-advance")
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
        resumeAsStart = codex("resume-swapped-resume")
        running = w.copy(governor = w.other(Role.Governor))
        active <- driven(service, running, resumeAsStart, workset(root))
        _ <- act(service, running.governor, DriverSession.Inherit(active.cycle, LineageMember.Run(active.run), attempt))
        issued <- directive(service, running, resumeAsStart)
        swapped = issued.directive.token.asInstanceOf[CycleToken.Resume].token
        _ <- rejects(service, running, resumeAsStart, "unknown start token")(
          activate(service, running.governor, RequestId(uuid), workflow, Some(CycleToken.Start(swapped))))
        single <- status(service, running, resumeAsStart)
        _ <- assertIO(single.exists(_.cycle.exists(cycle => cycle.run.contains(active.run) && cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1)))
        reuse = codex("resume-reused")
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
        def proposal(name: String, member: Int, begun: Boolean) = proposed(service, usage, artifacts, admissions, name, List.fill(2)(task("Proposal member")), begun)(
          members => List(ProposedMutation.Replace(members(member).id, task("Proposed title"))))
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

    "let a drive plan Tasks under an Open milestone outside its set, changing nothing else about that milestone" in {
      (service: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) =>
        val w = world
        val key = claude("milestone-existing")
        val direct = for {
          _ <- service.initialize(w.operator, "milestone-existing")
          root <- create(service, w.operator, goal("Goal"))
          open <- create(service, w.operator, milestone("Open milestone", MilestoneStatus.Open))
          before <- service.get(w.operator, open)
          _ <- driven(service, w, key, workset(root))
          ack <- producing(service, w.governor, root)(assigning(root, "Planned task", MilestoneRef.Existing(open)))
          planned = ack.items.map(_.id).find(_.ledger == Ledger.Tasks).get
          after <- service.get(w.operator, open)
          running <- status(service, w, key)
          _ <- assertIO(ack.items.map(_.id).toSet == Set(root, open, planned) && after.item.draft == before.item.draft && after.item.revision == Revision(2) &&
            after.refs == List(ItemRef(Relation.Contains, planned)) && running.exists(value => value.state == DriverState.On && value.cycle.exists(_.created == List(planned))))
          next <- directive(service, w, key)
          _ <- assertIO(next.messages == List("CQ driver: the advanceable set changed to 2 items; added T1") && !next.status.cycle.get.advanceable.exists(_.id == open))
          _ <- submit(service, w, w.governor, next.directive.text)
          change <- replace(service, w.governor, open, "The milestone's draft changed by the drive")
          _ <- rejects(service, w, key, "out-of-set change: M1 is outside the advanceable set stored for cycle 2")(service.change(w.governor, change))
        } yield ()
        val applied = for {
          (planning, planningKey, cycle, artifact, members) <- proposed(service, usage, artifacts, admissions, "milestone-existing-proposal",
            List(task("Producer"), milestone("Open milestone", MilestoneStatus.Open)), true)(members =>
            List(ProposedMutation.Produce(members.head.id, List(task("Planned task")), Some(MilestoneRef.Existing(members(1).id)))))
          ack <- proposals(planning.governor, artifact)
          stamped <- status(service, planning, planningKey)
          _ <- assertIO(ack.items.map(_.id).toSet == members.map(_.id).toSet + members.head.id.copy(number = 2) && stamped.exists(_.state == DriverState.On) &&
            lineage(stamped).contains(LineageEntry(LineageMember.Proposal(artifact), Some(LineageMember.Run(cycle.get.run)), true)))
        } yield ()
        // Both in one batch: the assignment does not admit another change of the same milestone.
        val edited = for {
          session <- ZIO.succeed(world)
          _ <- service.initialize(session.operator, "milestone-edited")
          root <- create(service, session.operator, goal("Goal"))
          open <- create(service, session.operator, milestone("Open milestone", MilestoneStatus.Open))
          current <- service.get(session.operator, open)
          _ <- driven(service, session, claude("milestone-edited"), workset(root))
          _ <- rejects(service, session, claude("milestone-edited"), "out-of-set change: M1 is outside the advanceable set stored for cycle 1")(
            producing(service, session.governor, root)(revision => Mutation.Replace(open, current.item.revision, current.item.draft.copy(title = "Edited")) ::
              assigning(root, "Planned task", MilestoneRef.Existing(open))(revision)))
        } yield ()
        val closed = for {
          session <- ZIO.succeed(world)
          _ <- service.initialize(session.operator, "milestone-closed")
          root <- create(service, session.operator, goal("Goal"))
          complete <- create(service, session.operator, milestone("Complete milestone", MilestoneStatus.Complete))
          _ <- driven(service, session, claude("milestone-closed"), workset(root))
          _ <- rejects(service, session, claude("milestone-closed"), "out-of-set change: M1 is outside the advanceable set stored for cycle 1")(
            producing(service, session.governor, root)(assigning(root, "Planned task", MilestoneRef.Existing(complete))))
        } yield ()
        each("direct Produce" -> direct, "applied proposal" -> applied, "milestone edited in the same batch" -> edited, "closed milestone" -> closed)
    }

    "admit the Milestone a batch creates for its in-set Produce and keep it in the cycle that created it" in {
      (service: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) =>
        val w = world
        val key = claude("milestone-created")
        def planning(root: ItemId)(revision: Revision): List[Mutation] =
          Mutation.Create(milestone("Planned milestone", MilestoneStatus.Open)) :: assigning(root, "Planned task", MilestoneRef.Created(0))(revision)
        val direct = for {
          _ <- service.initialize(w.operator, "milestone-created")
          root <- create(service, w.operator, goal("Goal"))
          _ <- driven(service, w, key, workset(root))
          ack <- producing(service, w.governor, root)(planning(root))
          made = ack.items.map(_.id).find(_.ledger == Ledger.Milestones).get
          planned = ack.items.map(_.id).find(_.ledger == Ledger.Tasks).get
          running <- status(service, w, key)
          _ <- assertIO(ack.items.map(_.id).toSet == Set(root, made, planned) && running.exists(value => value.state == DriverState.On && value.cycle.exists(_.created == List(made, planned))))
          own <- replace(service, w.governor, made, "The cycle may change the milestone it created").flatMap(service.change(w.governor, _))
          _ <- assertIO(own.items.map(_.id) == List(made))
          next <- directive(service, w, key)
          _ <- assertIO(next.messages == List("CQ driver: the advanceable set changed to 2 items; added T1") && !next.status.cycle.get.advanceable.exists(_.id == made))
          _ <- submit(service, w, w.governor, next.directive.text)
          later <- replace(service, w.governor, made, "A later cycle does not own the milestone")
          _ <- rejects(service, w, key, "out-of-set change: M1 is outside the advanceable set stored for cycle 2")(service.change(w.governor, later))
        } yield ()
        val applied = for {
          (session, sessionKey, cycle, artifact, members) <- proposed(service, usage, artifacts, admissions, "milestone-created-proposal", List(task("Producer"), task("Bystander")), true)(members =>
            List(ProposedMutation.Create(milestone("Planned milestone", MilestoneStatus.Open)),
              ProposedMutation.Produce(members.head.id, List(task("Planned task")), Some(MilestoneRef.Created(0)))))
          ack <- proposals(session.governor, artifact)
          stamped <- status(service, session, sessionKey)
          _ <- assertIO(ack.items.map(_.id).map(_.ledger).sortBy(_.toString) == List(Ledger.Milestones, Ledger.Tasks, Ledger.Tasks) &&
            stamped.exists(value => value.state == DriverState.On && value.cycle.exists(_.created.size == 2)) &&
            lineage(stamped).contains(LineageEntry(LineageMember.Proposal(artifact), Some(LineageMember.Run(cycle.get.run)), true)))
        } yield ()
        def refused(name: String, detail: String)(mutations: ItemId => Revision => List[Mutation]): IO[Throwable, Unit] = {
          val session = world
          for {
            _ <- service.initialize(session.operator, name)
            root <- create(service, session.operator, goal("Goal"))
            _ <- driven(service, session, claude(name), workset(root))
            _ <- rejects(service, session, claude(name), detail)(producing(service, session.governor, root)(mutations(root)))
          } yield ()
        }
        // A Created index that names a Task is an invalid request, not a boundary violation: nothing is written and the driver stays on.
        val misnamed = for {
          session <- ZIO.succeed(world)
          _ <- service.initialize(session.operator, "milestone-misnamed")
          root <- create(service, session.operator, goal("Goal"))
          _ <- driven(service, session, claude("milestone-misnamed"), workset(root))
          before <- cursor(service, session)
          result <- producing(service, session.governor, root)(revision => Mutation.Create(task("Not a milestone")) :: assigning(root, "Planned task", MilestoneRef.Created(0))(revision)).either
          after <- cursor(service, session)
          kept <- status(service, session, claude("milestone-misnamed"))
          _ <- assertIO(fault(result).contains(Fault.Invalid("Produce milestone must reference an earlier Create of a Milestone in this batch")) && before == after && kept.exists(_.state == DriverState.On))
        } yield ()
        each("direct Create and Produce" -> direct, "applied proposal" -> applied, "Created index naming a Task" -> misnamed,
          "milestone no Produce names" -> refused("milestone-unnamed", "non-selected creation: M1 is not a selected descendant of G1")(root => revision =>
            List(Mutation.Create(milestone("Unassigned milestone", MilestoneStatus.Open)), Mutation.Produce(root, revision, List(task("Planned task")), None))),
          "milestone created alone" -> refused("milestone-alone", "non-selected creation: M1 is not a selected descendant of G1")(_ => _ =>
            List(Mutation.Create(milestone("Unattached milestone", MilestoneStatus.Open)))))
    }

    "let an in-set Task join an Open milestone outside the set and reject every other reference to that milestone" in {
      (service: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) =>
        val w = world
        def begun(name: String, target: ItemId): IO[Throwable, (World, DriverKey)] = {
          val bound = w.copy(governor = w.other(Role.Governor))
          driven(service, bound, claude(name), workset(target)).as((bound, claude(name)))
        }
        val direct = for {
          _ <- service.initialize(w.operator, "milestone-joined")
          root <- create(service, w.operator, goal("Goal"))
          (_, first) <- produce(service, w.operator, root, "Task without a milestone")
          (_, second) <- produce(service, w.operator, root, "Another Task without a milestone")
          joining = first.items.find(_.id != root).get.id
          loose = second.items.find(_.id != root).get.id
          open <- create(service, w.operator, milestone("Open milestone", MilestoneStatus.Open))
          complete <- create(service, w.operator, milestone("Complete milestone", MilestoneStatus.Complete))
          before <- service.get(w.operator, open)
          (joined, joinedKey) <- begun("joined", root)
          ack <- reference(service, joined.governor, joining, Relation.PartOf, open, true).flatMap(service.change(joined.governor, _))
          after <- service.get(w.operator, open)
          running <- status(service, joined, joinedKey)
          _ <- assertIO(ack.items.map(_.id).toSet == Set(joining, open) && after.item.draft == before.item.draft && after.refs == List(ItemRef(Relation.Contains, joining)) &&
            running.exists(value => value.state == DriverState.On && value.cycle.exists(_.created.isEmpty)))
          inverse <- reference(service, joined.governor, open, Relation.Contains, loose, true).flatMap(service.change(joined.governor, _))
          _ <- assertIO(inverse.items.map(_.id).toSet == Set(loose, open))
          _ <- reference(service, w.operator, loose, Relation.PartOf, open, false).flatMap(service.change(w.operator, _))
          refusals = List[(String, String, Scope => IO[Throwable, ChangeRequest])](
            ("joined-closed", "out-of-set change: M2 is outside", reference(service, _, loose, Relation.PartOf, complete, true)),
            ("joined-related", "out-of-set change: M1 is outside", reference(service, _, loose, Relation.RelatesTo, open, true)),
            ("joined-removed", "out-of-set change: M1 is outside", reference(service, _, joining, Relation.PartOf, open, false)),
            ("joined-blocked", "out-of-set change: M1 is outside", reference(service, _, loose, Relation.BlockedBy, open, true)))
          _ <- ZIO.foreachDiscard(refusals) { case (name, detail, change) =>
            begun(name, root).flatMap { case (session, key) => change(session.governor).flatMap(value => rejects(service, session, key, detail)(service.change(session.governor, value))) }
          }
        } yield ()
        def proposal(name: String, status: MilestoneStatus) = proposed(service, usage, artifacts, admissions, name,
          List(task("Member"), milestone(s"$status milestone", status)), true)(members => List(ProposedMutation.Reference(members.head.id, Relation.PartOf, members(1).id, true)))
        val applied = for {
          (session, sessionKey, cycle, artifact, members) <- proposal("milestone-joined-proposal", MilestoneStatus.Open)
          ack <- proposals(session.governor, artifact)
          stamped <- status(service, session, sessionKey)
          _ <- assertIO(ack.items.map(_.id).toSet == members.map(_.id).toSet && stamped.exists(_.state == DriverState.On) &&
            lineage(stamped).contains(LineageEntry(LineageMember.Proposal(artifact), Some(LineageMember.Run(cycle.get.run)), true)))
        } yield ()
        val closed = for {
          (session, sessionKey, _, artifact, _) <- proposal("milestone-closed-proposal", MilestoneStatus.Complete)
          _ <- rejects(service, session, sessionKey, "out-of-set change: M1 is outside the advanceable set stored for cycle 1")(proposals(session.governor, artifact))
        } yield ()
        each("direct Reference" -> direct, "applied proposal" -> applied, "applied proposal to a closed milestone" -> closed)
    }

    // Defect 100, Question 27: a Task reopened under a closed milestone is recovered inside a Task-root drive by reassignment.
    "let a Task-root drive move its Task from a closed milestone outside the set to an Open one, one request each" in { (service: LedgerService[IO]) =>
      def recovered(name: String, closed: MilestoneStatus, inverse: Boolean): IO[Throwable, Unit] = for {
        (at, first) <- stranded(service, name, closed)(at => List(at.member))
        w = at.w
        refusal <- admission(service, w.operator, at.member)
        _ <- assertIO(refusal.map(_.reason).contains(CohortReason.ClosedMilestone))
        before <- ZIO.foreach(List(at.old, at.open, at.sibling, at.placed))(service.get(w.operator, _))
        removal <- if (inverse) reference(service, w.governor, at.old, Relation.Contains, at.member, false) else reference(service, w.governor, at.member, Relation.PartOf, at.old, false)
        removed <- service.change(w.governor, removal)
        old <- service.get(w.operator, at.old)
        loose <- service.get(w.operator, at.member)
        others <- ZIO.foreach(List(at.open, at.sibling, at.placed))(service.get(w.operator, _))
        running <- status(service, w, at.key)
        // Only the Task and its old milestone are revised; the milestone keeps its draft, and so its closed status.
        _ <- assertIO(removed.items.map(_.id).toSet == Set(at.member, at.old) && old.item.draft == before.head.item.draft &&
          old.item.revision == Revision(before.head.item.revision.value + 1) && old.refs == List(ItemRef(Relation.Contains, at.sibling)) && loose.refs.isEmpty)
        _ <- assertIO(others == before.tail && running.exists(found => found.state == DriverState.On && found.stopped.isEmpty))
        unassigned <- admission(service, w.operator, at.member)
        _ <- assertIO(unassigned.map(_.reason).contains(CohortReason.NoMilestone))
        // The assignment is a separate request: a batch changes the Task once.
        added <- reference(service, w.governor, at.member, Relation.PartOf, at.open, true).flatMap(service.change(w.governor, _))
        joined <- service.get(w.operator, at.open)
        kept <- status(service, w, at.key)
        _ <- assertIO(added.items.map(_.id).toSet == Set(at.member, at.open) && joined.item.draft == before(1).item.draft &&
          joined.refs.toSet == Set(ItemRef(Relation.Contains, at.member), ItemRef(Relation.Contains, at.placed)))
        _ <- assertIO(kept.exists(found => found.state == DriverState.On && found.stopped.isEmpty && found.cycle.exists(cycle => cycle.id == first.cycle &&
          Set(removal.request, added.request).forall(id => cycle.lineage.exists(_.member == LineageMember.Change(id))))))
        // The next continuation issues a new cycle over the Task.
        next <- directive(service, w, at.key)
        _ <- assertIO(next.status.cycle.exists(cycle => cycle.number == 2 && cycle.advanceable.map(_.id) == List(at.member)))
        second <- submit(service, w, w.governor, next.directive.text)
        _ <- assertIO(second.cycle == next.directive.cycle)
        accepted <- admission(service, w.operator, at.member)
        _ <- assertIO(accepted.isEmpty)
      } yield ()
      // Both in one request: the ledger refuses a batch that changes the Task twice. The driven session gets the undriven fault and the drive goes on.
      def combined(name: String, closed: MilestoneStatus): IO[Throwable, Unit] = for {
        (at, _) <- stranded(service, name, closed)(at => List(at.member))
        w = at.w
        current <- ZIO.foreach(List(at.member, at.old, at.open))(service.get(w.operator, _).map(_.item.revision))
        both = List(Mutation.Reference(at.member, current.head, Relation.PartOf, at.old, current(1), false),
          Mutation.Reference(at.member, current.head, Relation.PartOf, at.open, current(2), true))
        before <- cursor(service, w)
        undriven <- service.change(w.operator, request(both, Nil)).either
        bound <- service.change(w.governor, request(both, Nil)).either
        after <- cursor(service, w)
        kept <- status(service, w, at.key)
        _ <- assertIO(fault(undriven).contains(Fault.Invalid("An item may be changed only once in a batch")) && fault(bound) == fault(undriven))
        _ <- assertIO(before == after && kept.exists(found => found.state == DriverState.On && found.stopped.isEmpty))
        // The cycle is still active: the two requests then commit under it.
        _ <- reference(service, w.governor, at.member, Relation.PartOf, at.old, false).flatMap(service.change(w.governor, _))
        _ <- reference(service, w.governor, at.member, Relation.PartOf, at.open, true).flatMap(service.change(w.governor, _))
        next <- directive(service, w, at.key)
        _ <- assertIO(next.status.cycle.exists(cycle => cycle.number == 2 && cycle.advanceable.map(_.id) == List(at.member)))
      } yield ()
      each(List(MilestoneStatus.Complete, MilestoneStatus.Cancelled).flatMap(closed => List[(String, IO[Throwable, Any])](
        s"$closed, removed as PartOf" -> recovered(s"reassigned-partof-$closed", closed, false),
        s"$closed, removed as Contains" -> recovered(s"reassigned-contains-$closed", closed, true),
        s"$closed, removed and added in one request" -> combined(s"reassigned-combined-$closed", closed)))*)
    }

    "keep every other driven write that names a milestone outside the set an out-of-set change that stops the drive" in { (service: LedgerService[IO]) =>
      type Attempt = Stranded => IO[Throwable, ChangeRequest]
      def revision(scope: Scope, id: ItemId): IO[Throwable, Revision] = service.get(scope, id).map(_.item.revision)
      def single(mutation: IO[Throwable, Mutation]): IO[Throwable, ChangeRequest] = mutation.map(value => request(List(value), Nil))
      val alone: Stranded => List[ItemId] = at => List(at.member)
      // Each entry: the write, the detail of the stop, the roots of the drive and the request the bound session sends.
      val refusals = List[(String, String, Stranded => List[ItemId], Attempt)](
        ("remove an in-set Task from an Open milestone", "out-of-set change: M2 is outside the advanceable set stored for cycle 1", at => List(at.member, at.placed),
          at => reference(service, at.w.governor, at.placed, Relation.PartOf, at.open, false)),
        ("remove an in-set Task from an Open milestone, written as Contains", "out-of-set change: M2 is outside the advanceable set stored for cycle 1", at => List(at.member, at.placed),
          at => reference(service, at.w.governor, at.open, Relation.Contains, at.placed, false)),
        ("remove a Task outside the set from the closed milestone", "T2 is outside the advanceable set stored for cycle 1", alone,
          at => reference(service, at.w.governor, at.sibling, Relation.PartOf, at.old, false)),
        ("remove a Task outside the set, written as Contains", "T2 is outside the advanceable set stored for cycle 1", alone,
          at => reference(service, at.w.governor, at.old, Relation.Contains, at.sibling, false)),
        ("reopen the milestone by Replace", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => single(service.get(at.w.governor, at.old).map(view => Mutation.Replace(at.old, view.item.revision, milestone("Reassigned milestone", MilestoneStatus.Open))))),
        ("replace the milestone's draft, keeping its status", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => replace(service, at.w.governor, at.old, "Retitled by the drive")),
        ("restore the Task to its revision without the membership", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => single(for { task <- revision(at.w.governor, at.member); old <- revision(at.w.governor, at.old) }
            yield Mutation.Restore(at.member, task, Revision(1), List(ItemRevision(at.old, old))))),
        ("reopen the milestone by Restore", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => single(revision(at.w.governor, at.old).map(old => Mutation.Restore(at.old, old, Revision(old.value - 1), Nil)))),
        ("archive the milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => single(revision(at.w.governor, at.old).map(old => Mutation.Archive(List(ItemRevision(at.old, old)))))),
        ("terminate the milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => service.termination(at.w.governor, Set(at.old), TerminationIntent.Cancel).map(preview =>
            request(List(Mutation.Terminate(Set(at.old), TerminationIntent.Cancel, preview.snapshot)), preview.plan.claims.map(_.fence)))),
        ("relate the Task to the milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => reference(service, at.w.governor, at.member, Relation.RelatesTo, at.old, true)),
        ("block the Task by the milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => reference(service, at.w.governor, at.member, Relation.BlockedBy, at.old, true)),
        ("remove another relation to the milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", alone,
          at => reference(service, at.w.governor, at.member, Relation.RelatesTo, at.old, false)),
        ("assign an in-set Task to the closed milestone", "out-of-set change: M1 is outside the advanceable set stored for cycle 1", at => List(at.member, at.free),
          at => reference(service, at.w.governor, at.free, Relation.PartOf, at.old, true)))
      def refused(closed: MilestoneStatus, index: Int, detail: String, roots: Stranded => List[ItemId], attempt: Attempt): IO[Throwable, Unit] = for {
        (at, _) <- stranded(service, s"kept-out-$closed-$index", closed)(roots)
        w = at.w
        items = List(at.old, at.open, at.member, at.sibling, at.placed, at.free)
        before <- ZIO.foreach(items)(service.get(w.operator, _))
        change <- attempt(at)
        _ <- rejects(service, w, at.key, detail)(service.change(w.governor, change))
        after <- ZIO.foreach(items)(service.get(w.operator, _))
        continued <- query(service, w, at.key)
        _ <- assertIO(after == before && LedgerPolicy.status(after.head.item.draft.content) == closed.toString)
        _ <- assertIO(continued match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, found), _, _) => found.contains(detail); case _ => false })
      } yield ()
      each(List(MilestoneStatus.Complete, MilestoneStatus.Cancelled).flatMap(closed => refusals.zipWithIndex.map { case ((label, detail, roots, attempt), index) =>
        s"$closed: $label" -> refused(closed, index, detail, roots, attempt)
      })*)
    }

    "keep reopening an in-set milestone, an unbound session's recovery and the ledger's refusal to restore a Task under a closed milestone" in { (service: LedgerService[IO]) =>
      // A milestone-root drive selects the milestone, so the bound session reopens it and the drive continues with the milestone and its Task.
      def reopened(name: String, closed: MilestoneStatus, restore: Boolean): IO[Throwable, Unit] = for {
        (at, _) <- stranded(service, name, closed)(at => List(at.old))
        w = at.w
        old <- service.get(w.governor, at.old).map(_.item)
        reopening = if (restore) Mutation.Restore(at.old, old.revision, Revision(old.revision.value - 1), Nil)
          else Mutation.Replace(at.old, old.revision, milestone("Reassigned milestone", MilestoneStatus.Open))
        ack <- service.change(w.governor, request(List(reopening), Nil))
        open <- service.get(w.operator, at.old)
        _ <- assertIO(ack.items.map(_.id) == List(at.old) && LedgerPolicy.status(open.item.draft.content) == MilestoneStatus.Open.toString &&
          open.refs.toSet == Set(ItemRef(Relation.Contains, at.member), ItemRef(Relation.Contains, at.sibling)))
        next <- directive(service, w, at.key)
        _ <- assertIO(next.status.state == DriverState.On && next.status.cycle.exists(cycle => cycle.number == 2 && Set(at.old, at.member).subsetOf(cycle.advanceable.map(_.id).toSet)))
        accepted <- admission(service, w.operator, at.member)
        _ <- assertIO(accepted.isEmpty)
      } yield ()
      // A session that is not bound and names no cycle is not attributed: its unlink and its reopening commit and leave the cycle as it was.
      def unbound(name: String, closed: MilestoneStatus): IO[Throwable, Unit] = for {
        (at, _) <- stranded(service, name, closed)(at => List(at.member))
        w = at.w
        other = w.other(Role.Governor)
        before <- status(service, w, at.key)
        linked <- service.get(w.operator, at.member).map(_.item.revision)
        removed <- reference(service, other, at.member, Relation.PartOf, at.old, false).flatMap(service.change(other, _))
        emptied = removed.items.find(_.id == at.old).get
        unlinked = removed.items.find(_.id == at.member).get
        // Restoring the Task would re-add its membership in the closed milestone: refused by the ledger, for any session.
        start <- cursor(service, w)
        restored <- service.change(w.operator, request(List(Mutation.Restore(at.member, unlinked.revision, linked, List(emptied))), Nil)).either
        end <- cursor(service, w)
        _ <- assertIO(fault(restored).contains(Fault.Invalid(s"Tasks can be assigned only to an Open milestone; M1 is $closed")) && start == end)
        reopening <- service.change(other, request(List(Mutation.Replace(at.old, emptied.revision, milestone("Reassigned milestone", MilestoneStatus.Open))), Nil))
        // Once the milestone is Open again the same Restore commits.
        again <- service.change(w.operator, request(List(Mutation.Restore(at.member, unlinked.revision, linked, List(reopening.items.head))), Nil))
        after <- status(service, w, at.key)
        _ <- assertIO(removed.items.map(_.id).toSet == Set(at.member, at.old) && again.items.map(_.id).toSet == Set(at.member, at.old))
        _ <- assertIO(after.exists(_.state == DriverState.On) && lineage(after) == lineage(before))
        next <- directive(service, w, at.key)
        _ <- assertIO(next.status.cycle.exists(cycle => cycle.number == 2 && cycle.advanceable.map(_.id) == List(at.member)))
      } yield ()
      each(List(MilestoneStatus.Complete, MilestoneStatus.Cancelled).flatMap(closed => List[(String, IO[Throwable, Any])](
        s"$closed, reopened by Replace" -> reopened(s"reopened-replace-$closed", closed, false),
        s"$closed, reopened by Restore" -> reopened(s"reopened-restore-$closed", closed, true),
        s"$closed, unbound session" -> unbound(s"reopened-unbound-$closed", closed)))*)
    }

    // Question 31: once the Tasks a Defect produced are integrated, the Governor closes the Defect itself, without a Planner round.
    "admit a driven Governor's Replace that sets an in-set Defect Resolved with model-declared resolution evidence" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("defect-closure")
      def resolved(view: ItemView, origin: EvidenceOrigin): ItemDraft = view.item.draft.copy(content = Content.Defect(DefectStatus.Resolved, Severity.Medium,
        "Observed", "Expected", "Reproduction", None, List(Evidence("T1 is Done by recorded integration and states that its integration resolves this Defect", origin,
          List(Citation.Commit("consumer", "a" * 40))))))
      for {
        _ <- service.initialize(w.operator, "defect-closure")
        root <- create(service, w.operator, defect("Defect"))
        (_, produced) <- produce(service, w.operator, root, "Correction")
        correction = produced.items.find(_.id != root).get.id
        done <- service.get(w.operator, correction)
        _ <- service.change(w.operator, request(List(Mutation.Replace(correction, done.item.revision,
          done.item.draft.copy(content = Content.Task(TaskStatus.Done, List("Observed outcome"), Some("Integrated"), Nil)))), Nil))
        cycle <- driven(service, w, key, workset(root))
        open <- service.get(w.governor, root)
        // The Governor declares the closure; it cannot give its own entry host provenance. The refusal is the ledger's and leaves the driver on.
        fabricated <- service.change(w.governor, request(List(Mutation.Replace(root, open.item.revision, resolved(open, EvidenceOrigin.HostObserved))), Nil)).either
        _ <- assertIO(fault(fabricated).contains(Fault.Denied("Declared evidence cannot fabricate human or host provenance")))
        // While the Governor holds a claim on the Defect the Replace carries that claim's fence, as any write of a claimed item does.
        claim <- service.acquire(w.governor, ClaimId(uuid), Set(root), 600000L)
        closing = Mutation.Replace(root, open.item.revision, resolved(open, EvidenceOrigin.ModelDeclared))
        unfenced <- service.change(w.governor, request(List(closing), Nil)).either
        _ <- assertIO(fault(unfenced).exists(_.isInstanceOf[Fault.StaleFence]))
        change = request(List(closing), List(claim.fence))
        ack <- service.change(w.governor, change)
        closed <- service.get(w.operator, root)
        running <- status(service, w, key)
        _ <- assertIO(ack.items.map(_.id) == List(root) && LedgerPolicy.status(closed.item.draft.content) == DefectStatus.Resolved.toString &&
          running.exists(_.state == DriverState.On) && lineage(running).contains(LineageEntry(LineageMember.Change(change.request), Some(LineageMember.Run(cycle.run)), true)))
        _ <- service.release(w.governor, claim.fence)
        after <- query(service, w, key)
        _ <- assertIO(after match { case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, _), _, _) => true; case _ => false })
      } yield ()
    }

    // Defect 109, Question 34: a drive that meets a defect it does not work records it and links the items it blocks.
    "admit a Defect the bound session records with a plain Create and the BlockedBy links to it, and keep the Defect outside the drive" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("side-defect")
      for {
        _ <- service.initialize(w.operator, "side-defect")
        first <- create(service, w.operator, task("Blocked in the cycle that records the Defect"))
        second <- create(service, w.operator, task("Blocked in a later cycle"))
        one <- driven(service, w, key, workset(first, second))
        recording = request(List(Mutation.Create(defect("Host defect that blocks the workers"))), Nil)
        recorded <- service.change(w.governor, recording)
        side = recorded.items.head.id
        running <- status(service, w, key)
        _ <- assertIO(side.ledger == Ledger.Defects && running.exists(value => value.state == DriverState.On && value.cycle.exists(_.created.isEmpty)) &&
          lineage(running).contains(LineageEntry(LineageMember.Change(recording.request), Some(LineageMember.Run(one.run)), true)))
        // The cycle that recorded the Defect links an item it blocks; the Defect is revised for that link alone.
        before <- service.get(w.operator, side)
        linked <- reference(service, w.governor, first, Relation.BlockedBy, side, true).flatMap(service.change(w.governor, _))
        after <- service.get(w.operator, side)
        _ <- assertIO(linked.items.map(_.id).toSet == Set(first, side) && after.item.draft == before.item.draft && after.item.revision == Revision(2))
        // The Defect is context of the next cycle, never a member, and the continuation does not hold the cycle to account for it.
        next <- directive(service, w, key)
        _ <- assertIO(next.messages.isEmpty && next.status.cycle.exists(cycle => cycle.number == 2 && cycle.advanceable.map(_.id).toSet == Set(first, second)))
        _ <- submit(service, w, w.governor, next.directive.text)
        // A later cycle links another item to the same Defect, here written from the Defect's side.
        inverse <- reference(service, w.governor, side, Relation.Blocks, second, true).flatMap(service.change(w.governor, _))
        preview <- service.previewWorkset(w.operator, workset(first, second))
        _ <- assertIO(inverse.items.map(_.id).toSet == Set(second, side) && preview.context.map(_.item.id) == List(side) &&
          preview.readiness.forall(entry => !entry.ready && entry.reasons == List(WorksetReason.Blocked(side))))
        // Nothing is ready any more: the stop names the blocker the drive cannot change.
        stop <- query(service, w, key)
        _ <- assertIO(stop match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, "No item of the advanceable set is ready to advance; blocked from outside the set: D1 blocks T1,T2"), Some(value), _) =>
            stopped(Some(value), DriverStop.Quiescent)
          case _ => false
        })
      } yield ()
    }

    // D1: the root of a Defect drive stays ready, so the drive stops on an unchanged cycle and must still name the blocker it cannot change.
    "name the blockers outside the set when a drive whose Defect root stays ready stops on an unchanged cycle, listing a bounded number" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("defect-root-blocker")
      val unchanged = "The previous cycle changed nothing in the advanceable set, its context or its readiness; blocked from outside the set: "
      for {
        _ <- service.initialize(w.operator, "defect-root-blocker")
        root <- create(service, w.operator, defect("Driven Defect"))
        (_, produced) <- produce(service, w.operator, root, "Correction")
        correction = produced.items.find(_.id != root).get.id
        _ <- driven(service, w, key, workset(root))
        side <- service.change(w.governor, request(List(Mutation.Create(defect("Host defect that blocks the worker"))), Nil)).map(_.items.head.id)
        _ <- reference(service, w.governor, correction, Relation.BlockedBy, side, true).flatMap(service.change(w.governor, _))
        preview <- service.previewWorkset(w.operator, workset(root))
        _ <- assertIO(preview.readiness.map(entry => entry.item -> entry.ready).toMap == Map(root -> true, correction -> false))
        next <- directive(service, w, key)
        _ <- submit(service, w, w.governor, next.directive.text)
        stop <- query(service, w, key)
        _ <- ZIO.attempt(assert(stop match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, detail), Some(value), _) => detail == unchanged + "D2 blocks T1" && stopped(Some(value), DriverStop.Quiescent)
          case _ => false
        }, stop.toString))
        // More blockers than the detail names: the first are listed in ledger order and the rest are counted.
        bounded = claude("defect-root-blockers")
        _ <- driven(service, w, bounded, workset(root))
        others <- ZIO.foreach((1 to DriverPolicy.MaxBlockersNamed).toList)(number =>
          service.change(w.governor, request(List(Mutation.Create(defect(s"Further host defect $number"))), Nil)).map(_.items.head.id))
        _ <- ZIO.foreachDiscard(others)(other => reference(service, w.governor, correction, Relation.BlockedBy, other, true).flatMap(service.change(w.governor, _)))
        further <- directive(service, w, bounded)
        _ <- submit(service, w, w.governor, further.directive.text)
        many <- query(service, w, bounded)
        named = (side :: others).take(DriverPolicy.MaxBlockersNamed).map(id => s"D${id.number} blocks T1").mkString("; ")
        _ <- ZIO.attempt(assert(many match {
          case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, detail), _, _) => detail == unchanged + named + "; and 1 more"
          case _ => false
        }, many.toString))
      } yield ()
    }

    // Defect 147: the workflows tell a governing session to gate work on the Question that already holds the operator's decision.
    "admit a BlockedBy link from an in-set item to a Question outside the drive, Open or Answered, and stop for the user on the Open one" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("outside-question")
      def begun(name: String, targets: ItemId*): IO[Throwable, (World, DriverKey, Driven)] = {
        val bound = w.copy(governor = w.other(Role.Governor))
        driven(service, bound, claude(name), workset(targets*)).map(cycle => (bound, claude(name), cycle))
      }
      def settled(status: QuestionStatus, answer: Option[String]): ItemDraft =
        question("Settled decision").copy(content = Content.Question(status, "Prompt", "Context", Nil, None, answer))
      for {
        _ <- service.initialize(w.operator, "outside-question")
        first <- create(service, w.operator, task("Gated by the Open Question"))
        second <- create(service, w.operator, task("Gated by the Answered Question"))
        asked <- create(service, w.operator, question("Decision the operator still owes"))
        answered <- create(service, w.operator, settled(QuestionStatus.Answered, Some("Proceed")))
        withdrawn <- create(service, w.operator, settled(QuestionStatus.Withdrawn, None))
        spare <- create(service, w.operator, task("Stays ready for the refused writes"))
        _ <- driven(service, w, key, workset(first, second))
        before <- service.get(w.operator, asked)
        linked <- reference(service, w.governor, first, Relation.BlockedBy, asked, true).flatMap(service.change(w.governor, _))
        after <- service.get(w.operator, asked)
        _ <- assertIO(linked.items.map(_.id).toSet == Set(first, asked) && after.item.draft == before.item.draft && after.item.revision == Revision(2))
        // The Answered Question is linked from its own side; it satisfies the dependency, so the item stays ready.
        inverse <- reference(service, w.governor, answered, Relation.Blocks, second, true).flatMap(service.change(w.governor, _))
        running <- status(service, w, key)
        preview <- service.previewWorkset(w.operator, workset(first, second))
        _ <- assertIO(inverse.items.map(_.id).toSet == Set(second, answered) && running.exists(_.state == DriverState.On) &&
          preview.readiness.map(entry => entry.item -> entry.ready).toMap == Map(first -> false, second -> true))
        next <- directive(service, w, key)
        _ <- submit(service, w, w.governor, next.directive.text)
        _ <- reference(service, w.governor, second, Relation.BlockedBy, asked, true).flatMap(service.change(w.governor, _))
        // Nothing is ready any more, and what blocks it waits for the operator: the stop names the Question outside the set.
        stop <- query(service, w, key)
        _ <- assertIO(stop match {
          case DriverReply.Stop(DriverStopped(DriverStop.UserInputRequired, "Awaiting the user on Q1; the driver never answers questions or infers approval"), Some(value), _) =>
            stopped(Some(value), DriverStop.UserInputRequired)
          case _ => false
        })
        // Only the added link is admitted: a Withdrawn Question, another relation, the removal of the link and any edit stay out-of-set changes.
        edit <- replace(service, w.operator, asked, "Edited by a drive")
        refusals = List[(String, String, Scope => IO[Throwable, ChangeRequest])](
          ("question-withdrawn", "out-of-set change: Q3 is outside", reference(service, _, spare, Relation.BlockedBy, withdrawn, true)),
          ("question-related", "out-of-set change: Q2 is outside", reference(service, _, spare, Relation.RelatesTo, answered, true)),
          ("question-unlinked", "out-of-set change: Q1 is outside", reference(service, _, first, Relation.BlockedBy, asked, false)),
          ("question-blocked", "out-of-set change: Q2 is outside", reference(service, _, answered, Relation.BlockedBy, spare, true)),
          ("question-edited", "out-of-set change: Q1 is outside", _ => ZIO.succeed(edit)))
        _ <- ZIO.foreachDiscard(refusals) { case (name, detail, change) =>
          begun(name, first, spare).flatMap { case (session, sessionKey, _) =>
            change(session.governor).flatMap(value => rejects(service, session, sessionKey, detail)(service.change(session.governor, value)))
          }
        }
        // The exemption is the bound session's, as it is for a Defect.
        (linking, linkingKey, link) <- begun("question-delegated", first, spare)
        child = w.other(Role.Governor)
        _ <- act(service, linking.governor, DriverSession.Inherit(link.cycle, LineageMember.Run(link.run), LineageMember.Session(child.actor.session)))
        blocking <- reference(service, child, spare, Relation.BlockedBy, answered, true)
        _ <- rejects(service, linking, linkingKey, "out-of-set change: Q2 is outside")(act(service, child, DriverSession.Change(link.cycle, blocking)))
      } yield ()
    }

    "keep every other driven write that names a recorded Defect, and every other blocking link, an out-of-set change that stops the drive" in { (service: LedgerService[IO]) =>
      val w = world
      def begun(name: String, targets: ItemId*): IO[Throwable, (World, DriverKey, Driven)] = {
        val bound = w.copy(governor = w.other(Role.Governor))
        driven(service, bound, claude(name), workset(targets*)).map(cycle => (bound, claude(name), cycle))
      }
      def delegate(session: World, cycle: Driven): IO[Throwable, Scope] = {
        val child = w.other(Role.Governor)
        act(service, session.governor, DriverSession.Inherit(cycle.cycle, LineageMember.Run(cycle.run), LineageMember.Session(child.actor.session))).as(child)
      }
      for {
        _ <- service.initialize(w.operator, "side-defect-refusals")
        member <- create(service, w.operator, task("In-set task the Defect blocks"))
        spare <- create(service, w.operator, task("In-set task that stays ready"))
        stranger <- create(service, w.operator, task("Out-of-set task"))
        resolved <- create(service, w.operator, defect("Resolved defect").copy(content =
          Content.Defect(DefectStatus.Resolved, Severity.Medium, "Observed", "Expected", "Reproduction", None, Nil)))
        // The drive that recorded the Defect and linked it does not edit it afterwards, even inside the same cycle.
        (recorder, recorderKey, _) <- begun("side-recorder", member, spare)
        side <- create(service, recorder.governor, defect("Recorded by the drive"))
        _ <- reference(service, recorder.governor, member, Relation.BlockedBy, side, true).flatMap(service.change(recorder.governor, _))
        edit <- replace(service, recorder.governor, side, "Edited by the drive that recorded it")
        _ <- rejects(service, recorder, recorderKey, "out-of-set change: D2 is outside the advanceable set stored for cycle 1")(service.change(recorder.governor, edit))
        refusals = List[(String, String, Scope => IO[Throwable, ChangeRequest])](
          ("side-resolved", "out-of-set change: D1 is outside", reference(service, _, spare, Relation.BlockedBy, resolved, true)),
          ("side-task", "out-of-set change: T3 is outside", reference(service, _, spare, Relation.BlockedBy, stranger, true)),
          ("side-related", "out-of-set change: D2 is outside", reference(service, _, spare, Relation.RelatesTo, side, true)),
          ("side-unlinked", "out-of-set change: D2 is outside", reference(service, _, member, Relation.BlockedBy, side, false)),
          ("side-blocked", "out-of-set change: D2 is outside", reference(service, _, side, Relation.BlockedBy, spare, true)),
          ("side-outsider", "out-of-set change: T3 is outside", reference(service, _, stranger, Relation.BlockedBy, side, true)))
        _ <- ZIO.foreachDiscard(refusals) { case (name, detail, change) =>
          begun(name, member, spare).flatMap { case (session, key, _) =>
            change(session.governor).flatMap(value => rejects(service, session, key, detail)(service.change(session.governor, value)))
          }
        }
        // The exemption is the bound session's: a session the cycle delegated to neither records a Defect nor links one.
        (creating, creatingKey, created) <- begun("side-delegated-create", member, spare)
        child <- delegate(creating, created)
        _ <- rejects(service, creating, creatingKey, "non-selected creation: D3 is not a selected descendant of T1,T2")(
          act(service, child, DriverSession.Change(created.cycle, request(List(Mutation.Create(defect("Recorded by a delegated session"))), Nil))))
        (linking, linkingKey, link) <- begun("side-delegated-link", member, spare)
        other <- delegate(linking, link)
        blocking <- reference(service, other, spare, Relation.BlockedBy, side, true)
        _ <- rejects(service, linking, linkingKey, "out-of-set change: D2 is outside")(act(service, other, DriverSession.Change(link.cycle, blocking)))
        untouched <- service.get(w.operator, side)
        _ <- assertIO(untouched.item.revision == Revision(2) && untouched.refs == List(ItemRef(Relation.Blocks, member)))
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
        // The write itself is refused while the cycle continues on other ready work: a new answer, a changed one and an answered creation.
        answering = w.copy(governor = w.other(Role.Governor))
        _ <- driven(service, answering, claude("answering"), workset(ready, asked))
        answered = (text: String) => open.item.draft.copy(content = Content.Question(QuestionStatus.Answered, "Prompt", "Context", Nil, None, Some(text)))
        before <- cursor(service, w)
        attempts <- ZIO.foreach(List(
          request(List(Mutation.Replace(asked, open.item.revision, answered("The driver's own answer"))), Nil),
          request(List(Mutation.Replace(asked, open.item.revision, open.item.draft.copy(content = Content.Question(QuestionStatus.Open, "Prompt", "Context", Nil, None, Some("Drafted answer"))))), Nil),
        ))(service.change(answering.governor, _).either)
        after <- cursor(service, w)
        kept <- status(service, answering, claude("answering"))
        _ <- assertIO(attempts.forall(result => fault(result).contains(Fault.Denied(
          "The CQ driver never answers Questions: Q1 would be answered by a driven session; park the driver before recording the user's answer"))) &&
          before == after && kept.exists(_.state == DriverState.On))
        retitled <- service.change(answering.governor, request(List(Mutation.Replace(asked, open.item.revision, open.item.draft.copy(title = "Reworded question"))), Nil))
        _ <- park(service, answering, claude("answering"))
        byHand <- service.get(w.operator, asked).flatMap(view => service.change(answering.governor, request(List(Mutation.Replace(asked, view.item.revision, answered("The user's answer"))), Nil)))
        _ <- assertIO(retitled.items.map(_.id) == List(asked) && byHand.items.map(_.id) == List(asked))
        // Limit: every start and resume directive counts.
        limited = w.copy(governor = w.other(Role.Governor))
        one <- driven(service, limited, codex("limit"), workset(ready))
        _ <- act(service, limited.governor, DriverSession.Inherit(one.cycle, LineageMember.Run(one.run), LineageMember.Attempt(AttemptId(uuid))))
        resumes <- ZIO.foreach((2 to DriverPolicy.MaxDirectives).toList)(_ => query(service, limited, codex("limit")))
        _ <- assertIO(resumes.forall { case DriverReply.Continue(value, _, _) => value.token.isInstanceOf[CycleToken.Resume]; case _ => false } &&
          resumes.map { case DriverReply.Continue(value, _, _) => value.token; case other => other }.distinct.size == resumes.size)
        exhausted <- query(service, limited, codex("limit"))
        _ <- assertIO(reason(exhausted).contains(DriverStop.LimitReached) && (exhausted match { case DriverReply.Stop(_, Some(value), _) => value.directives == DriverPolicy.MaxDirectives; case _ => false }))
        notBound <- start(service, w, claude("not-bound"), workset(ready)) *> query(service, w, claude("not-bound"))
        skipped <- on(service, w, claude("failure"), workset(ready)) *> directive(service, w, claude("failure")) *> query(service, w, claude("failure"))
        parked <- start(service, w, claude("parked"), workset(ready)) *> park(service, w, claude("parked"))
        unknown <- query(service, w, claude("unknown-session"))
        none <- status(service, w, claude("unknown-session"))
        _ <- assertIO(reason(notBound).contains(DriverStop.NotBound) && reason(skipped).contains(DriverStop.Failure) &&
          (parked match { case DriverReply.Parked(Some(value), "CQ driver parked: T3 through work") => stopped(Some(value), DriverStop.Parked); case _ => false }) &&
          unknown == DriverReply.Stop(DriverStopped(DriverStop.Off, "No CQ driver is on for this session"), None, Nil) && none.isEmpty)
        _ <- assertIO(DriverStop.all.map(DriverPolicy.reason) == List("quiescent", "user input required", "limit reached", "not bound", "failure", "parked", "off", "restored archive"))
      } yield ()
    }

    "D164: decide a drive that stopped for user input anew at each continuation query, and continue it once the person has settled what it waited for" in { (service: LedgerService[IO]) =>
      val w = world
      val off = DriverStopped(DriverStop.Off, "The CQ driver is off")
      def set(asked: ItemId, status: QuestionStatus, answer: Option[String]) = service.get(w.operator, asked).flatMap(view => service.change(w.operator,
        request(List(Mutation.Replace(asked, view.item.revision, view.item.draft.copy(content = Content.Question(status, "Prompt", "Context", Nil, None, answer)))), Nil)))
      // A drive on one Task that an Open Question gates: its first continuation query stops it for user input.
      def resting(name: String): IO[Throwable, (World, DriverKey, ItemId, ItemId)] = {
        val session = w.copy(governor = w.other(Role.Governor))
        val key = claude(name)
        for {
          asked <- create(service, w.operator, question("Open question"))
          gated <- create(service, w.operator, task("Gated by the question"))
          _ <- reference(service, w.operator, gated, Relation.BlockedBy, asked, true).flatMap(service.change(w.operator, _))
          _ <- on(service, session, key, workset(gated))
          first <- query(service, session, key)
          _ <- assertIO(first match { case DriverReply.Stop(DriverStopped(DriverStop.UserInputRequired, _), _, List(_)) => true; case _ => false })
        } yield (session, key, asked, gated)
      }
      for {
        _ <- service.initialize(w.operator, "resting drive")
        // Still open: the query says nothing and changes nothing, so the session stops as it did before.
        (session, key, asked, gated) <- resting("rests")
        rested <- status(service, session, key)
        again <- query(service, session, key)
        unchanged <- status(service, session, key)
        _ <- ZIO.attempt(assert(again == DriverReply.Stop(off, rested, Nil) && unchanged == rested, s"$again $rested $unchanged"))
        // Answered: the next query issues the start directive of a new cycle, which the attached session of the drive starts.
        _ <- set(asked, QuestionStatus.Answered, Some("Yes"))
        resumed <- query(service, session, key)
        _ <- ZIO.attempt(assert(resumed.isInstanceOf[DriverReply.Continue], s"A drive that waited for an answer did not continue once the Question was answered: $resumed"))
        continued = resumed.asInstanceOf[DriverReply.Continue]
        _ <- ZIO.attempt(assert(continued.status.state == DriverState.On && continued.status.stopped.isEmpty && continued.status.attached.contains(session.governor.actor.session) &&
          continued.directive.token.isInstanceOf[CycleToken.Start] && continued.messages == List("CQ driver: the user settled what this drive waited for; it continues"), continued.toString))
        cycle <- submit(service, session, session.governor, continued.directive.text)
        _ <- assertIO(cycle.workflow == WorkflowRequest.Advance(Set(gated), WorkflowPhase.Work))
        // While it rests the drive names its attached session, which is how the caller of the query finds that session's host.
        _ <- ZIO.attempt(assert(rested.exists(value => value.state == DriverState.Off && value.attached.contains(session.governor.actor.session) &&
          value.stopped.exists(_.reason == DriverStop.UserInputRequired)), rested.toString))
        // Withdrawn: the gated Task stays blocked and nothing waits for a person any more, so the drive ends with that reason, said once.
        (other, second, withdrawn, _) <- resting("withdrawn")
        _ <- set(withdrawn, QuestionStatus.Withdrawn, None)
        ended <- query(service, other, second)
        last <- query(service, other, second)
        _ <- ZIO.attempt(assert((ended match {
          case DriverReply.Stop(value @ DriverStopped(DriverStop.Quiescent, _), Some(found), messages) => stopped(Some(found), DriverStop.Quiescent) && messages == List(DriverPolicy.stopMessage(value))
          case _ => false
        }) && (last match { case DriverReply.Stop(`off`, Some(found), Nil) => stopped(Some(found), DriverStop.Quiescent); case _ => false }), s"$ended $last"))
        // A session that drives under another key meanwhile keeps that drive: the one that rested stays off.
        (moved, third, later, _) <- resting("superseded")
        ready <- create(service, w.operator, task("Ready"))
        _ <- on(service, moved, claude("superseding"), workset(ready))
        _ <- set(later, QuestionStatus.Answered, Some("Yes"))
        kept <- query(service, moved, third)
        _ <- ZIO.attempt(assert(kept match { case DriverReply.Stop(`off`, Some(found), Nil) => found.state == DriverState.Off; case _ => false }, kept.toString))
        // A parked drive does not rest.
        (parked, fourth, last2, _) <- resting("parked")
        _ <- park(service, parked, fourth)
        _ <- set(last2, QuestionStatus.Answered, Some("Yes"))
        still <- query(service, parked, fourth)
        _ <- ZIO.attempt(assert(still match { case DriverReply.Stop(`off`, Some(found), Nil) => found.state == DriverState.Off; case _ => false }, still.toString))
      } yield ()
    }

    "refuse a driven Replace that withdraws an open Question" in { (service: LedgerService[IO]) =>
      waiting(service, "withdraw-replace") { (w, asked, open) =>
        val withdrawn = open.draft.copy(content = Content.Question(QuestionStatus.Withdrawn, "Prompt", "Context", Nil, None, None))
        for {
          // A withdrawal with archival in the same revision is the one write that both settles and archives the Question.
          _ <- ZIO.foreachDiscard(List(withdrawn, withdrawn.copy(archived = true)))(draft =>
            service.change(w.governor, request(List(Mutation.Replace(asked, open.revision, draft)), Nil)).either.flatMap(refused))
        } yield ()
      }
    }

    "refuse a driven Cancel termination that withdraws an open Question" in { (service: LedgerService[IO]) =>
      waiting(service, "withdraw-terminate") { (w, asked, _) =>
        for {
          preview <- service.termination(w.governor, Set(asked), TerminationIntent.Cancel)
          _ <- assertIO(preview.plan.canApply && preview.plan.entries.exists(entry =>
            entry.item.id == asked && entry.effect == TerminationEffect.Change(TerminalStatus.Question(QuestionStatus.Withdrawn))))
          result <- service.change(w.governor, request(List(Mutation.Terminate(Set(asked), TerminationIntent.Cancel, preview.snapshot)), preview.plan.claims.map(_.fence))).either
          _ <- refused(result)
        } yield ()
      }
    }

    "refuse a driven Restore that returns a reopened Question to its withdrawn revision" in { (service: LedgerService[IO]) =>
      // Before the drive the operator withdraws the Question and reopens it, so its history holds a withdrawn revision.
      def set(w: World, asked: ItemId, status: QuestionStatus) = service.get(w.operator, asked).flatMap(view => service.change(w.operator,
        request(List(Mutation.Replace(asked, view.item.revision, view.item.draft.copy(content = Content.Question(status, "Prompt", "Context", Nil, None, None)))), Nil)))
      waiting(service, "withdraw-restore", (w, asked) => (set(w, asked, QuestionStatus.Withdrawn) *> set(w, asked, QuestionStatus.Open)).unit) { (w, asked, open) =>
        for {
          _ <- assertIO(open.revision == Revision(3))
          result <- service.change(w.governor, request(List(Mutation.Restore(asked, open.revision, Revision(2), Nil)), Nil)).either
          _ <- refused(result)
        } yield ()
      }
    }

    "leave an open Question unarchivable by a driven write, while a non-settling edit is admitted" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("archive-open")
      for {
        _ <- service.initialize(w.operator, "archive-open")
        asked <- create(service, w.operator, question("Open question"))
        ready <- create(service, w.operator, task("Ready"))
        _ <- driven(service, w, key, workset(ready, asked))
        open <- service.get(w.operator, asked).map(_.item)
        before <- cursor(service, w)
        // The ledger admits archival of terminal or settled items only, so no write archives a Question that is still Open.
        flagged <- service.change(w.governor, request(List(Mutation.Replace(asked, open.revision, open.draft.copy(archived = true))), Nil)).either
        bulk <- service.change(w.governor, request(List(Mutation.Archive(List(ItemRevision(asked, open.revision)))), Nil)).either
        after <- cursor(service, w)
        kept <- status(service, w, key)
        _ <- assertIO(fault(flagged).contains(Fault.Invalid("Only terminal or settled items may be archived; unarchive an item before reopening it")) &&
          fault(bulk).contains(Fault.Invalid("Only terminal items or adopted Decisions with fully archived scope may be bulk archived")) &&
          before == after && kept.exists(_.state == DriverState.On))
        retitled <- service.change(w.governor, request(List(Mutation.Replace(asked, open.revision, open.draft.copy(title = "Reworded question"))), Nil))
        current <- service.get(w.operator, asked).map(_.item)
        _ <- assertIO(retitled.items == List(ItemRevision(asked, Revision(open.revision.value + 1))) && current.draft.title == "Reworded question" &&
          !current.draft.archived && LedgerPolicy.status(current.draft.content) == QuestionStatus.Open.toString)
      } yield ()
    }

    // Only a person settles an Operator Action: every driven write that takes one out of Requested is refused (Question 25).
    List(OperatorActionStatus.Cancelled, OperatorActionStatus.Failed, OperatorActionStatus.Confirmed, OperatorActionStatus.Observed).foreach { status =>
      s"refuse a driven Replace that moves a Requested Operator Action to $status" in { (service: LedgerService[IO]) =>
        requested(service, s"action-replace-$status".toLowerCase) { at =>
          // No confirmation text is added, so the ledger's own rule on operator confirmation does not apply.
          service.change(at.w.governor, request(List(Mutation.Replace(at.action, at.item.revision, moved(at.item, status))), Nil)).either
            .flatMap(kept(s"Replace to $status", _))
        }
      }
    }

    "refuse a driven Cancel termination rooted at a Requested Operator Action" in { (service: LedgerService[IO]) =>
      requested(service, "action-terminate") { at =>
        for {
          preview <- service.termination(at.w.governor, Set(at.action), TerminationIntent.Cancel)
          _ <- assertIO(preview.plan.canApply && preview.plan.entries.exists(entry =>
            entry.item.id == at.action && entry.effect == TerminationEffect.Change(TerminalStatus.OperatorAction(OperatorActionStatus.Cancelled))))
          result <- service.change(at.w.governor, request(List(Mutation.Terminate(Set(at.action), TerminationIntent.Cancel, preview.snapshot)), preview.plan.claims.map(_.fence))).either
          _ <- kept("Cancel termination rooted at the action", result)
        } yield ()
      }
    }

    "refuse a driven Cancel termination that reaches a Requested Operator Action from an in-set producer" in { (service: LedgerService[IO]) =>
      // The ready Task produces the action, so a termination rooted at the Task reaches it through Produces.
      requested(service, "action-terminate-producer", (w, ready, asked) =>
        reference(service, w.operator, asked, Relation.DerivedFrom, ready, true).flatMap(service.change(w.operator, _)).as(Nil)) { at =>
        for {
          preview <- service.termination(at.w.governor, Set(at.ready), TerminationIntent.Cancel)
          _ <- assertIO(preview.plan.canApply && preview.plan.roots == List(at.ready) && preview.plan.entries.exists(entry =>
            entry.item.id == at.action && entry.effect == TerminationEffect.Change(TerminalStatus.OperatorAction(OperatorActionStatus.Cancelled))))
          result <- service.change(at.w.governor, request(List(Mutation.Terminate(Set(at.ready), TerminationIntent.Cancel, preview.snapshot)), preview.plan.claims.map(_.fence))).either
          _ <- kept("Cancel termination rooted at the producing Task", result)
          task <- service.get(at.w.operator, at.ready).map(_.item)
          _ <- assertIO(LedgerPolicy.status(task.draft.content) == TaskStatus.Ready.toString)
        } yield ()
      }
    }

    "refuse a driven Cancel termination that reaches a Requested Operator Action from an in-set milestone" in { (service: LedgerService[IO]) =>
      // The action is a member of an Open milestone in the workset, so a termination rooted at the milestone reaches it through Contains.
      requested(service, "action-terminate-milestone", (w, _, asked) => for {
        containing <- create(service, w.operator, milestone("Containing milestone", MilestoneStatus.Open))
        _ <- reference(service, w.operator, asked, Relation.PartOf, containing, true).flatMap(service.change(w.operator, _))
      } yield List(containing)) { at =>
        for {
          containing <- service.get(at.w.operator, at.action).map(_.refs.collectFirst { case ItemRef(Relation.PartOf, target) => target }.get)
          preview <- service.termination(at.w.governor, Set(containing), TerminationIntent.Cancel)
          _ <- assertIO(preview.plan.canApply && preview.plan.roots == List(containing) && preview.plan.entries.exists(entry =>
            entry.item.id == at.action && entry.effect == TerminationEffect.Change(TerminalStatus.OperatorAction(OperatorActionStatus.Cancelled))))
          result <- service.change(at.w.governor, request(List(Mutation.Terminate(Set(containing), TerminationIntent.Cancel, preview.snapshot)), preview.plan.claims.map(_.fence))).either
          _ <- kept("Cancel termination rooted at the containing milestone", result)
          open <- service.get(at.w.operator, containing).map(_.item)
          _ <- assertIO(LedgerPolicy.status(open.draft.content) == MilestoneStatus.Open.toString)
        } yield ()
      }
    }

    // Defect 100, Question 24: the ledger's closure gate refuses the bound session's write as it refuses any other. It is not a boundary
    // violation, so the driver stays on.
    "refuse a driven Replace or termination that closes an in-set milestone over a Ready Task with the undriven fault, leaving the driver on" in { (service: LedgerService[IO]) =>
      def scenario(name: String, intent: TerminationIntent): IO[Throwable, Unit] = {
        val w = world
        val key = claude(name)
        val closed = if (intent == TerminationIntent.Complete) MilestoneStatus.Complete else MilestoneStatus.Cancelled
        def terminate(scope: Scope): IO[Throwable, (TerminationPreview, Either[Throwable, ChangeAck])] = for {
          preview <- service.termination(scope, Set(ItemId(w.project, Ledger.Milestones, 1)), intent)
          result <- service.change(scope, request(List(Mutation.Terminate(preview.plan.roots.toSet, intent, preview.snapshot)), preview.plan.claims.map(_.fence))).either
        } yield (preview, result)
        for {
          _ <- service.initialize(w.operator, name)
          producer <- create(service, w.operator, goal("Outside producer"))
          containing <- create(service, w.operator, milestone("Driven milestone", MilestoneStatus.Open))
          member <- create(service, w.operator, task("Ready member"))
          _ <- reference(service, w.operator, member, Relation.DerivedFrom, producer, true).flatMap(service.change(w.operator, _))
          _ <- reference(service, w.operator, member, Relation.PartOf, containing, true).flatMap(service.change(w.operator, _))
          _ <- driven(service, w, key, workset(containing))
          open <- service.get(w.operator, containing).map(_.item)
          ready <- service.get(w.operator, member).map(_.item)
          closing = Mutation.Replace(containing, open.revision, milestone("Driven milestone", closed))
          closure = s"M1 cannot be changed to $closed while it contains non-terminal Tasks: T1 (Ready). " +
            "Make each Done or Cancelled, or reassign it to another Open milestone, before closing M1"
          retention = closure + ". This termination leaves them unchanged; add them to its roots to terminate them with M1"
          before <- cursor(service, w)
          replaced <- service.change(w.governor, request(List(closing), Nil)).either
          terminated <- terminate(w.governor)
          undrivenReplace <- service.change(w.operator, request(List(closing), Nil)).either
          undrivenTerminate <- terminate(w.operator)
          after <- cursor(service, w)
          kept <- status(service, w, key)
          _ <- assertIO(fault(replaced).contains(Fault.Invalid(closure)) && fault(undrivenReplace) == fault(replaced))
          _ <- assertIO(!terminated._1.plan.canApply && terminated._1.plan.entries.exists(entry =>
            entry.item.id == containing && entry.effect == TerminationEffect.Unsupported(retention)))
          _ <- assertIO(fault(terminated._2).contains(Fault.Invalid(retention)) && fault(undrivenTerminate._2) == fault(terminated._2))
          current <- ZIO.foreach(List(containing, member))(service.get(w.operator, _).map(_.item))
          _ <- assertIO(before == after && current == List(open, ready))
          _ <- assertIO(kept.exists(found => found.state == DriverState.On && found.stopped.isEmpty))
          // The cycle is still active: the bound session's next in-set write commits under it.
          retitled <- replace(service, w.governor, member, "Retitled member").flatMap(service.change(w.governor, _))
          _ <- assertIO(retitled.items.map(_.id) == List(member))
          continued <- status(service, w, key)
          _ <- assertIO(continued.exists(_.state == DriverState.On))
        } yield ()
      }
      each("Complete" -> scenario("closure-gate-complete", TerminationIntent.Complete), "Cancel" -> scenario("closure-gate-cancel", TerminationIntent.Cancel))
    }

    "refuse a driven Restore that returns a Requested Operator Action to a revision that was not Requested" in { (service: LedgerService[IO]) =>
      // Before the drive the operator cancels the action and requests it again, so its history holds a Cancelled revision.
      def set(w: World, asked: ItemId, status: OperatorActionStatus) = service.get(w.operator, asked).flatMap(view =>
        service.change(w.operator, request(List(Mutation.Replace(asked, view.item.revision, moved(view.item, status))), Nil)))
      requested(service, "action-restore", (w, _, asked) =>
        (set(w, asked, OperatorActionStatus.Cancelled) *> set(w, asked, OperatorActionStatus.Requested)).as(Nil)) { at =>
        for {
          _ <- assertIO(at.item.revision == Revision(3))
          result <- service.change(at.w.governor, request(List(Mutation.Restore(at.action, at.item.revision, Revision(2), Nil)), Nil)).either
          _ <- kept("Restore to the Cancelled revision", result)
        } yield ()
      }
    }

    "refuse a session the drive delegated to, whatever its role, that takes an Operator Action out of Requested" in { (service: LedgerService[IO]) =>
      requested(service, "action-delegated") { at =>
        val delegate = at.w.other(Role.Human)
        def attributed(path: String, draft: ItemDraft) =
          act(service, delegate, DriverSession.Change(at.cycle.cycle, request(List(Mutation.Replace(at.action, at.item.revision, draft)), Nil))).either.flatMap(kept(path, _))
        for {
          _ <- act(service, at.w.governor, DriverSession.Inherit(at.cycle.cycle, LineageMember.Run(at.cycle.run), LineageMember.Session(delegate.actor.session)))
          _ <- attributed("Delegated Replace to Cancelled", moved(at.item, OperatorActionStatus.Cancelled))
          // A human delegate may record confirmation text as far as the ledger is concerned; the write still belongs to the cycle.
          _ <- attributed("Delegated Replace to Confirmed with confirmation text", moved(at.item, OperatorActionStatus.Confirmed, Some("Rotated by the operator")))
          // The delegate ends without having written; a lineage member still in flight would resume the cycle instead of stopping it.
          _ <- act(service, at.w.governor, DriverSession.Settle(at.cycle.cycle, LineageMember.Session(delegate.actor.session)))
        } yield ()
      }
    }

    "leave a Requested Operator Action unarchivable, admit a non-settling edit to it, and admit its settlement by hand once the driver is parked" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("action-edit")
      for {
        _ <- service.initialize(w.operator, "action-edit")
        asked <- create(service, w.operator, action("Requested action"))
        ready <- create(service, w.operator, task("Ready"))
        _ <- driven(service, w, key, workset(ready, asked))
        open <- service.get(w.operator, asked).map(_.item)
        // The ledger admits archival of terminal or settled items only, so no write archives an action that is still Requested.
        before <- cursor(service, w)
        flagged <- service.change(w.governor, request(List(Mutation.Replace(asked, open.revision, open.draft.copy(archived = true))), Nil)).either
        bulk <- service.change(w.governor, request(List(Mutation.Archive(List(ItemRevision(asked, open.revision)))), Nil)).either
        after <- cursor(service, w)
        _ <- assertIO(fault(flagged).contains(Fault.Invalid("Only terminal or settled items may be archived; unarchive an item before reopening it")) &&
          fault(bulk).contains(Fault.Invalid("Only terminal items or adopted Decisions with fully archived scope may be bulk archived")) && before == after)
        retitled <- service.change(w.governor, request(List(Mutation.Replace(asked, open.revision, open.draft.copy(title = "Reworded action", body = "Reworded narrative"))), Nil))
        edited <- service.get(w.operator, asked).map(_.item)
        kept <- status(service, w, key)
        _ <- assertIO(retitled.items == List(ItemRevision(asked, Revision(open.revision.value + 1))) && edited.draft.title == "Reworded action" &&
          !edited.draft.archived && LedgerPolicy.status(edited.draft.content) == OperatorActionStatus.Requested.toString && kept.exists(_.state == DriverState.On))
        // The documented way out: park, then the same session settles the action as an undriven write.
        _ <- park(service, w, key)
        byHand <- service.change(w.governor, request(List(Mutation.Replace(asked, edited.revision, moved(edited, OperatorActionStatus.Cancelled))), Nil))
        settled <- service.get(w.operator, asked).map(_.item)
        _ <- assertIO(byHand.items.map(_.id) == List(asked) && LedgerPolicy.status(settled.draft.content) == OperatorActionStatus.Cancelled.toString)
      } yield ()
    }

    "admit a driven write that creates an Operator Action in any status" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("action-create")
      for {
        _ <- service.initialize(w.operator, "action-create")
        ready <- create(service, w.operator, task("Ready"))
        _ <- driven(service, w, key, workset(ready))
        created <- producing(service, w.governor, ready)(revision => List(Mutation.Produce(ready, revision,
          List(action("Requested by the drive"), action("Created as observed", OperatorActionStatus.Observed), action("Created as cancelled", OperatorActionStatus.Cancelled)), None)))
        actions = created.items.filter(_.id.ledger == Ledger.OperatorActions)
        statuses <- ZIO.foreach(actions)(item => service.get(w.operator, item.id).map(view => LedgerPolicy.status(view.item.draft.content)))
        kept <- status(service, w, key)
        _ <- assertIO(actions.forall(_.revision == Revision(1)) && statuses.sorted == List("Cancelled", "Observed", "Requested") &&
          kept.exists(found => found.state == DriverState.On && actions.map(_.id).forall(found.cycle.get.created.contains)))
      } yield ()
    }

    "admit a driven write that moves an already Confirmed Operator Action to Observed or Failed" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("action-confirmed")
      val confirmation = Some("Rotated by the operator")
      for {
        _ <- service.initialize(w.operator, "action-confirmed")
        observed <- create(service, w.operator, action("Confirmed, to be observed", OperatorActionStatus.Confirmed, confirmation))
        failing <- create(service, w.operator, action("Confirmed, to fail", OperatorActionStatus.Confirmed, confirmation))
        ready <- create(service, w.operator, task("Ready"))
        _ <- driven(service, w, key, workset(ready, observed, failing))
        results <- ZIO.foreach(List(observed -> OperatorActionStatus.Observed, failing -> OperatorActionStatus.Failed)) { case (id, status) =>
          for {
            current <- service.get(w.operator, id).map(_.item)
            ack <- service.change(w.governor, request(List(Mutation.Replace(id, current.revision, moved(current, status, confirmation))), Nil))
            after <- service.get(w.operator, id).map(_.item)
          } yield ack.items == List(ItemRevision(id, Revision(current.revision.value + 1))) && LedgerPolicy.status(after.draft.content) == status.toString
        }
        kept <- status(service, w, key)
        _ <- assertIO(results.forall(identity) && kept.exists(_.state == DriverState.On))
      } yield ()
    }

    "move an attached session's binding to the driver of a new session key and park the old key's driver" in { (service: LedgerService[IO]) =>
      val w = world
      val (old, renewed) = (claude("key-before"), claude("key-after"))
      val attached = w.other(Role.Governor)
      val (piOld, piNew) = (DriverKey(Harness.Pi, "pi-before"), DriverKey(Harness.Pi, "pi-after"))
      def extension(key: DriverKey, root: ItemId): IO[Throwable, DriverReply] =
        control(service, w, key, DriverOrigin.Extension, DriverControl.Start(workset(root), Some(attached.actor.session)))
      for {
        _ <- service.initialize(w.operator, "rebinding")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, old, workset(root))
        // The harness issued a new session_id while the attached host lives on: the drive command arrives under the new key.
        started <- start(service, w, renewed, workset(root))
        bound <- act(service, w.governor, DriverSession.Bind(started.bind.get))
        before <- status(service, w, old)
        own <- act(service, w.governor, DriverSession.Status())
        _ <- assertIO((bound match { case DriverReply.Bound(value, _) => value.key == renewed && value.state == DriverState.On && value.attached.contains(w.governor.actor.session); case _ => false }) &&
          stopped(before, DriverStop.Parked) && before.get.stopped.get.detail == "Its attached session was bound to the CQ driver of session key-after" &&
          before.get.cycle.exists(_.state == CycleState.Ended) && (own match { case DriverReply.Status(Some(value)) => value.key == renewed; case _ => false }))
        stale <- activate(service, w.governor, RequestId(uuid), one.workflow, Some(one.token)).either
        _ <- assertIO(denied(stale))
        again <- start(service, w, renewed, workset(root)).flatMap(value => act(service, w.governor, DriverSession.Bind(value.bind.get)))
        parked <- park(service, w, renewed)
        _ <- assertIO(again.isInstanceOf[DriverReply.Bound] && (parked match { case DriverReply.Parked(Some(value), _) => stopped(Some(value), DriverStop.Parked) && value.key == renewed; case _ => false }))
        free <- replace(service, w.governor, root, "Parked under the new key: the session writes as before").flatMap(service.change(w.governor, _))
        _ <- assertIO(free.items.map(_.id) == List(root))
        _ <- extension(piOld, root)
        moved <- extension(piNew, root)
        displaced <- control(service, w, piOld, DriverOrigin.Extension, DriverControl.Status()).map { case DriverReply.Status(value) => value; case other => throw new IllegalStateException(other.toString) }
        _ <- assertIO((moved match { case DriverReply.Started(value, _, None, _) => value.key == piNew && value.state == DriverState.On && value.attached.contains(attached.actor.session); case _ => false }) &&
          stopped(displaced, DriverStop.Parked) && displaced.get.stopped.get.detail == "Its attached session was bound to the CQ driver of session pi-after")
      } yield ()
    }

    "check an activation's host preconditions before presenting its token and not cap the activations of one attached host" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("host-order")
      for {
        runtime <- ZIO.runtime[Any]
        _ <- service.initialize(w.operator, "host-order")
        root <- create(service, w.operator, goal("Goal"))
        quiet = new java.util.concurrent.atomic.AtomicBoolean(true)
        attachedHost = host(service, w, runtime, () => quiet.get)
        // A long attached session: more activations than one drive issues directives, before and between drives.
        undriven <- ZIO.foreach((1 to 2 * DriverPolicy.MaxDirectives).toList)(_ =>
          ZIO.attemptBlocking(attachedHost.activate(RequestId(uuid), WorkflowRequest.Begin(Set.empty), "Undriven", None)).either)
        _ <- assertIO(undriven.forall(_.exists(_.cycle.isEmpty)))
        _ <- on(service, w, key, workset(root))
        issued <- directive(service, w, key)
        (id, workflow, token) = activation(issued.directive.text, w.project)
        _ <- ZIO.succeed(quiet.set(false))
        busy <- ZIO.attemptBlocking(attachedHost.activate(id, workflow, "Driven", token)).either
        unused <- status(service, w, key)
        _ <- assertIO(busy.left.exists(_.getMessage.contains("Settle active child/check/integration/combination work before changing workflow")) &&
          unused.exists(value => value.state == DriverState.On && value.cycle.exists(cycle => cycle.state == CycleState.Pending && cycle.run.isEmpty)))
        _ <- ZIO.succeed(quiet.set(true))
        accepted <- ZIO.attemptBlocking(attachedHost.activate(id, workflow, "Driven", token))
        replayed <- ZIO.attemptBlocking(attachedHost.activate(id, workflow, "Driven", token))
        running <- status(service, w, key)
        _ <- assertIO(accepted.cycle.contains(issued.directive.cycle) && replayed == accepted && attachedHost.current.contains(accepted) &&
          running.exists(_.cycle.exists(cycle => cycle.state == CycleState.Active && cycle.run.contains(id))))
      } yield ()
    }

    "stop the driver when a resume token names a run that is not its attached host's active workflow" in { (service: LedgerService[IO]) =>
      val w = world
      val key = codex("host-resume")
      for {
        runtime <- ZIO.runtime[Any]
        _ <- service.initialize(w.operator, "host-resume")
        root <- create(service, w.operator, goal("Goal"))
        _ <- on(service, w, key, workset(root))
        issued <- directive(service, w, key)
        (id, workflow, token) = activation(issued.directive.text, w.project)
        owner = host(service, w, runtime, () => true)
        run <- ZIO.attemptBlocking(owner.activate(id, workflow, "Driven", token))
        _ <- act(service, w.governor, DriverSession.Inherit(run.cycle.get, LineageMember.Run(id), LineageMember.Attempt(AttemptId(uuid))))
        first <- directive(service, w, key)
        (again, _, resume) = activation(first.directive.text, w.project)
        reattached <- ZIO.attemptBlocking(owner.activate(again, workflow, "Driven", resume))
        _ <- assertIO(reattached == run)
        // A host that holds no record of the cycle's run, as after a failed activation record.
        second <- directive(service, w, key)
        (lost, _, other) = activation(second.directive.text, w.project)
        refused <- ZIO.attemptBlocking(host(service, w, runtime, () => true).activate(lost, workflow, "Driven", other)).either
        _ <- assertIO(denied(refused) && fault(refused).exists(_.toString.contains("CQ driver stopped with reason failure")))
        _ <- failed(service, w, key, s"run ${id.value} of cycle 1 is not the active workflow of its attached host")
      } yield ()
    }

    "retry lineage registration and settlement in transit and stop the driver naming a member the host cannot register or settle" in { (service: LedgerService[IO]) =>
      val w = world
      def scenario(name: String, observed: zio.Task[Option[LineageOutcome]], dropped: (DriverSession, Int) => Boolean)(verify: (World, DriverKey, LineageMember.Attempt) => IO[Throwable, Unit]): IO[Throwable, Unit] = {
        val session = w.copy(governor = w.other(Role.Governor))
        val key = claude(name)
        val attempt = LineageMember.Attempt(AttemptId(uuid))
        val calls = new java.util.concurrent.atomic.AtomicInteger(0)
        for {
          runtime <- ZIO.runtime[Any]
          root <- create(service, w.operator, goal(name))
          one <- driven(service, session, key, workset(root))
          reports <- zio.Ref.make(List.empty[String])
          tracker = new LineageTracker(new DriverSessionClient(new SessionApi(service, session.governor, runtime, action => dropped(action, calls.incrementAndGet())), w.project),
            message => Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(reports.update(message :: _)).getOrThrowFiberFailure() }, Pause, Pause, Pause.multipliedBy(4))
          _ <- tracker.track(one.cycle, LineageMember.Run(one.run), attempt, observed)
          _ <- verify(session, key, attempt)
        } yield ()
      }
      def eventually(service: LedgerService[IO], session: World, key: DriverKey)(holds: Option[DriverStatus] => Boolean): IO[Throwable, Unit] =
        (ZIO.sleep(zio.Duration.fromMillis(50)) *> status(service, session, key)).repeatUntil(holds)
          .timeoutFail(new IllegalStateException("The lineage did not reach the expected state"))(zio.Duration.fromSeconds(30)).unit
      for {
        _ <- service.initialize(w.operator, "tracker-retry")
        settled = ZIO.some(LineageOutcome.Settled)
        _ <- scenario("tracker-transient", settled, (action, call) => (action.isInstanceOf[DriverSession.Inherit] || action.isInstanceOf[DriverSession.Settle]) && call % 3 != 0) { (session, key, attempt) =>
          eventually(service, session, key)(value => value.exists(_.state == DriverState.On) && lineage(value).contains(LineageEntry(attempt, lineage(value).headOption.map(_.member), true)))
        }
        _ <- scenario("tracker-unregistered", settled, (action, _) => action.isInstanceOf[DriverSession.Inherit]) { (session, key, attempt) =>
          failed(service, session, key, s"attempt ${attempt.id.value} of cycle 1 could not be registered: Connection reset")
        }
        // How a member ended is reported until the server has it, however long the server stays out of reach: more calls are lost
        // here than a registration is tried, and the drive is neither stopped nor left with the member in flight.
        _ <- scenario("tracker-unsettled", settled, (action, call) => action.isInstanceOf[DriverSession.Settle] && call <= 16) { (session, key, attempt) =>
          eventually(service, session, key)(value => value.exists(_.state == DriverState.On) && lineage(value).contains(LineageEntry(attempt, lineage(value).headOption.map(_.member), true)))
        }
        _ <- scenario("tracker-unreadable", ZIO.fail(DomainFailure(Fault.Missing("Attempt is not owned by this governing session"))), (_, _) => false) { (session, key, attempt) =>
          eventually(service, session, key)(value => stopped(value, DriverStop.Failure) && value.get.stopped.get.detail.startsWith(s"attempt ${attempt.id.value} of cycle 1 could not be settled: "))
        }
        // A member that comes to rest is reported as resting, and as in flight again once the host works on it.
        phases <- zio.Ref.make[Option[LineageOutcome]](Some(LineageOutcome.Resting))
        _ <- scenario("tracker-resting", phases.get, (_, _) => false) { (session, key, attempt) =>
          for {
            held <- query(service, session, key).repeatUntil(_.isInstanceOf[DriverReply.Stop])
            _ <- assertIO(held match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), _, _) => detail.startsWith(s"cycle 1 is held by attempt ${attempt.id.value}"); case _ => false })
          } yield ()
        }
        _ <- phases.set(Some(LineageOutcome.Resting))
        _ <- scenario("tracker-resumed", phases.get, (_, _) => false) { (session, key, attempt) =>
          for {
            _ <- query(service, session, key)
            _ <- phases.set(None)
            _ <- ZIO.sleep(Pause.multipliedBy(20))
            resumed <- ZIO.foreach(List.fill(3)(()))(_ => query(service, session, key))
            _ <- assertIO(resumed.forall(_.isInstanceOf[DriverReply.Waiting]))
            _ <- phases.set(Some(LineageOutcome.Settled))
            _ <- eventually(service, session, key)(value => lineage(value).contains(LineageEntry(attempt, lineage(value).headOption.map(_.member), true)))
          } yield ()
        }
        _ <- assertIO(IntegrationPhase.all.map(AttachedDriver.integration) == List(None, Some(LineageOutcome.Resting), None, Some(LineageOutcome.Resting),
          Some(LineageOutcome.Settled), Some(LineageOutcome.Settled), Some(LineageOutcome.Settled)) &&
          CombinationPhase.all.map(AttachedDriver.combination) == List(None, Some(LineageOutcome.Settled), Some(LineageOutcome.Resting), Some(LineageOutcome.Settled)))
      } yield ()
    }

    "give work that rests on the session one resume directive and then stop naming it" in { (service: LedgerService[IO]) =>
      val w = world
      val key = claude("resting")
      val integration = LineageMember.Integration(IntegrationId(uuid))
      for {
        _ <- service.initialize(w.operator, "resting")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        run = LineageMember.Run(one.run)
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, run, integration))
        rested <- act(service, w.governor, DriverSession.Rest(one.cycle, integration))
        waiting <- status(service, w, key)
        _ <- assertIO(rested == DriverReply.Lineage(one.cycle, LineageEntry(integration, Some(run), false)) && waiting.exists(_.activeChildren == 0))
        prompted <- query(service, w, key)
        _ <- assertIO(prompted match { case DriverReply.Continue(value, _, _) => value.cycle == one.cycle && value.token.isInstanceOf[CycleToken.Resume]; case _ => false })
        // The session resumes the member, which then comes to rest again: that is new work to resolve and earns its own resume directive.
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, run, integration))
        running <- ZIO.foreach(List.fill(2)(()))(_ => query(service, w, key))
        _ <- act(service, w.governor, DriverSession.Rest(one.cycle, integration))
        again <- query(service, w, key)
        // While the host works on it the session may stop and nothing is issued; at rest again it earns its directive.
        _ <- assertIO(running.forall(_.isInstanceOf[DriverReply.Waiting]) && again.isInstanceOf[DriverReply.Continue])
        held <- query(service, w, key)
        _ <- assertIO(held match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), Some(value), _) =>
            detail == s"cycle 1 is held by integration ${integration.id.value}, which only the session can resolve, and a resume directive did not resolve it" &&
              stopped(Some(value), DriverStop.Failure) && value.directives == 3
          case _ => false
        })
        // Resolved after its resume directive, the member no longer holds the cycle and the next decision is an ordinary one.
        resolving = w.copy(governor = w.other(Role.Governor))
        two <- driven(service, resolving, claude("rest-resolved"), workset(root))
        _ <- act(service, resolving.governor, DriverSession.Inherit(two.cycle, LineageMember.Run(two.run), integration))
        _ <- act(service, resolving.governor, DriverSession.Rest(two.cycle, integration))
        _ <- directive(service, resolving, claude("rest-resolved"))
        _ <- act(service, resolving.governor, DriverSession.Settle(two.cycle, integration))
        done <- query(service, resolving, claude("rest-resolved"))
        _ <- assertIO(done match { case DriverReply.Stop(DriverStopped(DriverStop.Quiescent, _), _, _) => true; case _ => false })
        // The host reports a member it cannot account for: the drive stops with the member named.
        failing = w.copy(governor = w.other(Role.Governor))
        three <- driven(service, failing, claude("rest-failed"), workset(root))
        outsider <- act(service, w.other(Role.Governor), DriverSession.Rest(three.cycle, LineageMember.Run(three.run))).either
        vague <- act(service, failing.governor, DriverSession.Fail(three.cycle, LineageMember.Run(three.run), " ")).either
        still <- status(service, failing, claude("rest-failed"))
        _ <- assertIO(denied(outsider) && invalid(vague) && still.exists(_.state == DriverState.On))
        reported <- act(service, failing.governor, DriverSession.Fail(three.cycle, integration, "could not be registered: Connection reset"))
        _ <- assertIO(reported match {
          case DriverReply.Stop(DriverStopped(DriverStop.Failure, detail), Some(value), Nil) =>
            detail == s"integration ${integration.id.value} of cycle 1 could not be registered: Connection reset" && stopped(Some(value), DriverStop.Failure)
          case _ => false
        })
        announced <- query(service, failing, claude("rest-failed"))
        late <- act(service, failing.governor, DriverSession.Fail(three.cycle, integration, "could not be settled")).either
        _ <- assertIO((announced match { case DriverReply.Stop(DriverStopped(DriverStop.Failure, _), _, List(_)) => true; case _ => false }) && denied(late))
      } yield ()
    }

    "carry the integrations an earlier drive left unsettled to the next drives of the same attached session only" in {
      (service: LedgerService[IO], registry: DriverInspector) =>
      val w = world
      val (first, renewed) = (claude("carry-first"), claude("carry-renewed"))
      val (unsettled, settled) = (IntegrationId(uuid), IntegrationId(uuid))
      val stranger = w.copy(governor = w.other(Role.Governor))
      def record(key: DriverKey): DriverRecord = registry.get(w.project, key).get
      def carries(key: DriverKey, session: World, integration: IntegrationId): Boolean = record(key).carries(session.governor.actor.session, integration)
      // What the attached host reads before it applies an integration: whether a start directive is pending and what may be settled before it.
      def settleable(session: World): IO[Throwable, (Boolean, Set[IntegrationId])] = act(service, session.governor, DriverSession.Settleable()).flatMap {
        case DriverReply.Settleable(pending, integrations) => ZIO.succeed(pending -> integrations)
        case other => ZIO.fail(new IllegalStateException("Expected the settleable integrations: " + other))
      }
      for {
        _ <- service.initialize(w.operator, "carry")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, first, workset(root))
        _ <- ZIO.foreachDiscard(List(unsettled, settled))(id => act(service, w.governor, DriverSession.Inherit(one.cycle, LineageMember.Run(one.run), LineageMember.Integration(id))))
        _ <- act(service, w.governor, DriverSession.Settle(one.cycle, LineageMember.Integration(settled)))
        _ <- assertIO(record(first).carried.isEmpty)
        active <- settleable(w)
        _ <- assertIO(active == (false, Set.empty))
        _ <- park(service, w, first)
        // The next drive under the same key carries the unsettled integration once its start directive is pending, and never the settled one.
        _ <- on(service, w, first, workset(root))
        bound = record(first)
        _ <- assertIO(bound.carried == Map(w.governor.actor.session -> Set(unsettled)) && !carries(first, w, unsettled))
        undirected <- settleable(w)
        _ <- assertIO(undirected == (false, Set.empty))
        _ <- directive(service, w, first)
        _ <- assertIO(carries(first, w, unsettled) && !carries(first, w, settled) && !carries(first, stranger, unsettled))
        pending <- settleable(w)
        unbound <- settleable(stranger)
        _ <- assertIO(pending == (true, Set(unsettled)) && unbound == (false, Set.empty))
        // A drive that stops without settling it passes it on, also to the driver of a new session key the same attached session binds.
        _ <- on(service, w, renewed, workset(root))
        _ <- directive(service, w, renewed)
        _ <- assertIO(stopped(Some(DriverPolicy.status(record(first))), DriverStop.Parked) && carries(renewed, w, unsettled))
        _ <- park(service, w, renewed)
        // Another attached session that drives under either key carries nothing of it.
        _ <- on(service, stranger, renewed, workset(root))
        _ <- directive(service, stranger, renewed)
        _ <- assertIO(!carries(renewed, stranger, unsettled) && record(renewed).attached.contains(stranger.governor.actor.session) &&
          record(renewed).carried == Map(w.governor.actor.session -> Set(unsettled)))
        other <- settleable(stranger)
        parked <- settleable(w)
        _ <- assertIO(other == (true, Set.empty) && parked == (false, Set.empty))
        // A cycle that has started carries nothing: the write then meets the boundary of the active cycle.
        _ <- park(service, stranger, renewed)
        again <- driven(service, w, renewed, workset(root))
        _ <- assertIO(record(renewed).carried == Map(w.governor.actor.session -> Set(unsettled)) && !carries(renewed, w, unsettled) && again.cycle != one.cycle)
        started <- settleable(w)
        _ <- assertIO(started == (false, Set.empty))
      } yield ()
    }

    "I17: report the end of a member once, although a unit names its attempts again at each of its readings, and follow it again when the session resumes it" in {
      (service: LedgerService[IO], registry: DriverInspector) =>
      val w = world
      val session = w.copy(governor = w.other(Role.Governor))
      val key = claude("tracker-once")
      val request = LineageMember.Request(RequestId(uuid))
      val attempt = LineageMember.Attempt(AttemptId(uuid))
      val integration = LineageMember.Integration(IntegrationId(uuid))
      val sent = new java.util.concurrent.ConcurrentLinkedQueue[DriverSession]()
      def count(matches: PartialFunction[DriverSession, Boolean]): Int = { import scala.jdk.CollectionConverters.*; sent.asScala.count(matches.applyOrElse(_, (_: DriverSession) => false)) }
      def settled(member: LineageMember): IO[Throwable, Unit] = (ZIO.sleep(zio.Duration.fromMillis(20)) *> ZIO.succeed(registry.get(w.project, key).flatMap(_.cycle)))
        .repeatUntil(_.exists(_.lineage.exists(entry => entry.member == member && entry.settled)))
        .timeoutFail(new IllegalStateException(s"$member was not settled"))(zio.Duration.fromSeconds(30)).unit
      // Long enough for a follower that has reported its member's end to finish, and for one that should not exist to report.
      val quiet = ZIO.sleep(Pause.multipliedBy(40))
      for {
        _ <- service.initialize(w.operator, "tracker-once")
        runtime <- ZIO.runtime[Any]
        root <- create(service, w.operator, goal("tracker-once"))
        one <- driven(service, session, key, workset(root))
        run = LineageMember.Run(one.run)
        tracker = new LineageTracker(new DriverSessionClient(new SessionApi(service, session.governor, runtime, action => { sent.add(action); false }), w.project), _ => (), Pause, Pause, Pause.multipliedBy(4))
        ended <- zio.Ref.make(Option.empty[LineageOutcome])
        outcome = ChildOutcome(attempt.id, List(root), ChildEnd.Admitted, Some("input"), None)
        concluded = ZIO.some(LineageOutcome.Concluded(outcome))
        // The unit is in flight as its request; its first attempt has ended and the host goes on to the next candidate.
        _ <- tracker.track(one.cycle, run, request, ended.get)
        _ <- tracker.track(one.cycle, request, attempt, concluded)
        _ <- settled(attempt) *> quiet
        // The unit's follower names every attempt of the unit at each reading, the concluded one too.
        _ <- ZIO.foreachDiscard(1 to 3)(_ => tracker.track(one.cycle, request, attempt, concluded) *> quiet)
        // A call that names the ended attempt wakes nothing, and the unit's next reading still does not report it again.
        _ <- tracker.wake(one.cycle, request, attempt) *> tracker.track(one.cycle, request, attempt, concluded) *> quiet
        _ <- ended.set(Some(LineageOutcome.Settled)) *> settled(request) *> quiet
        _ <- ZIO.attempt(assert(count { case DriverSession.Inherit(_, _, `attempt`) => true } == 1 && count { case DriverSession.Conclude(_, value) => value.attempt == attempt.id } == 1,
          sent.toString))
        // A member whose follower ended is followed again when the session makes the host work on it again.
        phase <- zio.Ref.make[Option[LineageOutcome]](Some(LineageOutcome.Settled))
        _ <- tracker.track(one.cycle, run, integration, phase.get)
        _ <- settled(integration) *> quiet
        _ <- tracker.resume(one.cycle, run, integration, phase.get) *> quiet
        _ <- ZIO.attempt(assert(count { case DriverSession.Inherit(_, _, `integration`) => true } == 2 && count { case DriverSession.Settle(_, `integration`) => true } == 2, sent.toString))
      } yield ()
    }

    "hold a caller that names a member whose registration is outstanding until that registration has reached the server or has failed" in {
      (service: LedgerService[IO], registry: DriverInspector) =>
      val w = world
      // Two callers name the same attempt, as the follower of a unit's request and the reply to the dispatch that started the unit do.
      // The first holds the registration in transit; `lost` then loses every try of it.
      def scenario(name: String, lost: Boolean)(verify: (World, DriverKey, LineageMember.Attempt, Int, List[String]) => IO[Throwable, Unit]): IO[Throwable, Unit] = {
        val session = w.copy(governor = w.other(Role.Governor))
        val key = claude(name)
        val request = LineageMember.Request(RequestId(uuid))
        val attempt = LineageMember.Attempt(AttemptId(uuid))
        val entered = new java.util.concurrent.CountDownLatch(1)
        val release = new java.util.concurrent.CountDownLatch(1)
        val registrations = new java.util.concurrent.atomic.AtomicInteger(0)
        val reports = new java.util.concurrent.ConcurrentLinkedQueue[String]()
        def transit(action: DriverSession): Boolean = action match {
          case DriverSession.Inherit(_, _, `attempt`) => registrations.incrementAndGet(); entered.countDown(); release.await(); lost
          case _ => false
        }
        for {
          runtime <- ZIO.runtime[Any]
          root <- create(service, w.operator, goal(name))
          one <- driven(service, session, key, workset(root))
          tracker = new LineageTracker(new DriverSessionClient(new SessionApi(service, session.governor, runtime, transit), w.project), message => { reports.add(message); () },
            Pause, Pause, Pause.multipliedBy(4))
          ended <- zio.Ref.make(Option.empty[LineageOutcome])
          lineage = ZIO.succeed(registry.get(w.project, key).flatMap(_.cycle).map(_.lineage.map(_.member)))
          _ <- tracker.track(one.cycle, LineageMember.Run(one.run), request, ended.get)
          first <- tracker.track(one.cycle, request, attempt, ended.get).fork
          // The held registration is released however the check ends: its thread is not interruptible.
          second <- (for {
            _ <- ZIO.attemptBlocking(assert(entered.await(30, java.util.concurrent.TimeUnit.SECONDS), s"$name: the registration did not start"))
            second <- tracker.track(one.cycle, request, attempt, ended.get).fork
            // Long enough for a caller that does not wait to return.
            _ <- ZIO.sleep(Pause.multipliedBy(40))
            returned <- second.poll
            before <- lineage
            _ <- ZIO.attempt(assert(returned.isEmpty && !before.exists(_.contains(attempt)),
              s"$name: a caller that named the attempt returned while its registration was outstanding: returned=${returned.nonEmpty}, lineage=$before"))
          } yield second).ensuring(ZIO.succeed(release.countDown()))
          _ <- (first.join *> second.join).timeoutFail(new IllegalStateException(s"$name: a caller stayed held after the registration had ended"))(zio.Duration.fromSeconds(30))
          after <- lineage
          _ <- ZIO.attempt(assert(after.exists(_.contains(attempt)) != lost, s"$name: lineage=$after"))
          _ <- verify(session, key, attempt, registrations.get, { import scala.jdk.CollectionConverters.*; reports.asScala.toList })
          _ <- ended.set(Some(LineageOutcome.Settled))
        } yield ()
      }
      for {
        _ <- service.initialize(w.operator, "tracker-single-flight")
        // One registration serves both callers.
        _ <- scenario("registration-held", false) { (_, _, _, registrations, reports) => ZIO.attempt(assert(registrations == 1 && reports.isEmpty, s"$registrations $reports")) }
        // A registration that fails releases the waiting caller, is reported once and stops the driver as it does without a second caller.
        _ <- scenario("registration-lost", true) { (session, key, attempt, _, reports) =>
          failed(service, session, key, s"attempt ${attempt.id.value} of cycle 1 could not be registered: Connection reset") *>
            ZIO.attempt(assert(reports == List(s"Driver lineage registration failed for attempt ${attempt.id.value}: Connection reset; the driver stopped"), reports.toString))
        }
      } yield ()
    }

    "report a member the session resumed as in flight before the resume returns, whatever the tracker read or reported before it" in {
      (service: LedgerService[IO], registry: DriverInspector) =>
      val w = world
      // One followed member of a started cycle; `transit` runs before each lineage request reaches the server.
      def scenario(name: String, pause: zio.Duration, observed: zio.Ref[Option[LineageOutcome]] => zio.Task[Option[LineageOutcome]], transit: DriverSession => Unit)(
        verify: (LineageTracker, zio.Ref[Option[LineageOutcome]], zio.UIO[Option[CycleRecord]], zio.Task[Unit], IO[Throwable, DriverReply]) => IO[Throwable, Unit]): IO[Throwable, Unit] = {
        val session = w.copy(governor = w.other(Role.Governor))
        val key = claude(name)
        val member = LineageMember.Integration(IntegrationId(uuid))
        for {
          runtime <- ZIO.runtime[Any]
          root <- create(service, w.operator, goal(name))
          one <- driven(service, session, key, workset(root))
          phase <- zio.Ref.make[Option[LineageOutcome]](Some(LineageOutcome.Resting))
          tracker = new LineageTracker(new DriverSessionClient(new SessionApi(service, session.governor, runtime, action => { transit(action); false }), w.project), _ => (), pause, Pause, Pause.multipliedBy(4))
          reading = observed(phase)
          _ <- tracker.track(one.cycle, LineageMember.Run(one.run), member, reading)
          cycle = ZIO.succeed(registry.get(w.project, key).flatMap(_.cycle))
          _ <- verify(tracker, phase, cycle, tracker.resume(one.cycle, LineageMember.Run(one.run), member, reading), query(service, session, key))
          // The host finishes the member: its follower settles it and ends.
          _ <- phase.set(Some(LineageOutcome.Settled))
          _ <- (ZIO.sleep(zio.Duration.fromMillis(50)) *> cycle).repeatUntil(_.exists(_.lineage.exists(entry => entry.member == member && entry.settled)))
            .timeoutFail(new IllegalStateException(s"$name: the member was not settled"))(zio.Duration.fromSeconds(30))
        } yield ()
      }
      def rests(cycle: zio.UIO[Option[CycleRecord]]): IO[Throwable, Unit] = (ZIO.sleep(zio.Duration.fromMillis(20)) *> cycle).repeatUntil(_.exists(_.held.nonEmpty))
        .timeoutFail(new IllegalStateException("The member did not come to rest"))(zio.Duration.fromSeconds(30)).unit
      def flying(value: Option[CycleRecord]): Boolean = value.exists(cycle => cycle.held.isEmpty && cycle.inFlight.size == 1)
      for {
        _ <- service.initialize(w.operator, "tracker-resume")
        // The follower sleeps for a second between readings: only the resume itself can report the member before the query that follows it.
        _ <- scenario("resume-immediate", zio.Duration.fromSeconds(1), _.get, _ => ()) { (_, phase, cycle, resume, continuation) => for {
          _ <- rests(cycle)
          prompted <- continuation
          _ <- phase.set(None)
          _ <- resume
          after <- cycle
          decisions <- ZIO.foreach(List.fill(2)(()))(_ => continuation)
          _ <- assertIO(prompted.isInstanceOf[DriverReply.Continue] && flying(after) && decisions.forall(_.isInstanceOf[DriverReply.Waiting]))
        } yield () }
        // A resting report is in transit when the session resumes the member: the resume is reported after it and wins.
        entered = new java.util.concurrent.CountDownLatch(1)
        release = new java.util.concurrent.CountDownLatch(1)
        _ <- scenario("resume-after-rest", Pause, _.get, action => if (action.isInstanceOf[DriverSession.Rest] && entered.getCount > 0) { entered.countDown(); release.await() }) {
          (_, phase, cycle, resume, continuation) => (for {
            _ <- ZIO.attemptBlocking(entered.await())
            _ <- phase.set(None)
            resuming <- resume.fork
            early <- ZIO.sleep(zio.Duration.fromMillis(200)) *> resuming.poll
            _ <- ZIO.succeed(release.countDown())
            _ <- resuming.join
            after <- cycle
            decisions <- ZIO.sleep(Pause.multipliedBy(20)) *> ZIO.foreach(List.fill(2)(()))(_ => continuation)
            later <- cycle
            _ <- assertIO(early.isEmpty && flying(after) && flying(later) && decisions.forall(_.isInstanceOf[DriverReply.Waiting]))
          } yield ()).ensuring(ZIO.succeed(release.countDown()))
        }
        // A reading taken before the resume arrives after it: it is stale and is never reported as resting.
        reading = new java.util.concurrent.CountDownLatch(1)
        arrive = new java.util.concurrent.CountDownLatch(1)
        stale = new java.util.concurrent.atomic.AtomicBoolean(true)
        _ <- scenario("resume-before-stale-reading", Pause, phase => ZIO.suspend {
          if (stale.compareAndSet(true, false)) ZIO.attemptBlocking { reading.countDown(); arrive.await(); Some(LineageOutcome.Resting) } else phase.get
        }, _ => ()) { (_, phase, cycle, resume, continuation) => (for {
          _ <- ZIO.attemptBlocking(reading.await())
          _ <- phase.set(None)
          _ <- resume
          _ <- ZIO.succeed(arrive.countDown())
          decisions <- ZIO.sleep(Pause.multipliedBy(20)) *> ZIO.foreach(List.fill(2)(()))(_ => continuation)
          after <- cycle
          _ <- assertIO(flying(after) && decisions.forall(_.isInstanceOf[DriverReply.Waiting]))
        } yield ()).ensuring(ZIO.succeed(arrive.countDown())) }
      } yield ()
    }

    "record a write in its cycle only once the write is committed" in { (service: LedgerService[IO], repository: LedgerRepository[IO], mutations: LedgerMutation) =>
      val w = world
      val key = claude("uncommitted")
      for {
        _ <- service.initialize(w.operator, "uncommitted")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        claim <- service.acquire(w.governor, ClaimId(uuid), Set(root), 600000L)
        current <- service.get(w.governor, root)
        change = request(List(Mutation.Produce(root, current.item.revision, List(task("Never committed")), None)), List(claim.fence))
        // The boundary admits the write and the transaction then fails, as a failed commit does.
        failed <- repository.transact(w.project) { tx => mutations(tx, w.governor, change, Clock.systemUTC().millis()); throw new java.sql.SQLException("Commit failed") }.either
        after <- status(service, w, key)
        absent <- service.get(w.operator, root.copy(ledger = Ledger.Tasks, number = 1)).either
        _ <- assertIO(failed.left.exists(_.isInstanceOf[java.sql.SQLException]) && missing(absent))
        _ <- assertIO(after.exists(value => value.state == DriverState.On && value.cycle.exists(cycle => cycle.created.isEmpty &&
          cycle.lineage.map(_.member) == List(LineageMember.Run(one.run), LineageMember.Claim(claim.fence.claim)))))
        committed <- service.change(w.governor, change)
        recorded <- status(service, w, key)
        _ <- assertIO(recorded.exists(_.cycle.exists(cycle => cycle.created == committed.items.map(_.id).filter(_ != root) &&
          cycle.lineage.map(_.member).contains(LineageMember.Change(change.request)))))
      } yield ()
    }

    "answer a status read while a ledger write of the project is in progress" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("status-read")
      val (entered, release) = (new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1))
      for {
        _ <- service.initialize(w.operator, "status-read")
        root <- create(service, w.operator, goal("Goal"))
        _ <- on(service, w, key, workset(root))
        writer <- repository.transact(w.project) { _ => entered.countDown(); release.await() }.fork
        _ <- ZIO.attemptBlocking(entered.await())
        line <- status(service, w, key).timeout(zio.Duration.fromSeconds(5)).ensuring(ZIO.succeed(release.countDown()))
        own <- act(service, w.governor, DriverSession.Status())
        _ <- writer.join
        _ <- assertIO(line.flatten.exists(_.line == "CQ driver on: G1 through work; 0 active children") &&
          (own match { case DriverReply.Status(Some(value)) => value.key == key; case _ => false }))
        refused <- ZIO.foreach(List(service.drive(w.governor, DriverRequest.Control(key, DriverOrigin.StatusLine, DriverControl.Status())),
          control(service, w, claude("two words"), DriverOrigin.StatusLine, DriverControl.Status()),
          control(service, w, DriverKey(Harness.Pi, "pi-status"), DriverOrigin.StatusLine, DriverControl.Status())))(_.either)
        _ <- assertIO(denied(refused.head) && refused.tail.forall(invalid))
      } yield ()
    }

    "D156: show a unit whose attempt has ended and whose request has not settled as an active child, as a stop reads it" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val w = world
      val key = claude("unit-in-flight")
      val unit = LineageMember.Request(RequestId(uuid))
      for {
        _ <- service.initialize(w.operator, "unit-in-flight")
        root <- create(service, w.operator, goal("Goal"))
        one <- driven(service, w, key, workset(root))
        // The unit is registered before its first attempt is: it is work in flight from then on.
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, LineageMember.Run(one.run), unit))
        preparing <- status(service, w, key)
        outcome = ChildOutcome(AttemptId(uuid), List(root), ChildEnd.Cancelled, None, None)
        _ <- act(service, w.governor, DriverSession.Inherit(one.cycle, unit, LineageMember.Attempt(outcome.attempt)))
        running <- status(service, w, key)
        // The host reports the end of the attempt and the end of its unit in two calls: between them the unit is still in flight.
        _ <- act(service, w.governor, DriverSession.Conclude(one.cycle, outcome))
        between <- status(service, w, key)
        stop <- query(service, w, key)
        _ <- act(service, w.governor, DriverSession.Settle(one.cycle, unit))
        settled <- status(service, w, key)
        _ <- assertIO(between.map(value => value.activeChildren -> value.line) == Some(1 -> "CQ driver on: G1 through work; 1 active child"))
        _ <- assertIO(stop match {
          case DriverReply.Waiting(value, message) => value.activeChildren == 1 && message == s"CQ driver waiting: cycle 1 has request ${unit.id.value} in flight; the session continues when it ends"
          case _ => false
        })
        _ <- assertIO(preparing.map(_.activeChildren) == Some(1) && running.map(_.activeChildren) == Some(1))
        _ <- assertIO(settled.map(_.activeChildren) == Some(0))
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
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization, new CatalogRead(new McpSchemas()))
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
            message => Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(reports.update(message :: _)).getOrThrowFiberFailure() }, Pause, Pause, Pause.multipliedBy(4))
          finished <- Promise.make[Nothing, Unit]
          run = LineageMember.Run(one.run)
          _ <- tracker.track(one.cycle, run, dispatch, ZIO.some(LineageOutcome.Settled))
          _ <- tracker.track(one.cycle, dispatch, attempt, finished.await.as(Some(LineageOutcome.Settled)))
          _ <- tracker.track(one.cycle, dispatch, attempt, ZIO.some(LineageOutcome.Settled))
          running <- status(ledger, session, key)
          _ <- assertIO(lineage(running).contains(LineageEntry(attempt, Some(dispatch), false)) && running.exists(_.activeChildren == 1))
          resumed <- query(ledger, session, key)
          _ <- assertIO(resumed.isInstanceOf[DriverReply.Waiting])
          _ <- finished.succeed(())
          settled <- (ZIO.sleep(zio.Duration.fromMillis(50)) *> status(ledger, session, key)).repeatUntil(value => lineage(value).filter(_.member != run).forall(_.settled)).timeoutFail(new IllegalStateException("Lineage was not settled"))(zio.Duration.fromSeconds(20))
          _ <- assertIO(settled.exists(_.activeChildren == 0) && lineage(settled).map(_.member).toSet == Set(run, dispatch, attempt))
          _ <- tracker.track(CycleId(uuid), run, LineageMember.Attempt(AttemptId(uuid)), ZIO.some(LineageOutcome.Settled))
          problems <- reports.get
          _ <- assertIO(problems.size == 1 && problems.head.startsWith("Driver lineage registration failed for attempt ") && problems.head.contains("the driver was not stopped"))
          // D145: an attempt that repeated the fault of the attempt before it on the same input stops the drive, naming the work and the fault.
          again = LineageMember.Attempt(AttemptId(uuid))
          repeated = ChildOutcome(again.id, List(target), ChildEnd.Repeated, Some("input"), Some("refused report"))
          _ <- tracker.track(one.cycle, dispatch, again, ZIO.some(LineageOutcome.Concluded(repeated)))
          stopped <- (ZIO.sleep(zio.Duration.fromMillis(50)) *> status(ledger, session, key)).repeatUntil(_.exists(_.state == DriverState.Off)).timeoutFail(new IllegalStateException("The driver was not stopped"))(zio.Duration.fromSeconds(20))
          _ <- assertIO(stopped.flatMap(_.stopped).contains(DriverStopped(DriverStop.Failure,
            s"attempt ${again.id.value} of cycle ${stopped.get.cycle.get.number} failed on G1 with the same fault as the attempt before it on the same input: refused report")))
          recorded <- repository.driverRecords(w.project)
          _ <- assertIO(recorded.find(_.key == key).flatMap(_.cycle).exists(cycle => cycle.outcomes == List(repeated) && cycle.lineage.contains(LineageEntry(again, Some(dispatch), true))))
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
