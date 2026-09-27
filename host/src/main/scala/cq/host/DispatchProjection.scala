package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8

object DispatchProjection {
  val EmptyCounts: ChildCounts = ChildCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  private val MaxBytes = 12 * 1024
  private val MaxBlockerCodePoints = 300
  def concise(value: String): String = value.substring(0, value.offsetByCodePoints(0, value.codePointCount(0, value.length).min(MaxBlockerCodePoints)))
  def pending(ticket: DispatchTicket): DispatchStatus = DispatchStatus(ticket.request.request, ticket.attempt.id,
    DispatchPhase.Preparing, None, ticket.request.members.map(_.id), EmptyCounts, ChildNext.Wait, None, None, false, false)
  def completed(previous: DispatchStatus, result: ChildResult, handle: ArtifactId): DispatchStatus = {
    val failures = result.validation.count(_.state == ValidationState.Failed)
    val unknown = result.validation.count(_.state == ValidationState.Unknown)
    val (counts, next, blocker) = result.report match {
      case ChildReport.Evidence(members) =>
        val found = members.count(_.disposition == EvidenceDisposition.Findings)
        val blocked = members.count(_.disposition == EvidenceDisposition.Blocked)
        val failed = members.count(_.disposition == EvidenceDisposition.Failed)
        val inconclusive = members.count(_.disposition == EvidenceDisposition.Inconclusive)
        (ChildCounts(0, 0, 0, blocked, failed, failures, unknown, 0, found, inconclusive, 0),
          if (found > 0) ChildNext.Plan else if (blocked > 0) ChildNext.ResolveBlocker else ChildNext.InspectEvidence,
          members.find(_.disposition != EvidenceDisposition.Findings).map(_.summary))
      case ChildReport.Plan(members, proposal, assessments) =>
        val proposed = members.count(_.disposition == PlanDisposition.Proposed)
        val blocked = members.count(_.disposition == PlanDisposition.Blocked)
        val abstained = members.count(_.disposition == PlanDisposition.Abstained)
        val assessed = members.count(_.disposition == PlanDisposition.Assessed)
        (ChildCounts(0, 0, 0, blocked, 0, failures, unknown, proposed, 0, abstained, assessed),
          if (proposal.nonEmpty) ChildNext.ConsiderProposal else if (assessments.nonEmpty) ChildNext.ConsiderGrouping else if (blocked > 0) ChildNext.ResolveBlocker else ChildNext.InspectEvidence,
          members.find(value => Set(PlanDisposition.Blocked, PlanDisposition.Abstained)(value.disposition)).map(_.summary)
            .orElse(assessments.find(_.compatibility != CohortCompatibility.Compatible).map(value => s"${value.compatibility}: ${value.interference}")))
      case ChildReport.Work(members) =>
        val ready = members.count(_.disposition == WorkDisposition.CandidateReady)
        val blocked = members.count(_.disposition == WorkDisposition.Blocked)
        val failed = members.count(_.disposition == WorkDisposition.Failed)
        (ChildCounts(ready, 0, 0, blocked, failed, failures, unknown, 0, 0, 0, 0),
          if (ready > 0) ChildNext.Review else if (blocked > 0) ChildNext.ResolveBlocker else ChildNext.Retry,
          members.find(_.disposition != WorkDisposition.CandidateReady).map(_.summary))
      case ChildReport.Review(members, proposal) =>
        val accepted = members.count(_.verdict == ReviewVerdict.Accepted)
        val changes = members.count(_.verdict == ReviewVerdict.ChangesRequested)
        val blocked = members.count(_.verdict == ReviewVerdict.Blocked)
        (ChildCounts(0, accepted, changes, blocked, 0, failures, unknown, 0, 0, 0, 0),
          if (proposal.nonEmpty) ChildNext.ConsiderProposal else if (changes > 0) ChildNext.Revise else if (blocked > 0) ChildNext.ResolveBlocker else ChildNext.ConsiderAcceptance,
          members.find(_.verdict != ReviewVerdict.Accepted).flatMap(_.findings.headOption))
    }
    bounded(previous.copy(phase = DispatchPhase.Completed, counts = counts,
      next = if (unknown > 0) ChildNext.InspectEvidence else if (failures > 0) ChildNext.Revise else next,
      blocker = result.validation.find(_.state != ValidationState.Passed).map(value => s"Host check ${value.check}: ${value.state}").orElse(blocker).map(concise),
      result = Some(handle), detailsOmitted = true))
  }
  def bounded(value: DispatchStatus): DispatchStatus = {
    require(HostFiles.encode(DispatchStatus_JsonCodec, value).getBytes(UTF_8).length <= MaxBytes, "Dispatch projection exceeds its byte bound")
    value
  }
}
