package cq.server

import cq.api.*
import cq.core.Scope
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.SpecZIO
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO}

final class CheckoutExecutorLocal extends SpecZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def intent(local: LocalWorkspaceFixture, conflict: Boolean): IntegrationIntent = {
    val candidate = local.directory.resolve("candidate")
    local.git(local.source, "worktree", "add", "--detach", candidate.toString, local.base.value)
    Files.writeString(candidate.resolve(if (conflict) "tracked.txt" else "new.txt"), "candidate\n")
    local.git(candidate, "add", "--all")
    local.git(candidate, "-c", "user.name=CQ test", "-c", "user.email=cq@localhost", "commit", "-m", "Candidate")
    val id = IntegrationId(UUID.randomUUID())
    val project = ProjectId(UUID.randomUUID())
    val owner = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor)
    IntegrationIntent(id, project, owner, local.source.toString, "refs/heads/integration", local.base,
      GitCommit(local.git(candidate, "rev-parse", "HEAD")), ArtifactId(UUID.randomUUID()), ArtifactId(UUID.randomUUID()), Nil,
      Fence(ClaimId(UUID.randomUUID()), 1), Nil, ChangeRequest(RequestId(id.value), Nil, Nil, "Fixture"), None)
  }

  "Checkout executor (Behavioral Active Effectual; local Git Communication)" should {
    List("file", "symlink").foreach { kind =>
      s"preserve an ignored $kind at a candidate directory ancestor" in { (local: LocalWorkspaceFixture) => ZIO.attemptBlocking {
        val initial = intent(local, false)
        val worker = local.directory.resolve("candidate")
        Files.delete(worker.resolve("new.txt"))
        Files.createDirectory(worker.resolve("dir"))
        Files.writeString(worker.resolve("dir/new.txt"), "candidate\n")
        local.git(worker, "add", "--all")
        local.git(worker, "-c", "user.name=CQ test", "-c", "user.email=cq@localhost", "commit", "--amend", "--no-edit")
        val value = initial.copy(candidate = GitCommit(local.git(worker, "rev-parse", "HEAD")))
        local.git(local.source, "switch", "-c", "integration")
        val outside = local.directory.resolve("outside")
        Files.writeString(outside, "local content\n")
        val ancestor = local.source.resolve("dir")
        if (kind == "file") Files.writeString(ancestor, "local content\n") else Files.createSymbolicLink(ancestor, outside)
        Files.writeString(local.source.resolve(".git/info/exclude"), "dir\n")
        val before = Files.readAllBytes(local.source.resolve(".git/index")).toList
        val directory = local.directory.resolve("checkout")
        HostFiles.directory(directory)
        val input = directory.resolve("intent.json")
        HostFiles.immutable(input, HostFiles.encode(CheckoutPlan_JsonCodec, CheckoutPlan(value, true)), CheckoutRecords.MaxBytes)
        val result = scala.util.Try(new CheckoutExecutor(sys.env).run(input))
        assert(result.isFailure && result.failed.get.getMessage.contains("Checkout refused"), result.toString)
        assert(local.git(local.source, "rev-parse", "HEAD") == local.base.value)
        assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == before)
        assert(Files.readString(ancestor) == "local content\n" && Files.readString(outside) == "local content\n")
        assert(Files.isSymbolicLink(ancestor) == (kind == "symlink"))
        assert(!Files.exists(directory.resolve("started.json")))
        ()
      } }
    }
    List("staged", "untracked", "ignored").foreach { scenario =>
      s"preserve $scenario content when it collides with the candidate" in { (local: LocalWorkspaceFixture) => ZIO.attemptBlocking {
        val value = intent(local, scenario == "staged")
        local.git(local.source, "switch", "-c", "integration")
        val path = if (scenario == "staged") "tracked.txt" else "new.txt"
        Files.writeString(local.source.resolve(path), "local content\n")
        if (scenario == "staged") local.git(local.source, "add", path)
        if (scenario == "ignored") Files.writeString(local.source.resolve(".git/info/exclude"), "new.txt\n")
        val before = Files.readAllBytes(local.source.resolve(".git/index")).toList
        val directory = local.directory.resolve("checkout")
        HostFiles.directory(directory)
        val input = directory.resolve("intent.json")
        HostFiles.immutable(input, HostFiles.encode(CheckoutPlan_JsonCodec, CheckoutPlan(value, true)), CheckoutRecords.MaxBytes)
        val result = scala.util.Try(new CheckoutExecutor(sys.env).run(input))
        assert(result.isFailure && result.failed.get.getMessage.contains("Checkout refused"), result.toString)
        assert(local.git(local.source, "rev-parse", "HEAD") == local.base.value)
        assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == before)
        assert(Files.readString(local.source.resolve(path)) == "local content\n")
        assert(!Files.exists(directory.resolve("started.json")))
        local.git(local.source, "symbolic-ref", "HEAD", value.target)
        local.git(local.source, "update-ref", value.target, local.base.value, local.base.value)
        ()
      } }
    }
    "refuse overlapping edits without changing content or leaving Git locks" in { (local: LocalWorkspaceFixture) => ZIO.attemptBlocking {
      val value = intent(local, true)
      local.git(local.source, "switch", "-c", "integration")
      Files.writeString(local.source.resolve("tracked.txt"), "local edits\n")
      val index = Files.readAllBytes(local.source.resolve(".git/index")).toList
      val directory = local.directory.resolve("checkout")
      HostFiles.directory(directory)
      val input = directory.resolve("intent.json")
      HostFiles.immutable(input, HostFiles.encode(CheckoutPlan_JsonCodec, CheckoutPlan(value, true)), CheckoutRecords.MaxBytes)
      val result = scala.util.Try(new CheckoutExecutor(sys.env).run(input))
      assert(result.isFailure && result.failed.get.getMessage.contains("Checkout refused"), result.toString)
      assert(local.git(local.source, "rev-parse", "HEAD") == local.base.value)
      assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == index)
      assert(Files.readString(local.source.resolve("tracked.txt")) == "local edits\n")
      assert(!Files.exists(directory.resolve("started.json")))
      local.git(local.source, "symbolic-ref", "HEAD", value.target)
      local.git(local.source, "update-ref", value.target, local.base.value, local.base.value)
      local.git(local.source, "add", "tracked.txt")
      ()
    } }

    "detect a target checked out after scheduling before advancing its ref without its files" in { (local: LocalWorkspaceFixture) =>
      val value = intent(local, false)
      local.git(local.source, "branch", "integration")
      val jobs = new IntegrationJobs {
        override def execute(workspace: WorkspaceSpec, command: JobCommand): Task[Unit] = ZIO.attemptBlocking {
          local.git(local.source, "switch", "integration")
          val builder = new ProcessBuilder(command.arguments.asJava).directory(local.source.toFile)
          builder.environment().clear(); builder.environment().putAll(command.environment.asJava)
          builder.redirectOutput(local.directory.resolve("executor-out").toFile).redirectError(local.directory.resolve("executor-err").toFile)
          val process = builder.start()
          process.getOutputStream.write(command.input.getBytes(java.nio.charset.StandardCharsets.UTF_8)); process.getOutputStream.close()
          assert(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        override def status(id: AttemptId): Task[JobRecord] = ZIO.fail(new IllegalStateException("No status needed"))
      }
      val git = new SupervisedGitIntegration(Scope(value.project, value.owner), local.source, value.target, local.command, jobs,
        local.directory.resolve("payload"), sys.env,
        ExecutionLimits(Duration.ofSeconds(3), None, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), 65536), CqEntrypoint.command)
      git.execute(value) *> ZIO.attemptBlocking {
        val head = local.git(local.source, "rev-parse", "HEAD")
        assert(head == local.base.value || (head == value.candidate.value && Files.exists(local.source.resolve("new.txt"))),
          "Branch advanced to candidate while its checked-out files remained at the old commit")
      }
    }
    "D153: settle as not applied an integration whose Git job ended without starting, and keep the job from starting afterwards" in { (local: LocalWorkspaceFixture) =>
      val value = intent(local, false)
      local.git(local.source, "branch", "integration")
      val owner = Scope(value.project, value.owner)
      // Git jobs that the host gave up on before they started: each record is terminal unless the case says otherwise, and nothing ran.
      val phase = new java.util.concurrent.atomic.AtomicReference[JobPhase](JobPhase.Uncertain)
      val jobs = new IntegrationJobs {
        private var records = Map.empty[AttemptId, JobRecord]
        override def execute(workspace: WorkspaceSpec, command: JobCommand): Task[Unit] = ZIO.attempt {
          val problem = Option.when(phase.get == JobPhase.Uncertain)("Guardian start acknowledgement deadline exceeded")
          records = records.updated(workspace.attempt, JobRecord(workspace, command.fingerprint, if (problem.isEmpty) JobTarget.Run else JobTarget.Stop, phase.get, None, problem, 2, 1, 2))
        }
        override def status(id: AttemptId): Task[JobRecord] = ZIO.attempt(records.getOrElse(id, throw cq.core.DomainFailure(Fault.Missing("No job"))))
      }
      val git = new SupervisedGitIntegration(owner, local.source, value.target, local.command, jobs, local.directory.resolve("payload"), sys.env,
        ExecutionLimits(Duration.ofSeconds(3), None, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), 65536), CqEntrypoint.command)
      val server = new IntegrationReceiver
      val coordinator = new IntegrationCoordinator(owner, new MemoryIntegrationJournal(owner), git, server, server)
      def evidence(of: IntegrationIntent): Path = local.directory.resolve("checkouts").resolve(of.id.value.toString)
      def files(of: IntegrationIntent): Set[String] = scala.util.Using.resource(Files.list(evidence(of)))(_.iterator().asScala.map(_.getFileName.toString).toSet)
      def another: IntegrationIntent = {
        val id = IntegrationId(UUID.randomUUID())
        value.copy(id = id, change = value.change.copy(request = RequestId(id.value)))
      }
      val Unconfirmed = "Git execution or incorporation remains unconfirmed; retain the reservation and journal: target is at the expected commit; "
      val Uncertain = "Job Uncertain: no confirmed exit; Guardian start acknowledgement deadline exceeded; "
      def target: String = local.git(local.source, "rev-parse", value.target)
      // The job of `of` ends as the case arranges once its intent is retained; the integration then stays pending, whatever is asked again.
      // D155: the blocker says why the outcome is unconfirmed.
      def pending(of: IntegrationIntent, why: String, arrange: => Unit, release: => Unit): Task[Unit] = for {
        _ <- coordinator.prepare(of)
        _ <- ZIO.attemptBlocking { HostFiles.directory(evidence(of)); arrange }
        runs <- ZIO.foreach(List(1, 2))(_ => coordinator.run(of.id))
        discarded <- coordinator.discard(of.id).either
        _ <- ZIO.attemptBlocking {
          release
          assert(runs.forall(run => run.record.resolution == IntegrationResolution.Pending() && run.blocker.exists(_.startsWith(Unconfirmed + why))), runs.toString)
          assert(discarded.isLeft && !Files.exists(evidence(of).resolve("refused.json")) && target == local.base.value, files(of).toString)
        }
      } yield ()
      for {
        _ <- coordinator.prepare(value)
        first <- coordinator.run(value.id)
        _ <- ZIO.attemptBlocking {
          println(s"Integration whose job never started: ${first.record.resolution} blocker=${first.blocker} evidence=${files(value)}")
          assert(first.record.resolution == IntegrationResolution.NotApplied(SupervisedGitIntegration.Withdrawn) && first.blocker.isEmpty, s"${first.record.resolution} ${first.blocker} evidence=${files(value)}")
          assert(server.observations.get() == 1 && target == local.base.value && files(value) == Set("intent.json", "refused.json", CheckoutRecords.Lock))
          // The job cannot start any more: an executor launched late finds the withdrawal and changes nothing.
          val late = scala.util.Try(new CheckoutExecutor(sys.env).run(evidence(value).resolve("intent.json")))
          assert(late.isFailure && late.failed.get.getMessage.contains("already has retained evidence") && !Files.exists(evidence(value).resolve("started.json")) && target == local.base.value, late.toString)
          assert(!Files.exists(local.source.resolve(".git/index.lock")))
        }
        replay <- coordinator.run(value.id)
        _ <- ZIO.attempt(assert(replay.record == first.record && server.observations.get() == 1))
        // An executor that refused before any effect, under a job the host could not confirm: its own refusal settles the integration.
        refusing = another
        _ <- coordinator.prepare(refusing)
        _ <- ZIO.attemptBlocking {
          HostFiles.directory(evidence(refusing))
          HostFiles.immutable(evidence(refusing).resolve("refused.json"), HostFiles.encode(CheckoutRefusal_JsonCodec, CheckoutRefusal(refusing, "Checkout refused: fixture")), CheckoutRecords.MaxBytes)
        }
        refused <- coordinator.run(refusing.id)
        _ <- ZIO.attempt(assert(refused.record.resolution == IntegrationResolution.NotApplied("Checkout refused: fixture") && refused.blocker.isEmpty, refused.toString))
        // What cannot be settled stays pending: a job that began its effects, one whose executor runs, one whose executor left the index
        // locked, and one that has not ended.
        started = another
        _ <- pending(started, Uncertain + "checkout evidence: started.json", { Files.writeString(evidence(started).resolve("started.json"), "{}"); () }, ())
        running = another
        held = new java.util.concurrent.atomic.AtomicReference[java.nio.channels.FileChannel]()
        _ <- pending(running, Uncertain + "its executor holds the checkout lock", { held.set(CheckoutRecords.lock(evidence(running))); held.get.lock(); () }, held.get.close())
        locking = another
        _ <- pending(locking, Uncertain + "its executor left the index locked", { Files.writeString(local.source.resolve(".git/index.lock"), CheckoutRecords.lockText(locking, evidence(locking))); () },
          Files.delete(local.source.resolve(".git/index.lock")))
        unfinished = another
        _ <- ZIO.succeed(phase.set(JobPhase.Running))
        _ <- pending(unfinished, "its Git job is Running", (), ())
        // Once the evidence of an effect is gone, the same integration settles.
        _ <- ZIO.attemptBlocking(Files.delete(evidence(started).resolve("started.json")))
        settled <- coordinator.run(started.id)
        _ <- ZIO.attempt(assert(settled.record.resolution == IntegrationResolution.NotApplied(SupervisedGitIntegration.Withdrawn) && target == local.base.value, settled.toString))
      } yield ()
    }
  }
}
