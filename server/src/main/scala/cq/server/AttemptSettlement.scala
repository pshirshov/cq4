package cq.server

import cq.api.*
import cq.core.{DomainFailure, LedgerPolicy, WorkspaceService}
import cq.host.*
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import scala.util.{Try, Using}
import zio.{IO, Ref, Task, UIO, ZIO}

/** `unrun` names the checks the host could not start; the result is published with them Unknown. */
private[server] final case class AttemptTrace(native: Option[JobRecord], extra: List[ArtifactUpload], spans: List[PhaseSpan], uncertain: Boolean, unrun: List[String])
private[server] object AttemptTrace {
  val Empty: AttemptTrace = AttemptTrace(None, Nil, Nil, false, Nil)
}

/**
 * What the attempts of one governing session share once their work is done, whoever did it (a child process, or the governing session
 * in a workspace the host opened for it): the claim the work holds, the capture of a candidate from its workspace, the configured
 * checks on that candidate, and the publication of how the attempt ended.
 */
private[server] final class AttemptSettlement(config: SupervisorConfig, authority: SupervisorAuthority, jobs: JobSupervisor,
  workspaces: WorkspaceService[IO], candidates: CandidateWorkspace, renewal: ClaimRenewal, clock: Clock) {
  private val MaxGaps = 32
  private val ClaimMillis = Duration.ofMinutes(3).toMillis
  private val partials = new PartialWorkCapture(config)
  private val validation = new HostValidation(config)
  private def directory(attempt: AttemptId): Path = config.directory.resolve("payload").resolve(attempt.value.toString)
  private def transcript(attempt: AttemptId, name: String, bound: Int): Array[Byte] = NativeTranscript.retained(directory(attempt).resolve(name), bound)
  /** The work of a Worker, which leaves a workspace whose content may become a candidate. */
  private def working(ticket: DispatchTicket): Boolean = ChildContracts.role(ticket.request.work) == Role.Worker

  def collect(ticket: DispatchTicket, collectedAt: Long): CollectedUsage =
    if (AttemptSettlement.own(ticket)) AttemptSettlement.Unmetered
    else HarnessUsage.launchable(ticket.profile).fold(HarnessUsage.Unlaunched) { setting =>
      Using.resource(NativeTranscript.stream(directory(ticket.attempt.id).resolve("stdout")))(new HarnessUsage().collect(_, UsageCollectionRequest(ticket.attempt.id,
        ticket.attempt.harness, setting.version, UsageOrigin.Fresh, collectedAt, NativeArtifacts.id(ticket.attempt.id, "stdout"))))
    }

  /** A failed removal leaves the record open; the next host startup retries it and reports the outcome in its cleanup receipt. */
  def release(attempt: AttemptId): Task[Option[WorkspaceRecord]] = jobs.release(config.owner, attempt)

  // The governing session's workspace has no job whose settlement would release it.
  private def released(ticket: DispatchTicket): Task[Option[WorkspaceRecord]] =
    if (!AttemptSettlement.own(ticket)) release(ticket.attempt.id)
    else workspaces.get(config.owner, ticket.attempt.id).flatMap { record =>
      if (record.admission == WorkspaceAdmission.Open) workspaces.remove(config.owner, ticket.attempt.id).map(Some(_)) else ZIO.succeed(Some(record))
    }.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }

  def claim(entry: DispatchExecution, revisions: Boolean): Unit = {
    val request = entry.ticket.request
    authority.governor.call(Command.ClaimWork(ClaimInput(config.project.project, ClaimAction.Renew(request.fence, ClaimMillis)))) match {
      case Result.Claimed(value) => require(value.owner == config.owner.actor && value.members == request.members.map(_.id).toSet &&
        value.fence == request.fence && !value.released && value.expiresAt > clock.millis(), "Dispatch claim no longer owns its assignment")
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Claim refresh returned an unexpected result")
    }
    if (revisions) request.members.foreach { reference =>
      authority.governor.call(Command.Read(ReadInput(config.project.project, ReadSelection.ItemDetail(reference.id)))) match {
        case Result.Detail(value) => require(value.item.id == reference.id && value.item.revision == reference.revision, "Assignment changed before result admission")
        case Result.Failed(fault) => throw DomainFailure(fault)
        case _ => throw new IllegalStateException("Admission read returned an unexpected result")
      }
    }
  }

  /** `obtained` is when the claim was last renewed for this attempt (the ZIO clock's `nanoTime`, which `ClaimRenewal` reads).
    * `stopped` runs once the attempt was asked to stop because the claim is lost. */
  def maintain(entry: DispatchExecution, obtained: Long, stopped: UIO[Unit]): Task[Unit] = renewal.maintain(obtained, ZIO.attemptBlocking(claim(entry, false))).catchAll { failure =>
    ZIO.succeed(entry.requestStop("Work claim refresh failed: " + Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))) *>
      ZIO.foreachDiscard(entry.ownedJobs)(id => jobs.cancel(config.owner, id).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }) *> stopped
  }

  def launch(entry: DispatchExecution, id: AttemptId, base: GitCommit, command: JobCommand): Task[JobRecord] = for {
    _ <- ZIO.attempt { entry.check(); entry.active(id) }
    _ <- jobs.start(config.owner, WorkspaceSpec(config.project.project, config.run.attempt.session, id, config.run.repository, base), command)
    _ <- if (entry.stopReason.nonEmpty) jobs.cancel(config.owner, id).unit else ZIO.unit
    record <- jobs.await(config.owner, id)
  } yield record

  /** The evidence directory of the workspace and the files the report names; nothing for work that leaves no workspace content. */
  def evidence(entry: DispatchExecution, report: ChildReport): Task[CollectedEvidence] =
    if (!working(entry.ticket)) ZIO.succeed(CollectedEvidence(RetainedEvidence(Nil, Nil), Nil))
    else workspaces.get(config.owner, entry.ticket.attempt.id).flatMap { workspace => ZIO.attemptBlocking {
      val named = report match { case ChildReport.Work(members) => members.flatMap(_.evidence); case _ => Nil }
      new WorkspaceEvidence(config.project.project, entry.ticket.attempt.id, "evidence").collect(Path.of(workspace.directory), named)
    }}

  /** The candidate of a work report with a ready member: everything in the workspace but `.work`, committed by the host on its base. */
  def capture(entry: DispatchExecution, members: List[WorkMember], input: ChildInput, combination: Option[CombinationPlan]): Task[Option[GitCommit]] =
    if (!members.exists(_.disposition == WorkDisposition.CandidateReady)) ZIO.succeed(None)
    else workspaces.get(config.owner, entry.ticket.attempt.id).flatMap { workspace => ZIO.attemptBlocking {
      entry.check()
      combination.foreach { _ =>
        require(Set("0\n", "1\n")(HostFiles.text(entry.directory.resolve("assets/merge-ready"), 2)), "Merge preparation was not confirmed")
      }
      val commit = candidates.capture(workspace, combination, CandidateMessage(entry.ticket.attempt.id, input.members, input.guidance, combination))
      HostFiles.immutable(entry.directory.resolve("candidate.json"), HostFiles.encode(GitCommit_JsonCodec, commit), 1024)
      Some(commit)
    }}

  /** Every configured check on the captured candidate, each in a workspace of its own. */
  def checked(entry: DispatchExecution, candidate: GitCommit, trace: Ref[AttemptTrace]): Task[List[ValidationEvidence]] =
    ZIO.succeed(entry.phase(DispatchPhase.Validating)) *> ZIO.foreach(config.settings.checks.zipWithIndex) { case (check, index) =>
      validate(entry, candidate, check, index, trace)
    }

  private def validate(entry: DispatchExecution, candidate: GitCommit, check: ValidationCheck, index: Int, trace: Ref[AttemptTrace]): Task[ValidationEvidence] = for {
    validated <- validation(entry.ticket.attempt.id, s"check-$index", candidate, check, (id, base, command) => launch(entry, id, base, command).ensuring(release(id).ignore)
      .tap(record => trace.update(value => value.copy(spans = value.spans :+ PhaseSpans.check(record, entry.ticket.assignment.id)))))
    evidence = validated.evidence
    // A check that could not be started ran nothing: its cleanup is not in doubt and the result stands.
    uncertain = evidence.state == ValidationState.Unknown && validated.unrun.isEmpty
    _ <- trace.update(value => value.copy(extra = value.extra ++ validated.artifacts, uncertain = value.uncertain || uncertain, unrun = value.unrun ++ validated.unrun))
    _ <- ZIO.attempt(require(!uncertain, "Host validation cleanup is unconfirmed"))
  } yield evidence

  /** The result of the attempt under its claim as it stands now, checked against the contract of its work. */
  def stored(entry: DispatchExecution, base: GitCommit, candidate: Option[GitCommit], report: ChildReport, validation: List[ValidationEvidence],
    evidence: RetainedEvidence): Task[ChildResult] = ZIO.attemptBlocking {
    entry.check()
    claim(entry, true)
    val value = ChildResult(entry.ticket.attempt.id, entry.ticket.request, base, candidate, report, validation, evidence)
    ChildContracts.result(config.project.project, value)
    value
  }

  /** Keeps the workspace of an attempt that left no result: what is in it is not captured and is never removed by the host. */
  def quarantine(entry: DispatchExecution, reason: String): Task[Unit] =
    workspaces.quarantine(config.owner, entry.ticket.attempt.id, reason).unit.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }

  def publish(entry: DispatchExecution, result: Either[Throwable, ChildResult], trace: AttemptTrace): Task[Unit] = {
    val attempt = entry.ticket.attempt
    val own = AttemptSettlement.own(entry.ticket)
    for {
      job <- trace.native match {
        case Some(value) => ZIO.succeed(Some(value))
        case None => jobs.await(config.owner, attempt.id).map(Some(_)).catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
      }
      workspace <- workspaces.get(config.owner, attempt.id).map(Some(_)).catchSome { case DomainFailure(_: Fault.Missing) => ZIO.succeed(None) }
      _ <- ZIO.attemptBlocking {
        val cancelled = entry.freeze()
        val project = config.project.project
        val stdout = transcript(attempt.id, "stdout", entry.ticket.request.limits.retainedOutputBytes)
        val stderr = transcript(attempt.id, "stderr", entry.ticket.request.limits.retainedOutputBytes)
        // The governing session's own work ran no process: it has no native output to retain.
        val native = if (own) Nil else NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)._2 ++
          NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)._2
        val collectedAt = math.max(attempt.startedAt, clock.millis())
        val usage = collect(entry.ticket, collectedAt)
        val problem = cancelled.orElse(result.left.toOption.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
          .orElse(trace.unrun.headOption).map(DispatchProjection.concise)
        val valid = if (cancelled.nonEmpty) None else result.toOption
        val observed = job.map(JobOutcome.observed)
        val abstention = result.left.toOption.collect { case value: Abstention => value }
        val state = if (trace.uncertain || observed.exists(_.state == AttemptState.Unknown)) AttemptState.Unknown
          else if (cancelled.nonEmpty) AttemptState.Cancelled
          else if (abstention.nonEmpty) AttemptState.Abstained
          else observed.map(_.withResult(valid.nonEmpty)).getOrElse(if (own && valid.nonEmpty) AttemptState.Completed else AttemptState.Failed)
        if (state == AttemptState.Abstained) abstention.foreach(entry.abstained)
        // Work that failed, abstained or was cancelled leaves no result; its workspace state is retained so a following attempt can continue from it.
        val partial = workspace.filter(_ => working(entry.ticket) && Set(AttemptState.Failed, AttemptState.Cancelled, AttemptState.Abstained)(state))
          .map(record => Try(partials.capture(attempt.id, state, Path.of(record.directory), stdout, stderr)).toEither)
        val partialGap = partial.flatMap(_.left.toOption).map(error => "Partial work collection failed: " + Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
        val allArtifacts = native ++ trace.extra ++ partial.flatMap(_.toOption).toList.flatMap(_._2)
        val observations = usage.meters.flatMap(batch => HostDelivery.Usage(HostUsageInput(project, HostUsage.Meter(batch.meter))) ::
          batch.observations.map(value => HostDelivery.Usage(HostUsageInput(project, HostUsage.Ingest(value)))))
        // The attempt ends when its native job settles; host checks that follow are spans of their own.
        val finishedAt = job.filter(_.phase == JobPhase.Settled).fold(collectedAt)(record => math.max(attempt.startedAt, record.updatedAt))
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id, state, finishedAt,
          (problem.toList ++ observed.toList.flatMap(_.problem).map(DispatchProjection.concise) ++ partialGap.map(DispatchProjection.concise) ++ usage.gaps).take(MaxGaps), None)
        val entries = allArtifacts.map(HostDelivery.Artifact.apply) ++ observations ++ trace.spans.map(PhaseSpans.delivery(project, _))
        val base = entry.status.copy(process = job.map(_.phase), blocker = problem, partial = partial.flatMap(_.toOption).map(_._1),
          usageDelivered = false, detailsOmitted = true, workspace = workspace.map(DispatchProjection.workspace))
        val publication = new ChildPublicationDelivery(entry.directory, entry.ticket)
        publication.seal(ChildPublication(project, config.owner.actor, valid, base, outcome), entries)
        val retained = Try(publication.finish(authority.collector)).toOption.map(_.status).getOrElse(base.copy(
          phase = DispatchPhase.PublicationPending, next = ChildNext.RetryDelivery, result = None,
          blocker = Some("Result admission or operational publication is pending; retain the session directory and run cq job upload")))
        entry.finish(retained)
      }
      _ <- if (entry.status.phase == DispatchPhase.Failed && result.isRight)
        workspaces.quarantine(config.owner, entry.ticket.attempt.id, (AttemptSettlement.Refused + entry.status.blocker.getOrElse("it stated no reason")).take(LedgerPolicy.MaxTitle))
          .map(record => entry.finish(entry.status.copy(workspace = Some(DispatchProjection.workspace(record)))))
      // A workspace the governing session handed back without a ready candidate holds what the session wrote and nothing the host
      // captured: unlike a child's, it is kept.
      else if (entry.status.phase == DispatchPhase.Completed && own && working(entry.ticket) && result.exists(_.candidate.isEmpty))
        workspaces.quarantine(config.owner, entry.ticket.attempt.id, AttemptSettlement.Uncaptured)
          .map(record => entry.finish(entry.status.copy(workspace = Some(DispatchProjection.workspace(record)))))
      // The candidate is a commit under refs/cq/candidates and the evidence is published: nothing reads a completed attempt's tree again.
      else if (entry.status.phase == DispatchPhase.Completed) released(entry.ticket)
        .map(_.foreach(record => entry.finish(entry.status.copy(workspace = Some(DispatchProjection.workspace(record)))))).ignore
      else ZIO.unit
    } yield ()
  }
}

private[server] object AttemptSettlement {
  /** An attempt of the Governor role under the governing attempt is the governing session's own work: no process ran for it. */
  def own(ticket: DispatchTicket): Boolean = GoverningTickets.own(ticket)
  /** What is said of the usage of such an attempt, with its outcome and wherever its usage is read. */
  val UnmeteredGap = "No meter: the work was done in the governing session, whose usage is that session's own"
  /** Why the workspace of a result the server refused is kept; the server's reason follows. */
  val Refused = "The server refused the result: "
  /** Why a workspace the governing session submitted without a ready candidate is kept. */
  val Uncaptured = "The governing session submitted this workspace without a ready candidate; nothing was captured and its content is retained here"
  val Unmetered: CollectedUsage = CollectedUsage(Nil, false, false, List(UnmeteredGap), None)
}
