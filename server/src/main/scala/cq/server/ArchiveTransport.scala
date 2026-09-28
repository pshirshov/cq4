package cq.server

import cq.api.*
import java.nio.file.{Files, Path}
import org.http4s.{Header, Request, Response, Status}
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import org.typelevel.ci.CIString
import zio.{Task, ZIO}
import zio.interop.catz.*

final class ArchiveTransport(archives: ProjectArchives) {
  private def temporary: Task[Path] = ZIO.attemptBlocking(Files.createTempFile("cq-transfer-", ".zip"))
  private def remove(path: Path): Task[Unit] = ZIO.attemptBlocking(Files.deleteIfExists(path)).unit
  def backup(project: ProjectId): Task[Response[Task]] = ZIO.uninterruptibleMask { interruptible =>
    temporary.flatMap { path =>
      interruptible(for {
        _ <- archives.backup(project, path)
        digest <- ZIO.attemptBlocking(ArchiveFiles.sha256(path))
      } yield Response[Task](Status.Ok).withContentType(`Content-Type`(MediaType.application.zip))
        .putHeaders(Header.Raw(CIString("CQ-Archive-SHA256"), digest))
        .withBodyStream(fs2.io.file.Files.forAsync[Task].readAll(fs2.io.file.Path.fromNioPath(path)).onFinalize(remove(path))))
        .onError(_ => remove(path).orDie)
    }
  }
  def restore(request: Request[Task]): Task[Response[Task]] = ZIO.acquireReleaseWith(temporary)(path => remove(path).orDie) { path =>
    val body = request.body.take(ArchiveLimits.MaxBytes + 1)
    for {
      _ <- body.through(fs2.io.file.Files.forAsync[Task].writeAll(fs2.io.file.Path.fromNioPath(path))).compile.drain
      _ <- ZIO.attempt(ArchiveLimits.bounded(Files.size(path)))
      manifest <- archives.restore(path)
    } yield Response[Task](Status.Ok).withEntity(Wire.encode(BackupManifest_JsonCodec, manifest))
      .withContentType(`Content-Type`(MediaType.application.json))
  }
}
