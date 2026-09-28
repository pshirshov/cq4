package cq.host

import cq.api.*
import cq.core.UsageMath
import io.circe.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait CodexUsageSource {
  def poll(binding: CodexUsageBinding): CodexUsagePage
}
final case class CodexUsagePage(samples: List[CodexUsageSample], caughtUp: Boolean, source: Option[CodexUsageFile], pendingLine: Boolean)

/** Incremental reader of a single native thread, never a prompt/transcript exporter. */
final class CodexRollout extends CodexUsageSource {
  private val MaxDirectoryEntries = 8192
  private val MaxPollBytes = 8 * 1024 * 1024
  private val MaxFileBytes = 512L * 1024 * 1024
  private val MaxLineBytes = 4 * 1024 * 1024
  private val MaxTurns = 4096
  private val BufferBytes = 8192
  private var source = Option.empty[Path]
  private var fileKey = Option.empty[String]
  private var offset = 0L
  private val line = new ByteArrayOutputStream()
  private var sessionSeen = false
  private var provider = Option.empty[String]
  private var models = Map.empty[UUID, String]
  private var failed = Option.empty[Throwable]

  private def locate(binding: CodexUsageBinding): Option[Path] = {
    require(binding.thread.version() == 7, "Unsupported Codex thread identity format")
    val root = Path.of(binding.sessions)
    require(root.isAbsolute && root.toRealPath() == root, "Codex sessions root identity changed")
    val day = Instant.ofEpochMilli(binding.thread.getMostSignificantBits >>> 16).atZone(ZoneOffset.UTC).toLocalDate
    val matches = List(-1L, 0L, 1L).flatMap { delta =>
      val date = day.plusDays(delta)
      val directory = root.resolve(f"${date.getYear}%04d/${date.getMonthValue}%02d/${date.getDayOfMonth}%02d")
      if (!Files.exists(directory)) Nil else Using.resource(Files.list(directory)) { stream =>
        val paths = stream.iterator().asScala.take(MaxDirectoryEntries + 1).toList
        require(paths.size <= MaxDirectoryEntries, "Codex session directory exceeds its discovery bound")
        paths.filter(_.getFileName.toString.endsWith("-" + binding.thread + ".jsonl"))
      }
    }
    require(matches.size <= 1, "Ambiguous Codex rollout identity")
    matches.headOption.map { path =>
      require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(root), "Invalid Codex rollout file")
      path.toRealPath()
    }
  }
  private def label(json: Json, field: String): Option[String] = json.hcursor.get[Option[String]](field).fold(throw _, identity).map { value =>
    require(value.nonEmpty && value.length <= 100 && !value.exists(_.isControl), "Invalid Codex " + field)
    value
  }
  private def accept(json: Json, binding: CodexUsageBinding): Option[CodexUsageSample] = {
    val cursor = json.hcursor
    val payload = cursor.downField("payload").focus.getOrElse(throw new IllegalArgumentException("Missing Codex event payload"))
    val fields = payload.hcursor
    cursor.get[String]("type").fold(throw _, identity) match {
      case "session_meta" =>
        require(!sessionSeen && fields.get[String]("id") == Right(binding.thread.toString) && fields.get[String]("cli_version") == Right(binding.version),
          "Codex rollout metadata differs from its native MCP binding")
        provider = label(payload, "model_provider")
        sessionSeen = true
        None
      case "turn_context" =>
        require(sessionSeen, "Codex event preceded session identity")
        for { turn <- fields.get[Option[String]]("turn_id").fold(throw _, identity); model <- label(payload, "model") } {
          val id = UUID.fromString(turn)
          require(models.get(id).forall(_ == model), "Codex turn model changed")
          require(models.contains(id) || models.size < MaxTurns, "Codex turn identity bound exceeded")
          models += id -> model
        }
        None
      case "token_usage_record" =>
        require(sessionSeen, "Codex usage preceded session identity")
        val thread = UUID.fromString(fields.get[String]("thread_id").fold(throw _, identity))
        if (thread != binding.thread) None
        else {
          val turn = UUID.fromString(fields.get[String]("turn_id").fold(throw _, identity))
          val response = label(payload, "response_id").getOrElse(throw new IllegalArgumentException("Missing Codex response identity"))
          val usage = fields.downField("usage")
          def count(field: String): Counter = usage.get[Option[Long]](field).fold(throw _, identity) match {
            case Some(value) => require(value >= 0, "Negative Codex usage counter"); Counter(Some(value), Measurement.Observed)
            case None => UsageMath.missingCounter
          }
          val counters = TokenCounts(count("input_tokens"), count("output_tokens"), count("cached_input_tokens"),
            count("cache_write_input_tokens"), count("reasoning_output_tokens"))
          for { input <- counters.input.value; output <- counters.output.value; total <- usage.get[Option[Long]]("total_tokens").fold(throw _, identity) }
            require(Math.addExact(input, output) == total, "Codex total does not match inclusive input/output")
          for { input <- counters.input.value; cached <- counters.cacheRead.value; written <- counters.cacheWrite.value }
            require(Math.addExact(cached, written) <= input, "Codex cache subsets exceed input")
          for { output <- counters.output.value; reasoning <- counters.reasoning.value }
            require(reasoning <= output, "Codex reasoning subset exceeds output")
          val position = cursor.get[Long]("ordinal").fold(throw _, identity)
          val occurred = Instant.parse(cursor.get[String]("timestamp").fold(throw _, identity)).toEpochMilli
          require(position >= 0 && occurred >= 0, "Invalid Codex event position/time")
          Some(CodexUsageSample(thread, turn, response, position, occurred, label(payload, "model").orElse(models.get(turn)),
            label(payload, "provider").orElse(provider), counters))
        }
      case _ => None
    }
  }

  override def poll(binding: CodexUsageBinding): CodexUsagePage = {
    failed.foreach(throw _)
    try {
      if (source.isEmpty) source = locate(binding)
      source.map { path =>
        val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && attributes.size >= offset && attributes.size <= MaxFileBytes, "Codex rollout replaced, truncated or oversized")
        val key = Option(attributes.fileKey()).map(_.toString).getOrElse(throw new IllegalArgumentException("Codex rollout file identity unavailable"))
        require(fileKey.forall(_ == key), "Codex rollout file identity changed")
        fileKey = Some(key)
        val samples = List.newBuilder[CodexUsageSample]
        Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
          channel.position(offset)
          val buffer = ByteBuffer.allocate(BufferBytes)
          var remaining = math.min(MaxPollBytes.toLong, attributes.size - offset)
          while (remaining > 0) {
            buffer.clear(); buffer.limit(math.min(BufferBytes.toLong, remaining).toInt)
            val size = channel.read(buffer)
            require(size > 0, "Codex rollout changed while reading")
            buffer.flip()
            while (buffer.hasRemaining) {
              val byte = buffer.get()
              if (byte == '\n') {
                val json = io.circe.parser.parse(UTF_8.newDecoder().decode(ByteBuffer.wrap(line.toByteArray)).toString).fold(throw _, identity)
                accept(json, binding).foreach(samples += _)
                line.reset()
              } else {
                require(line.size() < MaxLineBytes, "Codex event exceeds its line bound")
                line.write(byte.toInt)
              }
            }
            remaining -= size; offset += size
          }
        }
        CodexUsagePage(samples.result(), offset == attributes.size, Some(CodexUsageFile(path.toString, key)), line.size() != 0)
      }.getOrElse(CodexUsagePage(Nil, true, None, false))
    } catch { case error: Throwable => failed = Some(error); throw error }
  }
}
