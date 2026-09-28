package cq.server

import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.*
import java.net.URI
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using
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
        val root = new HttpServerApi(endpoint, HostCredential.read(context.environment),
          run.attempt.session, RequestTimeout)
        val token = root.grant(GrantRequest(run.project.project, Actor("CQ host collector", run.attempt.session, Role.Collector),
          Math.addExact(clock.millis(), CredentialLifetime.toMillis)))
        val collector = new HttpServerApi(endpoint, token.value, run.attempt.session, RequestTimeout)
        val workspaces = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(directory.resolve("workspaces"),
          new BoundedHostCommand(GitEnvironment.isolated(context.environment), Duration.ofSeconds(10), MaxRecordBytes), clock))
        (collector, new SessionDelivery(journal, workspaces, clock))
      }
      (collector, delivery) = prepared
      delivered <- delivery.flush(directory, run, collector).flatMap { report => ZIO.attempt {
        context.output.println(s"Acknowledged ${report.acknowledged} pending delivery batches from $directory")
        report.incompleteTickets.foreach(path => context.output.println(s"Unresolved child/check ticket or usage sample: $path; its record was never committed"))
        require(report.incompleteTickets.isEmpty, "Incomplete tickets or usage samples retained for inspection; valid publications were replayed")
      }}.either
      combined <- combinations(directory, run, collector).either
      integrated <- integrations(directory, run, journal, collector).either
      _ <- ZIO.attempt {
        val failures = List("deliveries" -> delivered, "combinations" -> combined, "integrations" -> integrated)
          .collect { case (phase, Left(error)) => (phase, error) }
        if (failures.nonEmpty) {
          val error = new IllegalStateException("Unresolved session recovery phases: " + failures.map(_._1).mkString(", "))
          failures.foreach((_, cause) => error.addSuppressed(cause))
          throw error
        }
      }
    } yield ()
  }

  private def combinations(directory: Path, run: SupervisorRun, collector: ServerApi): Task[Unit] = ZIO.attemptBlocking {
    val root = directory.resolve("combinations")
    if (Files.exists(root)) {
      val settings = HostFiles.read(directory.resolve("settings.json"), SupervisorSettings_JsonCodec, MaxRecordBytes)
      val target = settings.integrationTarget.getOrElse(throw new IllegalArgumentException("Retained combination has no configured target"))
      val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
      val publication = new CombinationPublication(root, owner, run.attempt.id, run.repository, target)
      val results = publication.inventory.map { id =>
        val result = scala.util.Try(publication.publish(id, collector))
        result.fold(error => context.output.println(s"Combination ${id.value}: unresolved frozen publication (${error.getClass.getSimpleName})"),
          preview => context.output.println(s"Combination ${id.value}: published frozen plan ${preview.plan.value}; execution requires its current claim"))
        result.isSuccess
      }
      require(results.forall(identity), "Unresolved combinations retained; recovery did not prepare or launch work")
    }
  }

  private def integrations(directory: Path, run: SupervisorRun, journal: JobRepository, collector: ServerApi): Task[Unit] = for {
    ids <- ZIO.attemptBlocking {
      List("integration-requests", "integrations").flatMap { name =>
        val root = directory.resolve(name)
        if (!Files.exists(root)) Nil else Using.resource(Files.list(root)) { paths =>
          val entries = paths.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).take(IntegrationEntries.MaxOperations + 1).toList
          require(entries.size <= IntegrationEntries.MaxOperations, "Retained integrations exceed the session limit")
          entries.map(path => IntegrationId(UUID.fromString(path.getFileName.toString.stripSuffix(".json"))))
        }
      }.distinct.sortBy(_.value.toString)
    }
    _ <- if (ids.isEmpty) ZIO.unit else for {
      coordinator <- ZIO.attemptBlocking {
        require(ids.size <= IntegrationEntries.MaxOperations, "Retained integrations exceed the session limit")
        val settings = HostFiles.read(directory.resolve("settings.json"), SupervisorSettings_JsonCodec, MaxRecordBytes)
        val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
        val endpoint = URI.create(run.project.endpoint)
        val root = new HttpServerApi(endpoint, HostCredential.read(context.environment), run.attempt.session, RequestTimeout)
        val token = root.grant(GrantRequest(owner.project, owner.actor, Math.addExact(clock.millis(), CredentialLifetime.toMillis)))
        val governor = new HttpServerApi(endpoint, token.value, owner.actor.session, RequestTimeout)
        val target = settings.integrationTarget.getOrElse(throw new IllegalArgumentException("Retained integration has no configured target"))
        val git = new SupervisedGitIntegration(owner, Path.of(run.repository), target,
          new BoundedHostCommand(GitEnvironment.isolated(context.environment), Duration.ofSeconds(10), MaxRecordBytes),
          new RetainedIntegrationJobs(journal), directory.resolve("payload"), context.environment, SupervisorConfig.limits(settings.limits), CqEntrypoint.command)
        new IntegrationCoordinator(owner, new FileIntegrationJournal(directory.resolve("integrations"), owner), git, governor, collector)
      }
      results <- ZIO.foreach(ids) { id => coordinator.recover(id).either.flatMap { result => ZIO.attempt {
        val (message, resolved) = result match {
          case Right(None) => ("Prepared only; no server reservation and no Git update launched", true)
          case Right(Some(value)) => value.record.resolution match {
            case IntegrationResolution.Recorded(_, _) => ("Recorded", true)
            case IntegrationResolution.NotApplied(reason) => ("Not applied: " + reason, true)
            case IntegrationResolution.Pending() => (value.blocker.getOrElse("Integration remains pending"), false)
          }
          case Left(error) => ("Unresolved retained integration: " + error.getClass.getSimpleName, false)
        }
        context.output.println(s"Integration ${id.value}: $message")
        resolved
      }} }
      _ <- ZIO.attempt(require(results.forall(identity), "Unresolved integrations retained; no Git updates were launched by recovery"))
    } yield ()
  } yield ()
}
