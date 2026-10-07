package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey, ModuleDef}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class ResultAdmissionTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerService[IO]], DIKey[UsageService[IO]], DIKey[ArtifactService[IO]], DIKey[ResultAdmissionService[IO]]),
    // The cases of a governing session's own results need a project in the YOLO mode, whatever this release delivers.
    moduleOverrides = super.config.moduleOverrides ++ new ModuleDef { make[ProcessModePolicy].fromValue(new ProcessModePolicy(true)) },
  )
  private final case class Fixture(owner: Scope, collector: Scope, claim: Claim, result: ChildResult, artifact: ArtifactMetadata) {
    def input: HostAdmissionInput = HostAdmissionInput(owner.project, artifact.id, owner.actor)
  }
  private def task: ItemDraft = ItemDraft("Admission task", "Result publication", Set.empty, false,
    Content.Task(TaskStatus.Ready, List("Current ownership"), None, Nil), Nil)
  private def begin(ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO]): IO[Throwable, Fixture] = {
    val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "host", role = Role.Collector))
    for {
      _ <- ledger.initialize(owner, "Admission")
      members <- MilestoneFixture.assigned(ledger, owner, List.fill(2)(task))
      claim <- ledger.acquire(owner, ClaimId(UUID.randomUUID()), members.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None))
      parent <- usage.start(collector, Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Governor,
        Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None))
      assigned <- usage.assign(collector, Assignment(AssignmentId(UUID.randomUUID()), owner.project, claim.members, Attribution.Shared, Some(UUID.randomUUID()), None))
      child <- usage.start(collector, Attempt(AttemptId(UUID.randomUUID()), assigned.id, Some(parent.id), owner.actor.session, Role.Worker,
        Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Work, None))
      request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex,
        members, Nil, Nil, None, claim.fence, HostLimits(3000, 1000, 300, 2000, 262144))
      result = ChildResult(child.id, request, GitCommit("a" * 40), Some(GitCommit("b" * 40)),
        ChildReport.Work(members.map(item => WorkMember(item.id, WorkDisposition.CandidateReady, "Candidate", Nil))), Nil, RetainedEvidence(Nil, Nil))
      metadata <- artifacts.upload(collector, ArtifactUpload(owner.project, ArtifactId(UUID.randomUUID()), child.id,
        ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
    } yield Fixture(owner, collector, claim, result, metadata)
  }
  /** A claimed assignment under a governing attempt; an `attached` one is an interactive session's. */
  private final case class Governed(owner: Scope, collector: Scope, claim: Claim, members: List[ItemRevision], governing: Attempt)
  private def governed(ledger: LedgerService[IO], usage: UsageService[IO], attached: Boolean): IO[Throwable, Governed] = {
    val owner = Scope(ProjectId(UUID.randomUUID()), Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
    val collector = owner.copy(actor = owner.actor.copy(subject = "host", role = Role.Collector))
    for {
      _ <- ledger.initialize(owner, "Governing work")
      members <- MilestoneFixture.assigned(ledger, owner, List.fill(2)(task))
      claim <- ledger.acquire(owner, ClaimId(UUID.randomUUID()), members.map(_.id).toSet, 300000)
      assignment <- usage.assign(collector, Assignment(AssignmentId(UUID.randomUUID()), owner.project, Set.empty, Attribution.Unattributed, None, None))
      governing <- usage.start(collector, Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, owner.actor.session, Role.Governor, Harness.Codex,
        "fixture", "fixture", if (attached) AttemptObservation.AttachedGovernorCollector else "CQ native collector 0.1.0", 1000, UsagePhase.Govern, None))
    } yield Governed(owner, collector, claim, members, governing)
  }
  /** Publishes the result of an attempt of `role` for `work`; a review covers the result stored as `previous`. */
  private def published(g: Governed, usage: UsageService[IO], artifacts: ArtifactService[IO], role: Role, work: DispatchWork,
    previous: Option[ArtifactId]): IO[Throwable, Fixture] = for {
    assigned <- usage.assign(g.collector, Assignment(AssignmentId(UUID.randomUUID()), g.owner.project, g.claim.members, Attribution.Shared, Some(UUID.randomUUID()), None))
    attempt <- usage.start(g.collector, Attempt(AttemptId(UUID.randomUUID()), assigned.id, Some(g.governing.id), g.owner.actor.session, role,
      Harness.Codex, "fixture", "fixture", "fixture", 1001, UsagePhase.Work, None))
    request = DispatchRequest(RequestId(UUID.randomUUID()), work, Harness.Codex, g.members, Nil, Nil, previous, g.claim.fence, HostLimits(3000, 1000, 300, 2000, 262144))
    candidate = GitCommit("b" * 40)
    result = work match {
      case _: DispatchWork.Reviewer => ChildResult(attempt.id, request, candidate, Some(candidate),
        ChildReport.Review(g.members.map(item => ReviewMember(item.id, ReviewVerdict.Accepted, Nil)), None), Nil, RetainedEvidence(Nil, Nil))
      case _ => ChildResult(attempt.id, request, GitCommit("a" * 40), Some(candidate),
        ChildReport.Work(g.members.map(item => WorkMember(item.id, WorkDisposition.CandidateReady, "Candidate", Nil))), Nil, RetainedEvidence(Nil, Nil))
    }
    metadata <- artifacts.upload(g.collector, ArtifactUpload(g.owner.project, ArtifactId(UUID.randomUUID()), attempt.id,
      ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, result)))
  } yield Fixture(g.owner, g.collector, g.claim, result, metadata)
  private def mode(ledger: LedgerService[IO], project: ProjectId, value: ProcessMode): IO[Throwable, Unit] = {
    val operator = Scope(project, Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
    ledger.mode(operator).flatMap(current => ledger.replaceMode(operator, current.revision, ProjectSetting.Mode(value, false))).unit
  }
  private val Implement: DispatchWork = DispatchWork.Worker(WorkerMode.Implement)
  private val Candidate: DispatchWork = DispatchWork.Reviewer(ReviewerMode.Candidate)
  private def refusedIn(mode: String): Fault = Fault.Denied(
    s"A result the governing session made or reviewed itself is admitted only in the YOLO cross-cutting mode; the project's process mode is $mode")

  private def rejected[A](operation: IO[Throwable, A], accepts: Fault => Boolean): IO[Throwable, Unit] =
    operation.either.flatMap(value => assertIO(value match { case Left(DomainFailure(fault)) => accepts(fault); case _ => false })).unit

  "Durable result admission (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "retain an accepted decision across concurrent replay, release and later item changes without writing history" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admission: ResultAdmissionService[IO]) => for {
        f <- begin(ledger, usage, artifacts)
        before <- ledger.changes(f.owner, ChangeCursor(0), 20)
        decisions <- ZIO.collectAllPar(List.fill(2)(admission.admit(f.collector, f.input)))
        _ <- assertIO(decisions.distinct.size == 1 && decisions.head.decision == AdmissionDecision.Accepted())
        after <- ledger.changes(f.owner, ChangeCursor(0), 20)
        _ <- assertIO(after == before && decisions.head.artifact == f.artifact)
        _ <- ledger.release(f.owner, f.claim.fence)
        member = f.result.request.members.head
        _ <- ledger.change(f.owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Replace(member.id, member.revision, task.copy(title = "Later"))), Nil, "Correction"))
        replay <- admission.admit(f.collector, f.input)
        read <- admission.get(f.owner, f.result.attempt)
        _ <- assertIO(replay == decisions.head && read == replay)
        another <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(UUID.randomUUID()), f.result.attempt,
          ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, f.result)))
        _ <- rejected(admission.admit(f.collector, f.input.copy(artifact = another.id)), _.isInstanceOf[Fault.Conflict])
        _ <- rejected(admission.admit(f.collector, f.input.copy(owner = f.owner.actor.copy(subject = "Changed owner"))), _.isInstanceOf[Fault.Conflict])
      } yield ()
    }

    "reject released ownership permanently and distinguish changed revisions from claim loss" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admission: ResultAdmissionService[IO]) => for {
        f <- begin(ledger, usage, artifacts)
        _ <- ledger.release(f.owner, f.claim.fence)
        first <- admission.admit(f.collector, f.input)
        _ <- assertIO(first.decision == AdmissionDecision.Rejected(AdmissionRejection.ClaimLost))
        _ <- ledger.acquire(f.owner, ClaimId(UUID.randomUUID()), f.claim.members, 300000)
        replay <- admission.admit(f.collector, f.input)
        _ <- assertIO(replay == first)
        other <- begin(ledger, usage, artifacts)
        member = other.result.request.members.last
        _ <- ledger.change(other.owner, ChangeRequest(RequestId(UUID.randomUUID()), List(Mutation.Replace(member.id, member.revision, task.copy(title = "Changed"))), List(other.claim.fence), "Correction"))
        changed <- admission.admit(other.collector, other.input)
        _ <- assertIO(changed.decision == AdmissionDecision.Rejected(AdmissionRejection.RevisionsChanged))
        outcome = AttemptOutcome(RequestId(UUID.randomUUID()), f.result.attempt, AttemptState.Failed, 2000, List("Claim lost"), None)
        _ <- usage.finish(f.collector, outcome)
        observed <- usage.outcomes(f.owner, f.result.attempt, 0, 20)
        _ <- assertIO(observed.entries.size == 1)
      } yield ()
    }

    "deny model roles and foreign sessions and reject malformed or mismatched artifacts without reserving a decision" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admission: ResultAdmissionService[IO]) => for {
        f <- begin(ledger, usage, artifacts)
        _ <- ZIO.foreachDiscard(List(Role.Human, Role.Governor, Role.Worker, Role.Reviewer, Role.Explorer, Role.Planner)) { role =>
          rejected(admission.admit(f.collector.copy(actor = f.collector.actor.copy(role = role)), f.input), _.isInstanceOf[Fault.Denied])
        }
        foreign = f.collector.copy(actor = f.collector.actor.copy(session = SessionId(UUID.randomUUID())))
        _ <- rejected(admission.admit(foreign, f.input), _.isInstanceOf[Fault.Denied])
        _ <- rejected(admission.admit(foreign, f.input.copy(owner = f.owner.actor.copy(session = foreign.actor.session))), _.isInstanceOf[Fault.Denied])
        _ <- rejected(admission.admit(f.collector, f.input.copy(project = ProjectId(UUID.randomUUID()))), _.isInstanceOf[Fault.Denied])
        malformed <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(UUID.randomUUID()), f.result.attempt,
          ArtifactKind.Result, "application/json", "{}"))
        _ <- rejected(admission.admit(f.collector, f.input.copy(artifact = malformed.id)), _.isInstanceOf[Fault.Invalid])
        reduced = f.result.copy(request = f.result.request.copy(members = f.result.request.members.take(1)),
          report = ChildReport.Work(List(WorkMember(f.result.request.members.head.id, WorkDisposition.CandidateReady, "Partial assignment", Nil))))
        mismatched <- artifacts.upload(f.collector, ArtifactUpload(f.owner.project, ArtifactId(UUID.randomUUID()), f.result.attempt,
          ArtifactKind.Result, "application/json", Wire.encode(ChildResult_JsonCodec, reduced)))
        _ <- rejected(admission.admit(f.collector, f.input.copy(artifact = mismatched.id)), _.isInstanceOf[Fault.Invalid])
        _ <- rejected(admission.get(f.owner, f.result.attempt), _.isInstanceOf[Fault.Missing])
        valid <- admission.admit(f.collector, f.input)
        _ <- assertIO(valid.decision == AdmissionDecision.Accepted())
      } yield ()
    }

    "I30: admit a result the governing session made or reviewed itself only while the project is in the YOLO mode, whoever made the reviewed result" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admission: ResultAdmissionService[IO]) => for {
        g <- governed(ledger, usage, attached = true)
        // A Worker child's result is admitted in every mode, as before.
        child <- published(g, usage, artifacts, Role.Worker, Implement, None)
        admitted <- admission.admit(child.collector, child.input)
        _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
        own <- published(g, usage, artifacts, Role.Governor, Implement, None)
        ofChild <- published(g, usage, artifacts, Role.Governor, Candidate, Some(child.artifact.id))
        ofOwn <- published(g, usage, artifacts, Role.Governor, Candidate, Some(own.artifact.id))
        results = List(own, ofChild, ofOwn)
        _ <- ZIO.foreachDiscard(List(ProcessMode.Rigorous -> "Rigorous", ProcessMode.CrossCutting -> "Cross-cutting")) { (value, label) =>
          mode(ledger, g.owner.project, value) *> ZIO.foreachDiscard(results) { f =>
            rejected(admission.admit(f.collector, f.input), _ == refusedIn(label)) *> rejected(admission.get(f.owner, f.result.attempt), _.isInstanceOf[Fault.Missing])
          }
        }
        _ <- mode(ledger, g.owner.project, ProcessMode.Yolo)
        accepted <- ZIO.foreach(results)(f => admission.admit(f.collector, f.input))
        _ <- assertIO(accepted.map(_.decision) == List.fill(3)(AdmissionDecision.Accepted()) && accepted.map(_.artifact) == results.map(_.artifact))
        // The mode is read when the admission is written: after the project leaves the YOLO mode a further result is refused at once,
        // and the admissions already written are replayed as they are.
        later <- published(g, usage, artifacts, Role.Governor, Candidate, Some(child.artifact.id))
        _ <- mode(ledger, g.owner.project, ProcessMode.CrossCutting)
        _ <- rejected(admission.admit(later.collector, later.input), _ == refusedIn("Cross-cutting"))
        replay <- ZIO.foreach(results)(f => admission.admit(f.collector, f.input))
        _ <- assertIO(replay == accepted)
        child2 <- published(g, usage, artifacts, Role.Reviewer, Candidate, Some(child.artifact.id))
        independent <- admission.admit(child2.collector, child2.input)
        _ <- assertIO(independent.decision == AdmissionDecision.Accepted())
      } yield ()
    }

    "I30: refuse a governing session's own result for a batch session and for work it may not do itself" in {
      (ledger: LedgerService[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admission: ResultAdmissionService[IO]) => for {
        batch <- governed(ledger, usage, attached = false)
        _ <- mode(ledger, batch.owner.project, ProcessMode.Yolo)
        child <- published(batch, usage, artifacts, Role.Worker, Implement, None)
        admitted <- admission.admit(child.collector, child.input)
        _ <- assertIO(admitted.decision == AdmissionDecision.Accepted())
        own <- ZIO.foreach(List(Implement -> None, Candidate -> Some(child.artifact.id)))((work, previous) => published(batch, usage, artifacts, Role.Governor, work, previous))
        _ <- ZIO.foreachDiscard(own) { f =>
          rejected(admission.admit(f.collector, f.input), _ == Fault.Denied("A result the governing session made or reviewed itself is admitted only for an " +
            "interactive session; a batch run dispatches a Worker and an independent Reviewer")) *> rejected(admission.get(f.owner, f.result.attempt), _.isInstanceOf[Fault.Missing])
        }
        attached <- governed(ledger, usage, attached = true)
        _ <- mode(ledger, attached.owner.project, ProcessMode.Yolo)
        resolution <- published(attached, usage, artifacts, Role.Governor, DispatchWork.Worker(WorkerMode.ResolveConflict), None)
        _ <- rejected(admission.admit(resolution.collector, resolution.input), _ == Fault.Invalid("Result differs from the registered child assignment"))
        // A child's role still has to be the role of its work.
        mismatched <- published(attached, usage, artifacts, Role.Reviewer, Implement, None)
        _ <- rejected(admission.admit(mismatched.collector, mismatched.input), _ == Fault.Invalid("Result differs from the registered child assignment"))
      } yield ()
    }
  }
}

final class ResultAdmissionDummy extends ResultAdmissionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class ResultAdmissionPostgres extends ResultAdmissionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
