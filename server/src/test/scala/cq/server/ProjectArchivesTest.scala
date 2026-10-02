package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID
import scala.util.Using
import zio.{IO, ZIO}

// Restores an exported project into a fresh schema of the same cluster; the archive carries the project identity and restore never overwrites.
final class ProjectArchivesPostgres extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]]),
    activation = Activation(Repo -> Repo.Prod),
  )
  private def request(mutations: List[Mutation]): ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Archive scenario")

  "Project archives (Behavioral Active Blackbox; PostgreSQL Good Communication)" should {
    "restore a project whose installed-release rows hold an explicitly archived settled record" in {
      (service: LedgerService[IO], database: LedgerDatabase, config: DatabaseConfig, archives: ProjectArchives) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
      val adopted = ItemDraft("Adopted decision", "", Set.empty, false, Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil), Nil)
      val done = ItemDraft("Done task", "", Set.empty, true, Content.Task(TaskStatus.Done, List("Observable result"), None, Nil), Nil)
      val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
      val separator = if (config.url.contains("?")) "&" else "?"
      val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
      for {
        _ <- service.initialize(owner, "archived settled record")
        created <- service.change(owner, request(List(Mutation.Create(adopted), Mutation.Create(done))))
        decision = created.items.head
        // The installed release archived adopted decisions through the bulk operation; this reproduces such a row directly.
        archived <- database.transaction { connection =>
          new Jdbc(connection).execute("UPDATE cq_items SET archived = true, body = jsonb_set(body, '{draft,archived}', 'true'), " +
            "summary = jsonb_set(summary, '{archived}', 'true') WHERE project_id = ? AND ledger = ? AND number = ?") { s =>
            s.setObject(1, owner.project.value); s.setString(2, decision.id.ledger.toString); s.setLong(3, decision.id.number)
          }
        }
        _ <- assertIO(archived == 1)
        file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
        manifest <- archives.backup(owner.project, file)
        _ <- assertIO(manifest.project == owner.project && manifest.entries.exists(e => e.table == BackupTable.Items && e.rows == 2))
        _ <- ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        })
        _ <- target.initialize
        restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
        _ <- ZIO.attempt(Files.deleteIfExists(file))
        _ <- assertIO(restored.map(_.project) == Right(owner.project))
        item <- new PostgresLedgerRepository(target).transact(owner.project)(tx => tx.get(decision.id))
        _ <- assertIO(item.exists(value => value.draft.archived && value.draft.content == adopted.content))
      } yield ()
    }

    "round-trip host spans with the assignment they belong to" in {
      (service: LedgerService[IO], usage: UsageService[IO], config: DatabaseConfig, archives: ProjectArchives) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
      val host = owner.copy(actor = owner.actor.copy(role = Role.Collector))
      val task = ItemDraft("Checked task", "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil)
      val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
      val separator = if (config.url.contains("?")) "&" else "?"
      val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
      val filter = UsageFilter.ProjectAll()
      for {
        _ <- service.initialize(owner, "archived spans")
        created <- service.change(owner, request(List(Mutation.Create(task))))
        assignment = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set(created.items.head.id), Attribution.Direct, None, None)
        _ <- usage.assign(host, assignment)
        span = PhaseSpan(RequestId(UUID.randomUUID()), assignment.id, owner.actor.session, UsagePhase.Check, 1000, 1700, AttemptState.Failed)
        _ <- usage.span(host, span)
        before <- usage.phases(owner, filter)
        _ <- assertIO(before.phases.map(value => (value.phase, value.spans, value.wallMillis)) == List((UsagePhase.Check, 1L, 700L)))
        file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
        manifest <- archives.backup(owner.project, file)
        _ <- assertIO(manifest.entries.exists(entry => entry.table == BackupTable.UsageSpans && entry.rows == 1))
        _ <- ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        })
        _ <- target.initialize
        restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
        _ <- ZIO.attempt(Files.deleteIfExists(file))
        _ <- assertIO(restored.map(_.entries) == Right(manifest.entries))
        copy <- new PostgresUsageRepository(target).read(owner.project)(reader => (reader.span(span.id), reader.spans(filter), reader.cursor))
        _ <- assertIO(copy == (Some(span), List(SpanTally(UsagePhase.Check, 1, 700)), before.cursor))
      } yield ()
    }

    "Q32: round-trip the project's standing requirements with their revision and author" in {
      (service: LedgerService[IO], config: DatabaseConfig, archives: ProjectArchives) =>
      val operator = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
      val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
      val separator = if (config.url.contains("?")) "&" else "?"
      val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
      for {
        _ <- service.initialize(operator, "archived requirements")
        _ <- service.replaceRequirements(operator, Revision(0), "First text")
        written <- service.replaceRequirements(operator, Revision(1), "Every change carries a focused test λ😀.")
        file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
        manifest <- archives.backup(operator.project, file)
        _ <- assertIO(manifest.entries.last.table == BackupTable.Settings && manifest.entries.last.rows == 1)
        _ <- ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        })
        _ <- target.initialize
        restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
        _ <- ZIO.attempt(Files.deleteIfExists(file))
        _ <- assertIO(restored.map(_.entries) == Right(manifest.entries))
        copy <- new PostgresLedgerRepository(target).transact(operator.project)(_.setting(ProjectSettingKind.Requirements))
        _ <- assertIO(written.revision == Revision(2) && written.change.exists(_.actor == operator.actor) &&
          copy.contains(StoredSetting(written.revision, ProjectSetting.Requirements(written.text), operator.actor, written.change.get.at)))
      } yield ()
    }

    "Q32: refuse an archive whose settings row breaks the bounds of the write path or misstates its kind" in {
      (service: LedgerService[IO], database: LedgerDatabase, config: DatabaseConfig, archives: ProjectArchives) =>
      val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
      val separator = if (config.url.contains("?")) "&" else "?"
      val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
      val oversized = Wire.encode(ProjectSetting_JsonCodec, ProjectSetting.Requirements("x" * (LedgerPolicy.MaxRequirementsCodePoints + 1)))
      // A hand-edited archive is reproduced by editing the stored row before the backup: backup copies the table as it is.
      def refused(change: String, body: Option[String]): IO[Throwable, Unit] = {
        val operator = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
        for {
          _ <- service.initialize(operator, "edited requirements")
          _ <- service.replaceRequirements(operator, Revision(0), "Within the bound")
          edited <- database.transaction { connection =>
            new Jdbc(connection).execute(s"UPDATE cq_project_settings SET $change WHERE project_id = ?") { s =>
              body.foreach(s.setString(1, _)); s.setObject(body.size + 1, operator.project.value)
            }
          }
          _ <- assertIO(edited == 1)
          file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
          _ <- archives.backup(operator.project, file)
          restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
          _ <- ZIO.attempt(Files.deleteIfExists(file))
          _ <- ZIO.attempt(assert(restored.left.exists { case DomainFailure(_: Fault.Invalid) => true; case _ => false }, s"$change: ${restored.map(_.project)}"))
          copy <- new PostgresLedgerRepository(target).projects(None, 200)
          _ <- assertIO(!copy.projects.exists(_.id == operator.project))
        } yield ()
      }
      for {
        _ <- ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        })
        _ <- target.initialize
        _ <- refused("body = ?::jsonb", Some(oversized))
        _ <- refused("kind = 'Unknown'", None)
        _ <- refused("body = ?::jsonb", Some("{\"Unknown\":{}}"))
      } yield ()
    }
  }
}
