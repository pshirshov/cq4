package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.host.ChildContracts
import io.circe.Json
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class ChildContractsLocal extends AnyWordSpec {
  "Child result contracts (Behavioral Active Blackbox Atomic)" should {
    "reject extra fields, foreign or missing members, role substitution and unsupported candidate assertions" in {
      val project = ProjectId(UUID.randomUUID())
      val member = ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(1))
      val work = DispatchWork.Worker(WorkerMode.Implement)
      val report = ChildReport.Work(List(WorkMember(member.id, WorkDisposition.CandidateReady, "Implemented")))
      def encoded(value: ChildReport): Json = ChildReport_JsonCodec.encode(BaboonCodecContext.Default, value)
      assert(ChildContracts.report(work, List(member), encoded(report)) == report)
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(report).deepMerge(Json.obj("authority" -> Json.True))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Work(Nil))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member), encoded(ChildReport.Work(report.members ++ report.members))))
      intercept[IllegalArgumentException](ChildContracts.report(work, List(member.copy(id = member.id.copy(number = 2))), encoded(report)))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Reviewer(), List(member), encoded(report)))
      val changes = ChildReport.Review(List(ReviewMember(member.id, ReviewVerdict.ChangesRequested, Nil)))
      intercept[IllegalArgumentException](ChildContracts.report(DispatchWork.Reviewer(), List(member), encoded(changes)))
      val request = DispatchRequest(RequestId(UUID.randomUUID()), work, Harness.Codex, List(member), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val wire = DispatchRequest_JsonCodec.encode(BaboonCodecContext.Default, request)
      assert(ChildContracts.decodeRequest(project, wire) == request)
      val missing = intercept[RuntimeException](ChildContracts.decodeRequest(project, Json.fromJsonObject(wire.asObject.get.remove("previous"))))
      assert(missing.getMessage.contains("previous"))
      intercept[IllegalArgumentException](ChildContracts.decodeRequest(project, wire.deepMerge(Json.obj("prompt" -> Json.fromString("Copied narrative")))))
      intercept[IllegalArgumentException](ChildContracts.decodeRequest(project, wire.deepMerge(Json.obj("limits" -> Json.obj("prompt" -> Json.fromString("Nested narrative"))))))
      val result = ChildResult(AttemptId(UUID.randomUUID()), request, GitCommit("a" * 40), None, report, Nil)
      intercept[IllegalArgumentException](ChildContracts.result(project, result))
      ChildContracts.result(project, result.copy(candidate = Some(GitCommit("b" * 40))))
    }
  }
}
