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
      assert(advance.contains("Do not ask for a go-ahead that the invocation or an Answered Question already gives"))
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
