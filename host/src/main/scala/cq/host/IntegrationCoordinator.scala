package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope}
import zio.{Task, ZIO}

final case class IntegrationRun(record: IntegrationRecord, blocker: Option[String])

/** The server refused the reservation and holds none, and no Git update was launched: the journal has sealed the integration as not applied. */
final class IntegrationRefused(val reason: String) extends RuntimeException(reason)

object IntegrationCoordinator {
  /** The reason the journal seals for an integration its session discarded before applying it. */
  val Discarded: String = "Discarded by the governing session"
}

final class IntegrationCoordinator(owner: Scope, journal: IntegrationJournal, git: GitIntegration, governor: ServerApi, collector: ServerApi) {
  private def server(id: IntegrationId): Option[IntegrationRecord] =
    governor.call(Command.Read(ReadInput(owner.project, ReadSelection.Integration(id)))) match {
      case Result.Integration(record) => Some(record)
      case Result.Failed(_: Fault.Missing) => None
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Integration read returned an unexpected result")
    }

  def prepare(intent: IntegrationIntent): Task[Unit] = journal.locked(intent.id) { entry => ZIO.attemptBlocking {
    require(intent.project == owner.project && intent.owner == owner.actor, "Integration preparation belongs to another owner")
    entry.read match {
      case Some(value) => require(value.intent == intent, "Integration identity was reused with a different intent")
      case None =>
        require(server(intent.id).isEmpty, "Server integration exists without this local journal; execution cannot be reconstructed")
        entry.write(IntegrationLocal(intent, false, None))
    }
  }}

  private def reconcile(intent: IntegrationIntent): Task[Option[IntegrationObservation]] = for {
    target <- git.inspect(intent)
    execution <- if (target.incorporated) ZIO.succeed(None) else git.execution(intent)
    refused = execution.exists(_.refusedBeforeCommit)
    // A job that ended without a settled refusal may still have started no effect: then it is withdrawn, and the integration settles (D153).
    withdrawn <- if (target.incorporated || refused) ZIO.succeed(None) else git.withdraw(intent)
  } yield {
    if (target.incorporated) Some(IntegrationObservation.Incorporated(target.commit))
    else if (refused) Some(IntegrationObservation.NotApplied(execution.get.refusal.getOrElse("Git refused the conditional update before commit; executor settled")))
    else withdrawn.map(IntegrationObservation.NotApplied.apply)
  }

  private def observe(entry: IntegrationEntry, local: IntegrationLocal): Task[Option[IntegrationObservation]] = local.observation match {
    case Some(value) => ZIO.succeed(Some(value))
    case None =>
      val effect = if (local.attempted) reconcile(local.intent) else {
        git.inspect(local.intent).flatMap { target =>
          if (target.incorporated) ZIO.succeed(Some(IntegrationObservation.Incorporated(target.commit)))
          else if (target.checkoutBlocked) ZIO.succeed(Some(IntegrationObservation.NotApplied("Configured integration target is checked out in another or multiple worktrees; no update launched")))
          else if (target.commit != local.intent.expected) ZIO.succeed(Some(IntegrationObservation.NotApplied("Integration target advanced before execution; no update launched")))
          else ZIO.attemptBlocking(entry.write(local.copy(attempted = true))) *> git.execute(local.intent).either.flatMap {
            case Left(_: IntegrationAdmissionClosed) => ZIO.succeed(Some(IntegrationObservation.NotApplied("Owning supervisor closed execution admission before job registration")))
            case _ => reconcile(local.intent)
          }
        }
      }
      effect.flatMap(value => ZIO.attemptBlocking {
        value.foreach(observation => entry.write(entry.read.get.copy(observation = Some(observation))))
        value
      })
  }

  /** The reason a server refusal or a discard sealed in the journal, which only an unattempted integration without a server record carries. */
  private def refusal(local: IntegrationLocal): Option[String] = local.observation.collect {
    case IntegrationObservation.NotApplied(reason) if !local.attempted && server(local.intent.id).isEmpty => reason
  }

  /** Nothing was applied and nothing is reserved: no execution was admitted, no outcome is retained and the server holds no record. */
  private def unapplied(local: IntegrationLocal): Boolean = !local.attempted && local.observation.isEmpty && server(local.intent.id).isEmpty

  /** Whether a refusal of Reserve shows that this reservation can never be made. The intent, its evidence and its fence are immutable,
    * so a refusal that names one of them is final. */
  private def conclusive(fault: Fault): Boolean = fault match {
    // The claim was released, expired or replaced; the intent or its evidence is invalid, absent or too large; a member was revised
    // or the completion request was used.
    case _: Fault.StaleFence | _: Fault.Invalid | _: Fault.Missing | _: Fault.Conflict | _: Fault.Limit => true
    // A credential that expired or was not accepted says nothing about the reservation, and a sibling integration that holds a
    // reservation on a member may itself resolve as not applied.
    case _: Fault.Denied | _: Fault.IntegrationPending => false
    // Not answers of the reservation.
    case _: Fault.Resync | _: Fault.QuerySyntax => false
  }

  // A conclusive refusal is the server's answer, so nothing was reserved; with no execution admitted either, the integration can be
  // sealed. Any other refusal, and an unanswered reservation, prove neither and leave it pending.
  // `refused` is asked about a denial, which by itself may only mean a credential the server did not accept: it gives the reason
  // when the caller knows a rule of the server that refuses this reservation as things stand.
  private def reserve(entry: IntegrationEntry, local: IntegrationLocal, refused: () => Option[String]): IntegrationRecord =
    try {
      val value = collector.integrate(HostIntegrationInput(owner.project, HostIntegration.Reserve(local.intent)))
      require(value.intent == local.intent, "Server reservation differs from local intent")
      value
    } catch {
      case failure @ DomainFailure(fault) =>
        val answered = conclusive(fault) || fault.isInstanceOf[Fault.Denied] && refused().nonEmpty
        if (!answered || !unapplied(local)) throw failure
        val reason = DispatchProjection.concise("Server refused the reservation, so no Git update was launched: " + fault)
        entry.write(local.copy(observation = Some(IntegrationObservation.NotApplied(reason))))
        throw new IntegrationRefused(reason)
    }

  /** Seals an integration that its session will not apply as not applied. Only the journal is written: Git and the target are not
    * touched and the server is asked for nothing but its record. An integration already sealed as not applied stays as it is. */
  def discard(id: IntegrationId): Task[Unit] = journal.locked(id) { entry => ZIO.attemptBlocking {
    val local = entry.read.getOrElse(throw DomainFailure(Fault.Missing("Integration journal is missing; there is nothing to discard")))
    if (refusal(local).isEmpty) {
      if (!unapplied(local)) throw DomainFailure(Fault.Conflict(
        "its execution was admitted, its outcome is retained or the server holds its reservation; apply it with Integrate to settle it"))
      entry.write(local.copy(observation = Some(IntegrationObservation.NotApplied(IntegrationCoordinator.Discarded))))
    }
  }}

  /** Fails with `IntegrationRefused` when the server refused the reservation before any attempt, now or in an earlier run. */
  def run(id: IntegrationId): Task[IntegrationRun] = run(id, () => None)

  /** As `run`; `refused` says why the server refuses this reservation now, when the caller can tell (see `reserve`). */
  def run(id: IntegrationId, refused: () => Option[String]): Task[IntegrationRun] = journal.locked(id) { entry => for {
    local <- ZIO.attemptBlocking(entry.read.getOrElse(throw DomainFailure(Fault.Missing("Integration journal is missing; no effect may be retried"))))
    record <- ZIO.attemptBlocking {
      refusal(local).foreach(reason => throw new IntegrationRefused(reason))
      reserve(entry, local, refused)
    }
    result <- deliver(local, record, observe(entry, local))
  } yield result }

  private def deliver(local: IntegrationLocal, record: IntegrationRecord, observation: Task[Option[IntegrationObservation]]): Task[IntegrationRun] = record.resolution match {
      case IntegrationResolution.Pending() => observation.either.flatMap {
        case Right(Some(observation)) => ZIO.attemptBlocking {
          val recorded = collector.integrate(HostIntegrationInput(owner.project, HostIntegration.Observe(local.intent.id, observation)))
          require(recorded.intent == local.intent && recorded.resolution != IntegrationResolution.Pending(), "Server did not resolve the reserved integration")
          IntegrationRun(recorded, None)
        }.catchAll(error => ZIO.succeed(IntegrationRun(record, Some("Git observation retained; domain acknowledgement pending: " + error.getClass.getSimpleName))))
        case Right(None) => ZIO.succeed(IntegrationRun(record, Some("Git execution or incorporation remains unconfirmed; retain the reservation and journal")))
        case Left(error) => ZIO.succeed(IntegrationRun(record, Some("Git observation is pending: " + error.getClass.getSimpleName)))
      }
      case _ => ZIO.succeed(IntegrationRun(record, None))
    }

  def recover(id: IntegrationId): Task[Option[IntegrationRun]] = journal.locked(id) { entry => for {
    local <- ZIO.attemptBlocking(entry.read.getOrElse(throw DomainFailure(Fault.Missing("Integration journal is missing; recovery cannot reconstruct execution"))))
    previous <- ZIO.attemptBlocking(server(id))
    result <- previous match {
      case None => ZIO.attempt {
        require(IntegrationEntries.unreserved(local), "Server reservation is missing after local execution or observation; outcome is unresolved")
        None
      }
      case Some(record) =>
        val observation = local.observation match {
          case Some(value) => ZIO.succeed(Some(value))
          case None if local.attempted => reconcile(local.intent).flatMap { value => ZIO.attemptBlocking {
            value.foreach(observed => entry.write(local.copy(observation = Some(observed))))
            value
          }}
          case None => ZIO.attemptBlocking {
            val value = IntegrationObservation.NotApplied("Owning supervisor ended before Git execution admission")
            entry.write(local.copy(observation = Some(value)))
            Some(value)
          }
        }
        ZIO.attempt(require(record.intent == local.intent, "Server reservation differs from retained intent")) *>
          deliver(local, record, observation).map(Some(_))
    }
  } yield result }
}
