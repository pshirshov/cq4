package cq.core

import cq.api.*
import izumi.functional.bio.{Error2, F, *}
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.security.MessageDigest
import java.time.Clock
import scala.util.Try

final case class StoredArtifact(metadata: ArtifactMetadata, body: String)

trait ArtifactRepository[F[_, _]] {
  def put(value: StoredArtifact): F[Throwable, ArtifactMetadata]
  def get(project: ProjectId, id: ArtifactId): F[Throwable, Option[StoredArtifact]]
}

trait ArtifactService[F[_, _]] {
  def upload(scope: Scope, value: ArtifactUpload): F[Throwable, ArtifactMetadata]
  def metadata(scope: Scope, id: ArtifactId): F[Throwable, ArtifactMetadata]
  def page(scope: Scope, id: ArtifactId, offset: Int, limit: Int): F[Throwable, ArtifactPage]
}

object ArtifactService {
  val MaxBytes = 256 * 1024
  val MaxPageCodePoints = 8192

  def replay(previous: StoredArtifact, incoming: StoredArtifact): ArtifactMetadata = {
    if (previous.body != incoming.body || previous.metadata != incoming.metadata.copy(receivedAt = previous.metadata.receivedAt))
      throw DomainFailure(Fault.Conflict("Artifact identity reused with different immutable content or publisher"))
    previous.metadata
  }

  final class Impl[F[+_, +_]: Error2](repository: ArtifactRepository[F], usage: UsageRepository[F], clock: Clock) extends ArtifactService[F] {
    override def upload(scope: Scope, value: ArtifactUpload): F[Throwable, ArtifactMetadata] = for {
      stored <- F.fromEither(Try {
        if (!Set(Role.Collector, Role.Human).contains(scope.actor.role) || scope.project != value.project)
          throw DomainFailure(Fault.Denied("Artifact publication requires its project's host collector"))
        LedgerPolicy.invalid(Set("text/plain", "text/markdown", "application/json", "application/x-ndjson").contains(value.mediaType), "Unsupported artifact media type")
        if (value.body.length > MaxBytes) throw DomainFailure(Fault.Limit("Artifact part exceeds 256 KiB; split into referenced parts"))
        val buffer = Try(StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value.body)))
          .getOrElse(throw DomainFailure(Fault.Invalid("Artifact contains malformed Unicode")))
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        if (bytes.length > MaxBytes) throw DomainFailure(Fault.Limit("Artifact part exceeds 256 KiB; split into referenced parts"))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
        StoredArtifact(ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, hash,
          bytes.length, value.body.codePointCount(0, value.body.length), scope.actor, clock.millis()), value.body)
      }.toEither)
      _ <- usage.read(scope.project) { reader =>
        val attempt = reader.attempt(value.attempt).getOrElse(throw DomainFailure(Fault.Missing("Artifact attempt not registered")))
        if (scope.actor.role != Role.Human && attempt.session != scope.actor.session)
          throw DomainFailure(Fault.Denied("Artifact publisher does not own this attempt's session"))
      }
      result <- repository.put(stored)
    } yield result

    private def found(scope: Scope, id: ArtifactId): F[Throwable, StoredArtifact] = repository.get(scope.project, id).flatMap {
      case Some(value) => F.pure(value)
      case None => F.fail(DomainFailure(Fault.Missing("Artifact not found in this project")))
    }

    override def metadata(scope: Scope, id: ArtifactId): F[Throwable, ArtifactMetadata] = found(scope, id).map(_.metadata)

    override def page(scope: Scope, id: ArtifactId, offset: Int, limit: Int): F[Throwable, ArtifactPage] = for {
      _ <- F.fromEither(Try(LedgerPolicy.invalid(offset >= 0 && limit > 0 && limit <= MaxPageCodePoints,
        s"Invalid artifact page bounds: offset must be at least 0 and limit 1–$MaxPageCodePoints code points; requested offset $offset and limit $limit")).toEither)
      stored <- found(scope, id)
      page <- F.fromEither(Try {
        LedgerPolicy.invalid(offset <= stored.metadata.codePoints, "Artifact offset exceeds content")
        val next = offset + limit.min(stored.metadata.codePoints - offset)
        val begin = stored.body.offsetByCodePoints(0, offset)
        val end = stored.body.offsetByCodePoints(begin, next - offset)
        ArtifactPage(stored.metadata, offset, next, next < stored.metadata.codePoints, stored.body.substring(begin, end))
      }.toEither)
    } yield page
  }
}
