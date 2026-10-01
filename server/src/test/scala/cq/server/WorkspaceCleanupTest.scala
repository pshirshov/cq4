package cq.server

import cq.api.*
import cq.core.Scope
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.UUID
import zio.ZIO

final class WorkspaceCleanupLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private val clock = Clock.systemUTC()

  "Governing-session workspace cleanup (Behavioral Active Blackbox; local Git Communication)" should {
    "remove settled open workspaces and retain quarantined, unsettled and pending-integration ones" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val fixture = local.fixture
      val service = fixture.service
      val settled = fixture.spec(owner)
      val quarantined = fixture.spec(owner)
      val running = fixture.spec(owner)
      val pending = fixture.spec(owner)
      val unprepared = fixture.spec(owner)
      def record(spec: WorkspaceSpec, phase: JobPhase): JobRecord = JobRecord(spec, "0" * 64, JobTarget.Run, phase, None, None, 1, 1, 1)
      val records = List(record(settled, JobPhase.Settled), record(quarantined, JobPhase.Settled), record(running, JobPhase.Running),
        record(pending, JobPhase.Settled), record(unprepared, JobPhase.Settled))
      def listed: Set[String] = local.git(local.source, "worktree", "list", "--porcelain").linesIterator.filter(_.startsWith("worktree ")).map(_.stripPrefix("worktree ")).toSet
      def deadline: Long = clock.millis() + WorkspaceCleanup.Budget.toMillis
      for {
        _ <- ZIO.foreachDiscard(List(settled, quarantined, running, pending))(spec => service.prepare(owner, spec))
        _ <- service.quarantine(owner, quarantined.attempt, "Termination unconfirmed")
        expired <- WorkspaceCleanup.sweep(owner, records, Set(pending.attempt), service, clock, clock.millis())
        report <- WorkspaceCleanup.sweep(owner, records, Set(pending.attempt), service, clock, deadline)
        again <- WorkspaceCleanup.sweep(owner, records, Set(pending.attempt), service, clock, deadline)
        states <- ZIO.foreach(List(settled, quarantined, running, pending))(spec => service.get(owner, spec.attempt))
        _ <- ZIO.attemptBlocking {
          assert(expired.deadlineExceeded && expired.removed.isEmpty && expired.quarantined.isEmpty &&
            expired.retained == records.sortBy(_.workspace.attempt.value.toString).map(value => RetainedWorkspace(value.workspace.attempt, WorkspaceCleanup.DeadlineExceeded)), expired.toString)
          assert(!report.deadlineExceeded && report.removed == List(settled.attempt), report.toString)
          assert(report.quarantined == List(RetainedWorkspace(quarantined.attempt, "Termination unconfirmed")), report.toString)
          assert(report.retained.map(_.attempt).toSet == Set(running.attempt, pending.attempt), report.toString)
          assert(again.removed.isEmpty && again.quarantined == report.quarantined && again.retained == report.retained)
          assert(states.map(_.admission) == List(WorkspaceAdmission.Removed, WorkspaceAdmission.Quarantined, WorkspaceAdmission.Open, WorkspaceAdmission.Open))
          assert(!Files.exists(Path.of(states.head.directory)) && states.tail.forall(value => Files.exists(Path.of(value.directory).resolve("tracked.txt"))))
          assert(listed == (local.source :: states.tail.map(value => Path.of(value.directory))).map(_.toRealPath().toString).toSet)
        }
      } yield ()
    }
  }
}
