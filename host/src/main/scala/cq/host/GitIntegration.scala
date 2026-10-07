package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope}
import java.nio.channels.OverlappingFileLockException
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{Task, ZIO}

final case class IntegrationTarget(commit: GitCommit, incorporated: Boolean, checkoutBlocked: Boolean)
final case class IntegrationExecution(job: JobRecord, stdout: String, stderr: String, refusal: Option[String]) {
  def refusedBeforeCommit: Boolean = job.phase == JobPhase.Settled && job.exit.exists(value =>
    value.settled && !value.hostFailure && value.reason == StopReason.Exited && value.signal.isEmpty && value.code.exists(_ != 0)) &&
    refusal.nonEmpty
}

trait GitIntegration {
  def inspect(intent: IntegrationIntent): Task[IntegrationTarget]
  def execute(intent: IntegrationIntent): Task[Unit]
  def execution(intent: IntegrationIntent): Task[Option[IntegrationExecution]]
  /** Establishes that the Git job of `intent` started no effect and can start none any more, and gives the reason; empty when that
    * cannot be established: the job has not ended, its executor is running, or it left evidence of effects. */
  def withdraw(intent: IntegrationIntent): Task[Option[String]]
}

trait IntegrationJobs {
  def execute(workspace: WorkspaceSpec, command: JobCommand): Task[Unit]
  def status(id: AttemptId): Task[JobRecord]
}

final class IntegrationAdmissionClosed extends RuntimeException("Integration execution admission is closed before job registration")

final class RetainedIntegrationJobs(journal: JobRepository) extends IntegrationJobs {
  override def execute(workspace: WorkspaceSpec, command: JobCommand): Task[Unit] =
    ZIO.fail(new IllegalStateException("Retained integration recovery cannot execute Git"))
  override def status(id: AttemptId): Task[JobRecord] = ZIO.attemptBlocking {
    journal.records.find(_.workspace.attempt == id).getOrElse(throw DomainFailure(Fault.Missing("Retained integration job is missing")))
  }
}

object SupervisedGitIntegration {
  /** The refusal the host retains for a Git job it withdrew. */
  val Withdrawn = "The Git job ended without starting the update; the host withdrew it, so that it can no longer start"
  /** Above the sum of the checkout executor's own 30 s command deadlines, so it stops only a Git job those failed to bound. */
  val Execution: Duration = Duration.ofMinutes(30)
}

final class SupervisedGitIntegration(owner: Scope, repository: Path, target: String, command: HostCommand, jobs: IntegrationJobs,
  payloadRoot: Path, environment: Map[String, String], limits: ExecutionLimits, entrypoint: List[String]) extends GitIntegration {
  private val MaxProtocolBytes = 65536
  private val GitArguments = List("git", "--no-replace-objects", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false")
  private def identity(intent: IntegrationIntent): Unit = {
    require(intent.project == owner.project && intent.owner == owner.actor && owner.actor.role == Role.Governor,
      "Integration requires its governing owner")
    require(repository.isAbsolute && repository.toRealPath() == repository && intent.repository == repository.toString && intent.target == target,
      "Integration differs from its configured repository/target")
    require(target.startsWith("refs/heads/") && target.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") && !target.contains("..") &&
      target.split("/", -1).forall(part => part.nonEmpty && !part.startsWith(".") && !part.endsWith(".") && !part.endsWith(".lock")), "Invalid integration target")
    require(List(intent.expected, intent.candidate).forall(_.value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) && intent.expected != intent.candidate,
      "Distinct full integration object IDs required")
  }
  private def git(arguments: String*): CommandOutput = command.run(repository, GitArguments ++ arguments)
  private def required(arguments: String*): String = {
    val result = git(arguments*)
    require(result.exit == 0, "Git inspection failed: " + result.text.take(300))
    result.text.trim
  }
  private def ancestor(earlier: GitCommit, later: GitCommit): Boolean = git("merge-base", "--is-ancestor", earlier.value, later.value).exit match {
    case 0 => true
    case 1 => false
    case _ => throw new IllegalStateException("Git ancestry inspection failed")
  }
  private def workspace(intent: IntegrationIntent): WorkspaceSpec =
    WorkspaceSpec(owner.project, owner.actor.session, AttemptId(intent.id.value), intent.repository, intent.candidate)
  private def checkoutDirectory(intent: IntegrationIntent): Path = payloadRoot.getParent.resolve("checkouts").resolve(intent.id.value.toString)
  private def checkoutInput(intent: IntegrationIntent): Path = checkoutDirectory(intent).resolve("intent.json")
  private def checkedOutPaths: List[Path] = required("worktree", "list", "--porcelain", "-z").split("\u0000\u0000", -1)
    .map(_.split("\u0000", -1).toList).filter(_.contains("branch " + target)).map { fields =>
      Path.of(fields.find(_.startsWith("worktree ")).getOrElse(throw new IllegalStateException("Worktree path missing")).stripPrefix("worktree ")).toRealPath()
    }.toList
  private def launch(intent: IntegrationIntent): JobCommand = {
    require(HostFiles.read(checkoutInput(intent), CheckoutPlan_JsonCodec, CheckoutRecords.MaxBytes).intent == intent, "Checkout intent differs from integration")
    JobCommand(entrypoint ++ List(":checkout", "--", checkoutInput(intent).toString),
      GitEnvironment.isolated(HostEnvironment.runtime(environment)) ++ Map("LC_ALL" -> "C"), "", limits.copy(execution = Some(SupervisedGitIntegration.Execution)))
  }

  override def inspect(intent: IntegrationIntent): Task[IntegrationTarget] = ZIO.attemptBlocking {
    identity(intent)
    require(Path.of(required("rev-parse", "--show-toplevel")).toRealPath() == repository, "Integration repository identity changed")
    require(required("rev-parse", "--verify", "--end-of-options", intent.candidate.value + "^{commit}") == intent.candidate.value,
      "Integration candidate is not an available commit")
    require(ancestor(intent.expected, intent.candidate), "Integration candidate must descend from its expected target")
    require(git("symbolic-ref", "--quiet", target).exit == 1, "Integration target must be a direct branch")
    val current = GitCommit(required("show-ref", "--verify", "--hash", target))
    require(current.value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Integration target is not a full object ID")
    val paths = checkedOutPaths
    val incorporated = current == intent.candidate || ancestor(intent.candidate, current)
    (current, incorporated, paths)
  }.flatMap { case (current, incorporated, paths) =>
    val needsProof = Files.exists(checkoutInput(intent)) || paths == List(repository)
    val complete = if (!incorporated || !needsProof) ZIO.succeed(incorporated) else jobs.status(AttemptId(intent.id.value)).flatMap { record => ZIO.attemptBlocking {
      require(record.workspace == workspace(intent) && record.fingerprint == launch(intent).fingerprint, "Git job differs from the frozen integration effect")
      val published = checkoutDirectory(intent).resolve("completed.json")
      if (!Files.exists(published) || record.phase != JobPhase.Settled || !record.exit.exists(_.settled)) false
      else {
        val receipt = HostFiles.read(published, CheckoutReceipt_JsonCodec, CheckoutRecords.MaxBytes)
        require(receipt.intent == intent && receipt.indexSha256.matches("[0-9a-f]{64}"), "Checkout publication proof differs from integration")
        val directory = Path.of(receipt.gitDirectory)
        val targetLock = Path.of(required("rev-parse", "--path-format=absolute", "--git-path", target + ".lock"))
        !List(directory.resolve("index.lock"), directory.resolve("HEAD.lock"), targetLock).exists(Files.exists(_))
      }
    }}.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(false) }
    complete.map(value => IntegrationTarget(current, value, paths.nonEmpty && paths != List(repository)))
  }

  override def execute(intent: IntegrationIntent): Task[Unit] = for {
    _ <- ZIO.attemptBlocking {
      identity(intent)
      HostFiles.directory(checkoutDirectory(intent))
      HostFiles.immutable(checkoutInput(intent), HostFiles.encode(CheckoutPlan_JsonCodec, CheckoutPlan(intent, checkedOutPaths == List(repository))), CheckoutRecords.MaxBytes)
    }
    _ <- jobs.execute(workspace(intent), launch(intent))
  } yield ()

  override def execution(intent: IntegrationIntent): Task[Option[IntegrationExecution]] = {
    val id = AttemptId(intent.id.value)
    jobs.status(id).flatMap { record => ZIO.attemptBlocking {
      require(record.workspace == workspace(intent) && record.fingerprint == launch(intent).fingerprint, "Git job differs from the frozen integration effect")
      val settled = record.phase == JobPhase.Settled && record.exit.exists(value => value.settled && !value.hostFailure)
      def output(name: String, bytes: Long): String = {
        val text = HostFiles.text(payloadRoot.resolve(id.value.toString).resolve(name), MaxProtocolBytes)
        require(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length == bytes, "Git protocol output differs from its settled byte count")
        text
      }
      val refusalFile = checkoutDirectory(intent).resolve("refused.json")
      val refusal = if (settled && Files.exists(refusalFile)) {
        val value = HostFiles.read(refusalFile, CheckoutRefusal_JsonCodec, CheckoutRecords.MaxBytes)
        require(value.intent == intent && !Files.exists(checkoutDirectory(intent).resolve("started.json")), "Checkout refusal has possible effects")
        Some(value.reason)
      } else None
      Some(IntegrationExecution(record, if (settled) output("stdout", record.exit.get.stdoutBytes) else "",
        if (settled) output("stderr", record.exit.get.stderrBytes) else "", refusal))
    }}.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
  }

  // The executor holds the lock of its checkout directory while it runs, so under that lock no executor is running; and it writes
  // `started.json` before its first effect, so a directory without one holds no effect. A refusal retained there under the lock is
  // found by every executor that starts later, which then does nothing. A job that has not ended is left to its supervisor.
  override def withdraw(intent: IntegrationIntent): Task[Option[String]] = jobs.status(AttemptId(intent.id.value)).flatMap { record => ZIO.attemptBlocking {
    require(record.workspace == workspace(intent) && record.fingerprint == launch(intent).fingerprint, "Git job differs from the frozen integration effect")
    val directory = checkoutDirectory(intent)
    if (!JobRecords.terminal(record.phase)) None
    else Using.resource(CheckoutRecords.lock(directory)) { channel =>
      (try Option(channel.tryLock()) catch { case _: OverlappingFileLockException => None }).flatMap { held =>
        try {
          val retained = Using.resource(Files.list(directory))(_.iterator().asScala.map(_.getFileName.toString).toSet) -- Set(checkoutInput(intent).getFileName.toString, CheckoutRecords.Lock)
          val refusal = directory.resolve("refused.json")
          // An executor that was stopped between taking the index lock and its first effect left that lock behind: it is not removed here.
          val indexLock = Path.of(required("rev-parse", "--path-format=absolute", "--git-path", "index.lock"))
          val locked = Files.exists(indexLock) && HostFiles.text(indexLock, CheckoutRecords.MaxBytes) == CheckoutRecords.lockText(intent, directory)
          if (locked || !retained.subsetOf(Set(refusal.getFileName.toString))) None
          else if (retained.isEmpty) {
            HostFiles.immutable(refusal, HostFiles.encode(CheckoutRefusal_JsonCodec, CheckoutRefusal(intent, SupervisedGitIntegration.Withdrawn)), CheckoutRecords.MaxBytes)
            Some(SupervisedGitIntegration.Withdrawn)
          } else {
            val value = HostFiles.read(refusal, CheckoutRefusal_JsonCodec, CheckoutRecords.MaxBytes)
            require(value.intent == intent, "Checkout refusal differs from integration")
            Some(value.reason)
          }
        } finally held.release()
      }
    }
  }}.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
}
