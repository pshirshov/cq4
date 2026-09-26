package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, ZIO}

abstract class ApplicationContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]], DIKey[UsageService[IO]]),
  )
  private val Token = "application-contract-operator-token-32"
  private val Now = 1000000L
  private def authorization(time: Long): Authorization = new Authorization(AccessConfig(Token, "http://localhost"), Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC))
  private def task: ItemDraft = ItemDraft("Task λ", "body", Set.empty, false, Content.Task(TaskStatus.Ready, List("Observable"), None, Nil), Nil)
  private def request: ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(task)), Nil, "Create")

  "Authenticated application (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "scope project and role from signed credentials, preserve sessions, and deny mutation and host ingestion to workers" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(ledger, repository, usage, auth)
        val first = ProjectId(UUID.randomUUID())
        val second = ProjectId(UUID.randomUUID())
        val workerActor = Actor("worker", SessionId(UUID.randomUUID()), Role.Worker)
        val token = auth.grant(root, GrantRequest(first, workerActor, Now + 10000))
        val worker = auth.authenticate(token.value, None)
        for {
          initialized <- application.execute(root, Command.Initialize(ProjectConfig(first, "http://localhost", "first")))
          _ <- assertIO(initialized.isInstanceOf[Result.Initialized])
          _ <- application.execute(root, Command.Initialize(ProjectConfig(second, "http://localhost", "second")))
          reattached <- application.execute(root, Command.Initialize(ProjectConfig(first, "http://localhost", "renamed")))
          _ <- assertIO(reattached == initialized)
          created <- application.execute(root, Command.Change(ChangeInput(first, request)))
          _ <- assertIO(created.isInstanceOf[Result.Changed])
          read <- application.execute(worker, Command.Search(SearchInput(first, ItemFilter(None, ArchiveFilter.All), None, None, 20)))
          _ <- assertIO(read match { case Result.Found(page) => page.items.size == 1; case _ => false })
          denied <- application.execute(worker, Command.Change(ChangeInput(first, request)))
          _ <- assertIO(denied match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          cross <- application.execute(worker, Command.Search(SearchInput(second, ItemFilter(None, ArchiveFilter.All), None, None, 20)))
          _ <- assertIO(cross match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          projects <- application.execute(worker, Command.Projects(None, 20))
          _ <- assertIO(projects.isInstanceOf[Result.Failed])
          assignment = Assignment(AssignmentId(UUID.randomUUID()), first, Set.empty, Attribution.Unattributed, None, None)
          host <- application.ingest(worker, HostUsageInput(first, HostUsage.Assign(assignment))).either
          _ <- assertIO(host match { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
          _ <- assertIO(new McpSchemas().visible(worker).map(_.name).toSet == Set("search", "read", "usage"))
          _ <- assertIO(authorization(Now + 1).authenticate(token.value, None).scope(first).actor == workerActor)
          _ <- assertIO(scala.util.Try(auth.grant(worker, GrantRequest(first, workerActor, Now + 10000))).isFailure)
          _ <- assertIO(scala.util.Try(authorization(Now + 10000).authenticate(token.value, None)).isFailure)
          _ <- assertIO(scala.util.Try(auth.authenticate(token.value + "x", None)).isFailure)
          _ <- assertIO(scala.util.Try(auth.authenticate(Token, None)).isFailure)
        } yield ()
    }

    "preserve mutation acknowledgements across service re-creation and reject mixed snapshot pages" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO]) =>
        val auth = authorization(Now)
        val session = UUID.randomUUID().toString
        val root = auth.authenticate(Token, Some(session))
        val application = new Application(ledger, repository, usage, auth)
        val project = ProjectId(UUID.randomUUID())
        val change = request
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "snapshots")))
          first <- application.execute(root, Command.Change(ChangeInput(project, change)))
          restarted = new Application(ledger, repository, usage, authorization(Now + 1))
          replay <- restarted.execute(authorization(Now + 1).authenticate(Token, Some(session)), Command.Change(ChangeInput(project, change)))
          _ <- assertIO(first == replay)
          _ <- application.execute(root, Command.Change(ChangeInput(project, request)))
          found <- application.execute(root, Command.Search(SearchInput(project, ItemFilter(None, ArchiveFilter.All), None, None, 1)))
          page <- ZIO.attempt(found match { case Result.Found(page) => page; case other => throw new AssertionError(other) })
          _ <- assertIO(page.hasMore && page.items.size == 1)
          next <- application.execute(root, Command.Search(SearchInput(project, ItemFilter(None, ArchiveFilter.All), page.after, Some(page.cursor), 1)))
          _ <- assertIO(next match { case Result.Found(p) => !p.hasMore && p.items.head.id != page.items.head.id; case _ => false })
          _ <- application.execute(root, Command.Change(ChangeInput(project, request)))
          stale <- application.execute(root, Command.Search(SearchInput(project, ItemFilter(None, ArchiveFilter.All), page.after, Some(page.cursor), 1)))
          _ <- assertIO(stale match { case Result.Failed(_: Fault.Resync) => true; case _ => false })
          missing <- application.execute(root, Command.Search(SearchInput(project, ItemFilter(None, ArchiveFilter.All), page.after, None, 1)))
          _ <- assertIO(missing match { case Result.Failed(_: Fault.Invalid) => true; case _ => false })
        } yield ()
    }
  }
}

final class ApplicationContractDummy extends ApplicationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class ApplicationContractPostgres extends ApplicationContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
