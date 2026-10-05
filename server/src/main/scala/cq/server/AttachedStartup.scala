package cq.server

import cq.core.DomainFailure
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
    SupervisorConfig.StaleIntegration -> "run cq configure for this harness with --replace and restart the harness",
  )
  private val GeneralRemedy = "check the settings file, the harness environment and the CQ server"
  private val NotStarted = "CQ host failed to start: "
  private val MaxCauses = 8

  def problem(failure: Throwable): String = {
    val cause = Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName).stripPrefix(RequirementPrefix).split("\\s+").mkString(" ").take(MaxCauseCharacters)
    s"$cause; ${Remedies.getOrElse(cause, GeneralRemedy)}, see docs/interactive.md"
  }

  /** A startup precondition: the configuration, the harness, the credential or the server is at fault. Anything else is a defect of the host. */
  def precondition(failure: Throwable): Boolean = Iterator.iterate(failure)(_.getCause).takeWhile(_ != null).take(MaxCauses).exists {
    case _: IllegalArgumentException | _: java.io.IOException | _: io.circe.Error | _: DomainFailure | _: java.util.concurrent.TimeoutException => true
    case _ => false
  }

  private def unprovisioned(failure: Throwable): Option[Throwable] = failure match {
    case provisioning: ProvisioningException => provisioning.getSuppressed.headOption
    case _ => None
  }

  /** The launcher's fiber report would repeat the provisioning trace that [[Handler]] replaces with one line; the trace of a defect is kept. */
  def reporting(arguments: RoleAppArgs)(reported: FailureHandler): FailureHandler = reported match {
    case FailureHandler.Custom(report) if arguments.roles.exists(_.role == AttachedRole.id) => FailureHandler.Custom {
      case Exit.Error(failure: ProvisioningException, _) if unprovisioned(failure).exists(precondition) => ()
      case exit => report(exit)
    }
    case other => other
  }

  /** The request the harness has already written; absent when nothing complete arrives in time. */
  private def pending(input: InputStream): Option[Json] = {
    val request = new CompletableFuture[Option[Json]]()
    Thread.ofPlatform().daemon().name("cq-attached-startup").start { () =>
      request.complete(Try {
        val frame = new ByteArrayOutputStream()
        var value = input.read()
        while (value != -1 && value != '\n' && frame.size() < MaxFrameBytes) { frame.write(value); value = input.read() }
        if (value == '\n') parser.parse(frame.toString(UTF_8)).toOption else None
      }.toOption.flatten)
    }
    Try(request.get(RequestWait.toMillis, TimeUnit.MILLISECONDS)).toOption.flatten
  }

  /** `initialize` is answered with the problem itself; any other request learns that the host it addressed never started. */
  def reply(request: Json, problem: String): Option[Json] = request.hcursor.downField("id").focus.filter(id => id.isString || id.isNumber).map { id =>
    val message = if (request.hcursor.get[String]("method").contains("initialize")) problem else NotStarted + problem
    Json.obj("jsonrpc" -> Json.fromString("2.0"), "id" -> id, "error" -> Json.obj("code" -> Json.fromInt(ErrorCode), "message" -> Json.fromString(message)))
  }

  final class Handler(input: InputStream, output: PrintStream, diagnostics: PrintStream, otherwise: AppFailureHandler, exit: Int => Unit) extends AppFailureHandler {
    private def answer(problem: String): Unit = pending(input).flatMap(reply(_, problem)).foreach { value =>
      output.write((value.noSpaces + "\n").getBytes(UTF_8))
      output.flush()
    }
    override def onError(failure: Throwable): Unit = unprovisioned(failure) match {
      case Some(cause) if precondition(cause) =>
        val line = problem(cause)
        diagnostics.println(line)
        answer(line)
        exit(ExitCode)
      // A defect keeps its trace: the launcher's handler prints it, and the harness is told which exception to look for.
      case Some(cause) =>
        answer(s"$NotStarted${cause.getClass.getName}; its trace is on the host's standard error")
        otherwise.onError(failure)
      case None => otherwise.onError(failure)
    }
  }
}
