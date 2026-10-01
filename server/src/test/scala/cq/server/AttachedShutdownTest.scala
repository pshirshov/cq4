package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.{GitWorkspaceRepository, HostFiles}
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.TimeUnit
import zio.{IO, ZIO}

final class AttachedShutdownProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin, WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private val SigintExit = 130

  private def scenario(local: LocalWorkspaceFixture, guardian: GuardianFixture, expectedExit: Int)(end: Process => Unit): IO[Throwable, Unit] = {
    val owner = Scope(ProjectId(UUID.randomUUID()), Actor("CQ governor", SessionId(UUID.randomUUID()), Role.Governor))
    val workspace = local.fixture.spec(owner)
    for {
      at <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "attached-"))
      attempts <- ZIO.attemptBlocking {
        Files.writeString(at.resolve("workspace.json"), WorkspaceSpec_JsonCodec.encode(BaboonCodecContext.Default, workspace).noSpaces)
        val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("Fork fixture classpath is required"))
        val process = new ProcessBuilder(javaBinary.toString, "-cp", classpath, s"-D${AttachedOwnerFixture.RootProperty}=$at",
          s"-D${AttachedOwnerFixture.GuardianProperty}=${guardian.binary}", "cq.server.AttachedOwnerFixture", ":" + AttachedOwnerFixture.FixtureRole.id)
          .redirectErrorStream(true).redirectOutput(at.resolve("owner.log").toFile).start()
        try {
          val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos
          while (!Files.exists(at.resolve("attempts")) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
          assert(process.isAlive && Files.exists(at.resolve("attempts")), Files.readString(at.resolve("owner.log")))
          val attempts = Files.readString(at.resolve("attempts")).linesIterator.map(value => AttemptId(UUID.fromString(value))).toList
          assert(attempts.size == AttachedOwnerFixture.Children)
          end(process)
          assert(process.waitFor(60, TimeUnit.SECONDS), "Attached fixture did not exit after its owner ended the session")
          assert(process.exitValue() == expectedExit, Files.readString(at.resolve("owner.log")))
          attempts
        } finally if (process.isAlive) process.destroyForcibly()
      }
      session = at.resolve("session")
      service = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(session.resolve("workspaces"), local.command, Clock.systemUTC()))
      records <- ZIO.foreach(attempts)(attempt => service.get(owner, attempt))
      _ <- ZIO.attemptBlocking {
        assert(records.map(_.admission) == List.fill(attempts.size)(WorkspaceAdmission.Removed),
          records.map(_.admission).toString + "\n" + Files.readString(at.resolve("owner.log")))
        assert(records.forall(record => !Files.exists(Path.of(record.directory))))
        val receipt = HostFiles.read(session.resolve("workspaces").resolve("cleanup.json"), WorkspaceCleanupReceipt_JsonCodec, 65536)
        assert(receipt.owner == owner.actor.session && receipt.removed.toSet == attempts.toSet && receipt.quarantined.isEmpty && receipt.retained.isEmpty &&
          !receipt.deadlineExceeded && receipt.startedAt <= receipt.finishedAt, receipt.toString)
        val listed = local.git(local.source, "worktree", "list", "--porcelain").linesIterator.filter(_.startsWith("worktree ")).toSet
        assert(listed == Set("worktree " + local.source.toRealPath()), listed.toString)
      }
    } yield ()
  }

  "Attached host shutdown (Behavioral Active Blackbox; JVM/Git/process Communication)" should {
    "remove settled workspaces and record a cleanup receipt when the owning harness sends SIGINT" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      scenario(local, guardian, SigintExit) { process =>
        assert(new ProcessBuilder("kill", "-INT", process.pid().toString).inheritIO().start().waitFor() == 0)
      }
    }
    "remove settled workspaces and record a cleanup receipt when the owning harness closes the MCP input" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      scenario(local, guardian, 0)(process => process.getOutputStream.close())
    }
  }
}
