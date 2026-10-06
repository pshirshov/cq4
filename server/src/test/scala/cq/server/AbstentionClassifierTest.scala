package cq.server

import cq.api.*
import cq.host.*
import io.circe.Json
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

final class AbstentionClassifierLocal extends AnyWordSpec {
  private def resource(name: String): String = Using.resource(getClass.getResourceAsStream(s"/harness-usage/$name.jsonl")) { stream =>
    require(stream != null, s"Missing fixture $name")
    new String(stream.readAllBytes(), UTF_8)
  }
  private def collect(harness: Harness, version: String, text: String): CollectedUsage = new HarnessUsage().collect(new ByteArrayInputStream(text.getBytes(UTF_8)),
    UsageCollectionRequest(AttemptId(UUID.randomUUID()), harness, version, UsageOrigin.Fresh, 2000, ArtifactId(UUID.randomUUID())))
  private def captured(harness: Harness, name: String): CollectedUsage = {
    val version = AbstentionClassifier.captured(harness)
    collect(harness, version, resource(s"abstention/${harness.toString.toLowerCase}-$version-$name"))
  }
  private def events(text: String): List[Json] = text.split("\n").toList.filter(_.nonEmpty).map(io.circe.parser.parse(_).toOption.get)

  import AbstentionReason.*
  // Every retained transcript: the harness, the case and the class the provider's refusal is read as; None is a failure.
  private val transcripts: List[(Harness, String, Option[AbstentionReason])] = List(
    (Harness.Claude, "credential-api-key", Some(Credential)), (Harness.Claude, "credential-oauth", Some(Credential)),
    (Harness.Claude, "rate-limit", Some(RateLimit)), (Harness.Claude, "quota-credit", Some(Quota)),
    (Harness.Claude, "quota-subscription", Some(Quota)), (Harness.Claude, "unavailable-500", Some(Unavailable)),
    (Harness.Claude, "unavailable-503", Some(Unavailable)), (Harness.Claude, "unavailable-529", Some(Unavailable)),
    (Harness.Claude, "unclassified-model-not-found", None),
    (Harness.Codex, "credential-api-key", Some(Credential)), (Harness.Codex, "credential-none", Some(Credential)),
    (Harness.Codex, "rate-limit", Some(RateLimit)), (Harness.Codex, "quota-billing", Some(Quota)),
    (Harness.Codex, "quota-usage-limit", Some(Quota)), (Harness.Codex, "unavailable-500", Some(Unavailable)),
    (Harness.Codex, "unavailable-502", Some(Unavailable)), (Harness.Codex, "unavailable-503", Some(Unavailable)),
    (Harness.Codex, "unavailable-504", Some(Unavailable)), (Harness.Codex, "unclassified-forbidden", None),
    (Harness.Codex, "unclassified-model-not-found", None),
    (Harness.Pi, "openai-credential", Some(Credential)), (Harness.Pi, "openai-rate-limit", Some(RateLimit)),
    (Harness.Pi, "openai-quota", Some(Quota)), (Harness.Pi, "openai-unavailable-500", Some(Unavailable)),
    (Harness.Pi, "openai-unavailable-502", Some(Unavailable)), (Harness.Pi, "openai-unavailable-503", Some(Unavailable)),
    (Harness.Pi, "openai-unavailable-504", Some(Unavailable)), (Harness.Pi, "openai-unclassified-forbidden", None),
    (Harness.Pi, "openai-unclassified-model-not-found", None),
    (Harness.Pi, "anthropic-credential", Some(Credential)), (Harness.Pi, "anthropic-rate-limit", Some(RateLimit)),
    (Harness.Pi, "anthropic-quota", Some(Quota)), (Harness.Pi, "anthropic-unavailable-500", Some(Unavailable)),
    (Harness.Pi, "anthropic-unavailable-503", Some(Unavailable)), (Harness.Pi, "anthropic-unavailable-529", Some(Unavailable)),
    (Harness.Pi, "anthropic-unclassified-model-not-found", None),
    (Harness.Pi, "openai-codex-quota-usage-limit", Some(Quota)), (Harness.Pi, "openai-codex-unclassified-500", None))

  "Abstention classification (Behavioral Active Blackbox; Atomic)" should {
    "read the class of each provider refusal from the transcript the harness left" in {
      transcripts.foreach { case (harness, name, expected) =>
        val usage = captured(harness, name)
        assert(usage.abstention.map(_.reason) == expected, s"$harness $name")
        // Every one of these runs ended without a result, which the collector reports whether or not the refusal has a class.
        assert(usage.nativeFailure, s"$harness $name")
        usage.abstention.foreach { value =>
          assert(value.detail.nonEmpty && value.detail.codePointCount(0, value.detail.length) <= 240 && !value.detail.exists(_.isControl), s"$harness $name")
          assert(value.text == s"Abstained (${value.reason}): ${value.detail}" && value.getMessage == value.text)
        }
      }
      assert(captured(Harness.Claude, "quota-subscription").abstention.get.detail.startsWith("You've hit your session limit"))
      assert(captured(Harness.Codex, "quota-billing").abstention.get.detail == "Quota exceeded. Check your plan and billing details.")
      assert(captured(Harness.Pi, "openai-codex-quota-usage-limit").abstention.get.detail == "You have hit your ChatGPT usage limit (plus plan).")
    }

    "classify nothing for a harness version whose refusals were not captured" in {
      transcripts.collect { case (harness, name, Some(_)) => harness -> name }.foreach { (harness, name) =>
        val version = AbstentionClassifier.captured(harness)
        HarnessUsage.versions(harness).filterNot(_ == version).foreach { other =>
          assert(collect(harness, other, resource(s"abstention/${harness.toString.toLowerCase}-$version-$name")).abstention.isEmpty, s"$harness $other $name")
        }
      }
    }

    "classify nothing for a run that completed" in {
      List(Harness.Claude -> "claude-2.1.285", Harness.Codex -> "codex-0.159.2", Harness.Pi -> "pi-0.99.1").foreach { (harness, name) =>
        val usage = collect(harness, AbstentionClassifier.captured(harness), resource(name))
        assert(usage.abstention.isEmpty && !usage.nativeFailure, name)
      }
    }

    "forget a refusal the harness recovered from" in {
      val claude = resource("abstention/claude-2.1.285-rate-limit") + events(resource("claude-2.1.285")).filterNot(_.hcursor.get[String]("subtype").contains("init")).map(_.noSpaces).mkString("", "\n", "\n")
      assert(collect(Harness.Claude, "2.1.285", claude).abstention.isEmpty)
      val codex = resource("abstention/codex-0.160.0-quota-billing") + """{"type":"turn.started"}""" + "\n"
      assert(collect(Harness.Codex, "0.160.0", codex).abstention.isEmpty)
      val refused = events(resource("abstention/pi-0.99.1-openai-rate-limit"))
      val answer = refused.reverse.find(event => event.hcursor.get[String]("type").contains("message_end") &&
        event.hcursor.downField("message").get[String]("role").contains("assistant")).get
        .mapObject(_.mapValues(_.mapObject(_.add("stopReason", Json.fromString("stop")).remove("errorMessage").add("timestamp", Json.fromLong(1)))))
      assert(collect(Harness.Pi, "0.99.1", (refused :+ answer).map(_.noSpaces).mkString("", "\n", "\n")).abstention.isEmpty)
    }

    "not read a refusal the transcript does not end in, or a class the harness did not state" in {
      val failed = events(resource("abstention/claude-2.1.285-quota-credit"))
      def without(field: String): String = failed.map(_.mapObject(_.remove(field)).noSpaces).mkString("", "\n", "\n")
      assert(collect(Harness.Claude, "2.1.285", without("is_api_error_message")).abstention.isEmpty)
      assert(collect(Harness.Claude, "2.1.285", without("terminal_reason")).abstention.isEmpty)
      assert(collect(Harness.Claude, "2.1.285", failed.init.map(_.noSpaces).mkString("", "\n", "\n")).abstention.isEmpty)
      val limited = events(resource("abstention/claude-2.1.285-quota-subscription")).filterNot(_.hcursor.get[String]("type").contains("rate_limit_event"))
      assert(collect(Harness.Claude, "2.1.285", limited.map(_.noSpaces).mkString("", "\n", "\n")).abstention.map(_.reason).contains(RateLimit))
      val other = """{"type":"thread.started","thread_id":"t"}""" + "\n" + """{"type":"turn.started"}""" + "\n" +
        """{"type":"turn.failed","error":{"message":"unexpected status 400 Bad Request: Invalid schema"}}""" + "\n"
      assert(collect(Harness.Codex, "0.160.0", other).abstention.isEmpty)
    }

    "read a job the guardian could not start as a launch abstention, and no other end as one" in {
      def record(reason: StopReason, problem: Option[String]): JobRecord = JobRecord(
        WorkspaceSpec(ProjectId(UUID.randomUUID()), SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/repository", GitCommit("0" * 40)),
        "fixture", JobTarget.Run, JobPhase.Settled, Some(JobExit(None, None, reason, 0, 0, true, false)), problem, 1, 1000, 2000)
      assert(Abstention.launch(record(StopReason.LaunchFailed, Some("exec failed: No such file"))).contains(Abstention(Launch, "exec failed: No such file")))
      assert(Abstention.launch(record(StopReason.LaunchFailed, None)).map(_.reason).contains(Launch))
      StopReason.all.filterNot(_ == StopReason.LaunchFailed).foreach(reason => assert(Abstention.launch(record(reason, None)).isEmpty, reason))
    }

    "turn a refused launch precondition into an abstention and pass every other failure on" in {
      assert(intercept[Abstention](Abstention.unless(Launch)(require(false, "refused"))) == Abstention(Launch, "refused"))
      assert(intercept[Abstention](Abstention.unless(Launch)(throw new java.io.IOException("no executable"))) == Abstention(Launch, "no executable"))
      intercept[IllegalStateException](Abstention.unless(Launch)(throw new IllegalStateException("Cancelled by the governing session")))
      assert(Abstention.unless(Unconfigured)(7) == 7)
    }
  }
}
