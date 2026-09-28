package cq.server

import cq.api.*
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CliOutputLocal extends AnyWordSpec {
  "Operator rendering (Behavioral Active Blackbox Atomic)" should {
    "explain dependencies and proposal statuses without internal record syntax" in {
      val project = ProjectId(UUID.randomUUID())
      val task = ItemId(project, Ledger.Tasks, 1)
      val prerequisite = task.copy(number = 2)
      val summary = ItemSummary(task, Revision(1), "Ship result", "Ready", false, Set.empty, 1000, ItemOutcome(false, false))
      val bytes = new ByteArrayOutputStream()
      val output = new PrintStream(bytes, true, UTF_8)
      new CliOutput(output, CliFormat.Human, List("query", "--roots", "T1")).result(Result.Workset(WorksetPage(
        List(WorksetEntry(summary, WorksetRole.Selected, true, false, List(WorksetReason.Blocked(prerequisite)))),
        WorksetSnapshot(ChangeCursor(1), "fixture"), Some(task), false, 1, 0, 0)))
      assert(bytes.toString(UTF_8).contains("Blocked by T2"))
      assert(!bytes.toString(UTF_8).contains(project.value.toString))
      bytes.reset()
      val preview = ProposalPreview(ArtifactId(UUID.randomUUID()), RequestId(UUID.randomUUID()), Role.Planner, List(ItemRevision(task, Revision(1))),
        List(ProposalOperationSummary.Produce(task, List(ProposalDraftSummary(Ledger.Tasks, "Follow-up", TerminalStatus.Task(TaskStatus.Ready), false)))), true)
      new CliOutput(output, CliFormat.Human, List("proposal", "preview", preview.result.value.toString)).result(Result.Proposal(preview))
      val text = bytes.toString(UTF_8)
      assert(text.contains("Produce from T1: Tasks: Follow-up · Ready"))
      assert(text.contains("complete content") && !text.contains("Task(Ready)"))
    }
  }
}
