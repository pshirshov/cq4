package cq.host

import cq.api.*
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Files stay immutable after acknowledgement, so interrupted replay has the same identities. */
final class DeliveryQueue(root: Path, sync: DeliverySync) {
  private val MaxBatchBytes = 16 * 1024 * 1024
  private val MaxBatches = 512
  private val MaxSessionBytes = 512L * 1024 * 1024
  private val MaxEntries = 8192
  private val EntriesPerBatch = 32
  private val committed = root.resolve("final")
  private val staging = root.resolve("staging")
  HostFiles.directory(root)

  // Within a process the operations of one queue wait for each other, so a short retention never fails against a snapshot being taken;
  // another process still finds the queue in use.
  private val monitor = sync.monitor(root)
  private def locked[A](operation: => A): A = monitor.synchronized(Using.resource(FileChannel.open(root.resolve("delivery.lock"),
    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { channel =>
    val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
    Using.resource(lock.getOrElse(throw new IllegalStateException("Delivery queue is already in use")))(_ => operation)
  })
  private def batches(directory: Path): List[Path] = Using.resource(Files.list(directory)) { stream =>
    val found = stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).take(MaxBatches + 1).toList
    require(found.size <= MaxBatches, "Delivery queue exceeds its batch bound")
    require(found.forall(_.getFileName.toString.matches("[0-9]{6}\\.json")), "Unexpected delivery batch filename")
    require(found.map(Files.size(_)).sum <= MaxSessionBytes, "Delivery queue exceeds its session byte bound")
    found.sortBy(_.getFileName.toString)
  }
  def enqueue(sequence: Int, batch: DeliveryBatch): Unit = locked {
    require(sequence >= 0 && sequence < MaxBatches && batch.entries.nonEmpty && batch.entries.size <= MaxEntries, "Invalid delivery batch")
    val path = root.resolve(f"$sequence%06d.json")
    val encoded = HostFiles.encode(DeliveryBatch_JsonCodec, batch)
    require(!Files.exists(committed) || Files.exists(path), "Cannot extend initial delivery after final publication")
    val stored = batches(root)
    val additional = if (Files.exists(path)) 0 else encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    require(stored.map(Files.size(_)).sum + additional <= MaxSessionBytes, "Delivery queue exceeds its session byte bound")
    HostFiles.immutable(path, encoded, MaxBatchBytes)
  }
  private def finalBatches: List[Path] = {
    if (!Files.exists(committed)) Nil
    else {
      HostFiles.directory(committed)
      val stored = batches(committed)
      require(stored.nonEmpty, "Committed publication is empty")
      forceRoot()
      stored
    }
  }
  private def forceRoot(): Unit = Using.resource(FileChannel.open(root, StandardOpenOption.READ))(_.force(true))
  def finalized: Boolean = locked(finalBatches.nonEmpty)
  private def discardStaging(): Unit = if (Files.exists(staging)) {
    HostFiles.directory(staging)
    val files = Using.resource(Files.list(staging))(_.iterator().asScala.take(MaxBatches + 3).toList)
    require(files.size <= MaxBatches + 1 && files.forall { path =>
      Files.isRegularFile(path) && !Files.isSymbolicLink(path) &&
        (path.getFileName.toString.matches("[0-9]{6}\\.json") || path.getFileName.toString.matches("\\.upload-.+\\.pending"))
    }, "Uncommitted publication contains unexpected files")
    files.foreach(Files.delete(_))
    Files.delete(staging)
  }
  def commit(entries: List[HostDelivery]): Unit = locked {
    require(entries.nonEmpty && entries.size <= MaxBatches * EntriesPerBatch, "Final publication exceeds its entry bound")
    val encoded = entries.grouped(EntriesPerBatch).map(values => HostFiles.encode(DeliveryBatch_JsonCodec, DeliveryBatch(values))).toList
    val initial = batches(root)
    require(encoded.size + initial.size <= MaxBatches &&
      encoded.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong).sum + initial.map(Files.size(_)).sum <= MaxSessionBytes,
      "Final publication exceeds its session bounds")
    if (Files.exists(committed)) {
      val stored = finalBatches
      require(stored.size == encoded.size && stored.zip(encoded).forall { case (path, value) => HostFiles.text(path, MaxBatchBytes) == value },
        "Committed final publication changed")
    } else {
      discardStaging()
      HostFiles.directory(staging)
      encoded.zipWithIndex.foreach { case (value, index) => HostFiles.immutable(staging.resolve(f"$index%06d.json"), value, MaxBatchBytes) }
      Using.resource(FileChannel.open(staging, StandardOpenOption.READ))(_.force(true))
      Files.move(staging, committed, StandardCopyOption.ATOMIC_MOVE)
      forceRoot()
    }
  }
  /** Sends every unacknowledged batch while the queue's lock is held, so nothing is enqueued meanwhile. */
  def flush(api: ServerApi): Int = locked(deliver(snapshot(), api))
  /** Takes the list of batches under the lock and sends them outside it: a batch enqueued while the server is slow is retained at once
    * and sent by the next call. Delivery is idempotent on the server, and a receipt is written with identical content by any sender. */
  def send(api: ServerApi): Int = deliver(locked(snapshot()), api)
  /** As `send`, for the one batch at `sequence` only: it neither waits for nor retries the batches before it. A finish is sent this way,
    * so a start the server holds up or refused never delays it; the server accepts a finish without a start and replays the start
    * that follows. */
  def send(api: ServerApi, sequence: Int): Int =
    deliver(locked(snapshot()).filter(_.getFileName.toString == f"$sequence%06d.json"), api)
  private def snapshot(): List[Path] = {
    val ready = batches(root) ++ finalBatches
    require(ready.size <= MaxBatches && ready.map(Files.size(_)).sum <= MaxSessionBytes, "Delivery queue exceeds its session bounds")
    ready
  }
  private def deliver(ready: List[Path], api: ServerApi): Int = {
    var delivered = 0
    ready.foreach { path =>
      val receipt = path.resolveSibling(path.getFileName.toString.stripSuffix(".json") + ".ack")
      if (Files.exists(receipt)) require(HostFiles.text(receipt, 32) == "acknowledged\n", "Invalid delivery acknowledgement")
      // A batch another sender of this process has in flight is left to it: a stalled delivery never holds up the batches behind it.
      else if (sync.begin(path)) try {
        val batch = HostFiles.read(path, DeliveryBatch_JsonCodec, MaxBatchBytes)
        require(batch.entries.nonEmpty && batch.entries.size <= MaxEntries, "Invalid stored delivery batch")
        batch.entries.foreach {
          case HostDelivery.Usage(value) => api.usage(value)
          case HostDelivery.Artifact(value) => api.artifact(value)
        }
        HostFiles.immutable(receipt, "acknowledged\n", 32)
        delivered += 1
      } finally sync.end(path)
    }
    delivered
  }
}

/** In-process coordination of the queues of one session: the monitor of a queue directory, the batches being sent and the lock of a
  * span's queue. One instance is shared by every holder of the same queues (`SupervisorConfig.delivery`); an instance of its own
  * coordinates nothing beyond itself, and another process still finds a queue in use through its file lock. */
final class DeliverySync {
  private val monitors = new java.util.concurrent.ConcurrentHashMap[String, Object]()
  private val sending = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private val spans = new java.util.concurrent.ConcurrentHashMap[java.util.UUID, Object]()
  private def key(path: Path): String = path.toAbsolutePath.normalize.toString
  def monitor(directory: Path): Object = monitors.computeIfAbsent(key(directory), _ => new Object)
  /** Claims a batch for sending; false when another sender has it in flight. */
  def begin(batch: Path): Boolean = sending.add(key(batch))
  def end(batch: Path): Unit = sending.remove(key(batch))
  def span(id: java.util.UUID): Object = spans.computeIfAbsent(id, _ => new Object)
}
