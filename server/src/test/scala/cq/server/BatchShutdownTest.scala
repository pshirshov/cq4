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
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{IO, ZIO}

final class BatchShutdownProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin, WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private val SigintExit = 130

  "Batch supervisor shutdown (Behavioral Active Blackbox; JVM/Git/process Communication)" should {
    "stop the governor, remove its workspace and deliver the receipt and Finish usage when interrupted by SIGINT" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = Scope(ProjectId(UUID.randomUUID()), Actor("CQ governor", SessionId(UUID.randomUUID()), Role.Governor))
      val workspace = local.fixture.spec(scope)
      for {
        at <- ZIO.attemptBlocking {
          val at = Files.createTempDirectory(local.directory, "batch-")
          Files.writeString(at.resolve("workspace.json"), WorkspaceSpec_JsonCodec.encode(BaboonCodecContext.Default, workspace).noSpaces)
          at
        }
        session = at.resolve("session")
        governor <- ZIO.attemptBlocking {
          val process = ShutdownFixture.launch(at, ShutdownFixture.BatchFixtureRole, guardian.binary, Map.empty, Map.empty)
          try {
            def running: Boolean = Files.isDirectory(session.resolve("workspaces")) &&
              Using.resource(Files.list(session.resolve("workspaces")))(_.iterator().asScala.exists(entry => Files.exists(entry.resolve("tree").resolve("running"))))
            // The startup recovery records its receipt in the background while the governor starts.
            ShutdownFixture.awaitUntil(process, at, Duration.ofSeconds(60))(running && Files.exists(session.resolve("workspaces").resolve("cleanup.json")))
            assert(new ProcessBuilder("kill", "-INT", process.pid().toString).inheritIO().start().waitFor() == 0)
            assert(process.waitFor(60, TimeUnit.SECONDS), "Batch supervisor did not exit after SIGINT")
            assert(process.exitValue() == SigintExit, Files.readString(at.resolve("owner.log")))
            AttemptId(UUID.fromString(Files.readString(at.resolve("governor-attempt")).trim))
          } finally if (process.isAlive) process.destroyForcibly()
        }
        service = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(session.resolve("workspaces"), local.command, Clock.systemUTC()))
        record <- service.get(scope, governor)
        _ <- ZIO.attemptBlocking {
          val log = Files.readString(at.resolve("owner.log"))
          assert(record.admission == WorkspaceAdmission.Removed && !Files.exists(Path.of(record.directory)), record.toString + "\n" + log)
          val cleanup = HostFiles.read(session.resolve("workspaces").resolve("cleanup.json"), WorkspaceCleanupReceipt_JsonCodec, 65536)
          assert(cleanup.owner == scope.actor.session && cleanup.live.isEmpty && cleanup.sessions.isEmpty && !cleanup.deadlineExceeded, cleanup.toString)
          val receipt = HostFiles.read(session.resolve("receipt.json"), SupervisorReceipt_JsonCodec, 65536)
          assert(receipt.attempt == governor && receipt.phase == JobPhase.Settled && !receipt.processSucceeded && receipt.usageDelivered, receipt.toString)
          // The Finish usage is part of the committed final publication, stored under delivery/final with its acknowledgement.
          val delivery = session.resolve("delivery").resolve("final")
          val batches = Using.resource(Files.list(delivery))(_.iterator().asScala.filter(_.toString.endsWith(".json")).toList)
          val finished = batches.filter(path => Files.readString(path).contains("\"Finish\""))
          assert(finished.size == 1 && Files.exists(Path.of(finished.head.toString.stripSuffix(".json") + ".ack")), batches.toString + "\n" + log)
        }
      } yield ()
    }
  }
}
