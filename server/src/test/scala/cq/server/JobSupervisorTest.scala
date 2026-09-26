package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*
import zio.{IO, Promise, ZIO}

final class JobSupervisorProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin, WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def owner: Scope = Scope(ProjectId(UUID.randomUUID()), Actor("job owner", SessionId(UUID.randomUUID()), Role.Governor))
  private def command(fixture: GuardianFixture, script: String): JobCommand =
    JobCommand(List("python3", "-c", script), fixture.environment, "input λ", ExecutionLimits(Duration.ofSeconds(2), Duration.ofSeconds(30),
      Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144))
  private def root(local: LocalWorkspaceFixture): IO[Throwable, Path] = ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "supervisor-"))
  private def acquire(at: Path, scope: Scope, service: WorkspaceService[IO], driver: ExecutionDriver) =
    JobSupervisor.acquire(scope, ZIO.attemptBlocking(FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)),
      service, driver, at.resolve("payload"), Clock.systemUTC())

  "Durable supervisor (Behavioral Active Blackbox; Git/filesystem/process Communication)" should {
    "suppress launch when cancellation races a stalled Starting record" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val count = new AtomicInteger(0)
      val driver = new ExecutionDriver { override def start(spec: ExecutionSpec): ManagedExecution = { count.incrementAndGet(); new GuardianDriver(guardian.binary).start(spec) } }
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          val acquireRepository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long) = delegate.reserve(spec, fingerprint, now)
              override def replace(expected: JobRecord, next: JobRecord): Unit = {
                if (next.phase == JobPhase.Starting) {
                  entered.countDown()
                  require(release.await(10, TimeUnit.SECONDS), "Starting record fixture was not released")
                }
                delegate.replace(expected, next)
              }
              override def close(): Unit = delegate.close()
            }
          }
          for {
            supervisor <- JobSupervisor.acquire(scope, acquireRepository, local.fixture.service, driver, at.resolve("payload"), Clock.systemUTC())
            _ <- (for {
              _ <- supervisor.start(scope, workspace, command(guardian, "print('must not launch')"))
              _ <- ZIO.attemptBlocking(assert(entered.await(3, TimeUnit.SECONDS)))
              forbidden <- supervisor.start(scope.copy(actor = scope.actor.copy(role = Role.Worker)), workspace, command(guardian, "pass")).either
              _ <- assertIO(forbidden.left.exists { case DomainFailure(_: Fault.Denied) => true; case _ => false })
              cancellation <- supervisor.cancel(scope, workspace.attempt).fork
              _ <- ZIO.sleep(zio.Duration.fromMillis(100))
              observed <- supervisor.status(scope, workspace.attempt)
              _ <- assertIO(observed.phase == JobPhase.Preparing)
              _ <- ZIO.succeed(release.countDown())
              _ <- cancellation.join
              completed <- supervisor.await(scope, workspace.attempt)
              _ <- assertIO(completed.phase == JobPhase.Settled && completed.target == JobTarget.Stop && completed.exit.isEmpty && count.get() == 0)
            } yield ()).ensuring(ZIO.succeed(release.countDown()))
          } yield ()
        }
      } yield ()
    }

    "stop active jobs with or without explicit cancellation when an unrelated reservation stalls, and suppress late launch" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) => ZIO.foreachDiscard(List(true, false)) { explicitCancellation =>
      val scope = owner
      val first = local.fixture.spec(scope)
      val second = local.fixture.spec(scope)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val process = new AtomicReference[Option[ManagedExecution]](None)
      val count = new AtomicInteger(0)
      val driver = new ExecutionDriver { override def start(spec: ExecutionSpec): ManagedExecution = {
        count.incrementAndGet()
        val running = new GuardianDriver(guardian.binary).start(spec)
        process.set(Some(running))
        running
      } }
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          val acquireRepository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean) = {
                val record = delegate.reserve(spec, fingerprint, now)
                if (spec.attempt == second.attempt) {
                  entered.countDown()
                  require(release.await(10, TimeUnit.SECONDS), "Reservation fixture was not released")
                }
                record
              }
              override def replace(expected: JobRecord, next: JobRecord): Unit = delegate.replace(expected, next)
              override def close(): Unit = delegate.close()
            }
          }
          for {
            supervisor <- JobSupervisor.acquire(scope, acquireRepository, local.fixture.service, driver, at.resolve("payload"), Clock.systemUTC())
            _ <- (for {
              _ <- supervisor.start(scope, first, command(guardian, "import time; time.sleep(30)"))
              _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> supervisor.status(scope, first.attempt)).repeatUntil(_.phase == JobPhase.Running)
                .timeoutFail(new IllegalStateException("Fixture never started"))(zio.Duration.fromSeconds(5))
              starting <- supervisor.start(scope, second, command(guardian, "print('must not launch')")).fork
              _ <- ZIO.attemptBlocking(assert(entered.await(3, TimeUnit.SECONDS)))
              cancellation <- if (explicitCancellation) supervisor.cancel(scope, first.attempt).fork.map(Some(_)) else ZIO.succeed(None)
              _ <- ZIO.sleep(zio.Duration.fromMillis(1200))
              observed <- ZIO.attemptBlocking(process.get().get.await(Duration.ofSeconds(3)))
              _ <- assertIO(observed.phase == ProcessPhase.Settled && observed.result.exists(_.reason == StopReason.Cancelled))
              _ <- ZIO.sleep(zio.Duration.fromMillis(1200))
              startAck <- starting.poll
              cancelAck <- ZIO.foreach(cancellation)(_.poll)
              _ <- assertIO(startAck.exists(_.isFailure) && cancelAck.forall(_.exists(_.isFailure)))
              _ <- ZIO.succeed(release.countDown())
            } yield ()).ensuring(ZIO.succeed(release.countDown()))
          } yield ()
        }
        _ <- assertIO(count.get() == 1)
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            record <- supervisor.status(scope, second.attempt)
            _ <- assertIO(record.phase == JobPhase.Uncertain && record.target == JobTarget.Stop)
          } yield ()
        }
      } yield ()
    } }

    "deliver cancellation and bound its acknowledgement while journal persistence is stalled" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val armed = new AtomicBoolean(false)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val process = new AtomicReference[Option[ManagedExecution]](None)
      val driver = new ExecutionDriver {
        override def start(spec: ExecutionSpec): ManagedExecution = {
          val running = new GuardianDriver(guardian.binary).start(spec)
          process.set(Some(running))
          running
        }
      }
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          val acquireRepository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long) = delegate.reserve(spec, fingerprint, now)
              override def replace(expected: JobRecord, next: JobRecord): Unit = {
                if (armed.compareAndSet(true, false)) {
                  entered.countDown()
                  require(release.await(10, TimeUnit.SECONDS), "Stalled storage fixture was not released")
                }
                delegate.replace(expected, next)
              }
              override def close(): Unit = delegate.close()
            }
          }
          for {
            supervisor <- JobSupervisor.acquire(scope, acquireRepository, local.fixture.service, driver, at.resolve("payload"), Clock.systemUTC())
            _ <- (for {
              _ <- supervisor.start(scope, workspace, command(guardian, "import time; time.sleep(30)"))
              _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> supervisor.status(scope, workspace.attempt)).repeatUntil(_.phase == JobPhase.Running)
                .timeoutFail(new IllegalStateException("Fixture never started"))(zio.Duration.fromSeconds(5))
              _ <- ZIO.succeed(armed.set(true))
              cancellation <- supervisor.cancel(scope, workspace.attempt).fork
              _ <- ZIO.attemptBlocking(assert(entered.await(3, TimeUnit.SECONDS)))
              observed <- ZIO.attemptBlocking(process.get().get.await(Duration.ofSeconds(3)))
              _ <- assertIO(observed.phase == ProcessPhase.Settled && observed.result.exists(_.reason == StopReason.Cancelled))
              _ <- ZIO.sleep(zio.Duration.fromMillis(1200))
              acknowledgement <- cancellation.poll
              _ <- assertIO(acknowledgement.exists(_.isFailure))
              status <- supervisor.status(scope, workspace.attempt).either
              _ <- assertIO(status.isLeft)
            } yield ()).ensuring(ZIO.succeed(release.countDown()))
          } yield ()
        }
      } yield ()
    }

    "terminate a real supervisor hierarchy after SIGKILL and quarantine its unacknowledged workspace on reopen" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      for {
        at <- root(local)
        _ <- ZIO.attemptBlocking {
          Files.writeString(at.resolve("workspace.json"), WorkspaceSpec_JsonCodec.encode(BaboonCodecContext.Default, workspace).noSpaces)
          val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java")
          val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("Fork fixture classpath is required"))
          val process = new ProcessBuilder(javaBinary.toString, "-cp", classpath, "cq.server.JobOwnerFixture", at.toString, guardian.binary.toString)
            .redirectErrorStream(true).redirectOutput(at.resolve("owner.log").toFile).start()
          var descendants = List.empty[ProcessHandle]
          try {
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos
            while (!Files.exists(at.resolve("ready")) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assert(process.isAlive && Files.exists(at.resolve("ready")), Files.readString(at.resolve("owner.log")))
            val stream = process.descendants()
            try descendants = stream.iterator().asScala.toList finally stream.close()
            val tree = at.resolve("workspaces").resolve(workspace.attempt.value.toString).resolve("tree")
            val pids = Set(Files.readString(tree.resolve("root.pid")).toLong, Files.readString(tree.resolve("child.pid")).toLong)
            val children = descendants.filter(handle => pids(handle.pid()))
            assert(children.size == 2)
            process.destroyForcibly()
            assert(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 137)
            val stopped = System.nanoTime() + Duration.ofSeconds(5).toNanos
            while (children.exists(_.isAlive) && System.nanoTime() < stopped) Thread.sleep(20)
            assert(children.forall(!_.isAlive), "Guardian did not reap root and detached descendant after owner SIGKILL")
          } finally {
            if (process.isAlive) process.destroyForcibly()
            descendants.filter(_.isAlive).foreach(_.destroyForcibly())
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
          }
        }
        service = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(at.resolve("workspaces"), local.command, Clock.systemUTC()))
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, service, new GuardianDriver(guardian.binary))
            recovered <- supervisor.status(scope, workspace.attempt)
            _ <- assertIO(recovered.phase == JobPhase.Uncertain && recovered.exit.isEmpty)
            stored <- service.get(scope, workspace.attempt)
            _ <- assertIO(stored.admission == WorkspaceAdmission.Quarantined)
          } yield ()
        }
      } yield ()
    }

    "disable further work after a committed reservation loses its acknowledgement" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          val acquireRepository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long): (JobRecord, Boolean) = {
                delegate.reserve(spec, fingerprint, now)
                throw new java.io.IOException("Injected lost reservation acknowledgement")
              }
              override def replace(expected: JobRecord, next: JobRecord): Unit = delegate.replace(expected, next)
              override def close(): Unit = delegate.close()
            }
          }
          for {
            supervisor <- JobSupervisor.acquire(scope, acquireRepository, local.fixture.service, new GuardianDriver(guardian.binary), at.resolve("payload"), Clock.systemUTC())
            failed <- supervisor.start(scope, workspace, command(guardian, "pass")).either
            status <- supervisor.status(scope, workspace.attempt).either
            _ <- assertIO(failed.isLeft && status.isLeft)
          } yield ()
        }
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            recovered <- supervisor.status(scope, workspace.attempt)
            _ <- assertIO(recovered.phase == JobPhase.Uncertain)
          } yield ()
        }
      } yield ()
    }

    "cancel the retained process even when persisting cancellation fails" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val failWrite = new AtomicBoolean(false)
      val process = new AtomicReference[Option[ManagedExecution]](None)
      val driver = new ExecutionDriver {
        override def start(spec: ExecutionSpec): ManagedExecution = {
          val running = new GuardianDriver(guardian.binary).start(spec)
          process.set(Some(running))
          running
        }
      }
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          val acquireRepository = ZIO.attemptBlocking {
            val delegate = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
            new JobRepository {
              override def records = delegate.records
              override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long) = delegate.reserve(spec, fingerprint, now)
              override def replace(expected: JobRecord, next: JobRecord): Unit = {
                if (failWrite.compareAndSet(true, false)) throw new java.io.IOException("Injected cancellation write failure")
                delegate.replace(expected, next)
              }
              override def close(): Unit = delegate.close()
            }
          }
          for {
            supervisor <- JobSupervisor.acquire(scope, acquireRepository, local.fixture.service, driver, at.resolve("payload"), Clock.systemUTC())
            _ <- supervisor.start(scope, workspace, command(guardian, "import time; time.sleep(30)"))
            _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> supervisor.status(scope, workspace.attempt)).repeatUntil(_.phase == JobPhase.Running)
              .timeoutFail(new IllegalStateException("Fixture never started"))(zio.Duration.fromSeconds(5))
            _ <- ZIO.succeed(failWrite.set(true))
            failed <- supervisor.cancel(scope, workspace.attempt).either
            _ <- assertIO(failed.isLeft)
            observed <- ZIO.attemptBlocking(process.get().get.await(Duration.ofSeconds(3)))
            _ <- assertIO(observed.phase == ProcessPhase.Settled && observed.result.exists(_.reason == StopReason.Cancelled))
            status <- supervisor.status(scope, workspace.attempt).either
            _ <- assertIO(status.isLeft)
          } yield ()
        }
      } yield ()
    }

    "acknowledge before execution, launch concurrent identical retries once and retain completion across reopen" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val launch = command(guardian, "from pathlib import Path; import sys,time; p=Path('launches'); p.write_text(p.read_text()+'x' if p.exists() else 'x'); print(sys.stdin.read()); time.sleep(0.3)")
      for {
        at <- root(local)
        result <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            began <- ZIO.succeed(System.nanoTime())
            first <- supervisor.start(scope, workspace, launch)
            elapsed <- ZIO.succeed(Duration.ofNanos(System.nanoTime() - began).toMillis)
            _ <- assertIO(first.phase == JobPhase.Preparing && elapsed < 500)
            retries <- ZIO.collectAllPar(List.fill(4)(supervisor.start(scope, workspace, launch)))
            _ <- assertIO(retries.forall(_.workspace == workspace))
            completed <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(completed.phase == JobPhase.Settled && completed.exit.exists(_.code.contains(0)))
            stored <- local.fixture.service.get(scope, workspace.attempt)
            _ <- ZIO.attemptBlocking {
              assert(Files.readString(Path.of(stored.directory).resolve("launches")) == "x")
              assert(!Files.exists(local.source.resolve("launches")))
              assert(Files.readString(at.resolve("payload").resolve(workspace.attempt.value.toString).resolve("stdout")) == "input λ\n")
            }
            cancelled <- supervisor.cancel(scope, workspace.attempt)
            _ <- assertIO(cancelled == completed)
          } yield completed
        }
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            retried <- supervisor.start(scope, workspace, launch)
            _ <- assertIO(retried == result)
            conflict <- supervisor.start(scope, workspace, command(guardian, "print('different')")).either
            _ <- assertIO(conflict.isLeft)
          } yield ()
        }
      } yield ()
    }

    "persist cancellation during workspace preparation and enforce governing scope before every control operation" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val count = new AtomicInteger(0)
      val driver = new ExecutionDriver { override def start(spec: ExecutionSpec): ManagedExecution = { count.incrementAndGet(); new GuardianDriver(guardian.binary).start(spec) } }
      for {
        at <- root(local)
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        service = new WorkspaceService[IO] {
          override def prepare(scope: Scope, spec: WorkspaceSpec) = entered.succeed(()).unit *>
            release.await.timeoutFail(new IllegalStateException("Preparation fixture was not released"))(zio.Duration.fromSeconds(5)) *> local.fixture.service.prepare(scope, spec)
          override def get(scope: Scope, attempt: AttemptId) = local.fixture.service.get(scope, attempt)
          override def quarantine(scope: Scope, attempt: AttemptId, reason: String) = local.fixture.service.quarantine(scope, attempt, reason)
        }
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, service, driver)
            _ <- supervisor.start(scope, workspace, command(guardian, "print('forbidden')"))
            _ <- entered.await
            worker = scope.copy(actor = scope.actor.copy(role = Role.Worker))
            denied <- ZIO.collectAll(List(supervisor.start(worker, workspace, command(guardian, "pass")).either,
              supervisor.status(worker, workspace.attempt).either, supervisor.cancel(worker, workspace.attempt).either,
              supervisor.status(scope.copy(actor = scope.actor.copy(session = SessionId(UUID.randomUUID()))), workspace.attempt).either))
            _ <- assertIO(denied.forall(_.isLeft))
            began <- ZIO.succeed(System.nanoTime())
            stopped <- supervisor.cancel(scope, workspace.attempt)
            _ <- assertIO(stopped.target == JobTarget.Stop && Duration.ofNanos(System.nanoTime() - began).toMillis < 500)
            _ <- release.succeed(())
            result <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(result.phase == JobPhase.Settled && result.exit.isEmpty && count.get() == 0)
          } yield ()
        }.ensuring(release.succeed(()))
      } yield ()
    }

    "quarantine unconfirmed termination and preserve that decision across restart and exact launch retries" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val launch = command(guardian, "import os,signal; os.kill(os.getppid(),signal.SIGKILL)")
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            _ <- supervisor.start(scope, workspace, launch)
            completed <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(completed.phase == JobPhase.Uncertain && completed.problem.nonEmpty)
            record <- local.fixture.service.get(scope, workspace.attempt)
            _ <- assertIO(record.admission == WorkspaceAdmission.Quarantined)
          } yield ()
        }
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            retried <- supervisor.start(scope, workspace, launch)
            _ <- assertIO(retried.phase == JobPhase.Uncertain && retried.target == JobTarget.Stop)
            record <- local.fixture.service.get(scope, workspace.attempt)
            _ <- assertIO(record.admission == WorkspaceAdmission.Quarantined)
          } yield ()
        }
      } yield ()
    }

    "recover unfinished launch intents without executing anything and quarantine their existing workspaces" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val prepared = local.fixture.spec(scope)
      val absent = local.fixture.spec(scope)
      val launch = command(guardian, "pass")
      val driver = new ExecutionDriver { override def start(spec: ExecutionSpec): ManagedExecution = throw new AssertionError("Recovery must never launch") }
      for {
        at <- root(local)
        _ <- local.fixture.service.prepare(scope, prepared)
        _ <- ZIO.attemptBlocking {
          val repository = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
          try {
            val (record, _) = repository.reserve(prepared, launch.fingerprint, 100)
            repository.replace(record, record.copy(phase = JobPhase.Starting, revision = 2))
            repository.reserve(absent, launch.fingerprint, 100)
          } finally repository.close()
        }
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, driver)
            one <- supervisor.status(scope, prepared.attempt)
            two <- supervisor.status(scope, absent.attempt)
            _ <- assertIO(List(one, two).forall(r => r.phase == JobPhase.Uncertain && r.target == JobTarget.Stop && r.exit.isEmpty))
            stored <- local.fixture.service.get(scope, prepared.attempt)
            _ <- assertIO(stored.admission == WorkspaceAdmission.Quarantined)
            missing <- local.fixture.repository.get(absent.attempt)
            _ <- assertIO(missing.isEmpty)
          } yield ()
        }
      } yield ()
    }

    "finish active process cleanup before releasing journal ownership on orderly supervisor shutdown" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      for {
        at <- root(local)
        _ <- ZIO.scoped {
          for {
            supervisor <- acquire(at, scope, local.fixture.service, new GuardianDriver(guardian.binary))
            _ <- supervisor.start(scope, workspace, command(guardian, "import time; time.sleep(30)"))
            _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> supervisor.status(scope, workspace.attempt)).repeatUntil(r => r.phase == JobPhase.Running)
              .timeoutFail(new IllegalStateException("Fixture never started"))(zio.Duration.fromSeconds(5))
          } yield ()
        }
        _ <- ZIO.attemptBlocking {
          val repository = FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session)
          try {
            val result = repository.records.head
            assert(result.phase == JobPhase.Settled && result.target == JobTarget.Stop && result.exit.exists(_.reason == StopReason.Cancelled))
          } finally repository.close()
        }
      } yield ()
    }
  }
}
