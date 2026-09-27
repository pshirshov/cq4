package cq.server

import cq.api.*
import cq.host.*
import java.nio.file.{Files, LinkOption, Path}
import java.time.Duration

final class CandidateWorkspace(config: SupervisorConfig) {
  private val MaxOutputBytes = 1024 * 1024
  private val command = new BoundedHostCommand(GitEnvironment.isolated(HostEnvironment.runtime(config.environment)), Duration.ofSeconds(10), MaxOutputBytes)
  private def git(directory: Path, arguments: String*): String = {
    val result = command.run(directory, List("git", "--no-replace-objects", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false") ++ arguments)
    require(result.exit == 0, s"Candidate Git operation failed: ${result.text.take(300)}")
    result.text.trim
  }
  def verifyBase(base: GitCommit): Unit = {
    val repository = Path.of(config.run.repository)
    require(git(repository, "rev-parse", "--verify", "--end-of-options", base.value + "^{commit}") == base.value, "Candidate object is unavailable")
    git(repository, "merge-base", "--is-ancestor", config.run.base.value, base.value)
  }
  def observeTarget(candidate: GitCommit): GitCommit = {
    val target = config.settings.integrationTarget.getOrElse(throw new IllegalArgumentException("No integration target configured"))
    val repository = Path.of(config.run.repository)
    val observed = GitCommit(git(repository, "show-ref", "--verify", "--hash", target))
    verifyPair(observed, candidate)
    observed
  }
  private def verifyPair(base: GitCommit, candidate: GitCommit): Unit = {
    verifyBase(candidate)
    val repository = Path.of(config.run.repository)
    require(base != candidate && git(repository, "rev-parse", "--verify", "--end-of-options", base.value + "^{commit}") == base.value,
      "Combination target is unavailable or equals the original candidate")
    require(git(repository, "merge-base", base.value, candidate.value).matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Combination has no usable common ancestry")
  }
  private def verifyPlan(plan: CombinationPlan): Unit = {
    CombinationPlans.validate(plan, config.owner, config.run.attempt.id, config.run.repository,
      config.settings.integrationTarget.getOrElse(throw new IllegalArgumentException("No integration target configured")))
    verifyPair(plan.observedTarget, plan.candidate)
  }
  def mergeInputs(plan: CombinationPlan, attempt: AttemptId): MergeInputs = {
    verifyPlan(plan)
    val common = Path.of(git(Path.of(config.run.repository), "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
    MergeInputs(config.directory.toRealPath().resolve("workspaces").resolve(attempt.value.toString).resolve("tree"), common,
      plan.observedTarget, plan.candidate)
  }
  def capture(workspace: WorkspaceRecord, combination: Option[CombinationPlan]): GitCommit = {
    require(workspace.admission == WorkspaceAdmission.Open && workspace.observed.nonEmpty, "Candidate workspace is quarantined or unconfirmed")
    val tree = Path.of(workspace.directory)
    val top = Path.of(git(tree, "rev-parse", "--show-toplevel")).toRealPath()
    val common = Path.of(git(tree, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
    val gitDirectory = Path.of(git(tree, "rev-parse", "--absolute-git-dir")).toRealPath()
    require(top == tree && common.toString == workspace.observed.get.gitCommon && gitDirectory.toString == workspace.observed.get.gitDirectory &&
      git(tree, "rev-parse", "HEAD") == workspace.spec.base.value,
      "Worker changed its workspace identity or committed base")
    val parents = combination match {
      case None => List(workspace.spec.base)
      case Some(plan) =>
        val inputs = mergeInputs(plan, workspace.spec.attempt)
        require(tree == inputs.directory && common == inputs.common && workspace.spec.repository == config.run.repository &&
          workspace.spec.base == plan.observedTarget && workspace.spec.project == plan.project && workspace.spec.owner == plan.owner.session,
          "Combined candidate workspace differs from its immutable plan")
        val mergeHead = Path.of(git(tree, "rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD"))
        if (Files.exists(mergeHead, LinkOption.NOFOLLOW_LINKS))
          require(HostFiles.text(mergeHead, 256) == plan.candidate.value + "\n", "Worker changed its merge inputs")
        else git(tree, "merge-base", "--is-ancestor", plan.candidate.value, plan.observedTarget.value)
        List(plan.observedTarget, plan.candidate)
    }
    git(tree, "add", "--all", "--", ".")
    val staged = git(tree, "ls-files", "--stage")
    require(!staged.linesIterator.exists(_.startsWith("160000 ")), "Candidate submodules are not supported")
    val objectId = git(tree, "write-tree")
    val commit = GitCommit(git(tree, (List("-c", "user.name=CQ host", "-c", "user.email=cq@localhost", "commit-tree", objectId) ++
      parents.flatMap(parent => List("-p", parent.value)) ++ List("-m", "CQ candidate " + workspace.spec.attempt.value))*))
    git(tree, "update-ref", "refs/cq/candidates/" + workspace.spec.attempt.value, commit.value, "0" * commit.value.length)
    commit
  }
}
