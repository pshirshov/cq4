package cq.server

import distage.ModuleDef
import izumi.distage.plugins.PluginDef
import izumi.distage.roles.bundled.BundledRolesModule
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.fundamentals.platform.cli.{CLIParser, CLIParserImpl}
import izumi.fundamentals.platform.cli.model.{EntrypointArgs, RoleAppArgs, RoleArgs}
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.nio.file.Path
import zio.{Task, ZIO}

final class ClientRole(cli: Cli) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = {
    val arguments = parameters.raw.toList
    cli.run(if (arguments.headOption.contains("--")) arguments.tail else arguments)
  }
}

object ClientRole extends RoleDescriptor {
  override val id = "client"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("CQ init, query, status and web commands"), Some("cq :client init --endpoint URL; cq --help lists client options"), freeArgsAllowed = true)
}

object ClientPlugin extends PluginDef {
  include(new ModuleDef {
    include(new RoleModuleDef { makeRole[ClientRole] })
    include(BundledRolesModule[Task])
    make[Cli]
    make[AttachedAssets]
    make[cq.host.WorkflowAssets]
    make[SessionUpload]
    make[ProjectLocation]
    make[CliContext].fromEffect(ZIO.attempt(CliContext(sys.env, Path.of("").toAbsolutePath.normalize(), System.out)))
  })
}

final class CqCliParser(roles: CLIParserImpl) extends CLIParser {
  override def parse(args: Array[String]): Either[CLIParser.ParserError, RoleAppArgs] = args.headOption match {
    case Some("serve") => roles.parse(args.tail ++ Array(":" + ServerRole.id))
    case Some("run") =>
      val raw = args.tail.toVector
      Right(RoleAppArgs(EntrypointArgs.empty, Vector(RoleArgs(SupervisorRole.id, EntrypointArgs(raw, Vector.empty, Vector.empty, raw)))))
    case Some("host") =>
      val raw = args.tail.toVector
      Right(RoleAppArgs(EntrypointArgs.empty, Vector(RoleArgs(AttachedRole.id, EntrypointArgs(raw, Vector.empty, Vector.empty, raw)))))
    case Some(value) if value.startsWith(":") || (value.startsWith("-") && value != "--help") => roles.parse(args)
    case _ =>
      val raw = args.toVector
      Right(RoleAppArgs(EntrypointArgs.empty, Vector(RoleArgs(ClientRole.id, EntrypointArgs(raw, Vector.empty, Vector.empty, raw)))))
  }
}
