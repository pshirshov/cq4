package cq.server

import cq.api.*
import cq.core.Scope
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import logstage.IzLogger
import scala.util.Using
import zio.{Task, ZIO}

final class WorkspaceCleanupLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def uuid: UUID = UUID.randomUUID()

  private final class Receiver extends ServerApi {
    override def usage(value: HostUsageInput): HostUsageResult = value.operation match {
      case HostUsage.Assign(assignment) => HostUsageResult.Assigned(assignment)
      case HostUsage.Start(attempt) => HostUsageResult.Started(attempt)
      case HostUsage.Finish(outcome) => HostUsageResult.Finished(outcome)
      case HostUsage.Span(span) => HostUsageResult.Spanned(span)
      case other => throw new IllegalStateException("Unexpected fixture usage: " + other.getClass.getSimpleName)
    }
    override def artifact(value: ArtifactUpload): ArtifactMetadata = ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture",
      value.body.getBytes(UTF_8).length, value.body.codePointCount(0, value.body.length), Actor("fixture", SessionId(uuid), Role.Collector), 1)
    override def call(value: Command): Result = throw new IllegalStateException("Fixture receiver does not execute commands")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Fixture receiver does not admit results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Fixture receiver does not integrate")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Fixture receiver does not grant authority")
  }

  /** A state root shared by the sessions of one project and repository, with the collector grants its startup recoveries requested. */
  private final class State(local: LocalWorkspaceFixture) {
    val root: Path = Files.createTempDirectory(local.directory, "state-")
    val project: ProjectConfig = ProjectConfig(ProjectId(uuid), "http://localhost", "Startup recovery")
    val grants = new AtomicInteger(0)
    private val limits = HostLimits(3000, 1000, 300, 2000, 262144)
    private val profile = HarnessSetting(Harness.Claude, "/unused/claude", "fixture", "anthropic", HarnessUsage.version(Harness.Claude), Nil, Set.empty)
    private val collectors = new SessionCollectors {
      override def collector(run: SupervisorRun): ServerApi = { grants.incrementAndGet(); new Receiver }
    }
    def run(owner: ProjectConfig, repository: String): SupervisorRun = {
      val assignment = Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None)
      val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Claude,
        "unobserved-interactive-provider", "unobserved-interactive-model", "fixture", 1000, UsagePhase.Govern)
      SupervisorRun(owner, assignment, attempt, profile.version, repository, local.base, SessionOwnership.Attached)
    }
    def settings(run: SupervisorRun): SupervisorConfig = SupervisorConfig(
      SupervisorSettings(root.toString, "/unused/guardian", List(profile), limits, Nil, None, None), run.project, SupervisorConfig.profile(profile),
      SupervisorConfig.limits(limits), run, root.resolve(run.attempt.session.value.toString), "", None, sys.env)
    /** The startup recovery of a new session of this project; its receipt is read back from that session's directory. */
    def recover(bounds: WorkspaceCleanup.Bounds): Task[WorkspaceCleanupReceipt] = {
      val config = settings(run(project, local.source.toString))
      val clock = Clock.systemUTC()
      ZIO.attemptBlocking(HostFiles.directory(config.directory)) *>
        new WorkspaceCleanup(config, new SessionWorkspaces(config, clock), collectors, bounds, clock, IzLogger.NullLogger).recover *>
        ZIO.attemptBlocking {
          val receipt = HostFiles.read(config.directory.resolve("workspaces").resolve("cleanup.json"), WorkspaceCleanupReceipt_JsonCodec, 16 * 1024 * 1024)
          RecoveryState.discard(config.directory)
          receipt
        }
    }
    /** An earlier session as its host recorded it; `settled` jobs are in its journal and their workspaces prepared. */
    def session(run: SupervisorRun, settled: Int): Task[Ended] = {
      val directory = root.resolve(run.attempt.session.value.toString)
      val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
      val specs = List.fill(settled)(WorkspaceSpec(run.project.project, run.attempt.session, AttemptId(uuid), run.repository, local.base))
      val service = new SessionWorkspaces(settings(run), Clock.systemUTC()).at(directory)
      for {
        _ <- ZIO.attemptBlocking {
          HostFiles.directory(directory)
          HostFiles.immutable(directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, run), 65536)
          Using.resource(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session)) { journal =>
            specs.foreach { spec =>
              val (record, _) = journal.reserve(spec, "0" * 64, 1)
              journal.replace(record, record.copy(target = JobTarget.Stop, phase = JobPhase.Settled, revision = 2))
            }
          }
        }
        _ <- ZIO.foreachDiscard(specs)(service.prepare(owner, _))
      } yield Ended(directory, run, owner, specs.map(_.attempt), service)
    }
    def session(settled: Int): Task[Ended] = session(run(project, local.source.toString), settled)
  }
  private final case class Ended(directory: Path, run: SupervisorRun, owner: Scope, attempts: List[AttemptId], service: cq.core.WorkspaceService[zio.IO]) {
    def admissions: Task[List[WorkspaceAdmission]] = ZIO.foreach(attempts)(service.get(owner, _)).map(_.map(_.admission))
    def marker: Path = directory.resolve("recovery.json")
    def recovery: Option[SessionRecovery] = Option.when(Files.exists(marker))(HostFiles.read(marker, SessionRecovery_JsonCodec, 65536))
    /** Commits and acknowledges the governing final publication, as a host that finished in order leaves it. */
    def finish(): Unit = {
      val queue = new DeliveryQueue(directory.resolve("delivery"))
      queue.commit(List(HostDelivery.Usage(HostUsageInput(run.project.project, HostUsage.Finish(AttemptOutcome(RequestId(uuid), run.attempt.id, AttemptState.Unknown, 2000, Nil, None))))))
      queue.flush(new Receiver)
    }
    /** A dispatched child whose host died before sealing its publication: a committed ticket, no `publication.json`, no receipt. */
    def unsealedChild(attempt: AttemptId): Unit = {
      val limits = HostLimits(3000, 1000, 300, 2000, 65536)
      val members = List(ItemRevision(ItemId(run.project.project, Ledger.Tasks, 1), Revision(1)))
      val assignment = Assignment(AssignmentId(uuid), run.project.project, members.map(_.id).toSet, Attribution.Direct, None, None)
      val profile = HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty)
      val child = Attempt(attempt, assignment.id, Some(run.attempt.id), run.attempt.session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Work)
      val request = DispatchRequest(RequestId(uuid), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, members, Nil, Nil, None, Fence(ClaimId(uuid), 1), limits)
      val at = directory.resolve("children").resolve(attempt.value.toString)
      HostFiles.directory(at)
      HostFiles.immutable(at.resolve("ticket.json"), HostFiles.encode(DispatchTicket_JsonCodec, DispatchTicket(request, assignment, child, profile, None)), 65536)
    }
    def held[A](use: => Task[A]): Task[A] = ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), run.project.project, run.attempt.session)))(journal => ZIO.succeed(journal.close()))(_ => use)
  }

  "Ended-session workspace cleanup (Behavioral Active Blackbox; local Git Communication)" should {
    "remove settled workspaces, keep unpublished children, quarantine unsettled jobs and leave quarantined ones alone" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val fixture = local.fixture
      val service = fixture.service
      val check = fixture.spec(owner)
      val quarantined = fixture.spec(owner)
      val running = fixture.spec(owner)
      val completed = fixture.spec(owner)
      val unpublished = fixture.spec(owner)
      val failed = fixture.spec(owner)
      val unprepared = fixture.spec(owner)
      val prepared = List(check, quarantined, running, completed, unpublished, failed)
      def record(spec: WorkspaceSpec, phase: JobPhase): JobRecord = JobRecord(spec, "0" * 64, JobTarget.Run, phase, None, None, 1, 1, 1)
      val records = (unprepared :: prepared).map(spec => record(spec, if (spec == running) JobPhase.Running else JobPhase.Settled))
      def listed: Set[String] = local.git(local.source, "worktree", "list", "--porcelain").linesIterator.filter(_.startsWith("worktree ")).map(_.stripPrefix("worktree ")).toSet
      def child(session: Path, spec: WorkspaceSpec, phase: Option[DispatchPhase]): Unit = {
        val directory = session.resolve("children").resolve(spec.attempt.value.toString)
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve("ticket.json"), "{}", 16)
        phase.foreach(value => HostFiles.immutable(directory.resolve("receipt.json"), HostFiles.encode(DispatchStatus_JsonCodec, DispatchStatus(RequestId(UUID.randomUUID()),
          spec.attempt, value, None, Nil, DispatchProjection.EmptyCounts, ChildNext.Wait, None, None, None, true, true, None, None)), 16384))
      }
      for {
        session <- ZIO.attemptBlocking {
          val session = Files.createTempDirectory(local.directory, "session-")
          child(session, completed, Some(DispatchPhase.Completed))
          child(session, unpublished, None)
          child(session, failed, Some(DispatchPhase.Failed))
          session
        }
        _ <- ZIO.foreachDiscard(prepared)(spec => service.prepare(owner, spec))
        _ <- service.quarantine(owner, quarantined.attempt, "Termination unconfirmed")
        expired <- WorkspaceCleanup.sweep(owner, session, records, service, () => true)
        report <- WorkspaceCleanup.sweep(owner, session, records, service, () => false)
        again <- WorkspaceCleanup.sweep(owner, session, records, service, () => false)
        states <- ZIO.foreach(prepared)(spec => service.get(owner, spec.attempt))
        _ <- ZIO.attemptBlocking {
          val kept = List(unpublished, failed).map(spec => RetainedWorkspace(spec.attempt, WorkspaceCleanup.Unpublished)).sortBy(_.attempt.value.toString)
          assert(expired == SessionCleanup(owner.actor.session, Nil, Nil, Nil, 0, None))
          assert(report.session == owner.actor.session && report.removed.toSet == Set(check.attempt, completed.attempt), report.toString)
          assert(report.quarantined == List(RetainedWorkspace(running.attempt, WorkspaceCleanup.Unsettled)) && report.retained == kept, report.toString)
          assert(again == SessionCleanup(owner.actor.session, Nil, Nil, kept, 0, None), again.toString)
          assert(states.map(_.admission) == List(WorkspaceAdmission.Removed, WorkspaceAdmission.Quarantined, WorkspaceAdmission.Quarantined,
            WorkspaceAdmission.Removed, WorkspaceAdmission.Open, WorkspaceAdmission.Open))
          assert(states(1).quarantineReason.contains("Termination unconfirmed") && states(2).quarantineReason.contains(WorkspaceCleanup.Unsettled))
          val remaining = states.filter(_.admission != WorkspaceAdmission.Removed)
          assert(states.filter(_.admission == WorkspaceAdmission.Removed).forall(value => !Files.exists(Path.of(value.directory))) &&
            remaining.forall(value => Files.exists(Path.of(value.directory).resolve("tracked.txt"))))
          assert(listed == (local.source :: remaining.map(value => Path.of(value.directory))).map(_.toRealPath().toString).toSet)
        }
      } yield ()
    }

    "mark a recovered session and skip it at later startups without a lock, a grant or a flush, although its child never sealed a publication" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      for {
        ended <- state.session(1)
        _ <- ZIO.attemptBlocking(ended.unsealedChild(ended.attempts.head))
        first <- state.recover(WorkspaceCleanup.Default)
        marked <- ZIO.attemptBlocking(ended.recovery)
        states <- ended.admissions
        // A recovery that examined the session again would find its journal lock held and list it as live.
        second <- ended.held(state.recover(WorkspaceCleanup.Default))
        _ <- ZIO.attempt {
          println(s"Recovered session: first=$first marker=$marked second=$second grants=${state.grants.get()}")
          assert(first.sessions.map(value => (value.session, value.problem)) == List((ended.run.attempt.session, None)) && first.sessions.head.acknowledged > 0, first.toString)
          assert(first.totals.examined == 1 && first.totals.recovered == 1 && first.totals.abandoned == 0 && first.totals.unexamined == 0, first.toString)
          assert(states == List(WorkspaceAdmission.Quarantined) && marked.exists(value => value.outcome == RecoveryOutcome.Recovered && value.recordedAt > 0 &&
            value.host.nonEmpty && value.reason.nonEmpty), s"$states $marked")
          assert(second.live.isEmpty && second.sessions.isEmpty && second.totals == CleanupTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0) && state.grants.get() == 1,
            s"$second grants=${state.grants.get()}")
        }
      } yield ()
    }

    "not keep a delivered session pending for its quarantined trees" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      for {
        ended <- state.session(2)
        _ <- ZIO.attemptBlocking(ended.finish())
        _ <- ended.service.quarantine(ended.owner, ended.attempts.head, "Termination unconfirmed")
        first <- state.recover(WorkspaceCleanup.Default)
        states <- ended.admissions
        marked <- ZIO.attemptBlocking(ended.recovery)
        second <- ended.held(state.recover(WorkspaceCleanup.Default))
        _ <- ZIO.attempt {
          println(s"Quarantined tree: first=$first marker=$marked second=$second")
          assert(states == List(WorkspaceAdmission.Quarantined, WorkspaceAdmission.Removed) && first.sessions.map(_.removed) == List(List(ended.attempts(1))), s"$states $first")
          assert(marked.exists(_.outcome == RecoveryOutcome.Recovered) && second.live.isEmpty && second.sessions.isEmpty && state.grants.get() == 0, s"$marked $second")
        }
      } yield ()
    }

    "remove a running session's workspace at once, also inside a finalizer, and postpone removals to the end of the finish sequence once the session stops" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      val run = state.run(state.project, local.source.toString)
      val owner = Scope(run.project.project, Actor("CQ governor", run.attempt.session, Role.Governor))
      val specs = List.fill(3)(WorkspaceSpec(run.project.project, run.attempt.session, AttemptId(uuid), run.repository, local.base))
      val halted = new AtomicInteger(0)
      ZIO.acquireReleaseWith(ZIO.succeed(new SupervisorWatchdog(state.settings(run), () => System.nanoTime(), _ => { halted.incrementAndGet(); () })))(
        watchdog => ZIO.succeed(watchdog.close())) { watchdog =>
        val release = new SessionRelease(local.fixture.service, watchdog)
        for {
          _ <- ZIO.foreachDiscard(specs)(release.prepare(owner, _))
          direct <- release.remove(owner, specs(0).attempt).timeoutFail(new IllegalStateException("Removal did not return"))(zio.Duration.fromSeconds(30))
          // Owners release a check's workspace from a finalizer, where nothing can be interrupted.
          finalized <- ZIO.unit.ensuring(release.remove(owner, specs(1).attempt).orDie).disconnect
            .timeoutFail(new IllegalStateException("Removal inside a finalizer did not return"))(zio.Duration.fromSeconds(30)) *> release.get(owner, specs(1).attempt)
          _ <- ZIO.succeed(watchdog.beginShutdown())
          postponed <- release.remove(owner, specs(2).attempt)
          _ <- release.finish.uninterruptible
          finished <- release.get(owner, specs(2).attempt)
          _ <- ZIO.attempt(assert(List(direct, finalized, postponed, finished).map(_.admission) ==
            List(WorkspaceAdmission.Removed, WorkspaceAdmission.Removed, WorkspaceAdmission.Open, WorkspaceAdmission.Removed) && halted.get() == 0))
        } yield ()
      }
    }

    "deliver a span an ended session retained after its final publication" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      for {
        ended <- state.session(0)
        span <- ZIO.attemptBlocking {
          ended.finish()
          val span = PhaseSpan(RequestId(uuid), ended.run.assignment.id, ended.run.attempt.session, UsagePhase.Check, 1000, 2000, AttemptState.Cancelled)
          new SpanDelivery(ended.directory.resolve("spans"), ended.run.project.project).retain(span)
          ended.directory.resolve("spans").resolve(span.id.value.toString)
        }
        receipt <- state.recover(WorkspaceCleanup.Default)
        _ <- ZIO.attemptBlocking {
          println(s"Retained span: $receipt marker=${ended.recovery}")
          assert(Files.exists(span.resolve("000000.ack")) && state.grants.get() == 1 && receipt.sessions.map(_.acknowledged) == List(1), receipt.toString)
          assert(ended.recovery.exists(_.outcome == RecoveryOutcome.Recovered))
        }
      } yield ()
    }

    "abandon a session whose run record cannot be decoded once, and leave one whose owner still runs" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      def undecodable(): Path = {
        val directory = state.root.resolve(uuid.toString)
        HostFiles.directory(directory.resolve("journal"))
        HostFiles.immutable(directory.resolve("run.json"), "{\"project\":", 64)
        directory
      }
      for {
        directories <- ZIO.attemptBlocking((undecodable(), undecodable()))
        (ended, running) = directories
        first <- ZIO.acquireReleaseWith(ZIO.attemptBlocking(java.nio.channels.FileChannel.open(running.resolve("journal").resolve("owner.lock"),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)).map(channel => (channel, channel.lock())))(
          held => ZIO.succeed(held._1.close()))(_ => state.recover(WorkspaceCleanup.Default))
        second <- state.recover(WorkspaceCleanup.Default)
        third <- state.recover(WorkspaceCleanup.Default)
        _ <- ZIO.attemptBlocking {
          val marker = HostFiles.read(ended.resolve("recovery.json"), SessionRecovery_JsonCodec, 65536)
          println(s"Undecodable session: first=$first marker=$marker second=$second third=$third")
          def named(directory: Path): SessionId = SessionId(UUID.fromString(directory.getFileName.toString))
          assert(first.sessions.map(_.session) == List(named(ended)) && first.sessions.head.problem.exists(_.startsWith("Session record run.json could not be decoded")) &&
            first.live == List(named(running)) && first.totals.abandoned == 1, first.toString)
          assert(marker.outcome == RecoveryOutcome.Abandoned && marker.reason == first.sessions.head.problem.get)
          // Once its owner is gone the second one is abandoned too; neither is examined again.
          assert(second.sessions.map(_.session) == List(named(running)) && second.live.isEmpty && third.sessions.isEmpty && third.totals.examined == 0, s"$second $third")
        }
      } yield ()
    }

    "leave sessions of another project or repository and sessions whose host still runs untouched" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      for {
        foreign <- state.session(state.run(ProjectConfig(ProjectId(uuid), "http://localhost", "Another project"), local.source.toString), 1)
        // The recorded repository is another path to the same checkout: recovery compares the recorded string.
        elsewhere <- state.session(state.run(state.project, local.source.resolve(".").toString), 1)
        live <- state.session(1)
        receipt <- live.held(state.recover(WorkspaceCleanup.Default))
        states <- ZIO.foreach(List(foreign, elsewhere, live))(_.admissions)
        _ <- ZIO.attemptBlocking {
          println(s"Unrelated and live sessions: $receipt")
          assert(receipt.live == List(live.run.attempt.session) && receipt.sessions.isEmpty && receipt.totals == CleanupTotals(0, 1, 0, 0, 0, 0, 0, 0, 0, 0), receipt.toString)
          assert(states == List.fill(3)(List(WorkspaceAdmission.Open)) && List(foreign, elsewhere, live).forall(_.recovery.isEmpty) && state.grants.get() == 0)
        }
        // Released, the same session is recovered: its final publication is reconciled and its settled workspace removed.
        after <- state.recover(WorkspaceCleanup.Default)
        removed <- live.admissions
        _ <- ZIO.attempt(assert(after.sessions.map(_.removed) == List(live.attempts) && removed == List(WorkspaceAdmission.Removed) &&
          live.recovery.exists(_.outcome == RecoveryOutcome.Recovered) && state.grants.get() == 1, after.toString))
      } yield ()
    }

    "examine the oldest unrecovered sessions first within the session bound and summarise a receipt that exceeds its byte bound" in { (local: LocalWorkspaceFixture) =>
      val state = new State(local)
      val bounds = WorkspaceCleanup.Bounds(Duration.ofMinutes(5), 6, 1024)
      for {
        // Eight sessions that each report a problem; the directory names do not follow their age.
        directories <- ZIO.attemptBlocking(List.tabulate(8) { index =>
          val directory = state.root.resolve(uuid.toString)
          HostFiles.directory(directory)
          HostFiles.immutable(directory.resolve("run.json"), "not a run record " + "x" * 200, 4096)
          Files.setLastModifiedTime(directory.resolve("run.json"), FileTime.fromMillis(1_000_000L + index * 1000))
          directory
        })
        first <- state.recover(bounds)
        marked <- ZIO.attemptBlocking(directories.map(directory => Files.exists(directory.resolve("recovery.json"))))
        second <- state.recover(bounds)
        remaining <- ZIO.attemptBlocking(directories.map(directory => Files.exists(directory.resolve("recovery.json"))))
        _ <- ZIO.attempt {
          println(s"Bounded recovery: first=${first.totals} listed=${first.sessions.size} marked=$marked second=${second.totals}")
          assert(marked == List.fill(6)(true) ++ List.fill(2)(false) && remaining.forall(identity), s"$marked $remaining")
          assert(first.totals.examined == 6 && first.totals.abandoned == 6 && first.totals.problems == 6 && first.totals.unexamined == 2 && !first.deadlineExceeded, first.toString)
          // The receipt lists the first entries that fit and counts all of them.
          assert(first.sessions.nonEmpty && first.sessions.size < 6 && first.sessions.map(_.session.value.toString) == directories.take(first.sessions.size).map(_.getFileName.toString))
          assert(second.totals.examined == 2 && second.totals.unexamined == 0 && second.sessions.map(_.session.value.toString) == directories.drop(6).map(_.getFileName.toString))
        }
      } yield ()
    }
  }
}
