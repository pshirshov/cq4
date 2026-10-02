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
    JobCommand(List("python3", "-c", script), fixture.environment, "input λ", ExecutionLimits(Duration.ofSeconds(2), None,
      Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144))
  private def root(local: LocalWorkspaceFixture): IO[Throwable, Path] = ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "supervisor-"))
  private def journal(at: Path, scope: Scope): IO[Throwable, JobRepository] =
    ZIO.attemptBlocking(FileJobRepository.open(at.resolve("journal"), scope.project, scope.actor.session))
  private def acquire(at: Path, scope: Scope, service: WorkspaceService[IO], driver: ExecutionDriver) =
    JobSupervisor.acquire(scope, journal(at, scope), service, driver, at.resolve("payload"), Clock.systemUTC())
  private val SlowJournalMillis = 600L
  /** A journal whose every write takes `SlowJournalMillis`: longer than any elapsed-time bound a test could set, shorter than the supervisor's acknowledgement deadline. */
  private def slowJournal(at: Path, scope: Scope): IO[Throwable, JobRepository] = journal(at, scope).map { delegate =>
    new JobRepository {
      override def records = delegate.records
      override def reserve(spec: WorkspaceSpec, fingerprint: String, now: Long) = { Thread.sleep(SlowJournalMillis); delegate.reserve(spec, fingerprint, now) }
      override def replace(expected: JobRecord, next: JobRecord): Unit = { Thread.sleep(SlowJournalMillis); delegate.replace(expected, next) }
      override def close(): Unit = delegate.close()
    }
  }
  /**
   * A supervisor whose workspace preparation waits for `release` and whose launches are counted, so that a test states what had
   * happened when a call returned instead of how long the call took. The job's fiber is uninterruptible and the supervisor's
   * shutdown waits for it, so no timeout can end the wait: the body's end releases it before the supervisor's scope closes.
   */
  private def held[A](local: LocalWorkspaceFixture, guardian: GuardianFixture, scope: Scope, journal: IO[Throwable, JobRepository], payload: Path, launches: AtomicInteger)
    (test: (JobSupervisor, Promise[Nothing, Unit], Promise[Nothing, Unit]) => IO[Throwable, A]): IO[Throwable, A] = for {
    entered <- Promise.make[Nothing, Unit]
    release <- Promise.make[Nothing, Unit]
    service = new WorkspaceService[IO] {
      override def prepare(scope: Scope, spec: WorkspaceSpec) = entered.succeed(()).unit *> release.await *> local.fixture.service.prepare(scope, spec)
      override def get(scope: Scope, attempt: AttemptId) = local.fixture.service.get(scope, attempt)
      override def quarantine(scope: Scope, attempt: AttemptId, reason: String) = local.fixture.service.quarantine(scope, attempt, reason)
      override def remove(scope: Scope, attempt: AttemptId) = local.fixture.service.remove(scope, attempt)
      override def prune(scope: Scope, repository: String) = local.fixture.service.prune(scope, repository)
    }
    driver = new ExecutionDriver { override def start(spec: ExecutionSpec): ManagedExecution = { launches.incrementAndGet(); new GuardianDriver(guardian.binary).start(spec) } }
    result <- ZIO.scoped(JobSupervisor.acquire(scope, journal, service, driver, payload, Clock.systemUTC())
      .flatMap(supervisor => test(supervisor, entered, release).ensuring(release.succeed(()))))
  } yield result

  "Durable supervisor (Behavioral Active Blackbox; Git/filesystem/process Communication)" should {
    "I21: keep the journalled fingerprint of a deadline-bound command and give a command without an execution limit its own" in { (_: GuardianFixture) => ZIO.attempt {
      def limits(execution: Option[Duration]) = ExecutionLimits(Duration.ofSeconds(2), execution, Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144)
      def fingerprint(execution: Option[Duration]) = JobCommand(List("check"), Map("K" -> "V"), "input", limits(execution)).fingerprint
      // SHA-256 of the launch encoding as journalled before harness jobs lost their execution deadline.
      assert(fingerprint(Some(Duration.ofSeconds(30))) == "54d0141e3fd4bc7b55495bb3ba8a43aa7d088c4e9779a406a6676c7e42f6a3d6")
      assert(fingerprint(None) == "350fa8a8dd0cb33471d68517cc092cc52a1bd17f93d1fa128505f09bd1dbf012")
    }}

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
              startAck <- starting.await
              cancelAck <- ZIO.foreach(cancellation)(_.await)
              _ <- assertIO(startAck.isFailure && cancelAck.forall(_.isFailure))
              observed <- ZIO.attemptBlocking(process.get().get.await(Duration.ofSeconds(3)))
              _ <- assertIO(observed.phase == ProcessPhase.Settled && observed.result.exists(_.reason == StopReason.Cancelled))
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
              acknowledgement <- cancellation.await
              _ <- assertIO(acknowledgement.isFailure)
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
      val hold = new AtomicBoolean(false)
      val held = new CountDownLatch(1)
      val resume = new CountDownLatch(1)
      // The supervisor's monitor persists what it observes of the process, and a cancelled process is such an observation. The monitor
      // is held at its next observation, so that the write that fails is the cancellation's own, whichever would have come first.
      val driver = new ExecutionDriver {
        override def start(spec: ExecutionSpec): ManagedExecution = {
          val running = new GuardianDriver(guardian.binary).start(spec)
          process.set(Some(running))
          new ManagedExecution {
            override def status: ProcessObservation = {
              if (hold.get()) { held.countDown(); require(resume.await(30, TimeUnit.SECONDS), "Monitor fixture was not released") }
              running.status
            }
            override def cancel(): ProcessObservation = running.cancel()
            override def await(timeout: Duration): ProcessObservation = running.await(timeout)
            override def close(): Unit = running.close()
          }
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
            _ <- ZIO.succeed(hold.set(true))
            failed <- (for {
              _ <- ZIO.attemptBlocking(assert(held.await(10, TimeUnit.SECONDS)))
              _ <- ZIO.succeed(failWrite.set(true))
              failed <- supervisor.cancel(scope, workspace.attempt).either
            } yield failed).ensuring(ZIO.succeed { hold.set(false); resume.countDown() })
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
      val count = new AtomicInteger(0)
      for {
        at <- root(local)
        result <- held(local, guardian, scope, journal(at, scope), at.resolve("payload"), count) { (supervisor, _, release) =>
          for {
            // Preparation is held until `release`, so a call that returns has been acknowledged before preparation and execution.
            first <- supervisor.start(scope, workspace, launch)
            _ <- assertIO(first.phase == JobPhase.Preparing && count.get() == 0)
            retries <- ZIO.collectAllPar(List.fill(4)(supervisor.start(scope, workspace, launch)))
            _ <- assertIO(retries.forall(_.workspace == workspace) && count.get() == 0)
            _ <- release.succeed(())
            completed <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(completed.phase == JobPhase.Settled && completed.exit.exists(_.code.contains(0)))
            stored <- local.fixture.service.get(scope, workspace.attempt)
            _ <- ZIO.attemptBlocking {
              assert(Files.readString(Path.of(stored.directory).resolve("launches")) == "x")
              assert(!Files.exists(local.source.resolve("launches")))
              assert(Files.readString(at.resolve("payload").resolve(workspace.attempt.value.toString).resolve("stdout")) == "input λ\n")
            }
            cancelled <- supervisor.cancel(scope, workspace.attempt)
            _ <- assertIO(cancelled == completed && count.get() == 1)
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
      for {
        at <- root(local)
        _ <- held(local, guardian, scope, journal(at, scope), at.resolve("payload"), count) { (supervisor, entered, release) =>
          for {
            _ <- supervisor.start(scope, workspace, command(guardian, "print('forbidden')"))
            _ <- entered.await
            worker = scope.copy(actor = scope.actor.copy(role = Role.Worker))
            denied <- ZIO.collectAll(List(supervisor.start(worker, workspace, command(guardian, "pass")).either,
              supervisor.status(worker, workspace.attempt).either, supervisor.cancel(worker, workspace.attempt).either,
              supervisor.status(scope.copy(actor = scope.actor.copy(session = SessionId(UUID.randomUUID()))), workspace.attempt).either))
            _ <- assertIO(denied.forall(_.isLeft))
            // Preparation has begun and is still held, so a call that returns has not waited for it.
            stopped <- supervisor.cancel(scope, workspace.attempt)
            _ <- assertIO(stopped.target == JobTarget.Stop && stopped.phase == JobPhase.Preparing && count.get() == 0)
            _ <- release.succeed(())
            result <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(result.phase == JobPhase.Settled && result.exit.isEmpty && count.get() == 0)
          } yield ()
        }
      } yield ()
    }

    "D112: acknowledge a start and a cancellation before preparation ends when every journal write is slow" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val workspace = local.fixture.spec(scope)
      val count = new AtomicInteger(0)
      def timed[A](operation: IO[Throwable, A]): IO[Throwable, (A, Long)] = for {
        began <- ZIO.succeed(System.nanoTime())
        value <- operation
      } yield (value, Duration.ofNanos(System.nanoTime() - began).toMillis)
      for {
        at <- root(local)
        _ <- held(local, guardian, scope, slowJournal(at, scope), at.resolve("payload"), count) { (supervisor, entered, release) =>
          for {
            started <- timed(supervisor.start(scope, workspace, command(guardian, "print('forbidden')")))
            _ <- assertIO(started._1.phase == JobPhase.Preparing && count.get() == 0)
            _ <- entered.await
            stopped <- timed(supervisor.cancel(scope, workspace.attempt))
            _ <- assertIO(stopped._1.target == JobTarget.Stop && stopped._1.phase == JobPhase.Preparing && count.get() == 0)
            // Both calls took longer than the 500 ms these acknowledgements were once required to stay under.
            _ <- assertIO(started._2 >= SlowJournalMillis && stopped._2 >= SlowJournalMillis)
            _ <- release.succeed(())
            result <- supervisor.await(scope, workspace.attempt)
            _ <- assertIO(result.phase == JobPhase.Settled && result.exit.isEmpty && count.get() == 0)
          } yield ()
        }
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
