package cq.server

import cq.api.*
import cq.core.WorkspaceService
import cq.host.*
import java.net.URI
import java.nio.file.Path
import java.time.{Clock, Duration}
import zio.{IO, Task, ZIO}

final class SessionUpload(context: CliContext, clock: Clock) {
  private val MaxRecordBytes = 64 * 1024
  private val RequestTimeout = Duration.ofSeconds(30)
  private val CredentialLifetime = Duration.ofHours(1)

  def run(directory: Path): Task[Unit] = ZIO.scoped {
    for {
      run <- ZIO.attemptBlocking(HostFiles.read(directory.resolve("run.json"), SupervisorRun_JsonCodec, MaxRecordBytes))
      journal <- ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), run.project.project,
        run.attempt.session)))(repository => ZIO.attemptBlocking(repository.close()).orDie)
      prepared <- ZIO.attemptBlocking {
        val endpoint = URI.create(run.project.endpoint)
        val root = new HttpServerApi(endpoint, context.environment.getOrElse("CQ_TOKEN", throw new IllegalArgumentException("CQ_TOKEN is required")),
          run.attempt.session, RequestTimeout)
        val token = root.grant(GrantRequest(run.project.project, Actor("CQ host collector", run.attempt.session, Role.Collector),
          Math.addExact(clock.millis(), CredentialLifetime.toMillis)))
        val collector = new HttpServerApi(endpoint, token.value, run.attempt.session, RequestTimeout)
        val workspaces = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(directory.resolve("workspaces"),
          new BoundedHostCommand(GitEnvironment.isolated(context.environment), Duration.ofSeconds(10), MaxRecordBytes), clock))
        (collector, new SessionDelivery(journal, workspaces, clock))
      }
      (collector, delivery) = prepared
      report <- delivery.flush(directory, run, collector)
      _ <- ZIO.attempt {
        context.output.println(s"Acknowledged ${report.acknowledged} pending delivery batches from $directory")
        report.incompleteTickets.foreach(path => context.output.println(s"Unresolved child ticket: $path; assignment and usage identity were never committed"))
        require(report.incompleteTickets.isEmpty, "Incomplete child tickets retained for inspection; valid publications were replayed")
      }
    } yield ()
  }
}
