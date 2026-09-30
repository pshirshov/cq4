package cq.server

import cq.api.*
import cq.core.*
import cq.host.ChildContracts
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class ProposalContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ProposalService[IO]]),
  )
  private final case class Fixture(owner: Scope, collector: Scope, claim: Claim, members: List[ItemRevision], parent: AttemptId)
  private final case class Published(result: ChildResult, artifact: ArtifactMetadata)
  private def uuid: UUID = UUID.randomUUID()
  private def task: ItemDraft = ItemDraft("Proposal task", "PRIVATE_DRAFT " + "x" * 4096, Set.empty, false,
    Content.Task(TaskStatus.Ready, List("Preserve assignment and atomicity"), None, Nil), Nil)

  private def begin(ledger: LedgerService[IO], usage: UsageService[IO]): IO[Throwable, Fixture] = {
    val owner = Scope(ProjectId(uuid), Actor("proposal governor", SessionId(uuid), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "host", role = Role.Collector))
    for {
      _ <- ledger.initialize(owner, "Proposals")
      created <- ledger.change(owner, ChangeRequest(RequestId(uuid), List.fill(3)(Mutation.Create(task)), Nil, "Assignment"))
      claim <- ledger.acquire(owner, ClaimId(uuid), created.items.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(uuid), owner.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(uuid), assignment.id, None, owner.actor.session, Role.Governor,
        Harness.Codex, "fixture", "fixture", "fixture", 1000))
    } yield Fixture(owner, collector, claim, created.items, parent.id)
  }

  private def plan(f: Fixture, mutations: List[ProposedMutation]): ChildReport.Plan = ChildReport.Plan(
    f.members.map(ref => PlanMember(ref.id, PlanDisposition.Proposed, "Proposed next step")), Some(LedgerProposal(mutations, "Apply the proposed next step")), Nil)

  private def publish(f: Fixture, work: DispatchWork, report: ChildReport, usage: UsageService[IO], artifacts: ArtifactService[IO]): IO[Throwable, Published] = for {
    assignment <- usage.assign(f.collector, Assignment(AssignmentId(uuid), f.owner.project, f.claim.members, Attribution.Shared, Some(uuid), None))
    attempt <- usage.start(f.collector, Attempt(AttemptId(uuid), assignment.id, Some(f.parent), f.owner.actor.session, ChildContracts.role(work),
      Harness.Codex, "fixture", "fixture", "fixture", 1001))
    request = DispatchRequest(RequestId(uuid), work, Harness.Codex, f.members, Nil, Nil, None, f.claim.fence,
      HostLimits(3000, 10000, 1000, 300, 2000, 262144))
    result = ChildResult(attempt.id, request, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
    artifact <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(uuid), attempt.id, ArtifactKind.Result,
      "application/json", Wire.encode(ChildResult_JsonCodec, result)))
  } yield Published(result, artifact)

  private def admit(f: Fixture, value: Published, admissions: ResultAdmissionService[IO]): IO[Throwable, Unit] =
    admissions.admit(f.collector, HostAdmissionInput(f.owner.project, value.artifact.id, f.owner.actor))
      .flatMap(result => assertIO(result.decision == AdmissionDecision.Accepted())).unit

  private def rejected[A](operation: IO[Throwable, A], accepts: Fault => Boolean): IO[Throwable, Unit] =
    operation.either.flatMap(value => assertIO(value match { case Left(DomainFailure(fault)) => accepts(fault); case _ => false })).unit

  "Stored proposals (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "admit compatibility evidence without ledger writes and reject incorrect frozen acceptance mappings" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        group = CohortAssessment(CohortCompatibility.Compatible, "Shared implementation", "No intra-group dependency", "Acceptance remains per member",
          f.members.map(member => CohortMemberAssessment(member, List(CohortCriterion(0, Set.empty, "Inspect each member's acceptance")))))
        report = ChildReport.Plan(f.members.map(member => PlanMember(member.id, PlanDisposition.Assessed, "Compatibility assessed")), None, List(group))
        value <- publish(f, DispatchWork.Planner(), report, usage, artifacts)
        _ <- admit(f, value, admissions)
        _ <- rejected(proposals.preview(f.owner, value.artifact.id), _.isInstanceOf[Fault.Invalid])
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Invalid])
        wrongMembers = group.members.map(member => member.copy(acceptance = List(CohortCriterion(1, Set.empty, "Does not cover criterion zero"))))
        malformed = report.copy(assessments = List(group.copy(members = wrongMembers)))
        bad <- publish(f, DispatchWork.Planner(), malformed, usage, artifacts)
        _ <- rejected(admissions.admit(f.collector, HostAdmissionInput(f.owner.project, bad.artifact.id, f.owner.actor)),
          _ == Fault.Invalid("Cohort assessment: mapping must cover every frozen acceptance criterion exactly"))
        after <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(before == after)
      } yield ()
    }

    "preview semantic changes without narratives and commit once across concurrent retry, later edits and claim release" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        completed = task.copy(archived = true, content = Content.Task(TaskStatus.Done, List("Preserve assignment and atomicity"), Some("Declared complete"),
          List(Evidence("Model evidence", EvidenceOrigin.ModelDeclared, Nil))))
        report = plan(f, List(ProposedMutation.Create(task.copy(title = "New task")), ProposedMutation.Replace(f.members.head.id, completed),
          ProposedMutation.Produce(f.members(1).id, List(task.copy(title = "Derived task")))))
          .copy(members = f.members.zipWithIndex.map { case (ref, index) => PlanMember(ref.id,
            if (index == 2) PlanDisposition.Blocked else PlanDisposition.Proposed, "Per-member disposition") })
        value <- publish(f, DispatchWork.Planner(), report, usage, artifacts)
        _ <- admit(f, value, admissions)
        preview <- proposals.preview(f.owner, value.artifact.id)
        delta = preview.operations(1).asInstanceOf[ProposalOperationSummary.Replace]
        _ <- assertIO(preview.role == Role.Planner && preview.detailsOmitted &&
          delta.before.status == TerminalStatus.Task(TaskStatus.Ready) && delta.after.status == TerminalStatus.Task(TaskStatus.Done) &&
          !delta.before.archived && delta.after.archived && delta.fields.evidence && delta.fields.provenance &&
          !Wire.encode(ProposalPreview_JsonCodec, preview).contains("PRIVATE_DRAFT"))
        results <- ZIO.collectAllPar(List.fill(2)(proposals(f.owner, value.artifact.id)))
        ack = results.head
        _ <- assertIO(results.distinct.size == 1 && ack.items.size == 4)
        changes <- ledger.changes(f.owner, ChangeCursor(0), 20)
        history <- ledger.history(f.owner, f.members.head.id, Revision(Long.MaxValue), 20)
        blocked <- ledger.get(f.owner, f.members.last.id)
        child <- ledger.get(f.owner, ItemId(f.owner.project, Ledger.Tasks, 5))
        _ <- assertIO(changes.events.size == 2 && history.entries.size == 2 && blocked.item.revision == Revision(1) &&
          child.refs.contains(ItemRef(Relation.DerivedFrom, f.members(1).id)))
        _ <- ledger.release(f.owner, f.claim.fence)
        changed = ack.items.find(_.id == f.members.head.id).get
        _ <- ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(changed.id, changed.revision, task.copy(title = "Later correction"))), Nil, "Later"))
        replay <- proposals(f.owner, value.artifact.id)
        reread <- proposals.preview(f.owner.copy(actor = f.owner.actor.copy(role = Role.Human)), value.artifact.id)
        _ <- assertIO(replay == ack && reread == preview)
        _ <- rejected(proposals(f.owner.copy(actor = f.owner.actor.copy(subject = "another governor")), value.artifact.id), _.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "recover a lost application acknowledgement without repeating the committed effects" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        value <- publish(f, DispatchWork.Planner(), plan(f, List(ProposedMutation.Create(task))), usage, artifacts)
        _ <- admit(f, value, admissions)
        lost <- proposals(f.owner, value.artifact.id).flatMap(_ => ZIO.fail(new java.io.IOException("Lost after commit"))).either
        _ <- assertIO(lost.left.exists(_.getMessage == "Lost after commit"))
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        retry <- proposals(f.owner, value.artifact.id)
        after <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(before == after && after.events.size == 2 && retry.items.map(_.id.number) == List(4))
      } yield ()
    }

    "reject stale unmodified members, released or replaced claims, and unadmitted or rejected results" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        value <- publish(f, DispatchWork.Planner(), plan(f, List(ProposedMutation.Create(task))), usage, artifacts)
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Conflict])
        _ <- admit(f, value, admissions)
        untouched = f.members.last
        _ <- ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Replace(untouched.id, untouched.revision, task.copy(title = "Changed context"))), List(f.claim.fence), "Change"))
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Conflict])
        released <- begin(ledger, usage)
        late <- publish(released, DispatchWork.Planner(), plan(released, List(ProposedMutation.Create(task))), usage, artifacts)
        _ <- admit(released, late, admissions)
        _ <- ledger.release(released.owner, released.claim.fence)
        _ <- rejected(proposals(released.owner, late.artifact.id), _.isInstanceOf[Fault.StaleFence])
        _ <- ledger.acquire(released.owner, ClaimId(uuid), released.claim.members, 300000)
        _ <- rejected(proposals(released.owner, late.artifact.id), _.isInstanceOf[Fault.StaleFence])
        rejectedFixture <- begin(ledger, usage)
        rejectedResult <- publish(rejectedFixture, DispatchWork.Planner(), plan(rejectedFixture, List(ProposedMutation.Create(task))), usage, artifacts)
        _ <- ledger.release(rejectedFixture.owner, rejectedFixture.claim.fence)
        decision <- admissions.admit(rejectedFixture.collector, HostAdmissionInput(rejectedFixture.owner.project, rejectedResult.artifact.id, rejectedFixture.owner.actor))
        _ <- assertIO(decision.decision == AdmissionDecision.Rejected(AdmissionRejection.ClaimLost))
        _ <- rejected(proposals(rejectedFixture.owner, rejectedResult.artifact.id), _.isInstanceOf[Fault.Conflict])
      } yield ()
    }

    "roll back a preceding creation on invalid replacement and reject fabricated evidence or operator confirmation" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        invalidLedger = task.copy(content = Content.Idea(IdeaStatus.Proposed, "Outcome", "Motivation"))
        value <- publish(f, DispatchWork.Planner(), plan(f, List(ProposedMutation.Create(task), ProposedMutation.Replace(f.members.head.id, invalidLedger))), usage, artifacts)
        _ <- admit(f, value, admissions)
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Invalid])
        changes <- ledger.changes(f.owner, ChangeCursor(0), 20)
        current <- ledger.get(f.owner, f.members.head.id)
        created <- ledger.change(f.owner, ChangeRequest(RequestId(uuid), List(Mutation.Create(task)), Nil, "After rollback"))
        _ <- assertIO(changes.events.size == 1 && current.item.revision == Revision(1) && created.items.head.id.number == 4)
        fabricated = task.copy(content = Content.Task(TaskStatus.Done, List("Acceptance"), Some("Declared"), List(Evidence("Invented check", EvidenceOrigin.HostObserved, Nil))))
        confirmation = task.copy(content = Content.OperatorAction(OperatorActionStatus.Confirmed, "External action", "Actual evidence", Some("User said yes"), Nil))
        _ <- ZIO.foreachDiscard(List(fabricated, confirmation)) { draft => for {
          proposal <- publish(f, DispatchWork.Planner(), plan(f, List(ProposedMutation.Create(draft))), usage, artifacts)
          _ <- admit(f, proposal, admissions)
          _ <- rejected(proposals(f.owner, proposal.artifact.id), _.isInstanceOf[Fault.Denied])
        } yield () }
      } yield ()
    }

    "deny foreign authority and namespace collisions and reject malformed immutable result aliases" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        value <- publish(f, DispatchWork.Planner(), plan(f, List(ProposedMutation.Create(task))), usage, artifacts)
        _ <- admit(f, value, admissions)
        _ <- ZIO.foreachDiscard(List(Role.Human, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer, Role.Collector)) { role =>
          rejected(proposals(f.owner.copy(actor = f.owner.actor.copy(role = role)), value.artifact.id), _.isInstanceOf[Fault.Denied])
        }
        _ <- rejected(proposals(f.owner.copy(actor = f.owner.actor.copy(session = SessionId(uuid))), value.artifact.id), _.isInstanceOf[Fault.Denied])
        _ <- rejected(proposals(f.owner.copy(project = ProjectId(uuid)), value.artifact.id), _.isInstanceOf[Fault.Missing])
        preview <- proposals.preview(f.owner, value.artifact.id)
        _ <- ledger.change(f.owner, ChangeRequest(preview.request, List(Mutation.Create(task.copy(title = "Different request"))), Nil, "Ordinary request"))
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Conflict])
        alias <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(uuid), value.result.attempt, ArtifactKind.Result,
          "application/json", Wire.encode(ChildResult_JsonCodec, value.result)))
        _ <- rejected(proposals(f.owner, alias.id), _.isInstanceOf[Fault.Conflict])
        json = ChildResult_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, value.result).deepMerge(io.circe.Json.obj("authority" -> io.circe.Json.True))
        malformed <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(uuid), value.result.attempt, ArtifactKind.Result, "application/json", json.noSpaces))
        _ <- rejected(proposals.preview(f.owner, malformed.id), _.isInstanceOf[Fault.Invalid])
      } yield ()
    }

    "require eligible member outcomes even for create-only proposals and reject writes to blocked or guidance items" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO]) => for {
        f <- begin(ledger, usage)
        proposed = plan(f, List(ProposedMutation.Create(task)))
        invalidPlans = List(
          proposed.copy(members = proposed.members.map(_.copy(disposition = PlanDisposition.Blocked))),
          proposed.copy(members = proposed.members.map(_.copy(disposition = PlanDisposition.Abstained))),
          plan(f, List(ProposedMutation.Replace(f.members.last.id, task))).copy(members = proposed.members.init :+ proposed.members.last.copy(disposition = PlanDisposition.Blocked)),
          plan(f, List(ProposedMutation.Reference(f.members.head.id, Relation.RelatesTo, ItemId(f.owner.project, Ledger.Tasks, 99), true))))
        _ <- ZIO.foreachDiscard(invalidPlans) { report => for {
          value <- publish(f, DispatchWork.Planner(), report, usage, artifacts)
          _ <- rejected(admissions.admit(f.collector, HostAdmissionInput(f.owner.project, value.artifact.id, f.owner.actor)), _.isInstanceOf[Fault.Invalid])
        } yield () }
        _ <- ZIO.foreachDiscard(List(ReviewVerdict.Accepted, ReviewVerdict.Blocked)) { verdict => for {
          value <- publish(f, DispatchWork.Reviewer(ReviewerMode.Audit), ChildReport.Review(f.members.map(ref => ReviewMember(ref.id, verdict, List("Finding"))), proposed.proposal), usage, artifacts)
          _ <- rejected(admissions.admit(f.collector, HostAdmissionInput(f.owner.project, value.artifact.id, f.owner.actor)), _.isInstanceOf[Fault.Invalid])
        } yield () }
      } yield ()
    }

    "apply a reviewer follow-up as a proposal without treating its findings as acceptance" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        report = ChildReport.Review(f.members.map(ref => ReviewMember(ref.id, ReviewVerdict.ChangesRequested, List("Retain a follow-up task"))),
          Some(LedgerProposal(List(ProposedMutation.Create(task)), "Review follow-up")))
        value <- publish(f, DispatchWork.Reviewer(ReviewerMode.Audit), report, usage, artifacts)
        _ <- admit(f, value, admissions)
        preview <- proposals.preview(f.owner, value.artifact.id)
        ack <- proposals(f.owner, value.artifact.id)
        _ <- assertIO(preview.role == Role.Reviewer && ack.items.size == 1)
        existing <- ledger.get(f.owner, f.members.head.id)
        _ <- assertIO(existing.item.revision == Revision(1))
      } yield ()
    }

    "reject an oversized semantic preview and its application without partial creation" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], proposals: ProposalService[IO]) => for {
        f <- begin(ledger, usage)
        large = task.copy(title = "é" * 300, body = "")
        value <- publish(f, DispatchWork.Planner(), plan(f, List.fill(64)(ProposedMutation.Create(large))), usage, artifacts)
        _ <- admit(f, value, admissions)
        _ <- rejected(proposals.preview(f.owner, value.artifact.id), _.isInstanceOf[Fault.Limit])
        _ <- rejected(proposals(f.owner, value.artifact.id), _.isInstanceOf[Fault.Limit])
        changes <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(changes.events.size == 1)
      } yield ()
    }
  }
}

final class ProposalContractDummy extends ProposalContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class ProposalContractPostgres extends ProposalContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
