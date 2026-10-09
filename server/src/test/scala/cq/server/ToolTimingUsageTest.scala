package cq.server

import cq.api.*
import cq.host.*
import io.circe.parser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

/**
 * The tool-call timing captures under `harness-usage/tool-timing/` (T87, docs/validation/tool-call-timing.md): whole stdout
 * transcripts of Claude Code 2.1.295, Codex 0.162.0 and Pi 1.1.0 running scripted shell and MCP tool calls against a stub provider
 * whose replies, and token counts, are invented. The collector does not accept these versions; this suite records that, and what it
 * reads from the same transcripts when they are presented as the newest version it does accept.
 */
final class ToolTimingUsageLocal extends AnyWordSpec {
  private final case class Capture(harness: Harness, version: String, directory: String, nativeFailure: Boolean)
  private val captures = List(
    Capture(Harness.Claude, "2.1.295", "claude-2.1.295-a", false), Capture(Harness.Claude, "2.1.295", "claude-2.1.295-b", false),
    Capture(Harness.Claude, "2.1.295", "claude-2.1.295-c", false),
    // Codex reports the stub's refusal of its WebSocket upgrade as `error` events before it falls back to HTTPS; the collector reads them as a native failure.
    Capture(Harness.Codex, "0.162.0", "codex-0.162.0-a", true), Capture(Harness.Codex, "0.162.0", "codex-0.162.0-b", true),
    Capture(Harness.Codex, "0.162.0", "codex-0.162.0-c", true), Capture(Harness.Codex, "0.162.0", "codex-0.162.0-a-rollout", true),
    Capture(Harness.Pi, "1.1.0", "pi-1.1.0-a", false), Capture(Harness.Pi, "1.1.0", "pi-1.1.0-b", false))

  private def resource(directory: String, name: String): String =
    Using.resource(getClass.getResourceAsStream(s"/harness-usage/tool-timing/$directory/$name")) { stream =>
      require(stream != null, s"Missing capture file $directory/$name")
      new String(stream.readAllBytes(), UTF_8)
    }
  private def collect(capture: Capture, version: String): CollectedUsage = new HarnessUsage().collect(
    new ByteArrayInputStream(resource(capture.directory, "stdout.jsonl").getBytes(UTF_8)),
    UsageCollectionRequest(AttemptId(UUID.randomUUID()), capture.harness, version, UsageOrigin.Fresh, 2000, ArtifactId(UUID.randomUUID())))
  /** Input, output and cache-read tokens the stub provider sent, summed over its requests. */
  private def sent(capture: Capture): (Long, Long, Long) = {
    val usage = resource(capture.directory, "requests.jsonl").split("\n").toList.filter(_.nonEmpty)
      .map(line => parser.parse(line).toOption.get.hcursor)
      .filter(_.downField("kind").as[String].toOption.contains("refused") == false) // refused WebSocket upgrades carry no usage
      .map(_.downField("usage"))
    (usage.map(_.get[Long]("input").toOption.get).sum, usage.map(_.get[Long]("output").toOption.get).sum, usage.map(_.get[Long]("cached").toOption.get).sum)
  }
  private def read(report: CollectedUsage): (Long, Long, Long) = {
    val counts = report.meters.flatMap { batch =>
      val chosen = if (batch.meter.scope == CounterScope.Cumulative) batch.observations.takeRight(1) else batch.observations
      chosen.map(_.observation.counters)
    }
    def sum(field: TokenCounts => Counter): Long = counts.map(c => field(c).value.getOrElse(0L)).sum
    (sum(_.input), sum(_.output), sum(_.cacheRead))
  }

  "The tool-call timing captures (Behavioral Active Blackbox Group)" should {
    "be refused by the collector under the versions that produced them" in {
      captures.foreach { capture =>
        val refusal = intercept[IllegalArgumentException](collect(capture, capture.version))
        assert(refusal.getMessage.contains("Unverified harness usage format version"), capture.directory)
      }
    }

    "collect to the terminal result and the token counts the stub sent when read as the newest accepted version" in {
      captures.foreach { capture =>
        val report = collect(capture, HarnessUsage.version(capture.harness))
        assert(report.terminalSeen, capture.directory)
        assert(report.nativeFailure == capture.nativeFailure, s"${capture.directory}: ${report.gaps}")
        assert(read(report) == sent(capture), capture.directory)
        assert(read(report) == (300L, 17L, 30L), capture.directory)
      }
    }
  }
}
