package cq.server

import cq.api.*
import cq.host.{AdmittedResult, CohortExecutionFingerprint, CohortFingerprint, CohortProgress}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CohortProgressLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.randomUUID())
  private def id(number: Long): ItemId = ItemId(project, Ledger.Tasks, number)
  private val actor = Actor("fixture", SessionId(UUID.randomUUID()), Role.Governor)
  private def item(number: Long): ItemView = ItemView(Item(id(number), Revision(1),
    ItemDraft("Task", "Required behavior", Set.empty, false, Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil), Nil),
    1, 1, Provenance(actor, 1, RequestId(UUID.randomUUID()))), Nil)
  private val checks = List(ValidationCheck("test", List("verify", "first", "second"), 1000, 4096))
  private val work = DispatchWork.Worker(WorkerMode.Implement)
  private def hash(members: List[ItemView], guidance: List[ItemView], declared: List[ValidationCheck]): String =
    CohortFingerprint(work, members, guidance, Nil, Nil, None, GitCommit("a" * 40), declared)

  "Cohort progress (Behavioral Active Blackbox Atomic)" should {
    "distinguish changed reviewer validation while ignoring result and selection envelopes" in {
      val member = ItemRevision(id(1), Revision(1))
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Reviewer(ReviewerMode.Candidate), Harness.Codex,
        List(member), Nil, Nil, Some(ArtifactId(UUID.randomUUID())), Fence(ClaimId(UUID.randomUUID()), 1),
        HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val attempt = AttemptId(UUID.randomUUID())
      val metadata = ArtifactMetadata(project, ArtifactId(UUID.randomUUID()), attempt, ArtifactKind.Result, "application/json", "a" * 64,
        1, 1, actor.copy(role = Role.Collector), 1)
      val value = ChildResult(attempt, request, GitCommit("a" * 40), Some(GitCommit("b" * 40)),
        ChildReport.Review(List(ReviewMember(id(1), ReviewVerdict.Accepted, Nil)), None),
        List(ValidationEvidence("test", ValidationState.Failed, ArtifactId(UUID.randomUUID()))))
      def source(result: ChildResult): AdmittedResult = AdmittedResult(result, metadata,
        ResultAdmission(metadata, actor, result.request.fence, result.request.members, AdmissionDecision.Accepted(), 1))
      def hash(result: ChildResult, artifacts: List[ResolvedArtifact]): String = CohortFingerprint(work, List(item(1)), Nil,
        artifacts, List(source(result)), None, GitCommit("a" * 40), checks)
      val failed = hash(value, Nil)
      val passed = value.copy(validation = value.validation.map(_.copy(state = ValidationState.Passed)))
      assert(hash(passed, Nil) != failed)
      val replay = value.copy(attempt = AttemptId(UUID.randomUUID()), request = request.copy(request = RequestId(UUID.randomUUID()),
        harness = Harness.Pi, fence = Fence(ClaimId(UUID.randomUUID()), 20)), validation = value.validation.map(_.copy(artifact = ArtifactId(UUID.randomUUID()))))
      assert(hash(replay, Nil) == failed)
      val selection = ResolvedArtifact(metadata.copy(id = ArtifactId(UUID.randomUUID()), kind = ArtifactKind.Selection), "Selection envelope")
      assert(hash(value, List(selection)) == failed)
      val shared = value.copy(request = request.copy(members = List(member, ItemRevision(id(2), Revision(1)))),
        report = ChildReport.Review(List(ReviewMember(id(1), ReviewVerdict.Accepted, Nil),
          ReviewMember(id(2), ReviewVerdict.ChangesRequested, List("First correction"))), None))
      val correction = shared.copy(report = ChildReport.Review(List(ReviewMember(id(1), ReviewVerdict.Accepted, Nil),
        ReviewMember(id(2), ReviewVerdict.ChangesRequested, List("Different correction"))), None))
      def memberHash(number: Long, result: ChildResult): String = CohortFingerprint(work, List(item(number)), Nil, Nil,
        List(source(result)), None, GitCommit("a" * 40), checks)
      assert(memberHash(1, shared) == memberHash(1, correction))
      assert(memberHash(2, shared) != memberHash(2, correction))
      val context = value.copy(request = request.copy(work = DispatchWork.Explorer(ExplorerMode.Research), members = List(ItemRevision(id(2), Revision(1)))),
        candidate = None, validation = Nil, report = ChildReport.Evidence(List(EvidenceMember(id(2), EvidenceDisposition.Findings, "New research", Nil, Nil, Nil))))
      val noEvidence = CohortFingerprint(work, List(item(1)), Nil, Nil, Nil, None, GitCommit("a" * 40), checks)
      assert(hash(context, Nil) != noEvidence)
      assert(hash(context.copy(report = ChildReport.Evidence(List(EvidenceMember(id(2), EvidenceDisposition.Findings, "Changed research", Nil, Nil, Nil)))), Nil) != hash(context, Nil))
      val otherWorker = context.copy(request = context.request.copy(work = work),
        report = ChildReport.Work(List(WorkMember(id(2), WorkDisposition.Blocked, "Observed another task's constraint"))))
      assert(hash(otherWorker, Nil) != noEvidence)
      val ownWorker = otherWorker.copy(request = otherWorker.request.copy(members = List(member)),
        report = ChildReport.Work(List(WorkMember(id(1), WorkDisposition.Blocked, "Unchanged own failure"))))
      assert(hash(ownWorker, Nil) == noEvidence)
    }

    "ignore envelope and cosmetic changes while retaining operative content and command argument order" in {
      val members = List(item(1), item(2))
      val guidance = List(item(3), item(4))
      val original = hash(members, guidance, checks)
      val cosmetic = members.reverse.map(view => view.copy(item = view.item.copy(revision = Revision(9), updatedAt = 100,
        provenance = Provenance(actor.copy(session = SessionId(UUID.randomUUID())), 100, RequestId(UUID.randomUUID())),
        draft = view.item.draft.copy(labels = Set("cosmetic")))))
      assert(hash(cosmetic, guidance.reverse, checks) == original)
      assert(hash(members.map(view => view.copy(item = view.item.copy(draft = view.item.draft.copy(body = "Changed behavior")))), guidance, checks) != original)
      assert(hash(members, guidance, checks.map(_.copy(command = List("verify", "second", "first")))) != original)
      assert(hash(members, guidance, checks.map(_.copy(executionMillis = 2000))) != original)
    }

    "offer unreturned candidates before repeats across snapshots and new arrivals" in {
      val progress = new CohortProgress
      val initial = (1L to 32L).map(id).toList
      val first = progress.order(initial).take(8)
      progress.offered(first)
      val second = progress.order(id(33) :: initial.reverse).take(8)
      progress.offered(second)
      val third = progress.order(initial :+ id(33)).take(8)
      progress.offered(third)
      val fourth = progress.order(initial :+ id(33)).take(8)
      assert((first ++ second ++ third ++ fourth).toSet == initial.toSet)
      assert(List(first, second, third, fourth).forall(_.size == 8))
      progress.offered(fourth)
      assert(progress.order(initial :+ id(33)).head == id(33))
    }

    "defer unchanged executed inputs and prefer unattempted independent work" in {
      val progress = new CohortProgress
      val first = hash(List(item(1)), Nil, checks)
      progress.order(List(id(1), id(2)))
      val execution = CohortExecutionFingerprint(first, Map(id(1) -> first))
      progress.started(execution)
      assert(progress.deferred(first) && progress.order(List(id(1), id(2))).head == id(2))
      intercept[IllegalArgumentException](progress.started(execution))
      val changed = hash(List(item(1)), Nil, checks.map(_.copy(command = List("new-check"))))
      assert(!progress.deferred(changed))
    }
  }
}
