package cq.server

import cq.api.StopReason
import cq.host.*
import distage.ModuleDef
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import scala.util.Using
import zio.ZIO

final case class GuardianFixture(binary: Path, root: Path, environment: Map[String, String]) {
  def spec(command: List[String], execution: Option[Duration]): ExecutionSpec = {
    val directory = Files.createDirectory(root.resolve(UUID.randomUUID().toString))
    val input = directory.resolve("input")
    Files.writeString(input, "host input λ\n")
    ExecutionSpec(directory, command, environment, input, directory.resolve("stdout"), directory.resolve("stderr"),
      ExecutionLimits(Duration.ofSeconds(2), execution, Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144))
  }
  def artificial(script: String): (Path, ExecutionSpec) = {
    val execution = spec(List("true"), Some(Duration.ofSeconds(30)))
    val helper = execution.directory.resolve("artificial-guardian")
    Files.writeString(helper, "#!/bin/sh\n" + script + "\n")
    require(helper.toFile.setExecutable(true))
    (helper, execution)
  }
}
object GuardianTestPlugin extends PluginDef {
  include(new ModuleDef {
    make[GuardianFixture].fromEffect(ZIO.attemptBlocking {
      val root = Path.of(".work").toAbsolutePath.normalize()
      Files.createDirectories(root)
      GuardianFixture(Path.of(sys.env("CQ_GUARDIAN_TEST_BINARY")), Files.createTempDirectory(root, "guardian-driver-"), sys.env)
    })
  })
}

final class GuardianDriverProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin)))

  "Guardian driver (Behavioral Active Blackbox; local Process Communication)" should {
    "report observed execution and retain separate output without blocking the start call" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      // The command ends only once the gate exists, and the gate is created after `start` has returned: a `start` that waited for
      // the command would end at the execution deadline instead of with the command's own exit.
      val gate = fixture.root.resolve("gate-" + UUID.randomUUID())
      val spec = fixture.spec(List("python3", "-c", "import os,sys,time; print(sys.stdin.read(),end=''); sys.stderr.write('diagnostic')\nwhile not os.path.exists(sys.argv[1]): time.sleep(0.01)",
        gate.toString), Some(Duration.ofSeconds(30)))
      Using.resource(new GuardianDriver(fixture.binary).start(spec)) { running =>
        assert(running.status.result.isEmpty)
        Files.createFile(gate)
        val observed = running.await(Duration.ofSeconds(40))
        assert(observed.phase == ProcessPhase.Settled && observed.result.exists(r => r.code.contains(0) && r.reason == StopReason.Exited))
        assert(observed.helperPid.nonEmpty && observed.rootPid.nonEmpty)
        assert(Files.readString(spec.stdout) == "host input λ\n" && Files.readString(spec.stderr) == "diagnostic")
        assert(running.cancel() == observed)
      }
    }}

    "D95: let a command exceed its retained output bound and exit normally with the whole stream on disk" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val spec = fixture.spec(List("python3", "-c", "import os\nfor _ in range(128): os.write(1, b'o' * 8192)"), Some(Duration.ofSeconds(10)))
      Using.resource(new GuardianDriver(fixture.binary).start(spec)) { running =>
        val observed = running.await(Duration.ofSeconds(15))
        assert(observed.phase == ProcessPhase.Settled && observed.result.exists(r => r.code.contains(0) && r.reason == StopReason.Exited && r.stdoutBytes == 1048576),
          observed.toString)
        assert(Files.size(spec.stdout) == 1048576)
      }
    }}

    "cancel promptly and settle the actual root before declaring cleanup complete" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val spec = fixture.spec(List("python3", "-c", "import signal,time; signal.signal(signal.SIGTERM, lambda *args: time.sleep(30)); print('ready',flush=True); time.sleep(30)"), Some(Duration.ofSeconds(40)))
      Using.resource(new GuardianDriver(fixture.binary).start(spec)) { running =>
        val readinessDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos
        while (!Files.exists(spec.stdout) || !Files.readString(spec.stdout).contains("ready")) {
          require(System.nanoTime() < readinessDeadline, "Cancellation fixture never installed its termination handler")
          Thread.sleep(10)
        }
        val requested = running.cancel()
        assert(requested.cancellationRequested && requested.phase == ProcessPhase.Stopping && requested.result.isEmpty)
        assert(running.status.result.isEmpty, "Cancellation waited for actual settlement before returning its earlier observation")
        val observed = running.await(Duration.ofSeconds(5))
        assert(observed.phase == ProcessPhase.Settled && observed.result.exists(r => r.settled && r.reason == StopReason.Cancelled))
      }
    }}

    "distinguish a command deadline from unknown cleanup after abrupt guardian death" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val deadline = fixture.spec(List("sleep", "30"), Some(Duration.ofMillis(150)))
      Using.resource(new GuardianDriver(fixture.binary).start(deadline)) { running =>
        val observed = running.await(Duration.ofSeconds(5))
        assert(observed.phase == ProcessPhase.Settled && observed.result.exists(_.reason == StopReason.ExecutionDeadline))
      }
      val killed = fixture.spec(List("python3", "-c", "import os,signal; os.kill(os.getppid(),signal.SIGKILL)"), Some(Duration.ofSeconds(3)))
      Using.resource(new GuardianDriver(fixture.binary).start(killed)) { running =>
        val observed = running.await(Duration.ofSeconds(5))
        assert(observed.phase == ProcessPhase.Uncertain && observed.problem.nonEmpty)
      }
    }}

    "I21: run a command without an execution limit past every other wall-clock bound of its job and settle its own exit" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val spec = fixture.spec(List("sleep", "6"), None)
      Using.resource(new GuardianDriver(fixture.binary).start(spec)) { running =>
        val observed = running.await(Duration.ofSeconds(12))
        assert(observed.phase == ProcessPhase.Settled && observed.result.exists(r => r.code.contains(0) && r.reason == StopReason.Exited), observed.toString)
      }
    }}

    "reject forged completion, contradictory exits and malformed lifecycle numbers" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val start = GuardianTranscript(None, None, None)
      val valid = start.append("START 123").append("STOP Exited").append("EXIT 0 0 Exited 12 5 1 0")
      assert(valid.complete(0).code.contains(0))
      val invalid = List(
        () => start.append("EXIT 0 0 Exited 0 0 1 0"),
        () => start.append("START 1"),
        () => start.append("START 2147483648"),
        () => start.append("START 3").append("START 3"),
        () => start.append("STOP Cancelled").append("EXIT 0 0 Exited 0 0 1 0"),
        () => start.append("START 3").append("STOP Exited").append("EXIT -1 0 Exited 0 0 1 0"),
        () => valid.append("START 3"),
        () => valid.complete(3),
      )
      invalid.foreach(operation => assert(scala.util.Try(operation()).isFailure))
      val (falseGuardian, spec) = fixture.artificial("printf 'EXIT 0 0 Exited 0 0 1 0\\n'")
      Using.resource(new GuardianDriver(falseGuardian).start(spec)) { running =>
        val observed = running.await(Duration.ofSeconds(5))
        assert(observed.phase == ProcessPhase.Uncertain && observed.problem.nonEmpty)
      }
    }}

    "bound missing start acknowledgement independently of the command deadline" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val (helper, spec) = fixture.artificial("read line")
      Using.resource(new GuardianDriver(helper).start(spec)) { running =>
        assert(running.await(Duration.ofSeconds(3)).phase == ProcessPhase.Uncertain)
      }
    }}

    "bound a hung helper after its terminal record" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val (helper, spec) = fixture.artificial("printf 'START 123\\nSTOP Exited\\nEXIT 0 0 Exited 0 0 1 0\\n'\nread line")
      Using.resource(new GuardianDriver(helper).start(spec)) { running =>
        assert(running.await(Duration.ofSeconds(3)).phase == ProcessPhase.Uncertain)
      }
    }}

    "reject premature lifecycle EOF while the helper is still alive" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val (helper, spec) = fixture.artificial("printf 'START 123\\n'\nexec 1>&-\nread line")
      Using.resource(new GuardianDriver(helper).start(spec)) { running =>
        assert(running.await(Duration.ofSeconds(3)).phase == ProcessPhase.Uncertain)
      }
    }}

    "bound observed stopping independently of the original execution deadline" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val (helper, spec) = fixture.artificial("printf 'STOP OutputLimit\\n'\nread line")
      Using.resource(new GuardianDriver(helper).start(spec)) { running =>
        assert(running.await(Duration.ofSeconds(4)).phase == ProcessPhase.Uncertain)
      }
    }}
  }
}
