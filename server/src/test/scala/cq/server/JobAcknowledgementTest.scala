package cq.server

import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.*
import java.nio.file.Files
import java.time.{Clock, Duration}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import zio.{IO, Promise, Runtime, Unsafe, ZIO}

final class JobAcknowledgementLocal extends AnyWordSpec {
  "Journal acknowledgements (Behavioral Active Blackbox filesystem Good Communication)" should {
    "wait for a slow reservation without disabling admission or cancelling its jobs" in {
      val root = Files.createTempDirectory("cq-slow-ack")
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("fixture", SessionId(UUID.randomUUID()), Role.Governor))
      val spec = WorkspaceSpec(owner.project, owner.actor.session, AttemptId(UUID.randomUUID()), root.toString, GitCommit("a" * 40))
      val record = WorkspaceRecord(spec, root.toString, WorkspaceAdmission.Open,
        Some(WorkspaceObservation(spec.base, root.toString, root.toString, 1)), None)
      val driver = new ExecutionDriver {
        override def start(value: ExecutionSpec): ManagedExecution = throw new IllegalStateException("Cancelled preparation must not launch")
      }
      val program = ZIO.scoped {
        for {
          release <- Promise.make[Nothing, Unit]
          service = new WorkspaceService[IO] {
            override def prepare(scope: Scope, value: WorkspaceSpec) = release.await.as(record)
            override def get(scope: Scope, attempt: AttemptId) = ZIO.succeed(record)
            override def quarantine(scope: Scope, attempt: AttemptId, reason: String) = ZIO.succeed(record.copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some(reason)))
            override def remove(scope: Scope, attempt: AttemptId) = ZIO.succeed(record.copy(admission = WorkspaceAdmission.Removed))
            override def prune(scope: Scope, repository: String) = ZIO.succeed(0)
          }
          repository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(root.resolve("journal"), owner.project, owner.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(workspace: WorkspaceSpec, fingerprint: String, now: Long) = { Thread.sleep(1200); delegate.reserve(workspace, fingerprint, now) }
              override def replace(previous: JobRecord, next: JobRecord): Unit = delegate.replace(previous, next)
              override def close(): Unit = delegate.close()
            }
          }
          supervisor <- JobSupervisor.acquire(owner, repository, service, driver, root.resolve("payload"), Clock.systemUTC())
          command = JobCommand(List("fixture"), Map.empty, "", ExecutionLimits(Duration.ofSeconds(2), None,
            Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1), 4096))
          _ <- (for {
            started <- supervisor.start(owner, spec, command).either
            _ <- ZIO.attempt(assert(started.isRight, started.toString))
            cancelled <- supervisor.cancel(owner, spec.attempt)
            _ <- ZIO.attempt(assert(cancelled.target == JobTarget.Stop))
            retried <- supervisor.start(owner, spec, command)
            _ <- ZIO.attempt(assert(retried.target == JobTarget.Stop))
          } yield ()).ensuring(release.succeed(()))
        } yield ()
      }
      Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(program).getOrThrowFiberFailure() }
    }
  }
}
