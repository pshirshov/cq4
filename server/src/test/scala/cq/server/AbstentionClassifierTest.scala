package cq.server

import cq.api.*
import cq.host.*
import io.circe.Json
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class AbstentionClassifierLocal extends AnyWordSpec {
  private def resource(name: String): String = Using.resource(getClass.getResourceAsStream(s"/harness-usage/$name.jsonl")) { stream =>
    require(stream != null, s"Missing fixture $name")
    new String(stream.readAllBytes(), UTF_8)
  }
  private def collect(harness: Harness, version: String, text: String): CollectedUsage = new HarnessUsage().collect(new ByteArrayInputStream(text.getBytes(UTF_8)),
    UsageCollectionRequest(AttemptId(UUID.randomUUID()), harness, version, UsageOrigin.Fresh, 2000, ArtifactId(UUID.randomUUID())))
  private def transcript(harness: Harness, version: String, name: String): String = resource(s"abstention/${harness.toString.toLowerCase}-$version-$name")
  private def captured(harness: Harness, version: String, name: String): CollectedUsage = collect(harness, version, transcript(harness, version, name))
  private def captured(harness: Harness, name: String): CollectedUsage = captured(harness, AbstentionClassifier.captured(harness).last, name)
  private def events(text: String): List[Json] = text.split("\n").toList.filter(_.nonEmpty).map(io.circe.parser.parse(_).toOption.get)

  import AbstentionReason.*
  // Every retained transcript, one per captured version of its harness: the harness, the case and the class the provider's refusal
  // is read as; None is a failure.
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
    (Harness.Pi, "openai-codex-quota-usage-limit", Some(Quota)), (Harness.Pi, "openai-codex-unavailable-500", Some(Unavailable)))

  "Abstention classification (Behavioral Active Blackbox; Atomic)" should {
    "read the class of each provider refusal from the transcript the harness left" in {
      assert(Harness.all.toList.map(AbstentionClassifier.captured) == List(List("2.1.285"), List("0.160.0"), List("0.99.1", "1.0.0")))
      // Every retained transcript is listed above with its class: a file this list does not name, or names without having, fails here.
      def names(directory: java.nio.file.Path): Set[String] = Using.resource(java.nio.file.Files.list(directory))(_.iterator().asScala.map(_.getFileName.toString).toSet)
      val location = getClass.getResource("/harness-usage/abstention").toURI
      // The test resources are read from a directory or from the archive the build packs them into.
      val retained = if (location.getScheme != "jar") names(java.nio.file.Path.of(location))
        else Using.resource(java.nio.file.FileSystems.newFileSystem(location, java.util.Map.of[String, AnyRef]()))(archive => names(archive.getPath("/harness-usage/abstention")))
      val listed = (for ((harness, name, _) <- transcripts; version <- AbstentionClassifier.captured(harness)) yield s"${harness.toString.toLowerCase}-$version-$name.jsonl").toSet
      assert(retained == listed, s"not listed: ${(retained -- listed).toList.sorted}; not retained: ${(listed -- retained).toList.sorted}")
      // The name of a transcript says how it is read.
      transcripts.foreach { (harness, name, expected) =>
        val stated = List("credential" -> Credential, "rate-limit" -> RateLimit, "quota" -> Quota, "unavailable" -> Unavailable).collectFirst { case (word, reason) if name.contains(word) => reason }
        assert(stated == expected && name.contains("unclassified") == expected.isEmpty, s"$harness $name")
      }
      for ((harness, name, expected) <- transcripts; version <- AbstentionClassifier.captured(harness)) {
        val usage = captured(harness, version, name)
        assert(usage.abstention.map(_.reason) == expected, s"$harness $version $name")
        // Every one of these runs ended without a result, which the collector reports whether or not the refusal has a class.
        assert(usage.nativeFailure, s"$harness $version $name")
        usage.abstention.foreach { value =>
          assert(value.detail.nonEmpty && value.detail.codePointCount(0, value.detail.length) <= 240 && !value.detail.exists(_.isControl), s"$harness $version $name")
          assert(value.text == s"Abstained (${value.reason}): ${value.detail}" && value.getMessage == value.text)
        }
      }
      assert(captured(Harness.Claude, "quota-subscription").abstention.get.detail.startsWith("You've hit your session limit"))
      assert(captured(Harness.Codex, "quota-billing").abstention.get.detail == "Quota exceeded. Check your plan and billing details.")
      AbstentionClassifier.captured(Harness.Pi).foreach(version =>
        assert(captured(Harness.Pi, version, "openai-codex-quota-usage-limit").abstention.get.detail == "You have hit your ChatGPT usage limit (plus plan)."))
    }

    "classify nothing for a harness version whose refusals were not captured" in {
      for (case (harness, name, Some(_)) <- transcripts; version <- AbstentionClassifier.captured(harness);
          other <- HarnessUsage.versions(harness).filterNot(AbstentionClassifier.captured(harness).contains))
        assert(collect(harness, other, transcript(harness, version, name)).abstention.isEmpty, s"$harness $other $name")
    }

    "classify nothing for a run that completed" in {
      // A completed Codex run is retained for 0.159.2 only. That version's refusals were not captured, so nothing classifies there;
      // the same transcript read as the captured 0.160.0, whose events it shares, is what exercises the classifier on a completed run.
      List((Harness.Claude, "2.1.285", "claude-2.1.285"), (Harness.Codex, "0.159.2", "codex-0.159.2"), (Harness.Codex, "0.160.0", "codex-0.159.2"),
        (Harness.Pi, "0.99.1", "pi-0.99.1"), (Harness.Pi, "1.0.0", "pi-1.0.0-stub")).foreach { (harness, version, name) =>
        assert(HarnessUsage.verified(harness, version), s"$harness $version")
        val usage = collect(harness, version, resource(name))
        assert(usage.abstention.isEmpty && !usage.nativeFailure, s"$name as $version")
      }
      assert(!AbstentionClassifier.captured(Harness.Codex).contains("0.159.2") && AbstentionClassifier.captured(Harness.Codex).contains("0.160.0"))
    }

    "forget a refusal the harness recovered from" in {
      val claude = resource("abstention/claude-2.1.285-rate-limit") + events(resource("claude-2.1.285")).filterNot(_.hcursor.get[String]("subtype").contains("init")).map(_.noSpaces).mkString("", "\n", "\n")
      assert(collect(Harness.Claude, "2.1.285", claude).abstention.isEmpty)
      val codex = resource("abstention/codex-0.160.0-quota-billing") + """{"type":"turn.started"}""" + "\n"
      assert(collect(Harness.Codex, "0.160.0", codex).abstention.isEmpty)
      AbstentionClassifier.captured(Harness.Pi).foreach { version =>
        val refused = events(transcript(Harness.Pi, version, "openai-rate-limit"))
        val answer = refused.reverse.find(event => event.hcursor.get[String]("type").contains("message_end") &&
          event.hcursor.downField("message").get[String]("role").contains("assistant")).get
          .mapObject(_.mapValues(_.mapObject(_.add("stopReason", Json.fromString("stop")).remove("errorMessage").add("timestamp", Json.fromLong(1)))))
        assert(collect(Harness.Pi, version, (refused :+ answer).map(_.noSpaces).mkString("", "\n", "\n")).abstention.isEmpty)
        // A refusal Pi itself retried past, as the stub provider's first reply was: the transcript ends in the answer.
        val recovered = collect(Harness.Pi, version, resource(s"pi-$version-stub-recovered-retry"))
        assert(recovered.abstention.isEmpty && !recovered.nativeFailure, version)
      }
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

    "read no class from a refusal that only resembles a captured one" in {
      // Claude: the class counts only on a synthetic API-error message, in a result that failed for an API error.
      val claude = events(resource("abstention/claude-2.1.285-quota-credit"))
      def claudeWith(change: Json => Json): Option[AbstentionReason] =
        collect(Harness.Claude, "2.1.285", claude.map(change).map(_.noSpaces).mkString("", "\n", "\n")).abstention.map(_.reason)
      def set(kind: String, field: String, value: Json)(event: Json): Json =
        if (event.hcursor.get[String]("type").contains(kind) && event.hcursor.downField(field).succeeded) event.mapObject(_.add(field, value)) else event
      assert(claudeWith(identity).contains(Quota))
      assert(claudeWith(set("assistant", "is_api_error_message", Json.False)).isEmpty)
      assert(claudeWith(set("assistant", "error", Json.fromString("invalid_request"))).isEmpty)
      assert(claudeWith(set("result", "is_error", Json.False)).isEmpty)
      assert(claudeWith(set("result", "terminal_reason", Json.fromString("completed"))).isEmpty)
      // Codex: the whole text of the failed turn is the pattern.
      def codexWith(message: String): Option[AbstentionReason] = collect(Harness.Codex, "0.160.0", """{"type":"thread.started","thread_id":"t"}""" + "\n" +
        """{"type":"turn.started"}""" + "\n" + Json.obj("type" -> Json.fromString("turn.failed"), "error" -> Json.obj("message" -> Json.fromString(message))).noSpaces + "\n").abstention.map(_.reason)
      assert(codexWith("unexpected status 401 Unauthorized: Missing bearer").contains(Credential) && codexWith("unexpected status 401 Unauthorized").isEmpty)
      assert(codexWith("unexpected status 403 Forbidden: Country not supported").isEmpty)
      assert(codexWith("Quota exceeded. Check your plan and billing details.").contains(Quota) && codexWith("Quota exceeded.").isEmpty)
      assert(codexWith("You’ve hit your usage limit. Try again later.").contains(Quota) && codexWith("You've hit your usage limit. Try again later.").isEmpty)
      assert(codexWith("exceeded retry limit, last status: 429 Too Many Requests").contains(RateLimit) &&
        codexWith("exceeded retry limit, last status: 400 Bad Request").isEmpty)
      assert(codexWith("unexpected status 503 Service Unavailable: upstream").contains(Unavailable) && codexWith("unexpected status 501 Not Implemented: upstream").isEmpty &&
        codexWith("unexpected status 503 Service Unavailable").isEmpty)
      assert(codexWith("We’re currently experiencing high demand, which may cause temporary errors.").contains(Unavailable) &&
        codexWith("We’re currently experiencing high demand.").isEmpty)
      // Pi: the backend, the status and the provider's own error class together.
      AbstentionClassifier.captured(Harness.Pi).foreach { version =>
        def piWith(base: String, api: String, error: String): Option[AbstentionReason] = collect(Harness.Pi, version, events(transcript(Harness.Pi, version, base)).map { event =>
          val message = event.hcursor.downField("message")
          if (event.hcursor.get[String]("type").contains("message_end") && message.get[String]("role").contains("assistant"))
            event.mapObject(_.add("message", message.focus.get.mapObject(_.add("api", Json.fromString(api)).add("errorMessage", Json.fromString(error)))))
          else event
        }.map(_.noSpaces).mkString("", "\n", "\n")).abstention.map(_.reason)
        def anthropic(status: String, kind: String, message: String): Option[AbstentionReason] =
          piWith("anthropic-credential", "anthropic-messages", s"""$status {"type":"error","error":{"type":"$kind","message":"$message"}}""")
        assert(anthropic("401", "authentication_error", "invalid x-api-key").contains(Credential) && anthropic("401", "permission_error", "invalid x-api-key").isEmpty &&
          anthropic("403", "authentication_error", "invalid x-api-key").isEmpty, version)
        assert(anthropic("429", "rate_limit_error", "slow down").contains(RateLimit) && anthropic("429", "invalid_request_error", "slow down").isEmpty, version)
        assert(anthropic("400", "invalid_request_error", "Your credit balance is too low to access the API").contains(Quota) &&
          anthropic("400", "invalid_request_error", "max_tokens: field required").isEmpty, version)
        assert(anthropic("500", "api_error", "internal").contains(Unavailable) && anthropic("529", "overloaded_error", "overloaded").contains(Unavailable) &&
          anthropic("500", "invalid_request_error", "internal").isEmpty && anthropic("529", "api_error", "overloaded").isEmpty, version)
        def openai(status: String, code: String, kind: String): Option[AbstentionReason] =
          piWith("openai-credential", "openai-responses", s"""OpenAI API error ($status): {"message":"m","type":"$kind","param":null,"code":"$code"}""")
        assert(openai("401", "invalid_api_key", "invalid_request_error").contains(Credential) && openai("401", "model_not_found", "invalid_request_error").isEmpty, version)
        assert(openai("429", "rate_limit_exceeded", "requests").contains(RateLimit) && openai("429", "insufficient_quota", "insufficient_quota").contains(Quota) &&
          openai("429", "other", "requests").isEmpty && openai("400", "rate_limit_exceeded", "requests").isEmpty, version)
        assert(openai("503", "x", "server_error").contains(Unavailable) && openai("503", "x", "invalid_request_error").isEmpty && openai("501", "x", "server_error").isEmpty, version)
        // The same text under another backend is no pattern of that backend.
        val limit = "You have hit your ChatGPT usage limit (plus plan)."
        val server = "The server had an error while processing your request. Sorry about that!"
        assert(piWith("openai-credential", "openai-codex-responses", limit).contains(Quota) && piWith("openai-credential", "openai-responses", limit).isEmpty &&
          piWith("openai-credential", "openai-codex-responses", "You have hit a usage limit.").isEmpty, version)
        assert(piWith("openai-credential", "openai-codex-responses", server).contains(Unavailable) && piWith("openai-credential", "openai-responses", server).isEmpty &&
          piWith("openai-credential", "openai-codex-responses", "The server had an error while processing your request.").isEmpty, version)
      }
    }

    def record(reason: StopReason, problem: Option[String]): JobRecord = JobRecord(
      WorkspaceSpec(ProjectId(UUID.randomUUID()), SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/repository", GitCommit("0" * 40)),
      "fixture", JobTarget.Run, JobPhase.Settled, Some(JobExit(None, None, reason, 0, 0, true, false)), problem, 1, 1000, 2000)

    "read a job the guardian could not start as a launch abstention, and no other end as one" in {
      assert(Abstention.launch(record(StopReason.LaunchFailed, Some("exec failed: No such file"))).contains(Abstention(Launch, "exec failed: No such file")))
      assert(Abstention.launch(record(StopReason.LaunchFailed, None)).map(_.reason).contains(Launch))
      StopReason.all.filterNot(_ == StopReason.LaunchFailed).foreach(reason => assert(Abstention.launch(record(reason, None)).isEmpty, reason))
    }

    "count a provider's refusal only for a job that ended by itself and whose end is known" in {
      val refusal = Some(Abstention(Quota, "Quota exceeded. Check your plan and billing details."))
      def job(reason: StopReason, phase: JobPhase, settled: Boolean, hostFailure: Boolean): JobRecord = {
        val base = record(reason, None)
        base.copy(phase = phase, exit = base.exit.map(_.copy(code = Some(1), settled = settled, hostFailure = hostFailure)))
      }
      val exited = job(StopReason.Exited, JobPhase.Settled, true, false)
      assert(Abstention.provider(exited, refusal) == refusal && Abstention.provider(exited, None).isEmpty)
      // A job the host stopped is judged by how it was stopped, although its output ends in the same refusal.
      StopReason.all.filterNot(_ == StopReason.Exited).foreach(reason =>
        assert(Abstention.provider(job(reason, JobPhase.Settled, true, false), refusal).isEmpty, reason))
      // So is a job whose end is not known: an uncertain phase, an exit that is not settled, a failure of the host, no exit at all.
      assert(Abstention.provider(job(StopReason.Exited, JobPhase.Uncertain, true, false), refusal).isEmpty)
      assert(Abstention.provider(job(StopReason.Exited, JobPhase.Settled, false, false), refusal).isEmpty)
      assert(Abstention.provider(job(StopReason.Exited, JobPhase.Settled, true, true), refusal).isEmpty)
      assert(Abstention.provider(exited.copy(exit = None), refusal).isEmpty)
    }

    "turn a refused route into an abstention and pass every other failure on" in {
      assert(intercept[Abstention](Abstention.unless(Launch)(RouteRefusal.unless(false, "refused"))) == Abstention(Launch, "refused"))
      // A requirement that is none of the route's and a fault of the host are failures with their own text.
      assert(intercept[IllegalArgumentException](Abstention.unless(Launch)(require(false, "not of the route"))).getMessage == "requirement failed: not of the route")
      assert(intercept[java.io.IOException](Abstention.unless(Launch)(throw new java.io.IOException("No space left on device"))).getMessage == "No space left on device")
      intercept[IllegalStateException](Abstention.unless(Launch)(throw new IllegalStateException("Cancelled by the governing session")))
      assert(Abstention.unless(Unconfigured)(7) == 7)
    }
  }
}
