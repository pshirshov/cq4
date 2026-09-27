package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.*
import cq.host.ChildContracts
import io.circe.parser
import izumi.functional.bio.{Error2, F, *}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import scala.util.Try

final class ProposalServiceImpl[F[+_, +_]: Error2](ledger: LedgerRepository[F], artifacts: ArtifactRepository[F],
  mutations: LedgerMutation, clock: Clock) extends ProposalService[F] {
  private val MaxPreviewBytes = 32 * 1024
  private final case class Loaded(artifact: ArtifactMetadata, result: ChildResult, proposal: PreparedProposal) {
    def change: ChangeRequest = ChangeRequest(ProposalPolicy.request(artifact.id), proposal.mutations, List(result.request.fence), proposal.value.reason)
  }

  private def load(scope: Scope, id: ArtifactId): F[Throwable, Loaded] = for {
    stored <- artifacts.get(scope.project, id).flatMap {
      case Some(value) => F.pure(value)
      case None => F.fail(DomainFailure(Fault.Missing("Proposal result not found in this project")))
    }
    value <- F.fromEither(Try {
      LedgerPolicy.invalid(stored.metadata.kind == ArtifactKind.Result && stored.metadata.mediaType == "application/json", "Proposal requires a structured child result")
      val json = parser.parse(stored.body).fold(_ => throw DomainFailure(Fault.Invalid("Proposal result is not JSON")), identity)
      val result = ChildResult_JsonCodec.decode(BaboonCodecContext.Default, json)
        .fold(_ => throw DomainFailure(Fault.Invalid("Proposal result does not match its schema")), identity)
      LedgerPolicy.invalid(ChildResult_JsonCodec.encode(BaboonCodecContext.Default, result) == json, "Proposal result has undeclared or noncanonical fields")
      Try(ChildContracts.result(scope.project, result)).recover { case error: IllegalArgumentException => throw DomainFailure(Fault.Invalid(error.getMessage)) }.get
      LedgerPolicy.invalid(result.attempt == stored.metadata.attempt, "Proposal result attempt differs from its artifact")
      val prepared = ProposalPolicy.prepare(result.request.work, result.request.members, result.report)
        .getOrElse(throw DomainFailure(Fault.Invalid("Result contains no applicable proposal")))
      Loaded(stored.metadata, result, prepared)
    }.toEither)
  } yield value

  private def admitted(tx: LedgerTransaction, loaded: Loaded): ResultAdmission = {
    val admission = tx.admission(loaded.result.attempt).getOrElse(throw DomainFailure(Fault.Conflict("Proposal result has not been admitted")))
    if (admission.decision != AdmissionDecision.Accepted() || admission.artifact != loaded.artifact ||
      admission.fence != loaded.result.request.fence || admission.members != loaded.result.request.members)
      throw DomainFailure(Fault.Conflict("Proposal does not match the accepted result admission"))
    admission
  }

  private def boundedPreview(tx: LedgerTransaction, loaded: Loaded): ProposalPreview = {
    val operations = loaded.proposal.mutations.map {
      case Mutation.Create(draft) => ProposalOperationSummary.Create(ProposalPolicy.summary(draft))
      case Mutation.Replace(id, revision, draft) =>
        val before = tx.historical(id, revision).getOrElse(throw DomainFailure(Fault.Missing("Proposed source revision is unavailable"))).item.item.draft
        ProposalOperationSummary.Replace(id, ProposalPolicy.summary(before), ProposalPolicy.summary(draft), ProposalPolicy.fields(before, draft))
      case Mutation.Produce(producer, _, drafts) => ProposalOperationSummary.Produce(producer, drafts.map(ProposalPolicy.summary))
      case Mutation.Reference(source, _, relation, target, _, present) => ProposalOperationSummary.Reference(source, relation, target, present)
      case _ => throw new IllegalStateException("Prepared proposal contains an unsupported mutation")
    }
    val value = ProposalPreview(loaded.artifact.id, loaded.change.request, ChildContracts.role(loaded.result.request.work), loaded.result.request.members, operations, true)
    if (Wire.encode(ProposalPreview_JsonCodec, value).getBytes(UTF_8).length > MaxPreviewBytes)
      throw DomainFailure(Fault.Limit("Proposal preview exceeds 32 KiB; split the proposal"))
    value
  }

  override def preview(scope: Scope, id: ArtifactId): F[Throwable, ProposalPreview] = load(scope, id).flatMap { loaded =>
    ledger.transact(scope.project) { tx =>
      admitted(tx, loaded)
      boundedPreview(tx, loaded)
    }
  }

  override def apply(scope: Scope, id: ArtifactId): F[Throwable, ChangeAck] = for {
    _ <- F.fromEither(Try {
      if (scope.actor.role != Role.Governor) throw DomainFailure(Fault.Denied("Proposal application requires its original governor"))
    }.toEither)
    loaded <- load(scope, id)
    result <- ledger.transact(scope.project) { tx =>
      val admission = admitted(tx, loaded)
      if (admission.owner != scope.actor) throw DomainFailure(Fault.Denied("Proposal belongs to another governor"))
      boundedPreview(tx, loaded)
      val request = loaded.change
      val now = clock.millis()
      if (tx.request(scope.actor, request.request).isEmpty) {
        val members = admission.members.map(_.id).toSet
        val current = tx.claimById(admission.fence.claim).exists { claim =>
          claim.fence == admission.fence && claim.owner == scope.actor && claim.members == members && ClaimPolicy.active(tx, claim, now)
        }
        if (!current) throw DomainFailure(Fault.StaleFence("Proposal claim is no longer current"))
        admission.members.foreach { ref => LedgerAccess.expected(LedgerAccess.required(tx, scope, ref.id), ref.revision) }
        IntegrationPolicy.unreserved(tx, members)
      }
      mutations(tx, scope, request, now)
    }
  } yield result
}
