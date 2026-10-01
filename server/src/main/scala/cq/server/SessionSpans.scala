package cq.server

import cq.api.*
import cq.host.*
import zio.{Task, ZIO}

/** Records the spans of work the governing session runs itself. A span the server did not acknowledge stays retained; the session's
  * final delivery and `cq job upload` send it. */
private[server] final class SessionSpans(config: SupervisorConfig, authority: SupervisorAuthority) {
  private val delivery = new SpanDelivery(config.directory.resolve("spans"), config.project.project)

  def record(span: PhaseSpan): Task[Unit] = ZIO.attemptBlocking(delivery.retain(span)) *> ZIO.attemptBlocking(delivery.send(span, authority.collector)).ignore

  def flush(): Int = delivery.flush(authority.collector)

  /** Runs a check job on `execution` and records it as a Check span on `assignment`; an interrupted run is cancelled and recorded as it settles. */
  def check(jobs: JobSupervisor, execution: GovernedIntegrationJobs, assignment: AssignmentId)(id: AttemptId, base: GitCommit, command: JobCommand): Task[JobRecord] = {
    val span = (record: JobRecord) => this.record(PhaseSpans.check(record, assignment))
    val interrupted = jobs.cancel(config.owner, id) *> jobs.await(config.owner, id).flatMap(span)
    (execution.execute(WorkspaceSpec(config.owner.project, config.owner.actor.session, id, config.run.repository, base), command)
      .onInterrupt(interrupted.ignore) *> execution.status(id)).tap(span)
  }
}
