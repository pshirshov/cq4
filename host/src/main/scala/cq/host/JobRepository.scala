package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.DomainFailure
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait JobRepository extends AutoCloseable {
  def records: List[JobRecord]
  def reserve(workspace: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean)
  def replace(expected: JobRecord, next: JobRecord): Unit
}

/** The bytes of a job record were read and are not a record of this journal. Any other failure to open a journal is a failure to
  * read it, which may not happen again. */
final class JobRecordUndecodable(val path: Path, cause: Throwable)
  extends IllegalArgumentException(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName), cause)

object JobRecords {
  val MaxRecordBytes = 64 * 1024
  def conflict(message: String): Nothing = throw DomainFailure(Fault.Conflict(message))
  def terminal(phase: JobPhase): Boolean = phase == JobPhase.Settled || phase == JobPhase.Uncertain
  def validate(record: JobRecord): Unit = {
    require(record.fingerprint.matches("[0-9a-f]{64}"), "Invalid job fingerprint")
    require(record.workspace.base.value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Full workspace commit required")
    require(Path.of(record.workspace.repository).isAbsolute, "Absolute source repository required")
    require(record.revision > 0 && record.updatedAt >= record.createdAt, "Invalid job revision or receipt time")
    require(record.problem.forall(p => p.nonEmpty && p.length <= 300), "Invalid job diagnostic")
    require(record.exit.isEmpty || terminal(record.phase), "Nonterminal job cannot have an exit result")
    require(record.phase != JobPhase.Uncertain || (record.target == JobTarget.Stop && record.problem.nonEmpty), "Uncertain job must stop with an explicit diagnostic")
    if (record.phase == JobPhase.Settled) {
      require(record.exit.exists(e => e.settled && !e.hostFailure) || (record.exit.isEmpty && record.target == JobTarget.Stop), "Settlement requires observed cleanup or cancellation before launch")
    }
  }
  def transition(previous: JobRecord, next: JobRecord): Unit = {
    validate(next)
    require(previous.workspace == next.workspace && previous.fingerprint == next.fingerprint && previous.createdAt == next.createdAt,
      "Job launch identity is immutable")
    require(next.revision == Math.addExact(previous.revision, 1) && next.updatedAt >= previous.updatedAt, "Job revision must advance once")
    require(previous.target != JobTarget.Stop || next.target == JobTarget.Stop, "Stopped job cannot resume")
    val allowed = previous.phase match {
      case JobPhase.Preparing => Set(JobPhase.Preparing, JobPhase.Starting, JobPhase.Settled, JobPhase.Uncertain)
      case JobPhase.Starting => Set(JobPhase.Starting, JobPhase.Running, JobPhase.Stopping, JobPhase.Settled, JobPhase.Uncertain)
      case JobPhase.Running => Set(JobPhase.Running, JobPhase.Stopping, JobPhase.Settled, JobPhase.Uncertain)
      case JobPhase.Stopping => Set(JobPhase.Stopping, JobPhase.Settled, JobPhase.Uncertain)
      case JobPhase.Uncertain => Set(JobPhase.Uncertain)
      case JobPhase.Settled => Set.empty[JobPhase]
    }
    require(allowed(next.phase), "Job phase cannot regress or leave a terminal state")
  }
}

final class FileJobRepository private (root: Path, project: ProjectId, owner: SessionId, channel: FileChannel, lock: FileLock)
  extends JobRepository {
  private var closed = false
  private var state: Map[AttemptId, JobRecord] = {
    Using.resource(Files.list(root)) { entries =>
      val paths = entries.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).toList
      paths.map { path =>
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path), "Job record must be a regular file")
        val bytes = Using.resource(Files.newInputStream(path))(_.readNBytes(JobRecords.MaxRecordBytes + 1))
        require(bytes.length <= JobRecords.MaxRecordBytes, "Job record exceeds byte bound")
        try {
          val record = HostFiles.decode(bytes, JobRecord_JsonCodec)
          JobRecords.validate(record)
          require(record.workspace.project == project && record.workspace.owner == owner, "Job journal belongs to another project/session")
          require(path == recordPath(record.workspace.attempt), "Job filename and attempt disagree")
          record.workspace.attempt -> record
        } catch { case error: Exception => throw new JobRecordUndecodable(path, error) }
      }.toMap
    }
  }
  private def recordPath(attempt: AttemptId): Path = root.resolve(attempt.value.toString + ".json")
  private def checkOpen(): Unit = require(!closed, "Job journal is closed")
  private def persist(record: JobRecord): Unit = {
    val bytes = JobRecord_JsonCodec.encode(BaboonCodecContext.Default, record).noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    require(bytes.length <= JobRecords.MaxRecordBytes, "Job record exceeds byte bound")
    val temporary = Files.createTempFile(root, ".job-", ".pending", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
      Files.write(temporary, bytes)
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
      Files.move(temporary, recordPath(record.workspace.attempt), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      Using.resource(FileChannel.open(root, StandardOpenOption.READ))(_.force(true))
    } finally Files.deleteIfExists(temporary)
  }
  override def records: List[JobRecord] = synchronized { checkOpen(); state.values.toList.sortBy(_.workspace.attempt.value.toString) }
  override def reserve(workspace: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean) = synchronized {
    checkOpen()
    require(workspace.project == project && workspace.owner == owner, "Job ownership differs from journal")
    state.get(workspace.attempt) match {
      case Some(existing) =>
        if (existing.workspace != workspace || existing.fingerprint != fingerprint) JobRecords.conflict("Job identity reused with another launch")
        (existing, false)
      case None =>
        val record = JobRecord(workspace, fingerprint, JobTarget.Run, JobPhase.Preparing, None, None, 1, now, now)
        JobRecords.validate(record)
        persist(record)
        state = state.updated(workspace.attempt, record)
        (record, true)
    }
  }
  override def replace(expected: JobRecord, next: JobRecord): Unit = synchronized {
    checkOpen()
    if (!state.get(expected.workspace.attempt).contains(expected)) JobRecords.conflict("Job revision changed")
    JobRecords.transition(expected, next)
    persist(next)
    state = state.updated(next.workspace.attempt, next)
  }
  override def close(): Unit = synchronized {
    if (!closed) { closed = true; try lock.release() finally channel.close() }
  }
}

object FileJobRepository {
  def open(root: Path, project: ProjectId, owner: SessionId): FileJobRepository = {
    require(root.isAbsolute && root.normalize() == root, "Journal root must be absolute and normalized")
    Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    require(!Files.isSymbolicLink(root), "Journal root cannot be a symbolic link")
    val channel = FileChannel.open(root.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
      new FileJobRepository(root, project, owner, channel, lock.getOrElse(JobRecords.conflict("Job journal is already owned by a supervisor")))
    } catch { case failure: Throwable => channel.close(); throw failure }
  }
}
