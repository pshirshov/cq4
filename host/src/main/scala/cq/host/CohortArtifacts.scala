package cq.host

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.{LedgerPolicy, Scope}
import io.circe.{Json, parser}

final case class CohortArtifactFingerprint(kind: ArtifactKind, mediaType: String, content: Json)
final case class CohortResultFingerprint(value: ChildResult, validation: List[CohortArtifactFingerprint])

object CohortArtifacts {
  private val MaxNativeBytes = 32L * 1024 * 1024
  private val MaxNativeParts = 256
  private def encode[A](codec: BaboonJsonCodec[A], value: A): Json = codec.encode(BaboonCodecContext.Default, value)
  private def decode[A](codec: BaboonJsonCodec[A], stored: ResolvedArtifact): A = {
    require(stored.metadata.mediaType == "application/json", "Structured cohort evidence requires JSON")
    val json = parser.parse(stored.body).fold(throw _, identity)
    val value = codec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    require(encode(codec, value) == json, "Structured cohort evidence contains undeclared or noncanonical fields")
    value
  }

  private def manifest(stored: ResolvedArtifact, publisher: ArtifactMetadata): Json = {
    require(stored.metadata.project == publisher.project && stored.metadata.attempt == publisher.attempt &&
      stored.metadata.actor == publisher.actor && stored.metadata.kind == ArtifactKind.Transcript,
      "Validation output has different host provenance")
    val value = decode(NativeManifest_JsonCodec, stored)
    require(value.encoding == "base64" && value.bytes >= 0 && value.bytes <= MaxNativeBytes && value.sha256.matches("[0-9a-f]{64}") &&
      value.parts.size <= MaxNativeParts && value.parts.distinct.size == value.parts.size && (value.bytes == 0) == value.parts.isEmpty,
      "Validation output manifest violates its native evidence bounds")
    Json.obj("mediaType" -> Json.fromString(value.mediaType), "bytes" -> Json.fromLong(value.bytes), "sha256" -> Json.fromString(value.sha256))
  }

  private def validation(stored: ResolvedArtifact): ValidationObservation = {
    require(stored.metadata.kind == ArtifactKind.Validation && stored.metadata.actor.role == Role.Collector,
      "Validation observation requires host collector provenance")
    val value = decode(ValidationObservation_JsonCodec, stored)
    require(value.job.workspace.project == stored.metadata.project && value.job.workspace.owner == stored.metadata.actor.session &&
      value.job.workspace.base == value.candidate, "Validation workspace differs from its publisher or candidate")
    value
  }

  def result(admitted: AdmittedResult, read: ArtifactId => ResolvedArtifact): CohortResultFingerprint = {
    val observations = admitted.value.validation.map { evidence =>
      val stored = read(evidence.artifact)
      val value = validation(stored)
      require(stored.metadata.project == admitted.metadata.project && stored.metadata.actor == admitted.metadata.actor &&
        value.check.name == evidence.check && admitted.value.candidate.contains(value.candidate),
        "Result validation differs from its host publisher, check or candidate")
      apply(stored, read)
    }
    CohortResultFingerprint(admitted.value, observations)
  }

  def apply(stored: ResolvedArtifact, read: ArtifactId => ResolvedArtifact): CohortArtifactFingerprint = {
    val content = stored.metadata.kind match {
      case ArtifactKind.Validation =>
        val value = validation(stored)
        Json.obj("check" -> encode(ValidationCheck_JsonCodec, value.check), "candidate" -> encode(GitCommit_JsonCodec, value.candidate),
          "target" -> Json.fromString(value.job.target.toString), "phase" -> Json.fromString(value.job.phase.toString),
          "exit" -> value.job.exit.fold(Json.Null)(encode(JobExit_JsonCodec, _)), "problem" -> value.job.problem.fold(Json.Null)(Json.fromString),
          "stdout" -> manifest(read(value.stdout), stored.metadata), "stderr" -> manifest(read(value.stderr), stored.metadata))
      case ArtifactKind.Combination =>
        val value = decode(CombinationPlan_JsonCodec, stored)
        require(stored.metadata.actor.role == Role.Collector && stored.metadata.actor.session == value.owner.session,
          "Combination plan requires its host collector publisher")
        CombinationPlans.validate(value, Scope(stored.metadata.project, value.owner), stored.metadata.attempt, value.repository, value.target)
        Json.obj("repository" -> Json.fromString(value.repository), "target" -> Json.fromString(value.target),
          "observedTarget" -> encode(GitCommit_JsonCodec, value.observedTarget), "candidate" -> encode(GitCommit_JsonCodec, value.candidate),
          "members" -> Json.fromValues(value.members.map(_.id).sortBy(LedgerPolicy.key).map(encode(ItemId_JsonCodec, _))))
      case ArtifactKind.Result | ArtifactKind.Selection => Json.Null
      case _ => Json.fromString(stored.body)
    }
    CohortArtifactFingerprint(stored.metadata.kind, stored.metadata.mediaType, content)
  }
}
