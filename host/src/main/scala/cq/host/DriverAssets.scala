package cq.host

import cq.api.*
import io.circe.Json
import java.nio.file.Path

/**
 * What `cq configure` installs for the hook-driven harnesses (Claude Code and Codex): the drive and park command or skill files, and the
 * CQ entries it merges into harness-owned hook configuration. A CQ entry is recognised by its whole command: one executable followed by
 * `hook <harness> <event>`, as [[hookCommand]] writes it. Every other entry is user-owned and is kept as it is, including a command that
 * wraps the CQ hook or merely ends like one.
 */
object DriverAssets {
  val Hooks: List[DriverOrigin] = List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop)
  val StatusLineFlag = "--replace-statusline"

  private val Drive = """The CQ UserPromptSubmit hook handled this {{COMMAND}} for this session before you saw it. Its result is in this turn's context as a block that begins `{{DRIVE}}`. This {{KIND}} cannot start or park a driver, and neither can you: only the CQ hooks do.

1. Find that block. If it is absent, report that the CQ hooks are not active in this session ({{PRECONDITION}}) and stop.
2. If the block reports a rejection, show it to the user verbatim and stop.
3. Otherwise show the user the block's preview: the advanceable items, the context-only items and the readiness reasons.
4. Call the CQ `session` tool exactly once with the Bind request printed in the block, using the bind token of this turn only. Show the status line of its reply, or the refusal if the bind is refused.
5. End your turn without calling any other CQ tool. The CQ Stop hook then supplies each advance directive; run a directive only when the hook gives you one, exactly as given.
"""

  private val Park = """The CQ UserPromptSubmit hook handled this {{COMMAND}} for this session before you saw it. Its result is in this turn's context as a block that begins `{{PARK}}`. This {{KIND}} cannot start or park a driver, and neither can you: only the CQ hooks do.

1. Find that block. If it is absent, report that the CQ hooks are not active in this session ({{PRECONDITION}}) and stop.
2. Show the block to the user verbatim.
3. Call the CQ `session` tool with `{"Driver":{}}` and show the status line of its reply, then end your turn.
"""

  private final case class Wording(kind: String, precondition: String, asset: (String, String, String) => CommandAsset)

  private def wording(harness: Harness): Option[Wording] = harness match {
    case Harness.Claude => Some(Wording("command", "`cq configure claude` installs them; launch Claude Code with `--setting-sources project,local`",
      (name, description, body) => CommandAsset(Path.of(s".claude/commands/cq/$name.md"), s"---\ndescription: $description\n---\n\nInvocation arguments: $$ARGUMENTS\n\n$body")))
    case Harness.Codex => Some(Wording("skill", "`cq configure codex` installs them; Codex runs project hooks only in a trusted project after a `/hooks` review",
      (name, description, body) => CommandAsset(Path.of(s".agents/skills/cq-$name/SKILL.md"), s"---\nname: cq-$name\ndescription: $description\n---\n\n$body")))
    // Pi registers its drive and park commands in the CQ extension.
    case Harness.Pi => None
  }

  /** The drive and park command files (Claude Code) or skills (Codex). */
  def commands(harness: Harness): List[CommandAsset] = wording(harness).toList.flatMap { wording =>
    val dialect = DriverHook.Dialects.find(_.harness == harness).getOrElse(throw new IllegalStateException(s"$harness has no CQ hook dialect"))
    List(("drive", dialect.drive, Drive, "Turn the CQ auto-driver on for <target IDs> through=<phase> or workset=<id>"),
      ("park", dialect.park, Park, "Turn the CQ auto-driver off for this session")).map { (name, command, template, description) =>
      wording.asset(name, description, template.replace("{{COMMAND}}", command).replace("{{KIND}}", wording.kind)
        .replace("{{DRIVE}}", DriverHook.DriveLabel).replace("{{PARK}}", DriverHook.ParkLabel).replace("{{PRECONDITION}}", wording.precondition))
    }
  }

  private def quoted(value: String): String = if (value.matches("[-A-Za-z0-9_./]+")) value else "'" + value.replace("'", "'\"'\"'") + "'"

  def hookCommand(executable: Path, harness: Harness, origin: DriverOrigin): String = s"${quoted(executable.toString)} hook ${harness.toString.toLowerCase} $origin"

  // One shell word as `quoted` writes an executable path.
  private val Executable = """(?:[-A-Za-z0-9_./]+|'(?:[^']|'"'"')*')"""
  private val Generated = (Executable + " hook (?:" + Harness.all.map(_.toString.toLowerCase).mkString("|") + ") (?:" + DriverOrigin.all.mkString("|") + ")").r

  private def owned(handler: Json): Boolean = handler.hcursor.get[String]("command").exists(command => Generated.matches(command.trim.split("\\s+").mkString(" ")))

  private def handler(executable: Path, harness: Harness, origin: DriverOrigin): Json =
    Json.obj("type" -> Json.fromString("command"), "command" -> Json.fromString(hookCommand(executable, harness, origin)))

  /** Merges the CQ UserPromptSubmit and Stop hooks into the `hooks` object of a harness configuration, replacing only earlier CQ entries. */
  def hooks(configuration: Json, executable: Path, harness: Harness, file: String): Json = {
    require(configuration.isObject, s"$file must be an object")
    val current = configuration.hcursor.downField("hooks").focus.getOrElse(Json.obj())
    require(current.isObject, s"hooks in $file must be an object")
    val merged = Hooks.foldLeft(current.asObject.get) { (events, origin) =>
      val groups = events(origin.toString).getOrElse(Json.arr())
      require(groups.isArray, s"hooks.$origin in $file must be an array")
      val kept = groups.asArray.get.flatMap { group =>
        group.hcursor.downField("hooks").focus.flatMap(_.asArray) match {
          case Some(handlers) if handlers.exists(owned) =>
            val user = handlers.filterNot(owned)
            if (user.isEmpty) None else Some(group.mapObject(_.add("hooks", Json.fromValues(user))))
          case _ => Some(group)
        }
      }
      events.add(origin.toString, Json.fromValues(kept :+ Json.obj("hooks" -> Json.arr(handler(executable, harness, origin)))))
    }
    configuration.mapObject(_.add("hooks", Json.fromJsonObject(merged)))
  }

  /** Installs the CQ statusLine in Claude Code settings. A statusLine that is not CQ's is user-owned: it is replaced only on request. */
  def statusLine(settings: Json, executable: Path, replace: Boolean, file: String): Json = {
    val value = handler(executable, Harness.Claude, DriverOrigin.StatusLine)
    require(settings.hcursor.downField("statusLine").focus.forall(current => current == value || owned(current) || replace),
      s"Claude statusLine in $file differs; use $StatusLineFlag to replace it with the CQ driver status line")
    settings.mapObject(_.add("statusLine", value))
  }
}
