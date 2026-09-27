package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope}
import cq.host.*
import distage.{Activation, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import zio.{Semaphore, Task, ZIO}

final class IntegrationReceiver extends ServerApi {
  private var records = Map.empty[IntegrationId, IntegrationRecord]
  val observations = new AtomicInteger(0)
  val loseAcknowledgement = new AtomicBoolean(false)
  val failRecording = new AtomicBoolean(false)
  val loseReservation = new AtomicBoolean(false)
  override def call(command: Command): Result = synchronized { command match {
    case Command.Read(ReadInput(_, ReadSelection.Integration(id))) => records.get(id).map(Result.Integration.apply).getOrElse(Result.Failed(Fault.Missing("No integration")))
    case _ => throw new IllegalStateException("Unexpected integration fixture command")
  } }
  override def integrate(input: HostIntegrationInput): IntegrationRecord = synchronized { input.operation match {
    case HostIntegration.Reserve(intent) =>
      val record = records.getOrElse(intent.id, IntegrationRecord(intent, IntegrationResolution.Pending(), 1, None))
      require(record.intent == intent)
      records = records.updated(intent.id, record)
      if (loseReservation.compareAndSet(true, false)) throw new IOException("Reservation acknowledgement lost")
      record
    case HostIntegration.Observe(id, observed) =>
      if (failRecording.compareAndSet(true, false)) throw new IOException("Domain recording failed")
      val previous = records(id)
      val next = if (previous.resolution != IntegrationResolution.Pending()) previous else {
        observations.incrementAndGet()
        val resolution = observed match {
          case IntegrationObservation.Incorporated(target) => IntegrationResolution.Recorded(
            ChangeAck(previous.intent.change.request, ChangeCursor(1), previous.intent.members.map(ref => ref.copy(revision = Revision(ref.revision.value + 1)))), target)
          case IntegrationObservation.NotApplied(reason) => IntegrationResolution.NotApplied(reason)
        }
        previous.copy(resolution = resolution, resolvedAt = Some(2))
      }
      records = records.updated(id, next)
      if (loseAcknowledgement.compareAndSet(true, false)) throw new IOException("Domain acknowledgement lost")
      next
  } }
  override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Not a usage fixture")
  override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Not an artifact fixture")
  override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Not an authority fixture")
  override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Not an admission fixture")
}

final class MemoryIntegrationJournal(owner: Scope) extends IntegrationJournal {
  private var records = Map.empty[IntegrationId, IntegrationLocal]
  private val owned = new AtomicBoolean(false)
  override def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A] = ZIO.scoped {
    ZIO.acquireRelease(ZIO.attempt {
      if (!owned.compareAndSet(false, true)) throw DomainFailure(Fault.Conflict("Coordinator is already owned"))
    })(_ => ZIO.succeed(owned.set(false))) *> operation(new IntegrationEntry {
      override def read = records.get(id)
      override def write(value: IntegrationLocal): Unit = {
        IntegrationEntries.validate(owner, id, read, value)
        if (read.isEmpty && records.size >= IntegrationEntries.MaxOperations) throw DomainFailure(Fault.Limit("Journal is full"))
        records = records.updated(id, value)
      }
    })
  }
}

final case class IntegrationFixture(owner: Scope, repository: Path, target: String, base: GitCommit, first: GitCommit, second: GitCommit,
  combined: GitCommit, git: GitIntegration, journal: IntegrationJournal, freshJournal: Task[IntegrationJournal], rewrite: GitCommit => Task[Unit],
  checkout: Task[Unit], isolation: Task[Unit], closeAdmission: Task[Unit]) {
  val server = new IntegrationReceiver
  val executions = new AtomicInteger(0)
  val counted: GitIntegration = new GitIntegration {
    override def inspect(value: IntegrationIntent) = git.inspect(value)
    override def execution(value: IntegrationIntent) = git.execution(value)
    override def execute(value: IntegrationIntent) = ZIO.succeed(executions.incrementAndGet()) *> git.execute(value)
  }
  def coordinator(store: IntegrationJournal): IntegrationCoordinator = new IntegrationCoordinator(owner, store, counted, server, server)
  def intent(expected: GitCommit, candidate: GitCommit): IntegrationIntent = {
    val id = IntegrationId(UUID.randomUUID())
    val member = ItemRevision(ItemId(owner.project, Ledger.Tasks, 1), Revision(1))
    val fence = Fence(ClaimId(UUID.randomUUID()), 1)
    IntegrationIntent(id, owner.project, owner.actor, repository.toString, target, expected, candidate, ArtifactId(UUID.randomUUID()),
      ArtifactId(UUID.randomUUID()), Nil, fence, List(member), ChangeRequest(RequestId(id.value), Nil, List(fence), "Fixture completion"))
  }
}

trait IntegrationHarness {
  def use[A](operation: IntegrationFixture => Task[A]): Task[A]
}

final class DummyIntegrationHarness extends IntegrationHarness {
  override def use[A](operation: IntegrationFixture => Task[A]): Task[A] = {
    val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
    val base = GitCommit("a" * 40)
    val first = GitCommit("b" * 40)
    val second = GitCommit("c" * 40)
    val combined = GitCommit("d" * 40)
    val repository = Path.of("/dummy/repository")
    val target = "refs/heads/integration"
    var current = base
    var checkedOut = false
    var closed = false
    var executions = Map.empty[IntegrationId, IntegrationExecution]
    val lock = new Object
    val git = new GitIntegration {
      override def inspect(intent: IntegrationIntent) = ZIO.attempt(lock.synchronized {
        IntegrationTarget(current, current == intent.candidate || (current == combined && Set(first, second)(intent.candidate)), checkedOut)
      })
      override def execute(intent: IntegrationIntent) = ZIO.attempt(lock.synchronized {
        if (closed) throw new IntegrationAdmissionClosed
        val applied = current == intent.expected
        if (applied) current = intent.candidate
        val out = if (applied) "start: ok\nprepare: ok\ncommit: ok\n" else "start: ok\n"
        val err = if (applied) "" else "fatal: prepare: conditional comparison refused\n"
        val record = JobRecord(WorkspaceSpec(owner.project, owner.actor.session, AttemptId(intent.id.value), repository.toString, intent.candidate),
          "0" * 64, JobTarget.Run, JobPhase.Settled, Some(JobExit(Some(if (applied) 0 else 128), None, StopReason.Exited, out.length, err.length, true, false)), None, 3, 1, 2)
        executions = executions.updated(intent.id, IntegrationExecution(record, out, err))
      })
      override def execution(intent: IntegrationIntent) = ZIO.attempt(lock.synchronized(executions.get(intent.id)))
    }
    operation(IntegrationFixture(owner, repository, target, base, first, second, combined, git, new MemoryIntegrationJournal(owner),
      ZIO.succeed(new MemoryIntegrationJournal(owner)), value => ZIO.succeed(lock.synchronized { current = value }),
      ZIO.succeed(lock.synchronized { checkedOut = true }), ZIO.unit, ZIO.succeed(lock.synchronized { closed = true })))
  }
}

final class RealIntegrationHarness(local: LocalWorkspaceFixture, guardian: GuardianFixture) extends IntegrationHarness {
  override def use[A](operation: IntegrationFixture => Task[A]): Task[A] = ZIO.scoped {
    val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
    val target = "refs/heads/integration"
    for {
      prepared <- ZIO.attemptBlocking {
        def commit(name: String, base: GitCommit): GitCommit = {
          val directory = local.directory.resolve(name)
          local.git(local.source, "worktree", "add", "--detach", directory.toString, base.value)
          Files.writeString(directory.resolve(name + ".txt"), name + "\n")
          local.git(directory, "add", "--all")
          local.git(directory, "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "-m", name)
          GitCommit(local.git(directory, "rev-parse", "HEAD"))
        }
        val first = commit("first", local.base)
        val second = commit("second", local.base)
        val merged = local.directory.resolve("combined")
        local.git(local.source, "worktree", "add", "--detach", merged.toString, first.value)
        local.git(merged, "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "merge", "--no-ff", "-m", "Combined", second.value)
        val combined = GitCommit(local.git(merged, "rev-parse", "HEAD"))
        local.git(local.source, "branch", "integration", local.base.value)
        Files.writeString(local.source.resolve("tracked.txt"), "governing staged\n")
        local.git(local.source, "add", "tracked.txt")
        Files.writeString(local.source.resolve("untracked.txt"), "governing untracked\n")
        val index = Files.readAllBytes(local.source.resolve(".git/index"))
        (first, second, combined, index)
      }
      (first, second, combined, index) = prepared
      directory <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "integration-owner-"))
      jobs <- JobSupervisor.acquire(owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("jobs"), owner.project, owner.actor.session)),
        local.fixture.service, new GuardianDriver(guardian.binary), directory.resolve("payload"), Clock.systemUTC())
      admission <- Semaphore.make(1)
      controlled = new GovernedIntegrationJobs(owner, jobs, admission)
      git = new SupervisedGitIntegration(owner, local.source, target, local.command, controlled, directory.resolve("payload"), guardian.environment,
        ExecutionLimits(Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), 65536))
      result <- operation(IntegrationFixture(owner, local.source, target, local.base, first, second, combined, git,
        new FileIntegrationJournal(directory.resolve("integrations"), owner),
        ZIO.attemptBlocking(new FileIntegrationJournal(Files.createTempDirectory(directory, "missing-journal-"), owner)),
        value => ZIO.attemptBlocking { local.git(local.source, "update-ref", target, value.value); () },
        ZIO.attemptBlocking { local.git(local.source, "worktree", "add", local.directory.resolve("checked-out").toString, "integration"); () },
        ZIO.attemptBlocking {
          assert(java.util.Arrays.equals(index, Files.readAllBytes(local.source.resolve(".git/index"))))
          assert(Files.readString(local.source.resolve("tracked.txt")) == "governing staged\n")
          assert(Files.readString(local.source.resolve("untracked.txt")) == "governing untracked\n")
          assert(local.git(local.source, "rev-parse", "HEAD") == local.base.value)
        }, controlled.shutdown))
    } yield result
  }
}

object IntegrationTestPlugin extends PluginDef {
  include(new ModuleDef { tag(Repo.Dummy); make[IntegrationHarness].from[DummyIntegrationHarness] })
  include(new ModuleDef { tag(Repo.Prod); make[IntegrationHarness].from[RealIntegrationHarness] })
}

abstract class IntegrationCoordinatorTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(IntegrationTestPlugin, WorkspaceTestPlugin, GuardianTestPlugin)))
  "Integration coordinator (Behavioral Active Blackbox; dummy Group / Git, filesystem and process Good Communication)" should {
    "retain coordinator ownership until an interrupted persistence call has actually returned" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val finished = new CountDownLatch(1)
      val stalled = new IntegrationJournal {
        override def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A] = f.journal.locked(id) { delegate =>
          operation(new IntegrationEntry {
            override def read = delegate.read
            override def write(value: IntegrationLocal): Unit = {
              if (value.attempted) {
                entered.countDown()
                try {
                  require(release.await(10, TimeUnit.SECONDS), "Persistence fixture was not released")
                  delegate.write(value)
                } finally finished.countDown()
              } else delegate.write(value)
            }
          })
        }
      }
      (for {
        _ <- f.coordinator(stalled).prepare(intent)
        running <- f.coordinator(stalled).run(intent.id).fork
        _ <- ZIO.attemptBlocking(assert(entered.await(5, TimeUnit.SECONDS)))
        _ <- running.interruptFork
        _ <- ZIO.sleep(zio.Duration.fromMillis(100))
        competing <- f.journal.locked(intent.id)(_ => ZIO.unit).either
        _ <- ZIO.succeed(release.countDown())
        _ <- ZIO.attemptBlocking(assert(finished.await(5, TimeUnit.SECONDS)))
        _ <- running.await
        _ <- assertIO(competing.left.exists { case DomainFailure(_: Fault.Conflict) => true; case _ => false })
        _ <- assertIO(f.executions.get() == 0)
      } yield ()).ensuring(ZIO.succeed(release.countDown()))
    } }

    "launch only after reservation acknowledgement and replay the retained reservation exactly once" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- ZIO.succeed(f.server.loseReservation.set(true))
        lost <- coordinator.run(intent.id).either
        _ <- assertIO(lost.left.exists(_.isInstanceOf[IOException]) && f.executions.get() == 0)
        recorded <- coordinator.run(intent.id)
        replay <- coordinator.run(intent.id)
        _ <- assertIO(recorded == replay && recorded.record.resolution.isInstanceOf[IntegrationResolution.Recorded] && f.executions.get() == 1)
      } yield ()
    } }

    "suppress launch after failed execution-admission persistence and never replay an already persisted marker" in { (harness: IntegrationHarness) => harness.use { f =>
      ZIO.foreachDiscard(List((false, f.base, f.first), (true, f.first, f.combined))) { case (after, expected, candidate) =>
        val intent = f.intent(expected, candidate)
        val failing = new IntegrationJournal {
          override def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A] = f.journal.locked(id) { delegate =>
            operation(new IntegrationEntry {
              override def read = delegate.read
              override def write(value: IntegrationLocal): Unit = {
                if (value.attempted && !after) throw new IOException("Admission write failed before persistence")
                delegate.write(value)
                if (value.attempted) throw new IOException("Admission acknowledgement failed after persistence")
              }
            })
          }
        }
        for {
          _ <- f.coordinator(failing).prepare(intent)
          failed <- f.coordinator(failing).run(intent.id)
          _ <- assertIO(failed.blocker.nonEmpty && f.executions.get() == (if (after) 1 else 0))
          recovered <- f.coordinator(f.journal).run(intent.id)
          _ <- assertIO(recovered.record.resolution.isInstanceOf[IntegrationResolution.Pending] == after && f.executions.get() == 1)
        } yield ()
      }
    } }

    "conditionally preserve competing candidates and integrate their combined descendant without touching the governing index" in { (harness: IntegrationHarness) => harness.use { f =>
      val first = f.intent(f.base, f.first)
      val second = f.intent(f.base, f.second)
      val combined = f.intent(f.first, f.combined)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(first)
        _ <- coordinator.prepare(second)
        one <- coordinator.run(first.id)
        two <- coordinator.run(second.id)
        _ <- assertIO(one.record.resolution.isInstanceOf[IntegrationResolution.Recorded] && two.record.resolution.isInstanceOf[IntegrationResolution.NotApplied])
        _ <- coordinator.prepare(combined)
        merged <- coordinator.run(combined.id)
        _ <- assertIO(merged.record.resolution.isInstanceOf[IntegrationResolution.Recorded] && f.executions.get() == 2)
        a <- f.git.inspect(first)
        b <- f.git.inspect(second)
        _ <- assertIO(a.incorporated && b.incorporated && a.commit == f.combined)
        _ <- f.isolation
      } yield ()
    } }

    "refuse exactly one competing conditional update at Git preparation" in { (harness: IntegrationHarness) => harness.use { f =>
      val intents = List(f.intent(f.base, f.first), f.intent(f.base, f.second))
      for {
        _ <- ZIO.foreachParDiscard(intents)(f.git.execute)
        receipts <- ZIO.foreach(intents)(f.git.execution)
        targets <- ZIO.foreach(intents)(f.git.inspect)
        _ <- assertIO(receipts.flatten.count(_.refusedBeforeCommit) == 1 && targets.count(_.incorporated) == 1)
        _ <- f.isolation
      } yield ()
    } }

    "retain applied observations across failed or lost domain acknowledgements without another Git effect" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- ZIO.succeed(f.server.failRecording.set(true))
        failed <- coordinator.run(intent.id)
        _ <- assertIO(failed.record.resolution == IntegrationResolution.Pending() && failed.blocker.nonEmpty && f.executions.get() == 1)
        _ <- ZIO.succeed(f.server.loseAcknowledgement.set(true))
        lost <- coordinator.run(intent.id)
        _ <- assertIO(lost.blocker.nonEmpty && f.executions.get() == 1)
        recovered <- f.coordinator(f.journal).run(intent.id)
        replay <- coordinator.run(intent.id)
        _ <- assertIO(recovered == replay && recovered.blocker.isEmpty && f.server.observations.get() == 1 && f.executions.get() == 1)
        _ <- f.isolation
      } yield ()
    } }

    "recover incorporation after local observation failure and preserve ambiguity after an external reset" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val failing = new IntegrationJournal {
        override def locked[A](id: IntegrationId)(operation: IntegrationEntry => Task[A]): Task[A] = f.journal.locked(id) { delegate =>
          operation(new IntegrationEntry {
            override def read = delegate.read
            override def write(value: IntegrationLocal): Unit = if (value.observation.nonEmpty) throw new IOException("Observation not persisted") else delegate.write(value)
          })
        }
      }
      for {
        _ <- f.coordinator(failing).prepare(intent)
        failed <- f.coordinator(failing).run(intent.id)
        _ <- assertIO(failed.blocker.nonEmpty && f.executions.get() == 1 && f.server.observations.get() == 0)
        _ <- f.rewrite(f.base)
        ambiguous <- f.coordinator(f.journal).run(intent.id)
        _ <- assertIO(ambiguous.record.resolution == IntegrationResolution.Pending() && ambiguous.blocker.nonEmpty && f.executions.get() == 1)
        _ <- f.rewrite(f.first)
        recovered <- f.coordinator(f.journal).run(intent.id)
        _ <- assertIO(recovered.record.resolution.isInstanceOf[IntegrationResolution.Recorded] && f.executions.get() == 1)
      } yield ()
    } }

    "keep admitted but unconfirmed execution pending and deny reconstruction from a missing local journal" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- f.journal.locked(intent.id)(entry => ZIO.attempt(entry.write(entry.read.get.copy(attempted = true))))
        pending <- coordinator.run(intent.id)
        _ <- assertIO(pending.record.resolution == IntegrationResolution.Pending() && pending.blocker.nonEmpty && f.executions.get() == 0)
        missing <- f.freshJournal
        run <- f.coordinator(missing).run(intent.id).either
        prepare <- f.coordinator(missing).prepare(intent).either
        _ <- assertIO(run.isLeft && prepare.isLeft && f.executions.get() == 0)
      } yield ()
    } }

    "recover prepared and reserved operations without acquiring a reservation or launching Git" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        prepared <- coordinator.recover(intent.id)
        _ <- assertIO(prepared.isEmpty && f.executions.get() == 0)
        absent <- ZIO.attempt(f.server.call(Command.Read(ReadInput(f.owner.project, ReadSelection.Integration(intent.id)))))
        _ <- assertIO(absent.isInstanceOf[Result.Failed])
        _ <- ZIO.attempt(f.server.integrate(HostIntegrationInput(f.owner.project, HostIntegration.Reserve(intent))))
        released <- coordinator.recover(intent.id)
        _ <- assertIO(released.exists(_.record.resolution.isInstanceOf[IntegrationResolution.NotApplied]) && f.executions.get() == 0)
        replay <- coordinator.recover(intent.id)
        _ <- assertIO(replay == released && f.server.observations.get() == 1)
        _ <- f.isolation
      } yield ()
    } }

    "refuse to classify missing server evidence as no effect after local execution admission" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- f.journal.locked(intent.id)(entry => ZIO.attempt(entry.write(entry.read.get.copy(attempted = true))))
        recovered <- coordinator.recover(intent.id).either
        _ <- assertIO(recovered.isLeft)
        _ <- assertIO(f.executions.get() == 0 && f.server.observations.get() == 0)
      } yield ()
    } }

    "resolve a shutdown admission refusal as no effect rather than leaving an unlaunched reservation pending" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- f.closeAdmission
        stopped <- coordinator.run(intent.id)
        receipt <- f.git.execution(intent)
        _ <- assertIO(receipt.isEmpty)
        _ <- assertIO(stopped.record.resolution.isInstanceOf[IntegrationResolution.NotApplied])
        recovered <- coordinator.recover(intent.id)
        _ <- assertIO(recovered.contains(stopped) && f.server.observations.get() == 1)
        _ <- f.isolation
      } yield ()
    } }

    "keep attempted recovery pending and reject missing local ownership evidence without launching" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- ZIO.attempt(f.server.integrate(HostIntegrationInput(f.owner.project, HostIntegration.Reserve(intent))))
        _ <- f.journal.locked(intent.id)(entry => ZIO.attempt(entry.write(entry.read.get.copy(attempted = true))))
        pending <- coordinator.recover(intent.id)
        _ <- assertIO(pending.exists(value => value.record.resolution == IntegrationResolution.Pending() && value.blocker.nonEmpty))
        missing <- f.freshJournal
        rejected <- f.coordinator(missing).recover(intent.id).either
        _ <- assertIO(rejected.isLeft && f.executions.get() == 0 && f.server.observations.get() == 0)
      } yield ()
    } }

    "replay a retained incorporation through reconcile-only recovery after lost domain acknowledgements" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- ZIO.succeed(f.server.failRecording.set(true))
        pending <- coordinator.run(intent.id)
        _ <- assertIO(pending.record.resolution == IntegrationResolution.Pending())
        _ <- ZIO.succeed(f.server.loseAcknowledgement.set(true))
        lost <- coordinator.recover(intent.id)
        _ <- assertIO(lost.exists(_.blocker.nonEmpty))
        recovered <- coordinator.recover(intent.id)
        replay <- coordinator.recover(intent.id)
        _ <- assertIO(recovered == replay && recovered.exists(_.record.resolution.isInstanceOf[IntegrationResolution.Recorded]) &&
          f.executions.get() == 1 && f.server.observations.get() == 1)
        _ <- f.isolation
      } yield ()
    } }

    "reject checked-out targets before launching and keep terminal observation and coordinator ownership exclusive" in { (harness: IntegrationHarness) => harness.use { f =>
      val intent = f.intent(f.base, f.first)
      val coordinator = f.coordinator(f.journal)
      for {
        _ <- coordinator.prepare(intent)
        _ <- f.checkout
        rejected <- coordinator.run(intent.id)
        _ <- assertIO(rejected.record.resolution.isInstanceOf[IntegrationResolution.NotApplied] && f.executions.get() == 0)
        replay <- coordinator.run(intent.id)
        _ <- assertIO(rejected == replay)
        _ <- f.journal.locked(intent.id) { entry => for {
          nested <- f.journal.locked(IntegrationId(UUID.randomUUID()))(_ => ZIO.unit).either
          altered <- ZIO.attempt(entry.write(entry.read.get.copy(observation = None))).either
          _ <- assertIO(nested.isLeft && altered.isLeft)
        } yield () }
        _ <- f.isolation
      } yield ()
    } }
  }
}

final class IntegrationCoordinatorDummy extends IntegrationCoordinatorTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class IntegrationCoordinatorProcess extends IntegrationCoordinatorTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
