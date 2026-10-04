package cq.host

import cq.api.*
import cq.core.{DomainFailure, UsageMath}
import io.circe.{Json, JsonObject}
import java.io.{ByteArrayOutputStream, InputStream}
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import scala.collection.mutable

enum UsageOrigin {
  case Fresh
  case Resumed(baselines: List[UsageMeter])
}

final case class UsageCollectionRequest(attempt: AttemptId, harness: Harness, version: String,
  origin: UsageOrigin, collectedAt: Long, evidence: ArtifactId)
final case class CollectedMeter(meter: UsageMeter, observations: List[UsageUpload])
final case class CollectedUsage(meters: List[CollectedMeter], terminalSeen: Boolean, nativeFailure: Boolean, gaps: List[String])

/** Reads a completed native output stream line by line, whatever its length; never owns or drains a live process pipe. */
final class HarnessUsage {
  import HarnessUsage.*

  def collect(input: InputStream, request: UsageCollectionRequest): CollectedUsage = {
    require(request.collectedAt >= 0, "Invalid collection timestamp")
    require(verified(request.harness, request.version), "Unverified harness usage format version")
    request.origin match {
      case UsageOrigin.Resumed(baselines) =>
        require(baselines.map(_.key).distinct.size == baselines.size, "Duplicate usage baseline")
        baselines.foreach { meter =>
          require(meter.scope == CounterScope.Cumulative, "Only cumulative meters accept captured baselines")
          UsageMath.validate(meter.baseline)
          UsageMath.validate(meter.baselineCost)
        }
      case UsageOrigin.Fresh => ()
    }
    val collector = new Collection(request)
    val bytes = new Array[Byte](ReadBytes)
    val line = new ByteArrayOutputStream()
    var position = 0L
    var oversized = false
    var size = input.read(bytes)
    while (size != -1) {
      var index = 0
      while (index < size) {
        if (bytes(index) == '\n') {
          position += 1
          if (!oversized) {
            try {
              val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(line.toByteArray)).toString
              val json = io.circe.parser.parse(text).fold(_ => throw Malformed("Invalid native JSON event"), identity)
              collector.accept(json, position)
            } catch {
              case _: java.nio.charset.CharacterCodingException => collector.gap("Invalid UTF-8 in native output; usage from that event is unavailable")
              case Malformed(message) => collector.gap(message)
              case _: ArithmeticException => collector.gap("Native usage exceeded numeric bounds; usage from that event is unavailable")
            }
          }
          line.reset()
          oversized = false
        } else if (!oversized) {
          if (line.size() == MaxLineBytes) {
            oversized = true
            collector.gap("Native event exceeded the line bound; usage from that event is unavailable")
            line.reset()
          } else line.write(bytes(index))
        }
        index += 1
      }
      size = input.read(bytes)
    }
    if (line.size() != 0 || oversized) collector.gap("Native output ended without a line terminator; trailing event was not collected")
    collector.result()
  }
}

object HarnessUsage {
  val MaxLineBytes = 1024 * 1024
  val MaxSamples = 4096
  val MaxMeters = 64
  private val ReadBytes = 8192
  private val MaxGaps = 32
  private val MaxLabel = 100
  private val DoubleSignificantDigits = 17
  private final case class Malformed(message: String) extends RuntimeException(message)
  /** Native output formats verified against retained fixtures, oldest first; the last entry is the installed version live probes target. */
  def versions(harness: Harness): List[String] = harness match {
    case Harness.Claude => List("2.1.280", "2.1.285")
    case Harness.Codex => List("0.156.1", "0.159.2")
    case Harness.Pi => List("0.87.1", "0.99.1")
  }
  def version(harness: Harness): String = versions(harness).last
  def verified(harness: Harness, version: String): Boolean = versions(harness).contains(version)
  private def hash(value: String): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
  private def obj(json: Json, field: String): JsonObject = json.hcursor.downField(field).focus.flatMap(_.asObject)
    .getOrElse(throw Malformed(s"Missing or invalid native $field object"))
  private def label(json: Json, field: String): String = {
    val value = json.hcursor.get[String](field).getOrElse(throw Malformed(s"Missing or invalid native $field identity"))
    if (value.isEmpty || value.length > MaxLabel || value.exists(_.isControl)) throw Malformed(s"Invalid native $field identity bound")
    value
  }
  private def integer(json: Json, field: String): Option[Long] = json.hcursor.downField(field).focus match {
    case None | Some(Json.Null) => None
    case Some(value) => value.asNumber.flatMap(_.toLong) match {
      case Some(number) if number >= 0 => Some(number)
      case _ => throw Malformed(s"Invalid native $field counter")
    }
  }
  private def counter(value: Option[Long], zeroIsUnknown: Boolean): Counter = value match {
    case Some(number) if number != 0 || !zeroIsUnknown => Counter(Some(number), Measurement.Observed)
    case _ => UsageMath.missingCounter
  }
  private def cost(json: Json, field: String, zeroIsUnknown: Boolean): Money = json.hcursor.downField(field).focus match {
    case None | Some(Json.Null) => UsageMath.unknownMoney
    case Some(value) =>
      val amount = value.asNumber.flatMap(_.toBigDecimal).getOrElse(throw Malformed("Invalid native estimated cost"))
      if (amount < 0) throw Malformed("Negative native estimated cost")
      val reported = amount.bigDecimal.stripTrailingZeros()
      if (reported.scale() > UsageMath.MaxAmountScale + DoubleSignificantDigits) throw Malformed("Native estimated cost exceeds decimal bounds")
      // Native amounts are IEEE doubles; decimal places beyond the audit scale are binary noise, not price precision.
      val decimal = if (reported.scale() > UsageMath.MaxAmountScale)
        reported.setScale(UsageMath.MaxAmountScale, RoundingMode.HALF_EVEN).stripTrailingZeros() else reported
      // A positive amount that rounds to zero is below the audit scale, not a known zero.
      if (decimal.signum() == 0 && (zeroIsUnknown || reported.signum() != 0)) UsageMath.unknownMoney
      else {
        val scale = decimal.scale().toLong
        val precision = decimal.precision().toLong
        val length = if (scale <= 0) precision - scale else if (scale >= precision) scale + 2 else precision + 1
        if (length > UsageMath.MaxAmountLength) throw Malformed("Native estimated cost exceeds decimal bounds")
        val money = Money(Some(DecimalAmount(decimal.toPlainString)), Some("USD"), CostBasis.ProviderEstimate, None)
        try UsageMath.validate(money) catch { case _: DomainFailure => throw Malformed("Native estimated cost exceeds decimal bounds") }
        money
      }
  }
  private final case class Sample(key: String, source: String, scope: CounterScope, counts: TokenCounts,
    inputIncludesCache: Boolean, money: Money, gaps: List[String])
  private final case class Known(counts: TokenCounts, money: Money) {
    def retain(next: TokenCounts, cost: Money): Known = {
      def latest(old: Counter, current: Counter): Counter = if (current.value.nonEmpty) current else old
      Known(TokenCounts(latest(counts.input, next.input), latest(counts.output, next.output), latest(counts.cacheRead, next.cacheRead),
        latest(counts.cacheWrite, next.cacheWrite), latest(counts.reasoning, next.reasoning)), if (cost.amount.nonEmpty) cost else money)
    }
  }

  private final class Collection(request: UsageCollectionRequest) {
    private val problems = mutable.LinkedHashSet.empty[String]
    private val samples = mutable.LinkedHashMap.empty[String, (UsageMeter, mutable.ListBuffer[UsageUpload])]
    private val messages = mutable.Map.empty[String, Json]
    private val invalidMeters = mutable.Set.empty[String]
    private val known = mutable.Map.empty[String, Known]
    private var sampleCount = 0
    private var session = Option.empty[String]
    private var foreignSession = false
    private var turn = 0L
    private var terminal = false
    private var failed = false
    // Pi recovery is independent of usage completeness and of unrelated native failures.
    private val FirstPiRetryAttempt = 1L
    private final case class PiRetry(attempt: Option[Long], awaitingStart: Boolean, responseSeen: Boolean, endSeen: Boolean)
    private var piRetry = Option.empty[PiRetry]
    private def finishPiRetry(): Unit = {
      if (piRetry.exists(retry => retry.responseSeen && retry.endSeen)) piRetry = None
    }

    def gap(message: String): Unit = {
      if (problems.size < MaxGaps - 1) problems += message
      else if (!problems.contains(message)) problems += "Additional collection gaps omitted; inspect the native evidence"
    }
    private def sessionIdentity(value: String): Unit = session match {
      case None => session = Some(value)
      case Some(previous) if previous == value => ()
      case _ =>
        foreignSession = true
        gap("Native session identity changed within one attempt; subsequent usage was not attributed")
    }
    private def identity: String = session.getOrElse(throw Malformed("Usage event preceded the native session identity"))
    private def source: String = s"${request.harness}/${request.version}"
    private def baseline(sample: Sample): UsageMeter = {
      val (counts, money) = if (sample.scope == CounterScope.Increment) (UsageMath.zeroCounts, UsageMath.unknownMoney)
      else request.origin match {
        case UsageOrigin.Fresh =>
          val zeroCost = if (request.harness == Harness.Claude) Money(Some(DecimalAmount("0")), Some("USD"), CostBasis.ProviderEstimate, None)
            else UsageMath.unknownMoney
          (UsageMath.zeroCounts, zeroCost)
        case UsageOrigin.Resumed(baselines) => baselines.find(_.key == sample.key) match {
          case Some(value) => (value.baseline, value.baselineCost)
          case None =>
            gap("Resumed cumulative meter has no captured baseline; its attempt delta is unknown")
            (UsageMath.missingCounts, UsageMath.unknownMoney)
        }
      }
      UsageMeter(sample.key, request.attempt, sample.scope, counts, money)
    }
    private def record(sample: Sample, position: Long): Unit = {
      if (foreignSession || invalidMeters.contains(sample.key)) return
      if (sampleCount == MaxSamples || (!samples.contains(sample.key) && samples.size == MaxMeters)) {
        gap("Native usage exceeded the sample/meter bound; remaining usage is unavailable")
        return
      }
      val counters = List(sample.counts.input, sample.counts.output, sample.counts.cacheRead, sample.counts.cacheWrite, sample.counts.reasoning)
      val gaps = sample.gaps ++ Option.when(counters.exists(_.value.isEmpty))("One or more native token counters are unavailable") ++
        Option.when(sample.money.basis == CostBasis.Unknown)("Native monetary cost is unavailable")
      val observation = UsageObservation(
        ObservationId(UUID.nameUUIDFromBytes(s"cq/native-usage/${request.attempt.value}/${sample.key}/$position".getBytes(UTF_8))),
        request.attempt, sample.source, position, request.collectedAt, 0, sample.scope, sample.counts, sample.inputIncludesCache, true,
        sample.money, if (counters.forall(_.value.isEmpty)) UsageCompleteness.Unavailable else if (gaps.nonEmpty) UsageCompleteness.Partial else UsageCompleteness.Complete,
        gaps, Some(request.evidence), None,
      )
      val normalized = try UsageMath.normalize(observation) catch { case _: DomainFailure => throw Malformed("Inconsistent native usage counters; event was not collected") }
      val (meter, observations) = samples.getOrElse(sample.key, (baseline(sample), mutable.ListBuffer.empty))
      if (meter.scope != sample.scope) throw Malformed("Native counter scope changed within one meter")
      if (sample.scope == CounterScope.Cumulative) {
        try {
          UsageMath.since(normalized, meter.baseline)
          UsageMath.since(sample.money, meter.baselineCost)
          known.get(sample.key).foreach { previous =>
            UsageMath.monotonic(previous.counts, normalized)
            UsageMath.monotonic(previous.money, sample.money)
          }
        } catch {
          case _: DomainFailure =>
            invalidMeters += sample.key
            gap("Cumulative native usage decreased or contradicted its baseline; subsequent samples require explicit correction or a new meter")
            return
        }
      }
      observations += UsageUpload(observation, sample.key, UsageDisposition.Contribution, None)
      samples.update(sample.key, (meter, observations))
      if (sample.scope == CounterScope.Cumulative)
        known.update(sample.key, known.getOrElse(sample.key, Known(meter.baseline, meter.baselineCost)).retain(normalized, sample.money))
      sampleCount += 1
    }
    def accept(json: Json, position: Long): Unit = {
      val event = label(json, "type")
      request.harness match {
        case Harness.Claude => claude(json, event, position)
        case Harness.Codex => codex(json, event, position)
        case Harness.Pi => pi(json, event, position)
      }
    }
    private def claude(json: Json, event: String, position: Long): Unit = {
      if (event == "assistant") terminal = false
      if (event == "system" && json.hcursor.get[String]("subtype").contains("init")) sessionIdentity(label(json, "session_id"))
      if (event == "result") {
        sessionIdentity(label(json, "session_id"))
        terminal = true
        failed ||= json.hcursor.get[Boolean]("is_error").getOrElse(throw Malformed("Missing Claude result error status"))
        val models = obj(json, "modelUsage")
        if (models.isEmpty) gap("Claude result has no cumulative per-model usage")
        models.toList.sortBy(_._1).foreach { case (model, value) =>
          if (model.isEmpty || model.length > MaxLabel || model.exists(_.isControl)) throw Malformed("Invalid Claude usage model identity")
          val counts = TokenCounts(counter(integer(value, "inputTokens"), false), counter(integer(value, "outputTokens"), false),
            counter(integer(value, "cacheReadInputTokens"), false), counter(integer(value, "cacheCreationInputTokens"), false), counter(integer(value, "thinkingTokens"), false))
          val basis = value.hcursor.get[String]("costBasis").toOption
          record(Sample("claude/" + hash(identity + "/" + model), s"$source/model=$model", CounterScope.Cumulative,
            counts, false, cost(value, "costUSD", false),
            List("Claude client price estimate; pricing revision is not exposed") ++ Option.when(basis.isEmpty)("Claude native cost basis is unavailable")), position)
        }
      }
    }
    private def codex(json: Json, event: String, position: Long): Unit = event match {
      case "thread.started" => sessionIdentity(label(json, "thread_id"))
      case "turn.started" => terminal = false
      case "turn.failed" | "error" => failed = true; gap("Codex reported a failed turn or stream error; failed-request usage may be missing")
      case "turn.completed" =>
        terminal = true
        val usage = Json.fromJsonObject(obj(json, "usage"))
        val counts = TokenCounts(counter(integer(usage, "input_tokens"), true), counter(integer(usage, "output_tokens"), true),
          counter(integer(usage, "cached_input_tokens"), true), counter(integer(usage, "cache_write_input_tokens"), true), counter(integer(usage, "reasoning_output_tokens"), true))
        record(Sample("codex/" + hash(identity), source, CounterScope.Cumulative, counts, true, UsageMath.unknownMoney,
          List("Codex zero counters may be defaults and are retained as unknown; raw values remain in evidence")), position)
      case _ => ()
    }
    private def pi(json: Json, event: String, position: Long): Unit = event match {
      case "session" => sessionIdentity(label(json, "id"))
      case "turn_start" => turn = Math.addExact(turn, 1); terminal = false
      case "agent_start" => terminal = false
      case "agent_settled" => terminal = true
      case "auto_retry_start" =>
        terminal = false
        val attempt = json.hcursor.get[Long]("attempt").toOption.filter(_ > 0)
        piRetry match {
          case Some(retry) if retry.awaitingStart && attempt.exists(next => retry.attempt match {
              case None => next == FirstPiRetryAttempt
              case Some(previous) => previous < Long.MaxValue && next == previous + FirstPiRetryAttempt
            }) => piRetry = Some(retry.copy(attempt = attempt, awaitingStart = false))
          case _ => failed = true // An unmatched marker cannot forgive a native failure.
        }
      case "auto_retry_end" =>
        terminal = false
        val attempt = json.hcursor.get[Long]("attempt").toOption.filter(_ > 0)
        piRetry match {
          case Some(retry) if retry.attempt.nonEmpty && retry.attempt == attempt && !retry.awaitingStart && !retry.endSeen &&
              json.hcursor.get[Boolean]("success").contains(true) =>
            piRetry = Some(retry.copy(endSeen = true))
            finishPiRetry()
          case _ => failed = true
        }
      case "message_end" =>
        val message = Json.fromJsonObject(obj(json, "message"))
        if (message.hcursor.get[String]("role").contains("assistant")) {
          val model = label(message, "model")
          val provider = label(message, "provider")
          val timestamp = integer(message, "timestamp").getOrElse(throw Malformed("Missing Pi response timestamp"))
          val responseId = message.hcursor.downField("responseId").focus.filterNot(_.isNull).map(_ => label(message, "responseId"))
          val nativeIdentity = responseId.fold(s"$identity/$turn/$provider/$model/$timestamp")(id => s"$identity/$provider/$id")
          messages.get(nativeIdentity) match {
            case Some(previous) => if (previous != message) gap("Conflicting repeated Pi response; retained the first sample and native evidence")
            case None if messages.size == MaxSamples => gap("Pi response identity bound exceeded; remaining usage is unavailable")
            case None =>
              terminal = false
              val usage = Json.fromJsonObject(obj(message, "usage"))
              val rawInput = integer(usage, "input")
              val read = integer(usage, "cacheRead")
              val write = integer(usage, "cacheWrite")
              val output = integer(usage, "output")
              val inclusiveInput = for { input <- rawInput; cached <- read; written <- write } yield Math.addExact(Math.addExact(input, cached), written)
              val totalTokens = integer(usage, "totalTokens")
              for { input <- inclusiveInput; out <- output; total <- totalTokens.filter(_ > 0) }
                if (Math.addExact(input, out) != total) throw Malformed("Pi totalTokens disagrees with its input/output/cache counts")
              val counts = TokenCounts(counter(inclusiveInput, true), counter(output, true), counter(read, true), counter(write, true), counter(integer(usage, "reasoning"), true))
              val money = usage.hcursor.downField("cost").focus.fold(UsageMath.unknownMoney)(value => cost(value, "total", true))
              val stop = message.hcursor.get[String]("stopReason").toOption
              if (stop.exists(Set("error", "aborted"))) {
                piRetry match {
                  // A failed active attempt can advance the same chain, but only after a new start.
                  case Some(retry) if stop.contains("error") && retry.attempt.nonEmpty &&
                      !retry.awaitingStart && !retry.responseSeen && !retry.endSeen =>
                    piRetry = Some(retry.copy(awaitingStart = true))
                  case _ =>
                    // Failures outside an active attempt and all aborts remain sticky.
                    if (piRetry.nonEmpty || stop.contains("aborted")) failed = true
                    piRetry = if (stop.contains("error")) Some(PiRetry(None, true, false, false)) else None
                }
                gap("Pi reported an interrupted or failed response; final usage may be missing")
              } else if (stop.exists(Set("stop", "toolUse", "length"))) {
                piRetry.filter(retry => retry.attempt.nonEmpty && !retry.awaitingStart).foreach { retry =>
                  // Pi 0.99.1 emits the response before retry_end; also accept the reverse order.
                  piRetry = Some(retry.copy(responseSeen = true))
                  finishPiRetry()
                }
              }
              record(Sample("pi/" + hash(identity + "/" + provider + "/" + model), s"$source/$provider/$model", CounterScope.Increment,
                counts, true, money, List("Pi zero counters/cost may be defaults and are retained as unknown; raw values remain in evidence",
                  "Pi model price estimate; pricing revision is not exposed") ++
                  Option.when(totalTokens.forall(_ == 0))("Pi totalTokens is unavailable or a zero default") ++
                  Option.when(responseId.isEmpty)("Pi response ID is unavailable; deduplication is limited to turn/model/timestamp")), position)
              messages.update(nativeIdentity, message)
          }
        }
      case _ => ()
    }
    def result(): CollectedUsage = {
      if (!terminal) gap("No final native completion event; usage coverage is incomplete")
      if (samples.isEmpty) gap("No authoritative native usage sample was collected")
      if (request.harness == Harness.Pi) gap("Pi collection covers assistant responses; auxiliary, compaction and tool-result usage require separate observations")
      CollectedUsage(samples.valuesIterator.map { case (meter, entries) => CollectedMeter(meter, entries.toList) }.toList,
        terminal, failed || piRetry.nonEmpty, problems.toList)
    }
  }
}
