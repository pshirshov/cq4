package cq.server

import cq.api.{GitCommit, StopReason}
import cq.host.*
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import scala.util.Using
import zio.ZIO

final class MergePreparationProcess extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(GuardianTestPlugin)))

  private final case class Prepared(root: Path, repository: Path, tree: Path, assets: Path, initial: GitCommit,
    inputs: MergeInputs, environment: Map[String, String], index: Array[Byte], head: String) {
    def git(directory: Path, arguments: String*): String = {
      val output = new BoundedHostCommand(GitEnvironment.isolated(environment), Duration.ofSeconds(10), 262144)
        .run(directory, List("git") ++ arguments)
      require(output.exit == 0, output.text)
      output.text.trim
    }
    def unchanged(): Unit = {
      assert(git(repository, "rev-parse", "HEAD") == head)
      assert(java.util.Arrays.equals(Files.readAllBytes(repository.resolve(".git/index")), index))
      assert(Files.readString(repository.resolve("governing")) == "unstaged\n")
    }
  }

  private def prepare(fixture: GuardianFixture, conflict: String): Prepared = {
    val root = Files.createTempDirectory(fixture.root, "merge space '")
    val repository = Files.createDirectory(root.resolve("repository"))
    val tree = root.resolve("tree")
    val assets = root.resolve("assets")
    val environment = HostEnvironment.runtime(fixture.environment)
    def git(arguments: String*): String = {
      val result = new BoundedHostCommand(GitEnvironment.isolated(environment), Duration.ofSeconds(10), 262144)
        .run(repository, List("git") ++ arguments)
      require(result.exit == 0, result.text)
      result.text.trim
    }
    def write(name: String, text: String): Unit = {
      val file = repository.resolve(name)
      Files.createDirectories(file.getParent)
      Files.writeString(file, text)
    }
    def commit(message: String): GitCommit = {
      git("add", "--all")
      git("commit", "-m", message)
      GitCommit(git("rev-parse", "HEAD"))
    }
    git("init", "--initial-branch=main")
    git("config", "user.name", "Merge check")
    git("config", "user.email", "check@localhost")
    write("shared", "initial\n")
    write("old/first", "first\n")
    write("old/second", "second\n")
    val initial = commit("initial")
    conflict match {
      case "directory" =>
        Files.createDirectory(repository.resolve("new"))
        Files.createDirectory(repository.resolve("other"))
        git("mv", "old/first", "new/first")
        git("mv", "old/second", "other/second")
      case "text" => write("shared", "target\n")
      case "clean" => write("left", "target\n")
      case _ => throw new IllegalArgumentException("Unknown merge fixture")
    }
    val base = commit("target")
    git("checkout", "--detach", initial.value)
    conflict match {
      case "directory" => write("old/added", "candidate\n")
      case "text" => write("shared", "candidate\n")
      case "clean" => write("right", "candidate\n")
    }
    val candidate = commit("candidate")
    git("checkout", "main")
    git("worktree", "add", "--detach", tree.toString, base.value)
    write("governing", "staged\n")
    git("add", "governing")
    write("governing", "unstaged\n")
    Prepared(root, repository, tree, assets, initial, MergeInputs(tree, repository.resolve(".git"), base, candidate), environment,
      Files.readAllBytes(repository.resolve(".git/index")), git("rev-parse", "HEAD"))
  }

  private val Native = List("python3", "-c", "import json,os,sys; print(json.dumps({'input':sys.stdin.read(),'argument':sys.argv[1],'git':os.environ.get('GIT_DIR')}))",
    "literal $() ` ; ' argument")
  private def spec(fixture: GuardianFixture, prepared: Prepared, inputs: MergeInputs, environment: Map[String, String]): ExecutionSpec =
    spec(prepared, inputs, environment, fixture.binary)
  private def spec(prepared: Prepared, inputs: MergeInputs, environment: Map[String, String], capture: Path): ExecutionSpec = {
    val launch = new MergePreparation(capture).wrap(HarnessLaunch(Native, environment, Nil), prepared.assets, inputs)
    launch.install(prepared.assets)
    val input = prepared.root.resolve("input")
    Files.writeString(input, "native input λ\n")
    ExecutionSpec(prepared.tree, launch.arguments, launch.environment, input, prepared.root.resolve("stdout"), prepared.root.resolve("stderr"),
      ExecutionLimits(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144))
  }
  private def run(fixture: GuardianFixture, execution: ExecutionSpec): ProcessObservation =
    Using.resource(new GuardianDriver(fixture.binary).start(execution))(_.await(Duration.ofSeconds(15)))
  private def accepted(prepared: Prepared, execution: ExecutionSpec, observation: ProcessObservation, exit: String): Unit = {
    assert(observation.phase == ProcessPhase.Settled && observation.result.exists(result => result.code.contains(0) && result.reason == StopReason.Exited),
      observation.toString + Files.readString(execution.stderr))
    val result = parse(Files.readString(execution.stdout)).toOption.get.hcursor
    assert(result.get[String]("input").toOption.contains("native input λ\n"))
    assert(result.get[String]("argument").toOption.contains(Native.last))
    assert(Files.readString(prepared.assets.resolve("merge-ready")) == exit + "\n")
    assert(Files.readString(prepared.assets.resolve("merge-status")) == exit + "\n")
    assert(Files.readString(execution.stderr) == Files.readString(prepared.assets.resolve("merge.log")))
    assert(prepared.git(prepared.tree, "rev-parse", "HEAD") == prepared.inputs.base.value)
    prepared.unchanged()
  }
  private def denied(prepared: Prepared, execution: ExecutionSpec, observation: ProcessObservation): Unit = {
    assert(observation.phase == ProcessPhase.Settled && observation.result.exists(result => result.code.contains(2) && result.reason == StopReason.Exited), observation)
    assert(Files.readString(execution.stdout).isEmpty)
    assert(Files.readString(prepared.assets.resolve("merge-ready")).isEmpty)
    assert(Files.readString(execution.stderr).contains("CQ merge preparation failed"))
    prepared.unchanged()
  }
  private def injectGit(prepared: Prepared, body: String): Map[String, String] = {
    val bin = Files.createDirectory(prepared.root.resolve("bin"))
    val file = bin.resolve("git")
    Files.writeString(file, "#!/bin/sh\nfor argument; do\nif [ \"$argument\" = merge ]; then\n" + body +
      "\nfi\ndone\nexec \"$CQ_TEST_REAL_GIT\" \"$@\"\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    val executable = new BoundedHostCommand(prepared.environment, Duration.ofSeconds(5), 4096)
      .run(prepared.root, List("sh", "-c", "command -v git"))
    require(executable.exit == 0)
    prepared.environment ++ Map("CQ_TEST_REAL_GIT" -> executable.text.trim, "CQ_TEST_ASSETS" -> prepared.assets.toString,
      "PATH" -> (bin.toString + ":" + prepared.environment("PATH")))
  }

  "Merge preparation (Behavioral Active Blackbox; Git and guardian Process Communication)" should {
    "prepare a clean combination without consuming native input or changing the governing checkout" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val prepared = prepare(fixture, "clean")
      val environment = prepared.environment ++ Map("GIT_DIR" -> "/unavailable-provider-value", "GIT_CONFIG_COUNT" -> "invalid")
      val execution = spec(fixture, prepared, prepared.inputs, environment)
      accepted(prepared, execution, run(fixture, execution), "0")
      assert(parse(Files.readString(execution.stdout)).toOption.get.hcursor.get[String]("git").toOption.contains("/unavailable-provider-value"))
      assert(Files.readString(prepared.tree.resolve("left")) == "target\n" && Files.readString(prepared.tree.resolve("right")) == "candidate\n")
      assert(prepared.git(prepared.tree, "rev-parse", "MERGE_HEAD") == prepared.inputs.candidate.value)
    }}

    "expose textual and directory-rename conflicts even when the unmerged index is empty" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      List("text", "directory").foreach { conflict =>
        val prepared = prepare(fixture, conflict)
        val execution = spec(fixture, prepared, prepared.inputs, prepared.environment)
        accepted(prepared, execution, run(fixture, execution), "1")
        assert(Files.readString(prepared.assets.resolve("merge.log")).contains("CONFLICT"))
        val unmerged = prepared.git(prepared.tree, "ls-files", "--unmerged")
        assert(unmerged.isEmpty == (conflict == "directory"), unmerged)
        assert(prepared.git(prepared.tree, "rev-parse", "MERGE_HEAD") == prepared.inputs.candidate.value)
      }
    }}

    "accept an already incorporated candidate only with an unchanged HEAD and proven ancestry" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val prepared = prepare(fixture, "clean")
      val execution = spec(fixture, prepared, prepared.inputs.copy(candidate = prepared.initial), prepared.environment)
      accepted(prepared, execution, run(fixture, execution), "0")
      assert(!Files.exists(Path.of(prepared.git(prepared.tree, "rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD"))))
    }}

    "reject unavailable objects, changed identities, dirty workspaces and reused diagnostics before native launch" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      List("object", "head", "common", "dirty", "reused").foreach { failure =>
        val prepared = prepare(fixture, "clean")
        val inputs = failure match {
          case "object" => prepared.inputs.copy(candidate = GitCommit("f" * 40))
          case "head" => prepared.inputs.copy(base = prepared.initial)
          case "common" => prepared.inputs.copy(common = prepared.root)
          case _ => prepared.inputs
        }
        val execution = spec(fixture, prepared, inputs, prepared.environment)
        if (failure == "dirty") Files.writeString(prepared.tree.resolve("unexpected"), "dirty")
        if (failure == "reused") Files.writeString(prepared.assets.resolve("merge.log"), "retained")
        denied(prepared, execution, run(fixture, execution))
      }
    }}

    "reject unsuccessful preparation without merge state and bound diagnostic overflow before native launch" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      List("exit 1", "exit 0", "python3 -c 'import sys; sys.stdout.write(\"x\" * 100000)'\nexit 1").foreach { body =>
        val prepared = prepare(fixture, "clean")
        val execution = spec(fixture, prepared, prepared.inputs, injectGit(prepared, body))
        denied(prepared, execution, run(fixture, execution))
        assert(Files.size(prepared.assets.resolve("merge.log")) <= 65536)
      }
    }}

    "cancel a running merge and settle its hierarchy without starting the native harness" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val prepared = prepare(fixture, "clean")
      val environment = injectGit(prepared, "printf 'started\\n' > \"$CQ_TEST_ASSETS/started\"\nsleep 30\nexit 1")
      val execution = spec(fixture, prepared, prepared.inputs, environment)
      Using.resource(new GuardianDriver(fixture.binary).start(execution)) { running =>
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos
        while (!Files.exists(prepared.assets.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(5)
        assert(Files.exists(prepared.assets.resolve("started")), running.status)
        running.cancel()
        val observation = running.await(Duration.ofSeconds(5))
        assert(observation.phase == ProcessPhase.Settled && observation.result.exists(_.reason == StopReason.Cancelled), observation)
      }
      assert(Files.readString(execution.stdout).isEmpty && Files.readString(prepared.assets.resolve("merge-ready")).isEmpty)
      prepared.unchanged()
    }}

    "deny native handoff on diagnostic write or fsync failure and missing or malformed merge status" in { (fixture: GuardianFixture) => ZIO.attemptBlocking {
      val library = Path.of(fixture.environment("CQ_MERGE_FAULT_LIBRARY"))
      List("write", "fsync", "missing", "partial", "extra").foreach { fault =>
        val prepared = prepare(fixture, "clean")
        val capture = prepared.root.resolve("injected-capture")
        Files.writeString(capture, "#!/bin/sh\nLD_PRELOAD=\"$CQ_TEST_MERGE_LIBRARY\" exec \"$CQ_TEST_GUARDIAN\" \"$@\"\n")
        Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rwx------"))
        val environment = prepared.environment ++ Map("CQ_TEST_MERGE_LIBRARY" -> library.toString, "CQ_TEST_GUARDIAN" -> fixture.binary.toString,
          "CQ_TEST_MERGE_FAULT" -> fault, "CQ_TEST_MERGE_LOG" -> prepared.assets.resolve("merge.log").toString,
          "CQ_TEST_MERGE_STATUS" -> prepared.assets.resolve("merge-status").toString)
        val execution = spec(prepared, prepared.inputs, environment, capture)
        denied(prepared, execution, run(fixture, execution))
        assert(Files.readString(execution.stderr).contains("Injected merge diagnostic failure"), Files.readString(execution.stderr))
      }
    }}
  }
}
