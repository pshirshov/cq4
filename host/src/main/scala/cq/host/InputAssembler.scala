package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{DomainFailure, Scope}
import io.circe.parser
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Clock, Duration}
import java.util.HexFormat

final class InputAssembler(api: ServerApi, owner: Scope, clock: Clock) {
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val AssemblyNanos = Duration.ofSeconds(60).toNanos
  private val PageCodePoints = 8192
  private val MaxArtifactBytes = 128 * 1024
  require(owner.actor.role == Role.Governor, "Input assembly requires governing authority")

  def assemble(request: DispatchRequest): ChildInput = {
    ChildContracts.request(owner.project, request)
    val began = System.nanoTime()
    def call(command: Command): Result = {
      require(System.nanoTime() - began < AssemblyNanos, "Input assembly deadline exceeded")
      api.call(command) match {
        case Result.Failed(fault) => throw DomainFailure(fault)
        case value => value
      }
    }
    def claim(): Unit = call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Renew(request.fence, ClaimMillis)))) match {
      case Result.Claimed(value) => require(value.fence == request.fence && value.owner == owner.actor &&
        value.members == request.members.map(_.id).toSet && !value.released && value.expiresAt > clock.millis(),
        "Dispatch claim does not cover its exact governing assignment")
      case _ => throw new IllegalStateException("Claim renewal returned an unexpected result")
    }
    def item(reference: ItemRevision): ItemView = call(Command.Read(ReadInput(owner.project, ReadSelection.ItemDetail(reference.id)))) match {
      case Result.Detail(value) =>
        require(value.item.id == reference.id && value.item.revision == reference.revision, "Dispatch item revision changed")
        value
      case _ => throw new IllegalStateException("Item read returned an unexpected result")
    }
    def artifact(id: ArtifactId): ResolvedArtifact = {
      val metadata = call(Command.Read(ReadInput(owner.project, ReadSelection.ArtifactInfo(id)))) match {
        case Result.ArtifactInfo(value) => value
        case _ => throw new IllegalStateException("Artifact metadata read returned an unexpected result")
      }
      require(metadata.id == id && metadata.project == owner.project && metadata.bytes >= 0 && metadata.bytes <= MaxArtifactBytes &&
        metadata.codePoints >= 0 && metadata.codePoints <= metadata.bytes, "Input artifact exceeds its scope or byte bound")
      val body = new StringBuilder
      var offset = 0
      while (offset < metadata.codePoints) {
        val page = call(Command.Read(ReadInput(owner.project, ReadSelection.ArtifactText(id, offset, PageCodePoints)))) match {
          case Result.ArtifactText(value) => value
          case _ => throw new IllegalStateException("Artifact page read returned an unexpected result")
        }
        val count = page.text.codePointCount(0, page.text.length)
        require(page.metadata == metadata && page.offset == offset && count > 0 && count <= PageCodePoints &&
          page.next == offset + count && page.next <= metadata.codePoints && page.hasMore == (page.next < metadata.codePoints),
          "Input artifact pagination is inconsistent")
        body.append(page.text)
        require(body.length <= MaxArtifactBytes, "Input artifact exceeds its decoded bound")
        offset = page.next
      }
      val text = body.toString
      require(UTF_8.newEncoder().canEncode(text), "Input artifact contains malformed Unicode")
      val bytes = text.getBytes(UTF_8)
      require(bytes.length == metadata.bytes && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == metadata.sha256,
        "Input artifact digest or length differs from its metadata")
      ResolvedArtifact(metadata, text)
    }
    claim()
    val members = request.members.map(item)
    val guidance = request.guidance.map(item)
    val artifacts = request.artifacts.map(artifact)
    val previous = request.previous.map { id =>
      val stored = artifact(id)
      require(stored.metadata.kind == ArtifactKind.Result && stored.metadata.mediaType == "application/json", "Prior handle must contain a structured child result")
      val json = parser.parse(stored.body).fold(throw _, identity)
      val value = ChildResult_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
      require(ChildResult_JsonCodec.encode(BaboonCodecContext.Default, value) == json, "Prior result contains undeclared or noncanonical fields")
      ChildContracts.result(owner.project, value)
      val admission = call(Command.Read(ReadInput(owner.project, ReadSelection.Admission(value.attempt)))) match {
        case Result.Admission(record) => record
        case _ => throw new IllegalStateException("Result admission read returned an unexpected result")
      }
      require(admission.artifact == stored.metadata && admission.fence == value.request.fence &&
        admission.members == value.request.members && admission.decision == AdmissionDecision.Accepted(),
        "Prior result has no matching accepted admission")
      require(value.attempt == stored.metadata.attempt && value.request.members.toSet == request.members.toSet, "Prior result belongs to another attempt or assignment revision")
      if (ChildContracts.role(request.work) == Role.Reviewer)
        require(value.report.isInstanceOf[ChildReport.Work] && value.candidate.nonEmpty, "Candidate review requires a worker result with a candidate")
      value
    }
    val input = ChildInput(owner.project, request, members, guidance, artifacts, previous)
    require(HostFiles.encode(ChildInput_JsonCodec, input).getBytes(UTF_8).length <= ChildContracts.MaxInputBytes, "Assembled input exceeds its byte bound")
    claim()
    input
  }
}
