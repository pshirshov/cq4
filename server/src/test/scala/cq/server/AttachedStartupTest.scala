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
  private def host(configured: String): Outcome = {
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
      val builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString, "-cp", classpath, "cq.server.Main",
        "host", "claude", "--settings", at.resolve("settings.json").toString).directory(local.source.toFile).redirectError(at.resolve("stderr").toFile)
      List("CQ_TOKEN", "CQ_TOKEN_FILE", "CQ_SETTINGS").foreach(builder.environment().remove)
      val process = builder.start()
      try {
        process.getOutputStream.write((Initialize + "\n").getBytes(UTF_8))
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
    "answer initialize with the cause and remedy when the configured harness version is unverified" in {
      rejected("0.0.1", "Unverified harness version; configure a harness version this CQ package verifies, see docs/interactive.md")
    }
  }
}
