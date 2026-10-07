package cq.server

import cq.api.*
import cq.core.{DomainFailure, GoverningWorkPolicy, LedgerPolicy, WorkspaceService}
import cq.host.*
import java.time.Clock
import zio.{IO, Promise, Ref, Task, UIO, ZIO}

/** What the host established before it registered an attempt of the governing session itself: the input a child of that work would
  * be given, the commit the work starts from and, for a review, the verdicts and the checks of its subject as they stand.
  * `began` is when the claim was renewed for it (the ZIO clock's `nanoTime`). */
private[server] final case class OwnWork(input: ChildInput, base: GitCommit, review: Option[List[ReviewMember]], validation: List[ValidationEvidence], began: Long)

/**
 * The governing session's own work, which the YOLO process mode permits an interactive session: a change it makes itself in a
 * workspace the host opens for it, and its own review of a candidate. Each is an attempt of the Governor role under the governing
 * attempt and runs no process. The workspace is the kind a Worker gets; from its submission on, its content is captured, checked
 * and published as a Worker's is, so that every later step reads the result as a candidate like any other.
 */
final class GovernorWork(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor, workspaces: WorkspaceService[IO],
  candidates: CandidateWorkspace, requirements: OperatorRequirements, renewal: ClaimRenewal, clock: Clock) {
  private val settlement = new AttemptSettlement(config, authority, jobs, workspaces, candidates, renewal, clock)
  private val project = config.project.project
  // `handed` is completed once: with the report of the submitted workspace, or with nothing when the attempt was stopped first.
  // `stopping` runs before an attempt that the host itself stops is withdrawn.
  private final class Seat(val work: OwnWork, val stopping: UIO[Unit], val opened: Promise[Nothing, Unit], val handed: Promise[Nothing, Option[ChildReport.Work]])
  private var seats = Map.empty[AttemptId, Seat]
  private def seat(attempt: AttemptId): Seat = synchronized(seats.getOrElse(attempt,
    throw DomainFailure(Fault.Missing("The attempt is not work of the governing session that is still open"))))

  private def call(command: Command): Result = authority.governor.call(command) match {
    case Result.Failed(fault) => throw DomainFailure(fault)
    case value => value
  }

  /** Refuses what the server would refuse when the result is admitted, in its words, before anything is registered or opened. */
  def admissible(): Unit = {
    GoverningWorkPolicy.interactive(config.run.ownership == SessionOwnership.Attached)
    GoverningWorkPolicy.admit(ProcessModes.setting(call, project))
  }

  /** As `admissible`, for a workspace that is open: the refusal also says what becomes of it. */
  def submittable(): Unit = try admissible() catch {
    case DomainFailure(Fault.Denied(message)) => throw DomainFailure(Fault.Denied(message + ". " + GovernorWork.Unsubmitted))
  }

  /** Establishes `request` as own work of the governing session. A review names its verdicts in `review`; its subject is an admitted
    * worker result with a candidate on which every configured check has passed, as the revalidation rounds left it. */
  def prepare(request: DispatchRequest, review: Option[List[ReviewMember]]): Task[OwnWork] = zio.Clock.nanoTime.flatMap { began => ZIO.attemptBlocking {
    admissible()
    review.foreach(members => ChildContracts.report(request.work, request.members,
      ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, ChildReport.Review(members, None))))
    val input = new InputAssembler(authority.governor, config.owner, clock, requirements.current).assemble(request)
    val previous = input.previous
    // As for a Worker: the candidate to continue from is that of a worker result, or of a review of one that asks for changes.
    previous.foreach(value => require(value.candidate.nonEmpty && (value.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) ||
      value.request.work.isInstanceOf[DispatchWork.Worker] && value.request.work != DispatchWork.Worker(WorkerMode.Probe)) &&
      (review.isEmpty || value.request.work.isInstanceOf[DispatchWork.Worker]), "The previous result must be an admitted worker result with a candidate, or a review of one"))
    val validation = if (review.isEmpty) Nil else {
      val subject = request.previous.get
      val current = IntegrationValidation.effective(project, config.owner.actor.session, subject, previous.get, config.settings.checks,
        new ArtifactReader(call, project).amendments(subject)).current
      current.find(_.state != ValidationState.Passed).foreach(check => throw DomainFailure(Fault.Conflict(
        s"A self-review requires that every configured check of the candidate has passed; check ${check.check} is ${check.state}. " +
          "Correct the candidate in a workspace opened with this result as previous, or Revalidate it when the failure is intermittent")))
      current
    }
    val base = previous.flatMap(_.candidate).getOrElse(candidates.fresh())
    candidates.verifyBase(base)
    OwnWork(input, base, review, validation, began)
  }}

  /** The members of the admitted result `subject` at their current revisions: what a review of it is assigned. */
  def subject(result: ArtifactId): List[ItemRevision] = new ArtifactReader(call, project).result(result).value.request.members.map { member =>
    call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(member.id)))) match {
      case Result.Detail(view) => ItemRevision(view.item.id, view.item.revision)
      case _ => throw new IllegalStateException("Review member read returned an unexpected result")
    }
  }

  /** Called once for a registered attempt of own work, before it is launched. `stopping` runs when the host itself stops the
    * attempt while its workspace is open, before the attempt goes on to its end. */
  def assign(entry: DispatchExecution, work: OwnWork, stopping: UIO[Unit]): UIO[Unit] = for {
    opened <- Promise.make[Nothing, Unit]
    handed <- Promise.make[Nothing, Option[ChildReport.Work]]
    _ <- ZIO.succeed(synchronized { seats = seats.updated(entry.ticket.attempt.id, new Seat(work, stopping, opened, handed)) })
  } yield ()

  /** Returns once the workspace of the attempt is open for the governing session, or the attempt has ended without one. */
  def opened(attempt: AttemptId): UIO[Unit] = ZIO.succeed(synchronized(seats.get(attempt))).flatMap(_.fold(ZIO.unit)(_.opened.await))

  /** The report of a workspace that is open, checked against the contract a Worker's report has. */
  def report(entry: DispatchExecution, members: List[WorkMember]): ChildReport.Work = {
    entry.check()
    seat(entry.ticket.attempt.id)
    ChildContracts.report(entry.ticket.request.work, entry.ticket.request.members,
      ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, ChildReport.Work(members))) match {
      case value: ChildReport.Work => value
      case other => throw new IllegalStateException(s"A work report decoded as ${other.getClass.getSimpleName}")
    }
  }

  /** Hands the workspace back with `report`: from here the host works on it. A repetition of the submission changes nothing; another
    * report for a workspace that was already handed back is refused. */
  def submit(entry: DispatchExecution, report: ChildReport.Work): Task[Unit] = ZIO.attempt(seat(entry.ticket.attempt.id)).flatMap { seat =>
    seat.handed.succeed(Some(report)).flatMap { first =>
      if (first) ZIO.succeed(entry.phase(DispatchPhase.Validating))
      else seat.handed.await.flatMap {
        case Some(`report`) => ZIO.unit
        case Some(_) => ZIO.fail(DomainFailure(Fault.Conflict("The workspace was already submitted with another report")))
        case None => ZIO.fail(DomainFailure(Fault.Conflict("The workspace was cancelled before it was submitted")))
      }
    }
  }

  /** The attempt could not be launched and will never run: nothing waits for its workspace any more. */
  def abandon(attempt: AttemptId): UIO[Unit] = ZIO.succeed(synchronized { val seat = seats.get(attempt); seats = seats - attempt; seat })
    .flatMap(_.fold(ZIO.unit)(seat => seat.handed.succeed(None) *> seat.opened.succeed(()).unit))

  /** An attempt that was asked to stop no longer waits for its workspace to be submitted. */
  def withdraw(attempt: AttemptId): UIO[Unit] = ZIO.succeed(synchronized(seats.get(attempt))).flatMap(_.fold(ZIO.unit)(_.handed.succeed(None).unit))

  private def registered(entry: DispatchExecution, queue: DeliveryQueue, work: OwnWork): Task[Unit] = ZIO.attemptBlocking {
    val ticket = entry.ticket
    queue.enqueue(0, DeliveryBatch(List(HostDelivery.Usage(HostUsageInput(project, HostUsage.Assign(ticket.assignment))),
      HostDelivery.Usage(HostUsageInput(project, HostUsage.Start(ticket.attempt))))))
    queue.flush(authority.collector)
    entry.check()
    // The input a child of this work would have been given: what reads a result's execution finds it for own work too.
    val body = HostFiles.encode(ChildExecutionInput_JsonCodec, ChildExecutionInput(work.input, work.base, config.settings.checks))
    queue.enqueue(1, DeliveryBatch(List(HostDelivery.Artifact(ArtifactUpload(project, NativeArtifacts.id(ticket.attempt.id, "input"), ticket.attempt.id,
      ArtifactKind.Input, "application/json", body)))))
    queue.flush(authority.collector)
  }

  private def worked(entry: DispatchExecution, seat: Seat): Task[Unit] = for {
    trace <- Ref.make(AttemptTrace.Empty)
    queue <- ZIO.attemptBlocking(new DeliveryQueue(entry.directory.resolve("delivery")))
    result <- ZIO.scoped {
      for {
        _ <- registered(entry, queue, seat.work)
        // The claim is renewed for as long as the workspace is open and while its content is captured and checked.
        _ <- settlement.maintain(entry, seat.work.began, seat.stopping *> withdraw(entry.ticket.attempt.id)).forkScoped
        workspace <- workspaces.prepare(config.owner, WorkspaceSpec(project, config.run.attempt.session, entry.ticket.attempt.id, config.run.repository, seat.work.base))
        // The session is told an absolute directory, however the settings write the state root.
        _ <- ZIO.attempt(entry.editing(WorkspaceState(workspace.admission, Some(java.nio.file.Path.of(workspace.directory).toAbsolutePath.normalize().toString)))) *>
          seat.opened.succeed(())
        handed <- seat.handed.await
        report <- ZIO.attempt {
          entry.check()
          handed.getOrElse(throw new IllegalStateException("The workspace was withdrawn before it was submitted"))
        }
        evidence <- settlement.evidence(entry, report)
        _ <- trace.update(value => value.copy(extra = value.extra ++ evidence.uploads))
        candidate <- settlement.capture(entry, report.members, seat.work.input, None)
        validation <- candidate.fold[Task[List[ValidationEvidence]]](ZIO.succeed(Nil))(settlement.checked(entry, _, trace))
        stored <- settlement.stored(entry, seat.work.base, candidate, report, validation, evidence.retained)
      } yield stored
    }.either
    _ <- if (result.isLeft || entry.stopReason.nonEmpty) {
      val cause = entry.stopReason.orElse(result.left.toOption.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName))).getOrElse("its submission failed")
      settlement.quarantine(entry, (GovernorWork.Quarantined + DispatchProjection.concise(cause)).take(LedgerPolicy.MaxTitle))
    } else ZIO.unit
    observed <- trace.get
    _ <- settlement.publish(entry, result, observed)
  } yield ()

  private def reviewed(entry: DispatchExecution, seat: Seat, verdicts: List[ReviewMember]): Task[Unit] = for {
    queue <- ZIO.attemptBlocking(new DeliveryQueue(entry.directory.resolve("delivery")))
    result <- (registered(entry, queue, seat.work) *> settlement.stored(entry, seat.work.base, Some(seat.work.base),
      ChildReport.Review(verdicts, None), seat.work.validation, RetainedEvidence(Nil, Nil))).either
    _ <- settlement.publish(entry, result, AttemptTrace.Empty)
  } yield ()

  def run(entry: DispatchExecution): Task[Unit] = ZIO.attempt(seat(entry.ticket.attempt.id)).flatMap { seat =>
    seat.work.review.fold(worked(entry, seat))(reviewed(entry, seat, _)).ensuring(seat.opened.succeed(()) *>
      ZIO.succeed(synchronized { seats = seats - entry.ticket.attempt.id }))
  }
}

object GovernorWork {
  /** How the record of a workspace that was not captured begins; the reason follows. */
  /** What a session is told to do with a workspace whose submission the host refused. */
  val Unsubmitted = "The workspace was not captured: Cancel it, and its directory is kept as it is"
  val Quarantined = "The governing session's workspace was not captured and its edits are retained here: "
}
