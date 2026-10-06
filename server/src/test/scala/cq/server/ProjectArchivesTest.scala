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
    "restore drivers off with invalid control tokens and retained lineage" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO], config: DatabaseConfig, archives: ProjectArchives) =>
      val operator = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
      val governor = operator.copy(actor = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val key = DriverKey(Harness.Claude, "archived-driver")
      val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
      val separator = if (config.url.contains("?")) "&" else "?"
      val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
      val draft = ItemDraft("Ready", "", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil)
      for {
        _ <- service.initialize(operator, "archived driver")
        created <- service.change(operator, request(List(Mutation.Create(draft))))
        roots = created.items.map(_.id).toSet
        started <- service.drive(operator, DriverRequest.Control(key, DriverOrigin.UserPromptSubmit, DriverControl.Start(WorksetTarget.Inline(roots, WorkflowPhase.Work), None)))
        _ <- service.drive(governor, DriverRequest.Session(DriverSession.Bind(started.asInstanceOf[DriverReply.Started].bind.get)))
        issued <- service.drive(operator, DriverRequest.Control(key, DriverOrigin.Stop, DriverControl.Continue(false)))
        directive = issued.asInstanceOf[DriverReply.Continue].directive
        run = RequestId(UUID.randomUUID())
        _ <- service.drive(governor, DriverRequest.Session(DriverSession.Activate(run, WorkflowRequest.Advance(roots, WorkflowPhase.Work), Some(directive.token))))
        dispatch = LineageMember.Request(RequestId(UUID.randomUUID()))
        outcome = ChildOutcome(AttemptId(UUID.randomUUID()), roots.toList, ChildEnd.Retryable, Some("operative-input"), Some("Malformed report at line 3"))
        _ <- service.drive(governor, DriverRequest.Session(DriverSession.Inherit(directive.cycle, LineageMember.Run(run), dispatch)))
        _ <- service.drive(governor, DriverRequest.Session(DriverSession.Inherit(directive.cycle, dispatch, LineageMember.Attempt(outcome.attempt))))
        _ <- service.drive(governor, DriverRequest.Session(DriverSession.Conclude(directive.cycle, outcome)))
        before <- repository.driverRecords(operator.project)
        _ <- assertIO(before.head.cycle.exists(_.outcomes == List(outcome)))
        file <- ZIO.attempt(Files.createTempFile("cq-driver-archive-", ".zip"))
        manifest <- archives.backup(operator.project, file)
        _ <- assertIO(manifest.entries.exists(entry => entry.table == BackupTable.Drivers && entry.rows == 1))
        _ <- ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        })
        _ <- target.initialize
        _ <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file)
        restored <- new PostgresLedgerRepository(target).driverRecords(operator.project)
        record = restored.head
        _ <- assertIO(record.state == DriverState.Off && record.stopped.exists(_.reason == DriverStop.RestoredArchive) && record.bind.isEmpty &&
          record.cycle.exists(cycle => cycle.state == CycleState.Ended && cycle.startToken.isEmpty && cycle.resumeToken.isEmpty && cycle.resumed.isEmpty &&
            cycle.lineage.map(_.member) == before.head.cycle.get.lineage.map(_.member) && cycle.outcomes == List(outcome) && cycle.retried.isEmpty) &&
          record.revision.value == before.head.revision.value + 1)
        serviceAfter = FixedLedger.at(new PostgresLedgerRepository(target), System.currentTimeMillis())
        replay <- serviceAfter.drive(governor, DriverRequest.Session(DriverSession.Activate(run, WorkflowRequest.Advance(roots, WorkflowPhase.Work), Some(directive.token)))).either
        _ <- assertIO(replay.left.exists { case DomainFailure(_: Fault.Denied) => true; case _ => false })
        _ <- ZIO.attempt(Files.deleteIfExists(file))
      } yield ()
    }

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

    "D150: back up a project whose attached governing attempt has no outcome, and refuse one with a managed attempt that has none" in {
      (service: LedgerService[IO], usage: UsageService[IO], archives: ProjectArchives) =>
      def project(name: String, collector: String): IO[Throwable, ProjectId] = {
        val owner = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
        val host = owner.copy(actor = owner.actor.copy(role = Role.Collector))
        val overhead = Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None)
        val governing = Attempt(AttemptId(UUID.randomUUID()), overhead.id, None, owner.actor.session, Role.Governor, Harness.Claude,
          "provider", "model", collector, 1000, UsagePhase.Govern)
        service.initialize(owner, name) *> usage.assign(host, overhead) *> usage.start(host, governing).as(owner.project)
      }
      def backup(id: ProjectId): IO[Throwable, Either[Throwable, BackupManifest]] = for {
        file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
        result <- archives.backup(id, file).either
        _ <- ZIO.attempt(Files.deleteIfExists(file))
      } yield result
      for {
        attached <- project("open attached governor", cq.core.AttemptObservation.AttachedGovernorCollector)
        managed <- project("running managed governor", "CQ native collector 0.1.0")
        open <- backup(attached)
        running <- backup(managed)
        _ <- assertIO(open.exists(_.entries.exists(entry => entry.table == BackupTable.UsageAttempts && entry.rows == 1)))
        _ <- assertIO(running.left.exists(_.getMessage.contains("running attempts")))
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

    "I30: round-trip the project's process mode beside its standing requirements, and refuse a mode the write path refuses" in {
      (service: LedgerService[IO], database: LedgerDatabase, config: DatabaseConfig, archives: ProjectArchives) =>
      val separator = if (config.url.contains("?")) "&" else "?"
      def fresh: IO[Throwable, LedgerDatabase] = {
        val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
        val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
        ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        }) *> target.initialize.as(target)
      }
      // A hand-edited archive is reproduced by editing the stored row before the backup: backup copies the table as it is.
      def archived(body: Option[ProjectSetting]): IO[Throwable, (Scope, ProjectMode, Either[Throwable, BackupManifest], LedgerDatabase)] = {
        val operator = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
        for {
          _ <- service.initialize(operator, "archived mode")
          _ <- service.replaceRequirements(operator, Revision(0), "Every change carries a focused test.")
          written <- service.replaceMode(operator, Revision(0), ProjectSetting.Mode(ProcessMode.CrossCutting, false))
          _ <- ZIO.foreachDiscard(body) { value =>
            database.transaction { connection =>
              new Jdbc(connection).execute("UPDATE cq_project_settings SET body = ?::jsonb WHERE project_id = ? AND kind = 'Mode'") { s =>
                s.setString(1, Wire.encode(ProjectSetting_JsonCodec, value)); s.setObject(2, operator.project.value)
              }
            }.flatMap(edited => assertIO(edited == 1))
          }
          file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
          manifest <- archives.backup(operator.project, file)
          _ <- assertIO(manifest.entries.last.table == BackupTable.Settings && manifest.entries.last.rows == 2)
          target <- fresh
          restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
          _ <- ZIO.attempt(Files.deleteIfExists(file))
        } yield (operator, written, restored, target)
      }
      for {
        kept <- archived(None)
        (operator, written, restored, target) = kept
        _ <- assertIO(restored.isRight)
        copy <- new PostgresLedgerRepository(target).transact(operator.project)(tx => ProjectSettingKind.values.toList.map(tx.setting))
        _ <- assertIO(copy(1).contains(StoredSetting(Revision(1), ProjectSetting.Mode(ProcessMode.CrossCutting, false), operator.actor, written.change.get.at)) &&
          copy(0).exists(_.value == ProjectSetting.Requirements("Every change carries a focused test.")))
        refused <- ZIO.foreach(List[ProjectSetting](ProjectSetting.Mode(ProcessMode.Yolo, false), ProjectSetting.Mode(ProcessMode.CrossCutting, true),
          ProjectSetting.Requirements("A requirements document in the mode row")))(body => archived(Some(body)))
        _ <- ZIO.foreachDiscard(refused) { case (scope, _, outcome, schema) =>
          for {
            _ <- ZIO.attempt(assert(outcome.left.exists { case DomainFailure(_: Fault.Invalid) => true; case _ => false }, outcome.map(_.project).toString))
            projects <- new PostgresLedgerRepository(schema).projects(None, 200)
            _ <- assertIO(!projects.projects.exists(_.id == scope.project))
          } yield ()
        }
      } yield ()
    }

    "I17: round-trip the project's agent configuration without the installation's and refuse one that the write path refuses" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO], config: DatabaseConfig, archives: ProjectArchives) =>
      val separator = if (config.url.contains("?")) "&" else "?"
      def fresh: IO[Throwable, LedgerDatabase] = {
        val schema = "cq_restore_" + UUID.randomUUID().toString.replace("-", "")
        val target = new LedgerDatabase(config.copy(url = config.url + separator + "currentSchema=" + schema))
        ZIO.attemptBlocking(Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
          Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema")); ()
        }) *> target.initialize.as(target)
      }
      // A hand-edited archive is reproduced by storing the row past the service: backup copies the table as it is.
      def archived(text: String): IO[Throwable, (Scope, StoredSetting, Either[Throwable, BackupManifest], LedgerDatabase)] = {
        val operator = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
        val stored = StoredSetting(Revision(3), ProjectSetting.Agents(text), operator.actor, 1700000000000L)
        for {
          _ <- service.initialize(operator, "archived agents")
          _ <- repository.transact(operator.project)(_.putSetting(stored))
          file <- ZIO.attempt(Files.createTempFile("cq-archive-", ".zip"))
          manifest <- archives.backup(operator.project, file)
          _ <- assertIO(manifest.entries.last.table == BackupTable.Settings && manifest.entries.last.rows == 1)
          target <- fresh
          restored <- new PostgresProjectArchives(target, Clock.systemUTC()).restore(file).either
          _ <- ZIO.attempt(Files.deleteIfExists(file))
        } yield (operator, stored, restored, target)
      }
      for {
        kept <- archived("defaults: { roles: { worker: claude:sonnet } } # λ😀\n")
        (operator, stored, restored, target) = kept
        _ <- ZIO.attempt(assert(restored.isRight, restored.toString))
        current <- service.agents(operator)
        source <- service.replaceAgents(operator, AgentsScope.Installation(), current.installation.revision, s"# ${operator.project.value}\n")
        again <- archived("defaults: { roles: { explorer: claude:haiku } }\n")
        copy <- new PostgresLedgerRepository(target).transact(operator.project)(tx => tx.setting(ProjectSettingKind.Agents) -> tx.installationSetting(InstallationSettingKind.Agents))
        // The installation's layer stays with its server: the archive of a project of a server that has one restores without it.
        defaults <- new PostgresLedgerRepository(again._4).transact(again._1.project)(tx => tx.setting(ProjectSettingKind.Agents) -> tx.installationSetting(InstallationSettingKind.Agents))
        _ <- assertIO(copy == (Some(stored), None) && source.installation.revision.value > 0 && again._3.isRight && defaults == (Some(again._2), None))
        refused <- ZIO.foreach(List("defaults: [", "defaults: { roles: { worker: { all: [claude:sonnet], min: 1 } } }", "#" + "x" * LedgerPolicy.MaxConfigBytes))(archived)
        _ <- ZIO.foreachDiscard(refused) { case (scope, _, outcome, schema) =>
          for {
            _ <- ZIO.attempt(assert(outcome.left.exists { case DomainFailure(_: Fault.Invalid) => true; case _ => false }, outcome.map(_.project).toString))
            projects <- new PostgresLedgerRepository(schema).projects(None, 200)
            _ <- assertIO(!projects.projects.exists(_.id == scope.project))
          } yield ()
        }
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
