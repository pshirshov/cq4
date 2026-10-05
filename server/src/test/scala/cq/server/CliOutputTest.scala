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
        List(ProposalOperationSummary.Create(ProposalDraftSummary(Ledger.Milestones, "Release", TerminalStatus.Milestone(MilestoneStatus.Open), false)),
          ProposalOperationSummary.Produce(task, List(ProposalDraftSummary(Ledger.Tasks, "Follow-up", TerminalStatus.Task(TaskStatus.Ready), false),
            ProposalDraftSummary(Ledger.Researches, "Unknown", TerminalStatus.Research(ResearchStatus.Open), false)), Some(MilestoneRef.Created(0))),
          ProposalOperationSummary.Produce(task, List(ProposalDraftSummary(Ledger.Tasks, "Second", TerminalStatus.Task(TaskStatus.Ready), false)),
            Some(MilestoneRef.Existing(ItemId(project, Ledger.Milestones, 3)))),
          ProposalOperationSummary.Produce(task, List(ProposalDraftSummary(Ledger.Researches, "Unassigned", TerminalStatus.Research(ResearchStatus.Open), false)), None)), true)
      new CliOutput(output, CliFormat.Human, List("proposal", "preview", preview.result.value.toString)).result(Result.Proposal(preview))
      val text = bytes.toString(UTF_8)
      assert(text.linesIterator.toList.slice(2, 7) == List("Create Milestones: Release · Open",
        "Produce from T1: Tasks: Follow-up · Ready · milestone created by operation 0",
        "Produce from T1: Researches: Unknown · Open",
        "Produce from T1: Tasks: Second · Ready · milestone M3",
        "Produce from T1: Researches: Unassigned · Open"))
      assert(text.contains("complete content") && !text.contains("Task(Ready)"))
    }

    "print one row per usage phase with attempts, wall time, tokens and grouped costs" in {
      def metric(value: Long): MetricTotal = MetricTotal(value, 0, 0)
      def totals(input: Long, output: Long, unknownCosts: Long): UsageTotals =
        UsageTotals(metric(input), metric(output), metric(0), metric(0), metric(0), metric(input + output), unknownCosts)
      val cost = CostTotal(CostGroup(Attribution.Direct, "USD", CostBasis.ProviderEstimate, Some("provider-v1")), DecimalAmount("0.3"), 2)
      val report = PhaseReport(List(
        PhaseUsage(UsagePhase.Work, 2, 0, 0, 0, 3723500, totals(300, 30, 0), List(cost)),
        PhaseUsage(UsagePhase.Review, 1, 0, 1, 2, 0, totals(50, 5, 1), Nil)), true, 7)
      val bytes = new ByteArrayOutputStream()
      val session = UUID.randomUUID().toString
      new CliOutput(new PrintStream(bytes, true, UTF_8), CliFormat.Human, List("status", "phases", "--session", session)).result(Result.UsagePhases(report))
      val lines = bytes.toString(UTF_8).linesIterator.toList
      def row(phase: String): List[String] = lines.filter(_.startsWith(phase + " ")).map(_.split(" {2,}").toList).head
      assert(lines.head.split(" {2,}").toList == List("Phase", "Attempts", "Running", "Open", "Spans", "Wall h:mm:ss", "Input", "Output", "Cache read", "Cache write", "Reasoning", "Total", "Unknown costs"))
      assert(row("Work") == List("Work", "2", "0", "0", "0", "1:02:03", "300", "30", "0", "0", "0", "330", "0"))
      assert(row("Review") == List("Review", "1", "1", "2", "0", "0:00:00", "50", "5", "0", "0", "0", "55", "1"))
      assert(lines.count(line => UsagePhase.all.exists(phase => line.startsWith(phase.toString + " "))) == 3)
      assert(lines.exists(_.split(" {2,}").toList == List("Work", "Direct", "0.3", "USD", "ProviderEstimate", "provider-v1", "2")))
      assert(lines.exists(line => line.contains("truncated") && line.contains(s"cq status costs --session $session")))
      bytes.reset()
      new CliOutput(new PrintStream(bytes, true, UTF_8), CliFormat.Json, List("status", "phases")).result(Result.UsagePhases(report))
      assert(Wire.decode(Result_JsonCodec, bytes.toString(UTF_8).trim) == Result.UsagePhases(report))
    }

    "I20: print a phase of host spans with its span count and wall time and say that wall time includes spans" in {
      val none = MetricTotal(0, 0, 0)
      val empty = UsageTotals(none, none, none, none, none, none, 0)
      val report = PhaseReport(List(
        PhaseUsage(UsagePhase.Check, 0, 3, 0, 0, 65000, empty, Nil),
        PhaseUsage(UsagePhase.Combine, 1, 1, 0, 0, 1500, empty, Nil),
        PhaseUsage(UsagePhase.Integrate, 0, 1, 0, 0, 4000, empty, Nil)), false, 9)
      val bytes = new ByteArrayOutputStream()
      new CliOutput(new PrintStream(bytes, true, UTF_8), CliFormat.Human, List("status", "phases", "--task", "T3")).result(Result.UsagePhases(report))
      val lines = bytes.toString(UTF_8).linesIterator.toList
      def row(phase: String): List[String] = lines.filter(_.startsWith(phase + " ")).map(_.split(" {2,}").toList.take(6)).head
      assert(row("Check") == List("Check", "0", "0", "0", "3", "0:01:05") && row("Combine") == List("Combine", "1", "0", "0", "1", "0:00:01") &&
        row("Integrate") == List("Integrate", "0", "0", "0", "1", "0:00:04"), lines.mkString("\n"))
      assert(lines.contains("Wall time sums finished attempts and host spans (check, combination and integration time outside any attempt) " +
        "from start to finish; running and open attempts are counted without wall time."), lines.mkString("\n"))
    }
  }
}
