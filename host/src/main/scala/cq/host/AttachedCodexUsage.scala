package cq.host

import cq.api.*
import cq.core.UsageMath
import io.circe.Json
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Clock
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class AttachedCodexUsage(directory: Path, run: SupervisorRun, source: CodexUsageSource, clock: Clock) extends AutoCloseable {
  private val MaxSamples = 4096
  private val MaxBytes = 16384
  private val MaxRecoveryPages = 64
  private val MaxResponseOwners = 65536
  private val root = directory.resolve("codex-usage")
  private val bindingFile = root.resolve("binding.json")
  private val endFile = root.resolve("end.json")
  private val sampleRoot = root.resolve("samples")
  private val Gap = "Observed Codex response records only; native cost, unreported/auxiliary work and final-tail coverage are unknown. Outer attempt model grouping and task allocation remain unknown; sample evidence retains observed model/provider."
  private var ownership = Option.empty[(FileChannel, FileLock)]
  private var loaded = Option.empty[Map[String, CodexUsageSample]]
  private var pending = List.empty[CodexUsageSample]
  private var lastFailure = Option.empty[String]
  private var availability = "Native Codex thread identity not received"
  private var incomplete = List.empty[Path]
  private var ownerCount = Option.empty[Int]
  private final case class Collection(caughtUp: Boolean, acknowledged: Int)
  private def enabled: Boolean = run.ownership == SessionOwnership.Attached && run.attempt.harness == Harness.Codex
  private def binding: Option[CodexUsageBinding] = Option.when(Files.exists(bindingFile))(HostFiles.read(bindingFile, CodexUsageBinding_JsonCodec, MaxBytes))
  private def id(response: String): UUID = NativeArtifacts.id(run.attempt.id, "attached-codex/" + response).value
  private def samplePath(value: CodexUsageSample): Path = sampleRoot.resolve(id(value.response).toString)
  private def shared(value: CodexUsageBinding): Path = Path.of(value.sessions).getParent.resolve("cq-usage").resolve(value.thread.toString)
  private def acquire(value: CodexUsageBinding): Unit = if (ownership.isEmpty) {
    require(Path.of(value.sessions).toRealPath().toString == value.sessions, "Native Codex sessions identity changed")
    val global = shared(value)
    HostFiles.directory(global)
    val channel = FileChannel.open(global.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      val lock = Option(channel.tryLock()).getOrElse(throw new IllegalStateException("Native Codex thread is already observed by another CQ host"))
      ownership = Some(channel -> lock)
    } catch { case error: Throwable => channel.close(); throw error }
  }
  private def samples: Map[String, CodexUsageSample] = loaded.getOrElse {
    val values = if (!Files.exists(sampleRoot)) Map.empty[String, CodexUsageSample] else Using.resource(Files.list(sampleRoot)) { stream =>
      val paths = stream.iterator().asScala.take(MaxSamples + 1).toList
      require(paths.size <= MaxSamples, "Attached Codex sample bound exceeded")
      val (committed, unfinished) = paths.partition(path => Files.exists(path.resolve("sample.json")))
      unfinished.foreach { path =>
        require(Files.isDirectory(path) && !Files.isSymbolicLink(path) && UUID.fromString(path.getFileName.toString).toString == path.getFileName.toString,
          "Invalid incomplete Codex sample directory")
        val files = Using.resource(Files.list(path))(_.iterator().asScala.take(2).toList)
        require(files.size <= 1 && files.forall(file => Files.isRegularFile(file) && !Files.isSymbolicLink(file) &&
          file.getFileName.toString.matches("\\.upload-.+\\.pending") && Files.size(file) <= MaxBytes), "Incomplete Codex sample has unexpected evidence")
      }
      incomplete = unfinished
      val values = committed.map { path =>
        val value = HostFiles.read(path.resolve("sample.json"), CodexUsageSample_JsonCodec, MaxBytes)
        require(path == samplePath(value), "Attached Codex sample identity changed")
        value
      }
      require(values.map(_.response).distinct.size == values.size, "Duplicate retained Codex response")
      values.map(value => value.response -> value).toMap
    }
    loaded = Some(values)
    values
  }
  def status: String = synchronized {
    if (!enabled) "" else "Codex usage: " + lastFailure.getOrElse(availability) + ". " + Gap
  }
  def gaps: List[String] = synchronized {
    if (!enabled) Nil else List("Codex usage: " + lastFailure.getOrElse(availability), Gap)
  }
  def failure(error: Throwable): Unit = synchronized {
    val reason = (error.getClass.getSimpleName + ": " + Option(error.getMessage).getOrElse("Native collection failed"))
      .filterNot(_.isControl).take(250)
    lastFailure = Some(reason)
    HostFiles.directory(root)
    val record = root.resolve("first-failure.txt")
    if (!Files.exists(record)) HostFiles.immutable(record, reason, MaxBytes)
  }
  def observe(metadata: Option[Json], sessions: Path): Unit = synchronized {
    if (enabled) metadata match {
      case None => availability = "Native MCP request omitted thread metadata; usage unavailable"
      case Some(value) =>
        val cursor = value.hcursor
        val native = cursor.downField("x-codex-turn-metadata")
        val thread = UUID.fromString(cursor.get[String]("threadId").fold(throw _, identity))
        require(native.get[String]("thread_id") == Right(thread.toString), "Native Codex thread metadata disagrees")
        val version = native.get[String]("codex_version").fold(throw _, identity)
        require(Set("0.156.1", "0.157.1")(version), "Unverified native Codex rollout version")
        val proposed = CodexUsageBinding(thread, version, sessions.toRealPath().toString)
        require(binding.forall(_ == proposed), "Native Codex thread changed within a CQ host")
        require(!Files.exists(endFile), "Native Codex usage window is already closed")
        acquire(proposed)
        HostFiles.directory(root)
        HostFiles.immutable(bindingFile, HostFiles.encode(CodexUsageBinding_JsonCodec, proposed), MaxBytes)
        availability = "Bound to native thread " + thread
    }
  }
  private def deliveries(value: CodexUsageSample, bound: CodexUsageBinding): List[HostDelivery] = {
    require(value.thread == bound.thread && value.response.nonEmpty && value.response.length <= 100 && value.position >= 0 && value.occurredAt >= 0,
      "Invalid attached Codex sample identity")
    UsageMath.validate(value.counters)
    val evidence = ArtifactId(id(value.response))
    val key = "codex/" + value.thread + "/responses"
    val observation = UsageObservation(ObservationId(evidence.value), run.attempt.id,
      s"Codex/${bound.version}/rollout/${value.provider.getOrElse("unknown")}/${value.model.getOrElse("unknown")}",
      value.position, value.occurredAt, 0, CounterScope.Increment, value.counters, true, true, UsageMath.unknownMoney,
      UsageCompleteness.Partial, List(Gap), Some(evidence), None)
    UsageMath.normalize(observation)
    List(HostDelivery.Artifact(ArtifactUpload(run.project.project, evidence, run.attempt.id, ArtifactKind.Transcript,
      "application/json", HostFiles.encode(CodexUsageSample_JsonCodec, value))),
      HostDelivery.Usage(HostUsageInput(run.project.project, HostUsage.Meter(UsageMeter(key, run.attempt.id, CounterScope.Increment,
        UsageMath.zeroCounts, UsageMath.unknownMoney)))),
      HostDelivery.Usage(HostUsageInput(run.project.project, HostUsage.Ingest(UsageUpload(observation, key, UsageDisposition.Contribution, None)))))
  }
  private def claimPath(bound: CodexUsageBinding, response: String): Path = shared(bound).resolve("responses")
    .resolve(UUID.nameUUIDFromBytes(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString + ".json")
  private def sameResponse(left: CodexUsageSample, right: CodexUsageSample): Boolean =
    left.copy(position = right.position, occurredAt = right.occurredAt) == right
  private def retainLocal(value: CodexUsageSample, bound: CodexUsageBinding): Unit = {
    val previous = samples.get(value.response)
    require(previous.forall(sameResponse(_, value)), "Conflicting repeated Codex response")
    if (previous.isEmpty) {
      require(samples.size < MaxSamples, "Attached Codex sample bound exceeded")
      deliveries(value, bound)
      HostFiles.directory(sampleRoot)
      HostFiles.directory(samplePath(value))
      HostFiles.immutable(samplePath(value).resolve("sample.json"), HostFiles.encode(CodexUsageSample_JsonCodec, value), MaxBytes)
      loaded = Some(samples.updated(value.response, value))
      incomplete = incomplete.filterNot(_ == samplePath(value))
    }
  }
  private def recoverClaims(bound: CodexUsageBinding): Unit = {
    samples
    val claims = shared(bound).resolve("responses")
    if (Files.exists(claims)) Using.resource(Files.list(claims)) { stream =>
      val paths = stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).take(MaxResponseOwners + 1).toList
      require(paths.size <= MaxResponseOwners, "Native Codex response ownership bound exceeded")
      paths.foreach { path =>
        val owner = HostFiles.read(path, CodexResponseOwner_JsonCodec, MaxBytes)
        require(path == claimPath(bound, owner.sample.response) && owner.sample.thread == bound.thread, "Native Codex response ownership identity changed")
        if (owner.attempt == run.attempt.id) {
          // A shutdown clock rollback limits new discovery, not previously durable attribution.
          require(owner.sample.occurredAt >= run.attempt.startedAt, "Retained Codex response precedes its owner")
          retainLocal(owner.sample, bound)
        }
      }
    }
  }
  private def retain(value: CodexUsageSample, bound: CodexUsageBinding): Unit = {
    val previous = samples.get(value.response)
    require(previous.forall(sameResponse(_, value)), "Conflicting repeated Codex response")
    if (previous.isEmpty) {
      require(samples.size < MaxSamples, "Attached Codex sample bound exceeded")
      deliveries(value, bound)
      val claims = shared(bound).resolve("responses")
      HostFiles.directory(claims)
      if (ownerCount.isEmpty) ownerCount = Some(Using.resource(Files.list(claims))(_.iterator().asScala.take(MaxResponseOwners + 1).size))
      require(ownerCount.get <= MaxResponseOwners, "Native Codex response ownership bound exceeded")
      val claim = claimPath(bound, value.response)
      val canonical = if (Files.exists(claim)) {
        val owner = HostFiles.read(claim, CodexResponseOwner_JsonCodec, MaxBytes)
        require(sameResponse(owner.sample, value), "Conflicting repeated Codex response across owners")
        if (owner.attempt != run.attempt.id) {
          availability = "Response already owned by another CQ attempt; overlapping timestamps were not counted twice"
          return
        }
        owner.sample
      } else {
        require(ownerCount.get < MaxResponseOwners, "Native Codex response ownership bound exceeded")
        HostFiles.immutable(claim, HostFiles.encode(CodexResponseOwner_JsonCodec, CodexResponseOwner(run.attempt.id, value)), MaxBytes)
        ownerCount = ownerCount.map(_ + 1)
        value
      }
      retainLocal(canonical, bound)
    }
  }
  private def publish(api: ServerApi, bound: CodexUsageBinding): Int = samples.values.toList.sortBy(_.position).map { value =>
    val queue = new DeliveryQueue(samplePath(value).resolve("delivery"))
    if (!queue.finalized) queue.commit(deliveries(value, bound))
    queue.flush(api)
  }.sum
  private def collect(api: ServerApi, bound: CodexUsageBinding): Collection = {
    acquire(bound)
    samples
    require(incomplete.isEmpty, "Incomplete Codex usage sample requires explicit recovery inspection")
    val end = Option.when(Files.exists(endFile))(HostFiles.read(endFile, CodexUsageEnd_JsonCodec, MaxBytes).before)
    val page = if (pending.nonEmpty) CodexUsagePage(Nil, false, None, false) else source.poll(bound)
    page.source.foreach(value => HostFiles.immutable(root.resolve("source.json"), HostFiles.encode(CodexUsageFile_JsonCodec, value), MaxBytes))
    pending ++= page.samples.filter(value => value.occurredAt >= run.attempt.startedAt && end.forall(value.occurredAt < _))
    while (pending.nonEmpty) { retain(pending.head, bound); pending = pending.tail }
    val acknowledged = publish(api, bound)
    lastFailure = None
    availability = if (page.source.isEmpty) "Native rollout is unavailable (including ephemeral sessions)"
      else s"${samples.size} response records observed" + (if (page.pendingLine) "; trailing record still being written" else "")
    Collection(page.caughtUp, acknowledged)
  }
  def poll(api: ServerApi): Unit = synchronized { if (enabled) binding.foreach(collect(api, _)) }
  def finish(api: ServerApi): Unit = synchronized { if (enabled) {
    HostFiles.directory(root)
    HostFiles.immutable(endFile, HostFiles.encode(CodexUsageEnd_JsonCodec, CodexUsageEnd(math.max(run.attempt.startedAt, clock.millis()))), MaxBytes)
    poll(api)
  } }
  def recover(api: ServerApi): SessionDeliveryReport = synchronized {
    if (!enabled) SessionDeliveryReport(0, Nil) else binding match {
      case None => SessionDeliveryReport(0, Nil)
      case Some(bound) =>
        recoverClaims(bound)
        var acknowledged = publish(api, bound)
        if (Files.exists(endFile) && incomplete.isEmpty) {
          var caughtUp = false
          var pages = 0
          while (!caughtUp && pages < MaxRecoveryPages) {
            val page = collect(api, bound)
            caughtUp = page.caughtUp; acknowledged += page.acknowledged; pages += 1
          }
          require(caughtUp, "Codex recovery exceeded its read bound; retained samples were replayed")
        }
        SessionDeliveryReport(acknowledged, incomplete)
    }
  }
  override def close(): Unit = synchronized {
    ownership.foreach { (channel, lock) => try lock.release() finally channel.close() }
    ownership = None
  }
}
