package cq.host

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.{DomainFailure, IntegrationPolicy, JsonRoundtrip}
import cq.core.LedgerPolicy.invalid
import io.circe.parser
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

final case class ApplicableValidation(evidence: ValidationEvidence, declaration: ValidationCheck, author: AttemptId)
/** A run of a check that failed before the observation the integration relies on, cited in the Task evidence. */
final case class FailedRun(artifact: ArtifactId, declaration: ValidationCheck, author: AttemptId)
/** What the Task evidence cites, each under its own label: the observations that establish each check, the runs that failed before
  * them, and the revalidation rounds (whatever their outcome). */
final case class ValidationCitations(established: List[ArtifactId], failed: List[ArtifactId], rounds: List[ArtifactId])
/** A revalidation round of an admitted result as the server stores it. */
final case class PublishedAmendment(stored: ResolvedArtifact, value: ValidationAmendment)
/** One configured check of an admitted result: its evidence at admission and each revalidation round that reran it. */
final case class EffectiveCheck(original: ApplicableValidation, rounds: List[ApplicableValidation]) {
  def current: ApplicableValidation = rounds.lastOption.getOrElse(original)
}
/** An admitted result's validation with each check replaced by its latest revalidation round. The result itself never changes. */
final case class EffectiveValidation(result: ChildResult, checks: List[EffectiveCheck], amendments: List[ArtifactId]) {
  def current: List[ValidationEvidence] = checks.map(_.current.evidence)
}
/** The evidence one integration relies on (`passing`) and additionally cites (`failed`, `amendments`). */
final case class IntegrationEvidence(passing: List[ApplicableValidation], failed: List[FailedRun], amendments: List[ArtifactId]) {
  def citations: ValidationCitations = ValidationCitations(passing.map(_.evidence.artifact).distinct, failed.map(_.artifact).distinct, amendments.distinct)
}

object IntegrationValidation {
  private val RebasedRule = "Rebased integration requires passing host checks on the exact rebased commit"
  private val FailureRule = "Validation failure does not record this author's failed run of the check on this candidate"
  private val InventoryRule = "Worker and reviewer validation must cover the configured check inventory"
  private val AttemptRule = "Rebased integration cites earlier rebase attempts that are not bounded failed runs of the configured checks on other commits"
  private val AmendmentRule = "Validation amendment does not record a bounded revalidation of this result's failed checks on its candidate"
  /** Runs of one check on one commit: the first and its automatic reruns. */
  val MaxAttempts = 3
  /** Governor-requested revalidation rounds of one check of one admitted result. */
  val MaxRevalidations = 3

  /** Round `round` of a result's revalidation has one identity, so host and server find the rounds without a mutable index. */
  def amendmentId(result: ArtifactId, round: Int): ArtifactId =
    ArtifactId(UUID.nameUUIDFromBytes(s"${result.value}:validation-amendment:$round".getBytes(UTF_8)))

  private def runs(entry: ValidationEvidence): Boolean =
    entry.failures.size < MaxAttempts && (entry.artifact :: entry.failures).distinct.size == entry.failures.size + 1

  /** `amendments` are the published rounds of `result` (stored as `id`) in order. Each round reran exactly the checks that were failed
    * before it, and no check has more rounds than its declaration allows. */
  def effective(project: ProjectId, session: SessionId, id: ArtifactId, result: ChildResult, declarations: List[ValidationCheck],
    amendments: List[PublishedAmendment]): EffectiveValidation = {
    val names = declarations.map(_.name)
    invalid(names.distinct == names && names.size <= IntegrationPolicy.MaxChecks && result.validation.map(_.check) == names, InventoryRule)
    val admitted = result.validation.zip(declarations).map { case (evidence, declaration) =>
      EffectiveCheck(ApplicableValidation(evidence, declaration, result.attempt), Nil)
    }
    val checks = amendments.zipWithIndex.foldLeft(admitted) { case (checks, (published, index)) =>
      val metadata = published.stored.metadata
      val amendment = published.value
      val failing = checks.filter(_.current.evidence.state == ValidationState.Failed)
      invalid(metadata.id == amendmentId(id, index + 1) && metadata.project == project && metadata.kind == ArtifactKind.Amendment &&
        metadata.mediaType == "application/json" && metadata.actor.session == session && metadata.actor.role == Role.Collector &&
        metadata.attempt == amendment.author && amendment.result == id && result.candidate.contains(amendment.candidate) &&
        amendment.round == index + 1 && failing.nonEmpty && amendment.validation.map(_.check) == failing.map(_.original.declaration.name) &&
        amendment.validation.forall(runs) && failing.forall(check => check.rounds.size < check.original.declaration.revalidations), AmendmentRule)
      checks.map(check => amendment.validation.find(_.check == check.original.declaration.name).fold(check)(evidence =>
        check.copy(rounds = check.rounds :+ ApplicableValidation(evidence, check.original.declaration, amendment.author))))
    }
    EffectiveValidation(result, checks, amendments.map(_.stored.metadata.id))
  }

  /** The checks a further revalidation round reruns: every currently failed check, each within its bound. */
  def revalidated(effective: EffectiveValidation): List[EffectiveCheck] = {
    def name(check: EffectiveCheck): String = check.original.declaration.name
    effective.checks.find(_.current.evidence.state == ValidationState.Unknown).foreach(check =>
      throw DomainFailure(Fault.Conflict(s"Check ${name(check)} is Unknown; a check whose cleanup is unconfirmed is never rerun")))
    val failing = effective.checks.filter(_.current.evidence.state == ValidationState.Failed)
    if (failing.isEmpty) throw DomainFailure(Fault.Conflict("Result has no failed check to revalidate"))
    failing.find(check => check.rounds.size >= check.original.declaration.revalidations).foreach(check =>
      throw DomainFailure(Fault.Limit(s"Revalidation limit reached for check ${name(check)}: ${check.original.declaration.revalidations} rounds")))
    failing
  }

  /** The reviewer's entries are the worker's (as admitted or as any round left them) or its own fresh observations; the worker's
    * effective checks and the reviewer's fresh ones must all pass. */
  def applicable(worker: EffectiveValidation, reviewer: ChildResult): IntegrationEvidence = {
    val declarations = worker.checks.map(_.original.declaration)
    invalid(reviewer.validation.map(_.check) == declarations.map(_.name), InventoryRule)
    val inherited = worker.checks.flatMap(check => (check.original :: check.rounds).map(_.evidence))
    val fresh = reviewer.validation.zip(declarations).collect {
      case (evidence, declaration) if !inherited.contains(evidence) => ApplicableValidation(evidence, declaration, reviewer.attempt)
    }
    val passing = worker.checks.map(_.current) ++ fresh
    invalid(passing.forall(_.evidence.state == ValidationState.Passed), "All worker and reviewer checks must pass")
    // A revalidated check cites its admission evidence whole; the runs inside a round are recorded by the cited amendment.
    val replaced = worker.checks.flatMap { check =>
      val evidence = check.original.evidence
      (if (check.rounds.isEmpty) evidence.failures else evidence.artifact :: evidence.failures).map(FailedRun(_, check.original.declaration, check.original.author))
    }
    val rerun = fresh.flatMap(value => value.evidence.failures.map(FailedRun(_, value.declaration, value.author)))
    IntegrationEvidence(passing, replaced ++ rerun, worker.amendments)
  }

  /** The host's own checks of a rebased commit: every configured check, at least one, each passed. */
  def rebased(rebase: IntegrationRebase, declarations: List[ValidationCheck]): List[ApplicableValidation] = {
    invalid(declarations.nonEmpty && rebase.validation.map(_.check) == declarations.map(_.name) &&
      rebase.validation.forall(_.state == ValidationState.Passed), RebasedRule)
    rebase.validation.zip(declarations).map { case (evidence, declaration) => ApplicableValidation(evidence, declaration, rebase.author) }
  }

  /** The check that forbids another rebase of one reviewed candidate onto one target head after the `failed` ones: as with a
    * governor-requested revalidation, a check may be rerun `revalidations` times after its first failure. */
  def exhausted(failed: List[RebaseAttempt], declarations: List[ValidationCheck]): Option[ValidationCheck] = {
    def failing(attempt: RebaseAttempt, check: ValidationCheck): Boolean =
      attempt.validation.exists(evidence => evidence.check == check.name && evidence.state == ValidationState.Failed)
    failed.lastOption.flatMap(last => declarations.find(check => failing(last, check) && failed.dropWhile(!failing(_, check)).size > check.revalidations))
  }

  /** The failed runs on the earlier merge commits of a rebase, each with the commit it ran on. Every earlier attempt ran the
    * configured checks on a commit of its own, at least one failed, and none was beyond its check's bound. */
  def attempts(rebase: IntegrationRebase, candidate: GitCommit, declarations: List[ValidationCheck]): List[(GitCommit, FailedRun)] = {
    val commits = rebase.failed.map(_.commit)
    invalid((candidate :: commits).distinct.size == commits.size + 1 && rebase.failed.forall(attempt =>
      attempt.commit.value.matches("[0-9a-f]{40}|[0-9a-f]{64}") && attempt.validation.map(_.check) == declarations.map(_.name) &&
        attempt.validation.forall(evidence => evidence.state != ValidationState.Unknown && runs(evidence)) &&
        attempt.validation.exists(_.state == ValidationState.Failed)) &&
      (1 to rebase.failed.size).forall(count => exhausted(rebase.failed.take(count), declarations).isEmpty), AttemptRule)
    rebase.failed.flatMap(attempt => attempt.validation.zip(declarations).flatMap { case (evidence, declaration) =>
      IntegrationPolicy.failedRuns(evidence).map(artifact => (attempt.commit, FailedRun(artifact, declaration, rebase.author)))
    })
  }

  def failures(expected: ApplicableValidation): List[FailedRun] = expected.evidence.failures.map(FailedRun(_, expected.declaration, expected.author))

  /** A settled run of the check on the candidate, published by the session's collector under the author. */
  private def observed(project: ProjectId, session: SessionId, candidate: GitCommit, declaration: ValidationCheck, author: AttemptId,
    metadata: ArtifactMetadata, observation: ValidationObservation): Boolean =
    metadata.project == project && metadata.attempt == author &&
      metadata.kind == ArtifactKind.Validation && metadata.mediaType == "application/json" &&
      metadata.actor.session == session && metadata.actor.role == Role.Collector && observation.check == declaration &&
      observation.candidate == candidate && observation.job.workspace.project == project && observation.job.workspace.owner == session &&
      observation.job.workspace.base == candidate && observation.job.phase == JobPhase.Settled

  private def established(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Boolean =
    metadata.id == expected.evidence.artifact && observed(project, session, candidate, expected.declaration, expected.author, metadata, observation) &&
      JobOutcome.observed(observation.job).succeeded

  def verify(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Unit =
    invalid(established(project, session, candidate, expected, metadata, observation),
      "Validation observation does not establish success for this author, candidate and check")

  def verifyRebased(project: ProjectId, session: SessionId, candidate: GitCommit, expected: ApplicableValidation,
    metadata: ArtifactMetadata, observation: ValidationObservation): Unit =
    invalid(established(project, session, candidate, expected, metadata, observation), RebasedRule)

  def verifyFailure(project: ProjectId, session: SessionId, candidate: GitCommit, expected: FailedRun,
    metadata: ArtifactMetadata, observation: ValidationObservation): Unit =
    invalid(metadata.id == expected.artifact && observed(project, session, candidate, expected.declaration, expected.author, metadata, observation) &&
      !JobOutcome.observed(observation.job).succeeded, FailureRule)

  private def decoded[A](codec: BaboonJsonCodec[A], value: ResolvedArtifact, rule: String): A = {
    val json = parser.parse(value.body).fold(throw _, identity)
    val observed = codec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    invalid(JsonRoundtrip.lossless(json, codec.encode(BaboonCodecContext.Default, observed)), rule)
    observed
  }

  def decode(value: ResolvedArtifact): ValidationObservation =
    decoded(ValidationObservation_JsonCodec, value, "Validation observation contains undeclared or noncanonical fields")

  def amendment(value: ResolvedArtifact): ValidationAmendment =
    decoded(ValidationAmendment_JsonCodec, value, "Validation amendment contains undeclared or noncanonical fields")
}
