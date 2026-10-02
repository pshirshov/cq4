package cq.host

import cq.api.*
import cq.core.{DomainFailure, IntegrationPolicy, Scope}
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Using
import zio.{Task, ZIO}

trait IntegrationEntry {
  def read: Option[IntegrationLocal]
  def write(value: IntegrationLocal): Unit
}

trait IntegrationJournal {
  def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A]
}

object IntegrationEntries {
  val MaxRecordBytes = IntegrationPolicy.MaxIntentBytes + 8192
  def validate(owner: Scope, id: IntegrationId, previous: Option[IntegrationLocal], next: IntegrationLocal): Unit = {
    require(next.intent.id == id && next.intent.project == owner.project && next.intent.owner == owner.actor && owner.actor.role == Role.Governor,
      "Integration journal identity differs from its governing owner")
    require(HostFiles.encode(IntegrationLocal_JsonCodec, next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxRecordBytes,
      "Integration journal record exceeds its byte bound")
    previous match {
      case None => require(!next.attempted && next.observation.isEmpty, "New integration must precede execution and observation")
      case Some(old) =>
        require(old.intent == next.intent && (!old.attempted || next.attempted), "Integration intent and execution admission are immutable")
        require(old.observation.forall(value => next == old && next.observation.contains(value)), "Terminal integration observation is immutable")
        require(old.attempted == next.attempted || next.observation.isEmpty, "Execution admission must be persisted before its observation")
    }
  }
}

final class FileIntegrationJournal(root: Path, owner: Scope) extends IntegrationJournal {
  private def force(directory: Path): Unit = Using.resource(FileChannel.open(directory, StandardOpenOption.READ))(_.force(true))

  override def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A] = ZIO.scoped {
    for {
      channel <- ZIO.acquireRelease(ZIO.attemptBlocking {
        HostFiles.directory(root)
        force(root.getParent)
        FileChannel.open(root.resolve("coordinator.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      })(value => ZIO.attemptBlocking(value.close()).orDie)
      _ <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
        lock.getOrElse(throw DomainFailure(Fault.Conflict("Integration operation is already owned by another coordinator")))
      })(value => ZIO.attemptBlocking(value.release()).orDie)
      entry <- ZIO.attemptBlocking {
        val path = root.resolve(id.value.toString + ".json")
        var current = if (Files.exists(path)) Some(HostFiles.read(path, IntegrationLocal_JsonCodec, IntegrationEntries.MaxRecordBytes)) else None
        current.foreach { value =>
          IntegrationEntries.validate(owner, id, Some(value), value)
          Using.resource(FileChannel.open(path, StandardOpenOption.WRITE))(_.force(true))
        }
        force(root)
        new IntegrationEntry {
          override def read: Option[IntegrationLocal] = current
          override def write(value: IntegrationLocal): Unit = {
            IntegrationEntries.validate(owner, id, current, value)
            val temporary = Files.createTempFile(root, ".integration-", ".pending",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
              Files.writeString(temporary, HostFiles.encode(IntegrationLocal_JsonCodec, value))
              Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
              Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
              force(root)
              current = Some(value)
            } finally Files.deleteIfExists(temporary)
          }
        }
      }
      result <- operation(entry)
    } yield result
  }
}
