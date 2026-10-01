package cq.server

import cq.api.*
import cq.core.UsageMath
import cq.host.*
import io.circe.Json
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

final class HarnessUsageLocal extends AnyWordSpec {
  private def request(harness: Harness): UsageCollectionRequest = UsageCollectionRequest(AttemptId(UUID.randomUUID()), harness,
    HarnessUsage.version(harness), UsageOrigin.Fresh, 2000, ArtifactId(UUID.randomUUID()))
  private def collect(text: String, request: UsageCollectionRequest): CollectedUsage =
    new HarnessUsage().collect(new ByteArrayInputStream(text.getBytes(UTF_8)), request)
  private def fixture(harness: Harness): String = resource(harness.toString.toLowerCase)
  private def fixture(harness: Harness, version: String): String = resource(harness.toString.toLowerCase + "-" + version)
  private def resource(name: String): String = Using.resource(getClass.getResourceAsStream(s"/harness-usage/$name.jsonl")) { stream =>
    require(stream != null, s"Missing fixture $name")
    new String(stream.readAllBytes(), UTF_8)
  }
  private def events(text: String): List[Json] = text.split("\n").toList.filter(_.nonEmpty).map(io.circe.parser.parse(_).toOption.get)
  private def stream(events: List[Json]): String = events.map(_.noSpaces).mkString("", "\n", "\n")
  private def change(json: Json, field: String, value: Json): Json = json.mapObject(_.add(field, value))
  private def total(report: CollectedUsage): Long = report.meters.map { batch =>
    val observations = if (batch.meter.scope == CounterScope.Cumulative) batch.observations.takeRight(1) else batch.observations
    observations.map { upload =>
      val normalized = UsageMath.normalize(upload.observation)
      val counts = if (batch.meter.scope == CounterScope.Cumulative) UsageMath.since(normalized, batch.meter.baseline) else normalized
      UsageMath.totals(counts, UsageMath.unknownMoney).total.known
    }.sum
  }.sum

  "Harness usage collectors (Behavioral Active Blackbox Group)" should {
    "accept the installed harness versions and collect their retained observations and final results" in {
      val installed = List(
        (Harness.Claude, "2.1.285", 1102L, Some("0.010548"), "Claude/2.1.285/model=claude-opus-5-5"),
        (Harness.Codex, "0.159.2", 13126L, None, "Codex/0.159.2"),
        (Harness.Pi, "0.99.1", 78L, Some("0.000615"), "Pi/0.99.1/openai-codex/gpt-5.5"))
      val assets = Files.createTempDirectory("cq-harness-result-").toAbsolutePath
      try {
        Files.writeString(assets.resolve("last-message.json"), "{\"reply\":\"OK\"}")
        installed.foreach { case (harness, version, tokens, amount, source) =>
          val profile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model",
            if (harness == Harness.Claude) "anthropic" else "selected-provider", version, Nil, Set.empty)
          assert(profile.version == version)
          val input = request(harness).copy(version = version)
          val native = fixture(harness, version)
          val report = collect(native, input)
          assert(report.terminalSeen && !report.nativeFailure, s"$harness $version: ${report.gaps}")
          assert(report.meters.size == 1 && report.meters.head.observations.size == 1, s"$harness $version: ${report.gaps}")
          assert(total(report) == tokens)
          val observation = report.meters.head.observations.head.observation
          assert(observation.source == source && observation.cost.amount.map(_.value) == amount)
          assert(new HarnessOutput().result(harness, native.getBytes(UTF_8), assets) == Json.obj("reply" -> Json.fromString("OK")))
        }
      } finally { Files.deleteIfExists(assets.resolve("last-message.json")); Files.deleteIfExists(assets) }
    }

    "collect retained installed-harness observations with their native counter scopes and cost provenance" in {
      val expected = List((Harness.Claude, 424L, Some("0.00176")), (Harness.Codex, 18237L, None), (Harness.Pi, 68L, Some("0.00084")))
      expected.foreach { case (harness, tokens, amount) =>
        val input = request(harness)
        val report = collect(fixture(harness), input)
        assert(report.terminalSeen && !report.nativeFailure)
        assert(report.meters.size == 1 && report.meters.head.observations.size == 1)
        assert(total(report) == tokens)
        val observation = report.meters.head.observations.head.observation
        assert(observation.cost.amount.map(_.value) == amount && observation.evidence.contains(input.evidence))
        assert(observation.receivedAt == 0 && observation.occurredAt == input.collectedAt)
        assert(observation.scope == (if (harness == Harness.Pi) CounterScope.Increment else CounterScope.Cumulative))
        assert(observation.completeness == UsageCompleteness.Partial)
        assert(collect(fixture(harness), input) == report)
      }
    }

    "normalize nonzero cache and reasoning subsets without double-counting cumulative turns" in {
      val native = events(fixture(Harness.Codex))
      val usage = Json.obj("input_tokens" -> Json.fromLong(200), "output_tokens" -> Json.fromLong(50),
        "cached_input_tokens" -> Json.fromLong(80), "cache_write_input_tokens" -> Json.fromLong(20), "reasoning_output_tokens" -> Json.fromLong(30))
      val second = Json.obj("type" -> Json.fromString("turn.completed"), "usage" -> usage)
      val report = collect(stream(native.take(1) ++ List(second, change(second, "usage", change(usage, "input_tokens", Json.fromLong(300))), second)), request(Harness.Codex))
      assert(report.meters.head.observations.size == 2 && report.gaps.exists(_.contains("Cumulative")))
      val counts = UsageMath.normalize(report.meters.head.observations.head.observation)
      assert(counts.input.value.contains(200) && counts.output.value.contains(50) && counts.reasoning.value.contains(30))
      val monotonic = collect(stream(native.take(1) ++ List(second, change(second, "usage", change(usage, "input_tokens", Json.fromLong(300))))), request(Harness.Codex))
      assert(total(monotonic) == 350)
    }

    "keep a later unfinished turn incomplete after an earlier completed turn" in {
      List((Harness.Claude, "assistant"), (Harness.Codex, "turn.started"), (Harness.Pi, "turn_start")).foreach { case (harness, event) =>
        val report = collect(fixture(harness) + Json.obj("type" -> Json.fromString(event)).noSpaces + "\n", request(harness))
        assert(!report.terminalSeen, s"$harness later unfinished turn must invalidate prior completion")
        assert(report.gaps.exists(_.contains("No final native completion")))
      }
    }

    "detect a cumulative decrease even when an intervening sample has missing counters" in {
      val native = events(fixture(Harness.Codex))
      val missing = Json.obj("type" -> Json.fromString("turn.completed"), "usage" -> Json.obj())
      val lower = change(native.last, "usage", Json.obj("input_tokens" -> Json.fromLong(20), "output_tokens" -> Json.fromLong(1)))
      val report = collect(stream(native ++ List(missing, lower)), request(Harness.Codex))
      assert(report.meters.head.observations.size == 2 && report.gaps.exists(_.contains("Cumulative")))
    }

    "retain late cumulative costs when the first Claude result lacks a cost field" in {
      val native = events(fixture(Harness.Claude))
      val result = native.last
      val modelUsage = result.hcursor.downField("modelUsage").focus.get
      val early = change(result, "modelUsage", modelUsage.mapObject(_.mapValues(_.mapObject(_.remove("costUSD")))))
      val report = collect(stream(native.dropRight(1) ++ List(early, result)), request(Harness.Claude))
      val batch = report.meters.head
      assert(UsageMath.since(batch.observations.last.observation.cost, batch.meter.baselineCost).amount.contains(DecimalAmount("0.00176")))
    }

    "exclude Pi's repeated partial/turn/run aggregates and deduplicate repeated finalized responses" in {
      val native = events(fixture(Harness.Pi))
      val response = native.find(e => e.hcursor.get[String]("type").contains("message_end") && e.hcursor.downField("message").get[String]("role").contains("assistant")).get
      val duplicate = collect(stream(native ++ List(response)), request(Harness.Pi))
      assert(total(duplicate) == 68 && duplicate.meters.head.observations.size == 1)
      val nextTurn = Json.obj("type" -> Json.fromString("turn_start"))
      val newResponse = change(response, "message", change(response.hcursor.downField("message").focus.get, "responseId", Json.fromString("different-response")))
      val distinct = collect(stream(native ++ List(nextTurn, newResponse)), request(Harness.Pi))
      assert(total(distinct) == 136)
      val message = response.hcursor.downField("message").focus.get
      val usage = message.hcursor.downField("usage").focus.get
      val conflicted = change(response, "message", change(message, "usage", change(usage, "reasoning", Json.fromLong(14))))
      val conflict = collect(stream(native ++ List(conflicted)), request(Harness.Pi))
      assert(total(conflict) == 68 && conflict.gaps.exists(_.contains("Conflicting repeated")))
    }

    "deduplicate Pi response identities across repeated turn wrappers" in {
      val native = events(fixture(Harness.Pi))
      val response = native.find(e => e.hcursor.get[String]("type").contains("message_end") && e.hcursor.downField("message").get[String]("role").contains("assistant")).get
      val report = collect(stream(native ++ List(Json.obj("type" -> Json.fromString("turn_start")), response)), request(Harness.Pi))
      assert(total(report) == 68 && report.meters.head.observations.size == 1)
    }

    "preserve Pi's reported input and output when its native total field is a zero default" in {
      val native = events(fixture(Harness.Pi)).map { event =>
        if (event.hcursor.get[String]("type").contains("message_end") && event.hcursor.downField("message").get[String]("role").contains("assistant")) {
          val message = event.hcursor.downField("message").focus.get
          change(event, "message", change(message, "usage", change(message.hcursor.downField("usage").focus.get, "totalTokens", Json.fromLong(0))))
        } else event
      }
      val report = collect(stream(native), request(Harness.Pi))
      assert(total(report) == 68)
      assert(report.meters.head.observations.head.observation.gaps.exists(_.contains("totalTokens")))
    }

    "require captured baselines for resumed cumulative meters and retain exact monetary deltas" in {
      val input = request(Harness.Claude)
      val fresh = collect(fixture(Harness.Claude), input)
      val unknown = collect(fixture(Harness.Claude), input.copy(origin = UsageOrigin.Resumed(Nil)))
      assert(total(unknown) == 0 && unknown.gaps.exists(_.contains("no captured baseline")))
      val baseline = fresh.meters.head.meter.copy(baseline = UsageMath.zeroCounts.copy(input = Counter(Some(100), Measurement.Observed)),
        baselineCost = Money(Some(DecimalAmount("0.001")), Some("USD"), CostBasis.ProviderEstimate, None))
      val resumed = collect(fixture(Harness.Claude), input.copy(origin = UsageOrigin.Resumed(List(baseline))))
      assert(total(resumed) == 324)
      val batch = resumed.meters.head
      assert(UsageMath.since(batch.observations.last.observation.cost, batch.meter.baselineCost).amount.contains(DecimalAmount("0.00076")))
    }

    "preserve unknown zero defaults, missing fields, failed runs and auxiliary coverage gaps" in {
      val native = events(fixture(Harness.Codex))
      val zero = Json.obj("type" -> Json.fromString("turn.completed"), "usage" -> Json.obj(
        "input_tokens" -> Json.fromLong(0), "output_tokens" -> Json.fromLong(0)))
      val report = collect(stream(native.take(1) ++ List(zero, Json.obj("type" -> Json.fromString("turn.failed")))), request(Harness.Codex))
      val observation = report.meters.head.observations.head.observation
      assert(observation.counters == UsageMath.missingCounts && observation.completeness == UsageCompleteness.Unavailable)
      assert(observation.cost == UsageMath.unknownMoney && report.nativeFailure)
      assert(collect(fixture(Harness.Pi), request(Harness.Pi)).gaps.exists(_.contains("auxiliary")))
    }

    "bound malformed, oversized and truncated native output while retaining earlier usage" in {
      val input = request(Harness.Codex)
      val prefix = fixture(Harness.Codex)
      val invalid = collect(prefix + "not json\n" + "x" * (HarnessUsage.MaxLineBytes + 1) + "\n" + "{", input)
      assert(total(invalid) == 18237 && invalid.gaps.exists(_.contains("Invalid native JSON")) && invalid.gaps.exists(_.contains("line bound")) && invalid.gaps.exists(_.contains("line terminator")))
      val badUnicode = new ByteArrayInputStream(prefix.getBytes(UTF_8) ++ Array[Byte](0xc0.toByte, 0x80.toByte, 10))
      val utf8 = new HarnessUsage().collect(badUnicode, input)
      assert(total(utf8) == 18237 && utf8.gaps.exists(_.contains("Invalid UTF-8")))
      val separator = "{\"type\":\"ignored\",\"text\":\"a\u2028b\u2029c\"}\n"
      assert(collect(separator + prefix, input).gaps == collect(prefix, input).gaps)
      val incomplete = collect(stream(events(prefix).take(1)), input)
      assert(!incomplete.terminalSeen && incomplete.gaps.exists(_.contains("No authoritative")))
    }

    "reject invalid token relationships, version drift and mixed session identities" in {
      val native = events(fixture(Harness.Codex))
      val bad = Json.obj("type" -> Json.fromString("turn.completed"), "usage" -> Json.obj(
        "input_tokens" -> Json.fromLong(20), "output_tokens" -> Json.fromLong(2), "reasoning_output_tokens" -> Json.fromLong(3)))
      val report = collect(stream(native.take(1) ++ List(bad)), request(Harness.Codex))
      assert(report.meters.isEmpty && report.gaps.exists(_.contains("Inconsistent")))
      val mixed = collect(stream(native ++ List(change(native.head, "thread_id", Json.fromString("different")), native.last)), request(Harness.Codex))
      assert(total(mixed) == 18237 && mixed.gaps.exists(_.contains("identity changed")))
      intercept[IllegalArgumentException](collect(fixture(Harness.Codex), request(Harness.Codex).copy(version = "unverified")))
    }

    "reject exponential monetary expansion before allocating its decimal representation" in {
      List("1e100000000", "1e-100000000", "-1", "1e64").foreach { amount =>
        val native = fixture(Harness.Claude).replace("\"costUSD\":0.00176", s"\"costUSD\":$amount")
        val report = collect(native, request(Harness.Claude))
        assert(report.meters.isEmpty && report.gaps.exists(message => message.contains("cost") || message.contains("numeric")))
      }
      val boundary = collect(fixture(Harness.Claude).replace("\"costUSD\":0.00176", "\"costUSD\":1e63"), request(Harness.Claude))
      assert(boundary.meters.head.observations.head.observation.cost.amount.get.value.length == 64)
    }

    "round binary noise beyond the audit scale and reject deeper scales" in {
      def claudeCost(amount: String): Option[String] = {
        val report = collect(fixture(Harness.Claude).replace("\"costUSD\":0.00176", s"\"costUSD\":$amount"), request(Harness.Claude))
        report.meters.headOption.flatMap(_.observations.head.observation.cost.amount.map(_.value))
      }
      assert(claudeCost("0.0006150000000000001") == Some("0.000615"))
      assert(claudeCost("0." + "0" * 34 + "5") == Some("0"))
      assert(claudeCost("0." + "0" * 35 + "5") == None)
      val pi = fixture(Harness.Pi, "0.99.1")
      val tiny = collect(pi.replace("\"total\":0.0006150000000000001", "\"total\":1e-30"), request(Harness.Pi).copy(version = "0.99.1"))
      assert(tiny.meters.head.observations.head.observation.cost == UsageMath.unknownMoney)
    }
  }
}
