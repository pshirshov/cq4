package cq.host

import cq.api.*

final case class JobOutcome(state: AttemptState, problem: Option[String]) {
  def succeeded: Boolean = state == AttemptState.Completed
  def withResult(valid: Boolean): AttemptState = if (succeeded && !valid) AttemptState.Failed else state
}

object JobOutcome {
  def observed(record: JobRecord): JobOutcome = {
    require(Set(JobPhase.Settled, JobPhase.Uncertain)(record.phase), "Job outcome requires a terminal observation")
    val exit = record.exit
    val state = if (record.phase == JobPhase.Uncertain || !exit.exists(value => value.settled && !value.hostFailure)) AttemptState.Unknown
    else exit.get.reason match {
      case StopReason.Exited if exit.get.code.contains(0) && exit.get.signal.isEmpty => AttemptState.Completed
      case StopReason.Cancelled | StopReason.OwnerExited => AttemptState.Cancelled
      case _ => AttemptState.Failed
    }
    val problem = if (state == AttemptState.Completed) None else {
      val detail = exit.map(value => s"${value.reason}, code=${value.code}, signal=${value.signal}").getOrElse("no confirmed exit")
      Some(s"Job ${record.phase}: $detail" + record.problem.fold("")(value => s"; $value"))
    }
    JobOutcome(state, problem)
  }
}
