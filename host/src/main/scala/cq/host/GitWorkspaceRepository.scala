package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import io.circe.parser.parse
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Clock
import scala.util.Using
import zio.{IO, ZIO}

final class GitWorkspaceRepository(configuredRoot: Path, command: HostCommand, clock: Clock) extends WorkspaceRepository[IO] {
  private val MaxRecordBytes = 64 * 1024
  require(configuredRoot.isAbsolute && configuredRoot.normalize() == configuredRoot, "Workspace root must be absolute and normalized")
  private def canonical(path: Path): Path =
    if (Files.exists(path)) path.toRealPath() else canonical(path.getParent).resolve(path.getFileName)
  private val root = canonical(configuredRoot)
  private def container(attempt: AttemptId): Path = root.resolve(attempt.value.toString)
  private def recordPath(attempt: AttemptId): Path = container(attempt).resolve("workspace.json")
  private def conflict(message: String): Nothing = throw DomainFailure(Fault.Conflict(message))

  private def locked[A](attempt: AttemptId)(operation: => A): A = {
    Files.createDirectories(root)
    Using.resource(FileChannel.open(root.resolve(attempt.value.toString + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { channel =>
      val lock = try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }
      Using.resource(lock.getOrElse(conflict("Workspace operation is already in progress")))(_ => operation)
    }
  }
  private def read(attempt: AttemptId): Option[WorkspaceRecord] = {
    val file = recordPath(attempt)
    if (!Files.exists(file)) {
      if (Files.exists(container(attempt))) conflict("Workspace directory exists without a complete ownership record; manual quarantine required")
      None
    } else {
      require(!Files.isSymbolicLink(container(attempt)) && !Files.isSymbolicLink(file), "Workspace ownership record cannot be a symbolic link")
      val bytes = Using.resource(Files.newInputStream(file))(_.readNBytes(MaxRecordBytes + 1))
      require(bytes.length <= MaxRecordBytes, "Workspace record exceeds its byte bound")
      val value = parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).flatMap(WorkspaceRecord_JsonCodec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
      require(value.spec.attempt == attempt && Path.of(value.directory) == container(attempt).resolve("tree"), "Workspace ownership record/path mismatch")
      Some(value)
    }
  }
  private def write(value: WorkspaceRecord): Unit = {
    val file = recordPath(value.spec.attempt)
    val temporary = Files.createTempFile(file.getParent, ".workspace-", ".json")
    try {
      Files.writeString(temporary, WorkspaceRecord_JsonCodec.encode(BaboonCodecContext.Default, value).noSpaces)
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      Using.resource(FileChannel.open(file.getParent, StandardOpenOption.READ))(_.force(true))
    } finally Files.deleteIfExists(temporary)
  }
  private def git(directory: Path, arguments: String*): String = {
    val result = command.run(directory, List("git", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false") ++ arguments)
    if (result.exit != 0) throw new IllegalStateException(s"Git exited ${result.exit}: ${result.text.take(500)}")
    result.text.trim
  }

  override def prepare(spec: WorkspaceSpec): IO[Throwable, WorkspaceRecord] = ZIO.attemptBlocking {
    val source = Path.of(spec.repository).toRealPath()
    val checkout = Path.of(git(source, "rev-parse", "--show-toplevel")).toRealPath()
    require(!root.startsWith(checkout), "Workspace root must be outside the source checkout")
    locked(spec.attempt) {
      read(spec.attempt) match {
        case Some(existing) =>
          if (existing.spec != spec) conflict("Workspace identity reused with different ownership or base")
          if (existing.admission != WorkspaceAdmission.Open || existing.observed.isEmpty) conflict("Workspace preparation is quarantined or unconfirmed; it cannot be retried automatically")
          try {
            val directory = Path.of(existing.directory)
            val top = Path.of(git(directory, "rev-parse", "--show-toplevel")).toRealPath()
            val common = Path.of(git(directory, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
            val gitDirectory = Path.of(git(directory, "rev-parse", "--absolute-git-dir")).toRealPath()
            require(top == directory && common.toString == existing.observed.get.gitCommon &&
              gitDirectory.toString == existing.observed.get.gitDirectory, "Prepared workspace directory no longer belongs to its recorded Git worktree")
            existing
          } catch {
            case failure: Exception =>
              try write(existing.copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some("Prepared worktree identity could not be reverified")))
              catch { case storage: Exception => failure.addSuppressed(storage) }
              throw failure
          }
        case None =>
          val common = Path.of(git(source, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
          val base = git(source, "rev-parse", "--verify", "--end-of-options", spec.base.value + "^{commit}")
          require(base == spec.base.value, "Workspace base does not resolve to the requested commit")
          Files.createDirectory(container(spec.attempt))
          val pending = WorkspaceRecord(spec, container(spec.attempt).resolve("tree").toString, WorkspaceAdmission.Open, None, None)
          write(pending)
          try {
            git(source, "worktree", "add", "--detach", "--lock", "--reason", "CQ attempt " + spec.attempt.value, pending.directory, base)
            val directory = Path.of(pending.directory)
            val observedHead = git(directory, "rev-parse", "HEAD")
            val observedCommon = Path.of(git(directory, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
            require(observedHead == base && observedCommon == common, "Prepared worktree differs from its requested repository/base")
            val gitDirectory = Path.of(git(directory, "rev-parse", "--absolute-git-dir")).toRealPath()
            require(gitDirectory != common, "Prepared workspace must have its own Git directory")
            val ready = pending.copy(observed = Some(WorkspaceObservation(GitCommit(observedHead), common.toString, gitDirectory.toString, clock.millis())))
            write(ready)
            ready
          } catch {
            case failure: Exception =>
              try write(pending.copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some("Workspace creation or verification failed; inspect retained directory and Git registration")))
              catch { case storage: Exception => failure.addSuppressed(storage) }
              throw failure
          }
      }
    }
  }

  override def get(attempt: AttemptId): IO[Throwable, Option[WorkspaceRecord]] = ZIO.attemptBlocking(read(attempt))
  override def quarantine(attempt: AttemptId, reason: String): IO[Throwable, WorkspaceRecord] = ZIO.attemptBlocking {
    locked(attempt) {
      val current = read(attempt).getOrElse(throw DomainFailure(Fault.Missing("Workspace not registered")))
      val next = current.copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some(reason))
      write(next)
      next
    }
  }
}
