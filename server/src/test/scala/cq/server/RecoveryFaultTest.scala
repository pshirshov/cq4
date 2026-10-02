package cq.server

import cq.api.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import zio.{Task, ZIO}

/**
 * D103: a listing that fails with EIO is a fault of reaching a record, not a property of the record. The startup recovery under the
 * fault runs in its own JVM with `dev/readdir-fault.c` preloaded (`CQ_READDIR_FAULT_LIBRARY`, built by `./dev/check process`); the case
 * names are those of the R10 probe.
 */
final class RecoveryFaultProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))

  /** Three startups: one in a forked JVM with every listing of `target(faulty)` failing, then two without the fault. Only the first
    * needs the fault library, so the later ones run in this JVM and the suite forks one process per case. */
  private def scenario(local: LocalWorkspaceFixture, name: String)(target: EndedSession => Path): Task[Unit] = {
    val state = RecoveryState(local)
    val library = sys.env("CQ_READDIR_FAULT_LIBRARY")
    for {
      faulty <- state.session(1)
      healthy <- state.session(1)
      log <- ZIO.attemptBlocking { faulty.finish(); healthy.finish(); local.directory.resolve(s"$name-startup-1.log") }
      first <- ZIO.attemptBlocking(RecoveryStartup.fork(state, log, Map("LD_PRELOAD" -> library, "CQ_TEST_READDIR_FAULT" -> target(faulty).toRealPath().toString)))
      marked <- ZIO.attemptBlocking((faulty.markerText, healthy.outcome))
      swept <- healthy.admissions
      second <- state.recover
      states <- faulty.admissions
      third <- state.recover
      _ <- ZIO.attemptBlocking {
        println(s"$name: first=$first markers=$marked second=$second third=${third.totals}")
        val receipt = first.getOrElse(fail("The pass ended without a receipt:\n" + Files.readString(log)))
        val problem = faulty.reported(receipt).flatMap(_.problem).getOrElse("")
        // The fault was injected, reported for its session, and proved nothing about the session's records.
        assert(problem.startsWith("Session could not be examined") && problem.contains("FileSystemException") && marked._1.isEmpty, s"$receipt marker=${marked._1}")
        assert(receipt.totals.examined == 2 && receipt.totals.abandoned == 0 && receipt.totals.recovered == 1 && receipt.totals.problems == 1, receipt.toString)
        assert(healthy.reported(receipt).exists(_.removed == healthy.attempts) && marked._2.contains(RecoveryOutcome.Recovered) && swept == List(WorkspaceAdmission.Removed), s"$receipt $swept")
        assert(second.sessions.map(_.session) == List(faulty.id) && second.sessions.head.removed == faulty.attempts && second.totals.recovered == 1, second.toString)
        assert(faulty.outcome.contains(RecoveryOutcome.Recovered) && states == List(WorkspaceAdmission.Removed) && third.totals.examined == 0, s"${faulty.recovery} $states $third")
      }
    } yield ()
  }

  "Session recovery under a failing directory listing (Behavioral Active Blackbox; local Git Communication, forked JVM)" should {
    "io-10: write no marker when listing the job journal fails with EIO, and recover the session at the first startup without the fault" in { (local: LocalWorkspaceFixture) =>
      scenario(local, "io-10")(_.directory.resolve("journal"))
    }

    "io-12: record EIO on the final publication's listing as that session's problem, write the receipt and still recover a later session" in { (local: LocalWorkspaceFixture) =>
      scenario(local, "io-12")(_.directory.resolve("delivery").resolve("final"))
    }
  }
}
