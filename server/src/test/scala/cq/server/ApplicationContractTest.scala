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
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
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
          read <- application.execute(worker, Command.Search(SearchInput(first, "archived:all", None, None, 20)))
          _ <- assertIO(read match { case Result.Found(page) => page.items.size == 1; case _ => false })
          denied <- application.execute(worker, Command.Change(ChangeInput(first, request)))
          _ <- assertIO(denied match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          cross <- application.execute(worker, Command.Search(SearchInput(second, "archived:all", None, None, 20)))
          _ <- assertIO(cross match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          projects <- application.execute(worker, Command.Projects(None, None, 20))
          _ <- assertIO(projects.isInstanceOf[Result.Failed])
          assignment = Assignment(AssignmentId(UUID.randomUUID()), first, Set.empty, Attribution.Unattributed, None, None)
          host <- application.ingest(worker, HostUsageInput(first, HostUsage.Assign(assignment))).either
          _ <- assertIO(host match { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
          _ <- assertIO(new McpSchemas().visible(worker).map(_.name).toSet == Set("search", "read", "graph", "usage"))
          _ <- assertIO(authorization(Now + 1).authenticate(token.value, None).scope(first).actor == workerActor)
          _ <- assertIO(scala.util.Try(auth.grant(worker, GrantRequest(first, workerActor, Now + 10000))).isFailure)
          _ <- assertIO(scala.util.Try(authorization(Now + 10000).authenticate(token.value, None)).isFailure)
          _ <- assertIO(scala.util.Try(auth.authenticate(token.value + "x", None)).isFailure)
          _ <- assertIO(scala.util.Try(auth.authenticate(Token, None)).isFailure)
          browser = auth.login(Token, UUID.randomUUID().toString)
          _ <- assertIO(scala.util.Try(authorization(Now + 30L * 24 * 60 * 60 * 1000).authenticate(browser.value, None)).isSuccess)
          day = 24L * 60 * 60 * 1000
          _ <- assertIO(scala.util.Try(authorization(Now + 401 * day).authenticate(browser.value, None)).isFailure)
          later = authorization(Now + 399 * day)
          renewed = later.renew(later.authenticate(browser.value, None))
          _ <- assertIO(authorization(Now + 700 * day).authenticate(renewed.value, None).scope(first) == later.authenticate(browser.value, None).scope(first))
          _ <- assertIO(scala.util.Try(auth.renew(worker)).isFailure)
        } yield ()
    }

    "serve the per-phase usage report to the usage tool" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        val collector = Scope(project, Actor("collector", SessionId(UUID.randomUUID()), Role.Collector))
        val assignment = Assignment(AssignmentId(UUID.randomUUID()), project, Set.empty, Attribution.Unattributed, None, None)
        val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, collector.actor.session, Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None)
        val tool = new McpSchemas().tools.find(_.name == "usage").get
        val input = io.circe.parser.parse(s"""{"project":{"value":"${project.value}"},"selection":{"Phases":{"filter":{"SessionOnly":{"id":{"value":"${collector.actor.session.value}"}}}}}}""").toTry.get
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "phases")))
          _ <- usage.assign(collector, assignment)
          _ <- usage.start(collector, attempt)
          command <- ZIO.fromEither(tool.decode(input))
          result <- application.execute(root, command)
          _ <- assertIO(result match { case Result.UsagePhases(report) => report.phases.map(value => (value.phase, value.attempts, value.running)) == List((UsagePhase.Govern, 1L, 1L)); case _ => false })
          _ <- assertIO(tool.results.contains("UsagePhases") && new McpSchemas().advertised(tool).noSpaces.contains("cq_api_Result_UsagePhases"))
        } yield ()
    }

    "serve the command and agent catalogs through the typed read path to project readers" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val catalog = new CatalogRead(new McpSchemas())
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, catalog)
        val project = ProjectId(UUID.randomUUID())
        val other = ProjectId(UUID.randomUUID())
        val grant = auth.grant(root, GrantRequest(project, Actor("worker", SessionId(UUID.randomUUID()), Role.Worker), Now + 10000)).value
        val worker = auth.authenticate(grant, None)
        // The MCP endpoint a child or a managed Governor reaches; the routes exercised here use none of the omitted collaborators.
        val transport = new Transport(application, auth, AccessConfig(Token, "http://localhost"), new McpSchemas(), null, null, null, null)
        def mcp(method: String, params: String): zio.Task[io.circe.Json] = {
          import org.http4s.{Header, Method, Request, Uri}
          import zio.interop.catz.*
          val body = s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
          transport.routes(null).orNotFound.run(Request[zio.Task](Method.POST, Uri.unsafeFromString("/mcp")).withEntity(body)
            .putHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer " + grant))).flatMap(_.as[String])
            .map(io.circe.parser.parse(_).fold(throw _, identity))
        }
        def selection(value: String): String = s"""{"name":"read","arguments":{"project":{"value":"${project.value}"},"selection":$value}}"""
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "catalog")))
          refused <- mcp("tools/call", selection("""{"Catalog":{}}"""))
          _ <- assertIO(refused.hcursor.downField("result").get[Boolean]("isError") == Right(true))
          _ <- assertIO(refused.hcursor.downField("result").downField("structuredContent").downField("Failed").downField("fault").downField("Denied")
            .get[String]("message") == Right(McpSchemas.CatalogRefusal))
          offered <- mcp("tools/call", selection("""{"Counts":{}}"""))
          _ <- assertIO(offered.hcursor.downField("result").get[Boolean]("isError") == Right(false))
          listed <- mcp("tools/list", "{}")
          _ <- assertIO(listed.hcursor.downField("result").downField("tools").focus.exists(tools => tools.noSpaces.contains("\"ItemDetail\"") && !tools.noSpaces.contains("Catalog")))
          served <- application.execute(root, Command.Read(ReadInput(project, ReadSelection.Catalog())))
          _ <- assertIO(served == Result.Catalog(catalog.value))
          _ <- assertIO(catalog.value.commands.nonEmpty && catalog.value.agents.size == 9)
          scoped <- application.execute(worker, Command.Read(ReadInput(project, ReadSelection.Catalog())))
          _ <- assertIO(scoped == served)
          denied <- application.execute(worker, Command.Read(ReadInput(other, ReadSelection.Catalog())))
          _ <- assertIO(denied match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
        } yield ()
    }

    "I24: move the live work cursor when a claim is acquired, released or expires and not when it is renewed" in {
      (repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        def application(time: Long): Application = new Application(FixedLedger.at(repository, time), repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val start = application(Now)
        val later = application(Now + 2000)
        val project = ProjectId(UUID.randomUUID())
        def cursors(app: Application): IO[Throwable, ProjectCursors] = app.liveRevision(root, LiveScope(false, Some(project))).map(_.project.get)
        def claim(app: Application, action: ClaimAction): IO[Throwable, Claim] = app.execute(root, Command.ClaimWork(ClaimInput(project, action))).flatMap {
          case Result.Claimed(value) => ZIO.succeed(value)
          case other => ZIO.fail(new AssertionError(other))
        }
        for {
          _ <- start.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "work cursor")))
          created <- start.execute(root, Command.Change(ChangeInput(project, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(task), Mutation.Create(task)), Nil, "Create"))))
          ids <- ZIO.attempt(created match { case Result.Changed(ack) => ack.items.map(_.id); case other => throw new AssertionError(other) })
          initial <- cursors(start)
          held <- claim(start, ClaimAction.Acquire(ClaimId(UUID.randomUUID()), Set(ids.head), 300000))
          acquired <- cursors(start)
          _ <- assertIO(acquired.work > initial.work && acquired.items == initial.items && acquired.usage == initial.usage)
          _ <- claim(start, ClaimAction.Acquire(ClaimId(UUID.randomUUID()), Set(ids(1)), 1000))
          leased <- cursors(start)
          _ <- claim(start, ClaimAction.Renew(held.fence, 600000))
          renewed <- cursors(start)
          _ <- assertIO(leased.work > acquired.work && renewed == leased)
          expired <- cursors(later)
          _ <- assertIO(expired.work > renewed.work)
          _ <- claim(later, ClaimAction.Release(held.fence))
          released <- cursors(later)
          _ <- assertIO(released.work > expired.work && released.items == initial.items)
          browsed <- later.execute(root, Command.Read(ReadInput(project, ReadSelection.Browse("wip:true", ItemOrder(ItemOrderField.Id, SortDirection.Ascending, false), None, None, 20))))
          _ <- assertIO(browsed match { case Result.Browsed(page) => page.items.isEmpty && page.work == released.work; case _ => false })
        } yield ()
    }

    "I24: attach the claim owner's running child attempt to a claimed row and move the work cursor when it starts and finishes" in {
      (repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(FixedLedger.at(repository, Now), repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        def live: IO[Throwable, ProjectCursors] = application.liveRevision(root, LiveScope(false, Some(project))).map(_.project.get)
        def browse: IO[Throwable, BrowsePage] = application.execute(root, Command.Read(ReadInput(project,
          ReadSelection.Browse("", ItemOrder(ItemOrderField.Id, SortDirection.Ascending, false), None, None, 20)))).flatMap {
          case Result.Browsed(page) => ZIO.succeed(page)
          case other => ZIO.fail(new AssertionError(other))
        }
        def host(operation: HostUsage): IO[Throwable, HostUsageResult] = application.ingest(root, HostUsageInput(project, operation))
        def attempt(assignment: Assignment, session: SessionId, startedAt: Long): Attempt =
          Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", startedAt, UsagePhase.Work, None)
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "running work")))
          created <- application.execute(root, Command.Change(ChangeInput(project, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Create(task), Mutation.Create(task)), Nil, "Create"))))
          ids <- ZIO.attempt(created match { case Result.Changed(ack) => ack.items.map(_.id); case other => throw new AssertionError(other) })
          assignment = Assignment(AssignmentId(UUID.randomUUID()), project, ids.toSet, Attribution.Shared, None, None)
          _ <- host(HostUsage.Assign(assignment))
          // An attempt left running without a claim marks nothing: the mark requires an active claim.
          orphan = attempt(assignment, SessionId(UUID.randomUUID()), 3000)
          _ <- host(HostUsage.Start(orphan))
          unclaimed <- browse
          _ <- assertIO(unclaimed.items.forall(_.work.isEmpty))
          claimed <- application.execute(root, Command.ClaimWork(ClaimInput(project, ClaimAction.Acquire(ClaimId(UUID.randomUUID()), Set(ids.head), 300000))))
          held <- ZIO.attempt(claimed match { case Result.Claimed(value) => value; case other => throw new AssertionError(other) })
          idle <- browse
          before <- live
          // The other session's attempt covers the item and is not the claim owner's child.
          _ <- assertIO(idle.items.map(_.work.map(_.attempt)) == List(Some(None), None) && idle.work == before.work)
          earlier = attempt(assignment, held.owner.session, 4000)
          child = attempt(assignment, held.owner.session, 5000)
          _ <- host(HostUsage.Start(earlier))
          _ <- host(HostUsage.Start(child))
          running <- browse
          started <- live
          _ <- assertIO(running.items.map(_.work.flatMap(_.attempt)) == List(Some(WorkAttempt(Role.Worker, Harness.Codex, 5000)), None))
          _ <- assertIO(started.work > before.work && running.work == started.work && started.items == before.items)
          _ <- host(HostUsage.Finish(AttemptOutcome(RequestId(UUID.randomUUID()), child.id, AttemptState.Completed, 6000, Nil, None)))
          one <- browse
          _ <- assertIO(one.items.head.work.flatMap(_.attempt).contains(WorkAttempt(Role.Worker, Harness.Codex, 4000)))
          _ <- host(HostUsage.Finish(AttemptOutcome(RequestId(UUID.randomUUID()), earlier.id, AttemptState.Completed, 6000, Nil, None)))
          finished <- browse
          ended <- live
          _ <- assertIO(finished.items.map(_.work.map(_.attempt)) == List(Some(None), None) && ended.work > started.work && finished.work == ended.work)
        } yield ()
    }

    "I24: attach to each of several claimed rows the running attempt of that row's own claim owner" in {
      (repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val first = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val second = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(FixedLedger.at(repository, Now), repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        def marks: IO[Throwable, List[Option[Option[Long]]]] = application.execute(first, Command.Read(ReadInput(project,
          ReadSelection.Browse("", ItemOrder(ItemOrderField.Id, SortDirection.Ascending, false), None, None, 20)))).flatMap {
          case Result.Browsed(page) => ZIO.succeed(page.items.map(_.work.map(_.attempt.map(_.startedAt))))
          case other => ZIO.fail(new AssertionError(other))
        }
        def claim(authority: Authority, members: Set[ItemId]): IO[Throwable, Claim] =
          application.execute(authority, Command.ClaimWork(ClaimInput(project, ClaimAction.Acquire(ClaimId(UUID.randomUUID()), members, 300000)))).flatMap {
            case Result.Claimed(value) => ZIO.succeed(value)
            case other => ZIO.fail(new AssertionError(other))
          }
        def host(operation: HostUsage): IO[Throwable, HostUsageResult] = application.ingest(first, HostUsageInput(project, operation))
        def attempt(assignment: Assignment, session: SessionId, startedAt: Long): Attempt =
          Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", startedAt, UsagePhase.Work, None)
        for {
          _ <- application.execute(first, Command.Initialize(ProjectConfig(project, "http://localhost", "several claimed rows")))
          created <- application.execute(first, Command.Change(ChangeInput(project, ChangeRequest(RequestId(UUID.randomUUID()), List.fill(4)(Mutation.Create(task)), Nil, "Create"))))
          ids <- ZIO.attempt(created match { case Result.Changed(ack) => ack.items.map(_.id); case other => throw new AssertionError(other) })
          one <- claim(first, Set(ids(0), ids(1)))
          two <- claim(second, Set(ids(2)))
          _ <- assertIO(one.owner.session != two.owner.session)
          shared = Assignment(AssignmentId(UUID.randomUUID()), project, Set(ids(0), ids(2)), Attribution.Shared, None, None)
          direct = Assignment(AssignmentId(UUID.randomUUID()), project, Set(ids(1)), Attribution.Direct, None, None)
          _ <- host(HostUsage.Assign(shared))
          _ <- host(HostUsage.Assign(direct))
          earlier = attempt(shared, one.owner.session, 4000)
          // The other owner's later attempt covers the first row as well and must mark only its own row.
          _ <- host(HostUsage.Start(earlier))
          _ <- host(HostUsage.Start(attempt(shared, two.owner.session, 5000)))
          _ <- host(HostUsage.Start(attempt(direct, one.owner.session, 6000)))
          running <- marks
          _ <- assertIO(running == List(Some(Some(4000L)), Some(Some(6000L)), Some(Some(5000L)), None))
          _ <- host(HostUsage.Finish(AttemptOutcome(RequestId(UUID.randomUUID()), earlier.id, AttemptState.Completed, 7000, Nil, None)))
          finished <- marks
          _ <- assertIO(finished == List(Some(None), Some(Some(6000L)), Some(Some(5000L)), None))
        } yield ()
    }

    "rename display metadata with revision comparison and preserve item identity and counters" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val root = auth.authenticate(Token, Some(UUID.randomUUID().toString))
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        val worker = auth.authenticate(auth.grant(root, GrantRequest(project, Actor("worker", SessionId(UUID.randomUUID()), Role.Worker), Now + 10000)).value, None)
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "Original")))
          _ <- application.execute(root, Command.Change(ChangeInput(project, request)))
          denied <- application.execute(worker, Command.RenameProject(project, Revision(1), "Forbidden"))
          _ <- assertIO(denied match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          renamed <- application.execute(root, Command.RenameProject(project, Revision(1), "New display"))
          _ <- assertIO(renamed match { case Result.Initialized(value) => value.id == project && value.name == "New display" && value.revision == Revision(2); case _ => false })
          stale <- application.execute(root, Command.RenameProject(project, Revision(1), "Stale name"))
          _ <- assertIO(stale match { case Result.Failed(_: Fault.Conflict) => true; case _ => false })
          attached <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "New directory")))
          _ <- assertIO(attached == renamed)
          created <- application.execute(root, Command.Change(ChangeInput(project, request)))
          _ <- assertIO(created match { case Result.Changed(ack) => ack.items.head.id.number == 2; case _ => false })
        } yield ()
    }

    "Q32: keep a project's standing requirements under revision comparison, writable by the operator only and bounded" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val session = SessionId(UUID.randomUUID())
        val root = auth.authenticate(Token, Some(session.value.toString))
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        val other = ProjectId(UUID.randomUUID())
        def granted(role: Role) = auth.authenticate(auth.grant(root, GrantRequest(project, Actor(role.toString, SessionId(UUID.randomUUID()), role), Now + 10000)).value, None)
        def read(authority: Authority, target: ProjectId) = application.execute(authority, Command.Requirements(RequirementsInput(target, RequirementsAction.Read())))
        def replace(authority: Authority, expected: Long, text: String) =
          application.execute(authority, Command.Requirements(RequirementsInput(project, RequirementsAction.Replace(Revision(expected), text))))
        val text = "Every change carries a focused test λ😀.\nNo release gate runs in a worker's workspace."
        val operator = Actor("operator", session, Role.Human)
        val bound = LedgerPolicy.MaxRequirementsCodePoints
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "Standing")))
          _ <- application.execute(root, Command.Initialize(ProjectConfig(other, "http://localhost", "Other")))
          initial <- read(root, project)
          _ <- assertIO(initial == Result.Requirements(ProjectRequirements(project, Revision(0), "", None)))
          deniedGovernor <- replace(granted(Role.Governor), 0, text)
          deniedWorker <- replace(granted(Role.Worker), 0, text)
          _ <- assertIO(List(deniedGovernor, deniedWorker).forall { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          written <- replace(root, 0, text)
          _ <- assertIO(written match {
            case Result.Requirements(ProjectRequirements(`project`, Revision(1), `text`, Some(RequirementsChange(`operator`, at)))) => at > 0
            case _ => false
          })
          readers <- ZIO.foreach(List(root, granted(Role.Governor), granted(Role.Planner), granted(Role.Worker), granted(Role.Reviewer)))(read(_, project))
          _ <- assertIO(readers.forall(_ == written))
          stale <- replace(root, 0, "Stale")
          _ <- assertIO(stale match { case Result.Failed(Fault.Conflict(message)) => message.contains("expected revision 0, actual 1"); case _ => false })
          same <- replace(root, 1, text)
          _ <- assertIO(same == written)
          oversized <- replace(root, 1, "😀" * (bound + 1))
          _ <- assertIO(oversized == Result.Failed(Fault.Invalid(s"Standing requirements exceed $bound code points: ${bound + 1} supplied")))
          nul <- replace(root, 1, "a\u0000b")
          _ <- assertIO(nul match { case Result.Failed(_: Fault.Invalid) => true; case _ => false })
          unchanged <- read(root, project)
          _ <- assertIO(unchanged == written)
          full <- replace(root, 1, "😀" * bound)
          _ <- assertIO(full match { case Result.Requirements(value) => value.revision == Revision(2) && value.text == "😀" * bound; case _ => false })
          cleared <- replace(root, 2, "")
          _ <- assertIO(cleared match { case Result.Requirements(value) => value.revision == Revision(3) && value.text.isEmpty && value.change.nonEmpty; case _ => false })
          foreign <- read(granted(Role.Worker), other)
          _ <- assertIO(foreign match { case Result.Failed(_: Fault.Denied) => true; case _ => false })
          separate <- read(root, other)
          _ <- assertIO(separate == Result.Requirements(ProjectRequirements(other, Revision(0), "", None)))
          renamed <- application.execute(root, Command.RenameProject(project, Revision(1), "Renamed"))
          _ <- assertIO(renamed match { case Result.Initialized(value) => value.revision == Revision(2); case _ => false })
        } yield ()
    }

    "preserve mutation acknowledgements across service re-creation and reject mixed snapshot pages" in {
      (ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO]) =>
        val auth = authorization(Now)
        val session = UUID.randomUUID().toString
        val root = auth.authenticate(Token, Some(session))
        val application = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, auth, new CatalogRead(new McpSchemas()))
        val project = ProjectId(UUID.randomUUID())
        val change = request
        for {
          _ <- application.execute(root, Command.Initialize(ProjectConfig(project, "http://localhost", "snapshots")))
          first <- application.execute(root, Command.Change(ChangeInput(project, change)))
          restarted = new Application(ledger, repository, usage, artifacts, admissions, integrations, proposals, authorization(Now + 1), new CatalogRead(new McpSchemas()))
          replay <- restarted.execute(authorization(Now + 1).authenticate(Token, Some(session)), Command.Change(ChangeInput(project, change)))
          _ <- assertIO(first == replay)
          _ <- application.execute(root, Command.Change(ChangeInput(project, request)))
          found <- application.execute(root, Command.Search(SearchInput(project, "archived:all", None, None, 1)))
          page <- ZIO.attempt(found match { case Result.Found(page) => page; case other => throw new AssertionError(other) })
          _ <- assertIO(page.hasMore && page.items.size == 1)
          next <- application.execute(root, Command.Search(SearchInput(project, "archived:all", page.after, Some(page.cursor), 1)))
          _ <- assertIO(next match { case Result.Found(p) => !p.hasMore && p.items.head.id != page.items.head.id; case _ => false })
          _ <- application.execute(root, Command.Change(ChangeInput(project, request)))
          stale <- application.execute(root, Command.Search(SearchInput(project, "archived:all", page.after, Some(page.cursor), 1)))
          _ <- assertIO(stale match { case Result.Failed(_: Fault.Resync) => true; case _ => false })
          missing <- application.execute(root, Command.Search(SearchInput(project, "archived:all", page.after, None, 1)))
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
