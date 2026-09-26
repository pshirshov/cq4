package cq.server

import cq.api.*
import cq.core.*
import cq.host.*
import distage.{Activation, Lifecycle, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import zio.{IO, Ref, Task, ZIO}

final case class WorkspaceFixture(source: Path, base: GitCommit, repository: WorkspaceRepository[IO]) {
  def service: WorkspaceService[IO] = new WorkspaceService.Impl[IO](repository)
  def spec(scope: Scope): WorkspaceSpec = WorkspaceSpec(scope.project, scope.actor.session, AttemptId(UUID.randomUUID()), source.toString, base)
}

final class DummyWorkspaceResource extends Lifecycle.LiftF[Task, WorkspaceFixture](
  Ref.Synchronized.make(Map.empty[AttemptId, WorkspaceRecord]).map { state =>
    val repository = new WorkspaceRepository[IO] {
      override def get(attempt: AttemptId): IO[Throwable, Option[WorkspaceRecord]] = state.get.map(_.get(attempt))
      override def prepare(spec: WorkspaceSpec): IO[Throwable, WorkspaceRecord] = state.modifyZIO { records => ZIO.attempt {
        val value = records.get(spec.attempt) match {
          case Some(value) =>
            if (value.spec != spec || value.admission != WorkspaceAdmission.Open || value.observed.isEmpty) throw DomainFailure(Fault.Conflict("Workspace cannot be reused"))
            value
          case None => WorkspaceRecord(spec, "/dummy/" + spec.attempt.value, WorkspaceAdmission.Open, Some(WorkspaceObservation(spec.base, "/dummy/common", 1)), None)
        }
        (value, records.updated(spec.attempt, value))
      } }
      override def quarantine(attempt: AttemptId, reason: String): IO[Throwable, WorkspaceRecord] = state.modify { records =>
        val next = records(attempt).copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some(reason))
        (next, records.updated(attempt, next))
      }
    }
    WorkspaceFixture(Path.of("/dummy/source"), GitCommit("a" * 40), repository)
  }
)

final class LocalWorkspaceFixture(val directory: Path, val command: HostCommand) {
  val source: Path = Files.createDirectory(directory.resolve("source"))
  val workspaces: Path = directory.resolve("workspaces")
  def git(at: Path, args: String*): String = {
    val result = command.run(at, List("git") ++ args)
    require(result.exit == 0, result.text)
    result.text.trim
  }
  git(source, "init", "--quiet")
  Files.writeString(source.resolve("tracked.txt"), "committed\n")
  git(source, "add", "tracked.txt")
  git(source, "-c", "user.name=CQ test", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "-m", "Initial")
  val base: GitCommit = GitCommit(git(source, "rev-parse", "HEAD"))
  def repository: GitWorkspaceRepository = new GitWorkspaceRepository(workspaces, command, Clock.systemUTC())
  def fixture: WorkspaceFixture = WorkspaceFixture(source, base, repository)
  def close(): Unit = {
    val entries = Files.walk(directory)
    try entries.iterator().asScala.toList.reverse.foreach(Files.delete) finally entries.close()
  }
}

final class LocalWorkspaceResource extends Lifecycle.Of[Task, LocalWorkspaceFixture](
  Lifecycle.make(ZIO.attemptBlocking {
    val root = Path.of(".work").toAbsolutePath.normalize()
    Files.createDirectories(root)
    new LocalWorkspaceFixture(Files.createTempDirectory(root, "workspace-contract-"),
      new BoundedHostCommand(GitEnvironment.isolated(sys.env), Duration.ofSeconds(10), 65536))
  })(fixture => ZIO.attemptBlocking(fixture.close()).orDie)
)

object WorkspaceTestPlugin extends PluginDef {
  include(new ModuleDef { tag(Repo.Dummy); make[WorkspaceFixture].fromResource[DummyWorkspaceResource] })
  include(new ModuleDef {
    tag(Repo.Prod)
    make[LocalWorkspaceFixture].fromResource[LocalWorkspaceResource]
    make[WorkspaceFixture].from((local: LocalWorkspaceFixture) => local.fixture)
  })
}

abstract class WorkspaceContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)))
  private def scope: Scope = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
  private def denied[A](effect: IO[Throwable, A]): IO[Throwable, Unit] = effect.either.flatMap(result => assertIO(result.isLeft)).unit

  "Isolated workspaces (Behavioral Active Blackbox; dummy Group / local Git Good Communication)" should {
    "preserve frozen ownership and preparation identity while separating quarantine from observed creation" in { (fixture: WorkspaceFixture) =>
      val owner = scope
      val spec = fixture.spec(owner)
      val service = fixture.service
      for {
        first <- service.prepare(owner, spec)
        _ <- assertIO(first.observed.exists(_.head == spec.base) && first.admission == WorkspaceAdmission.Open)
        retry <- service.prepare(owner, spec)
        _ <- assertIO(retry == first)
        second <- service.prepare(owner, fixture.spec(owner))
        _ <- assertIO(second.directory != first.directory)
        _ <- denied(service.prepare(owner, spec.copy(base = GitCommit("b" * 40))))
        _ <- denied(service.get(owner.copy(project = ProjectId(UUID.randomUUID())), spec.attempt))
        _ <- denied(service.get(owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID()))), spec.attempt))
        worker = owner.copy(actor = owner.actor.copy(role = Role.Worker))
        _ <- denied(service.prepare(worker, fixture.spec(owner)))
        _ <- denied(service.quarantine(worker, spec.attempt, "Forbidden"))
        quarantined <- service.quarantine(owner, spec.attempt, "Termination unconfirmed")
        _ <- assertIO(quarantined.observed == first.observed && quarantined.admission == WorkspaceAdmission.Quarantined)
        _ <- denied(service.prepare(owner, spec))
        stored <- service.get(owner, spec.attempt)
        _ <- assertIO(stored == quarantined)
      } yield ()
    }
  }
}

final class WorkspaceContractDummy extends WorkspaceContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class WorkspaceContractLocal extends WorkspaceContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))

  "Real Git isolation" should {
    "leave governing content and index untouched and keep detached attempts independent" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val fixture = local.fixture
      val spec = fixture.spec(owner)
      for {
        before <- ZIO.attemptBlocking {
          Files.writeString(local.source.resolve("tracked.txt"), "governing dirty\n")
          local.git(local.source, "add", "tracked.txt")
          Files.readAllBytes(local.source.resolve(".git/index")).toList
        }
        one <- fixture.service.prepare(owner, spec)
        two <- fixture.service.prepare(owner, fixture.spec(owner))
        _ <- ZIO.attemptBlocking {
          val first = Path.of(one.directory)
          val second = Path.of(two.directory)
          assert(Files.readString(first.resolve("tracked.txt")) == "committed\n")
          Files.writeString(first.resolve("tracked.txt"), "attempt edit\n")
          local.git(first, "add", "tracked.txt")
          assert(Files.readString(second.resolve("tracked.txt")) == "committed\n")
          assert(Files.readString(local.source.resolve("tracked.txt")) == "governing dirty\n")
          assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == before)
          assert(local.command.run(first, List("git", "symbolic-ref", "-q", "HEAD")).exit == 1)
        }
        recovered <- new WorkspaceService.Impl[IO](local.repository).get(owner, spec.attempt)
        _ <- assertIO(recovered == one)
      } yield ()
    }

    "reject workspace roots inside the governing checkout through source subdirectories or root aliases" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      for {
        paths <- ZIO.attemptBlocking {
          val subdirectory = Files.createDirectory(local.source.resolve("subdirectory"))
          val inside = Files.createDirectory(local.source.resolve("generated-workspaces"))
          val alias = Files.createSymbolicLink(local.directory.resolve("workspace-alias"), inside)
          (subdirectory, inside, alias)
        }
        (subdirectory, inside, alias) = paths
        nestedService = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(inside, local.command, Clock.systemUTC()))
        nested <- nestedService.prepare(owner, local.fixture.spec(owner).copy(repository = subdirectory.toString)).either
        aliasService = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(alias, local.command, Clock.systemUTC()))
        aliased <- aliasService.prepare(owner, local.fixture.spec(owner)).either
        _ <- assertIO((nested.isLeft, aliased.isLeft) == (true, true))
      } yield ()
    }

    "reject reuse after the prepared directory is replaced" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val fixture = local.fixture
      val spec = fixture.spec(owner)
      for {
        record <- fixture.service.prepare(owner, spec)
        _ <- ZIO.attemptBlocking {
          val directory = Path.of(record.directory)
          Files.move(directory, directory.resolveSibling("retained-tree"))
          Files.createDirectory(directory)
        }
        replay <- fixture.service.prepare(owner, spec).either
        _ <- assertIO(replay.isLeft)
        retained <- fixture.service.get(owner, spec.attempt)
        _ <- assertIO(retained.admission == WorkspaceAdmission.Quarantined && retained.observed == record.observed)
      } yield ()
    }

    "retain and quarantine a created worktree after uncertain command acknowledgement" in { (local: LocalWorkspaceFixture) =>
      val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val spec = local.fixture.spec(owner)
      val uncertain = new HostCommand {
        override def run(directory: Path, arguments: List[String]): CommandOutput = {
          val output = local.command.run(directory, arguments)
          if (arguments.contains("worktree")) throw new IllegalStateException("Injected loss of creation acknowledgement")
          output
        }
      }
      val service = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(local.workspaces, uncertain, Clock.systemUTC()))
      for {
        result <- service.prepare(owner, spec).either
        _ <- assertIO(result.isLeft)
        record <- service.get(owner, spec.attempt)
        _ <- assertIO(record.admission == WorkspaceAdmission.Quarantined && record.observed.isEmpty && record.quarantineReason.nonEmpty)
        _ <- ZIO.attemptBlocking(assert(Files.readString(Path.of(record.directory).resolve("tracked.txt")) == "committed\n"))
        retry <- new WorkspaceService.Impl[IO](local.repository).prepare(owner, spec).either
        _ <- assertIO(retry.isLeft)
      } yield ()
    }

    "bound host command silence and output volume" in { (local: LocalWorkspaceFixture) => ZIO.attemptBlocking {
      val bounded = new BoundedHostCommand(sys.env, Duration.ofMillis(100), 4)
      val started = System.nanoTime()
      val silent = scala.util.Try(bounded.run(local.source, List("sh", "-c", "exec sleep 10")))
      assert(silent.isFailure && Duration.ofNanos(System.nanoTime() - started).toMillis < 4000)
      assert(scala.util.Try(bounded.run(local.source, List("sh", "-c", "printf 123456"))).isFailure)
    } }
  }
}
