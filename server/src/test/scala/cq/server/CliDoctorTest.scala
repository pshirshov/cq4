package cq.server

import cq.api.Harness
import cq.host.{DriverAssets, WorkflowAssets}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.concurrent.TimeUnit
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{Runtime, Unsafe}

final class CliDoctorLocal extends AnyWordSpec {
  private def run(root: Path, args: List[String]): (Either[Throwable, Unit], String) = {
    val bytes = new ByteArrayOutputStream
    val context = CliContext(Map.empty, root, new PrintStream(bytes, true, UTF_8), new ByteArrayInputStream(Array.emptyByteArray))
    val workflows = new WorkflowAssets
    val cli = new Cli(context, new ProjectLocation(context), new SessionUpload(context, Clock.systemUTC()), workflows,
      new AttachedAssets(new McpSchemas, workflows))
    val result = Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(cli.run(args).either).getOrThrowFiberFailure() }
    (result, bytes.toString(UTF_8))
  }
  private def fixture(operation: Path => Unit): Unit = {
    val root = Files.createTempDirectory("cq-cli-doctor-").toAbsolutePath
    try operation(root)
    finally Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  }

  "Command doctor CLI (Behavioral Active Blackbox Good Communication filesystem)" should {
    "accept the settlement flag before JSON and report missing server credentials without writes" in fixture { root =>
      val (result, output) = run(root, List("doctor", "server", "--endpoint", "http://127.0.0.1:1", "--require-settled", "--json"))
      assert(result.left.toOption.exists(_.isInstanceOf[InstallationNeedsAttention]), result.toString)
      val report = io.circe.parser.parse(output).fold(throw _, identity)
      assert(report.hcursor.get[String]("scope").contains("server") && report.hcursor.get[Boolean]("current").contains(false))
      assert(Using.resource(Files.list(root))(_.count()) == 0)
    }
    "report missing commands as one JSON value and fail without writes or credentials" in fixture { root =>
      val (result, output) = run(root, List("doctor", "commands", "codex", "--json"))
      assert(result.left.toOption.exists(_.isInstanceOf[CommandAssetsNeedAttention]), result.toString)
      val json = io.circe.parser.parse(output).fold(throw _, identity)
      assert(json.hcursor.get[Boolean]("current") == Right(false))
      val checks = json.hcursor.get[List[io.circe.Json]]("checks").toOption.get
      assert(checks.size == 6 && checks.forall(_.hcursor.get[String]("state") == Right("Missing")))
      assert(Using.resource(Files.list(root))(_.count()) == 0)
    }
    "verify an explicit directory and report changed contents without displaying them" in fixture { root =>
      val project = Files.createDirectory(root.resolve("project"))
      val assets = new WorkflowAssets().commands(Harness.Pi) ++ DriverAssets.commands(Harness.Pi)
      assets.foreach { asset =>
        val file = project.resolve(asset.path)
        Files.createDirectories(file.getParent); Files.writeString(file, asset.body)
      }
      val args = List("doctor", "commands", "pi", "--directory", "project")
      val (success, human) = run(root, args)
      assert(success == Right(()) && human.contains("Current") && human.contains("cq:begin.md"))
      assert(human.contains("Server, credentials, MCP, hooks and harness trust are not checked."))
      val stale = project.resolve(assets.head.path)
      Files.writeString(stale, "fixture secret must not print")
      val before = Files.getLastModifiedTime(stale)
      val (failure, changed) = run(root, args)
      assert(failure.isLeft && changed.contains("Different") && !changed.contains("fixture secret"))
      assert(Files.readString(stale) == "fixture secret must not print" && Files.getLastModifiedTime(stale) == before)
      assert(!Files.exists(root.resolve(".cq")) && !Files.exists(project.resolve(".cq")))
    }
    "report a checkout without a project file to the agents doctor as one JSON value and fail without writes or credentials" in fixture { root =>
      val (result, output) = run(root, List("doctor", "agents", "codex", "--settings", "settings.json", "--json"))
      assert(result.left.toOption.exists(_.isInstanceOf[InstallationNeedsAttention]), result.toString)
      val report = io.circe.parser.parse(output).fold(throw _, identity)
      assert(report.hcursor.get[String]("scope").contains("agents") && report.hcursor.get[Boolean]("current").contains(false))
      val checks = report.hcursor.get[List[io.circe.Json]]("checks").toOption.get
      assert(checks.map(_.hcursor.get[String]("name").toOption.get) == List("Project", "Credential") && checks.forall(_.hcursor.get[String]("state") == Right("Failed")))
      assert(Using.resource(Files.list(root))(_.count()) == 0)
      val (refused, _) = run(root, List("doctor", "agents", "codex"))
      assert(refused.left.toOption.exists(_.getMessage.endsWith("Agents doctor requires --settings FILE")), refused.toString)
      assert(run(root, List("doctor", "agents", "other", "--settings", "settings.json"))._1.isLeft)
    }
    "read the project file of the directory the agents doctor is given and say what it does not verify" in fixture { root =>
      val location = Files.createDirectories(root.resolve("project/.cq"))
      val project = java.util.UUID.randomUUID()
      val file = location.resolve("project.json")
      Files.writeString(file, s"""{"project":{"value":"$project"},"endpoint":"http://127.0.0.1:1","name":"Doctor"}""")
      val before = Files.getLastModifiedTime(file)
      val (result, human) = run(root, List("doctor", "agents", "pi", "--settings", "settings.json", "--directory", "project"))
      assert(result.left.toOption.exists(_.isInstanceOf[InstallationNeedsAttention]), result.toString)
      val lines = human.linesIterator.toList
      assert(lines.exists(line => line.startsWith("Project") && line.contains("Current") && line.contains(s"Project $project at http://127.0.0.1:1")), human)
      assert(lines.exists(line => line.startsWith("Credential") && line.contains("Failed")) && !human.contains("Configuration"), human)
      assert(lines.last == AgentsDoctor.Scope && lines.last.contains("not verified against providers") && lines.last.contains("cq doctor harness"), human)
      assert(Files.getLastModifiedTime(file) == before && Using.resource(Files.list(location))(_.count()) == 1 && !Files.exists(root.resolve(".cq")))
    }
    "print the whole detail of an agents doctor check, which says what to set" in {
      val bytes = new ByteArrayOutputStream
      val detail = "no layer assigns the planner role when codex governs; " + "set defaults.roles.planner " * 8 + "end"
      new CliOutput(new PrintStream(bytes, true, UTF_8), CliFormat.Human, List("doctor", "agents", "codex")).agents(
        InstallationReport("agents", List(InstallationCheck("Project", InstallationState.Current, "short"), InstallationCheck("Role planner", InstallationState.Failed, detail))))
      assert(bytes.toString(UTF_8).linesIterator.toList == List("Check         State    Detail", "────────────  ───────  ──────", "Project       Current  short",
        s"Role planner  Failed   $detail", AgentsDoctor.Scope))
    }
    "describe the agents doctor in doctor help" in {
      val help = CliHelp.render(List("doctor", "--help"))
      assert(help.contains("cq doctor agents HARNESS --settings FILE [--directory DIR] [--json]"))
      assert(help.contains("self-review") && help.contains("not verified against providers") && help.contains("line:column"))
      assert(CliHelp.render(Nil).contains("doctor            Verify commands, server, harness or agent models without writes"))
    }
    "describe the command-assets scope in doctor help" in {
      val help = CliHelp.render(List("doctor", "--help"))
      assert(help.contains("doctor commands HARNESS") && help.contains("--directory") && help.contains("--json"))
      assert(help.contains("read-only") && help.contains("not checked"))
    }
    "exit the actual JVM entrypoint quietly after a negative report" in fixture { root =>
      val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("CLI fixture classpath is required"))
      val source = Option(System.getProperty("cq.test.sourceRoot")).getOrElse(throw new IllegalStateException("CLI fixture source root is required"))
      val options = Files.readString(Path.of(source, ".jvmopts")).trim.split("\\s+").toList
      val command = List(Path.of(System.getProperty("java.home"), "bin", "java").toString) ++ options ++
        List("-cp", classpath, "cq.server.Main", "doctor", "commands", "codex", "--json")
      val errorFile = Files.createTempFile("cq-doctor-stderr-", ".log")
      val builder = new ProcessBuilder(command.asJava).directory(root.toFile).redirectError(errorFile.toFile)
      builder.environment().keySet().asScala.toList.filter(_.startsWith("CQ_")).foreach(builder.environment().remove)
      val process = builder.start()
      try {
        process.getOutputStream.close()
        assert(process.waitFor(60, TimeUnit.SECONDS), "CLI doctor did not terminate")
        val stdout = new String(process.getInputStream.readNBytes(65536), UTF_8)
        val stderr = Files.readString(errorFile)
        assert(process.exitValue() == 1 && stderr.isEmpty, stderr)
        val report = io.circe.parser.parse(stdout).fold(throw _, identity)
        assert(report.hcursor.get[Boolean]("current") == Right(false))
        assert(report.hcursor.get[List[io.circe.Json]]("checks").toOption.get.size == 6)
        assert(Using.resource(Files.list(root))(_.count()) == 0)
      } finally {
        if (process.isAlive) process.destroyForcibly()
        Files.deleteIfExists(errorFile)
      }
    }
  }
}
