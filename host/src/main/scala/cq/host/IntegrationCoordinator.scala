package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope}
import zio.{Task, ZIO}

final case class IntegrationRun(record: IntegrationRecord, blocker: Option[String])

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
  } yield {
    if (target.incorporated) Some(IntegrationObservation.Incorporated(target.commit))
    else if (execution.exists(_.refusedBeforeCommit)) Some(IntegrationObservation.NotApplied("Git refused the conditional update before commit; executor settled"))
    else None
  }

  private def observe(entry: IntegrationEntry, local: IntegrationLocal): Task[Option[IntegrationObservation]] = local.observation match {
    case Some(value) => ZIO.succeed(Some(value))
    case None =>
      val effect = if (local.attempted) reconcile(local.intent) else {
        git.inspect(local.intent).flatMap { target =>
          if (target.incorporated) ZIO.succeed(Some(IntegrationObservation.Incorporated(target.commit)))
          else if (target.checkedOut) ZIO.succeed(Some(IntegrationObservation.NotApplied("Configured integration target is checked out; no update launched")))
          else if (target.commit != local.intent.expected) ZIO.succeed(Some(IntegrationObservation.NotApplied("Integration target advanced before execution; no update launched")))
          else ZIO.attemptBlocking(entry.write(local.copy(attempted = true))) *> git.execute(local.intent).either *> reconcile(local.intent)
        }
      }
      effect.flatMap(value => ZIO.attemptBlocking {
        value.foreach(observation => entry.write(entry.read.get.copy(observation = Some(observation))))
        value
      })
  }

  def run(id: IntegrationId): Task[IntegrationRun] = journal.locked(id) { entry => for {
    local <- ZIO.attemptBlocking(entry.read.getOrElse(throw DomainFailure(Fault.Missing("Integration journal is missing; no effect may be retried"))))
    record <- ZIO.attemptBlocking {
      val value = collector.integrate(HostIntegrationInput(owner.project, HostIntegration.Reserve(local.intent)))
      require(value.intent == local.intent, "Server reservation differs from local intent")
      value
    }
    result <- record.resolution match {
      case IntegrationResolution.Pending() => observe(entry, local).either.flatMap {
        case Right(Some(observation)) => ZIO.attemptBlocking {
          val recorded = collector.integrate(HostIntegrationInput(owner.project, HostIntegration.Observe(id, observation)))
          require(recorded.intent == local.intent && recorded.resolution != IntegrationResolution.Pending(), "Server did not resolve the reserved integration")
          IntegrationRun(recorded, None)
        }.catchAll(error => ZIO.succeed(IntegrationRun(record, Some("Git observation retained; domain acknowledgement pending: " + error.getClass.getSimpleName))))
        case Right(None) => ZIO.succeed(IntegrationRun(record, Some("Git execution or incorporation remains unconfirmed; retain the reservation and journal")))
        case Left(error) => ZIO.succeed(IntegrationRun(record, Some("Git observation is pending: " + error.getClass.getSimpleName)))
      }
      case _ => ZIO.succeed(IntegrationRun(record, None))
    }
  } yield result }
}
