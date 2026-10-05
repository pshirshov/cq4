package cq.server

import cq.api.*
import cq.host.WorkflowAssets
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

  private val Wait = "/opt/cq/bin/cq wait --session /state/sessions/0199"
  /** The governing instructions of an attached session of every harness; only the Pi extension's host names no wait command. */
  private def attached(schemas: McpSchemas): List[String] = schemas.attachedInstructions(Harness.Pi, None) :: Harness.all.toList.map(schemas.attachedInstructions(_, Some(Wait)))

  "Waiting for the host's work in the governing instructions (Behavioral Active Blackbox Atomic)" should {
    val schemas = new McpSchemas()
    val work = "Waiting for work the host carries out (a child, an integration being prepared or applied, a combination, a revalidation): "
    val named = "adding --attempt ID for each running child and --integration ID, --combination ID or --revalidation ID for the others: `" + Wait + "`"
    "tell a Claude Code session to wait with a background command at the longest background lifetime and to read each end once" in {
      val text = schemas.attachedInstructions(Harness.Claude, Some(Wait))
      assert(text.contains(work + "do not call a status to wait. After starting such work, run this command with the Bash tool as a background command " +
        "(run_in_background true, timeout 7200000), " + named + ". Then continue with other ready work or end your turn."))
      assert(text.contains("0 means a named unit ended") && text.contains("3 means the CQ host is not running, which you report to the user") &&
        text.contains("Read the outcome of each ended unit with one Status, IntegrationStatus or CombinationStatus call with waitMillis 0, and start the command again for the units still active."))
    }
    "tell a Codex session to wait with one blocking call inside its turn, because nothing wakes it" in {
      val text = schemas.attachedInstructions(Harness.Codex, Some(Wait))
      assert(text.contains(work + "do not call a status to wait, and do not end your turn while such work is active: nothing wakes you when it ends. " +
        "After starting such work, run this command as one blocking shell call (exec_command with yield_time_ms 300000), " + named + ". " +
        "If the call returns while the command still runs, wait for it with empty write_stdin calls (yield_time_ms 300000) until it exits."))
      assert(text.contains("exit code 3 means the CQ host is not running") && !text.contains("run_in_background"))
    }
    "tell a Pi session that CQ sends it a message and that it starts no waiter" in {
      List(Some(Wait), None).map(schemas.attachedInstructions(Harness.Pi, _)).foreach { text =>
        assert(text.contains(work + "do not call a status to wait and start no waiter yourself. After starting such work, continue with other ready work or end your turn: " +
          "CQ sends you a message that begins `CQ:` when a unit ends, naming it, its items, its phase and the next step."))
        assert(!text.contains(Wait) && !text.contains("run_in_background") && !text.contains("exec_command"))
      }
    }
    "tell a batch Governor, which has no shell and is told nothing, to wait through its status calls, and no attached session" in {
      val byStatus = work + "call the status of that work (Status, IntegrationStatus or CombinationStatus; repeat Revalidate) with waitMillis 20000. " +
        "The call returns when the work ends or the wait has passed; call it again while the work continues."
      val batch = SupervisorProgram.Instructions
      assert(batch.contains(byStatus) && !batch.contains(" wait --session ") && !batch.contains("run_in_background") && !batch.contains("exec_command"))
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
      assert(dispatch.contains("StartChoice returns at once. Status reads the current state or the result of an attempt and is not called to wait") &&
        !dispatch.toLowerCase.contains("poll"), dispatch)
      List("workflows/common.md", "workflows/advance.md", "workflows/begin.md", "workflows/entrypoint.md").foreach { name =>
        val text = new String(getClass.getResourceAsStream("/cq/" + name).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        assert(!text.toLowerCase.contains("poll"), name)
      }
    }
  }

  "Operator decisions in the governing workflows (Behavioral Active Blackbox Atomic)" should {
    "tell the session to record a Question before it stops, not to ask for a go-ahead it has, and to store a chat answer (D147)" in {
      val assets = new WorkflowAssets
      val begin = assets.instructions(WorkflowRequest.Begin(Set.empty))
      val advance = assets.instructions(WorkflowRequest.Advance(Set.empty, WorkflowPhase.Work))
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
