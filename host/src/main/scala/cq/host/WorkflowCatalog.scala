package cq.host

import cq.api.*
import java.nio.file.Path

enum WorkflowName { case Begin, Advance, Review, Upstream }

/** A typed `WorkflowRequest` field together with its CLI option. */
final case class WorkflowOption(field: String, flag: String, value: String, summary: String, choices: List[String])
final case class WorkflowArgument(option: WorkflowOption, required: Boolean, note: Option[String])
final case class WorkflowAlias(harness: Harness, alias: String, path: Path)

/** One workflow command. Prompt resources are referenced by classpath path, never copied. */
final case class WorkflowCommand(name: WorkflowName, command: String, variant: String, description: String,
  arguments: List[WorkflowArgument], template: String, instructions: List[String], aliases: List[WorkflowAlias]) {
  def alias(harness: Harness): WorkflowAlias = aliases.find(_.harness == harness)
    .getOrElse(throw new IllegalStateException(s"Workflow $command has no $harness alias"))
}

/** Pure index of the CQ workflow commands: descriptions, harness aliases, argument docs and prompt resources. */
object WorkflowCatalog {
  val WorkflowFlag = "--workflow"
  private val Resources = "cq/workflows"

  val Roots: WorkflowOption = WorkflowOption("roots", "--roots", "IDS", "Comma-separated roots", Nil)
  val Through: WorkflowOption = WorkflowOption("through", "--through", "PHASE", "phase", WorkflowPhase.all.map(_.toString))
  val Result: WorkflowOption = WorkflowOption("result", "--result", "UUID", "stored result artifact", Nil)
  val Mode: WorkflowOption = WorkflowOption("mode", "--mode", "MODE", "reviewer mode", ReviewerMode.all.map(_.toString))
  val Action: WorkflowOption = WorkflowOption("action", "--action", "ACTION", "upstream action", UpstreamAction.all.map(_.toString))

  private def aliases(command: String): List[WorkflowAlias] = Harness.all.map {
    case harness @ Harness.Claude => WorkflowAlias(harness, s"/cq:$command", Path.of(s".claude/commands/cq/$command.md"))
    case harness @ Harness.Codex => WorkflowAlias(harness, s"cq-$command", Path.of(s".agents/skills/cq-$command/SKILL.md"))
    case harness @ Harness.Pi => WorkflowAlias(harness, s"/cq:$command", Path.of(s".pi/prompts/cq:$command.md"))
  }

  private def command(name: WorkflowName, description: String, arguments: List[WorkflowArgument]): WorkflowCommand = {
    val command = name.toString.toLowerCase
    WorkflowCommand(name, command, name.toString, description, arguments, s"$Resources/entrypoint.md",
      List(s"$Resources/common.md", s"$Resources/$command.md"), aliases(command))
  }

  val commands: List[WorkflowCommand] = List(
    command(WorkflowName.Begin, "Capture CQ intake or a scope follow-up",
      List(WorkflowArgument(Roots, false, Some("an empty set means new intake in this CQ session")))),
    command(WorkflowName.Advance, "Advance selected CQ work through a specified phase",
      List(WorkflowArgument(Roots, true, None), WorkflowArgument(Through, true, None))),
    command(WorkflowName.Review, "Independently review a stored CQ result",
      List(WorkflowArgument(Result, true, None), WorkflowArgument(Mode, true, None))),
    command(WorkflowName.Upstream, "Prepare, report or recheck a CQ upstream defect",
      List(WorkflowArgument(Roots, true, None), WorkflowArgument(Action, true, None))),
  )

  def of(name: WorkflowName): WorkflowCommand = commands.find(_.name == name)
    .getOrElse(throw new IllegalStateException(s"Workflow $name is missing from the catalog"))

  def of(request: WorkflowRequest): WorkflowCommand = of(request match {
    case _: WorkflowRequest.Begin => WorkflowName.Begin
    case _: WorkflowRequest.Advance => WorkflowName.Advance
    case _: WorkflowRequest.Review => WorkflowName.Review
    case _: WorkflowRequest.Upstream => WorkflowName.Upstream
  })

  def named(command: String): Option[WorkflowCommand] = commands.find(_.command == command)

  /** Every argument option, in first-use order. */
  val options: List[WorkflowOption] = commands.flatMap(_.arguments.map(_.option)).distinct

  val flags: Set[String] = options.map(_.flag).toSet + WorkflowFlag

  private def list(values: List[String], last: String): String =
    if (values.size <= 1) values.mkString else values.init.mkString(", ") + s" $last " + values.last

  /** CLI option lines for `cq run` help. */
  def optionHelp: String = {
    def line(flag: String, value: String, doc: String) = "  " + s"$flag $value".padTo(16, ' ') + "  " + doc
    val workflows = line(WorkflowFlag, "NAME", list(commands.map(_.command), "or"))
    workflows :: options.map { option =>
      val uses = commands.flatMap(command => command.arguments.find(_.option == option).map(command -> _))
      val doc = uses match {
        case List((command, argument)) =>
          val text = if (option.choices.isEmpty) option.summary else list(option.choices.map(_.toLowerCase), "or")
          s"${command.command}: $text" + (if (argument.required) "" else " (optional)")
        case _ =>
          val groups = uses.groupBy(_._2.required).toList.sortBy((required, _) => uses.indexWhere(_._2.required == required))
          option.summary + groups.map { (required, members) =>
            (if (required) "required" else "optional") + " for " + members.map(_._1.command).mkString("/")
          }.mkString(" (", "; ", ")")
      }
      line(option.flag, option.value, doc)
    }
  }.mkString("\n")

  /** Typed-request argument guidance rendered into the entrypoint template. */
  def argumentGuide: String = commands.map { command =>
    def render(argument: WorkflowArgument) = argument.option.field + argument.note.map(note => s" ($note)").getOrElse("") +
      (if (argument.option.choices.isEmpty) "" else s" (${list(argument.option.choices, "or")})")
    val (required, optional) = command.arguments.partition(_.required)
    val clauses = List("requires" -> required, "accepts" -> optional).collect {
      case (verb, arguments) if arguments.nonEmpty => s"$verb ${list(arguments.map(render), "and")}"
    }
    command.variant + " " + (if (clauses.isEmpty) "takes no arguments" else clauses.mkString("; ")) + "."
  }.mkString(" ")
}
