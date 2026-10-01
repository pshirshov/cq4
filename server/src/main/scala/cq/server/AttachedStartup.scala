package cq.server

import cq.host.{HarnessProfile, HostCredential}
import io.circe.{Json, parser}
import izumi.distage.model.exceptions.runtime.ProvisioningException
import izumi.distage.roles.launcher.AppFailureHandler
import izumi.functional.bio.Exit
import izumi.functional.bio.UnsafeRun2.FailureHandler
import izumi.fundamentals.platform.cli.model.RoleAppArgs
import java.io.{ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.util.Try

/** A startup precondition of `cq host` fails while its components are provisioned, before the MCP peer exists: the owning harness then learns the cause from the reply to its pending request instead of a closed connection. */
object AttachedStartup {
  val ExitCode = 78
  /** JSON-RPC implementation-defined server error. */
  val ErrorCode = -32003
  private val MaxCauseCharacters = 300
  private val MaxFrameBytes = 2 * 1024 * 1024
  private val RequestWait = Duration.ofSeconds(5)
  private val RequirementPrefix = "requirement failed: "
  private val Remedies = Map(
    HostCredential.Required -> "start the harness with CQ_TOKEN_FILE set",
    HarnessProfile.Unverified -> "configure a harness version this CQ package verifies",
    SupervisorConfig.VersionMismatch -> "set the configured harness version to the installed one",
  )
  private val GeneralRemedy = "check the settings file, the harness environment and the CQ server"

  def problem(failure: Throwable): String = {
    val cause = Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName).stripPrefix(RequirementPrefix).split("\\s+").mkString(" ").take(MaxCauseCharacters)
    s"$cause; ${Remedies.getOrElse(cause, GeneralRemedy)}, see docs/interactive.md"
  }

  /** The launcher's fiber report would repeat the provisioning trace that [[Handler]] replaces with one line. */
  def reporting(arguments: RoleAppArgs)(reported: FailureHandler): FailureHandler = reported match {
    case FailureHandler.Custom(report) if arguments.roles.exists(_.role == AttachedRole.id) => FailureHandler.Custom {
      case Exit.Error(_: ProvisioningException, _) => ()
      case exit => report(exit)
    }
    case other => other
  }

  /** The identity of the request the harness has already written; absent when nothing complete arrives in time. */
  private def pending(input: InputStream): Option[Json] = {
    val request = new CompletableFuture[Option[Json]]()
    Thread.ofPlatform().daemon().name("cq-attached-startup").start { () =>
      request.complete(Try {
        val frame = new ByteArrayOutputStream()
        var value = input.read()
        while (value != -1 && value != '\n' && frame.size() < MaxFrameBytes) { frame.write(value); value = input.read() }
        if (value == '\n') parser.parse(frame.toString(UTF_8)).toOption.flatMap(_.hcursor.downField("id").focus).filter(id => id.isString || id.isNumber) else None
      }.toOption.flatten)
    }
    Try(request.get(RequestWait.toMillis, TimeUnit.MILLISECONDS)).toOption.flatten
  }

  final class Handler(input: InputStream, output: PrintStream, diagnostics: PrintStream, otherwise: AppFailureHandler) extends AppFailureHandler {
    override def onError(failure: Throwable): Unit = failure match {
      case provisioning: ProvisioningException if provisioning.getSuppressed.nonEmpty =>
        val line = problem(provisioning.getSuppressed.head)
        diagnostics.println(line)
        pending(input).foreach { id =>
          output.write((Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id,
            "error" -> Json.obj("code" -> Json.fromInt(ErrorCode), "message" -> Json.fromString(line))).noSpaces + "\n").getBytes(UTF_8))
          output.flush()
        }
        System.exit(ExitCode)
      case other => otherwise.onError(other)
    }
  }
}
