package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, DriverPolicy}
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/** How one hook-driven harness spells the drive and park commands and is told to run an advance directive. */
/** `woken` says whether an idle session of the harness is started again when a background command of it exits; `waiter` is the order
  * to start the wait command for the standing units, in the way the harness can wait. `resting` is that order for a session that
  * ends its turn with nothing running while it waits on Open Questions; a harness whose idle session nothing wakes has none. */
final case class HookDialect(harness: Harness, drive: String, park: String, advance: String, woken: Boolean, waiter: (String, Option[String]) => String,
  resting: Option[(String, Option[String]) => String])

/**
 * The shared CQ hook program of Claude Code and Codex: one hook invocation (harness, event, the hook's stdin) becomes the hook's stdout.
 * It holds no driver state and never builds or edits a directive; `DriverEntry` carries every decision. A CQ error never blocks the
 * harness: it is reported in the message the harness shows and the prompt or stop proceeds.
 */
/** `sessions` is what the checkout of this hook knows about its attached sessions and their hosts. */
final class DriverHook(entry: () => DriverEntry, sessions: SessionViews) {
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

  // What a Stop hook answers: `reason` blocks the stop and is what the session reads; `posted` is shown in the transcript.
  private final case class Answer(reason: Option[String], posted: Option[String]) {
    def rendered: String = {
      val shown = posted.fold(JsonObject.empty)(text => JsonObject("systemMessage" -> Json.fromString(text)))
      reason match {
        case Some(text) => render(shown.add("decision", Json.fromString("block")).add("reason", Json.fromString(text)))
        case None => if (posted.isEmpty) "" else render(shown)
      }
    }
    // The stop is blocked for `order` as well, which is said first: a directive stays the last line. What was only posted is said
    // to the session too, since its turn goes on.
    def blocked(order: String): Answer = copy(reason = Some((order :: reason.orElse(posted).toList).mkString("\n")))
    def posting(text: String): Answer = copy(posted = Some((posted.toList :+ text).mkString("\n")))
  }
  private def message(text: String): Answer = Answer(None, Some(text))
  // A stop that would leave the drive waiting for something that will not come ends the drive here, where it can still be said.
  private def parked(call: DriverCall, cause: String): Answer = entry().park(call) match {
    case DriverReply.Parked(_, parked) => message(s"$cause $parked. $Restart")
    case other => unexpected(other)
  }

  /** `unseen` is the host of the drive's session when this checkout does not find it running. */
  private def answered(dialect: HookDialect, call: DriverCall, waiting: Boolean, unseen: Option[HostView]): Answer = entry().continuation(call, waiting) match {
    case DriverReply.Continue(directive, _, messages) =>
      Answer(Some((messages ++ List(dialect.advance, directive.text)).mkString("\n")), Option.when(messages.nonEmpty)(messages.mkString("\n")))
    case DriverReply.Stop(_, _, messages) => Answer(None, Option.when(messages.nonEmpty)(messages.mkString("\n")))
    case DriverReply.Waiting(_, waited) => unseen match {
      case None => message(waited)
      // A host that is gone finishes nothing and wakes nobody.
      case Some(HostView.Unrecorded) => parked(call, HostUnrecorded)
      case Some(_) => parked(call, HostGone)
    }
    case other => unexpected(other)
  }

  // The session may stop with work in flight only when it is certain to be started again: its host runs, the host works on something,
  // and a `cq wait` runs on the session of a harness whose idle session a finished background command wakes. With work standing and
  // no such waiter the stop is blocked with the order to start one; that is no directive of the driver and counts as none. A session
  // that stops again on the same standing work without a waiter is not asked twice: the drive is parked.
  private def working(dialect: HookDialect, call: DriverCall, session: SessionId, host: HostView.Running): Answer =
    if (dialect.woken && host.waited) { sessions.ask(session, None); answered(dialect, call, true, None) }
    else {
      val units = host.standing.map(SessionUnits.described).mkString("; ")
      val key = host.standing.map(unit => s"${unit.kind} ${unit.id}").sorted.mkString(",")
      if (sessions.asked(session).contains(key)) { sessions.ask(session, None); parked(call, s"$Unwaited $units.") }
      else {
        sessions.ask(session, Some(key))
        Answer(Some(dialect.waiter(units, host.waitCommand)), None)
      }
    }

  // A session that stops with nothing running while it waits on Open Questions is told once to start its waiter, where a waiter
  // can start its next turn: the answer then does. A session that stops again without one may stop, and learns of the answer at
  // its next turn end.
  private def resting(dialect: HookDialect, session: SessionId, host: HostView.Running, answer: Answer): Answer =
    if (host.standing.nonEmpty) answer
    else {
      val key = host.watched.map(question => "Awaited " + SessionUnits.reference(question)).sorted.mkString(",")
      dialect.resting.filter(_ => answer.reason.isEmpty && host.watched.nonEmpty && !host.waited) match {
        case Some(order) if !sessions.asked(session).contains(key) =>
          sessions.ask(session, Some(key))
          answer.blocked(order(host.watched.map(SessionUnits.reference).mkString(", "), host.waitCommand))
        case Some(_) => answer
        case None => sessions.ask(session, None); answer
      }
    }

  // What the host of `session` wrote about what the session waits on a person for: each end the session has not read is said
  // once, the first ones by their line and the rest by their reference, so that a directive after them stays whole.
  private def awaited(dialect: HookDialect, session: SessionId, driven: Answer): Answer = sessions.view(session) match {
    case host: HostView.Running =>
      val answer = resting(dialect, session, host, driven)
      val settled = sessions.announce(session)
      val (said, more) = settled.splitAt(MaxSettled)
      val rest = if (more.isEmpty) Nil else List(s"- and ${more.size} more: ${more.map(end => SessionUnits.reference(end.item)).mkString(", ")}")
      if (settled.isEmpty) answer else answer.blocked((Settled :: said.map("- " + SessionAwaited.described(_)) ::: rest ::: List(SessionAwaited.Act)).mkString("\n"))
    case _ => driven
  }

  // The driver is asked about the session it names, which is bound by token: one that is on, or that rests on user input. What a
  // session waits on a person for is that of the session whose host the harness of this hook started, driven or not; where the
  // hook finds no such host, it is that of the session the driver names. A failure in that part leaves what the driver answered as
  // it is and is said beside it: a directive the driver issued is handed to the session whatever else fails.
  private def stop(dialect: HookDialect, call: DriverCall): String = {
    val status = entry().status(call) match {
      case DriverReply.Status(value) => value
      case other => unexpected(other)
    }
    val on = status.exists(_.state == DriverState.On)
    val bound = status.flatMap(_.attached)
    val driven = bound.fold(answered(dialect, call, false, None)) { session =>
      sessions.view(session) match {
        // The host works on nothing: what the server still holds in flight is settled by its own rules, with a resume directive.
        // A drive that rests is asked to continue only then.
        case host: HostView.Running if host.standing.isEmpty => answered(dialect, call, false, None)
        case host: HostView.Running => if (on) working(dialect, call, session, host) else Answer(None, None)
        case unseen => if (on) answered(dialect, call, true, Some(unseen)) else Answer(None, None)
      }
    }
    (try sessions.owned(DriverEntry.identify(call)._1.session).orElse(bound).fold(driven)(awaited(dialect, _, driven)) catch {
      case NonFatal(error) => driven.posting(s"CQ Stop hook could not read what this session waits on a person for: ${describe(error)}")
    }).rendered
  }
}

object DriverHook {
  val MaxInputBytes: Int = 1024 * 1024
  val DriveLabel = "CQ driver drive-start"
  val ParkLabel = "CQ driver park"
  val NoDriver = "CQ driver off"
  val HostGone = "CQ driver stopped: the CQ host of this session is not running while its work was in flight, so nothing would continue the drive."
  val HostUnrecorded = "CQ driver stopped: this checkout holds no record of the CQ host of this session while its work was in flight: the host has ended, " +
    "or a CQ package without the waiter started it. Nothing would continue the drive."
  val Unwaited = "CQ driver stopped: this session was told how to wait for its running work and stopped again without waiting, so nothing would continue the drive. Still running:"
  val Settled = "CQ: a person settled what this session waits on:"
  // The ends a blocked stop says by their line. A line has at most some 800 characters (SessionAwaited.MaxTitle and MaxDetail, both
  // quoted), so these stay well below the 10,000 characters Claude Code passes to the model from one hook; that this bound holds
  // for the reason of a blocked stop as well is assumed, not established.
  val MaxSettled = 8
  private val Restart = "Restart the harness session and drive again; cq job upload --session DIR recovers what a host retained."
  private val Background = "Run exactly this command now with the Bash tool as a background command (run_in_background true, timeout 7200000), then end your turn: "
  private def command(value: Option[String]): String = s"`${value.getOrElse(throw new IllegalStateException("The CQ host of this session named no wait command"))}`"
  private val Unchanged = "Nothing changed for this session's driver and no bind token was issued."
  private def unknown(dialect: HookDialect): String = "The CQ server's reply was not received, so this session's driver may have changed and a bind token may have been issued. " +
    s"""Read the driver status with the CQ session tool ({"Driver":{}}) or run ${dialect.park} before driving again."""
  // Keeps the context block below the 10,000 characters Claude Code passes to the model from one hook.
  private val MaxListed = 25
  private val MaxTitle = 80
  private val Origins = List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop, DriverOrigin.StatusLine)
  private val Instruction = "CQ driver: run the directive on the last line verbatim as your next action, changing neither its roots, its phase nor its token."

  val Dialects: List[HookDialect] = List(
    HookDialect(Harness.Claude, "/cq:drive", "/cq:park", s"$Instruction Invoke it as the cq:advance command through the Skill tool with exactly these arguments:", true,
      (units, wait) => s"CQ driver: work of this session still runs ($units) and no cq wait runs for it, so nothing would start your next turn. " +
        Background + command(wait),
      Some((awaited, wait) => s"CQ: this session waits on $awaited and no cq wait runs for it, so nothing would start your next turn when a person settles one of them. " +
        Background + command(wait))),
    // Nothing wakes an idle Codex session when a background command exits (openai/codex#32188): it waits inside its turn, in a
    // status call of the host.
    HookDialect(Harness.Codex, "$cq-drive", "$cq-park", s"$Instruction Follow the cq-advance skill with exactly these arguments:", false,
      (units, _) => s"CQ driver: work of this session still runs ($units). Do not end your turn: nothing wakes you when it ends. " +
        s"Call the status of that work now with the CQ dispatch tool (Status, IntegrationStatus or CombinationStatus) with waitMillis ${DispatchWaits.MaxMillis}, and again while the work continues. " + DispatchWaits.CodexScript,
      None),
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
