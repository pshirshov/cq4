package cq.host

import cq.api.GitCommit

/** Git observations of the configured integration target that host coordination consumes without running Git itself. */
trait ExecutionBase {
  /** The commit fresh work starts from: the current integration target head, or the session base without a configured target. */
  def fresh(): GitCommit

  /** The target head an integration of `candidate` expects: the current head when the candidate strictly descends from it, otherwise
    * `base` (the worker's recorded base), so the conditional update reports NotApplied only when the target advanced past the candidate. */
  def expected(base: GitCommit, candidate: GitCommit): GitCommit
}
