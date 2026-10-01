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
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
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
  }
}
