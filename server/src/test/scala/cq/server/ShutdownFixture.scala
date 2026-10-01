package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.host.*
import distage.{Module, ModuleDef}
import io.circe.parser.parse
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.roles.RoleAppMain
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.fundamentals.platform.cli.model.{EntrypointArgs, RoleAppArgs}
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.{Clock, Duration}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import zio.{IO, Task, ZIO}

/** Forked governing hosts launched exactly like `cq host` / `cq run`: the production program wiring under izumi's role launcher, with the launching test as process owner. */
object ShutdownFixture extends RoleAppMain.LauncherBIO[IO] {
  val Children = 3
  val RootProperty = "cq.fixture.root"
  val GuardianProperty = "cq.fixture.guardian"
  val Limits = HostLimits(3000, 30000, 900, 100, 1000, 262144)
  /** The batch governor: a harness stand-in that marks its workspace and then runs until stopped. */
  val GovernorScript = "#!/usr/bin/env python3\nimport time\nfrom pathlib import Path\nPath('running').write_text('governor')\ntime.sleep(60)\n"

  private final class Receiver extends ServerApi {
    override def usage(value: HostUsageInput): HostUsageResult = value.operation match {
      case HostUsage.Assign(assignment) => HostUsageResult.Assigned(assignment)
      case HostUsage.Start(attempt) => HostUsageResult.Started(attempt)
      case HostUsage.Finish(outcome) => HostUsageResult.Finished(outcome)
      case other => throw new IllegalStateException("Unexpected fixture usage: " + other.getClass.getSimpleName)
    }
    override def artifact(value: ArtifactUpload): ArtifactMetadata =
      ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture", value.body.getBytes(UTF_8).length,
        value.body.codePointCount(0, value.body.length), Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
    override def call(value: Command): Result = throw new IllegalStateException("Fixture receiver does not execute commands")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Fixture receiver does not admit results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Fixture receiver does not integrate")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Fixture receiver does not grant authority")
  }
  private def property(name: String): Path = Path.of(Option(System.getProperty(name)).getOrElse(throw new IllegalArgumentException(s"-D$name is required")))

  private def load(role: String, clock: Clock): SupervisorConfig = {
    val root = property(RootProperty)
    val guardian = property(GuardianProperty)
    val attached = role == AttachedRole.id
    val workspace = parse(Files.readString(root.resolve("workspace.json"))).flatMap(WorkspaceSpec_JsonCodec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
    val project = ProjectConfig(workspace.project, "http://localhost", "Shutdown fixture")
    val executable = root.resolve("governor")
    Files.writeString(executable, GovernorScript)
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    val harness = if (attached) Harness.Claude else Harness.Codex
    val profile = HarnessSetting(harness, executable.toString, "fixture-model", if (attached) "anthropic" else "fixture-provider", HarnessUsage.version(harness), Nil, Set.empty)
    val settings = SupervisorSettings(root.toString, guardian.toString, List(profile), Limits, Nil, None, None)
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, workspace.owner, Role.Governor, harness,
      if (attached) "unobserved-interactive-provider" else profile.provider, if (attached) "unobserved-interactive-model" else profile.model, "fixture", clock.millis())
    Files.writeString(root.resolve("governor-attempt"), attempt.id.value.toString)
    SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(Limits),
      SupervisorRun(project, assignment, attempt, profile.version, workspace.repository, workspace.base, if (attached) SessionOwnership.Attached else SessionOwnership.Managed),
      root.resolve("session"), if (attached) "" else "Fixture governing input", None, sys.env)
  }

  /** `cq host` with three settled children, then the attached program on stdin/stdout. */
  final class AttachedFixtureRole(config: SupervisorConfig, program: AttachedProgram, jobs: JobSupervisor) extends RoleTask[Task] {
    override def start(parameters: EntrypointArgs): Task[Unit] = {
      val command = JobCommand(List("python3", "-c", "pass"), sys.env, "input", config.limits)
      for {
        attempts <- ZIO.foreach(List.fill(Children)(AttemptId(UUID.randomUUID()))) { id =>
          val spec = WorkspaceSpec(config.project.project, config.owner.actor.session, id, config.run.repository, config.run.base)
          jobs.start(config.owner, spec, command) *> jobs.await(config.owner, id).map { record =>
            require(record.phase == JobPhase.Settled && record.exit.exists(_.code.contains(0)), "Fixture child did not settle cleanly: " + record)
            id
          }
        }
        _ <- ZIO.attemptBlocking(Files.writeString(property(RootProperty).resolve("attempts"), attempts.map(_.value.toString).mkString("", "\n", "\n")))
        _ <- program.run
      } yield ()
    }
  }
  object AttachedFixtureRole extends RoleDescriptor {
    override val id = "attached-fixture"
    override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty, Some("Attached shutdown fixture"), None, freeArgsAllowed = false)
  }

  /** `cq run`: the batch program with the stand-in governor. */
  final class BatchFixtureRole(program: SupervisorProgram) extends RoleTask[Task] {
    override def start(parameters: EntrypointArgs): Task[Unit] = program.run
  }
  object BatchFixtureRole extends RoleDescriptor {
    override val id = "batch-fixture"
    override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty, Some("Batch shutdown fixture"), None, freeArgsAllowed = false)
  }

  object FixturePlugin extends PluginDef {
    include((SupervisorPlugin: Module).overriddenBy(new ModuleDef {
      include(new RoleModuleDef { makeRole[AttachedFixtureRole]; makeRole[BatchFixtureRole] })
      make[Clock].fromValue(Clock.systemUTC())
      make[SupervisorConfig].from { (arguments: RoleAppArgs, clock: Clock) =>
        load(if (arguments.roles.exists(_.role == BatchFixtureRole.id)) SupervisorRole.id else AttachedRole.id, clock)
      }
      make[SupervisorAuthority].from { (clock: Clock) =>
        val expires = clock.millis() + Duration.ofHours(1).toMillis
        SupervisorAuthority(new Receiver, new Receiver, new Receiver, AccessToken("governor", expires), expires)
      }
      make[CliContext].from((config: SupervisorConfig) => CliContext(sys.env, config.directory, System.out))
      make[McpSchemas]
      make[WorkflowAssets]
    }))
  }

  override def pluginConfig: PluginConfig = PluginConfig.const(List(FixturePlugin))

  /** Launches the fixture JVM for `role` with stdout/stderr in `at/owner.log`; `environment` extends the inherited one. */
  def launch(at: Path, role: RoleDescriptor, guardian: Path, environment: Map[String, String]): Process = {
    val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java")
    val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(throw new IllegalStateException("Fork fixture classpath is required"))
    val builder = new ProcessBuilder(javaBinary.toString, "-cp", classpath, s"-D$RootProperty=$at", s"-D$GuardianProperty=$guardian",
      "cq.server.ShutdownFixture", ":" + role.id).redirectErrorStream(true).redirectOutput(at.resolve("owner.log").toFile)
    builder.environment().putAll(environment.asJava)
    builder.start()
  }

  def awaitUntil(process: Process, at: Path, limit: Duration)(ready: => Boolean): Unit = {
    val deadline = System.nanoTime() + limit.toNanos
    while (!ready && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
    assert(process.isAlive && ready, Files.readString(at.resolve("owner.log")))
  }
}
