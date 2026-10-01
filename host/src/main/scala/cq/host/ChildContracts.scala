package cq.host

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.JsonRoundtrip
import cq.core.{CohortAssessmentPolicy, LedgerPolicy, ProposalPolicy}
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

object ChildContracts {
  val MaxInputBytes = 192 * 1024
  val MaxResultBytes = 128 * 1024
  private val MaxRequestBytes = 16 * 1024
  private val MaxMembers = 16
  private val MaxGuidance = 16
  private val MaxArtifacts = 8
  private val MaxNarrativeCharacters = 8192
  private val MaxFindings = 32
  private val MaxOutputBytes = 32 * 1024 * 1024

  /** Every dispatchable role and mode, in model order. */
  val Works: List[DispatchWork] = ExplorerMode.all.map(DispatchWork.Explorer.apply) ++ List(DispatchWork.Planner()) ++
    WorkerMode.all.map(DispatchWork.Worker.apply) ++ ReviewerMode.all.map(DispatchWork.Reviewer.apply)

  def role(work: DispatchWork): Role = work match {
    case _: DispatchWork.Explorer => Role.Explorer
    case _: DispatchWork.Planner => Role.Planner
    case _: DispatchWork.Worker => Role.Worker
    case _: DispatchWork.Reviewer => Role.Reviewer
  }

  def reportTag(work: DispatchWork): String = work match {
    case _: DispatchWork.Explorer | DispatchWork.Worker(WorkerMode.Probe) => "Evidence"
    case _: DispatchWork.Planner => "Plan"
    case _: DispatchWork.Worker => "Work"
    case _: DispatchWork.Reviewer => "Review"
  }

  def decodeRequest(project: ProjectId, json: Json): DispatchRequest = {
    require(json.noSpaces.getBytes(UTF_8).length <= MaxRequestBytes, "Dispatch request exceeds its byte bound")
    val value = DispatchRequest_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    require(JsonRoundtrip.lossless(json, DispatchRequest_JsonCodec.encode(BaboonCodecContext.Default, value)), "Dispatch request contains undeclared or noncanonical fields")
    request(project, value)
    value
  }

  def request(project: ProjectId, value: DispatchRequest): Unit = {
    require(value.members.nonEmpty && value.members.size <= MaxMembers && value.guidance.size <= MaxGuidance && value.artifacts.size <= MaxArtifacts,
      "Dispatch reference count exceeds its bounds")
    val references = value.members ++ value.guidance
    require(references.forall(ref => ref.id.project == project && ref.id.number > 0 && ref.revision.value > 0), "Dispatch requires valid project-local item revisions")
    require(references.map(_.id).distinct.size == references.size && value.artifacts.distinct.size == value.artifacts.size,
      "Dispatch references must be distinct")
    require(value.fence.generation > 0, "Dispatch requires a claim fence")
    require(!Set[DispatchWork](DispatchWork.Reviewer(ReviewerMode.Candidate), DispatchWork.Reviewer(ReviewerMode.Plan))(value.work) || value.previous.nonEmpty,
      "Candidate or plan review requires its previous result handle")
    require(HostFiles.encode(DispatchRequest_JsonCodec, value).getBytes(UTF_8).length <= MaxRequestBytes, "Dispatch request exceeds its byte bound")
    val limits = value.limits
    ExecutionLimits(Duration.ofMillis(limits.startupMillis), Duration.ofMillis(limits.executionMillis), Duration.ofMillis(limits.heartbeatMillis),
      Duration.ofMillis(limits.graceMillis), Duration.ofMillis(limits.killMillis), limits.outputBytes)
    require(limits.outputBytes <= MaxOutputBytes, "Dispatch output exceeds its retained byte bound")
  }

  def report(work: DispatchWork, members: List[ItemRevision], json: Json): ChildReport = {
    require(json.noSpaces.getBytes(UTF_8).length <= MaxResultBytes, "Child report exceeds its byte bound")
    val value = ChildReport_JsonCodec.decode(BaboonCodecContext.Default, json).fold(throw _, identity)
    require(JsonRoundtrip.lossless(json, ChildReport_JsonCodec.encode(BaboonCodecContext.Default, value)), "Child report contains undeclared or noncanonical fields")
    def narrative(text: String): Unit = require(text.trim.nonEmpty && text.length <= MaxNarrativeCharacters, "Invalid child narrative bound")
    val reported = (work, value) match {
      case (assigned, ChildReport.Evidence(entries)) if reportTag(assigned) == "Evidence" =>
        entries.foreach { entry =>
          narrative(entry.summary)
          require(entry.evidence.size <= MaxFindings && entry.uncertainties.size <= MaxFindings && entry.requestedProbes.size <= MaxFindings,
            "Evidence report exceeds its entry bounds")
          require(entry.disposition != EvidenceDisposition.Findings || entry.evidence.nonEmpty, "Findings require evidence")
          entry.evidence.foreach { evidence =>
            narrative(evidence.description)
            require(evidence.origin == EvidenceOrigin.ModelDeclared && evidence.citations.size <= MaxFindings,
              "Child evidence cannot declare host or human provenance")
            evidence.citations.foreach(LedgerPolicy.validateCitation)
          }
          (entry.uncertainties ++ entry.requestedProbes).foreach(narrative)
        }
        entries.map(_.item)
      case (_: DispatchWork.Planner, plan: ChildReport.Plan) =>
        plan.members.foreach(entry => narrative(entry.summary))
        CohortAssessmentPolicy.shape(members, plan)
        plan.members.map(_.item)
      case (assigned: DispatchWork.Worker, ChildReport.Work(entries)) if assigned.mode != WorkerMode.Probe =>
        entries.foreach { entry =>
          narrative(entry.summary)
          require(entry.evidence.size <= WorkspaceEvidence.MaxFiles &&
            entry.evidence.forall(path => path.nonEmpty && path.length <= WorkspaceEvidence.MaxPathCharacters), "Invalid named evidence bounds")
        }
        entries.map(_.item)
      case (_: DispatchWork.Reviewer, ChildReport.Review(entries, _)) =>
        entries.foreach { entry =>
          require(entry.findings.size <= MaxFindings && (entry.verdict == ReviewVerdict.Accepted || entry.findings.nonEmpty),
            "Non-accepted review requires bounded findings")
          entry.findings.foreach(narrative)
        }
        entries.map(_.item)
      case _ => throw new IllegalArgumentException("Child report does not match its assigned role")
    }
    require(reported.distinct.size == reported.size && reported.toSet == members.map(_.id).toSet, "Child report must cover each assigned member exactly once")
    ProposalPolicy.prepare(work, members, value)
    value
  }

  def result(project: ProjectId, value: ChildResult): Unit = {
    request(project, value.request)
    report(value.request.work, value.request.members, ChildReport_JsonCodec.encode(BaboonCodecContext.Default, value.report))
    require((value.base :: value.candidate.toList).forall(_.value.matches("[0-9a-f]{40}|[0-9a-f]{64}")), "Result commits must be full object IDs")
    val needsCandidate = value.report match {
      case ChildReport.Work(members) => members.exists(_.disposition == WorkDisposition.CandidateReady)
      case _: ChildReport.Review => value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate)
      case _: ChildReport.Evidence | _: ChildReport.Plan => false
    }
    require(!needsCandidate || value.candidate.nonEmpty, "Result requires its exact candidate commit")
    require(value.report.isInstanceOf[ChildReport.Work] || value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) ||
      (value.candidate.isEmpty && value.validation.isEmpty), "Non-candidate results cannot inherit candidate validation")
    require(value.validation.size <= 8 && value.validation.map(_.check).distinct.size == value.validation.size &&
      value.validation.forall(_.check.matches("[a-z][a-z0-9-]{0,49}")), "Invalid host validation inventory")
    val evidence = value.evidence
    require(value.request.work.isInstanceOf[DispatchWork.Worker] || (evidence.files.isEmpty && evidence.omitted.isEmpty), "Only worker results retain workspace evidence")
    require(evidence.files.size <= WorkspaceEvidence.MaxFiles && evidence.files.map(_.path).distinct.size == evidence.files.size &&
      evidence.files.forall(file => file.path.nonEmpty && file.path.length <= WorkspaceEvidence.MaxPathCharacters && file.bytes >= 0) &&
      evidence.omitted.size <= WorkspaceEvidence.MaxOmitted + 1 && evidence.omitted.forall(_.length <= WorkspaceEvidence.MaxPathCharacters),
      "Invalid retained evidence inventory")
    require(HostFiles.encode(ChildResult_JsonCodec, value).getBytes(UTF_8).length <= MaxResultBytes, "Stored child result exceeds its byte bound")
  }
}
