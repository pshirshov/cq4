package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import zio.{IO, ZIO}

abstract class ArtifactContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[ArtifactService[IO]], DIKey[UsageService[IO]], DIKey[LedgerService[IO]]),
  )
  private def owner(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("host", SessionId(UUID.randomUUID()), Role.Collector))
  private def begin(ledger: LedgerService[IO], usage: UsageService[IO], scope: Scope): IO[Throwable, AttemptId] = for {
    _ <- ledger.initialize(scope.copy(actor = scope.actor.copy(role = Role.Governor)), "artifact fixture")
    assignment <- usage.assign(scope, Assignment(AssignmentId(UUID.randomUUID()), scope.project, Set.empty, Attribution.Unattributed, None, None))
    attempt <- usage.start(scope, Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, scope.actor.session, Role.Worker,
      Harness.Pi, "fixture", "fixture", "fixture", 1000, UsagePhase.Work, None))
  } yield attempt.id
  private def value(scope: Scope, attempt: AttemptId, body: String): ArtifactUpload =
    ArtifactUpload(scope.project, ArtifactId(UUID.randomUUID()), attempt, ArtifactKind.Result, "text/plain", body)
  private def denied[A](effect: IO[Throwable, A])(expected: Fault => Boolean): IO[Throwable, Unit] =
    effect.either.flatMap(result => assertIO(result match { case Left(DomainFailure(fault)) => expected(fault); case _ => false })).unit

  "Immutable artifacts (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "page large Unicode bodies losslessly while metadata stays compact and retries preserve receipt identity" in {
      (artifacts: ArtifactService[IO], ledger: LedgerService[IO], usage: UsageService[IO]) =>
        val scope = owner()
        val body = "\u0000λ😀\r\n" * 15000
        for {
          attempt <- begin(ledger, usage, scope)
          upload = value(scope, attempt, body)
          first <- artifacts.upload(scope, upload)
          retry <- artifacts.upload(scope, upload)
          _ <- assertIO(first == retry && first.bytes == body.getBytes(UTF_8).length && first.codePoints == 75000)
          digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
          _ <- assertIO(first.sha256 == digest && Wire.encode(ArtifactMetadata_JsonCodec, first).getBytes(UTF_8).length < 1024)
          pages <- ZIO.foreach(0 until first.codePoints by ArtifactService.MaxPageCodePoints)(offset => artifacts.page(scope, upload.id, offset, ArtifactService.MaxPageCodePoints))
          _ <- assertIO(pages.map(_.text).mkString == body && !pages.last.hasMore && pages.last.next == first.codePoints)
          _ <- assertIO(pages.forall(p => Wire.encode(ArtifactPage_JsonCodec, p).getBytes(UTF_8).length < 65536))
          eof <- artifacts.page(scope, upload.id, first.codePoints, 1)
          _ <- assertIO(eof.text.isEmpty && !eof.hasMore)
          summary <- usage.summary(scope, UsageFilter.ProjectAll())
          _ <- assertIO(summary.cursor == 2 && summary.attempts.running == 1)
        } yield ()
    }

    "enforce collector session, project boundaries, immutable content and concurrent publication identity" in {
      (artifacts: ArtifactService[IO], ledger: LedgerService[IO], usage: UsageService[IO]) =>
        val scope = owner()
        val other = owner()
        for {
          attempt <- begin(ledger, usage, scope)
          _ <- begin(ledger, usage, other)
          upload = value(scope, attempt, "original")
          acks <- ZIO.collectAllPar(List.fill(4)(artifacts.upload(scope, upload)))
          _ <- assertIO(acks.distinct.size == 1)
          _ <- denied(artifacts.upload(scope, upload.copy(body = "replacement")))(_.isInstanceOf[Fault.Conflict])
          _ <- denied(artifacts.upload(scope, upload.copy(kind = ArtifactKind.Prompt)))(_.isInstanceOf[Fault.Conflict])
          _ <- denied(artifacts.upload(scope.copy(actor = scope.actor.copy(subject = "other publisher")), upload))(_.isInstanceOf[Fault.Conflict])
          _ <- denied(artifacts.upload(scope.copy(actor = scope.actor.copy(session = other.actor.session)), upload))(_.isInstanceOf[Fault.Denied])
          _ <- ZIO.foreachDiscard(List(Role.Governor, Role.Worker, Role.Reviewer, Role.Explorer, Role.Planner)) { role =>
            denied(artifacts.upload(scope.copy(actor = scope.actor.copy(role = role)), upload))(_.isInstanceOf[Fault.Denied])
          }
          _ <- denied(artifacts.upload(other, upload))(_.isInstanceOf[Fault.Denied])
          _ <- denied(artifacts.metadata(other, upload.id))(_.isInstanceOf[Fault.Missing])
          _ <- denied(artifacts.upload(scope, upload.copy(id = ArtifactId(UUID.randomUUID()), attempt = AttemptId(UUID.randomUUID()))))(_.isInstanceOf[Fault.Missing])
          metadata <- artifacts.metadata(scope.copy(actor = scope.actor.copy(role = Role.Reviewer)), upload.id)
          _ <- assertIO(metadata == acks.head)
          page <- artifacts.page(scope, upload.id, 0, 8192)
          _ <- assertIO(page.text == "original")
        } yield ()
    }

    "reject oversized parts, malformed Unicode and invalid pagination without creating artifacts" in {
      (artifacts: ArtifactService[IO], ledger: LedgerService[IO], usage: UsageService[IO]) =>
        val scope = owner()
        for {
          attempt <- begin(ledger, usage, scope)
          upload = value(scope, attempt, "😀" * (ArtifactService.MaxBytes / 4 + 1))
          _ <- denied(artifacts.upload(scope, upload))(_.isInstanceOf[Fault.Limit])
          _ <- denied(artifacts.upload(scope, upload.copy(body = "\ud800")))(_.isInstanceOf[Fault.Invalid])
          _ <- denied(artifacts.upload(scope, upload.copy(body = "", mediaType = "invalid")))(_.isInstanceOf[Fault.Invalid])
          _ <- denied(artifacts.metadata(scope, upload.id))(_.isInstanceOf[Fault.Missing])
          _ <- artifacts.upload(scope, upload.copy(body = "😀λ"))
          _ <- ZIO.foreachDiscard(List((-1, 1), (0, 0), (0, 8193), (3, 1), (Int.MaxValue, 1))) { case (offset, limit) =>
            denied(artifacts.page(scope, upload.id, offset, limit))(_.isInstanceOf[Fault.Invalid])
          }
          // D83: a refused page read states the maximum and the requested bounds.
          oversized <- artifacts.page(scope, upload.id, 0, ArtifactService.MaxPageCodePoints + 1).either
          _ <- assertIO(oversized match {
            case Left(DomainFailure(Fault.Invalid(message))) =>
              message.contains(ArtifactService.MaxPageCodePoints.toString) && message.contains((ArtifactService.MaxPageCodePoints + 1).toString)
            case _ => false
          })
          emoji <- artifacts.page(scope, upload.id, 0, 1)
          lambda <- artifacts.page(scope, upload.id, emoji.next, 1)
          _ <- assertIO(emoji.text == "😀" && emoji.next == 1 && emoji.hasMore && lambda.text == "λ" && !lambda.hasMore)
        } yield ()
    }
  }
}

final class ArtifactContractDummy extends ArtifactContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class ArtifactContractPostgres extends ArtifactContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
