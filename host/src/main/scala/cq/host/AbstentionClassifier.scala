package cq.host

import cq.api.*
import io.circe.Json

/** A route, or the settings entry of its harness, that cannot be launched as it is configured: nothing of the work caused it, and
  * another route may run the same input. Its message has the form of a refused requirement. */
final class RouteRefusal(message: String) extends IllegalArgumentException("requirement failed: " + message)
object RouteRefusal {
  def unless(condition: Boolean, message: => String): Unit = if (!condition) throw new RouteRefusal(message)
}

/** An attempt that could not run its model for a reason that is none of the work's: another model may run the same input. */
final case class Abstention(reason: AbstentionReason, detail: String) extends RuntimeException(Abstention.text(reason, detail)) {
  def text: String = Abstention.text(reason, detail)
}
object Abstention {
  def text(reason: AbstentionReason, detail: String): String = s"Abstained ($reason): $detail"
  private def message(error: Throwable): String = Option(error.getMessage).fold(error.getClass.getSimpleName)(_.stripPrefix("requirement failed: "))
  /** Runs one step that precedes the launch. A refusal of the route or of its settings entry is an abstention of `reason`; any other
    * fault of the step, an I/O fault of the host included, is a failure of the attempt with its own text. */
  def unless[A](reason: AbstentionReason)(step: => A): A = try step catch {
    case error: RouteRefusal => throw Abstention(reason, message(error))
  }
  /** The provider's refusal as the end of a job. It counts only when the harness ended by itself: a job that was stopped, by a
    * cancellation, a deadline or the host, and a job whose end is not known are judged by how they ended, whatever their output says. */
  def provider(record: JobRecord, refusal: Option[Abstention]): Option[Abstention] =
    refusal.filter(_ => JobOutcome.observed(record).state != AttemptState.Unknown && record.exit.exists(_.reason == StopReason.Exited))
  /** A job that the guardian could not start ran nothing. */
  def launch(record: JobRecord): Option[Abstention] = record.exit.filter(_.reason == StopReason.LaunchFailed)
    .map(_ => Abstention(AbstentionReason.Launch, record.problem.getOrElse("The harness process could not be started")))
}

/**
 * Reads from a harness's native events whether its provider refused the session, and in which class. The patterns are those of
 * transcripts captured from the versions named in `captured` and retained under the test resources `harness-usage/abstention`;
 * another version, and any refusal those transcripts do not show, is not classified, and the attempt is then a failure.
 */
final class AbstentionClassifier(harness: Harness, version: String) {
  import AbstentionClassifier.*
  private val known = captured(harness).contains(version)
  private var verdict = Option.empty[Abstention]
  // Claude: the class of the last synthetic assistant message that reports an API error, and whether a plan window was rejected.
  private var claudeError = Option.empty[String]
  private var claudeRejected = false

  def accept(event: Json): Unit = if (known) {
    val cursor = event.hcursor
    val kind = cursor.get[String]("type").getOrElse("")
    harness match {
      case Harness.Claude => kind match {
        case "rate_limit_event" => claudeRejected = cursor.downField("rate_limit_info").get[String]("status").contains("rejected")
        case "assistant" =>
          verdict = None
          claudeError = cursor.get[String]("error").toOption.filter(_ => cursor.get[Boolean]("is_api_error_message").contains(true))
        case "result" =>
          val refused = cursor.get[Boolean]("is_error").contains(true) && cursor.get[String]("terminal_reason").contains("api_error")
          verdict = claudeError.filter(_ => refused).flatMap(error => claude(error, claudeRejected))
            .map(Abstention(_, detail(cursor.get[String]("result").getOrElse(""))))
        case _ => ()
      }
      case Harness.Codex => kind match {
        case "turn.started" | "turn.completed" => verdict = None
        case "turn.failed" =>
          val message = cursor.downField("error").get[String]("message").getOrElse("")
          verdict = codex(message).map(Abstention(_, detail(message)))
        case _ => ()
      }
      case Harness.Pi => if (kind == "message_end") {
        val message = cursor.downField("message")
        if (message.get[String]("role").contains("assistant")) {
          val text = message.get[String]("errorMessage").getOrElse("")
          verdict = Option.when(message.get[String]("stopReason").contains("error"))(text)
            .flatMap(pi(message.get[String]("api").getOrElse(""), _)).map(Abstention(_, detail(text)))
        }
      }
    }
  }

  /** The refusal the native output ended in; a refusal the harness recovered from is none. */
  def result: Option[Abstention] = verdict
}

object AbstentionClassifier {
  private val MaxDetailCodePoints = 240
  /** The versions of each harness whose refusals were captured, oldest first. One set of patterns reads all versions of a harness:
    * the transcripts of Pi 1.0.0 state every captured refusal as those of Pi 0.99.1 do. */
  def captured(harness: Harness): List[String] = harness match {
    case Harness.Claude => List("2.1.285")
    case Harness.Codex => List("0.160.0")
    case Harness.Pi => List("0.99.1", "1.0.0")
  }
  private def detail(text: String): String = {
    val line = text.filterNot(_.isControl).trim
    if (line.isEmpty) "the harness stated no detail" else line.substring(0, line.offsetByCodePoints(0, line.codePointCount(0, line.length).min(MaxDetailCodePoints)))
  }

  // Claude Code names the class itself. `rate_limit` covers a request-rate refusal and an exhausted plan window; only the second
  // is preceded by a rate-limit event whose status is `rejected`.
  private def claude(error: String, rejected: Boolean): Option[AbstentionReason] = error match {
    case "authentication_failed" => Some(AbstentionReason.Credential)
    case "billing_error" => Some(AbstentionReason.Quota)
    case "rate_limit" => Some(if (rejected) AbstentionReason.Quota else AbstentionReason.RateLimit)
    case "server_error" => Some(AbstentionReason.Unavailable)
    case _ => None
  }

  // Codex states a refusal as the text of its failed turn only.
  private val CodexUnavailable = "unexpected status (502 Bad Gateway|503 Service Unavailable|504 Gateway Timeout): .*".r
  private def codex(message: String): Option[AbstentionReason] = message match {
    case text if text.startsWith("unexpected status 401 Unauthorized: ") => Some(AbstentionReason.Credential)
    case "Quota exceeded. Check your plan and billing details." => Some(AbstentionReason.Quota)
    case text if text.startsWith("You’ve hit your usage limit.") => Some(AbstentionReason.Quota)
    case "exceeded retry limit, last status: 429 Too Many Requests" => Some(AbstentionReason.RateLimit)
    case "We’re currently experiencing high demand, which may cause temporary errors." => Some(AbstentionReason.Unavailable)
    case CodexUnavailable(_) => Some(AbstentionReason.Unavailable)
    case _ => None
  }

  // Pi passes on the provider's reply: the HTTP status and the error body for the Anthropic and OpenAI APIs, and for the ChatGPT
  // backend its own sentence for an exhausted plan and the backend's sentence for a server error, which carries no status.
  private val PiCodexServerError = "The server had an error while processing your request. Sorry about that!"
  private val PiAnthropic = "(\\d{3}) (\\{.*\\})".r
  private val PiOpenAi = "OpenAI API error \\((\\d{3})\\): (\\{.*\\})".r
  private def field(body: String, path: String*): Option[String] =
    io.circe.parser.parse(body).toOption.flatMap(json => path.foldLeft(json.hcursor: io.circe.ACursor)(_.downField(_)).as[String].toOption)
  private def pi(api: String, text: String): Option[AbstentionReason] = (api, text) match {
    case ("anthropic-messages", PiAnthropic(status, body)) => (status, field(body, "error", "type")) match {
      case ("401", Some("authentication_error")) => Some(AbstentionReason.Credential)
      case ("429", Some("rate_limit_error")) => Some(AbstentionReason.RateLimit)
      case ("400", Some("invalid_request_error")) if field(body, "error", "message").exists(_.startsWith("Your credit balance is too low")) => Some(AbstentionReason.Quota)
      case ("500" | "503", Some("api_error")) | ("529", Some("overloaded_error")) => Some(AbstentionReason.Unavailable)
      case _ => None
    }
    case ("openai-responses", PiOpenAi(status, body)) => (status, field(body, "code"), field(body, "type")) match {
      case ("401", Some("invalid_api_key"), _) => Some(AbstentionReason.Credential)
      case ("429", Some("rate_limit_exceeded"), _) => Some(AbstentionReason.RateLimit)
      case ("429", Some("insufficient_quota"), _) => Some(AbstentionReason.Quota)
      case ("500" | "502" | "503" | "504", _, Some("server_error")) => Some(AbstentionReason.Unavailable)
      case _ => None
    }
    case ("openai-codex-responses", sentence) if sentence.startsWith("You have hit your ChatGPT usage limit") => Some(AbstentionReason.Quota)
    case ("openai-codex-responses", PiCodexServerError) => Some(AbstentionReason.Unavailable)
    case _ => None
  }
}
