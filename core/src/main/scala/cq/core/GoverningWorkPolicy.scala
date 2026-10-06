package cq.core

import cq.api.*

/** Who made and who reviewed an integration's candidate: the roles of the two registered attempts. An attempt of the Governor role
  * is the governing session's own. */
final case class CandidateAuthorship(maker: Role, reviewer: Role) {
  def selfMade: Boolean = maker == Role.Governor
  def selfReviewed: Boolean = reviewer == Role.Governor
  def governing: Boolean = selfMade || selfReviewed
}

/** The rules for work a governing session does itself instead of dispatching a child. The server applies them when it admits a
  * result and when it reserves an integration; the host applies the integration rule first, to refuse with the same words. */
object GoverningWorkPolicy {
  import ProcessModePolicy.label

  /** The work a governing session may do itself. */
  def permits(work: DispatchWork): Boolean =
    work == DispatchWork.Worker(WorkerMode.Implement) || work == DispatchWork.Reviewer(ReviewerMode.Candidate)

  /** Only an interactive session works itself; `governing` is the attempt that parents the session's own attempt. */
  def interactive(governing: Attempt): Unit = if (AttemptObservation.observed(governing)) throw DomainFailure(Fault.Denied(
    "A result the governing session made or reviewed itself is admitted only for an interactive session; a batch run dispatches a Worker and an independent Reviewer"))

  def admit(mode: ProjectSetting.Mode): Unit = if (mode.value != ProcessMode.Yolo) throw DomainFailure(Fault.Denied(
    s"A result the governing session made or reviewed itself is admitted only in the ${label(ProcessMode.Yolo)} mode; the project's process mode is ${label(mode.value)}"))

  /** `checks` is the number of configured checks the integration freezes. */
  def integrate(mode: ProjectSetting.Mode, authorship: CandidateAuthorship, checks: Int): Unit = if (authorship.governing) {
    if (mode.value != ProcessMode.Yolo) throw DomainFailure(Fault.Denied(
      s"An integration of a candidate the governing session made or reviewed itself requires the ${label(ProcessMode.Yolo)} mode; the project's process mode is ${label(mode.value)}"))
    if (authorship.selfReviewed && checks == 0 && !mode.selfReviewWithoutChecks) throw DomainFailure(Fault.Denied(
      "A self-reviewed integration requires at least one configured check: configure at least one check for the project, " +
        "or have the operator allow self-review without checks in the project's process mode"))
  }

  /** What the host-observed integration evidence of a Task says about the governing session's part; empty when it had none. */
  def evidence(authorship: CandidateAuthorship): String = (authorship.selfMade, authorship.selfReviewed) match {
    case (false, false) => ""
    case (false, true) => "; candidate reviewed by the governing session (self-review, YOLO mode)"
    case (true, true) => "; candidate made and reviewed by the governing session (self-review, YOLO mode)"
    case (true, false) => "; candidate made by the governing session"
  }
}
