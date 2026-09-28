package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant

enum CliFormat { case Human, Json }

final case class CliArguments(values: List[String], format: CliFormat)
object CliArguments {
  def parse(args: List[String]): CliArguments = {
    val values = List.newBuilder[String]
    var remaining = args
    var format = CliFormat.Human
    while (remaining.nonEmpty) remaining match {
      case "--json" :: tail =>
        require(format != CliFormat.Json, "Repeated --json option")
        format = CliFormat.Json; remaining = tail
      case option :: value :: tail if option.startsWith("--") && option != "--replace" =>
        values += option; values += value; remaining = tail
      case head :: tail => values += head; remaining = tail
      case Nil => ()
    }
    CliArguments(values.result(), format)
  }
}

final class CliOutput(output: PrintStream, format: CliFormat, invocation: List[String]) {
  def archive(manifest: BackupManifest, action: String, path: Path): Unit = format match {
    case CliFormat.Json => output.println(Wire.encode(BackupManifest_JsonCodec, manifest))
    case CliFormat.Human =>
      line(s"$action project ${manifest.project.value}: $path")
      line(s"${manifest.entries.map(_.rows).sum} records in ${manifest.entries.size} tables; snapshot ${instant(manifest.createdAt)}")
      line("Archive includes stored project history, usage and artifacts. Git checkouts and local harness journals are separate.")
  }
  private val CellLimit = 80
  private def clean(value: String): String = value.replaceAll("[\\p{Cc}]", " ")
  private def cell(value: String): String = {
    val safe = clean(value)
    if (safe.codePointCount(0, safe.length) <= CellLimit) safe else safe.substring(0, safe.offsetByCodePoints(0, CellLimit - 1)) + "…"
  }
  private def id(value: ItemId): String = LedgerPolicy.prefix(value.ledger) + value.number
  private def instant(value: Long): String = Instant.ofEpochMilli(value).toString
  private def shell(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
  private def line(value: String): Unit = output.println(clean(value))
  private def table(headers: List[String], rows: List[List[String]]): Unit = {
    val formatted = (headers :: rows).map(_.map(cell))
    require(formatted.forall(_.size == headers.size), "CLI table column invariant")
    val widths = headers.indices.map(index => formatted.map(_(index).length).max)
    formatted.zipWithIndex.foreach { case (row, index) =>
      output.println(row.zip(widths).map((value, width) => value.padTo(width, ' ')).mkString("  ").stripTrailing())
      if (index == 0) output.println(widths.map("─" * _).mkString("  "))
    }
    if (rows.isEmpty) line("No entries.")
  }
  private def withoutPagination(values: List[String]): List[String] = values match {
    case ("--after" | "--snapshot") :: _ :: rest => withoutPagination(rest)
    case option :: value :: rest if option.startsWith("--") => option :: value :: withoutPagination(rest)
    case head :: rest => head :: withoutPagination(rest)
    case Nil => Nil
  }
  private def page(hasMore: Boolean, after: String, snapshot: Option[String], command: List[String]): Unit = {
    if (hasMore) {
      require(after.nonEmpty, "Missing continuation anchor")
      val args = withoutPagination(command)
      val continuation = s"--after ${shell(after)}" + snapshot.fold("")(value => s" --snapshot ${shell(value)}")
      if (args.exists(value => clean(value) != value)) {
        line(s"More available. Repeat cq ${args.takeWhile(!_.startsWith("--")).mkString(" ")} with the original options and $continuation (filter contains control characters).")
      } else {
        val rendered = args.map(value => if (value.matches("[-A-Za-z0-9_./:=]+")) value else shell(value)).mkString(" ")
        line(s"More available. Continue: cq $rendered $continuation")
      }
    }
  }
  def paths(values: List[Path]): Unit = format match {
    case CliFormat.Json => output.println(io.circe.Json.arr(values.map(value => io.circe.Json.fromString(value.toString))*).noSpaces)
    case CliFormat.Human => values.foreach(value => line(value.toString))
  }
  def endpoint(value: String): Unit = format match {
    case CliFormat.Json => output.println(io.circe.Json.obj("endpoint" -> io.circe.Json.fromString(value)).noSpaces)
    case CliFormat.Human => line(value)
  }
  def configuration(value: Path): Unit = if (format == CliFormat.Human) line(s"Configuration: $value")
  def result(value: Result): Unit = format match {
    case CliFormat.Json => output.println(Wire.encode(Result_JsonCodec, value))
    case CliFormat.Human => human(value)
  }
  private def costs(value: CostPage): Unit = {
    table(List("Attribution", "Amount", "Currency", "Basis", "Pricing", "Measurements"), value.entries.map { entry =>
      List(entry.group.attribution.toString, entry.amount.value, entry.group.currency, entry.group.basis.toString,
        entry.group.pricingVersion.getOrElse("—"), entry.measurements.toString)
    })
    val command = invocation match {
      case "status" :: "costs" :: _ => invocation
      case "status" :: rest => "status" :: "costs" :: rest
      case _ => throw new IllegalStateException("Costs require a status invocation")
    }
    page(value.hasMore, value.after.fold("")(Wire.encode(CostGroup_JsonCodec, _)), Some(value.cursor.toString), command)
  }
  private def metric(value: MetricTotal): String = value.known.toString +
    (if (value.unknown > 0) s" (+${value.unknown} unknown)" else "") + (if (value.estimated > 0) s" (${value.estimated} estimates)" else "")
  private def counter(value: Counter): String = value.value.fold("unknown")(_.toString) +
    (if (value.measurement == Measurement.Observed) "" else s" (${value.measurement})")
  private def reason(value: WorksetReason): String = value match {
    case WorksetReason.Archived() => "Archived"
    case WorksetReason.Terminal() => "Terminal"
    case WorksetReason.Blocked(prerequisite) => s"Blocked by ${id(prerequisite)}"
    case WorksetReason.Shared(producer) => s"Shared by ${id(producer)}"
    case WorksetReason.Context(source, relation) => s"Context: ${id(source)} $relation"
  }
  private def status(value: TerminalStatus): String = value match {
    case TerminalStatus.Milestone(value) => value.toString
    case TerminalStatus.Idea(value) => value.toString
    case TerminalStatus.Defect(value) => value.toString
    case TerminalStatus.Goal(value) => value.toString
    case TerminalStatus.Task(value) => value.toString
    case TerminalStatus.Research(value) => value.toString
    case TerminalStatus.Hypothesis(value) => value.toString
    case TerminalStatus.Question(value) => value.toString
    case TerminalStatus.Decision(value) => value.toString
    case TerminalStatus.Review(value) => value.toString
    case TerminalStatus.Handoff(value) => value.toString
    case TerminalStatus.OperatorAction(value) => value.toString
    case TerminalStatus.Memory(value) => value.toString
    case TerminalStatus.Upstream(value) => value.toString
  }
  private def usage(value: UsageReport): Unit = {
    line("Usage — tokens")
    table(List("Attribution", "Input", "Output", "Cache read", "Cache write", "Reasoning", "Total", "Unknown costs"),
      List("Direct" -> value.direct, "Shared" -> value.shared, "Unattributed" -> value.unattributed).map { case (label, totals) =>
        List(label, metric(totals.input), metric(totals.output), metric(totals.cacheRead), metric(totals.cacheWrite),
          metric(totals.reasoning), metric(totals.total), totals.unknownCosts.toString)
      })
    line("Shared work is counted once per assignment, not divided among members.")
    line(s"Incomplete meters: ${value.incompleteMeters}; attempts without meters: ${value.attemptsWithoutMeters}.")
    line(s"Attempts: ${value.attempts.running} running; ${value.attempts.unknown} unknown outcomes; ${value.attempts.withGaps} with gaps.")
    line("Costs — estimates and billing remain separate")
    costs(value.costs)
    if (value.sharedAssignmentsTruncated) line("Shared assignment list is truncated; use the paged audit for further records.")
  }
  private def proposal(value: ProposalPreview): Unit = {
    line(s"Proposal ${value.result.value} · ${value.role} · request ${value.request.value}")
    line("Members: " + value.members.map(item => s"${id(item.id)} @ ${item.revision.value}").mkString(", "))
    def draft(value: ProposalDraftSummary): String = s"${value.ledger}: ${value.title} · ${status(value.status)}" + (if (value.archived) " · archived" else "")
    value.operations.foreach {
      case ProposalOperationSummary.Create(value) => line("Create " + draft(value))
      case ProposalOperationSummary.Replace(item, before, after, fields) =>
        line(s"Replace ${id(item)}: ${draft(before)} → ${draft(after)}")
        val changed = List("body" -> fields.body, "labels" -> fields.labels, "content" -> fields.content, "citations" -> fields.citations,
          "evidence" -> fields.evidence, "provenance" -> fields.provenance, "operator confirmation" -> fields.operatorConfirmation).collect { case (name, true) => name }
        line("  Changed: " + changed.mkString(", "))
      case ProposalOperationSummary.Produce(producer, drafts) => drafts.foreach(value => line(s"Produce from ${id(producer)}: ${draft(value)}"))
      case ProposalOperationSummary.Reference(source, relation, target, present) => line(s"${if (present) "Add" else "Remove"} ${id(source)} $relation ${id(target)}")
    }
    if (value.detailsOmitted) line("This preview summarizes changed fields; inspect the stored result for complete content.")
  }
  private def human(value: Result): Unit = value match {
    case Result.Initialized(project) => line(s"Project: ${project.name} (${project.id.value}) · revision ${project.revision.value}")
    case Result.Found(value) =>
      table(List("ID", "Type", "Title", "Status", "Revision", "Archived"), value.items.map(item =>
        List(id(item.id), item.id.ledger.toString, item.title, item.status, item.revision.value.toString, if (item.archived) "yes" else "")))
      page(value.hasMore, value.after.fold("")(id), Some(value.cursor.value.toString), invocation)
    case Result.Workset(value) =>
      table(List("ID", "Title", "Status", "Role", "Ready", "Reasons"), value.entries.map(entry => List(id(entry.item.id), entry.item.title,
        entry.item.status, entry.role.toString, if (entry.ready) "yes" else "no", entry.reasons.map(reason).mkString(", "))))
      line(s"Selected: ${value.selectedCount}; context: ${value.contextCount}; ready: ${value.readyCount}.")
      page(value.hasMore, value.after.fold("")(id), Some(Wire.encode(WorksetSnapshot_JsonCodec, value.snapshot)), invocation)
    case Result.QueryAnalyzed(value) =>
      value.diagnostic.foreach(error => line(s"Query: ${error.message} (${error.span.start}–${error.span.end})"))
      table(List("Kind", "Suggestion", "Meaning", "Replace UTF-16"), value.suggestions.map(entry =>
        List(entry.kind.toString, entry.text, entry.label, s"${entry.span.start}–${entry.span.end}")))
      if (value.hasMore) line("More suggestions available; narrow the prefix or increase --limit.")
    case Result.Proposal(value) => proposal(value)
    case Result.Changed(value) =>
      line(s"Saved request ${value.request.value} · cursor ${value.cursor.value}")
      table(List("ID", "Revision"), value.items.map(item => List(id(item.id), item.revision.value.toString)))
    case Result.UsageSummary(value) => usage(value)
    case Result.UsageCosts(value) => costs(value)
    case Result.UsageAttempts(value) =>
      table(List("Attempt", "Role", "Harness", "Model", "State", "Started", "Items"), value.entries.map(entry =>
        List(entry.attempt.id.value.toString, entry.attempt.role.toString, entry.attempt.harness.toString, entry.attempt.model,
          entry.outcome.fold("Running")(_.value.state.toString), instant(entry.attempt.startedAt), entry.assignment.members.toList.sortBy(LedgerPolicy.key).map(id).mkString(", "))))
      value.entries.foreach(entry => entry.outcome.foreach(outcome => outcome.value.gaps.foreach(gap => line(s"${entry.attempt.id.value}: $gap"))))
      page(value.hasMore, value.after.fold("")(_.value.toString), Some(value.cursor.toString), invocation)
    case Result.UsageOutcomes(value) =>
      table(List("Sequence", "State", "Finished", "Request"), value.entries.map(entry =>
        List(entry.sequence.toString, entry.value.state.toString, instant(entry.value.finishedAt), entry.value.request.value.toString)))
      value.entries.foreach(entry => entry.value.gaps.foreach(gap => line(s"${entry.sequence}: $gap")))
      page(value.hasMore, value.after.toString, None, invocation)
    case Result.UsageAudit(value) =>
      table(List("Sequence", "Attempt", "Source", "Disposition", "Input", "Output", "Coverage"), value.entries.map { entry =>
        val observation = entry.upload.observation
        List(entry.sequence.toString, observation.attempt.value.toString, observation.source, entry.upload.disposition.toString,
          counter(entry.normalized.input), counter(entry.normalized.output), observation.completeness.toString)
      })
      value.entries.foreach { entry =>
        entry.upload.observation.gaps.foreach(gap => line(s"${entry.sequence}: $gap"))
        entry.upload.detailReason.foreach(reason => line(s"${entry.sequence}: $reason"))
      }
      page(value.hasMore, value.after.toString, None, invocation)
    case other => throw new IllegalStateException(s"No operator formatter for ${other.getClass.getSimpleName}; use --json")
  }
}
