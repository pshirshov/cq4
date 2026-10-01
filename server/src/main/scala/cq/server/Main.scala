package cq.server

import com.comcast.ip4s.{Host, Port}
import cq.core.{ArtifactRepository, ArtifactService, ClaimPlanner, LedgerRepository, LedgerService, LedgerMutation, IntegrationService, ProbeRepository, ProbeService, ProposalService, QueryCompleter, QueryParser, ResultAdmissionService, TerminationPlanner, UsageRepository, UsageService, WorksetTraversal}
import distage.{Activation, Lifecycle, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.{PluginConfig, PluginDef}
import izumi.distage.roles.RoleAppMain
import izumi.distage.roles.launcher.{AppFailureHandler, EarlyLoggerFactory, RouterFactory}
import izumi.distage.roles.model.{RoleDescriptor, RoleService}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.fundamentals.platform.cli.{CLIParser, CLIParserImpl}
import izumi.fundamentals.platform.cli.model.EntrypointArgs
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import zio.{IO, Task, ZIO}
import zio.interop.catz.*
import java.time.Clock

final case class ListenConfig(host: Host, port: Port)

final case class RunningServer(server: Server)

object RunningServer {
  final class Resource(transport: Transport, listen: ListenConfig)
      extends Lifecycle.Of[Task, RunningServer](
          Lifecycle.fromCats(
            EmberServerBuilder.default[Task]
              .withHost(listen.host).withPort(listen.port)
              .withHttpWebSocketApp(ws => transport.routes(ws).orNotFound)
              .build.map(RunningServer.apply)
          )
      )
}

final class ServerRole(running: RunningServer) extends RoleService[Task] {
  override def start(parameters: EntrypointArgs): Lifecycle[Task, Unit] =
    Lifecycle.liftF(ZIO.logInfo(s"CQ listening on ${running.server.address}"))
}

object ServerRole extends RoleDescriptor {
  override final val id = "server"
}

object CqPlugin extends PluginDef {
  include(new ModuleDef {
  include(new RoleModuleDef { makeRole[ServerRole] })
  make[ProbeService[IO]].from[ProbeService.Impl[IO]]
  make[LedgerService[IO]].from[LedgerService.Impl[IO]]
  make[LedgerMutation]
  make[QueryParser]
  make[QueryCompleter]
  make[WorksetTraversal]
    make[TerminationPlanner]
    make[ClaimPlanner]
  make[UsageService[IO]].from[UsageService.Impl[IO]]
  make[ArtifactService[IO]].from[ArtifactService.Impl[IO]]
  make[ResultAdmissionService[IO]].from[ResultAdmissionServiceImpl[IO]]
  make[IntegrationService[IO]].from[IntegrationServiceImpl[IO]]
  make[ProposalService[IO]].from[ProposalServiceImpl[IO]]
  make[Clock].fromValue(Clock.systemUTC())
  make[LedgerDatabase]
  make[ProjectArchives].from[PostgresProjectArchives]
  make[ArchiveTransport]
  make[Transport]
  make[Authorization]
  make[Application]
  make[LiveSession]
  make[StaticAssets]
  make[McpSchemas]
  make[RunningServer].fromResource[RunningServer.Resource]
  make[DatabaseSetup]
  make[DatabaseConfig].fromEffect(ZIO.attempt {
    DatabaseConfig(required("CQ_DATABASE_URL"), required("CQ_DATABASE_USER"), required("CQ_DATABASE_PASSWORD"))
  })
  make[AccessConfig].fromEffect(ZIO.attempt {
    val token = required("CQ_TOKEN")
    require(token.length >= 32, "CQ_TOKEN must contain at least 32 characters")
    AccessConfig(token, required("CQ_ORIGIN"))
  })
  make[ListenConfig].fromEffect(ZIO.attempt {
    ListenConfig(Host.fromString(required("CQ_HOST")).getOrElse(throw new IllegalArgumentException("Invalid CQ_HOST")),
      Port.fromString(required("CQ_PORT")).getOrElse(throw new IllegalArgumentException("Invalid CQ_PORT")))
  })
  include(new ModuleDef {
    tag(Repo.Prod)
    make[PostgresProbeRepository]
    make[ProbeRepository[IO]].fromResource[PostgresProbeResource]
    make[PostgresLedgerRepository]
    make[LedgerRepository[IO]].fromResource[PostgresLedgerResource]
    make[PostgresUsageRepository]
    make[UsageRepository[IO]].fromResource[PostgresUsageResource]
    make[PostgresArtifactRepository]
    make[ArtifactRepository[IO]].fromResource[PostgresArtifactResource]
  })
  include(new ModuleDef {
    tag(Repo.Dummy)
    make[ProbeRepository[IO]].fromResource[DummyProbeRepository]
    make[LedgerRepository[IO]].fromResource[DummyLedgerResource]
    make[UsageRepository[IO]].fromResource[DummyUsageResource]
    make[ArtifactRepository[IO]].fromResource[DummyArtifactResource]
  })
  })

  private def required(name: String): String = sys.env.getOrElse(name, throw new IllegalArgumentException(s"Missing $name"))
}

object Main extends RoleAppMain.LauncherBIO[IO] {
  override def pluginConfig: PluginConfig = PluginConfig.const(List(CqPlugin, ClientPlugin, SupervisorPlugin, CheckoutPlugin))
  override protected def roleAppBootOverrides(argv: RoleAppMain.ArgV): distage.Module =
    super.roleAppBootOverrides(argv) ++ new ModuleDef {
      make[Activation].named("default").fromValue(Activation(Repo -> Repo.Prod))
      make[CLIParserImpl]
      make[CLIParser].from[CqCliParser]
      make[DiagnosticOutput].fromValue(DiagnosticOutput(System.err))
      make[izumi.logstage.api.Log.Level].named("early").fromValue(izumi.logstage.api.Log.Level.Warn)
      make[EarlyLoggerFactory].from[EarlyDiagnostics]
      make[RouterFactory].from[DiagnosticRouter]
    }
  override protected def earlyFailureHandler(argv: RoleAppMain.ArgV): AppFailureHandler =
    if (argv.args.headOption.contains(AttachedRole.id)) new AttachedStartup.Handler(System.in, System.out, System.err, super.earlyFailureHandler(argv))
    else super.earlyFailureHandler(argv)
}
