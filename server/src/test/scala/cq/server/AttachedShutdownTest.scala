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

  private def receiptFile(session: Path): Path = session.resolve("workspaces").resolve("cleanup.json")
  private def receipt(session: Path): WorkspaceCleanupReceipt = HostFiles.read(receiptFile(session), WorkspaceCleanupReceipt_JsonCodec, 1024 * 1024)
  /** The Finish usage is the committed final publication of an attached session, stored under delivery/final with its acknowledgement. */
  private def finishDelivered(session: Path): Boolean = {
    val delivery = session.resolve("delivery").resolve("final")
    Files.isDirectory(delivery) && Using.resource(Files.list(delivery))(_.iterator().asScala.toList).exists(path => path.toString.endsWith(".json") &&
      Files.readString(path).contains("\"Finish\"") && Files.exists(Path.of(path.toString.stripSuffix(".json") + ".ack")))
  }

  private def scenario(local: LocalWorkspaceFixture, guardian: GuardianFixture, expectedExit: Int)(end: Process => Unit): IO[Throwable, Unit] = {
    val scope = owner
    for {
      at <- prepare(local, local.fixture.spec(scope))
      session = at.resolve("session")
      attempts <- ZIO.attemptBlocking {
        val process = ShutdownFixture.launch(at, ShutdownFixture.AttachedFixtureRole, guardian.binary, Map.empty, Map.empty)
        try {
          // owner.json is written by the program's initial step, after its termination guard is installed; the recovery receipt follows in the background.
          val started = List(at.resolve("attempts"), session.resolve("owner.json"), receiptFile(session))
          ShutdownFixture.awaitUntil(process, at, Duration.ofSeconds(60))(started.forall(Files.exists(_)))
          val attempts = Files.readString(at.resolve("attempts")).linesIterator.map(value => AttemptId(UUID.fromString(value))).toList
          assert(attempts.size == ShutdownFixture.Children)
          end(process)
          assert(process.waitFor(60, TimeUnit.SECONDS), "Attached fixture did not exit after its owner ended the session")
          assert(process.exitValue() == expectedExit, Files.readString(at.resolve("owner.log")))
          attempts
        } finally if (process.isAlive) process.destroyForcibly()
      }
      records <- ZIO.foreach(attempts)(attempt => service(local, session).get(scope, attempt))
      _ <- ZIO.attemptBlocking {
        assert(records.map(_.admission) == List.fill(attempts.size)(WorkspaceAdmission.Removed),
          records.map(_.admission).toString + "\n" + Files.readString(at.resolve("owner.log")))
        assert(records.forall(record => !Files.exists(Path.of(record.directory))))
        val recovered = receipt(session)
        assert(recovered.owner == scope.actor.session && recovered.live.isEmpty && recovered.sessions.isEmpty &&
          !recovered.deadlineExceeded && recovered.startedAt <= recovered.finishedAt, recovered.toString)
        assert(finishDelivered(session), Files.readString(at.resolve("owner.log")))
        assert(registered(local) == Set("worktree " + local.source.toRealPath()), registered(local).toString)
      }
    } yield ()
  }

  /** A fixture host and the files it reports through; `attempts` appears once its children have settled. */
  private final case class Host(at: Path, session: Path, process: Process) {
    def attempts: List[AttemptId] = Files.readString(at.resolve("attempts")).linesIterator.map(value => AttemptId(UUID.fromString(value))).toList
    def log: String = Files.readString(at.resolve("owner.log"))
    def signal(name: String): Int = new ProcessBuilder("kill", "-" + name, process.pid().toString).redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
  }
  private def host(local: LocalWorkspaceFixture, guardian: GuardianFixture, scope: Scope, state: Path, properties: Map[String, String]): ZIO[zio.Scope, Throwable, Host] =
    ZIO.acquireRelease(prepare(local, local.fixture.spec(scope)).flatMap { at => ZIO.attemptBlocking {
      val session = state.resolve(scope.actor.session.value.toString)
      val process = ShutdownFixture.launch(at, ShutdownFixture.AttachedFixtureRole, guardian.binary, Map.empty, properties.updated(ShutdownFixture.StateProperty, state.toString))
      val started = List(at.resolve("attempts"), session.resolve("owner.json"))
      try ShutdownFixture.awaitUntil(process, at, Duration.ofSeconds(60))(started.forall(Files.exists(_)))
      catch { case failure: Throwable => process.destroyForcibly(); throw failure }
      Host(at, session, process)
    } })(value => ZIO.attemptBlocking { value.process.destroyForcibly(); value.process.waitFor(10, TimeUnit.SECONDS) }.orDie)
  private def service(local: LocalWorkspaceFixture, session: Path): WorkspaceService[IO] =
    new WorkspaceService.Impl[IO](new GitWorkspaceRepository(session.resolve("workspaces"), local.command, Clock.systemUTC()))
  private def registered(local: LocalWorkspaceFixture): Set[String] =
    local.git(local.source, "worktree", "list", "--porcelain").linesIterator.filter(_.startsWith("worktree ")).toSet

  "Attached host shutdown (Behavioral Active Blackbox; JVM/Git/process Communication)" should {
    "D96: have removed every settled workspace before the owning harness sends SIGINT, SIGTERM after 100 ms and SIGKILL after a further 400 ms" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      ZIO.scoped { for {
        state <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "state-"))
        running <- host(local, guardian, scope, state, Map.empty)
        attempts = running.attempts
        before <- ZIO.foreach(attempts)(attempt => service(local, running.session).get(scope, attempt))
        _ <- ZIO.attemptBlocking {
          assert(attempts.size == ShutdownFixture.Children && running.process.isAlive)
          assert(before.map(_.admission) == List.fill(attempts.size)(WorkspaceAdmission.Removed),
            "Settled workspaces still await shutdown: " + before.map(_.admission) + "\n" + running.log)
          assert(before.forall(record => !Files.exists(Path.of(record.directory))))
          // Claude Code 2.1.285 ends its MCP servers with this sequence; the later signals find no process when the host has already exited.
          assert(running.signal("INT") == 0)
          Thread.sleep(100)
          running.signal("TERM")
          Thread.sleep(400)
          running.signal("KILL")
          assert(running.process.waitFor(10, TimeUnit.SECONDS), "Attached fixture survived SIGKILL")
          assert(registered(local) == Set("worktree " + local.source.toRealPath()), registered(local).toString)
        }
      } yield () }
    }
    "D96: leave a live session alone, then remove a killed session's workspaces and deliver its Finish usage at the next host startup, with a receipt" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val project = ProjectId(UUID.randomUUID())
      def scope: Scope = Scope(project, Actor("CQ governor", SessionId(UUID.randomUUID()), Role.Governor))
      val (earlier, concurrent, next) = (scope, scope, scope)
      def admissions(session: Path, owner: Scope, attempts: List[AttemptId]): IO[Throwable, List[WorkspaceAdmission]] =
        ZIO.foreach(attempts)(attempt => service(local, session).get(owner, attempt)).map(_.map(_.admission))
      def settle[A](limit: Duration)(observe: IO[Throwable, A])(done: A => Boolean): IO[Throwable, A] =
        observe.repeatUntil(done).timeout(zio.Duration.fromJava(limit)).someOrElseZIO(observe)
      ZIO.scoped { for {
        state <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "state-"))
        // The earlier session stands for one whose host was killed before it released its settled workspaces.
        killed <- host(local, guardian, earlier, state, Map(ShutdownFixture.RetainProperty -> "true"))
        attempts = killed.attempts
        live <- host(local, guardian, concurrent, state, Map.empty)
        observed <- settle(Duration.ofSeconds(30))(ZIO.attemptBlocking(Files.exists(receiptFile(live.session))))(identity)
        untouched <- admissions(killed.session, earlier, attempts)
        _ <- ZIO.attemptBlocking {
          assert(observed, "The concurrent host recorded no recovery receipt\n" + live.log)
          val recovered = receipt(live.session)
          assert(recovered.owner == concurrent.actor.session && recovered.live == List(earlier.actor.session) && recovered.sessions.isEmpty, recovered.toString)
          assert(killed.process.isAlive && untouched == List.fill(attempts.size)(WorkspaceAdmission.Open), untouched.toString + "\n" + live.log)
          assert(killed.signal("KILL") == 0 && killed.process.waitFor(10, TimeUnit.SECONDS))
          assert(!finishDelivered(killed.session))
          live.process.getOutputStream.close()
          assert(live.process.waitFor(60, TimeUnit.SECONDS) && live.process.exitValue() == 0, live.log)
        }
        following <- host(local, guardian, next, state, Map.empty)
        swept <- settle(Duration.ofSeconds(30))(admissions(killed.session, earlier, attempts))(_.forall(_ == WorkspaceAdmission.Removed))
        recorded <- settle(Duration.ofSeconds(30))(ZIO.attemptBlocking(Files.exists(receiptFile(following.session))))(identity)
        _ <- ZIO.attemptBlocking {
          assert(swept == List.fill(attempts.size)(WorkspaceAdmission.Removed),
            "The next host startup left the killed session's workspaces: " + swept + "\n" + following.log)
          assert(recorded, "The next host recorded no recovery receipt\n" + following.log)
          val recovered = receipt(following.session)
          assert(recovered.owner == next.actor.session && recovered.live.isEmpty && !recovered.deadlineExceeded && recovered.sessions ==
            List(SessionCleanup(earlier.actor.session, attempts.sortBy(_.value.toString), Nil, Nil, 1, None)), recovered.toString)
          assert(finishDelivered(killed.session), following.log)
          assert(registered(local) == Set("worktree " + local.source.toRealPath()), registered(local).toString)
        }
      } yield () }
    }
    "end in order and deliver the Finish usage when the owning harness sends SIGINT alone" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      scenario(local, guardian, SigintExit) { process =>
        assert(new ProcessBuilder("kill", "-INT", process.pid().toString).inheritIO().start().waitFor() == 0)
      }
    }
    "end in order and deliver the Finish usage when the owning harness closes the MCP input" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      scenario(local, guardian, 0)(process => process.getOutputStream.close())
    }
    "D148: release the claims the session still holds, and no claim it released itself, when the owning harness closes the MCP input" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      val held = List.fill(2)(ClaimId(UUID.randomUUID()))
      val returned = ClaimId(UUID.randomUUID())
      def call(id: Int, action: ClaimAction): String = io.circe.Json.obj("jsonrpc" -> io.circe.Json.fromString("2.0"), "id" -> io.circe.Json.fromInt(id),
        "method" -> io.circe.Json.fromString("tools/call"), "params" -> io.circe.Json.obj("name" -> io.circe.Json.fromString("claim"),
          "arguments" -> ClaimInput_JsonCodec.encode(BaboonCodecContext.Default, ClaimInput(scope.project, action)))).noSpaces
      val member = Set(ItemId(scope.project, Ledger.Tasks, 1))
      val requests = List("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""") ++
        (held :+ returned).zipWithIndex.map((id, index) => call(index + 2, ClaimAction.Acquire(id, member, 60000))) ++
        List(call(5, ClaimAction.Release(Fence(returned, 1))))
      ZIO.scoped { for {
        state <- ZIO.attemptBlocking(Files.createTempDirectory(local.directory, "state-"))
        running <- host(local, guardian, scope, state, Map.empty)
        _ <- ZIO.attemptBlocking {
          val log = running.at.resolve(ShutdownFixture.ClaimLog)
          def lines: List[String] = if (Files.exists(log)) Files.readString(log).linesIterator.toList else Nil
          val input = running.process.getOutputStream
          requests.foreach(request => input.write((request + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          input.flush()
          // The fixture marks a release that precedes the session's local Finish commit, as the Governor's own release does.
          val acquired = (held :+ returned).map("Acquire " + _.value) :+ s"Release ${returned.value} before Finish"
          // The reply to the last request follows its log line; the host has then observed every claim reply.
          ShutdownFixture.awaitUntil(running.process, running.at, Duration.ofSeconds(60))(lines == acquired && running.log.contains("\"id\":5"))
          input.close()
          assert(running.process.waitFor(60, TimeUnit.SECONDS) && running.process.exitValue() == 0, running.log)
          println(s"Claims at orderly shutdown: ${lines.drop(acquired.size)}")
          // The host releases only after it has committed the Finish, so that the release cannot use up the drain before it.
          assert(lines.drop(acquired.size).sorted == held.map("Release " + _.value).sorted, lines.toString + "\n" + running.log)
          assert(finishDelivered(running.session), running.log)
        }
      } yield () }
    }
    "halt with the unresolved exit at the base drain deadline when EOF finds the initial record fsync stalled" in { (local: LocalWorkspaceFixture, guardian: GuardianFixture) =>
      val scope = owner
      for {
        at <- prepare(local, local.fixture.spec(scope))
        _ <- ZIO.attemptBlocking {
          val latch = Files.createDirectory(at.resolve("initial-stall"))
          val stall = Map("LD_PRELOAD" -> sys.env("CQ_SHUTDOWN_STALL_LIBRARY"), "CQ_FIXTURE_STALL_MODE" -> "attached-initial", "CQ_FIXTURE_STALL_ROOT" -> latch.toString)
          val process = ShutdownFixture.launch(at, ShutdownFixture.AttachedFixtureRole, guardian.binary, stall, Map.empty)
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
