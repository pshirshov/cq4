package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.host.{ChildContracts, DispatchProjection, HostFiles}
import io.circe.Json
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class ChildContractsLocal extends AnyWordSpec {
  "Child result contracts (Behavioral Active Blackbox Atomic)" should {
    "separate evidence and proposal roles, provenance and non-candidate validation" in {
      val project = ProjectId(UUID.randomUUID())
      val member = ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(1))
      val evidence = Evidence("Declared observation", EvidenceOrigin.ModelDeclared, Nil)
      val finding = EvidenceMember(member.id, EvidenceDisposition.Findings, "Observation", List(evidence), Nil, List("Verify with a probe"))
      def encoded(value: ChildReport): Json = ChildReport_JsonCodec.encode(BaboonCodecContext.Default, value)
      val evidenceReport = ChildReport.Evidence(List(finding))
      val modes = List(DispatchWork.Explorer(ExplorerMode.Investigate), DispatchWork.Explorer(ExplorerMode.Research), DispatchWork.Worker(WorkerMode.Probe))
      modes.foreach { work =>
        assert(ChildContracts.report(work, List(member), encoded(evidenceReport)) == evidenceReport)
        intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Evidence(List(finding.copy(evidence = Nil))))))
        List(EvidenceOrigin.HostObserved, EvidenceOrigin.HumanReported).foreach { origin =>
          intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Evidence(List(finding.copy(evidence = List(evidence.copy(origin = origin))))))))
        }
        intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Evidence(List(finding.copy(uncertainties = List.fill(33)("Uncertain")))))))
        intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Evidence(List(finding.copy(summary = ""))))))
        intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Evidence(List(finding.copy(item = member.id.copy(number = 2)))))))
      }
      val plan = ChildReport.Plan(List(PlanMember(member.id, PlanDisposition.Abstained, "No proposal")), None, Nil)
      val review = ChildReport.Review(List(ReviewMember(member.id, ReviewVerdict.Accepted, Nil)), None)
      val cases = modes.map(_ -> evidenceReport) ++ List(DispatchWork.Planner() -> plan,
        DispatchWork.Reviewer(ReviewerMode.Plan) -> review, DispatchWork.Reviewer(ReviewerMode.Audit) -> review)
      cases.foreach { case (work, report) =>
        val request = DispatchRequest(RequestId(UUID.randomUUID()), work, Harness.Codex, List(member), Nil, Nil,
          Some(ArtifactId(UUID.randomUUID())), Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
        val result = ChildResult(AttemptId(UUID.randomUUID()), request, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
        ChildContracts.result(project, result)
        intercept[IllegalArgumentException](ChildContracts.result(project, result.copy(candidate = Some(GitCommit("b" * 40)))))
        intercept[IllegalArgumentException](ChildContracts.result(project, result.copy(validation = List(ValidationEvidence("check", ValidationState.Passed, ArtifactId(UUID.randomUUID()))))))
        intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Worker(WorkerMode.Implement), List(member), encoded(report)))
      }
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Planner(), List(member), encoded(evidenceReport)))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Explorer(ExplorerMode.Research), List(member), encoded(plan)))
    }

    "reject malformed evidence citations through the shared domain rules" in {
      val project = ProjectId(UUID.randomUUID())
      val member = ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(1))
      val malformed = List(Citation.Url("javascript:invalid"), Citation.File("", None), Citation.Commit("repository", "not-a-commit"))
      malformed.foreach { citation =>
        val report = ChildReport.Evidence(List(EvidenceMember(member.id, EvidenceDisposition.Findings, "Declared observation",
          List(Evidence("Evidence", EvidenceOrigin.ModelDeclared, List(citation))), Nil, Nil)))
        val failure = intercept[cq.core.DomainFailure](ChildContracts.report(DispatchWork.Explorer(ExplorerMode.Investigate), List(member),
          ChildReport_JsonCodec.encode(BaboonCodecContext.Default, report)))
        assert(failure.fault.isInstanceOf[Fault.Invalid])
      }
    }

    "keep evidence, abstentions and review follow-up narratives behind bounded projections" in {
      val project = ProjectId(UUID.randomUUID())
      val members = (1L to 16L).map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))).toList
      val handle = ArtifactId(UUID.randomUUID())
      val attempt = AttemptId(UUID.randomUUID())
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Planner(), Harness.Codex, members, Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val initial = DispatchStatus(request.request, attempt, DispatchPhase.Running, Some(JobPhase.Settled), members.map(_.id),
        DispatchProjection.EmptyCounts, ChildNext.Wait, None, None, None, false, true, None)
      val reports = List(
        ChildReport.Evidence(members.map(ref => EvidenceMember(ref.id, EvidenceDisposition.Inconclusive, "🙂" * 3000, Nil, Nil, Nil))),
        ChildReport.Plan(members.map(ref => PlanMember(ref.id, PlanDisposition.Abstained, "🙂" * 3000)), None, Nil),
        ChildReport.Review(members.map(ref => ReviewMember(ref.id, ReviewVerdict.ChangesRequested, List("🙂" * 3000))), None))
      reports.foreach { report =>
        val result = ChildResult(attempt, request, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
        val projected = DispatchProjection.completed(initial, result, handle)
        assert(projected.result.contains(handle) && projected.blocker.contains("🙂" * 300) && projected.detailsOmitted)
        assert(projected.counts.abstained == 16 || projected.counts.changesRequested == 16)
        assert(HostFiles.encode(DispatchStatus_JsonCodec, projected).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 12 * 1024)
      }
    }

    "reject extra fields, foreign or missing members, role substitution and unsupported candidate assertions" in {
      val project = ProjectId(UUID.randomUUID())
      val member = ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(1))
      val work = DispatchWork.Worker(WorkerMode.Implement)
      val report = ChildReport.Work(List(WorkMember(member.id, WorkDisposition.CandidateReady, "Implemented", Nil)))
      def encoded(value: ChildReport): Json = ChildReport_JsonCodec.encode(BaboonCodecContext.Default, value)
      assert(ChildContracts.report(work, List(member), encoded(report)) == report)
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(report).deepMerge(Json.obj("authority" -> Json.True))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Work(Nil))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Work(report.members ++ report.members))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member.copy(id = member.id.copy(number = 2))), encoded(report)))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Reviewer(ReviewerMode.Candidate), List(member), encoded(report)))
      val changes = ChildReport.Review(List(ReviewMember(member.id, ReviewVerdict.ChangesRequested, Nil)), None)
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Reviewer(ReviewerMode.Candidate), List(member), encoded(changes)))
      val request = DispatchRequest(RequestId(UUID.randomUUID()), work, Harness.Codex, List(member), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val wire = DispatchRequest_JsonCodec.encode(BaboonCodecContext.Default, request)
      assert(ChildContracts.decodeRequest(project, wire) == request)
      val missing = intercept[RuntimeException](ChildContracts.decodeRequest(project, Json.fromJsonObject(wire.asObject.get.remove("previous"))))
      assert(missing.getMessage.contains("previous"))
      intercept[IllegalArgumentException](ChildContracts.decodeRequest(project, wire.deepMerge(Json.obj("prompt" -> Json.fromString("Copied narrative")))))
      intercept[IllegalArgumentException](ChildContracts.decodeRequest(project, wire.deepMerge(Json.obj("limits" -> Json.obj("prompt" -> Json.fromString("Nested narrative"))))))
      val result = ChildResult(AttemptId(UUID.randomUUID()), request, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
      intercept[IllegalArgumentException](ChildContracts.result(project, result))
      ChildContracts.result(project, result.copy(candidate = Some(GitCommit("b" * 40))))
    }
  }
}
