package cq.server

import cq.api.*
import cq.core.{CohortAssessmentPolicy, DomainFailure, ProposalPolicy}
import cq.host.{ChildContracts, DispatchProjection, HostFiles}
import io.circe.{Json, JsonObject}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CohortAssessmentLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.randomUUID())
  private val members = (1L to 3L).map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))).toList
  private val checks = List(ValidationCheck("contract", List("verify"), 1000, 4096))
  private def assessment: CohortAssessment = CohortAssessment(CohortCompatibility.Compatible, "Share one parser change",
    "No dependency between members", "All members use the same parser; their acceptance checks remain separate",
    members.map(member => CohortMemberAssessment(member, List(CohortCriterion(0, Set("contract"), "Inspect this member's expected result")))))
  private def report: ChildReport.Plan = ChildReport.Plan(members.map(member => PlanMember(member.id, PlanDisposition.Assessed, "Assessed compatibility")), None, List(assessment))
  private def rejected(value: ChildReport.Plan): Unit = {
    val fault = intercept[DomainFailure](ChildContracts.report(DispatchWork.Planner(), members,
      ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, value))).fault
    assert(fault.isInstanceOf[Fault.Invalid])
  }

  "Cohort assessment contracts (Behavioral Active Blackbox Atomic)" should {
    "accept arbitrary check-set order while preserving ordered report members" in {
      val names = (1 to 8).map(index => s"check-$index").toSet
      val value = report.copy(members = report.members.reverse, assessments = List(assessment.copy(
        members = assessment.members.map(member => member.copy(acceptance = List(CohortCriterion(0, names, "Inspect this member")))))))
      val encoded = ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, value)
      def editCriteria(json: Json)(edit: JsonObject => JsonObject): Json = json.arrayOrObject(json,
        values => Json.fromValues(values.map(value => editCriteria(value)(edit))),
        fields => Json.fromJsonObject {
          val nested = fields.mapValues(value => editCriteria(value)(edit))
          if (nested.contains("checks")) edit(nested) else nested
        })
      val reordered = editCriteria(encoded)(fields => fields.add("checks", Json.fromValues(fields("checks").get.asArray.get.reverse)))
      assert(reordered != encoded)
      assert(ChildReport_JsonCodec.decode(baboon.runtime.shared.BaboonCodecContext.Default, reordered) == Right(value))
      assert(ChildContracts.report(DispatchWork.Planner(), members, reordered) == value)
      val duplicate = editCriteria(reordered) { fields =>
        val checks = fields("checks").get.asArray.get
        fields.add("checks", Json.fromValues(checks :+ checks.head))
      }
      assert(ChildReport_JsonCodec.decode(baboon.runtime.shared.BaboonCodecContext.Default, duplicate) == Right(value))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Planner(), members, duplicate))
      val unknown = editCriteria(reordered)(_.add("undeclared", Json.True))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Planner(), members, unknown))
      val noncanonical = reordered.hcursor.downField("Plan").downField("assessments").downArray.downField("members")
        .downArray.downField("member").downField("revision").downField("value").withFocus(_ => Json.fromString("01")).top.get
      assert(ChildReport_JsonCodec.decode(baboon.runtime.shared.BaboonCodecContext.Default, noncanonical) == Right(value))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Planner(), members, noncanonical))
    }

    "review compatibility without inventing a ledger proposal and reject incomplete or overlapping groups" in {
      val value = report
      ChildContracts.report(DispatchWork.Planner(), members, ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, value))
      assert(CohortAssessmentPolicy.reviewable(DispatchWork.Planner(), members, value))
      assert(ProposalPolicy.prepare(DispatchWork.Planner(), members, value).isEmpty)
      rejected(value.copy(assessments = Nil))
      rejected(value.copy(assessments = List(assessment, assessment)))
      rejected(value.copy(assessments = List(assessment.copy(members = assessment.members.take(1)))))
      rejected(value.copy(assessments = List(assessment.copy(members = assessment.members.init))))
      rejected(value.copy(assessments = List(assessment.copy(members = assessment.members.map(member => member.copy(member = member.member.copy(revision = Revision(2))))))))
      rejected(value.copy(members = value.members.map(_.copy(disposition = PlanDisposition.Abstained))))
      rejected(value.copy(assessments = List(assessment.copy(interference = ""))))
    }

    "require complete per-member criterion mappings and configured check names" in {
      val actor = Actor("fixture", SessionId(UUID.randomUUID()), Role.Governor)
      val items = members.map { member =>
        member.id -> Item(member.id, member.revision,
          ItemDraft("Task", "Specification", Set.empty, false, Content.Task(TaskStatus.Ready, List("Expected behavior"), None, Nil), Nil),
          1, 1, Provenance(actor, 1, RequestId(UUID.randomUUID())))
      }.toMap
      CohortAssessmentPolicy.criteria(report, items.apply)
      CohortAssessmentPolicy.checks(report, checks)
      List(
        assessment.copy(members = assessment.members.map(member => member.copy(acceptance = member.acceptance ++ member.acceptance))),
        assessment.copy(members = assessment.members.map(member => member.copy(acceptance = List(CohortCriterion(1, Set("contract"), "Wrong index"))))),
      ).foreach { group =>
        val failure = intercept[DomainFailure](CohortAssessmentPolicy.criteria(report.copy(assessments = List(group)), items.apply))
        assert(failure.fault == Fault.Invalid("Cohort assessment: mapping must cover every frozen acceptance criterion exactly"))
      }
      intercept[DomainFailure](CohortAssessmentPolicy.checks(report, Nil))
      intercept[DomainFailure](CohortAssessmentPolicy.criteria(report, id => items(id).copy(revision = Revision(2))))
    }

    "keep assessment narratives outside normal parent traffic and preserve uncertainty" in {
      val attempt = AttemptId(UUID.randomUUID())
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Planner(), Harness.Codex, members, Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val initial = DispatchStatus(request.request, attempt, DispatchPhase.Running, None, members.map(_.id), DispatchProjection.EmptyCounts,
        ChildNext.Wait, None, None, false, true)
      val handle = ArtifactId(UUID.randomUUID())
      def project(value: CohortAssessment): DispatchStatus = DispatchProjection.completed(initial,
        ChildResult(attempt, request, GitCommit("a" * 40), None, report.copy(assessments = List(value)), Nil), handle)
      val small = project(assessment)
      val large = project(assessment.copy(objective = "PRIVATE_ASSESSMENT " * 400))
      assert(small == large && small.counts.assessed == 3 && small.counts.proposed == 0 && small.next == ChildNext.ConsiderGrouping)
      assert(!HostFiles.encode(DispatchStatus_JsonCodec, large).contains("PRIVATE_ASSESSMENT"))
      val unknown = project(assessment.copy(compatibility = CohortCompatibility.Unknown, interference = "No evidence of independent edits"))
      assert(unknown.blocker.contains("Unknown: No evidence of independent edits") && unknown.counts.ready == 0 && unknown.counts.accepted == 0)
    }
  }
}
