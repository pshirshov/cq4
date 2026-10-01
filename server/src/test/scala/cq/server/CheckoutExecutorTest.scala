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
        ExecutionLimits(Duration.ofSeconds(3), Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), 65536), CqEntrypoint.command)
      git.execute(value) *> ZIO.attemptBlocking {
        val head = local.git(local.source, "rev-parse", "HEAD")
        assert(head == local.base.value || (head == value.candidate.value && Files.exists(local.source.resolve("new.txt"))),
          "Branch advanced to candidate while its checked-out files remained at the old commit")
      }
    }
  }
}
