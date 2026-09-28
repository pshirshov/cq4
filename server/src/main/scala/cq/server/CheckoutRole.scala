package cq.server

import cq.host.CheckoutExecutor
import distage.ModuleDef
import izumi.distage.plugins.PluginDef
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.distage.roles.model.definition.RoleModuleDef
import izumi.fundamentals.platform.cli.model.EntrypointArgs
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO}

final class CheckoutRole(executor: CheckoutExecutor) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = ZIO.attemptBlocking {
    parameters.raw.toList match {
      case List("--", input) => executor.run(Path.of(input))
      case _ => throw new IllegalArgumentException("Internal checkout role requires its immutable intent file")
    }
  }
}
object CheckoutRole extends RoleDescriptor {
  override val id = "checkout"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("Internal guardian-owned checked-out integration"), None, freeArgsAllowed = true)
}
object CheckoutPlugin extends PluginDef {
  include(new ModuleDef {
    include(new RoleModuleDef { makeRole[CheckoutRole] })
    make[CheckoutExecutor].fromEffect(ZIO.attempt(new CheckoutExecutor(sys.env)))
  })
}

private[server] object CqEntrypoint {
  def command: List[String] = {
    val executable = ProcessHandle.current().info().command().orElseThrow()
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") List(executable)
    else {
      val agents = java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList
        .filter(_.startsWith("-agentlib:native-image-agent="))
      val classpath = Option(System.getProperty("cq.test.classpath")).getOrElse(System.getProperty("java.class.path"))
      List(executable) ++ agents ++ List("-cp", classpath, "cq.server.Main")
    }
  }
}
