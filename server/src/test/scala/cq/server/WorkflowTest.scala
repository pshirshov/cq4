package cq.server

import cq.api.*
import cq.host.{ProcessModes, WorkflowAssets}
import java.nio.file.Files
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class WorkflowLocal extends AnyWordSpec {
  "Workflow arguments (Behavioral Active Blackbox Group)" should {
    "keep explicit roots and phase limits and reject ambiguous or cross-command options" in {
      val project = ProjectId(UUID.randomUUID())
      assert(WorkflowArguments.parse(project, Map.empty).isEmpty)
      assert(WorkflowArguments.parse(project, Map("--workflow" -> "begin")).contains(WorkflowRequest.Begin(Set.empty)))
      val roots = Set(ItemId(project, Ledger.Ideas, 1), ItemId(project, Ledger.Goals, 2))
      assert(WorkflowArguments.parse(project, Map("--workflow" -> "advance", "--roots" -> "I1,G2", "--through" -> "plan"))
        .contains(WorkflowRequest.Advance(roots, WorkflowPhase.Plan)))
      List(
        Map("--roots" -> "I1"),
        Map("--workflow" -> "advance", "--roots" -> "I1"),
        Map("--workflow" -> "begin", "--through" -> "work"),
        Map("--workflow" -> "begin", "--roots" -> "I1,I1"),
        Map("--workflow" -> "begin", "--roots" -> "I01"),
        Map("--workflow" -> "begin", "--roots" -> "I0"),
        Map("--workflow" -> "begin", "--roots" -> ""),
        Map("--workflow" -> "begin", "--roots" -> (1 to 65).map(i => s"I$i").mkString(",")),
        Map("--workflow" -> "upstream", "--roots" -> "U1", "--action" -> "approve"),
        Map("--workflow" -> "unknown"),
      ).foreach(value => intercept[IllegalArgumentException](WorkflowArguments.parse(project, value)))
      val result = ArtifactId(UUID.randomUUID())
      ReviewerMode.all.foreach { mode =>
        assert(WorkflowArguments.parse(project, Map("--workflow" -> "review", "--result" -> result.value.toString, "--mode" -> mode.toString.toLowerCase))
          .contains(WorkflowRequest.Review(result, mode)))
      }
    }
  }

  private val Wait = "/opt/cq/bin/cq wait"
  /** The governing instructions of an attached session of every harness; only the Pi extension's host names no wait command. */
  private def attached(schemas: McpSchemas): List[String] = schemas.attachedInstructions(Harness.Pi, None) :: Harness.all.toList.map(schemas.attachedInstructions(_, Some(Wait)))

  "Waiting for the host's work in the governing instructions (Behavioral Active Blackbox Atomic)" should {
    val schemas = new McpSchemas()
    val work = "Waiting for work the host carries out (a child, an integration being prepared or applied, a combination, a revalidation): "
    "tell a Claude Code session to await a child with one fixed background command and anything quicker with one status call inside its turn" in {
      val text = schemas.attachedInstructions(Harness.Claude, Some(Wait))
      assert(text.contains(work + "a child is awaited in the background, anything else inside your turn. Do not call a status to wait for a child: after starting one, " +
        "run exactly this command with the Bash tool as a background command (run_in_background true, timeout 7200000): `" + Wait + "`. Then continue with other ready work or end your turn."))
      assert(text.contains("0: a unit ended, or nothing was active") && text.contains("3: the CQ host is not running") &&
        text.contains("4 or 5: the command found no single session of this checkout, and its output says why. Report 3, 4 and 5 to the user.") &&
        text.contains("Any other exit, including the harness ending the command at its lifetime limit: run it again while work is active.") &&
        text.contains("After exit 0, read the outcome of each ended unit with one Status, IntegrationStatus or CombinationStatus call with waitMillis 0, and run the command again while other work is active."))
      // What ends within seconds would end before the session's turn does, and a stop that finds nothing running costs a resume directive.
      assert(text.contains("An integration being prepared or applied, a combination and a revalidation usually end within seconds: after starting one, start no command for it and do not end your turn. " +
        "Call its status once (IntegrationStatus or CombinationStatus; repeat Revalidate) with waitMillis 120000, which returns when the work ends. " +
        "Only if that call returns while the work continues, run the command above."))
    }
    "tell a Codex session to wait with status calls of the longest wait inside its turn, because nothing wakes it, and with no shell command" in {
      val text = schemas.attachedInstructions(Harness.Codex, Some(Wait))
      assert(text.contains(work + "do not end your turn while such work is active: nothing wakes you when it ends. " +
        "After starting such work, call its status (Status, IntegrationStatus or CombinationStatus; repeat Revalidate) with waitMillis 120000. " +
        "The call returns when the work ends or the wait has passed; call it again while the work continues. Run no shell command to wait. " +
        // Codex hands a script back unfinished after 30 s by default, and the model would then ask again every 10 s.
        "A script of the exec tool is handed back unfinished after 30 s unless it asks for longer: begin a script that makes this call with the line " +
        "`// @exec: {\"yield_time_ms\": 150000}`, and continue a script that is handed back as still running with the wait tool and yield_time_ms 150000."))
      assert(!text.contains(Wait) && !text.contains("exec_command") && !text.contains("write_stdin") && !text.contains("run_in_background") && !text.contains("do not call a status to wait"))
    }
    "tell a Pi session that CQ sends it a message and that it starts no waiter" in {
      List(Some(Wait), None).map(schemas.attachedInstructions(Harness.Pi, _)).foreach { text =>
        assert(text.contains(work + "do not call a status to wait and start no waiter yourself. After starting such work, continue with other ready work or end your turn: " +
          "CQ sends you a message that begins `CQ:` when a unit ends, naming it, its items, its phase and the next step."))
        assert(!text.contains(Wait) && !text.contains("run_in_background") && !text.contains("exec_command"))
      }
    }
    "tell a batch Governor, which has no shell and is told nothing, to wait through its status calls, and no attached session" in {
      val byStatus = work + "call the status of that work (Status, IntegrationStatus or CombinationStatus; repeat Revalidate) with waitMillis 120000. " +
        "The call returns when the work ends or the wait has passed; call it again while the work continues."
      val batch = SupervisorProgram.Instructions
      assert(batch.contains(byStatus) && !batch.contains("cq wait") && !batch.contains("run_in_background") && !batch.contains("exec_command"))
      attached(schemas).foreach(text => assert(!text.contains("waitMillis 20000")))
      // A Claude Code or Codex host is not started without its wait command, so no instructions exist for one.
      List(Harness.Claude, Harness.Codex).foreach(harness => intercept[IllegalStateException](schemas.attachedInstructions(harness, None)))
    }
    "give every governing session exactly one way to wait and never tell it to poll" in {
      (SupervisorProgram.Instructions :: attached(schemas)).foreach { text =>
        assert(text.sliding(work.length).count(_ == work) == 1 && !text.toLowerCase.contains("poll"), text.take(200))
        assert(text.contains("Status reads the current state or the result of an attempt"))
      }
      assert(!SupervisorProgram.Guidance.contains("waitMillis") && !SupervisorProgram.Guidance.contains(work))
      val dispatch = schemas.attachedTools.find(_.hcursor.get[String]("name") == Right("dispatch")).get.hcursor.get[String]("description").fold(throw _, identity)
      assert(dispatch.contains("StartChoice returns at once. Status reads the current state or the result of an attempt, after waiting up to waitMillis (at most 120000) for the attempt to end: " +
        "the governing instructions of session Context say how this session waits for work") &&
        !dispatch.toLowerCase.contains("poll"), dispatch)
      (List("workflows/common.md", "workflows/advance.md", "workflows/begin.md", "workflows/entrypoint.md") ++ ProcessModes.all.map(_.instructions.stripPrefix("cq/"))).foreach { name =>
        val text = new String(getClass.getResourceAsStream("/cq/" + name).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        assert(!text.toLowerCase.contains("poll"), name)
      }
      // The section of a process mode says nothing about waiting, so the one way to wait holds in every mode.
      ProcessModes.all.foreach { mode =>
        val text = new WorkflowAssets().resource(mode.instructions)
        assert(!text.contains("waitMillis") && !text.contains("Waiting for") && !text.contains("cq wait"), mode.label)
      }
    }
  }

  "The models of a child (Behavioral Active Blackbox Atomic)" should {
    "I17: take the choice and the fence in StartChoice, and the work without a harness in Start" in {
      val context = baboon.runtime.shared.BaboonCodecContext.Default
      def decoded(text: String): Either[Throwable, DispatchCommand] = {
        val json = io.circe.parser.parse(text).fold(throw _, identity)
        DispatchCommand_JsonCodec.decode(context, json).filterOrElse(command => cq.core.JsonRoundtrip.lossless(json, DispatchCommand_JsonCodec.encode(context, command)),
          new IllegalArgumentException("undeclared or noncanonical fields"))
      }
      val (choice, claim) = (UUID.randomUUID(), UUID.randomUUID())
      val fence = s""""fence":{"claim":{"value":"$claim"},"generation":"1"}"""
      assert(decoded(s"""{"StartChoice":{"choice":{"value":"$choice"},$fence}}""") ==
        Right(DispatchCommand.StartChoice(RequestId(choice), Fence(ClaimId(claim), 1))))
      // The session no longer names a harness: a command that does is not this contract's.
      assert(decoded(s"""{"StartChoice":{"choice":{"value":"$choice"},"harness":"Codex",$fence}}""").isLeft)
      val limits = """"limits":{"startupMillis":"3000","heartbeatMillis":"1000","graceMillis":"300","killMillis":"2000","retainedOutputBytes":262144}"""
      val work = s""""request":{"value":"$choice"},"work":{"Planner":{}},"members":[],"guidance":[],"artifacts":[],"previous":null,$fence,$limits"""
      assert(decoded(s"""{"Start":{"work":{$work}}}""").exists(_.isInstanceOf[DispatchCommand.Start]))
      assert(decoded(s"""{"Start":{"work":{"harness":"Codex",$work}}}""").isLeft && decoded(s"""{"Start":{"request":{"harness":"Codex",$work}}}""").isLeft)
      def schemas(root: io.circe.Json, name: String): io.circe.Json = root.hcursor.downField("$defs").downField(name).focus.getOrElse(root)
      val schema = schemas(new McpSchemas().schema("DispatchCommand"), "cq_api_DispatchCommand_StartChoice")
      assert(schema.hcursor.downField("properties").keys.map(_.toSet).contains(Set("choice", "fence")), schema.noSpaces)
      val contexts = List("cq_api_AttachedContext", "cq_api_GoverningInput").map(name => name -> new McpSchemas().schema(name.stripPrefix("cq_api_")))
      contexts.foreach((name, value) => assert(!schemas(value, name).hcursor.downField("properties").keys.exists(_.toSet("routes")) && !value.noSpaces.contains("HarnessRoute"), name))
    }
  }

  "Operator decisions in the governing workflows (Behavioral Active Blackbox Atomic)" should {
    "tell the session to record a Question before it stops, not to ask for a go-ahead it has, and to store a chat answer (D147)" in {
      val assets = new WorkflowAssets
      // The rules hold in every process mode.
      ProcessMode.all.foreach { mode =>
      val begin = assets.instructions(WorkflowRequest.Begin(Set.empty), mode)
      val advance = assets.instructions(WorkflowRequest.Advance(Set.empty, WorkflowPhase.Work), mode)
      // The shared rules reach every workflow; begin and advance repeat the part that applies at their own stop.
      List(begin, advance).foreach { text =>
        assert(text.contains("record it before you stop") && text.contains("Name that Question's ID in your final message"))
        assert(text.contains("never end a turn on a question that exists only in prose"))
        assert(text.contains("stops a drive with user input required"))
        assert(text.contains("do not ask whether to do what it already tells you to do"))
        assert(text.contains("store the answer on the Question") && text.contains("while a driver is on that write is refused until the operator parks it"))
      }
      assert(begin.contains("when it says to capture and plan, capture and plan without asking whether to proceed"))
      assert(begin.contains("the IDs of the Open Questions that hold the outstanding user choices") && begin.contains("never a prose question at the end of your message"))
      assert(advance.contains("as Questions recorded before you stop, never as prose alone"))
      assert(advance.contains("Do not ask for a go-ahead that the invocation already gives"))
      }
    }

    "give both rules to a governing session that has no workflow text: a run without a workflow and an attached host before activation (D147)" in {
      val schemas = new McpSchemas()
      (SupervisorProgram.Instructions :: attached(schemas)).foreach { text =>
        assert(text.contains("record it as a Question, with the items it gates BlockedBy it, before you stop; never ask it in prose alone"))
        assert(text.contains("The request is the go-ahead for what it asks: do not ask whether to do it."))
      }
    }
  }

  "Gates held by Questions in the governing and child instructions (Behavioral Active Blackbox Atomic)" should {
    def resource(path: String): String = new String(getClass.getResourceAsStream("/cq/" + path).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val instructions = new cq.host.ChildInstructions

    "tell the Governor to read the answer of a Question before dispatching the work it gated, in the shared rules, advance and the base instructions (D147)" in {
      val answer = "Before you dispatch work that a Question gated, read its answer: an Answered Question releases the work only as far as the answer allows. " +
        "When the answer refuses the work, do not dispatch it: cancel it or leave it blocked, and tell the operator. " +
        "Carry a condition the answer sets into the requirements of the work, or ask it in a follow-up Question. " +
        "A Withdrawn Question never releases the work: Produce a new Question and link the gated items BlockedBy it, or remove the link and record the reason."
      val schemas = new McpSchemas()
      (List(resource("workflows/common.md"), resource("workflows/advance.md"), SupervisorProgram.Guidance) ++ attached(schemas))
        .foreach(text => assert(text.contains(answer)))
      ProcessMode.all.foreach { mode =>
        List(WorkflowRequest.Begin(Set.empty), WorkflowRequest.Advance(Set.empty, WorkflowPhase.Work)).map(new WorkflowAssets().instructions(_, mode))
          .foreach(text => assert(text.contains(answer), mode))
      }
      assert(!resource("workflows/advance.md").contains("Do not ask for a go-ahead that the invocation or an Answered Question already gives"))
      assert(resource("workflows/common.md").contains("While a driver is on, the removal of a link to a Question outside the drive is refused as an out-of-set change"))
    }

    "extend the gate of a producer to the items produced from it, in the Planner, the Plan reviewer and the shared rules (D146)" in {
      assert(instructions(DispatchWork.Planner()).contains("When an assigned producer is BlockedBy an Open Question, every item you produce from it is gated by that Question as well: " +
        "name each of them in the member summary as gated by it"))
      assert(instructions(DispatchWork.Reviewer(ReviewerMode.Plan)).contains(
        "when items are produced from a producer that is BlockedBy an Open Question and the member summary does not name them as gated by that Question"))
      val common = resource("workflows/common.md")
      assert(common.contains("When you apply a proposal that produces items from a producer that is BlockedBy an Open Question, link every produced item BlockedBy the same Question"))
      assert(common.contains("The compact outcome of a Planner does not carry its member summaries"))
      ProcessMode.all.foreach { mode =>
        val text = new WorkflowAssets().instructions(WorkflowRequest.Advance(Set.empty, WorkflowPhase.Work), mode)
        assert(text.contains("When you apply a proposal that produces items from a producer that is BlockedBy an Open Question, link every produced item BlockedBy the same Question") &&
          text.contains("The compact outcome of a Planner does not carry its member summaries"), mode)
      }
    }

    "ask the Plan reviewer for a Question only where the Planner produces one (D146)" in {
      val needed = "when it needs the operator's decision or approval"
      val plan = instructions(DispatchWork.Reviewer(ReviewerMode.Plan))
      assert(instructions(DispatchWork.Planner()).contains(needed + ", produce one Question that asks for it"))
      assert(plan.contains(needed + ", check that a Question asking for it is proposed or already exists") &&
        plan.contains("a requirement that only fixes an order or forbids starting something needs no Question"))
    }

    "tell a Worker that the workflow which dispatched it meets a requirement about the process (D146)" in {
      val result = "about the result (what the change must do or how it must be verified)"
      val process = "about the process (when to ask the operator, what to wait for, who approves, in what order, what not to start yet)"
      List(DispatchWork.Worker(WorkerMode.Implement), DispatchWork.Worker(WorkerMode.ResolveConflict)).map(instructions(_)).foreach { text =>
        assert(text.contains(result) && text.contains(process))
        assert(text.contains("is met by the workflow that dispatched you: it is no reason to block, fail or abstain"))
      }
    }
  }

  "Process modes in the governing workflows (Behavioral Active Blackbox Atomic)" should {
    val assets = new WorkflowAssets
    def resource(name: String): String = assets.resource(s"cq/workflows/$name.md")
    def section(mode: ProcessMode): String = assets.resource(ProcessModes.of(mode).instructions)
    val begin = WorkflowRequest.Begin(Set.empty)
    val advance = WorkflowRequest.Advance(Set.empty, WorkflowPhase.Integrate)
    // The rules of the prescribed order, which the shared texts carried before the modes existed, by the workflow that stated each.
    val beginOrder = List(
      "have Planner propose goals with distinct acceptance criteria",
      "Obtain independent Plan review before choosing to apply the planner's result handle.",
      "The Planner proposal that produces Tasks assigns each to an Open milestone or to a Milestone it creates, under the same Plan review")
    val advanceOrder = List(
      "Earlier phases may be necessary",
      "Do not silently bypass an earlier unresolved requirement to reach the phase limit.")
    val sharedOrder = List(
      "When no Open milestone fits, select a Planner with those roots, obtain independent Plan review of the Milestone it proposes and apply it by handle.",
      "Never create a milestone yourself.",
      "A reviewed Planner proposal assigns the Tasks it produces")

    "keep every rule of the prescribed order in the Rigorous instructions and state none of them in the texts every mode shares" in {
      val rigorous = Map(begin -> assets.instructions(begin, ProcessMode.Rigorous), advance -> assets.instructions(advance, ProcessMode.Rigorous))
      (beginOrder ++ sharedOrder).foreach(rule => assert(rigorous(begin).contains(rule), rule))
      (advanceOrder ++ sharedOrder).foreach(rule => assert(rigorous(advance).contains(rule), rule))
      // The remainder of each shared text is delivered whole, whatever the mode.
      List("common", "begin", "advance").foreach { name =>
        (beginOrder ++ advanceOrder ++ sharedOrder).foreach(rule => assert(!resource(name).toLowerCase.contains(rule.toLowerCase), s"$name: $rule"))
        assert(!resource(name).contains("create a milestone yourself"), name)
      }
      ProcessMode.all.foreach { mode =>
        assert(assets.instructions(begin, mode).endsWith("\n" + resource("common") + "\n" + resource("begin")), mode)
        assert(assets.instructions(advance, mode).endsWith("\n" + resource("common") + "\n" + resource("advance")), mode)
        assert(assets.instructions(begin, mode).startsWith(section(mode)) && section(mode).startsWith(s"Process mode of this project: ${ProcessModes.of(mode).label}."), mode)
      }
    }

    "lift the Planner, the Plan review and the phase order in the relaxed modes and nothing else" in {
      List(ProcessMode.CrossCutting, ProcessMode.Yolo).foreach { mode =>
        val text = section(mode)
        (beginOrder ++ advanceOrder ++ sharedOrder).foreach(rule => assert(!assets.instructions(advance, mode).contains(rule) && !assets.instructions(begin, mode).contains(rule), s"$mode: $rule"))
        assert(text.contains("that step is optional: you may plan yourself and write the records with change"), mode)
        assert(text.contains("You may take the selected items in any order and move between the phases in any order up to the phase limit"), mode)
        // The request keeps its scope (Q59).
        assert(text.contains("It does not widen the request: the roots and the phase limit bound the work as in every mode, and begin captures and plans without starting implementation."), mode)
        // The Governor writes the item and its criteria before the work, by a Produce a drive admits.
        assert(text.contains("Every piece of work has its own Task with acceptance criteria, under an Open milestone"), mode)
        assert(text.contains("change with Produce from the in-scope item the work derives from") && text.contains("""milestone set to {"Existing":{"id":ItemId}} for an Open milestone whose objective covers the Task"""), mode)
        assert(text.contains("""When no Open milestone fits, Create a Milestone draft earlier in the same request and set milestone to {"Created":{"mutation":its zero-based index}}"""), mode)
        assert(text.contains("Never Create a Task without a producer"), mode)
        assert(text.contains("To work a Task without that assessment, Select it as its own root."), mode)
        // What no mode relaxes.
        assert(text.contains("A recorded gate is never left out") && text.contains("Memories are not relaxed"), mode)
        assert(text.contains("a Task becomes Done only by recorded integration") && text.contains("Keep the ledger current as you go"), mode)
      }
    }

    "keep the isolated Worker and the independent Candidate review mandatory in Cross-cutting, and relax them only in YOLO" in {
      val crossCutting = section(ProcessMode.CrossCutting)
      assert(crossCutting.contains("Every change is made by a Worker in its isolated workspace and captured by the host: never edit, build or test yourself."))
      assert(crossCutting.contains("Every candidate is checked by the host, reviewed by an independent Reviewer Candidate and integrated by the host"))
      assert(section(ProcessMode.Rigorous).contains("Every change is made by a Worker in its isolated workspace, checked by the host, reviewed by an independent Reviewer Candidate and integrated by the host."))
      val yolo = section(ProcessMode.Yolo)
      val direct = "you may implement a Task yourself in an isolated workspace that the host gives you for it"
      val selfReview = "you may instead review the candidate yourself, whether a Worker made it or you did"
      assert(yolo.contains(direct) && yolo.contains(selfReview))
      assert(yolo.contains("the ledger then records the integration as self-reviewed by the governing session"))
      assert(yolo.contains("The configured host checks run on every candidate and are never waived by a verdict, yours included. Only the host integrates"))
      assert(yolo.contains("A self-reviewed candidate is integrated only when the project configures at least one check, unless the operator has exempted the project from that rule."))
      List(ProcessMode.Rigorous, ProcessMode.CrossCutting).map(section).foreach { text =>
        assert(!text.contains("yourself in an isolated workspace") && !text.contains("review the candidate yourself") && !text.contains("self-review"))
      }
      // The rule the shared text states for every mode: the operator's checkout is never edited, in YOLO either.
      ProcessMode.all.foreach(mode => assert(assets.instructions(advance, mode).contains("The operator's checkout is the integration target of your workers. Never edit, build, test or run checks there")))
      assert(yolo.contains("they still hold for the operator's checkout and for every place other than that workspace"))
    }

    "name no Planner in the refusal of a Task without a milestone, which every mode meets" in {
      val project = ProjectId(UUID.randomUUID())
      val task = Item(ItemId(project, Ledger.Tasks, 7), Revision(1), ItemDraft("Task", "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil), 1L, 1L,
        Provenance(Actor("governor", SessionId(UUID.randomUUID()), Role.Governor), 1L, RequestId(UUID.randomUUID())))
      val refusal = cq.core.MilestonePolicy.refusal(DispatchWork.Worker(WorkerMode.Implement), ItemView(task, Nil), _ => fail("No milestone is read"))
      assert(refusal.contains(cq.core.MilestoneRefusal(CohortReason.NoMilestone, "Work refused: T7 has no milestone. Assign each Task to an Open milestone before work starts")))
    }
  }

  "Driver token handling at activation (Behavioral Active Blackbox Atomic)" should {
    "keep the driver token out of the operator requirements delivered to children" in {
      val start = DriverToken(UUID.randomUUID())
      val directive = s"/cq:advance --roots G1 --through work --start-token ${start.value}"
      // A driven activation whose requirements text carries its own token is refused by name before any run starts.
      List[CycleToken](CycleToken.Start(start), CycleToken.Resume(start)).foreach { token =>
        List(directive, directive.toUpperCase, s"Advance G1.\nToken ${start.value}.").foreach { text =>
          val refused = intercept[cq.core.DomainFailure](cq.host.OperatorRequirements.admitted(text, Some(token)))
          assert(refused.fault == Fault.Invalid(cq.host.OperatorRequirements.TokenLeak))
        }
        assert(cq.host.OperatorRequirements.admitted("/cq:advance --roots G1 --through work", Some(token)) == "/cq:advance --roots G1 --through work")
      }
      assert(cq.host.OperatorRequirements.TokenLeak.contains("Workflow.token") && cq.host.OperatorRequirements.TokenLeak.contains("operatorRequirements"))
      assert(cq.host.OperatorRequirements.admitted(directive, None) == directive)
      assert(cq.host.OperatorRequirements.admitted(directive, Some(CycleToken.Start(DriverToken(UUID.randomUUID())))) == directive)
      // The generated advance command tells the model the same rule.
      Harness.all.foreach { harness =>
        val advance = new WorkflowAssets().commands(harness).find(_.path.toString.contains("advance")).get.body
        assert(advance.contains("Pass the token only in `token`") && advance.contains("leave the `--start-token` or `--resume-token` flag and its UUID out of `operatorRequirements`"))
        // D110: a directive carries only workflow flags, which are not requirements.
        assert(advance.contains("when the invocation consists only of workflow flags, as a CQ driver directive does, pass `operatorRequirements` as an empty string"))
      }
    }
  }

  "Command export (Behavioral Active Effectual filesystem Good Communication)" should {
    "export all four commands idempotently, preserve unrelated files and preflight every conflict" in {
      val assets = new WorkflowAssets
      Harness.all.foreach { harness =>
        val root = Files.createTempDirectory("cq-command-export-").toAbsolutePath
        val unrelated = root.resolve("notes.txt")
        Files.writeString(unrelated, "consumer notes")
        val commands = assets.commands(harness)
        assert(commands.size == 4 && commands.map(_.path).distinct.size == 4)
        val last = root.resolve(commands.last.path)
        Files.createDirectories(last.getParent)
        Files.writeString(last, "edited by user")
        intercept[IllegalArgumentException](assets.writeCommands(harness, root, false))
        assert(!Files.exists(root.resolve(commands.head.path)) && Files.readString(last) == "edited by user")
        val paths = assets.writeCommands(harness, root, true)
        assert(assets.writeCommands(harness, root, false) == paths)
        assert(paths.zip(commands).forall((path, asset) => Files.readString(path) == asset.body))
        assert(Files.readString(unrelated) == "consumer notes")
      }
    }

    "refuse symbolic destinations and parents even when replacement is explicit" in {
      val assets = new WorkflowAssets
      val root = Files.createTempDirectory("cq-command-links-").toAbsolutePath
      val external = Files.createTempDirectory("cq-command-external-").toAbsolutePath
      Files.createSymbolicLink(root.resolve(".pi"), external)
      intercept[IllegalArgumentException](assets.writeCommands(Harness.Pi, root, true))
      assert(!Files.exists(external.resolve("prompts")))
      val target = root.resolve(assets.commands(Harness.Claude).head.path)
      Files.createDirectories(target.getParent)
      val existing = external.resolve("user-file")
      Files.writeString(existing, "preserve")
      Files.createSymbolicLink(target, existing)
      intercept[IllegalArgumentException](assets.writeCommands(Harness.Claude, root, true))
      assert(Files.readString(existing) == "preserve")
    }
  }
}
