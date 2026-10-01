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
import izumi.fundamentals.platform.cli.model.EntrypointArgs
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import zio.{IO, Task, ZIO}

/** Forked attached host launched exactly like `cq host`: the production `AttachedProgram` wiring under izumi's role launcher, three settled children, MCP input on stdin and the launching test as owner. */
object AttachedOwnerFixture extends RoleAppMain.LauncherBIO[IO] {
  val Children = 3
  val RootProperty = "cq.fixture.root"
  val GuardianProperty = "cq.fixture.guardian"
  private final class Receiver extends ServerApi {
    override def usage(value: HostUsageInput): HostUsageResult = value.operation match {
      case HostUsage.Assign(assignment) => HostUsageResult.Assigned(assignment)
      case HostUsage.Start(attempt) => HostUsageResult.Started(attempt)
      case HostUsage.Finish(outcome) => HostUsageResult.Finished(outcome)
      case other => throw new IllegalStateException("Unexpected attached usage: " + other.getClass.getSimpleName)
    }
    override def call(value: Command): Result = throw new IllegalStateException("Fixture receiver does not execute commands")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Fixture receiver does not store artifacts")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Fixture receiver does not admit results")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Fixture receiver does not integrate")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Fixture receiver does not grant authority")
  }
  private def property(name: String): Path = Path.of(Option(System.getProperty(name)).getOrElse(throw new IllegalArgumentException(s"-D$name is required")))
  private def load(clock: Clock): SupervisorConfig = {
    val root = property(RootProperty)
    val guardian = property(GuardianProperty)
    val workspace = parse(Files.readString(root.resolve("workspace.json"))).flatMap(WorkspaceSpec_JsonCodec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
    val project = ProjectConfig(workspace.project, "http://localhost", "Attached shutdown")
    val limits = HostLimits(3000, 30000, 900, 100, 1000, 262144)
    val profile = HarnessSetting(Harness.Claude, guardian.toString, "unobserved-interactive-model", "unobserved-interactive-provider", HarnessUsage.version(Harness.Claude), Nil, Set.empty)
    val settings = SupervisorSettings(root.toString, guardian.toString, List(profile), limits, Nil, None, None)
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, workspace.owner, Role.Governor, Harness.Claude,
      "unobserved-interactive-provider", "unobserved-interactive-model", "CQ attached session; outer usage unavailable", clock.millis())
    SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits),
      SupervisorRun(project, assignment, attempt, profile.version, workspace.repository, workspace.base, SessionOwnership.Attached),
      root.resolve("session"), "", None, sys.env)
  }

  final class FixtureRole(config: SupervisorConfig, program: AttachedProgram, jobs: JobSupervisor) extends RoleTask[Task] {
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
  object FixtureRole extends RoleDescriptor {
    override val id = "attached-fixture"
    override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty, Some("Attached shutdown fixture"), None, freeArgsAllowed = false)
  }

  object FixturePlugin extends PluginDef {
    include((SupervisorPlugin: Module).overriddenBy(new ModuleDef {
      include(new RoleModuleDef { makeRole[FixtureRole] })
      make[Clock].fromValue(Clock.systemUTC())
      make[SupervisorConfig].from((clock: Clock) => load(clock))
      make[SupervisorAuthority].from { (clock: Clock) =>
        val expires = clock.millis() + Duration.ofHours(1).toMillis
        SupervisorAuthority(new Receiver, new Receiver, new Receiver, AccessToken("governor", expires), expires)
      }
      make[McpSchemas]
      make[WorkflowAssets]
    }))
  }

  override def pluginConfig: PluginConfig = PluginConfig.const(List(FixturePlugin))
}
