package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import izumi.distage.testkit.model.TestConfig.Parallelism
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, ZIO}

abstract class CatalogueContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef {
      make[Clock].fromValue(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
    },
    // Exact global catalogue increments require a separate environment after concurrent suites settle.
    parallelEnvs = Parallelism.Sequential,
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]]),
  )

  "Live catalogue (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "publish independent committed clocks, bound snapshots and enforce watch scope" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO],
        admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val token = "catalogue-contract-operator-token-32"
        val auth = new Authorization(AccessConfig(token, "http://localhost"), Clock.systemUTC())
        val root = auth.authenticate(token, Some(UUID.randomUUID().toString))
        val app = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth)
        val project = ProjectId(UUID.randomUUID())
        val config = ProjectConfig(project, "http://localhost", "Catalogue fixture")
        val scope = LiveScope(true, Some(project))
        val worker = auth.authenticate(auth.grant(root, GrantRequest(project, Actor("worker", SessionId(UUID.randomUUID()), Role.Worker), System.currentTimeMillis() + 60000)).value, None)
        for {
          before <- repository.catalogueCursor
          created <- app.execute(root, Command.Initialize(config))
          _ <- assertIO(created.isInstanceOf[Result.Initialized])
          initial <- app.liveRevision(root, scope)
          _ <- assertIO(initial.catalogue.contains(CatalogueCursor(before.value + 1)) && initial.project.contains(ProjectCursors(project, ChangeCursor(0), 0, 0)))
          _ <- app.execute(root, Command.Initialize(config))
          _ <- app.execute(root, Command.RenameProject(project, Revision(1), config.name))
          same <- app.liveRevision(root, scope)
          _ <- assertIO(same == initial)
          _ <- app.execute(root, Command.RenameProject(project, Revision(1), "Renamed"))
          renamed <- app.liveRevision(root, scope)
          _ <- assertIO(renamed.catalogue.contains(CatalogueCursor(before.value + 2)) && renamed.project == initial.project)
          rollback <- repository.transact(project) { tx =>
            tx.renameProject(tx.project.copy(name = "Rolled back", revision = Revision(3)))
            throw new IllegalStateException("Abort catalogue transaction")
          }.either
          _ <- assertIO(rollback.isLeft)
          afterRollback <- app.liveRevision(root, scope)
          _ <- assertIO(afterRollback == renamed)
          draft = ItemDraft("Task", "Fixture", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable"), None, Nil), Nil)
          _ <- app.execute(root, Command.Change(ChangeInput(project, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(draft)), Nil, "Clock fixture"))))
          itemChanged <- app.liveRevision(root, scope)
          _ <- assertIO(itemChanged.catalogue == renamed.catalogue && itemChanged.project.contains(ProjectCursors(project, ChangeCursor(1), 0, 0)))
          _ <- app.ingest(root, HostUsageInput(project, HostUsage.Assign(Assignment(AssignmentId(UUID.randomUUID()), project, Set.empty, Attribution.Unattributed, None, None))))
          usageChanged <- app.liveRevision(root, scope)
          _ <- assertIO(usageChanged.catalogue == renamed.catalogue && usageChanged.project.exists(p => p.items == ChangeCursor(1) && p.usage > 0))
          _ <- ZIO.foreachPar((1 to 8).toList)(n => app.execute(root, Command.Initialize(ProjectConfig(ProjectId(UUID.randomUUID()), "http://localhost", s"Concurrent $n"))))
          concurrent <- repository.catalogueCursor
          _ <- assertIO(concurrent == CatalogueCursor(before.value + 10))
          result <- app.execute(root, Command.Projects(None, None, 1))
          page <- ZIO.attempt(result match { case Result.Projects(page) => page; case other => throw new AssertionError(other) })
          _ <- assertIO(page.projects.size == 1 && page.hasMore && page.cursor == concurrent)
          continued <- app.execute(root, Command.Projects(page.after, Some(page.cursor), 1))
          _ <- assertIO(continued match { case Result.Projects(next) => next.cursor == page.cursor && next.projects.head.id != page.projects.head.id; case _ => false })
          noSnapshot <- app.execute(root, Command.Projects(page.after, None, 1))
          _ <- assertIO(noSnapshot match { case Result.Failed(_: Fault.Invalid) => true; case _ => false })
          _ <- app.execute(root, Command.RenameProject(project, Revision(2), "Latest"))
          stale <- app.execute(root, Command.Projects(page.after, Some(page.cursor), 1))
          _ <- assertIO(stale match { case Result.Failed(_: Fault.Resync) => true; case _ => false })
          denied <- app.liveRevision(worker, scope).either
          _ <- assertIO(denied match { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
          allowed <- app.liveRevision(worker, LiveScope(false, Some(project)))
          _ <- assertIO(allowed.catalogue.isEmpty && allowed.project == usageChanged.project)
          foreign <- app.liveRevision(worker, LiveScope(false, Some(ProjectId(UUID.randomUUID())))).either
          _ <- assertIO(foreign match { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
        } yield ()
    }
  }
}

final class CatalogueContractDummy extends CatalogueContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class CatalogueContractPostgres extends CatalogueContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
