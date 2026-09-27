package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope}
import java.nio.file.Path
import zio.{Task, ZIO}

final case class IntegrationTarget(commit: GitCommit, incorporated: Boolean, checkedOut: Boolean)
final case class IntegrationExecution(job: JobRecord, stdout: String, stderr: String) {
  def refusedBeforeCommit: Boolean = job.phase == JobPhase.Settled && job.exit.exists(value =>
    value.settled && !value.hostFailure && value.reason == StopReason.Exited && value.signal.isEmpty && value.code.exists(_ != 0)) &&
    stdout == "start: ok\n" && stderr.startsWith("fatal: prepare: ")
}

trait GitIntegration {
  def inspect(intent: IntegrationIntent): Task[IntegrationTarget]
  def execute(intent: IntegrationIntent): Task[Unit]
  def execution(intent: IntegrationIntent): Task[Option[IntegrationExecution]]
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

final class SupervisedGitIntegration(owner: Scope, repository: Path, target: String, command: HostCommand, jobs: IntegrationJobs,
  payloadRoot: Path, environment: Map[String, String], limits: ExecutionLimits) extends GitIntegration {
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
  private def launch(intent: IntegrationIntent): JobCommand = JobCommand(GitArguments ++ List("-c", "user.name=CQ host", "-c", "user.email=cq@localhost",
    "update-ref", "--no-deref", "--stdin", "-m", "CQ integration " + intent.id.value),
    GitEnvironment.isolated(HostEnvironment.runtime(environment)) ++ Map("LC_ALL" -> "C"),
    s"start\nupdate ${intent.target} ${intent.candidate.value} ${intent.expected.value}\nprepare\ncommit\n", limits)

  override def inspect(intent: IntegrationIntent): Task[IntegrationTarget] = ZIO.attemptBlocking {
    identity(intent)
    require(Path.of(required("rev-parse", "--show-toplevel")).toRealPath() == repository, "Integration repository identity changed")
    require(required("rev-parse", "--verify", "--end-of-options", intent.candidate.value + "^{commit}") == intent.candidate.value,
      "Integration candidate is not an available commit")
    require(ancestor(intent.expected, intent.candidate), "Integration candidate must descend from its expected target")
    require(git("symbolic-ref", "--quiet", target).exit == 1, "Integration target must be a direct branch")
    val current = GitCommit(required("show-ref", "--verify", "--hash", target))
    require(current.value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Integration target is not a full object ID")
    val checkedOut = required("worktree", "list", "--porcelain", "-z").split("\u0000", -1).contains("branch " + target)
    IntegrationTarget(current, current == intent.candidate || ancestor(intent.candidate, current), checkedOut)
  }

  override def execute(intent: IntegrationIntent): Task[Unit] = for {
    _ <- ZIO.attemptBlocking(identity(intent))
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
      Some(IntegrationExecution(record, if (settled) output("stdout", record.exit.get.stdoutBytes) else "",
        if (settled) output("stderr", record.exit.get.stderrBytes) else ""))
    }}.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
  }
}
