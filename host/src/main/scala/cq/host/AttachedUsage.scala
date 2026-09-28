package cq.host

import cq.api.*
import io.circe.Json
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.time.Clock
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class AttachedUsage(directory: Path, run: SupervisorRun, clock: Clock) {
  private val MaxSamples = 4096
  private val MaxSampleBytes = 16384
  private val root = directory.resolve("pi-usage")
  private val Gap = "Interactive Pi coverage includes finalized assistant messages observed by this extension; compaction, auxiliary calls and interrupted/unreported responses remain unobserved"
  private var retained = Option.empty[Vector[AttachedPiSample]]
  private var incomplete = List.empty[Path]
  private def path(sequence: Long): Path = root.resolve(f"$sequence%04d")
  private def samples: Vector[AttachedPiSample] = retained.getOrElse {
    val values = if (!Files.exists(root)) Vector.empty else {
      require(Files.isDirectory(root) && !Files.isSymbolicLink(root), "Attached usage root must be a directory")
      val paths = Using.resource(Files.list(root))(_.iterator().asScala.take(MaxSamples + 1).toVector.sortBy(_.getFileName.toString))
      require(paths.size <= MaxSamples && paths.zipWithIndex.forall((value, index) => value == path(index + 1) &&
        Files.isDirectory(value) && !Files.isSymbolicLink(value)), "Attached usage sequence is incomplete or exceeds its bound")
      val (committed, pending) = paths.partition(value => Files.exists(value.resolve("sample.json"), LinkOption.NOFOLLOW_LINKS))
      require(pending.isEmpty || (pending.size == 1 && paths.last == pending.head), "Only the last attached usage sample may be incomplete")
      pending.foreach { value =>
        val files = Using.resource(Files.list(value))(_.iterator().asScala.take(2).toList)
        require(files.size <= 1 && files.forall(file => Files.isRegularFile(file) && !Files.isSymbolicLink(file) &&
          file.getFileName.toString.matches("\\.upload-.+\\.pending") && Files.size(file) <= MaxSampleBytes),
          "Incomplete attached usage sample contains unexpected evidence; manual inspection required")
      }
      incomplete = pending.toList
      committed.zipWithIndex.map { (value, index) =>
        val sample = HostFiles.read(value.resolve("sample.json"), AttachedPiSample_JsonCodec, MaxSampleBytes)
        require(sample.event.sequence == index + 1, "Attached usage sequence changed")
        validate(sample.event)
        sample
      }
    }
    retained = Some(values)
    values
  }
  private def validate(event: AttachedPiEvent): Unit = {
    require(run.ownership == SessionOwnership.Attached && run.attempt.harness == Harness.Pi, "Native Pi usage requires its attached Pi owner")
    require(event.sequence > 0 && event.sequence <= MaxSamples && event.turn >= 0 && event.timestamp >= 0, "Invalid attached usage position")
    require((List(event.session, event.provider, event.model, event.stopReason) ++ event.responseId).forall(value =>
      value.nonEmpty && value.length <= 300 && !value.exists(_.isControl)), "Invalid attached usage identity")
    require(List(event.input, event.output, event.cacheRead, event.cacheWrite, event.reasoning, event.totalTokens).flatten.forall(_ >= 0),
      "Negative attached usage counter")
  }
  private def sameResponse(left: AttachedPiEvent, right: AttachedPiEvent): Boolean =
    left.session == right.session && left.provider == right.provider && ((left.responseId, right.responseId) match {
      case (Some(a), Some(b)) => a == b
      case (None, None) => left.model == right.model && left.turn == right.turn && left.timestamp == right.timestamp
      case _ => false
    })
  private def entries(sample: AttachedPiSample, duplicate: Boolean): List[HostDelivery] = {
    val event = sample.event
    val evidence = NativeArtifacts.id(run.attempt.id, "attached-pi-" + event.sequence)
    val artifact = HostDelivery.Artifact(ArtifactUpload(run.project.project, evidence, run.attempt.id, ArtifactKind.Transcript,
      "application/json", HostFiles.encode(AttachedPiSample_JsonCodec, sample)))
    if (duplicate) List(artifact) else {
      def count(value: Option[Long]): Json = value.fold(Json.Null)(Json.fromLong)
      val usage = Json.obj("input" -> count(event.input), "output" -> count(event.output), "cacheRead" -> count(event.cacheRead),
        "cacheWrite" -> count(event.cacheWrite), "reasoning" -> count(event.reasoning), "totalTokens" -> count(event.totalTokens),
        "cost" -> Json.obj("total" -> event.costUSD.fold(Json.Null)(value => Json.fromBigDecimal(BigDecimal(value.value)))))
      val message = Json.obj("role" -> Json.fromString("assistant"), "provider" -> Json.fromString(event.provider), "model" -> Json.fromString(event.model),
        "timestamp" -> Json.fromLong(event.timestamp), "responseId" -> event.responseId.fold(Json.Null)(Json.fromString),
        "stopReason" -> Json.fromString(event.stopReason), "usage" -> usage)
      val native = List(Json.obj("type" -> Json.fromString("session"), "id" -> Json.fromString(event.session)),
        Json.obj("type" -> Json.fromString("message_end"), "message" -> message)).map(_.noSpaces).mkString("", "\n", "\n")
      val collected = new HarnessUsage().collect(new ByteArrayInputStream(native.getBytes(UTF_8)),
        UsageCollectionRequest(run.attempt.id, Harness.Pi, run.harnessVersion, UsageOrigin.Fresh, sample.receivedAt, evidence))
      require(collected.meters.size == 1 && collected.meters.head.observations.size == 1,
        "Pi usage sample was rejected: " + collected.gaps.mkString("; "))
      def delivery(value: HostUsage): HostDelivery = HostDelivery.Usage(HostUsageInput(run.project.project, value))
      artifact :: collected.meters.flatMap { meter =>
        delivery(HostUsage.Meter(meter.meter)) :: meter.observations.map { value =>
          val observation = value.observation.copy(id = ObservationId(evidence.value), position = event.sequence,
            completeness = UsageCompleteness.Partial, gaps = Gap :: value.observation.gaps)
          delivery(HostUsage.Ingest(value.copy(observation = observation)))
        }
      }
    }
  }
  private def publish(sample: AttachedPiSample, api: ServerApi): Int = {
    val earlier = samples.take((sample.event.sequence - 1).toInt)
    require(earlier.forall(_.event.session == sample.event.session), "Native Pi session changed within one CQ attempt")
    val previous = earlier.find(value => sameResponse(value.event, sample.event))
    require(previous.forall(value => value.event.copy(sequence = sample.event.sequence) == sample.event), "Conflicting repeated native Pi response")
    val queue = new DeliveryQueue(path(sample.event.sequence).resolve("delivery"))
    if (!queue.finalized) queue.commit(entries(sample, previous.nonEmpty))
    queue.flush(api)
  }
  def accept(event: AttachedPiEvent, api: ServerApi): Unit = synchronized {
    validate(event)
    val existing = samples
    require(incomplete.isEmpty, "Incomplete attached usage sample requires recovery; this session cannot resume recording")
    val sample = if (event.sequence <= existing.size) {
      val value = existing((event.sequence - 1).toInt)
      require(value.event == event, "Attached usage retry changed content")
      value
    } else {
      require(event.sequence == existing.size + 1, "Attached usage sequence has a gap")
      require(existing.forall(_.event.session == event.session), "Native Pi session changed within one CQ attempt")
      val duplicate = existing.find(value => sameResponse(value.event, event))
      require(duplicate.forall(value => value.event.copy(sequence = event.sequence) == event), "Conflicting repeated native Pi response")
      val value = AttachedPiSample(event, math.max(run.attempt.startedAt, clock.millis()))
      entries(value, duplicate.nonEmpty)
      HostFiles.directory(root)
      HostFiles.directory(path(event.sequence))
      HostFiles.immutable(path(event.sequence).resolve("sample.json"), HostFiles.encode(AttachedPiSample_JsonCodec, value), MaxSampleBytes)
      retained = Some(existing :+ value)
      value
    }
    publish(sample, api)
  }
  def recover(api: ServerApi): SessionDeliveryReport = synchronized {
    val acknowledged = samples.map(value => publish(value, api)).sum
    SessionDeliveryReport(acknowledged, incomplete)
  }
}
