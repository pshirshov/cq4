package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import distage.{Activation, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.util.Using
import zio.ZIO

final class DummyJobRepository(project: ProjectId, owner: SessionId) extends JobRepository {
  private var state = Map.empty[AttemptId, JobRecord]
  private var closed = false
  override def records: List[JobRecord] = synchronized { require(!closed); state.values.toList }
  override def reserve(workspace: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean) = synchronized {
    require(!closed && workspace.project == project && workspace.owner == owner)
    state.get(workspace.attempt) match {
      case Some(record) =>
        if (record.workspace != workspace || record.fingerprint != fingerprint) throw DomainFailure(Fault.Conflict("Different launch"))
        (record, false)
      case None =>
        if (state.size == JobRecords.MaxJobs) throw DomainFailure(Fault.Limit("Session full"))
        val record = JobRecord(workspace, fingerprint, JobTarget.Run, JobPhase.Preparing, None, None, 1, now, now)
        JobRecords.validate(record)
        state = state.updated(workspace.attempt, record)
        (record, true)
    }
  }
  override def replace(expected: JobRecord, next: JobRecord): Unit = synchronized {
    require(!closed)
    if (!state.get(expected.workspace.attempt).contains(expected)) throw DomainFailure(Fault.Conflict("Stale revision"))
    JobRecords.transition(expected, next)
    state = state.updated(next.workspace.attempt, next)
  }
  override def close(): Unit = synchronized { closed = true }
}

trait JobStorageFixture {
  def open(project: ProjectId, owner: SessionId): JobRepository
}
object JobTestPlugin extends PluginDef {
  include(new ModuleDef {
    tag(Repo.Dummy)
    make[JobStorageFixture].fromValue(new JobStorageFixture {
      override def open(project: ProjectId, owner: SessionId): JobRepository = new DummyJobRepository(project, owner)
    })
  })
  include(new ModuleDef {
    tag(Repo.Prod)
    make[JobStorageFixture].fromValue(new JobStorageFixture {
      override def open(project: ProjectId, owner: SessionId): JobRepository = {
        val root = Path.of(".work").toAbsolutePath.normalize()
        Files.createDirectories(root)
        FileJobRepository.open(Files.createTempDirectory(root, "job-journal-"), project, owner)
      }
    })
  })
}

abstract class JobRepositoryTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(JobTestPlugin)))
  private def spec: WorkspaceSpec = WorkspaceSpec(ProjectId(UUID.randomUUID()), SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/source", GitCommit("a" * 40))

  "Job journal (Behavioral Active Blackbox; dummy Atomic / filesystem Communication)" should {
    "freeze launch identity, replay the original receipt and reject stale state changes" in { (fixture: JobStorageFixture) => ZIO.attemptBlocking {
      val workspace = spec
      Using.resource(fixture.open(workspace.project, workspace.owner)) { repository =>
        val (first, created) = repository.reserve(workspace, "1" * 64, 100)
        assert(created && first.phase == JobPhase.Preparing && first.target == JobTarget.Run)
        assert(repository.reserve(workspace, "1" * 64, 200) == (first, false))
        assert(scala.util.Try(repository.reserve(workspace, "2" * 64, 200)).isFailure)
        assert(scala.util.Try(repository.reserve(workspace.copy(base = GitCommit("b" * 40)), "1" * 64, 200)).isFailure)
        val stopped = first.copy(target = JobTarget.Stop, revision = 2, updatedAt = 200)
        repository.replace(first, stopped)
        assert(scala.util.Try(repository.replace(first, stopped)).isFailure)
        assert(scala.util.Try(repository.replace(stopped, stopped.copy(target = JobTarget.Run, revision = 3))).isFailure)
        val completed = stopped.copy(phase = JobPhase.Settled, revision = 3)
        repository.replace(stopped, completed)
        assert(scala.util.Try(repository.replace(completed, completed.copy(phase = JobPhase.Starting, revision = 4))).isFailure)
        assert(repository.reserve(workspace, "1" * 64, 300) == (completed, false))
        assert(repository.records == List(completed))
      }
    }}

    "enforce session bounds and ownership and never interpret missing observation as success" in { (fixture: JobStorageFixture) => ZIO.attemptBlocking {
      val workspace = spec
      Using.resource(fixture.open(workspace.project, workspace.owner)) { repository =>
        val (first, _) = repository.reserve(workspace, "1" * 64, 100)
        assert(scala.util.Try(repository.reserve(workspace.copy(project = ProjectId(UUID.randomUUID())), "1" * 64, 100)).isFailure)
        assert(scala.util.Try(repository.replace(first, first.copy(phase = JobPhase.Settled, revision = 2))).isFailure)
        val uncertain = first.copy(phase = JobPhase.Uncertain, target = JobTarget.Stop, problem = Some("No acknowledgement"), revision = 2)
        repository.replace(first, uncertain)
        assert(scala.util.Try(repository.replace(uncertain, uncertain.copy(phase = JobPhase.Settled, revision = 3))).isFailure)
        for (_ <- 1 until JobRecords.MaxJobs) repository.reserve(workspace.copy(attempt = AttemptId(UUID.randomUUID())), "1" * 64, 100)
        assert(repository.records.size == JobRecords.MaxJobs)
        val limit = scala.util.Try(repository.reserve(workspace.copy(attempt = AttemptId(UUID.randomUUID())), "1" * 64, 100)).failed.get
        assert(limit.isInstanceOf[DomainFailure] && limit.asInstanceOf[DomainFailure].fault.isInstanceOf[Fault.Limit])
      }
    }}
  }
}

final class JobRepositoryDummy extends JobRepositoryTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class JobRepositoryLocal extends JobRepositoryTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))

  "Durable job ownership" should {
    "retain exact records across close/reopen and reject concurrent ownership or damaged records" in { (fixture: JobStorageFixture) => ZIO.attemptBlocking {
      val root = Files.createTempDirectory(Path.of(".work").toAbsolutePath.normalize(), "journal-restart-")
      val workspace = WorkspaceSpec(ProjectId(UUID.randomUUID()), SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/source", GitCommit("a" * 40))
      val first = Using.resource(FileJobRepository.open(root, workspace.project, workspace.owner)) { repository =>
        assert(scala.util.Try(FileJobRepository.open(root, workspace.project, workspace.owner)).isFailure)
        repository.reserve(workspace, "1" * 64, 100)._1
      }
      Using.resource(FileJobRepository.open(root, workspace.project, workspace.owner)) { repository => assert(repository.records == List(first)) }
      assert(scala.util.Try(FileJobRepository.open(root, ProjectId(UUID.randomUUID()), workspace.owner)).isFailure)
      val record = root.resolve(workspace.attempt.value.toString + ".json")
      Files.writeString(record, "{truncated")
      assert(scala.util.Try(FileJobRepository.open(root, workspace.project, workspace.owner)).isFailure)
      Files.delete(record)
      Using.resource(FileJobRepository.open(root, workspace.project, workspace.owner)) { repository => assert(repository.records.isEmpty) }
    }}
  }
}
