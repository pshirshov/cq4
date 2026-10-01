package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.JsonRoundtrip
import cq.core.IntegrationPolicy
import cq.core.LedgerPolicy.invalid
import io.circe.parser

final case class ApplicableValidation(evidence: ValidationEvidence, declaration: ValidationCheck, author: AttemptId)

object IntegrationValidation {
  private val RebasedRule = "Rebased integration requires passing host checks on the exact rebased commit"

  def applicable(worker: ChildResult, reviewer: ChildResult, declarations: List[ValidationCheck]): List[ApplicableValidation] = {
    val names = declarations.map(_.name)
    invalid(names.distinct == names && names.size <= IntegrationPolicy.MaxChecks && worker.validation.map(_.check) == names && reviewer.validation.map(_.check) == names,
      "Worker and reviewer validation must cover the configured check inventory")
    invalid((worker.validation ++ reviewer.validation).forall(_.state == ValidationState.Passed), "All worker and reviewer checks must pass")
    val original = worker.validation.zip(declarations).map { case (evidence, declaration) => ApplicableValidation(evidence, declaration, worker.attempt) }
    val fresh = reviewer.validation.zip(original).collect {
      case (evidence, previous) if evidence != previous.evidence => ApplicableValidation(evidence, previous.declaration, reviewer.attempt)
    }
    original ++ fresh
  }

  /** The host's own checks of a rebased commit: every configured check, at least one, each passed. */
  def rebased(rebase: IntegrationRebase, declarations: List[ValidationCheck]): List[ApplicableValidation] = {
    invalid(declarations.nonEmpty && rebase.validation.map(_.check) == declarations.map(_.name) &&
      rebase.validation.forall(_.state == ValidationState.Passed), RebasedRule)
    rebase.validation.zip(declarations).map { case (evidence, declaration) => ApplicableValidation(evidence, declaration, rebase.author) }
  }

  def citations(worker: ChildResult, reviewer: ChildResult): List[ArtifactId] =
    (worker.validation ++ reviewer.validation).map(_.artifact).distinct

  private def established(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Boolean =
    metadata.id == expected.evidence.artifact && metadata.project == project && metadata.attempt == expected.author &&
      metadata.kind == ArtifactKind.Validation && metadata.mediaType == "application/json" &&
      metadata.actor.session == session && metadata.actor.role == Role.Collector && observation.check == expected.declaration &&
      observation.candidate == candidate && observation.job.workspace.project == project && observation.job.workspace.owner == session &&
      observation.job.workspace.base == candidate && observation.job.phase == JobPhase.Settled && JobOutcome.observed(observation.job).succeeded

  def verify(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Unit =
    invalid(established(project, session, candidate, expected, metadata, observation),
      "Validation observation does not establish success for this author, candidate and check")

  def verifyRebased(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Unit =
    invalid(established(project, session, candidate, expected, metadata, observation), RebasedRule)

  def decode(value: ResolvedArtifact): ValidationObservation = {
    val json = parser.parse(value.body).fold(throw _, identity)
    val observed = ValidationObservation_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    invalid(JsonRoundtrip.lossless(json, ValidationObservation_JsonCodec.encode(BaboonCodecContext.Default, observed)),
      "Validation observation contains undeclared or noncanonical fields")
    observed
  }
}
