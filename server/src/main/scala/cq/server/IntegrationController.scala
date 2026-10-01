package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope}
import cq.host.*
import distage.Lifecycle
import java.nio.file.Path
import java.time.{Clock, Duration}
import zio.{Promise, Semaphore, Task, ZIO}

private[server] final class IntegrationExecutionState(val ticket: IntegrationTicket, val ready: Promise[Throwable, Unit],
  var done: Promise[Nothing, Unit], var view: IntegrationStatus)

private[server] final class GovernedIntegrationJobs(owner: Scope, jobs: JobSupervisor, admission: Semaphore) extends IntegrationJobs {
  private var closed = false
  private var admitted = Set.empty[AttemptId]
  override def execute(workspace: WorkspaceSpec, command: JobCommand): Task[Unit] =
    admission.withPermit(ZIO.attempt {
      if (closed) throw new IntegrationAdmissionClosed
      admitted += workspace.attempt
    // The Git job leaves its evidence under checkouts/ and payload/; its tree is never read. A failed removal is retried by the next host startup.
    } *> jobs.start(owner, workspace, command)).unit *> jobs.await(owner, workspace.attempt).unit *> jobs.release(owner, workspace.attempt).ignore
  override def status(id: AttemptId): Task[JobRecord] = jobs.status(owner, id)
  def shutdown: Task[Unit] = for {
    ids <- admission.withPermit(ZIO.succeed { closed = true; admitted.toList })
    results <- ZIO.foreach(ids)(id => jobs.cancel(owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }.exit)
    _ <- ZIO.foreachDiscard(results)(ZIO.done(_))
  } yield ()
}

final class IntegrationController(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor, candidates: CandidateWorkspace, clock: Clock,
  admission: Semaphore) {
  private val MaxWaitMillis = 20000
  private val AcknowledgementMillis = 1000L
  private val journal = new FileIntegrationJournal(config.directory.resolve("integrations"), config.owner)
  private val execution = new GovernedIntegrationJobs(config.owner, jobs, admission)
  private val coordinator = config.settings.integrationTarget.map { target =>
    new IntegrationCoordinator(config.owner, journal, new SupervisedGitIntegration(config.owner, Path.of(config.run.repository), target,
      new BoundedHostCommand(GitEnvironment.isolated(config.environment), Duration.ofSeconds(10), 65536), execution,
      config.directory.resolve("payload"), config.environment, config.limits, CqEntrypoint.command), authority.governor, authority.collector)
  }
  private var entries = Map.empty[IntegrationId, IntegrationExecutionState]
  private var closing = false
  private var disabled = false
  private def available: IntegrationCoordinator = coordinator.getOrElse(throw DomainFailure(Fault.Invalid("No integration target is configured")))
  private def found(id: IntegrationId): IntegrationExecutionState = entries.getOrElse(id,
    throw DomainFailure(Fault.Missing("Integration is not owned by this governing session")))
  private def admit(): Unit = {
    available
    require(!closing && !disabled, "Integration admission is closed")
    require(!entries.values.exists(value => Set(IntegrationPhase.Preparing, IntegrationPhase.Running)(value.view.phase)),
      "An integration operation is active; poll it before starting another")
  }
  private def snapshot(entry: IntegrationExecutionState): IntegrationStatus = synchronized(entry.view)
  private def update(entry: IntegrationExecutionState, value: IntegrationStatus): Unit = synchronized { entry.view = value }
  private def preview(intent: IntegrationIntent): IntegrationPreview =
    IntegrationPreview(intent.id, intent.reviewer, intent.target, intent.expected, intent.candidate, intent.members)
  private def projected(value: IntegrationRun): IntegrationStatus = {
    val (phase, next, blocker) = value.record.resolution match {
      case IntegrationResolution.Recorded(_, _) => (IntegrationPhase.Recorded, IntegrationNext.Complete, None)
      case IntegrationResolution.NotApplied(reason) => (IntegrationPhase.NotApplied, IntegrationNext.InspectEvidence, Some(reason))
      case IntegrationResolution.Pending() => (IntegrationPhase.Pending, IntegrationNext.Reconcile, value.blocker)
    }
    IntegrationStatus(value.record.intent.id, phase, Some(preview(value.record.intent)), next, blocker.map(DispatchProjection.concise))
  }
  private def background(entry: IntegrationExecutionState, done: Promise[Nothing, Unit], operation: Task[IntegrationStatus]): Task[Unit] =
    operation.flatMap(value => ZIO.succeed(update(entry, value))).catchAll { error =>
      ZIO.succeed {
        val value = snapshot(entry)
        update(entry, value.copy(phase = if (value.phase == IntegrationPhase.Preparing) IntegrationPhase.Failed else IntegrationPhase.Pending,
          next = IntegrationNext.InspectEvidence, blocker = Some(DispatchProjection.concise("Integration failed: " +
            Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))))
      } *> entry.ready.fail(error).unit
    }.ensuring(done.succeed(()).unit).forkDaemon.unit

  def prepare(ticket: IntegrationTicket): Task[IntegrationStatus] = ZIO.uninterruptibleMask { restore => for {
    ready <- Promise.make[Throwable, Unit]
    done <- Promise.make[Nothing, Unit]
    registered <- ZIO.attempt(synchronized {
      entries.get(ticket.id) match {
        case Some(entry) => require(entry.ticket == ticket, "Integration identity was reused with another reviewer handle"); (entry, false)
        case None =>
          admit()
          require(entries.size < IntegrationEntries.MaxOperations, "Session integration limit reached")
          val entry = new IntegrationExecutionState(ticket, ready, done,
            IntegrationStatus(ticket.id, IntegrationPhase.Preparing, None, IntegrationNext.Wait, None))
          entries = entries.updated(ticket.id, entry)
          (entry, true)
      }
    })
    (entry, fresh) = registered
    _ <- if (!fresh) ZIO.unit else background(entry, done, for {
      _ <- ZIO.attemptBlocking {
        val directory = config.directory.resolve("integration-requests")
        HostFiles.directory(directory)
        HostFiles.immutable(directory.resolve(ticket.id.value.toString + ".json"), HostFiles.encode(IntegrationTicket_JsonCodec, ticket), 1024)
      }
      _ <- ready.succeed(())
      intent <- ZIO.attemptBlocking(new IntegrationPreparation(authority.governor, config.owner, config.run.repository,
        config.settings.integrationTarget.get, config.settings.checks, clock, candidates).prepare(ticket))
      _ <- available.prepare(intent)
    } yield IntegrationStatus(ticket.id, IntegrationPhase.Ready, Some(preview(intent)), IntegrationNext.Confirm, None))
    _ <- restore(entry.ready.await).timeoutFail(new IllegalStateException("Integration ticket acknowledgement deadline exceeded; admission disabled"))(
      zio.Duration.fromMillis(AcknowledgementMillis)).tapError(_ => ZIO.succeed(synchronized { disabled = true }))
  } yield snapshot(entry) }

  def apply(id: IntegrationId): Task[IntegrationStatus] = ZIO.uninterruptibleMask { _ => for {
    done <- Promise.make[Nothing, Unit]
    registered <- ZIO.attempt(synchronized {
      val entry = found(id)
      entry.view.phase match {
        case IntegrationPhase.Ready | IntegrationPhase.Pending =>
          admit()
          entry.done = done
          entry.view = entry.view.copy(phase = IntegrationPhase.Running, next = IntegrationNext.Wait, blocker = None)
          (entry, true)
        case IntegrationPhase.Running | IntegrationPhase.Recorded | IntegrationPhase.NotApplied => (entry, false)
        case _ => throw DomainFailure(Fault.Conflict("Integration is not prepared"))
      }
    })
    (entry, fresh) = registered
    _ <- if (!fresh) ZIO.unit else background(entry, done, available.run(id).map(projected))
  } yield snapshot(entry) }

  def status(id: IntegrationId, waitMillis: Int): Task[IntegrationStatus] = for {
    current <- ZIO.attempt(synchronized {
      require(waitMillis >= 0 && waitMillis <= MaxWaitMillis, "Integration wait must be 0–20000 ms")
      val entry = found(id)
      (entry, entry.done)
    })
    (entry, done) = current
    _ <- if (waitMillis == 0) ZIO.unit else done.await.timeout(zio.Duration.fromMillis(waitMillis)).unit
  } yield snapshot(entry)

  def quiescent: Boolean = synchronized(entries.values.forall(value => IntegrationController.terminal(value.view.phase)))

  def shutdown: Task[Unit] = for {
    pending <- ZIO.succeed(synchronized { closing = true; entries.values.map(_.done).toList })
    stopped <- execution.shutdown.exit
    _ <- ZIO.foreachDiscard(pending)(_.await)
    _ <- ZIO.done(stopped)
  } yield ()
}

object IntegrationController {
  def terminal(phase: IntegrationPhase): Boolean = Set(IntegrationPhase.Recorded, IntegrationPhase.NotApplied, IntegrationPhase.Failed)(phase)
  final class Resource(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor, candidates: CandidateWorkspace, clock: Clock,
    watchdog: SupervisorWatchdog)
    extends Lifecycle.Of[Task, IntegrationController](Lifecycle.make(
      Semaphore.make(1).map(new IntegrationController(config, authority, jobs, candidates, clock, _)))(
      value => ZIO.succeed(watchdog.beginShutdown()) *> value.shutdown))
}
