package cq.server

import cq.api.*
import cq.core.Scope
import cq.host.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import logstage.IzLogger
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{Task, ZIO}

/** The server a startup recovery reaches: it acknowledges deliveries, holds integration reservations and counts what it is asked. */
final class RecoveryServer extends ServerApi {
  val deliveries = new AtomicInteger(0)
  /** Reservations and observations: startup recovery must make none. */
  val integrations = new AtomicInteger(0)
  /** An upload this selects reaches the server and its acknowledgement is lost. */
  @volatile var lose: ArtifactUpload => Boolean = _ => false
  private var records = Map.empty[IntegrationId, IntegrationRecord]
  def reserve(intent: IntegrationIntent): Unit = synchronized { records = records.updated(intent.id, IntegrationRecord(intent, IntegrationResolution.Pending(), 1, None)) }
  def resolve(id: IntegrationId, resolution: IntegrationResolution): Unit = synchronized { records = records.updated(id, records(id).copy(resolution = resolution, resolvedAt = Some(2))) }
  def resolution(id: IntegrationId): Option[IntegrationResolution] = synchronized(records.get(id).map(_.resolution))
  override def usage(value: HostUsageInput): HostUsageResult = {
    deliveries.incrementAndGet()
    value.operation match {
      case HostUsage.Assign(assignment) => HostUsageResult.Assigned(assignment)
      case HostUsage.Start(attempt) => HostUsageResult.Started(attempt)
      case HostUsage.Meter(meter) => HostUsageResult.Metered(meter)
      case HostUsage.Ingest(upload) => HostUsageResult.Ingested(UsageReceipt(upload.observation.id, 1))
      case HostUsage.Finish(outcome) => HostUsageResult.Finished(outcome)
      case HostUsage.Span(span) => HostUsageResult.Spanned(span)
    }
  }
  override def artifact(value: ArtifactUpload): ArtifactMetadata = {
    deliveries.incrementAndGet()
    if (lose(value)) throw new IOException("Injected lost acknowledgement")
    ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture", value.body.getBytes(UTF_8).length,
      value.body.codePointCount(0, value.body.length), Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
  }
  override def call(value: Command): Result = value match {
    case Command.Read(ReadInput(_, ReadSelection.Integration(id))) => synchronized(records.get(id)).map(Result.Integration.apply).getOrElse(Result.Failed(Fault.Missing("No integration")))
    case _ => throw new IllegalStateException("Fixture server reads integration records only")
  }
  override def integrate(value: HostIntegrationInput): IntegrationRecord = {
    integrations.incrementAndGet()
    throw new IllegalStateException("Startup recovery must not reserve or observe an integration")
  }
  override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Fixture server does not admit results")
  override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Fixture server does not grant authority")
}

/** A state root shared by the sessions of one project and repository, with the server and the grants its startup recoveries used. */
final class RecoveryState(val root: Path, val project: ProjectConfig, val repository: String, val base: GitCommit) {
  val server = new RecoveryServer
  val grants = new AtomicInteger(0)
  /** Runs when a recovery requests a grant, that is while it examines a session with something to deliver or resolve. */
  @volatile var onGrant: () => Unit = () => ()
  private def uuid: UUID = UUID.randomUUID()
  private val limits = HostLimits(3000, 1000, 300, 2000, 262144)
  private val collectors = new SessionCollectors {
    def collector(run: SupervisorRun): ServerApi = { grants.incrementAndGet(); onGrant(); server }
  }
  private def profile(harness: Harness): HarnessSetting = HarnessSetting(harness, "/unused/harness", "fixture", "fixture", HarnessUsage.version(harness), Nil, Set.empty)

  /** An attached governing session of `harness`. */
  def run(harness: Harness, session: SessionId): SupervisorRun = {
    val assignment = Assignment(AssignmentId(uuid), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(uuid), assignment.id, None, session, Role.Governor, harness,
      "unobserved-interactive-provider", "unobserved-interactive-model", "fixture", 1000, UsagePhase.Govern)
    SupervisorRun(project, assignment, attempt, HarnessUsage.version(harness), repository, base, SessionOwnership.Attached)
  }
  def settings(run: SupervisorRun, checks: List[ValidationCheck]): SupervisorConfig = {
    val harness = profile(run.attempt.harness)
    SupervisorConfig(SupervisorSettings(root.toString, "/unused/guardian", List(harness), limits, checks, None, Some("refs/heads/integration")), run.project,
      SupervisorConfig.profile(harness), SupervisorConfig.limits(limits), run, root.resolve(run.attempt.session.value.toString), "", None, sys.env)
  }
  def receipt(session: SessionId): Path = root.resolve(session.value.toString).resolve("workspaces").resolve("cleanup.json")

  /** The startup recovery of a new session `starting` of this project; its receipt is read back from that session's directory. */
  def recover(starting: SessionId): Task[WorkspaceCleanupReceipt] = {
    val config = settings(run(Harness.Claude, starting), Nil)
    val clock = Clock.systemUTC()
    ZIO.attemptBlocking(HostFiles.directory(config.directory)) *>
      new WorkspaceCleanup(config, new SessionWorkspaces(config, clock), collectors, WorkspaceCleanup.Default, clock, IzLogger.NullLogger).recover *>
      ZIO.attemptBlocking {
        val recorded = HostFiles.read(receipt(starting), WorkspaceCleanupReceipt_JsonCodec, 16 * 1024 * 1024)
        RecoveryState.discard(config.directory)
        recorded
      }
  }
  def recover: Task[WorkspaceCleanupReceipt] = recover(SessionId(uuid))

  /** An earlier session as its host recorded it: `settled` jobs are in its journal and their workspaces prepared. Sessions are examined
    * in the order they are created here. */
  def session(run: SupervisorRun, settled: Int, checks: List[ValidationCheck]): Task[EndedSession] = {
    val directory = root.resolve(run.attempt.session.value.toString)
    val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
    val specs = List.fill(settled)(WorkspaceSpec(run.project.project, run.attempt.session, AttemptId(uuid), run.repository, base))
    val config = settings(run, checks)
    val service = new SessionWorkspaces(config, Clock.systemUTC()).at(directory)
    for {
      _ <- ZIO.attemptBlocking {
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, run), 65536)
        HostFiles.immutable(directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, config.settings), 65536)
        created += 1
        Files.setLastModifiedTime(directory.resolve("run.json"), FileTime.fromMillis(1_000_000_000L + created * 1000L))
        Using.resource(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session)) { journal =>
          specs.foreach { spec =>
            val (record, _) = journal.reserve(spec, "0" * 64, 1)
            journal.replace(record, record.copy(target = JobTarget.Stop, phase = JobPhase.Settled, revision = 2))
          }
        }
      }
      _ <- ZIO.foreachDiscard(specs)(service.prepare(owner, _))
    } yield EndedSession(this, directory, run, owner, specs.map(_.attempt), service)
  }
  private var created = 0
  def session(harness: Harness, settled: Int): Task[EndedSession] = session(run(harness, SessionId(uuid)), settled, Nil)
  def session(settled: Int): Task[EndedSession] = session(Harness.Claude, settled)
  /** A directory that claims to be a session and holds only what `content` puts under `run.json`. */
  def raw(content: Array[Byte]): Path = {
    val directory = root.resolve(uuid.toString)
    HostFiles.directory(directory.resolve("journal"))
    Files.write(directory.resolve("run.json"), content)
    created += 1
    Files.setLastModifiedTime(directory.resolve("run.json"), FileTime.fromMillis(1_000_000_000L + created * 1000L))
    directory
  }
}

object RecoveryState {
  /**
   * Removes the directory of a starting session once its receipt is read. The fixtures start a recovery without the rest of a host, so
   * this directory has a receipt and no `run.json`; a real host has recorded itself by then and is itself examined by the next startup.
   * The scenarios are about the sessions they build, so the stand-in is not left behind for later startups to report.
   */
  def discard(starting: Path): Unit =
    Using.resource(Files.walk(starting))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  def apply(local: LocalWorkspaceFixture): RecoveryState = new RecoveryState(Files.createTempDirectory(local.directory, "state-"),
    ProjectConfig(ProjectId(UUID.randomUUID()), "http://localhost", "Recovery markers"), local.source.toString, local.base)
}

final case class EndedSession(state: RecoveryState, directory: Path, run: SupervisorRun, owner: Scope, attempts: List[AttemptId], service: cq.core.WorkspaceService[zio.IO]) {
  private def uuid: UUID = UUID.randomUUID()
  def id: SessionId = run.attempt.session
  def admissions: Task[List[WorkspaceAdmission]] = ZIO.foreach(attempts)(service.get(owner, _)).map(_.map(_.admission))
  def marker: Path = directory.resolve("recovery.json")
  def markerText: Option[String] = Option.when(Files.exists(marker))(Files.readString(marker))
  def recovery: Option[SessionRecovery] = Option.when(Files.exists(marker))(HostFiles.read(marker, SessionRecovery_JsonCodec, 65536))
  def outcome: Option[RecoveryOutcome] = recovery.map(_.outcome)
  def reported(receipt: WorkspaceCleanupReceipt): Option[SessionCleanup] = receipt.sessions.find(_.session == id)
  /** Commits and acknowledges the governing final publication, as a host that finished in order leaves it. */
  def finish(): Unit = {
    val queue = new DeliveryQueue(directory.resolve("delivery"))
    queue.commit(List(HostDelivery.Usage(HostUsageInput(run.project.project, HostUsage.Finish(AttemptOutcome(RequestId(uuid), run.attempt.id, AttemptState.Unknown, 2000, Nil, None))))))
    queue.flush(new RecoveryServer)
  }
  def journal[A](use: FileJobRepository => A): A = Using.resource(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session))(use)
  def records: List[JobRecord] = journal(_.records)
  /** What `cq job upload --session` runs first; a recovered session has nothing left for it. */
  def flush: Task[SessionDeliveryReport] = ZIO.scoped {
    ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session)))(journal => ZIO.succeed(journal.close()))
      .flatMap(journal => new SessionDelivery(journal, service, Clock.systemUTC()).flush(directory, run, new RecoveryServer))
  }
  /** A dispatched child whose host died before sealing its publication: a committed ticket, no `publication.json`, no receipt. */
  def child(attempt: AttemptId, work: DispatchWork): DispatchTicket = {
    val limits = HostLimits(3000, 1000, 300, 2000, 65536)
    val members = List(ItemRevision(ItemId(run.project.project, Ledger.Tasks, 1), Revision(1)))
    val assignment = Assignment(AssignmentId(uuid), run.project.project, members.map(_.id).toSet, Attribution.Direct, None, None)
    val profile = HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty)
    val review = work.isInstanceOf[DispatchWork.Reviewer]
    val child = Attempt(attempt, assignment.id, Some(run.attempt.id), run.attempt.session, ChildContracts.role(work), Harness.Codex, "fixture", "fixture", "fixture", 1000,
      if (review) UsagePhase.Review else UsagePhase.Work)
    val request = DispatchRequest(RequestId(uuid), work, Harness.Codex, members, Nil, Nil, Option.when(review)(ArtifactId(uuid)), Fence(ClaimId(uuid), 1), limits)
    val ticket = DispatchTicket(request, assignment, child, profile, None)
    val at = directory.resolve("children").resolve(attempt.value.toString)
    HostFiles.directory(at)
    HostFiles.immutable(at.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, ticket), 65536)
    ticket
  }
  /** The child's result was published as Completed: its receipt is what a finished publication leaves. */
  def completed(ticket: DispatchTicket): Unit = HostFiles.immutable(directory.resolve("children").resolve(ticket.attempt.id.value.toString).resolve("receipt.json"),
    HostFiles.encode(DispatchStatus_JsonCodec, DispatchStatus(ticket.request.request, ticket.attempt.id, DispatchPhase.Completed, None, Nil, DispatchProjection.EmptyCounts,
      ChildNext.Wait, None, None, None, true, true, None, None)), 16384)
  /** The directory of the first run of the reviewer-declared check `name` of `reviewer`. */
  def checkDirectory(reviewer: DispatchTicket, name: String): Path = directory.resolve("children").resolve(reviewer.attempt.id.value.toString).resolve("checks").resolve(name)
  /** A declared check whose evidence was sealed and never acknowledged; `reviewer`'s own job must be in the journal. */
  def sealedCheck(reviewer: DispatchTicket, check: ValidationCheck): Path = {
    val at = checkDirectory(reviewer, check.name)
    HostFiles.directory(at.getParent)
    HostFiles.directory(at)
    val ticket = DeclaredCheckTicket(reviewer.attempt.id, check, WorkspaceSpec(run.project.project, run.attempt.session,
      DeclaredCheckPublication.job(reviewer.attempt.id, check.name, 1), run.repository, state.base), "a" * 64, Nil)
    HostFiles.immutable(at.resolve("ticket.json"), HostFiles.encode(DeclaredCheckTicket_JsonCodec, ticket), 65536)
    new DeclaredCheckPublication(at, ticket, reviewer.assignment.id, directory.resolve("payload")).seal(None, None)
    at
  }
  /** A declared check whose ticket upload was cut: nothing was committed, so nothing can ever be delivered for it. */
  def uncommittedCheck(reviewer: DispatchTicket, name: String): Path = {
    val at = checkDirectory(reviewer, name)
    HostFiles.directory(at.getParent)
    HostFiles.directory(at)
    Files.writeString(at.resolve(".upload-interrupted.pending"), "{\"partial")
    at
  }
  /** A job the host left running, with its workspace `Open`. */
  def running: Task[AttemptId] = {
    val spec = WorkspaceSpec(run.project.project, run.attempt.session, AttemptId(uuid), run.repository, state.base)
    ZIO.attemptBlocking(journal { journal =>
      val (reserved, _) = journal.reserve(spec, "1" * 64, 1)
      val starting = reserved.copy(phase = JobPhase.Starting, revision = 2)
      journal.replace(reserved, starting)
      journal.replace(starting, starting.copy(phase = JobPhase.Running, revision = 3))
    }) *> service.prepare(owner, spec).as(spec.attempt)
  }
  def intent: IntegrationIntent = {
    val id = IntegrationId(uuid)
    IntegrationIntent(id, run.project.project, owner.actor, run.repository, "refs/heads/integration", state.base, GitCommit("c" * 40),
      ArtifactId(uuid), ArtifactId(uuid), Nil, Fence(ClaimId(uuid), 1), Nil, ChangeRequest(RequestId(id.value), Nil, Nil, "Fixture"), None)
  }
  def integrationFile(id: IntegrationId): Path = directory.resolve("integrations").resolve(id.value.toString + ".json")
  /** The integration journal entry as the coordinator writes it: prepared, and `attempted` once Git execution was admitted. */
  def integration(intent: IntegrationIntent, attempted: Boolean): Task[Unit] =
    new FileIntegrationJournal(directory.resolve("integrations"), owner).locked(intent.id) { entry => ZIO.attemptBlocking {
      entry.write(IntegrationLocal(intent, false, None))
      if (attempted) entry.write(IntegrationLocal(intent, true, None))
    } }
  /** The ticket `IntegrationController.prepare` writes before it reviews, rebases and freezes an intent. */
  def integrationRequest(id: IntegrationId): Path = {
    val root = directory.resolve("integration-requests")
    HostFiles.directory(root)
    val path = root.resolve(id.value.toString + ".json")
    HostFiles.immutable(path, HostFiles.encode(IntegrationTicket_JsonCodec, IntegrationTicket(id, ArtifactId(uuid))), 1024)
    path
  }
  /** A retained combination: its ticket, and with `frozen` the plan `cq job upload --session` publishes. */
  def combination(frozen: Boolean): Path = {
    val ticket = CombinationTicket(RequestId(uuid), IntegrationId(uuid), Fence(ClaimId(uuid), 1))
    val entry = directory.resolve("combinations").resolve(ticket.id.value.toString)
    HostFiles.directory(entry.getParent)
    HostFiles.directory(entry)
    HostFiles.immutable(entry.resolve("ticket.json"), HostFiles.encode(CombinationTicket_JsonCodec, ticket), 1024)
    if (frozen) HostFiles.immutable(entry.resolve("plan.json"), HostFiles.encode(CombinationPlan_JsonCodec, CombinationPlan(ticket, run.project.project, owner.actor,
      run.attempt.id, run.repository, "refs/heads/integration", state.base, GitCommit("c" * 40), ArtifactId(uuid),
      List(ItemRevision(ItemId(run.project.project, Ledger.Tasks, 1), Revision(1))))), 65536)
    entry
  }
  /** Everything under the session that recovery may not add to when it prepares and launches nothing. */
  def inventory: Set[String] = {
    def names(path: Path): Set[String] = if (!Files.isDirectory(path)) Set.empty else Using.resource(Files.list(path))(_.iterator().asScala.map(directory.relativize(_).toString).toSet)
    names(directory.resolve("workspaces")) ++ names(directory.resolve("journal")) ++ names(directory.resolve("integrations")) ++ names(directory.resolve("integration-requests")) ++
      names(directory.resolve("combinations")) ++ names(directory.resolve("payload")) ++ names(directory.resolve("checkouts"))
  }
}

/**
 * One startup recovery in its own JVM, so that a fault library can be preloaded into it: `<state root> <project> <repository> <base>
 * <starting session>`. Its receipt is `workspaces/cleanup.json` of the starting session, as for any host.
 */
object RecoveryStartup {
  def main(arguments: Array[String]): Unit = {
    val state = new RecoveryState(Path.of(arguments(0)), ProjectConfig(ProjectId(UUID.fromString(arguments(1))), "http://localhost", "Recovery markers"),
      arguments(2), GitCommit(arguments(3)))
    val starting = SessionId(UUID.fromString(arguments(4)))
    val clock = Clock.systemUTC()
    val config = state.settings(state.run(Harness.Claude, starting), Nil)
    HostFiles.directory(config.directory)
    zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(new WorkspaceCleanup(config, new SessionWorkspaces(config, clock), new SessionCollectors {
        def collector(run: SupervisorRun): ServerApi = state.server
      }, WorkspaceCleanup.Default, clock, IzLogger.NullLogger).recover).getOrThrowFiberFailure()
    }
  }

  /** Runs a startup in a forked JVM with `environment` added, and returns its receipt if it wrote one. */
  def fork(state: RecoveryState, log: Path, environment: Map[String, String]): Option[WorkspaceCleanupReceipt] = {
    val starting = SessionId(UUID.randomUUID())
    val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("Fork fixture classpath is required"))
    // The startup is small; kept to two processors and a serial collector so that it does not disturb the timing of other process suites.
    val builder = new ProcessBuilder(List(Path.of(System.getProperty("java.home"), "bin", "java").toString, "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC",
      "-XX:TieredStopAtLevel=1", "-Xmx256m", "-cp", classpath, "cq.server.RecoveryStartup",
      state.root.toString, state.project.project.value.toString, state.repository, state.base.value, starting.value.toString).asJava)
      .redirectErrorStream(true).redirectOutput(log.toFile)
    builder.environment().putAll(environment.asJava)
    val process = builder.start()
    try require(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "Forked startup recovery did not end\n" + Files.readString(log))
    finally process.destroyForcibly()
    val receipt = state.receipt(starting)
    println(s"Forked startup recovery: exit=${process.exitValue()} receipt=${Files.exists(receipt)}")
    val recorded = Option.when(Files.exists(receipt))(HostFiles.read(receipt, WorkspaceCleanupReceipt_JsonCodec, 16 * 1024 * 1024))
    RecoveryState.discard(state.root.resolve(starting.value.toString))
    recorded
  }
}
