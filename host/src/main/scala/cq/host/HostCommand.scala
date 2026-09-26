package cq.host

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.{FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

final case class CommandOutput(exit: Int, text: String)

trait HostCommand {
  def run(directory: Path, arguments: List[String]): CommandOutput
}

object GitEnvironment {
  def isolated(environment: Map[String, String]): Map[String, String] =
    environment.filterNot(_._1.startsWith("GIT_")) ++ Map(
      "GIT_CONFIG_NOSYSTEM" -> "1", "GIT_CONFIG_GLOBAL" -> "/dev/null", "GIT_TERMINAL_PROMPT" -> "0",
    )
}

final class BoundedHostCommand(environment: Map[String, String], deadline: Duration, maxBytes: Int) extends HostCommand {
  private val DrainMillis = 2000L
  private val StopMillis = 2000L
  require(!deadline.isNegative && !deadline.isZero && maxBytes > 0, "Invalid host command bounds")

  override def run(directory: Path, arguments: List[String]): CommandOutput = {
    require(arguments.nonEmpty, "Empty host command")
    val builder = new ProcessBuilder(arguments.asJava).directory(directory.toFile).redirectErrorStream(true)
    builder.environment().clear()
    builder.environment().putAll(environment.asJava)
    val process = builder.start()
    process.getOutputStream.close()
    val drain = new FutureTask[String](() => {
      val output = new ByteArrayOutputStream()
      val input = process.getInputStream
      var overflow = false
      try {
        val bytes = new Array[Byte](8192)
        var count = input.read(bytes)
        while (count != -1) {
          val remaining = maxBytes - output.size()
          output.write(bytes, 0, count.min(remaining))
          if (count > remaining) overflow = true
          count = input.read(bytes)
        }
        if (overflow) throw new IllegalStateException("Host command output limit exceeded")
        output.toString(UTF_8)
      } finally input.close()
    })
    Thread.ofVirtual().name("cq-command-output").start(drain)
    try {
      if (!process.waitFor(deadline.toMillis, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Host command deadline exceeded")
      CommandOutput(process.exitValue(), drain.get(DrainMillis, TimeUnit.MILLISECONDS))
    } finally {
      if (process.isAlive) {
        val descendants = process.descendants().toList.asScala.toList
        descendants.reverse.foreach(_.destroyForcibly())
        process.destroyForcibly()
        if (!process.waitFor(StopMillis, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Host command termination unconfirmed")
      }
    }
  }
}
