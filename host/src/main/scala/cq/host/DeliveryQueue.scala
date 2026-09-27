package cq.host

import cq.api.*
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Files stay immutable after acknowledgement, so interrupted replay has the same identities. */
final class DeliveryQueue(root: Path) {
  private val MaxBatchBytes = 16 * 1024 * 1024
  private val MaxBatches = 512
  private val MaxSessionBytes = 512L * 1024 * 1024
  private val MaxEntries = 8192
  HostFiles.directory(root)

  private def locked[A](operation: => A): A = Using.resource(FileChannel.open(root.resolve("delivery.lock"),
    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { channel =>
    val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
    Using.resource(lock.getOrElse(throw new IllegalStateException("Delivery queue is already in use")))(_ => operation)
  }
  private def batches: List[Path] = Using.resource(Files.list(root)) { stream =>
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
    val stored = batches
    val additional = if (Files.exists(path)) 0 else encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    require(stored.map(Files.size(_)).sum + additional <= MaxSessionBytes, "Delivery queue exceeds its session byte bound")
    HostFiles.immutable(path, encoded, MaxBatchBytes)
  }
  def flush(api: ServerApi): Int = locked {
    var delivered = 0
    batches.foreach { path =>
      val receipt = path.resolveSibling(path.getFileName.toString.stripSuffix(".json") + ".ack")
      if (Files.exists(receipt)) require(HostFiles.text(receipt, 32) == "acknowledged\n", "Invalid delivery acknowledgement")
      else {
        val batch = HostFiles.read(path, DeliveryBatch_JsonCodec, MaxBatchBytes)
        require(batch.entries.nonEmpty && batch.entries.size <= MaxEntries, "Invalid stored delivery batch")
        batch.entries.foreach {
          case HostDelivery.Usage(value) => api.usage(value)
          case HostDelivery.Artifact(value) => api.artifact(value)
        }
        HostFiles.immutable(receipt, "acknowledged\n", 32)
        delivered += 1
      }
    }
    delivered
  }
}
