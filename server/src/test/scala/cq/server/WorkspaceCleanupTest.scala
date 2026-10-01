package cq.server

import cq.api.*
import cq.core.Scope
import cq.host.{DispatchProjection, HostFiles}
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.util.UUID
import zio.ZIO

final class WorkspaceCleanupLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))

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
          spec.attempt, value, None, Nil, DispatchProjection.EmptyCounts, ChildNext.Wait, None, None, None, true, true, None)), 16384))
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
  }
}
