package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import io.circe.Json
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.UUID
import zio.{IO, Runtime, Unsafe, ZIO}

/**
 * Drives the generated Claude Code and Codex hook entry points — the harness and event words of the hook commands `cq configure`
 * installs — with the stdin payloads the harnesses send, against the real driver core. The "model" of each simulated session does
 * only what the generated command body and the hook output tell it.
 */
abstract class DriverHookTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ProposalService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()
  private val Executable = Path.of("/opt/cq/bin/cq")
  private val Harnesses = List(Harness.Claude, Harness.Codex)

  private final class ApplicationApi(application: Application, authority: Authority, runtime: Runtime[Any]) extends ServerApi {
    var driverReplies = List.empty[DriverReply]
    override def call(command: Command): Result = {
      val result = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(application.execute(authority, command)).getOrThrowFiberFailure() }
      result match { case Result.Driver(reply) => driverReplies = reply :: driverReplies; case _ => () }
      result
    }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("The driver publishes no usage")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("The driver publishes no artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("The driver admits no results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("The driver integrates no candidates")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("The driver issues no credentials")
  }

  // An allowed stop: no block decision, and at most the transcript message that posts the stop reason.
  private def allowed(reply: Json): Option[String] = {
    assert(reply.isNull || reply.asObject.get.keys.toList == List("systemMessage"))
    reply.hcursor.get[String]("systemMessage").toOption
  }

  private final case class Spelling(drive: String, park: String, advance: String)
  private def spelling(harness: Harness): Spelling = harness match {
    case Harness.Codex => Spelling("$cq-drive", "$cq-park", "$cq-advance")
    case _ => Spelling("/cq:drive", "/cq:park", "/cq:advance")
  }

  // One project, the operator-held hook program and the attached sessions of one harness.
  private final class World(val harness: Harness, ledger: LedgerService[IO], application: Application, authorization: Authorization, root: Authority,
    runtime: Runtime[Any], clock: Clock) {
    val project: ProjectId = ProjectId(uuid)
    val operator: Scope = root.scope(project)
    val words: Spelling = spelling(harness)
    val server = new ApplicationApi(application, root, runtime)
    /** The CQ directory of the checkout the hook runs in: what its attached hosts left there. */
    val sessions = new AttachedSessions(Files.createTempDirectory("cq-hook-checkout-"))
    /** The processes the hook descends from, nearest first: its harness is among them when the harness runs its hooks itself. */
    var ancestors = List.empty[ProcessIdentity]
    val views = new CheckoutSessions(() => sessions, () => ancestors)
    val hook = new DriverHook(() => new DriverEntry(server, project), views)
    def await[A](effect: IO[Throwable, A]): A = Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect).getOrThrowFiberFailure() }
    await(ledger.initialize(operator, "hook driver"))

    private def draft(title: String, content: Content): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false, content, Nil)
    private def request(mutations: List[Mutation], fences: List[Fence]): ChangeRequest = ChangeRequest(RequestId(uuid), mutations, fences, "Hook scenario")
    def goal(title: String): ItemId =
      await(ledger.change(operator, request(List(Mutation.Create(draft(title, Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope")))), Nil))).items.head.id
    def task(title: String): ItemId =
      await(ledger.change(operator, request(List(Mutation.Create(draft(title, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil)))), Nil))).items.head.id
    def retitle(id: ItemId, title: String): ChangeRequest = {
      val view = await(ledger.get(operator, id))
      request(List(Mutation.Replace(id, view.item.revision, view.item.draft.copy(title = title))), Nil)
    }
    def title(id: ItemId): String = await(ledger.get(operator, id)).item.draft.title
    def question(title: String): ItemDraft = draft(title, Content.Question(QuestionStatus.Open, "Prompt", "Context", Nil, None, None))
    def asked(title: String): ItemId = await(ledger.change(operator, request(List(Mutation.Create(question(title))), Nil))).items.head.id
    def gate(blocked: ItemId, by: ItemId): Unit = {
      val (source, target) = (await(ledger.get(operator, blocked)), await(ledger.get(operator, by)))
      await(ledger.change(operator, request(List(Mutation.Reference(blocked, source.item.revision, Relation.BlockedBy, by, target.item.revision, true)), Nil)))
      ()
    }
    /** The operator settles a Question in the ledger, as the browser does. */
    def settle(id: ItemId, status: QuestionStatus, answer: Option[String]): Unit = {
      val view = await(ledger.get(operator, id))
      await(ledger.change(operator, request(List(Mutation.Replace(id, view.item.revision,
        view.item.draft.copy(content = Content.Question(status, "Prompt", "Context", Nil, None, answer)))), Nil)))
      ()
    }
    def create(value: ItemDraft): ChangeRequest = request(List(Mutation.Create(value)), Nil)
    def cursor: ChangeCursor = await(ledger.counts(operator)).cursor
    def stored(targets: Set[ItemId], through: WorkflowPhase): WorksetId = await(ledger.createWorkset(operator, targets, through)).id
    // Produces one descendant under `producer` as `scope`, holding the producer claim only for the write.
    def produce(scope: Scope, producer: ItemId, title: String): ItemId = await(for {
      claim <- ledger.acquire(scope, ClaimId(uuid), Set(producer), 600000L)
      current <- ledger.get(scope, producer)
      change = request(List(Mutation.Produce(producer, current.item.revision, List(draft(title, Content.Task(TaskStatus.Ready, List("Observed outcome"), None, Nil))), None)), List(claim.fence))
      ack <- ledger.change(scope, change).ensuring(ledger.release(scope, claim.fence).ignore)
    } yield ack.items.find(_.id != producer).get.id)
    def change(scope: Scope, value: ChangeRequest): ChangeAck = await(ledger.change(scope, value))

    // The hook entry point as the harness runs it: the generated hook command's own harness and event words, and the payload on stdin.
    def invoke(origin: DriverOrigin, stdin: String): String = {
      val command = DriverAssets.hookCommand(Executable, harness, origin).split(" ").toList
      assert(command.take(2) == List(Executable.toString, "hook") && command.size == 4)
      hook.run(command(2), command(3), stdin.getBytes(UTF_8))
    }
    def output(origin: DriverOrigin, stdin: Json): Json = {
      val text = invoke(origin, stdin.noSpaces)
      if (text.isEmpty) Json.Null else { assert(text.endsWith("\n") && text.count(_ == '\n') == 1); parse(text).fold(throw _, identity) }
    }
    // The payload shapes Claude Code 2.1.285 and Codex 0.159.2 were observed to send.
    def payload(event: String, session: String, fields: (String, Json)*): Json = {
      val common = List("session_id" -> Json.fromString(session), "transcript_path" -> Json.fromString(s"/home/operator/sessions/$session.jsonl"),
        "cwd" -> Json.fromString("/home/operator/project"), "permission_mode" -> Json.fromString("default"), "hook_event_name" -> Json.fromString(event))
      val native = if (harness == Harness.Codex) List("turn_id" -> Json.fromString(uuid.toString), "model" -> Json.fromString("gpt-6.1-sol"))
        else List("prompt_id" -> Json.fromString(uuid.toString), "effort" -> Json.obj("level" -> Json.fromString("medium")))
      Json.obj((common ++ native ++ fields)*)
    }
    def status(session: String): Option[DriverStatus] = new DriverEntry(new ApplicationApi(application, root, runtime), project)
      .status(DriverCall(harness.toString.toLowerCase, "StatusLine", Some(session))) match {
        case DriverReply.Status(value) => value
        case other => throw new IllegalStateException(other.toString)
      }
    def session(name: String): Session = {
      val actor = Actor("attached governor", SessionId(uuid), Role.Governor)
      val authority = authorization.authenticate(authorization.grant(root, GrantRequest(project, actor, clock.millis() + 600000)).value, None)
      new Session(this, name, authority.scope(project), new ApplicationApi(application, authority, runtime))
    }
  }

  private val WaitLine = "/opt/cq/bin/cq wait"
  /** The attached host of a session, as it leaves itself in its checkout and in its session directory. */
  private final class Host(world: World, session: SessionId) extends AutoCloseable {
    val directory: Path = Files.createTempDirectory("cq-hook-host-")
    Files.createDirectories(directory.resolve("journal"))
    private val channel = java.nio.channels.FileChannel.open(directory.resolve("journal/owner.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
    private var lock = Option(channel.lock())
    SessionWaiters.create(directory)
    val units = new SessionUnits(directory)
    world.sessions.record(session, AttachedHostRecord(directory.toString, Some(WaitLine)))
    def works(attempt: AttemptId): SessionUnit = { val unit = SessionUnit(SessionUnitKind.Attempt, attempt.value, Nil); units.started(unit); unit }
    def finishes(unit: SessionUnit): Unit = units.ended(UnitEnd(unit, "Completed", Some("ConsiderAcceptance"), None))
    /** A `cq wait` of the session, for as long as the result is open. */
    def waiter(): AutoCloseable = SessionWaiters.hold(directory)(())._1
    /** The harness process that started this host, as the host leaves it in its session directory. */
    def startedBy(owner: ProcessIdentity): Unit = SessionOwner.record(directory, owner)
    /** What the host wrote about the Questions the session waits on, in order. */
    def questions: List[SessionUnitEvent] = SessionUnits.read(directory).filter {
      case _: SessionUnitEvent.Watching | _: SessionUnitEvent.Settled | _: SessionUnitEvent.Released => true
      case _ => false
    }
    /** A real `cq wait` of the session, running until something ends it. */
    def waits(): java.util.concurrent.Future[WaitOutcome] = {
      if (!Files.exists(directory.resolve("run.json"))) Files.writeString(directory.resolve("run.json"), "{}")
      val outcome = new java.util.concurrent.FutureTask[WaitOutcome](() => new SessionWait(directory, () => Thread.sleep(5)).await(Nil))
      new Thread(outcome, "cq-hook-test-waiter").start()
      val deadline = System.nanoTime() + 30000000000L
      while (!SessionWaiters.present(directory) && !outcome.isDone) { assert(System.nanoTime() < deadline, "The waiter did not start"); Thread.sleep(5) }
      outcome
    }
    def ends(): Unit = { lock.foreach(_.release()); lock = None }
    override def close(): Unit = { ends(); channel.close() }
  }

  // One harness session: its hooks receive its session_id, and its model reaches CQ only through its attached session.
  private final class Session(val world: World, val id: String, val scope: Scope, val api: ApplicationApi) {
    import world.*
    val attached = new DriverSessionClient(api, project)
    def submit(prompt: String): Json = output(DriverOrigin.UserPromptSubmit, payload("UserPromptSubmit", id, "prompt" -> Json.fromString(prompt)))
    def context(reply: Json): String = {
      assert(reply.hcursor.downField("hookSpecificOutput").get[String]("hookEventName") == Right("UserPromptSubmit") && reply.hcursor.downField("decision").failed)
      val text = reply.hcursor.downField("hookSpecificOutput").get[String]("additionalContext").toOption.get
      assert(reply.hcursor.get[String]("systemMessage") == Right(text.linesIterator.next()))
      text
    }
    def drive(arguments: String): String = context(submit(s"${words.drive} $arguments"))
    def park(): String = context(submit(words.park))
    def stop(): Json = output(DriverOrigin.Stop, payload("Stop", id, "stop_hook_active" -> Json.False, "last_assistant_message" -> Json.fromString("Done.")))
    def line: String = invoke(DriverOrigin.StatusLine, Json.obj("session_id" -> Json.fromString(id), "version" -> Json.fromString("2.1.285"),
      "workspace" -> Json.obj("current_dir" -> Json.fromString("/home/operator/project"))).noSpaces)
    // The command body's only CQ call: the Bind request printed in the hook's context block.
    def bind(context: String): DriverReply = {
      val printed = context.linesIterator.filter(_.contains("{\"Bind\":")).toList
      assert(printed.size == 1)
      SessionCommand_JsonCodec.decode(BaboonCodecContext.Default, parse(printed.head.drop(printed.head.indexOf("{\"Bind\":"))).toOption.get) match {
        case Right(SessionCommand.Bind(token)) => attached.bind(token)
        case other => throw new IllegalStateException(other.toString)
      }
    }
    def on(arguments: String): Unit = { bind(drive(arguments)); () }
    // A blocked stop: the directive is the last line of the reason, exactly the text the host issued.
    def directive(reply: Json): String = {
      assert(reply.hcursor.get[String]("decision") == Right("block"))
      val reason = reply.hcursor.get[String]("reason").toOption.get
      val issued = world.server.driverReplies.head.asInstanceOf[DriverReply.Continue]
      assert(reason.endsWith("\n" + issued.directive.text) && reason.linesIterator.toList.last == issued.directive.text)
      assert(reason.contains("run the directive on the last line verbatim") && reason.linesIterator.toList.dropRight(2) == issued.messages)
      assert(reply.hcursor.get[String]("systemMessage").toOption == Option.when(issued.messages.nonEmpty)(issued.messages.mkString("\n")))
      issued.directive.text
    }
    // What the advance command does with a directive: reads its roots, phase and token and activates the workflow with them.
    def activation(text: String): (WorkflowRequest, CycleToken) = {
      val parts = text.split(" ").toList
      assert(parts.head == words.advance && parts.size == 7)
      val options = parts.tail.grouped(2).map(pair => pair.head -> pair(1)).toMap
      val workflow = WorkflowArguments.parse(project, Map(WorkflowCatalog.WorkflowFlag -> "advance",
        WorkflowCatalog.Roots.flag -> options(WorkflowCatalog.Roots.flag), WorkflowCatalog.Through.flag -> options(WorkflowCatalog.Through.flag))).get
      val token = options.get(DriverPolicy.StartFlag).map(value => CycleToken.Start(DriverToken(UUID.fromString(value))))
        .getOrElse(CycleToken.Resume(DriverToken(UUID.fromString(options(DriverPolicy.ResumeFlag)))))
      (workflow, token)
    }
    def advance(text: String, run: RequestId): DriverActivation = {
      val (workflow, token) = activation(text)
      attached.activate(run, workflow, Some(token))
    }
    def refused(operation: => Any): Fault = try { operation; throw new IllegalStateException("The operation was admitted") } catch { case DomainFailure(fault) => fault }
    /** A domain tool call as the attached gateway makes it: the command under the session's credential, then the host's look at its result. */
    def tool(watch: QuestionWatch, command: Command): Result = { val result = api.call(command); watch.observe(command, result); result }
    def records(watch: QuestionWatch, value: ItemDraft): ItemId = tool(watch, Command.Change(ChangeInput(project, create(value)))) match {
      case Result.Changed(ack) => ack.items.head.id
      case other => throw new IllegalStateException(other.toString)
    }
    def reads(watch: QuestionWatch, id: ItemId): Result = tool(watch, Command.Read(ReadInput(project, ReadSelection.ItemDetail(id))))
    def failure(reply: Json, detail: String): Unit = {
      val message = allowed(reply).get
      assert(message.startsWith("CQ driver stopped (failure): ") && message.contains(detail))
      assert(world.status(id).exists(value => value.state == DriverState.Off && value.attached.isEmpty && value.stopped.exists(_.reason == DriverStop.Failure)))
      assert(line == world.status(id).get.line + "\n" && line.contains("stopped (failure)"))
      assert(allowed(stop()).isEmpty)
    }
  }

  private def scenarios(body: World => Unit) = { (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
    admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
    val clock = Clock.systemUTC()
    val operatorToken = "driver-hook-test-operator-token-" + uuid
    val authorization = new Authorization(AccessConfig(operatorToken, "http://localhost"), clock)
    val root = authorization.authenticate(operatorToken, Some(uuid.toString))
    val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization, new CatalogRead(new McpSchemas()))
    ZIO.runtime[Any].flatMap(runtime => ZIO.foreachDiscard(Harnesses)(harness =>
      ZIO.attemptBlocking(body(new World(harness, ledger, application, authorization, root, runtime, clock)))))
  }

  "The shared CQ hook driver of Claude Code and Codex (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "let a stop with work in flight pass only when a wake-up is certain, order the waiter once when it is missing, and park a drive that nothing would continue" in scenarios { world =>
      import world.*
      goal("Goal")
      // One driven session per case, each with a child of its first cycle that the server holds in flight.
      def flying(name: String): (Session, AttemptId) = {
        val s = session(name)
        s.on("G1 through=work")
        val run = RequestId(uuid)
        val one = s.advance(s.directive(s.stop()), run) match { case DriverActivation.Started(cycle) => cycle; case other => fail(other.toString) }
        val attempt = AttemptId(uuid)
        s.attached.inherit(one, LineageMember.Run(run), LineageMember.Attempt(attempt))
        (s, attempt)
      }
      def on(s: Session, directives: Int): Boolean = status(s.id).exists(value => value.state == DriverState.On && value.directives == directives)
      def parked(s: Session): Boolean = status(s.id).exists(value => value.state == DriverState.Off && value.stopped.exists(_.reason == DriverStop.Parked))

      // Work standing on the host and no waiter: the stop is blocked with the exact command, which is no directive. A session that
      // stops again on the same work without a waiter is not asked twice.
      val (unwaited, first) = flying("unwaited")
      val host = new Host(world, unwaited.scope.actor.session)
      val unit = host.works(first)
      val ordered = unwaited.stop()
      assert(ordered.asObject.get.keys.toList == List("decision", "reason") && ordered.hcursor.get[String]("decision") == Right("block"))
      val reason = ordered.hcursor.get[String]("reason").toOption.get
      assert(reason.contains(s"attempt ${first.value}") && on(unwaited, 1), reason)
      // A Claude Code session is woken by its background command; a Codex session by nothing, so it waits in a status call of the host.
      if (harness == Harness.Claude) assert(reason.endsWith(s"as a background command (run_in_background true, timeout 7200000), then end your turn: `$WaitLine`"), reason)
      else assert(reason.endsWith("Do not end your turn: nothing wakes you when it ends. Call the status of that work now with the CQ dispatch tool " +
        "(Status, IntegrationStatus or CombinationStatus) with waitMillis 120000, and again while the work continues. " + cq.host.DispatchWaits.CodexScript) && reason.contains("`// @exec: {\"yield_time_ms\": 150000}`") && !reason.contains(WaitLine) && !reason.contains("shell"), reason)
      if (harness == Harness.Claude) {
        // The session starts its waiter: the stop passes. Once the waiter is gone while the work stands, the order is given anew.
        val waiter = host.waiter()
        try assert(allowed(unwaited.stop()).exists(_.startsWith("CQ driver waiting: cycle 1")) && on(unwaited, 1)) finally waiter.close()
        assert(unwaited.stop().hcursor.get[String]("decision") == Right("block") && on(unwaited, 1))
      }
      val ended = allowed(unwaited.stop()).getOrElse(fail("The stop said nothing"))
      assert(ended.startsWith(s"${DriverHook.Unwaited} attempt ${first.value}. CQ driver parked: G1 through work") && parked(unwaited), ended)
      host.finishes(unit)

      // The host works on nothing although the server holds a member in flight: Waiting is not accepted, and the cycle is settled
      // by the server's own rule, a resume directive.
      val (stale, _) = flying("stale")
      val idle = new Host(world, stale.scope.actor.session)
      assert(stale.directive(stale.stop()).contains(DriverPolicy.ResumeFlag) && on(stale, 2))

      // The host is gone: nothing finishes the child and nothing wakes the session.
      val (lost, _) = flying("lost")
      val gone = new Host(world, lost.scope.actor.session)
      gone.works(AttemptId(uuid))
      gone.ends()
      val said = allowed(lost.stop()).getOrElse(fail("The stop said nothing"))
      assert(said.startsWith(DriverHook.HostGone + " CQ driver parked: G1 through work") && said.contains("cq job upload --session") && parked(lost), said)
      assert(allowed(lost.stop()).isEmpty)

      // No host of this checkout recorded the session: it ended in order, or a package without the waiter started it. The message says that, not more.
      val (unrecorded, _) = flying("unrecorded")
      val unknown = allowed(unrecorded.stop()).getOrElse(fail("The stop said nothing"))
      assert(unknown.startsWith(DriverHook.HostUnrecorded + " CQ driver parked: G1 through work") && !unknown.contains("is not running") && parked(unrecorded), unknown)
      List(host, idle, gone).foreach(_.close())
    }
    "start and park a driver from the typed command, pass other prompts through and bind through the command body" in scenarios { world =>
      import world.*
      val root = goal("Goal")
      val child = produce(operator, root, "Descendant")
      val neighbour = task("Unrelated")
      val a = session("drive-a")
      val other = spelling(Harnesses.find(_ != harness).get)
      List("Reply with exactly: HELLO", s"please ${words.drive} G1 through=work", s"${words.drive}r G1 through=work", s"${other.drive} G1 through=work", other.park, "")
        .foreach(prompt => assert(a.submit(prompt).isNull))
      assert(status(a.id).isEmpty && allowed(a.stop()).isEmpty && a.line == "CQ driver off\n")
      List("through=work" -> "Drive targets are empty; name at least one item ID. Empty targets never mean the whole project", "" -> "Drive targets are empty",
        "G1 through=ship" -> "Unknown through phase ship", "G1" -> "Drive requires exactly one through=<phase>", "G9 through=work" -> "missing: ",
        "X1 through=work" -> "Unknown item ID X1", s"workset=$uuid" -> "missing: Missing workset").foreach { (arguments, detail) =>
        val rejected = a.context(a.submit(s"${words.drive} $arguments".stripTrailing))
        assert(rejected.startsWith("CQ driver drive-start rejected: " + detail) && !rejected.contains("Bind"))
        assert(rejected.linesIterator.toList.last == "Nothing changed for this session's driver and no bind token was issued.")
        assert(status(a.id).isEmpty)
      }
      val started = a.drive("G1 through=work")
      val token = status(a.id).map(_.state)
      assert(token.contains(DriverState.Binding))
      assert(started.linesIterator.toList.take(2) == List(
        "CQ driver drive-start: CQ driver binding: G1 through work; it turns on when this session presents the bind token", "CQ driver binding: G1 through work"))
      assert(started.contains("Advanceable (2):\n- G1 [Open] Goal (target) — ready\n- T1 [Ready] Descendant — ready\nContext only, never advanced (0):"))
      assert(!started.contains("Unrelated") && a.line == "CQ driver binding: G1 through work\n")
      // The generated command body presents the hook's bind token to the bind operation and displays status; it starts and parks nothing.
      DriverAssets.commands(harness).foreach { asset =>
        assert(asset.body.contains("cannot start or park a driver") && asset.body.contains("CQ `session` tool") && !asset.body.contains("{{"))
        assert(!asset.body.contains("Control") && !asset.body.contains("\"Start\"") && !asset.body.contains("\"Park\""))
      }
      assert(DriverAssets.commands(harness).head.body.contains("Bind request printed in the block") && DriverAssets.commands(harness).head.body.contains(DriverHook.DriveLabel))
      a.bind(started) match {
        case DriverReply.Bound(bound, message) => assert(bound.state == DriverState.On && bound.attached.contains(a.scope.actor.session) && message == "CQ driver on: G1 through work")
        case reply => fail(reply.toString)
      }
      assert(a.line == "CQ driver on: G1 through work; 0 active children\n")
      assert(a.refused(a.bind(started)).isInstanceOf[Fault.Denied])
      val again = a.context(a.submit(s"${words.drive} G1 through=plan"))
      assert(again.startsWith("CQ driver drive-start rejected: conflict: ") && status(a.id).exists(value => value.state == DriverState.On && value.through == WorkflowPhase.Work))
      assert(a.park().linesIterator.toList == List("CQ driver park: CQ driver parked: G1 through work", "CQ driver off: G1 through work; stopped (parked): Parked by the operator"))
      assert(a.line == "CQ driver off: G1 through work; stopped (parked): Parked by the operator\n" && allowed(a.stop()).isEmpty)
      assert(a.park().linesIterator.next() == "CQ driver park: CQ driver is already off")
      val workset = stored(Set(root, neighbour), WorkflowPhase.Plan)
      val byWorkset = a.context(a.submit(s"  ${words.drive}\tworkset=${workset.value}\n"))
      assert(byWorkset.contains("CQ driver binding: G1,T2 through plan") && status(a.id).exists(value => value.workset.contains(workset) && value.targets == Set(root, neighbour)))
      // A session that never presents the bind token: the driver never turns on and the next stop is allowed with the not-bound failure.
      val skipped = allowed(a.stop())
      assert(skipped.contains("CQ driver stopped (not bound): failure: no attached CQ session presented the bind token, so the driver never turned on"))
      assert(status(a.id).exists(value => value.state == DriverState.Off && value.stopped.exists(_.reason == DriverStop.NotBound)) && a.line.contains("stopped (not bound)"))
      assert(allowed(a.stop()).isEmpty && a.refused(a.bind(byWorkset)).isInstanceOf[Fault.Denied] && child.ledger == Ledger.Tasks)
      // A large preview is listed up to a bound and the rest is counted.
      val many = (1 to 26).map(index => task(s"Member $index")).map(DriverPolicy.reference)
      val large = a.drive(many.mkString(",") + " through=explore").linesIterator.toList
      assert(large.contains("Advanceable (26):") && large.count(_.startsWith("- T")) == 25 && large.contains("- … 1 more") && large.mkString("\n").length < 10000)
    }

    "say that the driver may have changed when a drive or park reply is lost, and that nothing changed when the host rejects" in scenarios { world =>
      import world.*
      val root = goal("Goal")
      // The server commits the request and the reply is lost in transit, as a client deadline does.
      val lossy = new ServerApi {
        override def call(command: Command): Result = { server.call(command); throw new IllegalStateException("HTTP response deadline exceeded") }
        override def usage(value: HostUsageInput): HostUsageResult = server.usage(value)
        override def artifact(value: ArtifactUpload): ArtifactMetadata = server.artifact(value)
        override def admit(value: HostAdmissionInput): ResultAdmission = server.admit(value)
        override def integrate(value: HostIntegrationInput): IntegrationRecord = server.integrate(value)
        override def grant(value: GrantRequest): AccessToken = server.grant(value)
      }
      val unanswered = new DriverHook(() => new DriverEntry(lossy, project), views)
      def prompt(hook: DriverHook, text: String): String = {
        val reply = parse(hook.run(harness.toString.toLowerCase, "UserPromptSubmit", payload("UserPromptSubmit", "lost-reply", "prompt" -> Json.fromString(text)).noSpaces.getBytes(UTF_8))).fold(throw _, identity)
        reply.hcursor.downField("hookSpecificOutput").get[String]("additionalContext").fold(throw _, identity)
      }
      val lost = prompt(unanswered, s"${words.drive} G1 through=work")
      assert(status("lost-reply").exists(_.state == DriverState.Binding), "the server did start the driver")
      assert(lost == s"CQ driver drive-start rejected: HTTP response deadline exceeded\nThe CQ server's reply was not received, so this session's driver may have changed and a bind token may have been issued. " +
        s"Read the driver status with the CQ session tool ({\"Driver\":{}}) or run ${words.park} before driving again.")
      val parked = prompt(unanswered, words.park)
      assert(status("lost-reply").exists(_.stopped.exists(_.reason == DriverStop.Parked)) && parked.startsWith("CQ driver park rejected: HTTP response deadline exceeded\nThe CQ server's reply was not received"))
      // A rejection is the host's answer: nothing changed.
      val rejected = prompt(hook, s"${words.drive} through=work")
      assert(rejected.startsWith("CQ driver drive-start rejected: Drive targets are empty") &&
        rejected.endsWith("\nNothing changed for this session's driver and no bind token was issued."))
      assert(root.number == 1)
    }

    "keep two concurrent sessions isolated by their own session_id and show the trusted-key limitation" in scenarios { world =>
      import world.*
      val first = goal("Goal of A")
      val second = goal("Goal of B")
      val a = session("concurrent-a")
      val b = session("concurrent-b")
      a.on("G1 through=plan")
      b.on("G2 through=work")
      val beforeB = status(b.id)
      assert(status(a.id).exists(value => value.state == DriverState.On && value.targets == Set(first) && value.attached.contains(a.scope.actor.session)) &&
        beforeB.exists(value => value.state == DriverState.On && value.targets == Set(second) && value.attached.contains(b.scope.actor.session)))
      assert(a.line == "CQ driver on: G1 through plan; 0 active children\n" && b.line == "CQ driver on: G2 through work; 0 active children\n")
      assert(a.directive(a.stop()).startsWith(s"${words.advance} --roots G1 --through plan --start-token ") && status(b.id) == beforeB)
      a.park()
      assert(status(a.id).exists(_.state == DriverState.Off) && status(b.id) == beforeB)
      assert(b.directive(b.stop()).startsWith(s"${words.advance} --roots G2 --through work --start-token "))
      // Decision 2 limitation: the session_id is trusted, not authenticated. B's hook input carrying A's id parks A's driver, not B's.
      a.on("G1 through=plan")
      val runningB = status(b.id)
      assert(status(a.id).exists(_.state == DriverState.On))
      val forged = b.context(output(DriverOrigin.UserPromptSubmit, payload("UserPromptSubmit", a.id, "prompt" -> Json.fromString(words.park))))
      assert(forged.startsWith("CQ driver park: CQ driver parked: G1 through plan"))
      assert(status(a.id).exists(value => value.state == DriverState.Off && value.stopped.exists(_.reason == DriverStop.Parked)) && status(b.id) == runningB)
    }

    "D164: tell a session at its turn end, once, how a person settled a Question it waits on, wake its waiter with it instead when one runs, and let a session with nothing new stop" in scenarios { world =>
      import world.*
      val root = goal("Goal")
      // A drive whose one root waits for an answer rests from its first stop on: every later stop asks the driver again.
      val held = asked("Which way")
      gate(root, held)
      val s = session("answers")
      s.on("G1 through=work")
      val host = new Host(world, s.scope.actor.session)
      val watch = new QuestionWatch(s.api, project, host.directory, problem => fail(problem))
      def end(id: ItemId, status: QuestionStatus, answer: Option[String]): QuestionEnd = QuestionEnd(id, title(id), status, answer)
      def said(reply: Json): List[String] = reply.hcursor.get[String]("reason").toOption.toList.flatMap(_.linesIterator.toList)
      def announced(reply: Json): List[String] = {
        val lines = said(reply)
        if (!lines.contains(DriverHook.Settled)) Nil
        else {
          assert(reply.hcursor.get[String]("decision") == Right("block") && lines.head == DriverHook.Settled && lines.contains(SessionQuestions.Act))
          lines.tail.takeWhile(_ != SessionQuestions.Act).map(_.stripPrefix("- "))
        }
      }
      val stopped = allowed(s.stop())
      assert(stopped.contains("CQ driver stopped (user input required): Awaiting the user on Q1; the driver never answers questions or infers approval"), stopped.toString)
      assert(status(s.id).exists(value => value.state == DriverState.Off && value.attached.contains(s.scope.actor.session)))

      // The set: the Open Questions the session wrote, and those the workset of its advance workflow waits on; nothing else.
      val own = s.records(watch, question("Asked by the session"))
      val foreign = asked("Asked by somebody else")
      assert(host.questions == List(SessionUnitEvent.Watching(own)))
      watch.enter(WorkflowRequest.Advance(Set(root), WorkflowPhase.Work))
      watch.poll()
      assert(host.questions == List(SessionUnitEvent.Watching(own), SessionUnitEvent.Watching(held)), host.questions.toString)
      assert(sessions.view(s.scope.actor.session) == HostView.Running(host.directory, Nil, List(own, held), false, Some(WaitLine)))

      // Nothing was settled: a round of the host writes nothing, and the stop announces nothing. Where a waiter can start the next
      // turn the session is told once to start it; told or not, it may stop.
      val before = (host.questions, server.driverReplies.size)
      watch.poll()
      val first = s.stop()
      if (harness == Harness.Codex) assert(first.isNull, first.noSpaces)
      else {
        assert(said(first) == List(s"CQ: this session waits on Q2, Q1 and no cq wait runs for it, so nothing would start your next turn when a person settles one of them. " +
          s"Run exactly this command now with the Bash tool as a background command (run_in_background true, timeout 7200000), then end your turn: `$WaitLine`"), first.noSpaces)
      }
      assert(List.fill(2)(s.stop()).forall(_.isNull) && host.questions == before._1)
      assert(server.driverReplies.drop(0).take(server.driverReplies.size - before._2).forall {
        case DriverReply.Stop(DriverStopped(DriverStop.Off, _), _, Nil) | _: DriverReply.Status => true
        case _ => false
      })

      // Settled with no waiter running: the next turn end says so, once. A Question outside the set is not announced.
      settle(own, QuestionStatus.Answered, Some("Take the\nsecond  alternative"))
      settle(foreign, QuestionStatus.Answered, Some("Nobody of this session asked"))
      watch.poll()
      assert(host.questions.last == SessionUnitEvent.Settled(end(own, QuestionStatus.Answered, Some("Take the\nsecond  alternative")), false))
      val told = s.stop()
      assert(announced(told) == List("question Q2 \"Asked by the session\" answered: Take the second alternative"), told.noSpaces)
      assert(List.fill(2)(s.stop()).forall(reply => announced(reply).isEmpty) && !SessionUnits.read(host.directory).toString.contains("Nobody of this session"))

      // Settled while a waiter runs: the waiter reports it and ends, and no turn end announces it again.
      val waited = s.records(watch, question("Waited for"))
      val waiter = host.waits()
      assert(allowed(s.stop()).isEmpty)
      settle(waited, QuestionStatus.Withdrawn, None)
      watch.poll()
      val outcome = waiter.get(30, java.util.concurrent.TimeUnit.SECONDS)
      assert(outcome == WaitOutcome.Ended(Nil, Nil, List(end(waited, QuestionStatus.Withdrawn, None))), outcome.toString)
      assert(SessionWait.lines(host.directory, outcome) == List("question Q4 \"Waited for\" withdrawn"))
      assert(host.questions.last == SessionUnitEvent.Settled(end(waited, QuestionStatus.Withdrawn, None), true))
      assert(List.fill(2)(s.stop()).forall(reply => announced(reply).isEmpty))
      // A waiter that starts afterwards has nothing of it to report: the session still waits on the first Question only.
      val later = host.waits()
      assert(!later.isDone)

      // A Question the session reads after it was settled is not announced: it has read it.
      val read = s.records(watch, question("Read in time"))
      later.cancel(true)
      while (SessionWaiters.present(host.directory)) Thread.sleep(5)
      settle(read, QuestionStatus.Answered, Some("Read before the turn ended"))
      s.reads(watch, read)
      watch.poll()
      assert(host.questions.takeRight(2) == List(SessionUnitEvent.Watching(read), SessionUnitEvent.Released(read)), host.questions.toString)
      assert(announced(s.stop()).isEmpty)

      // The answer the drive rested on: the same stop announces it and carries the directive of the drive, which continues.
      settle(held, QuestionStatus.Answered, Some("This way"))
      watch.poll()
      val continued = s.stop()
      val issued = server.driverReplies.head match { case value: DriverReply.Continue => value; case other => fail(other.toString) }
      assert(announced(continued) == List("question Q1 \"Which way\" answered: This way") &&
        said(continued).takeRight(3) == List(DriverPolicy.Rested, said(continued).takeRight(2).head, issued.directive.text) &&
        issued.directive.text.startsWith(s"${words.advance} --roots G1 --through work --start-token "), continued.noSpaces)
      assert(status(s.id).exists(_.state == DriverState.On) && SessionQuestions.open(SessionUnits.read(host.directory)).isEmpty)

      // A session whose turn end asks its host, as the Pi extension does, is told by the host, once.
      val direct = session("asks-its-host")
      val other = new Host(world, direct.scope.actor.session)
      val asking = new QuestionWatch(direct.api, project, other.directory, problem => fail(problem))
      val plain = direct.records(asking, question("Asked through the host"))
      assert(asking.settled() == (Nil, true))
      settle(plain, QuestionStatus.Answered, Some("Yes"))
      asking.poll()
      assert(asking.settled() == (List(end(plain, QuestionStatus.Answered, Some("Yes"))), false) && asking.settled() == (Nil, false))
      host.close(); other.close()
    }

    "D164: tell a session that never drove how a person settled a Question it waits on, when its host was started by a process the hook descends from, and no other session" in scenarios { world =>
      import world.*
      def end(id: ItemId, answer: String): QuestionEnd = QuestionEnd(id, title(id), QuestionStatus.Answered, Some(answer))
      def announced(reply: Json): List[String] = reply.hcursor.get[String]("reason").toOption.toList.flatMap(_.linesIterator.toList)
        .dropWhile(_ != DriverHook.Settled).drop(1).takeWhile(_ != SessionQuestions.Act).map(_.stripPrefix("- "))
      // Two harness processes in one checkout, each with its own host; neither session has a driver.
      val (mine, theirs) = (ProcessIdentity(4242L, 1000L), ProcessIdentity(5151L, 2000L))
      val (s, other) = (session("never-driven"), session("another-harness"))
      val (host, second) = (new Host(world, s.scope.actor.session), new Host(world, other.scope.actor.session))
      host.startedBy(mine); second.startedBy(theirs)
      val (watch, watching) = (new QuestionWatch(s.api, project, host.directory, problem => fail(problem)), new QuestionWatch(other.api, project, second.directory, problem => fail(problem)))
      val own = s.records(watch, question("Asked without a drive"))
      val foreign = other.records(watching, question("Asked in the other harness"))
      assert(status(s.id).isEmpty && status(other.id).isEmpty)
      // The hook of the first harness: a shell between it and the harness, and what started the harness, are ancestors as well.
      ancestors = List(ProcessIdentity(9001L, 3000L), mine, ProcessIdentity(1L, 0L))
      assert(views.owned.contains(s.scope.actor.session))
      // Nothing settled: where a waiter can start the next turn the session is told once to start it, and then it stops.
      val first = s.stop()
      if (harness == Harness.Codex) assert(first.isNull, first.noSpaces)
      else assert(first.hcursor.get[String]("reason").exists(_.startsWith("CQ: this session waits on Q1 and no cq wait runs for it")), first.noSpaces)
      assert(s.stop().isNull)
      settle(own, QuestionStatus.Answered, Some("Go on"))
      settle(foreign, QuestionStatus.Answered, Some("For the other harness"))
      watch.poll(); watching.poll()
      assert(host.questions.last == SessionUnitEvent.Settled(end(own, "Go on"), false) && second.questions.last == SessionUnitEvent.Settled(end(foreign, "For the other harness"), false))
      // A hook that descends from no process that started a host says nothing and lets the stop through: one run by hand, one whose
      // ancestors it cannot see, and one whose ancestor has the pid of the harness and another start.
      List(Nil, List(ProcessIdentity(9001L, 3000L)), List(mine.copy(startMillis = 999L))).foreach { unrelated =>
        ancestors = unrelated
        assert(views.owned.isEmpty && s.stop().isNull && other.stop().isNull, unrelated.toString)
      }
      // The hook of the first harness announces the first session's Question, once, whatever session key it is run with; the second
      // session's Question is said by the hook of the second harness alone.
      ancestors = List(ProcessIdentity(9001L, 3000L), mine)
      val told = s.stop()
      assert(told.hcursor.get[String]("decision") == Right("block") && announced(told) == List("question Q1 \"Asked without a drive\" answered: Go on"), told.noSpaces)
      assert(s.stop().isNull && other.stop().isNull)
      ancestors = List(theirs)
      assert(announced(other.stop()) == List("question Q2 \"Asked in the other harness\" answered: For the other harness") && other.stop().isNull)
      // Two hosts that the same process started cannot be told apart: neither session is named.
      val third = new Host(world, session("same-owner").scope.actor.session)
      third.startedBy(mine)
      ancestors = List(mine)
      assert(views.owned.isEmpty)
      host.close(); second.close(); third.close()
    }

    "reject malformed input, a missing session_id and an unknown event with an explicit error, change nothing and allow the stop" in scenarios { world =>
      import world.*
      goal("Goal")
      val a = session("errors-a")
      a.on("G1 through=work")
      val before = (status(a.id), cursor, server.driverReplies.size)
      val name = harness.toString.toLowerCase
      def error(event: String, stdout: String): String = {
        val reply = parse(stdout).fold(throw _, identity)
        // Only a transcript message: no block decision, no context and no state-changing call.
        assert(reply.asObject.get.keys.toList == List("systemMessage") && stdout.endsWith("\n"))
        val message = reply.hcursor.get[String]("systemMessage").toOption.get
        assert(message.startsWith(s"CQ $event hook error: "))
        message.stripPrefix(s"CQ $event hook error: ")
      }
      List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop).foreach { origin =>
        val event = origin.toString
        val valid = payload(event, "errors-ghost", "prompt" -> Json.fromString(s"${words.drive} G1 through=work"))
        def without(field: String): String = valid.mapObject(_.remove(field)).noSpaces
        def having(field: String, value: Json): String = valid.mapObject(_.add(field, value)).noSpaces
        val missing = "Driver session key is missing; no default session is used"
        val malformed = "Malformed CQ hook input: expected one JSON object on stdin"
        List("not json" -> malformed, "" -> malformed, "[]" -> malformed, "\"session_id\"" -> malformed,
          without("session_id") -> missing, having("session_id", Json.Null) -> missing, having("session_id", Json.fromString("")) -> missing,
          having("session_id", Json.fromString("errors ghost")) -> "Driver session key is malformed", having("session_id", Json.fromString("x" * 201)) -> "Driver session key is malformed",
          having("session_id", Json.fromInt(7)) -> "Malformed CQ hook input: session_id is not a string",
          without("hook_event_name") -> "Malformed CQ hook input: hook_event_name is missing",
          having("hook_event_name", Json.fromString("PreToolUse")) -> "Unknown CQ hook event PreToolUse",
          having("hook_event_name", Json.fromString("")) -> "Unknown CQ hook event ",
          having("hook_event_name", Json.fromString(if (origin == DriverOrigin.Stop) "UserPromptSubmit" else "Stop")) -> s"The CQ $event hook received a ",
          having("padding", Json.fromString("x" * DriverHook.MaxInputBytes)) -> s"Malformed CQ hook input: more than ${DriverHook.MaxInputBytes} bytes",
        ).foreach((stdin, detail) => assert(error(event, invoke(origin, stdin)).startsWith(detail), stdin.take(80)))
        assert(error(event, hook.run("emacs", event, valid.noSpaces.getBytes(UTF_8))) == "Unknown CQ hook harness emacs")
        assert(error(event, hook.run("pi", event, valid.noSpaces.getBytes(UTF_8))) == "Unknown CQ hook harness pi")
      }
      val prompted = payload("UserPromptSubmit", "errors-ghost")
      assert(error("UserPromptSubmit", invoke(DriverOrigin.UserPromptSubmit, prompted.noSpaces)) == "Malformed CQ hook input: prompt is missing")
      assert(error("UserPromptSubmit", invoke(DriverOrigin.UserPromptSubmit, prompted.mapObject(_.add("prompt", Json.arr())).noSpaces)) == "Malformed CQ hook input: prompt is not a string")
      List("PreToolUse", "SessionStart", "Extension", "").foreach { event =>
        assert(error(event, hook.run(name, event, payload(event, a.id).noSpaces.getBytes(UTF_8))) == s"Unknown CQ hook event $event")
      }
      assert(invoke(DriverOrigin.StatusLine, "{}") == "CQ StatusLine hook error: Driver session key is missing; no default session is used\n")
      assert(invoke(DriverOrigin.StatusLine, "status") == "CQ StatusLine hook error: Malformed CQ hook input: expected one JSON object on stdin\n")
      assert(status("errors-ghost").isEmpty && (status(a.id), cursor) == (before._1, before._2))
      assert(server.driverReplies.size == before._3)
      // An unreachable or unauthorized backend is an explicit error too; the stop is allowed and an unrelated prompt needs no backend at all.
      val offline = new DriverHook(() => throw new IllegalArgumentException(HostCredential.Required), views)
      def offlineRun(origin: DriverOrigin, fields: (String, Json)*): String = offline.run(name, origin.toString, payload(origin.toString, a.id, fields*).noSpaces.getBytes(UTF_8))
      assert(error("Stop", offlineRun(DriverOrigin.Stop)) == HostCredential.Required)
      assert(offlineRun(DriverOrigin.UserPromptSubmit, "prompt" -> Json.fromString("Reply with exactly: HELLO")) == "")
      val unreachable = a.context(parse(offlineRun(DriverOrigin.UserPromptSubmit, "prompt" -> Json.fromString(words.park))).toOption.get)
      assert(unreachable.startsWith(s"CQ driver park rejected: ${HostCredential.Required}") && status(a.id) == before._1)
      assert(offline.run(name, "StatusLine", Json.obj("session_id" -> Json.fromString(a.id)).noSpaces.getBytes(UTF_8)) == s"CQ StatusLine hook error: ${HostCredential.Required}\n")
    }

    "block the stop with the issued start and resume directives byte for byte and drive consecutive cycles with one run each" in scenarios { world =>
      import world.*
      val root = goal("Goal")
      val s = session("cycles")
      s.on("G1 through=work")
      val first = s.directive(s.stop())
      val startToken = server.driverReplies.head.asInstanceOf[DriverReply.Continue].directive.token.asInstanceOf[CycleToken.Start].token
      assert(first == s"${words.advance} --roots G1 --through work --start-token ${startToken.value}")
      val run = RequestId(uuid)
      val one = s.advance(first, run) match { case DriverActivation.Started(cycle) => cycle; case other => fail(other.toString) }
      // Cycle 1 dispatches a child that is still running when the session stops again: the stop is blocked with a resume directive.
      val attempt = LineageMember.Attempt(AttemptId(uuid))
      s.attached.inherit(one, LineageMember.Run(run), attempt)
      val child = produce(s.scope, root, "Descendant created by cycle 1")
      val host = new Host(world, s.scope.actor.session)
      val unit = host.works(attempt.id)
      if (harness == Harness.Codex) {
        // Nothing wakes an idle Codex session: its stop is blocked with the order to wait inside the turn, which is no directive.
        val blocked = s.stop()
        assert(blocked.hcursor.get[String]("decision") == Right("block") && blocked.hcursor.get[String]("reason").exists(reason =>
          reason.startsWith(s"CQ driver: work of this session still runs (attempt ${attempt.id.value}). Do not end your turn") && reason.endsWith(cq.host.DispatchWaits.CodexScript)), blocked.noSpaces)
      } else {
        // The waiter of a Claude Code session starts its next turn when the child ends: with it running, the stop is allowed, no
        // directive is issued and the drive stays on, however often the session stops meanwhile.
        val waiter = host.waiter()
        try {
          val waiting = List.fill(2)(allowed(s.stop()))
          assert(waiting.forall(_.contains(s"CQ driver waiting: cycle 1 has attempt ${attempt.id.value} in flight; the session continues when it ends")))
          assert(server.driverReplies.head.isInstanceOf[DriverReply.Waiting])
        } finally waiter.close()
      }
      assert(status(s.id).exists(value => value.state == DriverState.On && value.directives == 1))
      assert(s.line == "CQ driver on: G1 through work; 1 active child\n")
      assert(status(s.id).exists(_.cycle.exists(cycle => cycle.id == one && cycle.run.contains(run) && cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1)))
      s.attached.settle(one, attempt)
      host.finishes(unit)
      // Cycle 2: a new start directive; the descendant cycle 1 created is advanceable now and the change is posted to the transcript.
      val second = s.directive(s.stop())
      val issued = server.driverReplies.head.asInstanceOf[DriverReply.Continue]
      assert(second.startsWith(s"${words.advance} --roots G1 --through work --start-token ") && second != first && issued.directive.cycle != one)
      assert(issued.messages == List("CQ driver: the advanceable set changed to 2 items; added T1") && issued.status.cycle.exists(_.number == 2))
      val two = s.advance(second, RequestId(uuid)) match { case DriverActivation.Started(cycle) => cycle; case other => fail(other.toString) }
      assert(two == issued.directive.cycle && change(s.scope, retitle(child, "Advanced in cycle 2")).items.map(_.id) == List(child))
      val third = s.directive(s.stop())
      assert(s.advance(third, RequestId(uuid)).isInstanceOf[DriverActivation.Started] && status(s.id).exists(_.directives == 3))
      // Nothing changed in cycle 3: the stop is allowed and its reason is posted.
      val quiet = allowed(s.stop())
      assert(quiet.exists(_.startsWith("CQ driver stopped (quiescent): The previous cycle changed nothing")))
      assert(s.line.startsWith("CQ driver off: G1 through work; stopped (quiescent): ") && allowed(s.stop()).isEmpty)
      assert(change(s.scope, retitle(root, "Driver off: the session writes as before")).items.map(_.id) == List(root))
    }

    "allow the stop with a failure reason and admit no out-of-set write when a session leaves the directive" in scenarios { world =>
      import world.*
      val root = goal("Goal")
      val stranger = task("Out-of-set item")
      def driven(name: String): (Session, String) = {
        val s = session(name)
        s.on("G1 through=work")
        (s, s.directive(s.stop()))
      }
      // The session ignores the directive and stops again.
      val (ignoring, _) = driven("ignores-directive")
      val unstarted = cursor
      ignoring.failure(ignoring.stop(), "directive not started: the start directive of cycle 1 was not submitted")
      assert(cursor == unstarted)
      // The session runs advance without the directive's token.
      val (untracked, directive) = driven("untracked-advance")
      val (workflow, _) = untracked.activation(directive)
      assert(untracked.refused(untracked.attached.activate(RequestId(uuid), workflow, None)).isInstanceOf[Fault.Denied])
      assert(status(untracked.id).exists(_.cycle.exists(_.run.isEmpty)) && cursor == unstarted)
      untracked.failure(untracked.stop(), "")
      // The session makes a direct CQ change without a cycle ID against an out-of-set item, during a cycle and between cycles.
      def direct(s: Session): Unit = {
        val before = cursor
        val result = s.api.call(Command.Change(ChangeInput(project, retitle(stranger, "Out-of-set write"))))
        assert(result.isInstanceOf[Result.Failed] && result.asInstanceOf[Result.Failed].fault.isInstanceOf[Fault.Denied])
        assert(cursor == before && title(stranger) == "Out-of-set item")
      }
      val (writing, started) = driven("direct-write")
      assert(writing.advance(started, RequestId(uuid)).isInstanceOf[DriverActivation.Started])
      direct(writing)
      writing.failure(writing.stop(), "out-of-set change: T1 is outside the advanceable set stored for cycle 1")
      val between = session("direct-write-between-cycles")
      between.on("G1 through=work")
      direct(between)
      between.failure(between.stop(), "untracked mutation")
      assert(title(root) == "Goal" && change(writing.scope, retitle(stranger, "Driver off: written as before")).items.map(_.id) == List(stranger))
    }
  }
}

final class DriverHookDummy extends DriverHookTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class DriverHookPostgres extends DriverHookTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
