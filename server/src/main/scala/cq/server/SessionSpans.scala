package cq.server

import cq.api.*
import cq.host.*
import zio.{Task, ZIO}

/** Records the spans of work the governing session runs itself. A span is registered Running when its work starts and finished once
  * when it ends, both retained in the span's own queue before they are sent. A start the server did not receive does not fail or delay
  * the work: it stays retained, and the finish, the session's final delivery and `cq job upload` send it. A span whose host ended
  * before its finish stays Running on the server until a later host's recovery or `cq job upload` closes it as Unknown. */
private[server] final class SessionSpans(config: SupervisorConfig, collector: ServerApi) {
  def this(config: SupervisorConfig, authority: SupervisorAuthority) = this(config, authority.collector)
  private val delivery = new SpanDelivery(config.directory.resolve("spans"), config.project.project, config.delivery)

  /** Retains the start and sends it in the background; neither a failure to retain it nor one to send it reaches the work. */
  def start(span: PhaseSpan): Task[Unit] =
    ZIO.attemptBlocking(delivery.retain(PhaseSpans.running(span))).ignore *>
      ZIO.attemptBlocking(delivery.send(span, collector)).ignore.forkDaemon.unit

  /** Retains the finish beside the start and sends the finish alone: the start, retained and sent in the background, is never retried
    * on this path, so a start the server refused or holds up cannot delay the work's result. */
  def record(span: PhaseSpan): Task[Unit] = ZIO.attemptBlocking(delivery.retain(span)) *> ZIO.attemptBlocking(delivery.sendRetained(span, collector)).ignore

  def flush(): Int = delivery.flush(collector)

  /** Runs a check job on `execution` and records it as a Check span on `assignment` whose parent is `parent` (the unit that requested
    * the run): Running from the job's registration, finished when it settles. An interrupted run is cancelled, recorded as it settles
    * and its workspace released. */
  def check(jobs: JobSupervisor, execution: GovernedIntegrationJobs, assignment: AssignmentId, parent: Option[SpanParent])(name: String, id: AttemptId, base: GitCommit, command: JobCommand): Task[JobRecord] = {
    val finish = (record: JobRecord) => this.record(PhaseSpans.check(record, assignment, name, parent))
    val interrupted = jobs.cancel(config.owner, id) *> jobs.await(config.owner, id).flatMap(finish) *> jobs.release(config.owner, id)
    (execution.execute(WorkspaceSpec(config.owner.project, config.owner.actor.session, id, config.run.repository, base), command,
      record => start(PhaseSpans.checkStart(record, assignment, name, parent)))
      .onInterrupt(interrupted.ignore) *> execution.status(id)).tap(finish)
  }
}
