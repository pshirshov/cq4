package cq.server

import cq.api.*
import cq.host.{BoundedHostCommand, GitEnvironment, HarnessUsage, HostFiles}
import io.circe.{Json, parser}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class AttachedStartupProcess extends AnyWordSpec {
  private val StartupExit = 78
  private val StartupCode = -32003
  private val RequestId = 7
  private val Initialize = s"""{"jsonrpc":"2.0","id":$RequestId,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}"""
  private final case class Outcome(exit: Int, replies: List[String], diagnostics: List[String])

  private def executable(path: Path, script: String): Path = {
    Files.writeString(path, script)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
  }

  /** The production `cq host claude` in an initialized checkout without operator credentials; its harness sends `initialize` and keeps the input open. */
  private val Approved = List("--executable", "/opt/cq/bin/cq")
  private def host(configured: String): Outcome = host(configured, Initialize, Approved)
  private def host(configured: String, first: String): Outcome = host(configured, first, Approved)
  private def host(configured: String, first: String, options: List[String]): Outcome = {
    val local = new LocalWorkspaceFixture(Files.createTempDirectory(Files.createDirectories(Path.of(".work").toAbsolutePath.normalize()), "attached-startup-"),
      new BoundedHostCommand(GitEnvironment.isolated(sys.env), Duration.ofSeconds(10), 65536))
    try {
      val at = local.directory
      val harness = executable(at.resolve("harness"), s"#!/bin/sh\necho ${HarnessUsage.version(Harness.Claude)}\n")
      val guardian = executable(at.resolve("guardian"), "#!/bin/sh\n")
      val settings = SupervisorSettings(at.resolve("state").toString, guardian.toString,
        List(HarnessSetting(Harness.Claude, harness.toString, "fixture-model", "anthropic", configured, Nil, Set.empty)), ShutdownFixture.Limits, Nil, None, None)
      Files.writeString(at.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, settings))
      val project = Files.createDirectory(local.source.resolve(".git").resolve("cq")).resolve("project.json")
      Files.writeString(project, HostFiles.encode(ProjectConfig_JsonCodec, ProjectConfig(ProjectId(UUID.randomUUID()), "http://localhost", "Startup fixture")))
      val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("Fork fixture classpath is required"))
      val builder = new ProcessBuilder((List(Path.of(System.getProperty("java.home"), "bin", "java").toString, "-cp", classpath, "cq.server.Main",
        "host", "claude", "--settings", at.resolve("settings.json").toString) ++ options)*).directory(local.source.toFile).redirectError(at.resolve("stderr").toFile)
      List("CQ_TOKEN", "CQ_TOKEN_FILE", "CQ_SETTINGS").foreach(builder.environment().remove)
      val process = builder.start()
      try {
        process.getOutputStream.write((first + "\n").getBytes(UTF_8))
        process.getOutputStream.flush()
        assert(process.waitFor(60, TimeUnit.SECONDS), "Attached host did not exit after its startup precondition failed")
        // The pinned JDK reports Scala's legacy lazy values before any CQ code runs (D25).
        Outcome(process.exitValue(), new String(process.getInputStream.readAllBytes(), UTF_8).linesIterator.toList,
          Files.readAllLines(at.resolve("stderr")).asScala.toList.filterNot(_.startsWith("WARNING: ")))
      } finally if (process.isAlive) process.destroyForcibly()
    } finally local.close()
  }

  private def rejected(configured: String, problem: String): Unit = {
    val outcome = host(configured)
    assert(outcome.diagnostics == List(problem), outcome.toString)
    assert(outcome.replies.map(parser.parse(_).fold(throw _, identity)) == List(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(RequestId),
      "error" -> Json.obj("code" -> Json.fromInt(StartupCode), "message" -> Json.fromString(problem)))), outcome.toString)
    assert(outcome.exit == StartupExit, outcome.toString)
  }

  "Attached host startup (Behavioral Active Blackbox; JVM/Git/process Communication)" should {
    "answer initialize with the cause and remedy when the operator credential is missing" in {
      rejected(HarnessUsage.version(Harness.Claude), "CQ_TOKEN or CQ_TOKEN_FILE is required; start the harness with CQ_TOKEN_FILE set, see docs/interactive.md")
    }
    "refuse to start for an integration an earlier package generated, which names no executable for the session's wait command" in {
      val problem = "This harness integration starts the CQ host without --executable, as an earlier CQ package generated it; " +
        "run cq configure for this harness with --replace and restart the harness, see docs/interactive.md"
      val outcome = host(HarnessUsage.version(Harness.Claude), Initialize, Nil)
      assert(outcome.diagnostics == List(problem) && outcome.exit == StartupExit, outcome.toString)
      assert(outcome.replies.map(parser.parse(_).fold(throw _, identity)) == List(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(RequestId),
        "error" -> Json.obj("code" -> Json.fromInt(StartupCode), "message" -> Json.fromString(problem)))), outcome.toString)
    }
    "answer initialize with the cause and remedy when the configured harness version is unverified" in {
      rejected("0.0.1", "Unverified harness version; configure a harness version this CQ package verifies, see docs/interactive.md")
    }
    "tell a first request other than initialize only that the host failed to start" in {
      val outcome = host(HarnessUsage.version(Harness.Claude), s"""{"jsonrpc":"2.0","id":$RequestId,"method":"tools/list"}""")
      val problem = "CQ_TOKEN or CQ_TOKEN_FILE is required; start the harness with CQ_TOKEN_FILE set, see docs/interactive.md"
      assert(outcome.diagnostics == List(problem) && outcome.exit == StartupExit, outcome.toString)
      assert(outcome.replies.map(parser.parse(_).fold(throw _, identity)) == List(Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> Json.fromInt(RequestId),
        "error" -> Json.obj("code" -> Json.fromInt(StartupCode), "message" -> Json.fromString("CQ host failed to start: " + problem)))), outcome.toString)
    }
    "keep the trace of a failure that is not a startup precondition and answer the pending request with its exception class" in {
      final class Recorded(val exits: scala.collection.mutable.ListBuffer[Int], val delegated: scala.collection.mutable.ListBuffer[Throwable],
        val output: java.io.ByteArrayOutputStream, val diagnostics: java.io.ByteArrayOutputStream)
      def handled(cause: Throwable, first: String): Recorded = {
        val recorded = new Recorded(scala.collection.mutable.ListBuffer.empty, scala.collection.mutable.ListBuffer.empty, new java.io.ByteArrayOutputStream, new java.io.ByteArrayOutputStream)
        val failure = new izumi.distage.model.exceptions.runtime.ProvisioningException("Provisioner stopped after 1 instances", false)
        failure.addSuppressed(cause)
        new AttachedStartup.Handler(new java.io.ByteArrayInputStream((first + "\n").getBytes(UTF_8)), new java.io.PrintStream(recorded.output, true, UTF_8),
          new java.io.PrintStream(recorded.diagnostics, true, UTF_8), new izumi.distage.roles.launcher.AppFailureHandler {
            override def onError(error: Throwable): Unit = { recorded.delegated += error; () }
          }, code => { recorded.exits += code; () }).onError(failure)
        recorded
      }
      def error(recorded: Recorded): String = parser.parse(recorded.output.toString(UTF_8)).fold(throw _, identity).hcursor.downField("error").get[String]("message").fold(throw _, identity)
      val defect = handled(new NullPointerException("Cannot invoke \"cq.server.SupervisorConfig.project()\" because \"config\" is null"), Initialize)
      assert(defect.exits.isEmpty && defect.diagnostics.size == 0, "a defect is not reduced to one line")
      assert(defect.delegated.toList.map(_.getSuppressed.toList.map(_.getClass.getName)) == List(List("java.lang.NullPointerException")), "the launcher's handler prints its trace")
      assert(error(defect) == "CQ host failed to start: java.lang.NullPointerException; its trace is on the host's standard error")
      // The known preconditions: configuration, harness, credential and server.
      List[Throwable](new IllegalArgumentException("requirement failed: CQ_TOKEN or CQ_TOKEN_FILE is required"), new java.nio.file.NoSuchFileException("/absent/settings.json"),
        cq.core.DomainFailure(Fault.Denied("Invalid credential")),
        new java.util.concurrent.ExecutionException(new java.net.ConnectException("Connection refused")),
        new IllegalStateException("HTTP response deadline exceeded", new java.util.concurrent.TimeoutException())).foreach { cause =>
        val precondition = handled(cause, Initialize)
        assert(precondition.exits.toList == List(StartupExit) && precondition.delegated.isEmpty && precondition.diagnostics.toString(UTF_8).linesIterator.size == 1, cause.toString)
        assert(error(precondition) == precondition.diagnostics.toString(UTF_8).stripLineEnd, cause.toString)
      }
      assert(List[Throwable](new IllegalStateException("Attributed driver disappeared inside its transaction"), new MatchError("Unknown"), new ClassCastException("x"))
        .forall(cause => handled(cause, Initialize).exits.isEmpty))
    }
  }
}
