package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, DriverPolicy}
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/** How one hook-driven harness spells the drive and park commands and is told to run an advance directive. */
final case class HookDialect(harness: Harness, drive: String, park: String, advance: String)

/**
 * The shared CQ hook program of Claude Code and Codex: one hook invocation (harness, event, the hook's stdin) becomes the hook's stdout.
 * It holds no driver state and never builds or edits a directive; `DriverEntry` carries every decision. A CQ error never blocks the
 * harness: it is reported in the message the harness shows and the prompt or stop proceeds.
 */
final class DriverHook(entry: () => DriverEntry) {
  import DriverHook.*

  def run(harness: String, event: String, stdin: Array[Byte]): String =
    try {
      val origin = Origins.find(_.toString == event).getOrElse(invalid(s"Unknown CQ hook event $event"))
      val dialect = Dialects.find(_.harness.toString.toLowerCase == harness).getOrElse(invalid(s"Unknown CQ hook harness $harness"))
      if (stdin.length > MaxInputBytes) invalid(s"Malformed CQ hook input: more than $MaxInputBytes bytes")
      val input = parse(new String(stdin, UTF_8)).toOption.flatMap(_.asObject)
        .getOrElse(invalid("Malformed CQ hook input: expected one JSON object on stdin"))
      // The status line input names no event; a hook input must name the event this command was installed for.
      val received = if (origin == DriverOrigin.StatusLine) event
        else text(input, "hook_event_name").getOrElse(invalid("Malformed CQ hook input: hook_event_name is missing"))
      val call = DriverCall(harness, received, text(input, "session_id"))
      val (key, _) = DriverEntry.identify(call)
      DriverPolicy.key(key)
      if (received != event) invalid(s"The CQ $event hook received a $received event")
      origin match {
        case DriverOrigin.UserPromptSubmit => prompt(dialect, call, text(input, "prompt").getOrElse(invalid("Malformed CQ hook input: prompt is missing")))
        case DriverOrigin.Stop => stop(dialect, call)
        case _ => entry().status(call) match {
          case DriverReply.Status(value) => value.fold(NoDriver)(_.line) + "\n"
          case other => unexpected(other)
        }
      }
    } catch {
      case NonFatal(error) =>
        val message = s"CQ $event hook error: ${describe(error)}"
        if (event == DriverOrigin.StatusLine.toString) message + "\n" else render(JsonObject("systemMessage" -> Json.fromString(message)))
    }

  private def prompt(dialect: HookDialect, call: DriverCall, prompt: String): String = {
    // The result is context for this session's turn and a transcript message; a failure is reported the same way so the command body can show it.
    def reported(label: String)(operation: => (String, List[String])): String = {
      val (message, context) = try operation catch {
        // A fault is the host's answer. Any other failure, such as a reply lost after the server committed, leaves the outcome unknown.
        case error @ DomainFailure(_) => (s"$label rejected: ${describe(error)}", List(Unchanged))
        case NonFatal(error) => (s"$label rejected: ${describe(error)}", List(unknown(dialect)))
      }
      render(JsonObject("hookSpecificOutput" -> Json.obj("hookEventName" -> Json.fromString(call.event),
        "additionalContext" -> Json.fromString((message :: context).mkString("\n"))), "systemMessage" -> Json.fromString(message)))
    }
    arguments(prompt, dialect.drive).map { value =>
      reported(DriveLabel) {
        entry().start(call, value, None) match {
          case DriverReply.Started(status, preview, bind, message) =>
            (s"$DriveLabel: $message", status.line :: bind.toList.map(token =>
              s"Bind token (single use): ${token.value}. Present it once with the CQ session tool: " +
                SessionCommand_JsonCodec.encode(BaboonCodecContext.Default, SessionCommand.Bind(token)).noSpaces) ++ described(preview))
          case other => unexpected(other)
        }
      }
    }.orElse(arguments(prompt, dialect.park).map { _ =>
      reported(ParkLabel) {
        entry().park(call) match {
          case DriverReply.Parked(status, message) => (s"$ParkLabel: $message", status.toList.map(_.line))
          case other => unexpected(other)
        }
      }
    }).getOrElse("")
  }

  private def stop(dialect: HookDialect, call: DriverCall): String = entry().continuation(call) match {
    case DriverReply.Continue(directive, _, messages) =>
      val posted = if (messages.isEmpty) JsonObject.empty else JsonObject("systemMessage" -> Json.fromString(messages.mkString("\n")))
      render(posted.add("decision", Json.fromString("block")).add("reason", Json.fromString((messages ++ List(dialect.advance, directive.text)).mkString("\n"))))
    case DriverReply.Stop(_, _, messages) => if (messages.isEmpty) "" else render(JsonObject("systemMessage" -> Json.fromString(messages.mkString("\n"))))
    case other => unexpected(other)
  }
}

object DriverHook {
  val MaxInputBytes: Int = 1024 * 1024
  val DriveLabel = "CQ driver drive-start"
  val ParkLabel = "CQ driver park"
  val NoDriver = "CQ driver off"
  private val Unchanged = "Nothing changed for this session's driver and no bind token was issued."
  private def unknown(dialect: HookDialect): String = "The CQ server's reply was not received, so this session's driver may have changed and a bind token may have been issued. " +
    s"""Read the driver status with the CQ session tool ({"Driver":{}}) or run ${dialect.park} before driving again."""
  // Keeps the context block below the 10,000 characters Claude Code passes to the model from one hook.
  private val MaxListed = 25
  private val MaxTitle = 80
  private val Origins = List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop, DriverOrigin.StatusLine)
  private val Instruction = "CQ driver: run the directive on the last line verbatim as your next action, changing neither its roots, its phase nor its token."

  val Dialects: List[HookDialect] = List(
    HookDialect(Harness.Claude, "/cq:drive", "/cq:park", s"$Instruction Invoke it as the cq:advance command through the Skill tool with exactly these arguments:"),
    HookDialect(Harness.Codex, "$cq-drive", "$cq-park", s"$Instruction Follow the cq-advance skill with exactly these arguments:"),
  )

  private def invalid(message: String): Nothing = throw DomainFailure(Fault.Invalid(message))
  private def unexpected(reply: DriverReply): Nothing = throw new IllegalStateException(s"Unexpected driver reply ${reply.getClass.getSimpleName}")
  private def render(value: JsonObject): String = Json.fromJsonObject(value).noSpaces + "\n"

  private def text(input: JsonObject, field: String): Option[String] = input(field).filterNot(_.isNull)
    .map(_.asString.getOrElse(invalid(s"Malformed CQ hook input: $field is not a string")))

  // The command must be the first word of the prompt; everything after it is its argument text.
  private def arguments(prompt: String, command: String): Option[String] = {
    val value = prompt.strip
    if (value == command) Some("")
    else if (value.startsWith(command) && value.charAt(command.length).isWhitespace) Some(value.drop(command.length))
    else None
  }

  private def describe(error: Throwable): String = error match {
    case DomainFailure(Fault.Invalid(message)) => message
    case DomainFailure(Fault.Denied(message)) => s"denied: $message"
    case DomainFailure(Fault.Missing(message)) => s"missing: $message"
    case DomainFailure(Fault.Conflict(message)) => s"conflict: $message"
    case DomainFailure(Fault.Limit(message)) => s"limit: $message"
    case DomainFailure(fault) => fault.toString
    case _ => Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
  }

  private def reason(value: WorksetReason): String = value match {
    case WorksetReason.Archived() => "archived"
    case WorksetReason.Terminal() => "terminal"
    case WorksetReason.Settled() => "settled"
    case WorksetReason.Blocked(prerequisite) => s"blocked by ${DriverPolicy.reference(prerequisite)}"
    case WorksetReason.Shared(producer) => s"shared by ${DriverPolicy.reference(producer)}"
    case WorksetReason.Context(source, relation) => s"${DriverPolicy.reference(source)} $relation"
  }

  private def item(value: ItemSummary): String = {
    val title = value.title.replaceAll("\\p{Cc}", " ")
    s"${DriverPolicy.reference(value.id)} [${value.status}] " +
      (if (title.codePointCount(0, title.length) <= MaxTitle) title else title.substring(0, title.offsetByCodePoints(0, MaxTitle - 1)) + "…")
  }

  private def listed(heading: String, lines: List[String]): List[String] =
    s"$heading (${lines.size}):" :: lines.take(MaxListed).map("- " + _) ++ (if (lines.size > MaxListed) List(s"- … ${lines.size - MaxListed} more") else Nil)

  // The host preview in its three separate groups: what the driver may advance, what is shown only, and why an item is not ready.
  private def described(preview: WorksetPreview): List[String] = {
    val readiness = preview.readiness.map(value => value.item -> value).toMap
    listed("Advanceable", preview.advanceable.map { member =>
      val state = readiness.get(member.item.id).fold("readiness unknown") { value =>
        if (value.ready) "ready" else "not ready" + (if (value.reasons.isEmpty) "" else value.reasons.map(reason).mkString(": ", ", ", ""))
      }
      item(member.item) + (if (member.root) " (target)" else "") + " — " + state
    }) ++ listed("Context only, never advanced", preview.context.map(value => item(value.item) + value.reasons.map(reason).mkString(" — ", ", ", "")))
  }
}
