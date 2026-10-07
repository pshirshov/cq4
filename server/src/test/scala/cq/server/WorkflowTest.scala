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
    val work = "Waiting for work the host carries out (a child, an integration being prepared or applied, a combination, a revalidation, a submitted workspace): "
    "tell a Claude Code session to await a child with one fixed background command and anything quicker with one status call inside its turn" in {
      val text = schemas.attachedInstructions(Harness.Claude, Some(Wait))
      assert(text.contains(work + "a child is awaited in the background, anything else inside your turn. Do not call a status to wait for a child: after starting one, " +
        "run exactly this command with the Bash tool as a background command (run_in_background true, timeout 7200000): `" + Wait + "`. Then continue with other ready work or end your turn."))
      assert(text.contains("0: a unit ended, or nothing was active") && text.contains("3: the CQ host is not running") &&
        text.contains("4 or 5: the command found no single session of this checkout, and its output says why. Report 3, 4 and 5 to the user.") &&
        text.contains("Any other exit, including the harness ending the command at its lifetime limit: run it again while work is active.") &&
        text.contains("After exit 0, read the outcome of each ended unit with one Status, IntegrationStatus or CombinationStatus call with waitMillis 0, and run the command again while other work is active."))
      // What ends within seconds would end before the session's turn does, and a stop that finds nothing running costs a resume directive.
      // I30: a workspace the session submitted is captured and checked by the host, which is waited for in the same way and by no third one.
      assert(text.contains("An integration being prepared or applied, a combination and a revalidation usually end within seconds, " +
        "and the host checks a workspace you submitted as it runs a revalidation: after starting one, start no command for it and do not end your turn. " +
        "Call its status once (IntegrationStatus or CombinationStatus; repeat Revalidate; Status for a submitted workspace) with waitMillis 120000, which returns when the work ends. " +
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
    "I17: tell every governing session that the host starts the models the configuration assigns, name no harness to choose, and say what Abstained, Arbitrate and Seats mean" in {
      val schemas = new McpSchemas()
      (SupervisorProgram.Instructions :: attached(schemas)).foreach { text =>
        assert(text.contains("Claim all members of one returned choice, then StartChoice with its ID and current fence; " +
          "the host starts the models the project's agent configuration assigns to the role."))
        assert(text.contains("One StartChoice is one unit of work.") && text.contains("Its reply names the unit by one attempt ID, which Status, Cancel and Seats take; Cancel stops the whole unit."))
        assert(text.contains("Seats lists every model the host tried and how each seat ended, with the result handle of each seat that delivered."))
        assert(text.contains("Phase Abstained means that no assigned model could run the work") &&
          text.contains("do not select it again at once, continue other work and report it."))
        assert(text.contains("Next Arbitrate means that the reviewers of one unit disagree; the status carries the dissenting review. Read Seats. " +
          "By default correct: Select Worker Implement with the dissenting review as previous and the other non-accepting reviews as artifacts. " +
          "You decide: you may instead integrate with the review of an accepting seat when the dissent is unfounded, and then say so in your report."))
        // I30: who reviews a candidate is the mode's to say; without workflow instructions it is the independent Reviewer.
        assert(text.contains("Have every worker candidate reviewed as the workflow instructions say; without such instructions, pass it to Reviewer Candidate. ") &&
          !text.contains("Pass worker candidates to Reviewer Candidate"))
        assert(!text.contains("configured harness") && !text.contains("harness and fence"), text.take(200))
      }
      assert(!SupervisorProgram.Guidance.contains("routes"))
      val tools = schemas.attachedTools.map(tool => tool.hcursor.get[String]("name").fold(throw _, identity) -> tool.hcursor.get[String]("description").fold(throw _, identity)).toMap
      val managed = new LocalControl(null, null, null, null, null, null, schemas, null, null)
        .advertised(LocalCapability(AttemptId(UUID.randomUUID()), Role.Governor)).hcursor.get[String]("description").fold(throw _, identity)
      List(tools("dispatch"), managed).foreach { description =>
        assert(description.contains("then StartChoice by ID and fence: the host starts the models the project's agent configuration assigns to the role, as one unit named by one attempt ID."))
        assert(description.contains("Cancel stops the whole unit of an attempt") && description.contains("Seats lists the models the host tried"), description)
        assert(!description.contains("harness and fence"), description)
      }
      assert(tools("dispatch").contains(s"Up to ${DispatchController.MaxActiveChildren} child attempts may run at once, and the members of running units are disjoint."))
      assert(tools("session").contains("First call Context for project, limits, governing instructions and complete argument guide.") && !tools("session").contains("routes"))
      // The workflow texts of every mode name no harness to choose either.
      val assets = new WorkflowAssets
      ProcessMode.all.foreach { mode =>
        List(assets.instructions(WorkflowRequest.Begin(Set.empty), mode), assets.instructions(WorkflowRequest.Advance(Set.empty, WorkflowPhase.Integrate), mode)).foreach { text =>
          assert(text.contains("then StartChoice with its ID and current fence; the host starts the models the project's agent configuration assigns to the role."))
          assert(text.contains("A child whose phase is Abstained ran on no model: no assigned model could run the work. Its input is not used up, " +
            "but do not select it again at once: continue other work and report it with its blocker."))
          assert(text.contains("When a review ends with next Arbitrate, its reviewers disagree: read Seats, then by default Select Worker Implement with the dissenting review, " +
            "which the status carries, as previous and the other non-accepting reviews as artifacts."))
          assert(!text.contains("configured harness") && !text.contains("routes"), mode.toString)
        }
      }
      val entrypoint = new String(getClass.getResourceAsStream("/cq/workflows/entrypoint.md").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assert(entrypoint.contains("Read its governing instructions, complete argument guide and project identity.") && !entrypoint.contains("routes"))
    }
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
      ProcessMode.all.foreach(mode => assert(assets.instructions(advance, mode).contains("The operator's checkout is the integration target. Never edit, build, test or run checks there: " +
        "every change is made in an isolated workspace of the host, by whom the workflow instructions say, and the host captures the candidates.")))
      assert(yolo.contains("they still hold for the operator's checkout and for every place other than that workspace: the operator's checkout is never touched."))
      assert(assets.resource("cq/workflows/entrypoint.md").contains("Never edit, build, test or run checks in the operator's checkout: it is the integration target, " +
        "a change is made only where the workflow instructions say, and the host preserves the operator's own in-progress work there."))
    }

    "I30: give the YOLO instructions the three commands of the session's own work in their sequence, and name none of them in another mode" in {
      val yolo = section(ProcessMode.Yolo)
      // Open, edit there only and without committing, submit with a Worker's report, wait as for host work, read the result.
      val sequence = List(
        "claim the members as for a child and call dispatch OpenWorkspace with a fresh request ID, the members at their current revisions, previous null and the fence of that claim",
        "The reply's phase is Editing, its next is Submit, and its workspace names an absolute directory",
        "outside the operator's checkout",
        "edit only under that directory, by absolute path, and run commands with that directory as the working directory",
        "Do not commit there and do not change its Git state: the host captures the content of the directory as the candidate and refuses a workspace whose HEAD moved",
        "hand the workspace back with SubmitWorkspace: the attempt ID of the reply and, for each member, the report a Worker makes of its work",
        "The call returns at once; from then on the host captures the candidate and runs the configured checks on it",
        "Wait for that as the governing instructions say for work of the host that is not a child, then read its result with Status",
        "a Completed status with next Review carries the result handle of your candidate: have it reviewed, by an independent Reviewer Candidate or by yourself as below, " +
          "then prepare and apply the integration as for any candidate")
      sequence.foreach(sentence => assert(yolo.contains(sentence), sentence))
      assert(sequence.map(yolo.indexOf) == sequence.map(yolo.indexOf).sorted, "The steps are stated in the order they are taken")
      // What a failed check means, how to correct, how to discard, and what an open workspace holds back.
      assert(yolo.contains("A failed check of your candidate means what it means for a Worker's: the status is Completed with next Revise, its blocker names the check, " +
        "the result is retained and nothing is integrated."))
      assert(yolo.contains("To correct the candidate, call OpenWorkspace again with previous set to that result handle, or to the handle of a review of it that requests changes: the new workspace starts from the candidate as you submitted it."))
      assert(yolo.contains("To discard a workspace, call Cancel with its attempt ID: nothing in it becomes a candidate, and the host keeps the directory as it is for the operator, " +
        "as it keeps one you submit with no member CandidateReady."))
      // The way out when the session's harness does not let it work in the directory, whichever harness that is.
      val unable = "If you cannot edit or run commands there, Cancel the workspace and dispatch a Worker instead."
      assert(yolo.contains(unable) && yolo.indexOf(unable) > yolo.indexOf("edit only under that directory") && yolo.indexOf(unable) < yolo.indexOf("Do not commit there") &&
        List("Claude", "Codex", "Pi ", "sandbox", "permission").forall(word => !section(ProcessMode.Yolo).contains(word)))
      assert(yolo.contains("no child and no second workspace starts on them, the workflow cannot be changed, and a driver answers a stop with one resume directive and ends the drive at the next. " +
        "Submit or cancel every workspace before you end your turn."))
      assert(yolo.contains("The host cannot see an edit you make elsewhere, and nothing you write elsewhere becomes part of a candidate."))
      // The self-review is recorded with a command whose reply is a review's.
      assert(yolo.contains("then call dispatch SelfReview with a fresh request ID, the result handle of the candidate, your verdict for each member " +
        "(Accepted, ChangesRequested or Blocked, with findings for every verdict other than Accepted) and the fence of the claim."))
      assert(yolo.contains("The host refuses it while a configured check of the candidate has not passed.") &&
        yolo.contains("its result is the reviewer handle that PrepareIntegration takes, and its next names the next step"))
      // No other mode names a command it would be refused, and no shared text does.
      val commands = List("OpenWorkspace", "SubmitWorkspace", "SelfReview")
      (List(ProcessMode.Rigorous, ProcessMode.CrossCutting).map(section) ++ List("common", "begin", "advance", "entrypoint", "review", "upstream").map(resource)).foreach { text =>
        commands.foreach(command => assert(!text.contains(command), command))
      }
      commands.foreach(command => assert(!SupervisorProgram.Guidance.contains(command)))
      // The tool is the same in every mode: its description names the three commands and says where they are permitted.
      val schemas = new McpSchemas()
      val attached = schemas.attachedTools.find(_.hcursor.get[String]("name") == Right("dispatch")).get.hcursor.get[String]("description").fold(throw _, identity)
      val managed = new LocalControl(null, null, null, null, null, null, schemas, null, null)
        .advertised(LocalCapability(AttemptId(UUID.randomUUID()), Role.Governor)).hcursor.get[String]("description").fold(throw _, identity)
      List(attached, managed).foreach { description =>
        assert(description.endsWith(" OpenWorkspace, SubmitWorkspace and SelfReview are the governing session's own work: the YOLO process mode permits them to an interactive session, " +
          "its workflow instructions give their sequence, and they are refused in every other mode and to a batch run. " +
          "OpenWorkspace replies with an isolated workspace directory for the members (phase Editing, next Submit), SubmitWorkspace hands it back with a Worker's report and returns at once, " +
          "and SelfReview records your verdicts on the candidate of an admitted worker result and replies with the status of that review."), description.takeRight(300))
      }
      // The schema of the tool carries the commands, the phase and the next step for every session.
      val schema = schemas.schema("DispatchCommand").noSpaces
      commands.foreach(command => assert(schema.contains("\"" + command + "\""), command))
      val reply = schemas.schema("DispatchReply").noSpaces
      assert(reply.contains("\"Editing\"") && reply.contains("\"Submit\""))
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
