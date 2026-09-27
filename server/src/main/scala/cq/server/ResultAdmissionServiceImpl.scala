package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.host.ChildContracts
import io.circe.parser
import izumi.functional.bio.{Error2, F, *}
import java.time.Clock
import scala.util.Try

final class ResultAdmissionServiceImpl[F[+_, +_]: Error2](ledger: LedgerRepository[F], artifacts: ArtifactRepository[F],
  usage: UsageRepository[F], clock: Clock) extends ResultAdmissionService[F] {

  override def admit(scope: Scope, input: HostAdmissionInput): F[Throwable, ResultAdmission] = for {
    _ <- F.fromEither(Try {
      if (scope.project != input.project || scope.actor.role != Role.Collector || input.owner.role != Role.Governor ||
        scope.actor.session != input.owner.session) throw DomainFailure(Fault.Denied("Result admission requires the governing session's host collector"))
    }.toEither)
    artifact <- artifacts.get(scope.project, input.artifact).flatMap {
      case Some(value) => F.pure(value)
      case None => F.fail(DomainFailure(Fault.Missing("Result artifact not found")))
    }
    result <- F.fromEither(Try {
      val metadata = artifact.metadata
      if (metadata.actor.role != Role.Collector || metadata.actor.session != scope.actor.session)
        throw DomainFailure(Fault.Denied("Result artifact belongs to another publisher session"))
      LedgerPolicy.invalid(metadata.kind == ArtifactKind.Result && metadata.mediaType == "application/json", "Expected a structured child result artifact")
      val json = parser.parse(artifact.body).fold(_ => throw DomainFailure(Fault.Invalid("Result artifact is not JSON")), identity)
      val value = ChildResult_JsonCodec.decode(BaboonCodecContext.Default, json)
        .fold(_ => throw DomainFailure(Fault.Invalid("Result artifact does not match its schema")), identity)
      LedgerPolicy.invalid(ChildResult_JsonCodec.encode(BaboonCodecContext.Default, value) == json, "Result artifact has undeclared or noncanonical fields")
      Try(ChildContracts.result(scope.project, value)).recover { case failure: IllegalArgumentException =>
        throw DomainFailure(Fault.Invalid(failure.getMessage))
      }.get
      LedgerPolicy.invalid(value.attempt == metadata.attempt, "Result artifact attempt differs from its content")
      value
    }.toEither)
    _ <- usage.read(scope.project) { reader =>
      val attempt = reader.attempt(result.attempt).getOrElse(throw DomainFailure(Fault.Missing("Result attempt is not registered")))
      if (attempt.session != scope.actor.session) throw DomainFailure(Fault.Denied("Result attempt belongs to another session"))
      val assignment = reader.assignment(attempt.assignment).getOrElse(throw new IllegalStateException("Registered attempt has no assignment"))
      val parent = attempt.parent.flatMap(reader.attempt).getOrElse(throw DomainFailure(Fault.Invalid("Child result requires its registered governing attempt")))
      LedgerPolicy.invalid(parent.role == Role.Governor && parent.session == attempt.session &&
        attempt.role == ChildContracts.role(result.request.work) && attempt.harness == result.request.harness &&
        assignment.members == result.request.members.map(_.id).toSet, "Result differs from the registered child assignment")
    }
    admitted <- ledger.transact(scope.project) { tx =>
      tx.admission(result.attempt) match {
        case Some(previous) =>
          if (previous.artifact != artifact.metadata || previous.owner != input.owner || previous.fence != result.request.fence ||
            previous.members != result.request.members) throw DomainFailure(Fault.Conflict("Attempt already has another result admission intent"))
          previous
        case None =>
          val now = clock.millis()
          val owned = tx.claimById(result.request.fence.claim).exists { claim =>
            claim.fence == result.request.fence && claim.owner == input.owner && claim.members == result.request.members.map(_.id).toSet &&
              ClaimPolicy.active(tx, claim, now)
          }
          val decision = if (!owned) AdmissionDecision.Rejected(AdmissionRejection.ClaimLost)
          else if (!result.request.members.forall(ref => tx.summary(ref.id).exists(_.revision == ref.revision)))
            AdmissionDecision.Rejected(AdmissionRejection.RevisionsChanged)
          else AdmissionDecision.Accepted()
          val value = ResultAdmission(artifact.metadata, input.owner, result.request.fence, result.request.members, decision, now)
          tx.insertAdmission(value)
          value
      }
    }
  } yield admitted

  override def get(scope: Scope, attempt: AttemptId): F[Throwable, ResultAdmission] = ledger.transact(scope.project) { tx =>
    tx.admission(attempt).getOrElse(throw DomainFailure(Fault.Missing("Result admission not found")))
  }
}
