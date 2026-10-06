package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import cq.host.*
import java.net.URI
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.time.{Clock, Duration}
import java.util.UUID
import logstage.IzLogger
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import zio.{IO, Task, UIO, ZIO}

/** Workspace services of this state root's sessions; each session keeps its records under its own directory. */
final class SessionWorkspaces(config: SupervisorConfig, clock: Clock) {
  def at(session: Path): WorkspaceService[IO] = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(session.resolve("workspaces"),
    // Worktree creation and removal move whole checkouts; the ten-second bound of the other Git probes is too short for them.
    new BoundedHostCommand(GitEnvironment.isolated(config.environment), Duration.ofMinutes(5), 65536), clock))
}

/**
 * The running session's workspaces. Removing a worktree can take longer than the watchdog's drain, so nothing on the finish path waits
 * for one: once the session is stopping a removal is postponed until the receipt and the final delivery are done (`finish`), and one
 * already running is no longer awaited. What `finish` does not complete stays `Open` and is removed by the next host startup.
 */
final class SessionRelease(delegate: WorkspaceService[IO], watchdog: SupervisorWatchdog) extends WorkspaceService[IO] {
  private val PollMillis = 20L
  /** Left of the drain for the receipt to reach its reader and the host to exit in order. */
  private val ExitMargin = Duration.ofSeconds(2)
  private var postponed = List.empty[(Scope, AttemptId)]
  override def prepare(scope: Scope, spec: WorkspaceSpec): IO[Throwable, WorkspaceRecord] = delegate.prepare(scope, spec)
  override def get(scope: Scope, attempt: AttemptId): IO[Throwable, WorkspaceRecord] = delegate.get(scope, attempt)
  override def quarantine(scope: Scope, attempt: AttemptId, reason: String): IO[Throwable, WorkspaceRecord] = delegate.quarantine(scope, attempt, reason)
  override def prune(scope: Scope, repository: String): IO[Throwable, Int] = delegate.prune(scope, repository)
  /** Owners release from finalizers, where nothing can be interrupted: a removal runs on its own fiber and is observed, not raced. */
  private def observed[A](removal: IO[Throwable, A], abandon: () => Boolean): IO[Throwable, Option[A]] = removal.forkDaemon.flatMap { fiber =>
    (fiber.poll <* ZIO.sleep(zio.Duration.fromMillis(PollMillis))).repeatUntil(exit => exit.nonEmpty || abandon()).flatMap(ZIO.foreach(_)(ZIO.done(_)))
  }
  override def remove(scope: Scope, attempt: AttemptId): IO[Throwable, WorkspaceRecord] = ZIO.suspendSucceed {
    if (watchdog.stopping) ZIO.succeed(synchronized { postponed = postponed :+ (scope, attempt) }) *> delegate.get(scope, attempt)
    else observed(delegate.remove(scope, attempt), () => watchdog.stopping).flatMap(ZIO.fromOption(_).orElse(delegate.get(scope, attempt)))
  }
  /** Run by the finish sequence after the receipt and the final delivery, within what is left of the drain. */
  def finish: UIO[Unit] = observed(ZIO.foreachDiscard(synchronized(postponed))((scope, attempt) => delegate.remove(scope, attempt).ignore),
    () => watchdog.remaining.compareTo(ExitMargin) <= 0).ignore
}

/** Collector authority for another session of this host's project, obtained as `cq job upload` obtains it. */
trait SessionCollectors {
  def collector(run: SupervisorRun): ServerApi
}

final class HttpSessionCollectors(config: SupervisorConfig, clock: Clock) extends SessionCollectors {
  private val RequestTimeout = Duration.ofSeconds(30)
  private val CredentialLifetime = Duration.ofHours(1)
  override def collector(run: SupervisorRun): ServerApi = {
    val endpoint = URI.create(run.project.endpoint)
    val root = new HttpServerApi(endpoint, HostCredential.read(config.environment), run.attempt.session, RequestTimeout)
    val token = root.grant(GrantRequest(run.project.project, Actor("CQ host collector", run.attempt.session, Role.Collector),
      Math.addExact(clock.millis(), CredentialLifetime.toMillis)))
    new HttpServerApi(endpoint, token.value, run.attempt.session, RequestTimeout)
  }
}

object WorkspaceCleanup {
  /** One startup recovery ends after `budget` and examines at most `sessions` ended sessions; those not reached are left for the next
    * host startup. Its receipt is at most `receiptBytes` long. */
  final case class Bounds(budget: Duration, sessions: Int, receiptBytes: Int)
  val Default: Bounds = Bounds(Duration.ofMinutes(5), 4096, 1024 * 1024)
  val Unsettled = "Owning session ended before the job settled; termination is unconfirmed"
  val Unpublished = "Child result is not published as Completed; run cq job upload"
  private val MaxRecordBytes = 64 * 1024
  private val MaxProblemCharacters = 300

  /** Written into a session's own directory once startup recovery has nothing left to do there; later startups skip the session. */
  val Marker = "recovery.json"
  private val Host = s"${SupervisorRun.baboonDomainIdentifier} ${SupervisorRun.baboonDomainVersion} " + ProcessHandle.current().info().command().orElse("unknown executable")
  val Upload = "cq job upload --session"

  private enum Disposition {
    case Unrelated
    case Live(session: SessionId)
    case Examined(report: SessionCleanup, marked: Option[RecoveryOutcome])
  }
  private final case class Outcome(live: List[SessionId], sessions: List[SessionCleanup], recovered: Int, abandoned: Int, unexamined: Int, deadlineExceeded: Boolean) {
    def examined: Int = live.size + sessions.size
  }

  private def problem(prefix: String, error: Throwable): String =
    (prefix + ": " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName)).take(MaxProblemCharacters)

  private def childDirectories(session: Path): List[Path] = {
    val root = session.resolve("children")
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
    else Using.resource(Files.list(root))(_.iterator().asScala.filter(path => Files.exists(path.resolve("ticket.json"), LinkOption.NOFOLLOW_LINKS)).toList)
  }

  /** A child's `receipt.json` is written once its result, usage and admission are acknowledged; only a Completed one releases its tree. */
  private def completed(child: Path): Boolean = {
    val receipt = child.resolve("receipt.json")
    Files.exists(receipt, LinkOption.NOFOLLOW_LINKS) && HostFiles.read(receipt, DispatchStatus_JsonCodec, MaxRecordBytes).phase == DispatchPhase.Completed
  }

  /** What `cq job upload --session` would still resolve (`pending`), and the tickets whose preparation was cut before anything was
    * frozen or reserved (`unfrozen`): nothing can resolve those, and `cq job upload` reports them as unresolved every time. */
  final case class UploadRemainder(pending: List[String], unfrozen: Int)

  private enum Leftover {
    case Resolved, Reservation, Request, Combination, Unfrozen
  }

  private def counted(count: Int, what: String): Option[String] = Option.when(count > 0)(s"$count $what")

  /**
   * Reads what `SessionUpload` resolves beyond the deliveries, without resolving it: startup recovery launches no Git update, prepares
   * no work, publishes no combination and writes no observation. An integration is resolved when the server recorded a terminal
   * resolution for it, or when it was prepared and never reserved; a frozen combination when its plan's publication is acknowledged.
   */
  def unresolved(session: Path, run: SupervisorRun, server: () => ServerApi): UploadRemainder = {
    def stems(name: String): Set[String] = {
      val root = session.resolve(name)
      if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Set.empty
      else Using.resource(Files.list(root))(_.iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".json")).map(_.stripSuffix(".json")).toSet)
    }
    val journals = stems("integrations")
    val integrations = (stems("integration-requests") ++ journals).toList.sorted.map { name =>
      val id = IntegrationId(UUID.fromString(name))
      val local = Option.when(journals(name))(HostFiles.read(session.resolve("integrations").resolve(name + ".json"), IntegrationLocal_JsonCodec, IntegrationEntries.MaxRecordBytes))
      val record = server().call(Command.Read(ReadInput(run.project.project, ReadSelection.Integration(id)))) match {
        case Result.Integration(value) => Some(value)
        case Result.Failed(_: Fault.Missing) => None
        case Result.Failed(fault) => throw DomainFailure(fault)
        case _ => throw new IllegalStateException("Integration read returned an unexpected result")
      }
      val terminal = record.exists(_.resolution != IntegrationResolution.Pending())
      (local, record) match {
        case (Some(journal), Some(reserved)) => if (terminal && reserved.intent == journal.intent) Leftover.Resolved else Leftover.Reservation
        // Prepared and never reserved, or refused by the server: nothing was launched and nothing is held.
        case (Some(journal), None) => if (IntegrationEntries.unreserved(journal)) Leftover.Resolved else Leftover.Reservation
        case (None, Some(_)) => if (terminal) Leftover.Resolved else Leftover.Request
        case (None, None) => Leftover.Unfrozen
      }
    }
    val root = session.resolve("combinations")
    val combinations = if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Nil else Using.resource(Files.list(root))(_.iterator().asScala.toList).map { entry =>
      require(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS), "Retained combination is not a directory")
      val queue = entry.resolve("delivery")
      if (!Files.exists(entry.resolve("plan.json"), LinkOption.NOFOLLOW_LINKS)) Leftover.Unfrozen
      else if (Files.exists(queue.resolve("000000.json"), LinkOption.NOFOLLOW_LINKS) && !SessionDelivery.unacknowledged(queue)) Leftover.Resolved
      else Leftover.Combination
    }
    val found = integrations ++ combinations
    UploadRemainder(List(
      counted(found.count(_ == Leftover.Reservation), "integration reservations without a terminal resolution (integrations/)"),
      counted(found.count(_ == Leftover.Request), "integration requests reserved without a local journal (integration-requests/)"),
      counted(found.count(_ == Leftover.Combination), "frozen combinations not published (combinations/)")).flatten, found.count(_ == Leftover.Unfrozen))
  }

  /**
   * Disposes of what a session whose host is gone left under `session`: an open workspace of an unsettled job is quarantined,
   * a child's is kept until its result is published as Completed, every other settled job's is removed. A workspace the governing
   * session opened for its own work has no job: it is removed once its result is published as Completed and quarantined otherwise,
   * with what the session wrote in it, and reported. `expired` ends the sweep early.
   */
  def sweep(owner: Scope, session: Path, records: List[JobRecord], workspaces: WorkspaceService[IO], expired: () => Boolean): Task[SessionCleanup] =
    ZIO.attemptBlocking(childDirectories(session).map(child => child.getFileName.toString -> child).toMap).flatMap { children =>
      jobs(owner, records, children, workspaces, expired).flatMap(own(owner, _, children -- records.map(_.workspace.attempt.value.toString), workspaces, expired))
    }

  // Only the governing session's own work prepares a workspace without a job, so only the tickets no job names are read.
  private def own(owner: Scope, swept: SessionCleanup, jobless: Map[String, Path], workspaces: WorkspaceService[IO], expired: () => Boolean): Task[SessionCleanup] =
    ZIO.foldLeft(jobless.toList.sortBy(_._1))(swept) { case (report, (name, child)) =>
      val attempt = AttemptId(UUID.fromString(name))
      def retain(reason: String): SessionCleanup = report.copy(retained = report.retained :+ RetainedWorkspace(attempt, reason))
      def quarantined(reason: String): SessionCleanup = report.copy(quarantined = report.quarantined :+ RetainedWorkspace(attempt, reason))
      if (expired()) ZIO.succeed(report)
      else ZIO.attemptBlocking(GoverningTickets.workspace(HostFiles.read(child.resolve("ticket.json"), DispatchTicket_JsonCodec, MaxRecordBytes))).either.flatMap {
        case Left(error) => ZIO.succeed(retain(problem("Child ticket unreadable", error)))
        case Right(false) => ZIO.succeed(report)
        case Right(true) => workspaces.get(owner, attempt).either.flatMap {
          case Left(DomainFailure(_: Fault.Missing)) => ZIO.succeed(report)
          case Left(error) => ZIO.succeed(retain(problem("Workspace record unreadable", error)))
          // Kept by this recovery or by the delivery it ran before the sweep; one the session's own host kept is its operator's already.
          case Right(workspace) if workspace.admission == WorkspaceAdmission.Quarantined =>
            ZIO.succeed(if (workspace.quarantineReason.contains(GoverningTickets.Abandoned)) quarantined(GoverningTickets.Abandoned) else report)
          case Right(workspace) if workspace.admission != WorkspaceAdmission.Open => ZIO.succeed(report)
          case Right(_) => ZIO.attemptBlocking(completed(child)).either.flatMap {
            case Left(error) => ZIO.succeed(retain(problem("Child receipt unreadable", error)))
            // The candidate is a commit and its result is admitted: the host ended between the receipt and the removal.
            case Right(true) => workspaces.remove(owner, attempt).either.map {
              case Right(removed) if removed.admission == WorkspaceAdmission.Removed => report.copy(removed = report.removed :+ attempt)
              case Right(refused) => quarantined(refused.quarantineReason.getOrElse("Removal refused"))
              case Left(error) => retain(problem("Removal failed", error))
            }
            case Right(false) => workspaces.quarantine(owner, attempt, GoverningTickets.Abandoned).either.map {
              case Right(_) => quarantined(GoverningTickets.Abandoned)
              case Left(error) => retain(problem("Quarantine failed", error))
            }
          }
        }
      }
    }

  private def jobs(owner: Scope, records: List[JobRecord], children: Map[String, Path], workspaces: WorkspaceService[IO], expired: () => Boolean): Task[SessionCleanup] =
    ZIO.foldLeft(records.sortBy(_.workspace.attempt.value.toString))(SessionCleanup(owner.actor.session, Nil, Nil, Nil, 0, None)) { (report, record) =>
      val attempt = record.workspace.attempt
      def retain(reason: String): SessionCleanup = report.copy(retained = report.retained :+ RetainedWorkspace(attempt, reason))
      def quarantined(reason: String): SessionCleanup = report.copy(quarantined = report.quarantined :+ RetainedWorkspace(attempt, reason))
      if (expired()) ZIO.succeed(report)
      else workspaces.get(owner, attempt).either.flatMap {
        case Left(DomainFailure(_: Fault.Missing)) => ZIO.succeed(report)
        case Left(error) => ZIO.succeed(retain(problem("Workspace record unreadable", error)))
        case Right(workspace) if workspace.admission != WorkspaceAdmission.Open => ZIO.succeed(report)
        case Right(_) if record.phase != JobPhase.Settled => workspaces.quarantine(owner, attempt, Unsettled).either.map {
          case Right(_) => quarantined(Unsettled)
          case Left(error) => retain(problem("Quarantine failed", error))
        }
        case Right(_) => ZIO.attemptBlocking(children.get(attempt.value.toString).forall(completed)).either.flatMap {
          case Left(error) => ZIO.succeed(retain(problem("Child receipt unreadable", error)))
          case Right(false) => ZIO.succeed(retain(Unpublished))
          case Right(true) => workspaces.remove(owner, attempt).either.map {
            case Right(removed) if removed.admission == WorkspaceAdmission.Removed => report.copy(removed = report.removed :+ attempt)
            case Right(refused) => quarantined(refused.quarantineReason.getOrElse("Removal refused"))
            case Left(error) => retain(problem("Removal failed", error))
          }
        }
      }
    }
}

/**
 * Startup housekeeping for the sessions an earlier host of this project left in the state root. Workspaces of the running session
 * are released by their owners as soon as nothing reads their tree again (`JobSupervisor.release`); nothing here runs at shutdown.
 */
final class WorkspaceCleanup(config: SupervisorConfig, sessions: SessionWorkspaces, collectors: SessionCollectors, bounds: WorkspaceCleanup.Bounds,
  clock: Clock, logger: IzLogger) {
  import WorkspaceCleanup.*

  /** The session directories no startup recovery has finished with, oldest first by the time their host recorded them. Whether
    * `run.json` can be reached is not asked here: a directory whose record is absent, is not a regular file or cannot be stat-ed is
    * examined last, where reading it reports that as the session's problem, finds its host alive or finds that nothing was recorded. */
  private def candidates: List[Path] = {
    val root = config.directory.getParent
    val own = config.directory.getFileName.toString
    Using.resource(Files.list(root))(_.iterator().asScala.filter { path =>
      val name = path.getFileName.toString
      name != own && Try(UUID.fromString(name)).toOption.exists(_.toString == name) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        !Files.exists(path.resolve(Marker), LinkOption.NOFOLLOW_LINKS)
    }.map(path => (Try(Files.getLastModifiedTime(path.resolve("run.json"), LinkOption.NOFOLLOW_LINKS).toMillis).getOrElse(Long.MaxValue), path.getFileName.toString, path)).toList)
      .sortBy(value => (value._1, value._2)).map(_._3)
  }

  private def mark(directory: Path, outcome: RecoveryOutcome, reason: String): Unit =
    HostFiles.immutable(directory.resolve(Marker), HostFiles.encode(SessionRecovery_JsonCodec, SessionRecovery(outcome, reason, clock.millis(), Host, ProducingBuild.value)), MaxRecordBytes)

  /** Every host holds the exclusive lock on its `journal/owner.lock` for its lifetime. */
  private def ownerRuns(directory: Path): Boolean = {
    val lock = directory.resolve("journal").resolve("owner.lock")
    Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && Using.resource(FileChannel.open(lock, StandardOpenOption.WRITE)) { channel =>
      (try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }).fold(true) { held => held.release(); false }
    }
  }

  /** A session that can never be examined is recorded as such, once: bytes of its `run.json` or of a job record were read and are
    * not such a record, and its owner is gone. Failing to reach a record proves nothing about it; that is reported and the session
    * examined again at the next startup. */
  private def abandon(directory: Path, session: SessionId, reason: String): Disposition = {
    mark(directory, RecoveryOutcome.Abandoned, reason)
    Disposition.Examined(SessionCleanup(session, Nil, Nil, Nil, 0, Some(reason)), Some(RecoveryOutcome.Abandoned))
  }

  /** A host takes its lock before it writes `run.json` and writes everything else after it. A directory holding at most that lock
    * was never recorded as a session and holds nothing to recover, deliver or report, whichever project its host served. */
  private def unrecorded(directory: Path): Boolean = {
    def names(path: Path): Set[String] = Using.resource(Files.list(path))(_.iterator().asScala.map(_.getFileName.toString).toSet)
    val journal = directory.resolve("journal")
    names(directory).subsetOf(Set("journal")) && (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS) || names(journal).subsetOf(Set("owner.lock")))
  }

  private def unexamined(session: SessionId, error: Throwable): Disposition =
    Disposition.Examined(SessionCleanup(session, Nil, Nil, Nil, 0, Some(problem("Session could not be examined", error))), None)

  /**
   * `run.json` is written after its session has taken the exclusive lock on `journal/owner.lock`, which the operating system releases
   * when that host process ends: acquiring the lock therefore proves the owner is gone, and holding it excludes `cq job upload`.
   * A failure of one session's examination is that session's problem and never ends the pass.
   */
  private def session(directory: Path, expired: () => Boolean): UIO[Disposition] = {
    val id = SessionId(UUID.fromString(directory.getFileName.toString))
    ZIO.scoped {
      // Reaching the record and decoding it are separate steps: only bytes that were read can show that a record is undecodable.
      ZIO.attemptBlocking(HostFiles.bytes(directory.resolve("run.json"), MaxRecordBytes)).either.flatMap {
        // Not reached. A host that has not recorded itself yet holds its lock; otherwise the fault is reported and nothing concluded.
        case Left(error) => ZIO.attemptBlocking {
          if (ownerRuns(directory)) Disposition.Live(id) else if (unrecorded(directory)) Disposition.Unrelated else unexamined(id, error)
        }
        case Right(bytes) => ZIO.attempt(HostFiles.decode(bytes, SupervisorRun_JsonCodec)).either.flatMap {
          case Left(error) => ZIO.attemptBlocking {
            if (ownerRuns(directory)) Disposition.Live(id) else abandon(directory, id, problem("Session record run.json could not be decoded", error))
          }
          case Right(run) if run.project.project != config.project.project || run.repository != config.run.repository || run.attempt.session != id =>
            ZIO.succeed(Disposition.Unrelated)
          case Right(run) => ZIO.acquireRelease(ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session)).either)(
            opened => ZIO.attemptBlocking(opened.foreach(_.close())).orDie).flatMap {
            case Left(DomainFailure(_: Fault.Conflict)) => ZIO.succeed(Disposition.Live(id))
            // The lock was free, so the owner is gone and the record it left will not change.
            case Left(error: JobRecordUndecodable) => ZIO.attemptBlocking(abandon(directory, id, problem("Job journal could not be decoded", error)))
            case Left(error) => ZIO.fail(error)
            case Right(_) if Files.exists(directory.resolve(Marker), LinkOption.NOFOLLOW_LINKS) => ZIO.succeed(Disposition.Unrelated)
            case Right(journal) => examine(directory, run, journal, expired)
          }
        }
      }
    }.catchAll(error => ZIO.succeed(unexamined(id, error))).catchAllDefect(error => ZIO.succeed(unexamined(id, error)))
  }

  /**
   * The owner is gone and this recovery holds its journal lock. The session is marked `Recovered` when `cq job upload --session`
   * would find nothing to deliver and nothing to resolve, and no workspace is `Open`; quarantined trees stay for the operator and do
   * not keep it pending, nor does a ticket or usage sample that was never committed and so can never be delivered.
   */
  private def examine(directory: Path, run: SupervisorRun, journal: JobRepository, expired: () => Boolean): Task[Disposition] = {
    val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
    val workspaces = sessions.at(directory)
    val delivery = new SessionDelivery(journal, workspaces, clock)
    // One grant serves the flush and the integration reads of a session, and none is requested for a session that needs neither.
    lazy val collector = collectors.collector(run)
    for {
      interrupted <- ZIO.attemptBlocking(delivery.interrupt())
      before <- ZIO.attemptBlocking(SessionDelivery.remainder(directory, run))
      delivered <- (if (before.undelivered) ZIO.attemptBlocking(collector).flatMap(delivery.flush(directory, run, _)).map(Some(_)) else ZIO.none).either
      swept <- sweep(owner, directory, journal.records, workspaces, expired)
      after <- ZIO.attemptBlocking(SessionDelivery.remainder(directory, run)).either
      upload <- ZIO.attemptBlocking(unresolved(directory, run, () => collector)).either
      marked <- ZIO.attemptBlocking {
        val incomplete = delivered.toOption.flatten.fold(before.incomplete)(_.incompleteTickets.size)
        val notes = List(
          upload.toOption.filter(_.pending.nonEmpty).map(value => s"Left for $Upload: " + value.pending.mkString(", ")),
          upload.left.toOption.map(problem(s"Integrations and combinations could not be examined; run $Upload", _)),
          delivered.left.toOption.map(problem("Delivery reconciliation failed", _)),
          after.left.toOption.map(problem("Deliveries could not be examined", _)),
          counted(incomplete, "incomplete tickets or usage samples retained for inspection"),
          upload.toOption.flatMap(value => counted(value.unfrozen, "integration requests or combination tickets never frozen, retained for inspection")),
          counted(interrupted.size, "unsettled jobs recorded as Uncertain")).flatten
        val report = swept.copy(acknowledged = delivered.toOption.flatten.fold(0)(_.acknowledged),
          problem = Option.when(notes.nonEmpty)(notes.mkString("; ").take(MaxProblemCharacters)))
        val settled = delivered.isRight && after.exists(!_.undelivered) && upload.exists(_.pending.isEmpty) && report.retained.isEmpty && !expired()
        if (settled) mark(directory, RecoveryOutcome.Recovered, (s"${report.removed.size} workspaces removed, ${report.quarantined.size} quarantined, " +
          s"${report.acknowledged} delivery batches acknowledged" + report.problem.fold("")("; " + _)).take(MaxProblemCharacters))
        Disposition.Examined(report, Option.when(settled)(RecoveryOutcome.Recovered))
      }
    } yield marked
  }

  private def prune: UIO[Unit] = sessions.at(config.directory).prune(config.owner, config.run.repository)
    .flatMap(count => ZIO.succeed(logger.info(s"Pruned $count stale worktree registrations from ${config.run.repository}")))
    .catchAll(error => ZIO.succeed(logger.warn(s"Worktree pruning did not complete: ${error.getMessage}")))

  /** The receipt counts everything and lists the first entries that fit its byte bound. */
  private def receipt(startedAt: Long, outcome: Outcome, reported: List[SessionCleanup]): WorkspaceCleanupReceipt = {
    val totals = CleanupTotals(outcome.sessions.size, outcome.live.size, outcome.recovered, outcome.abandoned, reported.map(_.removed.size).sum,
      reported.map(_.quarantined.size).sum, reported.map(_.retained.size).sum, reported.map(_.acknowledged).sum, reported.count(_.problem.nonEmpty), outcome.unexamined)
    val finishedAt = clock.millis()
    def listing(live: List[SessionId], sessions: List[SessionCleanup]): WorkspaceCleanupReceipt =
      WorkspaceCleanupReceipt(config.owner.actor.session, startedAt, finishedAt, outcome.deadlineExceeded, live, sessions, totals)
    def fits(value: WorkspaceCleanupReceipt): Boolean = HostFiles.encode(WorkspaceCleanupReceipt_JsonCodec, value).getBytes(UTF_8).length <= bounds.receiptBytes
    val live = outcome.live.inits.find(value => fits(listing(value, Nil))).getOrElse(Nil)
    reported.inits.map(listing(live, _)).find(fits).getOrElse(listing(live, Nil))
  }

  /**
   * Run in the background once the session has recorded itself: reconciles the pending deliveries (including the Finish usage) and
   * the workspaces of this project's sessions whose host is gone, records `workspaces/cleanup.json` and never fails the session.
   * It may be cut at any instant: every removal and quarantine rewrites its own record before the next begins.
   */
  def recover: UIO[Unit] = prune *> (for {
    startedAt <- ZIO.succeed(clock.millis())
    expired = () => clock.millis() - startedAt >= bounds.budget.toMillis
    found <- ZIO.attemptBlocking(candidates)
    outcome <- ZIO.foldLeft(found)(Outcome(Nil, Nil, 0, 0, 0, false)) { (outcome, directory) =>
      if (outcome.deadlineExceeded || expired()) ZIO.succeed(outcome.copy(unexamined = outcome.unexamined + 1, deadlineExceeded = true))
      else if (outcome.examined >= bounds.sessions) ZIO.succeed(outcome.copy(unexamined = outcome.unexamined + 1))
      else session(directory, expired).map {
        case Disposition.Unrelated => outcome
        case Disposition.Live(value) => outcome.copy(live = outcome.live :+ value)
        case Disposition.Examined(report, marked) => outcome.copy(sessions = outcome.sessions :+ report,
          recovered = outcome.recovered + marked.count(_ == RecoveryOutcome.Recovered), abandoned = outcome.abandoned + marked.count(_ == RecoveryOutcome.Abandoned))
      }
    }
    _ <- ZIO.attemptBlocking {
      val reported = outcome.sessions.filter(value => value.removed.nonEmpty || value.quarantined.nonEmpty || value.retained.nonEmpty || value.acknowledged > 0 || value.problem.nonEmpty)
      val recorded = receipt(startedAt, outcome.copy(deadlineExceeded = outcome.deadlineExceeded || expired()), reported)
      val directory = config.directory.resolve("workspaces")
      Files.createDirectories(directory)
      HostFiles.immutable(directory.resolve("cleanup.json"), HostFiles.encode(WorkspaceCleanupReceipt_JsonCodec, recorded), bounds.receiptBytes)
      logger.info(s"Startup recovery examined ${outcome.sessions.size} ended sessions, skipped ${outcome.live.size} live ones and left ${outcome.unexamined} unexamined; receipt under $directory")
      recorded.sessions.foreach { value =>
        val removed = value.removed.size
        val kept = value.quarantined.size + value.retained.size
        val acknowledged = value.acknowledged
        logger.info(s"Ended session ${value.session.value}: removed $removed worktrees, kept $kept, acknowledged $acknowledged delivery batches")
        (value.quarantined ++ value.retained).foreach(kept => logger.info(s"Retained workspace ${kept.attempt.value}: ${kept.reason}"))
        value.problem.foreach(text => logger.warn(s"Ended session ${value.session.value}: $text"))
      }
    }
  } yield ()).catchAll(error => ZIO.succeed(logger.warn(s"Startup recovery did not complete: ${error.getMessage}")))
}
