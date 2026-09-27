package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.*
import cq.host.{ChildContracts, JobOutcome}
import io.circe.parser
import izumi.functional.bio.{Error2, F, *}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import scala.util.Try

final class IntegrationServiceImpl[F[+_, +_]: Error2](ledger: LedgerRepository[F], artifacts: ArtifactRepository[F],
  mutations: LedgerMutation, clock: Clock) extends IntegrationService[F] {
  import LedgerPolicy.invalid

  private def collector(scope: Scope, project: ProjectId, owner: Actor): Unit =
    if (scope.project != project || scope.actor.role != Role.Collector || owner.role != Role.Governor || scope.actor.session != owner.session)
      throw DomainFailure(Fault.Denied("Integration requires its governing session's host collector"))

  private def objectId(value: GitCommit): Unit = invalid(value.value.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Expected a complete Git object ID")

  private def artifact[A](scope: Scope, id: ArtifactId, kind: ArtifactKind, codec: BaboonJsonCodec[A]): F[Throwable, (ArtifactMetadata, A)] = for {
    stored <- artifacts.get(scope.project, id).flatMap {
      case Some(value) => F.pure(value)
      case None => F.fail(DomainFailure(Fault.Missing("Integration evidence artifact is missing")))
    }
    result <- F.fromEither(Try {
      val metadata = stored.metadata
      if (metadata.actor.role != Role.Collector || metadata.actor.session != scope.actor.session)
        throw DomainFailure(Fault.Denied("Integration evidence belongs to another publisher session"))
      invalid(metadata.kind == kind && metadata.mediaType == "application/json", "Unexpected integration evidence artifact type")
      val json = parser.parse(stored.body).fold(_ => throw DomainFailure(Fault.Invalid("Integration evidence is not JSON")), identity)
      val value = codec.decode(BaboonCodecContext.Default, json).fold(_ => throw DomainFailure(Fault.Invalid("Integration evidence differs from its schema")), identity)
      invalid(codec.encode(BaboonCodecContext.Default, value) == json, "Integration evidence has undeclared or noncanonical fields")
      (metadata, value)
    }.toEither)
  } yield result

  override def reserve(scope: Scope, intent: IntegrationIntent): F[Throwable, IntegrationRecord] = for {
    _ <- F.fromEither(Try {
      collector(scope, intent.project, intent.owner)
      invalid(intent.members.nonEmpty && intent.members.size <= LedgerPolicy.MaxBatch && intent.members.map(_.id).distinct.size == intent.members.size &&
        intent.members.forall(ref => ref.id.project == scope.project && ref.id.ledger == Ledger.Tasks && ref.id.number > 0 && ref.revision.value > 0), "Invalid integration task membership")
      invalid(intent.repository.nonEmpty && intent.repository.length <= LedgerPolicy.MaxLocation && !intent.repository.contains('\u0000') &&
        java.nio.file.Path.of(intent.repository).isAbsolute, "Integration repository must be an absolute path")
      invalid(intent.target.length <= LedgerPolicy.MaxLocation && intent.target.startsWith("refs/heads/") &&
        intent.target.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") && !intent.target.contains("..") &&
        intent.target.split("/", -1).forall(part => part.nonEmpty && !part.startsWith(".") && !part.endsWith(".") && !part.endsWith(".lock")), "Invalid integration branch")
      objectId(intent.expected); objectId(intent.candidate)
      invalid(intent.candidate != intent.expected, "Integration candidate must advance its target")
      invalid(intent.checks.size <= IntegrationPolicy.MaxChecks && intent.checks.map(_.name).distinct.size == intent.checks.size, "Invalid integration check set")
      if (IntegrationIntent_JsonCodec.encode(BaboonCodecContext.Default, intent).noSpaces.getBytes(UTF_8).length > IntegrationPolicy.MaxIntentBytes)
        throw DomainFailure(Fault.Limit("Integration intent exceeds 512 KiB"))
    }.toEither)
    worker <- artifact(scope, intent.worker, ArtifactKind.Result, ChildResult_JsonCodec)
    reviewer <- artifact(scope, intent.reviewer, ArtifactKind.Result, ChildResult_JsonCodec)
    _ <- F.fromEither(Try {
      val work = worker._2
      val review = reviewer._2
      Try { ChildContracts.result(scope.project, work); ChildContracts.result(scope.project, review) }.recover { case error: IllegalArgumentException =>
        throw DomainFailure(Fault.Invalid(error.getMessage))
      }.get
      invalid(work.attempt == worker._1.attempt && review.attempt == reviewer._1.attempt && work.attempt != review.attempt &&
        work.request.work.isInstanceOf[DispatchWork.Worker] && work.request.work != DispatchWork.Worker(WorkerMode.Probe) &&
        review.request.work == DispatchWork.Reviewer() && review.request.previous.contains(intent.worker) &&
        work.request.members == intent.members && review.request.members == intent.members &&
        work.request.fence == intent.fence && review.request.fence == intent.fence && work.base == intent.expected &&
        work.candidate.contains(intent.candidate) && review.candidate == work.candidate && review.base == intent.candidate && review.validation == work.validation,
        "Integration requires an independently reviewed exact worker candidate and assignment")
      invalid(work.report match { case ChildReport.Work(members) => members.forall(_.disposition == WorkDisposition.CandidateReady); case _ => false }, "Every integration member must be candidate-ready")
      invalid(review.report match { case ChildReport.Review(members) => members.forall(_.verdict == ReviewVerdict.Accepted); case _ => false }, "Every integration member must be independently accepted")
      invalid(work.validation.map(_.check) == intent.checks.map(_.name) && work.validation.forall(_.state == ValidationState.Passed), "Applicable integration checks have not all passed")
    }.toEither)
    _ <- F.traverse_(worker._2.validation.zip(intent.checks)) { case (evidence, check) =>
      artifact(scope, evidence.artifact, ArtifactKind.Validation, ValidationObservation_JsonCodec).flatMap { case (metadata, observed) => F.fromEither(Try {
        invalid(metadata.attempt == worker._2.attempt && observed.check == check && observed.candidate == intent.candidate &&
          observed.job.workspace.project == scope.project && observed.job.workspace.owner == scope.actor.session &&
          observed.job.workspace.base == intent.candidate && observed.job.phase == JobPhase.Settled && JobOutcome.observed(observed.job).succeeded,
          "Validation observation does not establish success for this candidate/check")
      }.toEither) }
    }
    result <- ledger.transact(scope.project) { tx =>
      tx.integration(intent.id) match {
        case Some(previous) =>
          if (previous.intent != intent) throw DomainFailure(Fault.Conflict("Integration identity reused with different intent"))
          previous
        case None =>
          val now = clock.millis()
          List(worker, reviewer).foreach { case (metadata, body) =>
            invalid(tx.admission(body.attempt).exists(value => value.artifact == metadata && value.owner == intent.owner && value.fence == intent.fence &&
              value.members == intent.members && value.decision == AdmissionDecision.Accepted()), "Integration evidence has no matching accepted admission")
          }
          val owned = tx.claimById(intent.fence.claim).exists(claim => claim.owner == intent.owner && claim.fence == intent.fence &&
            claim.members == intent.members.map(_.id).toSet && ClaimPolicy.active(tx, claim, now))
          if (!owned) throw DomainFailure(Fault.StaleFence("Integration reservation requires the current full claim"))
          IntegrationPolicy.unreserved(tx, intent.members.map(_.id).toSet)
          val items = intent.members.map { ref =>
            val item = LedgerAccess.required(tx, Scope(scope.project, intent.owner), ref.id)
            LedgerAccess.expected(item, ref.revision)
            item
          }
          val change = IntegrationPolicy.completion(intent.id, intent.repository, intent.target, intent.candidate, intent.worker, intent.reviewer,
            worker._2.validation.map(_.artifact), intent.fence, items)
          invalid(intent.change == change, "Integration may only apply the exact narrative-preserving task completion request")
          if (tx.request(intent.owner, change.request).nonEmpty) throw DomainFailure(Fault.Conflict("Integration domain request was already used"))
          val value = IntegrationRecord(intent, IntegrationResolution.Pending(), now, None)
          tx.insertIntegration(value)
          value
      }
    }
  } yield result

  override def get(scope: Scope, id: IntegrationId): F[Throwable, IntegrationRecord] = ledger.transact(scope.project) { tx =>
    tx.integration(id).getOrElse(throw DomainFailure(Fault.Missing("Integration not found")))
  }

  override def observe(scope: Scope, id: IntegrationId, observation: IntegrationObservation): F[Throwable, IntegrationRecord] = ledger.transact(scope.project) { tx =>
    val record = tx.integration(id).getOrElse(throw DomainFailure(Fault.Missing("Integration not found")))
    collector(scope, record.intent.project, record.intent.owner)
    val now = clock.millis()
    val resolution = (record.resolution, observation) match {
      case (IntegrationResolution.Pending(), IntegrationObservation.Incorporated(target)) =>
        objectId(target)
        IntegrationResolution.Recorded(mutations.integrate(tx, id, now), target)
      case (IntegrationResolution.Pending(), IntegrationObservation.NotApplied(reason)) =>
        invalid(reason.trim.nonEmpty && reason.length <= LedgerPolicy.MaxTitle, "A bounded settled non-application reason is required")
        IntegrationResolution.NotApplied(reason)
      case (previous @ IntegrationResolution.Recorded(_, target), IntegrationObservation.Incorporated(observed)) if target == observed => previous
      case (previous @ IntegrationResolution.NotApplied(reason), IntegrationObservation.NotApplied(observed)) if reason == observed => previous
      case _ => throw DomainFailure(Fault.Conflict("Integration already has another immutable observation"))
    }
    if (record.resolution == resolution) record
    else {
      val resolved = record.copy(resolution = resolution, resolvedAt = Some(now))
      tx.resolveIntegration(resolved)
      resolved
    }
  }
}
