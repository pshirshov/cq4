package cq.server

import cq.api.*
import cq.host.{CohortArtifactFingerprint, CohortArtifacts, CohortExecutionFingerprint, CohortFingerprint, CohortProgress, CohortResultFingerprint}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CohortProgressLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.randomUUID())
  private def id(number: Long): ItemId = ItemId(project, Ledger.Tasks, number)
  private val actor = Actor("fixture", SessionId(UUID.randomUUID()), Role.Governor)
  private def item(number: Long): ItemView = ItemView(Item(id(number), Revision(1),
    ItemDraft("Task", "Required behavior", Set.empty, false, Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil), Nil),
    1, 1, Provenance(actor, 1, RequestId(UUID.randomUUID()))), Nil)
  private val checks = List(ValidationCheck("test", List("verify", "first", "second"), 1000, 4096, 1))
  private val work = DispatchWork.Worker(WorkerMode.Implement)
  private def hash(members: List[ItemView], guidance: List[ItemView], declared: List[ValidationCheck]): String =
    CohortFingerprint(work, members, guidance, Nil, Nil, GitCommit("a" * 40), declared)
  private def artifact(kind: ArtifactKind, body: String): ResolvedArtifact = ResolvedArtifact(
    ArtifactMetadata(project, ArtifactId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), kind, "application/json", "a" * 64,
      body.length, body.length, actor.copy(role = Role.Collector), 1), body)
  private def artifactHash(value: ResolvedArtifact): String =
    CohortFingerprint(work, List(item(1)), Nil, List(operative(value)), Nil, GitCommit("a" * 40), checks)
  private def operative(value: ResolvedArtifact): CohortArtifactFingerprint = CohortArtifacts(value, id => {
    val empty = NativeManifest("base64", "text/plain", 0L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Nil)
    ResolvedArtifact(value.metadata.copy(id = id, kind = ArtifactKind.Transcript), Wire.encode(NativeManifest_JsonCodec, empty))
  })

  "Cohort progress (Behavioral Active Blackbox Atomic)" should {
    "ignore validation job identities while retaining the candidate, check and observed exit" in {
      val workspace = WorkspaceSpec(project, actor.session, AttemptId(UUID.randomUUID()), "/consumer", GitCommit("b" * 40))
      val job = JobRecord(workspace, "a" * 64, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(1), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1, 2)
      val value = ValidationObservation(checks.head, workspace.base, job, ArtifactId(UUID.randomUUID()), ArtifactId(UUID.randomUUID()))
      def stored(value: ValidationObservation) = artifact(ArtifactKind.Validation, Wire.encode(ValidationObservation_JsonCodec, value))
      val original = artifactHash(stored(value))
      val replay = value.copy(job = job.copy(workspace = workspace.copy(attempt = AttemptId(UUID.randomUUID())),
        fingerprint = "c" * 64, revision = 9, createdAt = 100, updatedAt = 101))
      assert(artifactHash(stored(replay)) == original)
      val changedCandidate = value.copy(candidate = GitCommit("c" * 40), job = job.copy(workspace = workspace.copy(base = GitCommit("c" * 40))))
      assert(artifactHash(stored(changedCandidate)) != original)
      assert(artifactHash(stored(value.copy(check = checks.head.copy(command = List("different-check"))))) != original)
      assert(artifactHash(stored(value.copy(job = job.copy(exit = job.exit.map(_.copy(code = Some(0))))))) != original)
    }

    "ignore combination publisher and claim envelopes while retaining the candidate and observed target" in {
      val ticket = CombinationTicket(RequestId(UUID.randomUUID()), IntegrationId(UUID.randomUUID()), Fence(ClaimId(UUID.randomUUID()), 1))
      val value = CombinationPlan(ticket, project, actor, AttemptId(UUID.randomUUID()), "/consumer", "refs/heads/main",
        GitCommit("a" * 40), GitCommit("b" * 40), ArtifactId(UUID.randomUUID()), List(ItemRevision(id(1), Revision(1))))
      def stored(value: CombinationPlan) = {
        val stored = artifact(ArtifactKind.Combination, Wire.encode(CombinationPlan_JsonCodec, value))
        stored.copy(metadata = stored.metadata.copy(attempt = value.governor, actor = stored.metadata.actor.copy(session = value.owner.session)))
      }
      val original = artifactHash(stored(value))
      val replay = value.copy(request = ticket.copy(id = RequestId(UUID.randomUUID()), source = IntegrationId(UUID.randomUUID()),
        fence = Fence(ClaimId(UUID.randomUUID()), 99)), owner = actor.copy(session = SessionId(UUID.randomUUID())), governor = AttemptId(UUID.randomUUID()),
        worker = ArtifactId(UUID.randomUUID()), members = value.members.map(_.copy(revision = Revision(9))))
      assert(artifactHash(stored(replay)) == original)
      assert(artifactHash(stored(value.copy(observedTarget = GitCommit("c" * 40)))) != original)
      assert(artifactHash(stored(value.copy(candidate = GitCommit("c" * 40)))) != original)
      assert(artifactHash(stored(value.copy(target = "refs/heads/other"))) != original)
    }

    "reject malformed structured evidence and validation output from another publisher" in {
      val workspace = WorkspaceSpec(project, actor.session, AttemptId(UUID.randomUUID()), "/consumer", GitCommit("b" * 40))
      val job = JobRecord(workspace, "a" * 64, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(1), None, StopReason.Exited, 1, 1, true, false)), None, 1, 1, 2)
      val outputId = ArtifactId(UUID.randomUUID())
      val observation = ValidationObservation(checks.head, workspace.base, job, outputId, outputId)
      val stored = artifact(ArtifactKind.Validation, Wire.encode(ValidationObservation_JsonCodec, observation))
      val manifest = NativeManifest("base64", "text/plain", 1L, "a" * 64, List(ArtifactId(UUID.randomUUID())))
      val output = ResolvedArtifact(stored.metadata.copy(id = outputId, kind = ArtifactKind.Transcript), Wire.encode(NativeManifest_JsonCodec, manifest))
      def fingerprint(input: ResolvedArtifact, evidence: ResolvedArtifact): CohortArtifactFingerprint = CohortArtifacts(input, id => {
        assert(id == outputId)
        evidence
      })
      val original = fingerprint(stored, output)
      val republished = manifest.copy(parts = List(ArtifactId(UUID.randomUUID())))
      assert(fingerprint(stored, output.copy(body = Wire.encode(NativeManifest_JsonCodec, republished))) == original)
      val foreign = List(output.metadata.copy(project = ProjectId(UUID.randomUUID())), output.metadata.copy(attempt = AttemptId(UUID.randomUUID())),
        output.metadata.copy(actor = actor), output.metadata.copy(kind = ArtifactKind.Input))
      foreign.foreach(metadata => intercept[IllegalArgumentException](fingerprint(stored, output.copy(metadata = metadata))))
      val malformed = List(manifest.copy(bytes = -1L), manifest.copy(bytes = 32L * 1024 * 1024 + 1),
        manifest.copy(parts = Nil), manifest.copy(parts = manifest.parts ++ manifest.parts), manifest.copy(sha256 = "invalid"))
      malformed.foreach(value => intercept[IllegalArgumentException](fingerprint(stored, output.copy(body = Wire.encode(NativeManifest_JsonCodec, value)))))
      val extra = io.circe.parser.parse(stored.body).toOption.get.mapObject(_.add("undeclared", io.circe.Json.True)).noSpaces
      intercept[IllegalArgumentException](fingerprint(stored.copy(body = extra), output))
      intercept[IllegalArgumentException](fingerprint(stored.copy(metadata = stored.metadata.copy(mediaType = "text/plain")), output))
    }

    "reject human-published host observations and inconsistent validation workspace identity" in {
      val workspace = WorkspaceSpec(project, actor.session, AttemptId(UUID.randomUUID()), "/consumer", GitCommit("b" * 40))
      val job = JobRecord(workspace, "a" * 64, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(1), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1, 2)
      val value = ValidationObservation(checks.head, workspace.base, job, ArtifactId(UUID.randomUUID()), ArtifactId(UUID.randomUUID()))
      val stored = artifact(ArtifactKind.Validation, Wire.encode(ValidationObservation_JsonCodec, value))
      val invalidWorkspaces = List("project" -> workspace.copy(project = ProjectId(UUID.randomUUID())), "owner" -> workspace.copy(owner = SessionId(UUID.randomUUID())),
        "base" -> workspace.copy(base = GitCommit("c" * 40))).map { (name, invalid) =>
        name -> stored.copy(body = Wire.encode(ValidationObservation_JsonCodec, value.copy(job = job.copy(workspace = invalid))))
      }
      val ticket = CombinationTicket(RequestId(UUID.randomUUID()), IntegrationId(UUID.randomUUID()), Fence(ClaimId(UUID.randomUUID()), 1))
      val plan = CombinationPlan(ticket, project, actor, stored.metadata.attempt, "/consumer", "refs/heads/main",
        GitCommit("a" * 40), GitCommit("b" * 40), ArtifactId(UUID.randomUUID()), List(ItemRevision(id(1), Revision(1))))
      val combination = stored.copy(metadata = stored.metadata.copy(kind = ArtifactKind.Combination), body = Wire.encode(CombinationPlan_JsonCodec, plan))
      val invalid = invalidWorkspaces ++ List("human validation" -> stored.copy(metadata = stored.metadata.copy(actor = actor.copy(role = Role.Human))),
        "human combination" -> combination.copy(metadata = combination.metadata.copy(actor = actor.copy(role = Role.Human))))
      val accepted = invalid.collect { case (name, input) if scala.util.Try(operative(input)).isSuccess => name }
      assert(accepted.isEmpty, s"Accepted inconsistent host evidence: $accepted")
    }

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
        List(ValidationEvidence("test", ValidationState.Failed, ArtifactId(UUID.randomUUID()), Nil)), RetainedEvidence(Nil, Nil))
      def source(result: ChildResult): CohortResultFingerprint = CohortResultFingerprint(result, Nil)
      def hash(result: ChildResult, artifacts: List[ResolvedArtifact]): String = CohortFingerprint(work, List(item(1)), Nil,
        artifacts.map(operative), List(source(result)), GitCommit("a" * 40), checks)
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
        List(source(result)), GitCommit("a" * 40), checks)
      assert(memberHash(1, shared) == memberHash(1, correction))
      assert(memberHash(2, shared) != memberHash(2, correction))
      val context = value.copy(request = request.copy(work = DispatchWork.Explorer(ExplorerMode.Research), members = List(ItemRevision(id(2), Revision(1)))),
        candidate = None, validation = Nil, report = ChildReport.Evidence(List(EvidenceMember(id(2), EvidenceDisposition.Findings, "New research", Nil, Nil, Nil))))
      val noEvidence = CohortFingerprint(work, List(item(1)), Nil, Nil, Nil, GitCommit("a" * 40), checks)
      assert(hash(context, Nil) != noEvidence)
      assert(hash(context.copy(report = ChildReport.Evidence(List(EvidenceMember(id(2), EvidenceDisposition.Findings, "Changed research", Nil, Nil, Nil)))), Nil) != hash(context, Nil))
      val otherWorker = context.copy(request = context.request.copy(work = work),
        report = ChildReport.Work(List(WorkMember(id(2), WorkDisposition.Blocked, "Observed another task's constraint", Nil))))
      assert(hash(otherWorker, Nil) != noEvidence)
      val ownWorker = otherWorker.copy(request = otherWorker.request.copy(members = List(member)),
        report = ChildReport.Work(List(WorkMember(id(1), WorkDisposition.Blocked, "Unchanged own failure", Nil))))
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
