package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.JsonRoundtrip
import io.circe.parser
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

final case class AdmittedResult(value: ChildResult, metadata: ArtifactMetadata, admission: ResultAdmission)
final case class ExecutedResult(result: AdmittedResult, input: ChildExecutionInput)

final class ArtifactReader(call: Command => Result, project: ProjectId) {
  private val PageCodePoints = 8192
  private val MaxArtifactBytes = 128 * 1024
  private val drafts = new HistoricalDrafts(call, project)
  def read(id: ArtifactId): ResolvedArtifact = readBounded(id, MaxArtifactBytes)

  private def readBounded(id: ArtifactId, maximum: Int): ResolvedArtifact = {
    val metadata = call(Command.Read(ReadInput(project, ReadSelection.ArtifactInfo(id)))) match {
      case Result.ArtifactInfo(value) => value
      case _ => throw new IllegalStateException("Artifact metadata read returned an unexpected result")
    }
    require(metadata.id == id && metadata.project == project && metadata.bytes >= 0 && metadata.bytes <= maximum &&
      metadata.codePoints >= 0 && metadata.codePoints <= metadata.bytes, "Input artifact exceeds its scope or byte bound")
    val body = new StringBuilder
    var offset = 0
    while (offset < metadata.codePoints) {
      val page = call(Command.Read(ReadInput(project, ReadSelection.ArtifactText(id, offset, PageCodePoints)))) match {
        case Result.ArtifactText(value) => value
        case _ => throw new IllegalStateException("Artifact page read returned an unexpected result")
      }
      val count = page.text.codePointCount(0, page.text.length)
      require(page.metadata == metadata && page.offset == offset && count > 0 && count <= PageCodePoints &&
        page.next == offset + count && page.next <= metadata.codePoints && page.hasMore == (page.next < metadata.codePoints),
        "Input artifact pagination is inconsistent")
      body.append(page.text)
      require(body.length <= maximum, "Input artifact exceeds its decoded bound")
      offset = page.next
    }
    val text = body.toString
    require(UTF_8.newEncoder().canEncode(text), "Input artifact contains malformed Unicode")
    val bytes = text.getBytes(UTF_8)
    require(bytes.length == metadata.bytes && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == metadata.sha256,
      "Input artifact digest or length differs from its metadata")
    ResolvedArtifact(metadata, text)
  }

  def result(id: ArtifactId): AdmittedResult = {
    val stored = read(id)
    require(stored.metadata.kind == ArtifactKind.Result && stored.metadata.mediaType == "application/json", "Prior handle must contain a structured child result")
    val json = parser.parse(stored.body).fold(throw _, identity)
    val value = ChildResult_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    require(JsonRoundtrip.lossless(json, ChildResult_JsonCodec.encode(BaboonCodecContext.Default, value)), "Prior result contains undeclared or noncanonical fields")
    ChildContracts.result(project, value)
    val admission = call(Command.Read(ReadInput(project, ReadSelection.Admission(value.attempt)))) match {
      case Result.Admission(record) => record
      case _ => throw new IllegalStateException("Result admission read returned an unexpected result")
    }
    require(admission.artifact == stored.metadata && admission.fence == value.request.fence &&
      admission.members == value.request.members && admission.decision == AdmissionDecision.Accepted(),
      "Prior result has no matching accepted admission")
    require(value.attempt == stored.metadata.attempt, "Prior result belongs to another attempt")
    AdmittedResult(value, stored.metadata, admission)
  }

  private def execution(admitted: AdmittedResult): ChildExecutionInput = {
    val value = admitted.value
    val stored = readBounded(NativeArtifacts.id(value.attempt, "input"), cq.core.ArtifactService.MaxBytes)
    require(stored.metadata.kind == ArtifactKind.Input && stored.metadata.mediaType == "application/json" &&
      stored.metadata.attempt == value.attempt && stored.metadata.actor == admitted.metadata.actor && stored.metadata.actor.role == Role.Collector,
      "Execution input has different host provenance")
    val json = parser.parse(stored.body).fold(throw _, identity)
    val input = ChildExecutionInput_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    require(JsonRoundtrip.lossless(json, ChildExecutionInput_JsonCodec.encode(BaboonCodecContext.Default, input)) && input.input.project == project &&
      input.input.request == value.request && input.base == value.base, "Input differs from its admitted execution")
    require(input.input.members.map(view => ItemRevision(view.item.id, view.item.revision)) == value.request.members,
      "Input members differ from the frozen assignment")
    input
  }

  def review(id: ArtifactId): ExecutedResult = {
    val admitted = result(id)
    val input = execution(admitted)
    require(admitted.value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) &&
      input.input.previous.exists(value => value.candidate == admitted.value.candidate && value.candidate.contains(input.base) &&
        drafts.unchanged(value.request.members, admitted.value.request.members)), "Review input differs from its candidate subject")
    ExecutedResult(admitted, input)
  }

  def assessment(id: ArtifactId): ExecutedResult = {
    val admitted = result(id)
    val plan = admitted.value.report match {
      case report: ChildReport.Plan if report.assessments.nonEmpty => report
      case _ => throw new IllegalArgumentException("Assessment handle requires a Planner compatibility report")
    }
    val input = execution(admitted)
    val items = input.input.members.map(view => view.item.id -> view.item).toMap
    cq.core.CohortAssessmentPolicy.criteria(plan, items.apply)
    cq.core.CohortAssessmentPolicy.checks(plan, input.checks)
    ExecutedResult(admitted, input)
  }
}
