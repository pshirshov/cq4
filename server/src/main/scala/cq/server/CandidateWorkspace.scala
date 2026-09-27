package cq.server

import cq.api.*
import cq.host.*
import java.nio.file.Path
import java.time.Duration

final class CandidateWorkspace(config: SupervisorConfig) {
  private val MaxOutputBytes = 1024 * 1024
  private val command = new BoundedHostCommand(GitEnvironment.isolated(HostEnvironment.runtime(config.environment)), Duration.ofSeconds(10), MaxOutputBytes)
  private def git(directory: Path, arguments: String*): String = {
    val result = command.run(directory, List("git", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false") ++ arguments)
    require(result.exit == 0, s"Candidate Git operation failed: ${result.text.take(300)}")
    result.text.trim
  }
  def verifyBase(base: GitCommit): Unit = {
    val repository = Path.of(config.run.repository)
    require(git(repository, "rev-parse", "--verify", "--end-of-options", base.value + "^{commit}") == base.value, "Candidate object is unavailable")
    git(repository, "merge-base", "--is-ancestor", config.run.base.value, base.value)
  }
  def capture(workspace: WorkspaceRecord): GitCommit = {
    require(workspace.admission == WorkspaceAdmission.Open && workspace.observed.nonEmpty, "Candidate workspace is quarantined or unconfirmed")
    val tree = Path.of(workspace.directory)
    val top = Path.of(git(tree, "rev-parse", "--show-toplevel")).toRealPath()
    val common = Path.of(git(tree, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath()
    require(top == tree && common.toString == workspace.observed.get.gitCommon && git(tree, "rev-parse", "HEAD") == workspace.spec.base.value,
      "Worker changed its workspace identity or committed base")
    git(tree, "add", "--all", "--", ".")
    val staged = git(tree, "ls-files", "--stage")
    require(!staged.linesIterator.exists(_.startsWith("160000 ")), "Candidate submodules are not supported")
    val objectId = git(tree, "write-tree")
    val commit = GitCommit(git(tree, "-c", "user.name=CQ host", "-c", "user.email=cq@localhost", "commit-tree", objectId,
      "-p", workspace.spec.base.value, "-m", "CQ candidate " + workspace.spec.attempt.value))
    git(tree, "update-ref", "refs/cq/candidates/" + workspace.spec.attempt.value, commit.value, "0" * commit.value.length)
    commit
  }
}
