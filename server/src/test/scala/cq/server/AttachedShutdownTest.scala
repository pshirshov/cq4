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
  private val UnresolvedExit = 75
  /** Fixture limits: grace 100 ms + kill 1 s + the watchdog's 10 s host drain. */
  private val Drain = Duration.ofMillis(11100)

  private def owner: Scope = Scope(ProjectId(UUID.randomUUID()), Actor("CQ governor", SessionId(UUID.randomUUID()), Role.Governor))
  private def prepare(local: LocalWorkspaceFixture, workspace: WorkspaceSpec): IO[Throwable, Path] = ZIO.attemptBlocking {
    val at = Files.createTempDirectory(local.directory, "attached-")
    Files.writeString(at.resolve("workspace.json"), WorkspaceSpec_JsonCodec.encode(BaboonCodecContext.Default, workspace).noSpaces)
    at
  }

  private def scenario(local: LocalWorkspaceFixture, guardian: GuardianFixture, expectedExit: Int)(end: Process => Unit): IO[Throwable, Unit] = {
    val scope = owner
    for {
      at <- prepare(local, local.fixture.spec(scope))
      attempts <- ZIO.attemptBlocking {
        val process = ShutdownFixture.launch(at, ShutdownFixture.AttachedFixtureRole, guardian.binary, Map.empty)
        try {
          // owner.json is written by the program's initial step, after its termination guard is installed; attempts alone precede program.run.
          val started = List(at.resolve("attempts"), at.resolve("session").resolve("owner.json"))
          ShutdownFixture.awaitUntil(process, at, Duration.ofSeconds(60))(started.forall(Files.exists(_)))
          val attempts = Files.readString(at.resolve("attempts")).linesIterator.map(value => AttemptId(UUID.fromString(value))).toList
          assert(attempts.size == ShutdownFixture.Children)
          end(process)
          assert(process.waitFor(60, TimeUnit.SECONDS), "Attached fixture did not exit after its owner ended the session")
          assert(process.exitValue() == expectedExit, Files.readString(at.resolve("owner.log")))
          attempts
        } finally if (process.isAlive) process.destroyForcibly()
      }
      session = at.resolve("session")
      service = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(session.resolve("workspaces"), local.command, Clock.systemUTC()))
      records <- ZIO.foreach(attempts)(attempt => service.get(scope, attempt))
      _ <- ZIO.attemptBlocking {
        assert(records.map(_.admission) == List.fill(attempts.size)(WorkspaceAdmission.Removed),
          records.map(_.admission).toString + "\n" + Files.readString(at.resolve("owner.log")))
        assert(records.forall(record => !Files.exists(Path.of(record.directory))))
        val receipt = HostFiles.read(session.resolve("workspaces").resolve("cleanup.json"), WorkspaceCleanupReceipt_JsonCodec, 65536)
        assert(receipt.owner == scope.actor.session && receipt.removed.toSet == attempts.toSet && receipt.quarantined.isEmpty && receipt.retained.isEmpty &&
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
    "halt with the unresolved exit at the base drain deadline when EOF finds the initial record fsync stalled" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      for {
        at <- prepare(local, local.fixture.spec(scope))
        _ <- ZIO.attemptBlocking {
          val latch = Files.createDirectory(at.resolve("initial-stall"))
          val stall = Map("LD_PRELOAD" -> sys.env("CQ_SHUTDOWN_STALL_LIBRARY"), "CQ_FIXTURE_STALL_MODE" -> "attached-initial", "CQ_FIXTURE_STALL_ROOT" -> latch.toString)
          val process = ShutdownFixture.launch(at, ShutdownFixture.AttachedFixtureRole, guardian.binary, stall)
          try {
            ShutdownFixture.awaitUntil(process, at, Duration.ofSeconds(60))(Files.exists(latch.resolve("entered")))
            val closed = System.nanoTime()
            process.getOutputStream.close()
            assert(process.waitFor(Drain.plusSeconds(5).toMillis, TimeUnit.MILLISECONDS), "Attached host survived EOF with its initial fsync stalled beyond the base drain deadline")
            val elapsed = Duration.ofNanos(System.nanoTime() - closed)
            assert(process.exitValue() == UnresolvedExit && elapsed.compareTo(Drain.minusSeconds(1)) >= 0, s"exit ${process.exitValue()} after $elapsed")
            assert(!Files.exists(at.resolve("session").resolve("workspaces").resolve("cleanup.json")))
          } finally {
            Files.createFile(latch.resolve("release"))
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
          }
        }
      } yield ()
    }
  }
}
