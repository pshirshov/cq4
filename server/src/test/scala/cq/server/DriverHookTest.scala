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
import java.nio.file.Path
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
    val hook = new DriverHook(() => new DriverEntry(server, project))
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
    val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization)
    ZIO.runtime[Any].flatMap(runtime => ZIO.foreachDiscard(Harnesses)(harness =>
      ZIO.attemptBlocking(body(new World(harness, ledger, application, authorization, root, runtime, clock)))))
  }

  "The shared CQ hook driver of Claude Code and Codex (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
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
      val unanswered = new DriverHook(() => new DriverEntry(lossy, project))
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
      val offline = new DriverHook(() => throw new IllegalArgumentException(HostCredential.Required))
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
      val resumed = s.directive(s.stop())
      val resumeToken = server.driverReplies.head.asInstanceOf[DriverReply.Continue].directive.token.asInstanceOf[CycleToken.Resume].token
      assert(resumed == s"${words.advance} --roots G1 --through work --resume-token ${resumeToken.value}" && resumeToken != startToken)
      assert(s.line == "CQ driver on: G1 through work; 1 active child\n")
      assert(s.advance(resumed, RequestId(uuid)) == DriverActivation.Resumed(one, run))
      assert(status(s.id).exists(_.cycle.exists(cycle => cycle.id == one && cycle.run.contains(run) && cycle.lineage.count(_.member.isInstanceOf[LineageMember.Run]) == 1)))
      s.attached.settle(one, attempt)
      // Cycle 2: a new start directive; the descendant cycle 1 created is advanceable now and the change is posted to the transcript.
      val second = s.directive(s.stop())
      val issued = server.driverReplies.head.asInstanceOf[DriverReply.Continue]
      assert(second.startsWith(s"${words.advance} --roots G1 --through work --start-token ") && second != first && issued.directive.cycle != one)
      assert(issued.messages == List("CQ driver: the advanceable set changed to 2 items; added T1") && issued.status.cycle.exists(_.number == 2))
      val two = s.advance(second, RequestId(uuid)) match { case DriverActivation.Started(cycle) => cycle; case other => fail(other.toString) }
      assert(two == issued.directive.cycle && change(s.scope, retitle(child, "Advanced in cycle 2")).items.map(_.id) == List(child))
      val third = s.directive(s.stop())
      assert(s.advance(third, RequestId(uuid)).isInstanceOf[DriverActivation.Started] && status(s.id).exists(_.directives == 4))
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
