package cq.server

import izumi.distage.roles.launcher.{CLILoggerOptions, EarlyLoggerFactory, RouterFactory}
import izumi.distage.roles.launcher.LogConfigLoader.{DeclarativeLoggerConfig, LoggerFormat}
import izumi.logstage.api.{IzLogger, Log}
import izumi.logstage.api.config.LoggingTarget
import izumi.logstage.api.logger.{LogQueue, LogSink}
import izumi.logstage.api.rendering.{RenderingPolicy, StringRenderingPolicy}
import izumi.logstage.api.routing.ConfigurableLogRouter
import java.io.PrintStream
import logstage.circe.LogstageCirceRenderingPolicy

final case class DiagnosticOutput(stream: PrintStream)

final class DiagnosticSink(policy: RenderingPolicy, output: DiagnosticOutput) extends LogSink {
  override def flush(entry: Log.Entry): Unit = output.stream.println(policy.render(entry))
  override def sync(): Unit = output.stream.flush()
}

final class EarlyDiagnostics(options: CLILoggerOptions, output: DiagnosticOutput) extends EarlyLoggerFactory {
  override def makeEarlyLogger(): IzLogger = {
    val policy = if (options.json) new LogstageCirceRenderingPolicy() else RenderingPolicy.colorlessPolicy()
    IzLogger(options.level, new DiagnosticSink(policy, output))("phase" -> "early")
  }
}

final class DiagnosticRouter(output: DiagnosticOutput) extends RouterFactory {
  override def createRouter(config: DeclarativeLoggerConfig, buffer: LogQueue): ConfigurableLogRouter = {
    val policy = config.format match {
      case LoggerFormat.Json => new LogstageCirceRenderingPolicy()
      case LoggerFormat.Text => new StringRenderingPolicy(config.rendering, None)
    }
    ConfigurableLogRouter(config.rootLevel, List(new DiagnosticSink(policy, output)),
      config.levels.view.mapValues(level => LoggingTarget.Level(level)).toMap, buffer)
  }
}
