package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import java.nio.file.{Files, LinkOption, Path}
import java.time.Clock
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{IO, Task, ZIO}

final case class SessionDeliveryReport(acknowledged: Int, incompleteTickets: List[Path])

/** What `SessionDelivery.flush` would still do for a session. `incomplete` counts the tickets and usage samples whose record was never
  * committed: they can never be delivered, and `flush` reports them instead. */
final case class SessionRemainder(undelivered: Boolean, incomplete: Int)

object SessionDelivery {
  val Interrupted = "Supervisor publication was interrupted; retained output is a bounded snapshot, process settlement and remaining usage are unknown"

  private def entries(directory: Path): List[Path] =
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) Nil else Using.resource(Files.list(directory))(_.iterator().asScala.toList)
  private def has(directory: Path, name: String): Boolean = Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS)
  /** A batch directly under `directory` has no acknowledgement. */
  def unacknowledged(directory: Path): Boolean = {
    val names = entries(directory).map(_.getFileName.toString).toSet
    names.exists(name => name.endsWith(".json") && !names(name.stripSuffix(".json") + ".ack"))
  }
  /** The queue's final publication is committed and the server acknowledged it and every batch before it. */
  private def delivered(queue: Path): Boolean =
    Files.isDirectory(queue.resolve("final"), LinkOption.NOFOLLOW_LINKS) && !unacknowledged(queue) && !unacknowledged(queue.resolve("final"))

  /**
   * Reads, without changing anything, whether `flush` has something left to deliver: the governing final publication; a child's
   * publication (a child whose owner died before sealing it never gets a receipt, and is delivered once its reconciled outcome is
   * acknowledged); a run of a declared check; an attached Pi or Codex usage sample; a retained span.
   */
  def remainder(directory: Path, run: SupervisorRun): SessionRemainder = {
    val (children, unticketed) = entries(directory.resolve("children")).partition(has(_, "ticket.json"))
    val (checks, partialChecks) = children.flatMap { child =>
      entries(child.resolve("checks")).flatMap { check =>
        check :: (2 to IntegrationValidation.MaxAttempts).map(DeclaredCheckPublication.directory(check, _)).filter(Files.exists(_, LinkOption.NOFOLLOW_LINKS)).toList
      }
    }.partition(has(_, "ticket.json"))
    val attached = run.ownership == SessionOwnership.Attached
    val codex = directory.resolve("codex-usage")
    val (samples, partialSamples) = ((if (attached) entries(directory.resolve("pi-usage")) else Nil) ++
      (if (attached && run.attempt.harness == Harness.Codex && has(codex, "binding.json")) entries(codex.resolve("samples")) else Nil)).partition(has(_, "sample.json"))
    val undelivered = !delivered(directory.resolve("delivery")) ||
      children.exists(child => !has(child, "receipt.json") && (has(child, "publication.json") || !delivered(child.resolve("delivery")))) ||
      checks.exists(check => !has(check, "result.json") || !delivered(check.resolve("delivery"))) ||
      samples.exists(sample => !delivered(sample.resolve("delivery"))) ||
      entries(directory.resolve("spans")).exists(unacknowledged)
    SessionRemainder(undelivered, unticketed.size + partialChecks.size + partialSamples.size)
  }
}

/** The caller holds the journal's exclusive ownership for this entire operation. */
final class SessionDelivery(journal: JobRepository, workspaces: WorkspaceService[IO], clock: Clock) {
  private val MaxPartialTicketFiles = 32
  private val MaxRecordBytes = 64 * 1024
  private val MaxGaps = 32
  import SessionDelivery.Interrupted
  private final case class Publication(assignment: Assignment, attempt: Attempt, version: Option[String], retainedOutputBytes: Option[Int], queue: DeliveryQueue, child: Option[ChildPublicationDelivery])

  private final case class Inventory(publications: List[Publication], incompleteTickets: List[Path])

  private def inventory(directory: Path, run: SupervisorRun): Inventory = {
    require(run.assignment.project == run.project.project && run.attempt.assignment == run.assignment.id &&
      run.attempt.parent.isEmpty && run.attempt.role == Role.Governor, "Invalid governing publication identity")
    val governing = Publication(run.assignment, run.attempt, Some(run.harnessVersion), None, new DeliveryQueue(directory.resolve("delivery")), None)
    val root = directory.resolve("children")
    val (children, incomplete) = if (!Files.exists(root)) (Nil, Nil) else {
      require(Files.isDirectory(root) && !Files.isSymbolicLink(root), "Child delivery root must be a directory")
      val paths = Using.resource(Files.list(root))(_.iterator().asScala.toList)
      paths.foreach { child =>
        require(Files.isDirectory(child) && !Files.isSymbolicLink(child) &&
          UUID.fromString(child.getFileName.toString).toString == child.getFileName.toString, "Child delivery path must be an attempt directory")
      }
      val (complete, incomplete) = paths.sortBy(_.getFileName.toString).partition(path => Files.exists(path.resolve("ticket.json"), LinkOption.NOFOLLOW_LINKS))
      incomplete.foreach { child =>
        val pending = Using.resource(Files.list(child))(_.iterator().asScala.take(MaxPartialTicketFiles + 1).toList)
        require(pending.size <= MaxPartialTicketFiles && pending.forall(path => Files.isRegularFile(path) && !Files.isSymbolicLink(path) &&
          (if (path.getFileName.toString == "cancel.txt") HostFiles.text(path, 32) == "stop\n"
           else path.getFileName.toString.matches("\\.upload-.+\\.pending") && Files.size(path) <= MaxRecordBytes)),
          "Child without a committed ticket contains unexpected evidence; manual inspection required")
      }
      val publications = complete.map { child =>
        val ticket = HostFiles.read(child.resolve("ticket.json"), DispatchTicket_JsonCodec, MaxRecordBytes)
        ChildContracts.request(run.project.project, ticket.request)
        require(child.getFileName.toString == ticket.attempt.id.value.toString && ticket.attempt.session == run.attempt.session &&
          ticket.attempt.parent.contains(run.attempt.id) && ticket.assignment.project == run.project.project &&
          ticket.attempt.assignment == ticket.assignment.id && ticket.assignment.members == ticket.request.members.map(_.id).toSet &&
          ticket.attempt.role == ChildContracts.role(ticket.request.work) && ticket.attempt.harness == ticket.request.harness &&
          ticket.profile.forall(_.harness == ticket.attempt.harness),
          "Child delivery ticket has another assignment or governing owner")
        Publication(ticket.assignment, ticket.attempt, HarnessUsage.launchable(ticket.profile).map(_.version), Some(ticket.request.limits.retainedOutputBytes), new DeliveryQueue(child.resolve("delivery")),
          Some(new ChildPublicationDelivery(child, ticket)))
      }
      (publications, incomplete)
    }
    Inventory(governing :: children, incomplete)
  }

  private def quarantine(owner: Scope, attempt: AttemptId): Task[Unit] =
    workspaces.get(owner, attempt).flatMap { value =>
      if (value.admission != WorkspaceAdmission.Open) ZIO.unit
      else workspaces.quarantine(owner, attempt, Interrupted).unit
    }.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }

  private def snapshot(path: Path, bound: => Int): (Array[Byte], List[String]) =
    if (Files.exists(path)) (NativeTranscript.retained(path, bound), Nil) else (Array.emptyByteArray, List(s"Native ${path.getFileName} was absent at reconciliation"))

  private def reconcile(directory: Path, publication: Publication, ownership: SessionOwnership): Unit = {
    if (ownership == SessionOwnership.Attached && publication.child.isEmpty) {
      val project = publication.assignment.project
      val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(publication.attempt.id, "outcome").value), publication.attempt.id,
        AttemptState.Unknown, math.max(publication.attempt.startedAt, clock.millis()),
        List("Attached owner observation interrupted; outer model completion and remaining usage are unobserved; retained native usage samples are replayed independently; no native Governor process was launched"), None)
      publication.queue.commit(List(HostDelivery.Usage(HostUsageInput(project, HostUsage.Assign(publication.assignment))),
        HostDelivery.Usage(HostUsageInput(project, HostUsage.Start(publication.attempt))),
        HostDelivery.Usage(HostUsageInput(project, HostUsage.Finish(outcome)))))
      return
    }
    val attempt = publication.attempt
    val project = publication.assignment.project
    val payload = directory.resolve("payload").resolve(attempt.id.value.toString)
    // A child retains what its dispatch request bounds; the managed governor retains what the session settings bound.
    lazy val bound = publication.retainedOutputBytes.getOrElse(
      HostFiles.read(directory.resolve("settings.json"), SupervisorSettings_JsonCodec, MaxRecordBytes).limits.retainedOutputBytes)
    val (stdout, outGaps) = snapshot(payload.resolve("stdout"), bound)
    val (stderr, errGaps) = snapshot(payload.resolve("stderr"), bound)
    val (nativeId, outParts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
    val (_, errParts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
    val collectedAt = math.max(attempt.startedAt, clock.millis())
    val usage = publication.version.fold(HarnessUsage.Unlaunched) { version =>
      Using.resource(NativeTranscript.stream(payload.resolve("stdout")))(new HarnessUsage().collect(_, UsageCollectionRequest(attempt.id,
        attempt.harness, version, UsageOrigin.Fresh, collectedAt, nativeId)))
    }
    def entry(value: HostUsage): HostDelivery = HostDelivery.Usage(HostUsageInput(project, value))
    val observations = usage.meters.flatMap { batch =>
      entry(HostUsage.Meter(batch.meter)) :: batch.observations.map { upload =>
        val observed = upload.observation
        entry(HostUsage.Ingest(upload.copy(observation = observed.copy(
          completeness = if (observed.completeness == UsageCompleteness.Complete) UsageCompleteness.Partial else observed.completeness,
          gaps = (Interrupted :: observed.gaps).take(MaxGaps)))))
      }
    }
    val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(attempt.id, "outcome").value), attempt.id, AttemptState.Unknown,
      collectedAt, (List(Interrupted) ++ outGaps ++ errGaps ++ usage.gaps).take(MaxGaps), None)
    publication.queue.commit(List(entry(HostUsage.Assign(publication.assignment)), entry(HostUsage.Start(attempt))) ++
      (outParts ++ errParts).map(HostDelivery.Artifact.apply) ++ observations :+ entry(HostUsage.Finish(outcome)))
  }

  private def independent[A](values: List[Either[Throwable, A]]): Task[List[A]] = ZIO.attempt {
    val failures = values.collect { case Left(error) => error }
    if (failures.nonEmpty) {
      val error = failures.head
      failures.tail.filterNot(_ eq error).foreach(error.addSuppressed)
      throw error
    }
    values.collect { case Right(value) => value }
  }

  private def uncertain(record: JobRecord): JobRecord = if (record.phase == JobPhase.Uncertain) record else {
    val next = record.copy(target = JobTarget.Stop, phase = JobPhase.Uncertain, problem = Some(Interrupted),
      revision = Math.addExact(record.revision, 1), updatedAt = math.max(record.updatedAt, clock.millis()))
    journal.replace(record, next)
    next
  }

  /** Records every job the ended owner left unsettled as `Uncertain`, because nothing observed its termination, and returns the
    * jobs it changed. `flush` does the same before it delivers; this part needs no server. */
  def interrupt(): List[JobRecord] = journal.records.filterNot(record => JobRecords.terminal(record.phase)).map(uncertain)

  private def checks(directory: Path, run: SupervisorRun, publication: Publication, api: ServerApi): Task[SessionDeliveryReport] = {
    val root = directory.resolve("children").resolve(publication.attempt.id.value.toString).resolve("checks")
    for {
      paths <- ZIO.attemptBlocking {
        if (publication.child.isEmpty || !Files.exists(root)) Nil
        else {
          require(!Files.isSymbolicLink(root) && Files.isDirectory(root), "Declared check inventory must be a directory")
          val found = Using.resource(Files.list(root))(_.iterator().asScala.take(9).toList)
          require(found.size <= 8 && found.forall(path => !Files.isSymbolicLink(path) && Files.isDirectory(path) &&
            path.getFileName.toString.matches("[a-z][a-z0-9-]{0,49}")), "Invalid declared check inventory")
          // Each check has its first run in its own directory and every rerun of a failed run in a directory below it.
          found.sortBy(_.getFileName.toString).flatMap { check =>
            val reruns = (2 to IntegrationValidation.MaxAttempts).map(number => (check.getFileName.toString, DeclaredCheckPublication.directory(check, number), number))
              .filter((_, rerun, _) => Files.exists(rerun, LinkOption.NOFOLLOW_LINKS))
            require(reruns.forall((_, rerun, _) => !Files.isSymbolicLink(rerun) && Files.isDirectory(rerun)), "Invalid declared check inventory")
            (check.getFileName.toString, check, 1) :: reruns.toList
          }
        }
      }
      results <- ZIO.foreach(paths) { case (name, path, number) => ZIO.attemptBlocking {
        val id = DeclaredCheckPublication.job(publication.attempt.id, name, number)
        val records = journal.records
        val record = records.find(_.workspace.attempt == id)
        val ticketFile = path.resolve("ticket.json")
        if (!Files.exists(ticketFile)) {
          val files = Using.resource(Files.list(path))(_.iterator().asScala.take(MaxPartialTicketFiles + 1).toList)
          require(record.isEmpty && files.size <= MaxPartialTicketFiles && files.forall(file => Files.isRegularFile(file) &&
            !Files.isSymbolicLink(file) && file.getFileName.toString.matches("\\.upload-.+\\.pending") && Files.size(file) <= MaxRecordBytes),
            "Uncommitted declared check ticket has unexpected evidence")
          SessionDeliveryReport(0, List(path))
        } else {
          val ticket = HostFiles.read(ticketFile, DeclaredCheckTicket_JsonCodec, MaxRecordBytes)
          val dispatch = HostFiles.read(root.getParent.resolve("ticket.json"), DispatchTicket_JsonCodec, MaxRecordBytes)
          val settings = HostFiles.read(directory.resolve("settings.json"), SupervisorSettings_JsonCodec, MaxRecordBytes)
          val native = records.find(_.workspace.attempt == publication.attempt.id)
            .getOrElse(throw new IllegalStateException("Declared check has no governing reviewer job"))
          require(dispatch.request.work == DispatchWork.Reviewer(ReviewerMode.Candidate) && ticket.parent == publication.attempt.id &&
            ticket.check.name == name && settings.checks.find(_.name == name).contains(ticket.check) &&
            ticket.failures == DeclaredCheckPublication.earlier(publication.attempt.id, name, number) &&
            ticket.check.retainedOutputBytes > 0 && ticket.check.retainedOutputBytes <= 1024 * 1024 && ticket.fingerprint.matches("[0-9a-f]{64}") &&
            ticket.workspace == WorkspaceSpec(run.project.project, run.attempt.session, id, run.repository, native.workspace.base),
            "Declared check ticket differs from its owner, configuration or reviewed candidate")
          val pending = new DeclaredCheckPublication(path, ticket, publication.assignment.id, directory.resolve("payload"))
          pending.reconcile(record)
          SessionDeliveryReport(pending.finish(api).acknowledged, Nil)
        }
      }.either }
      recovered <- independent(results)
    } yield SessionDeliveryReport(recovered.map(_.acknowledged).sum, recovered.flatMap(_.incompleteTickets))
  }

  def flush(directory: Path, run: SupervisorRun, api: ServerApi): Task[SessionDeliveryReport] = for {
    inventory <- ZIO.attemptBlocking(inventory(directory, run))
    owner = Scope(run.project.project, Actor("CQ recovery", run.attempt.session, Role.Governor))
    records <- ZIO.attemptBlocking {
      val records = journal.records
      require(!inventory.incompleteTickets.exists(path => records.exists(_.workspace.attempt.value.toString == path.getFileName.toString)),
        "Job exists without its committed dispatch ticket; manual inspection required")
      records
    }
    _ <- ZIO.foreachDiscard(records.filter(_.phase != JobPhase.Settled)) { record =>
      ZIO.attemptBlocking(uncertain(record)) *> quarantine(owner, record.workspace.attempt)
    }
    checked <- ZIO.foreach(inventory.publications)(publication => checks(directory, run, publication, api).either)
    delivered <- ZIO.foreach(inventory.publications) { publication =>
      (publication.child.filter(_.sealedIntent) match {
        case Some(child) => for {
          receipt <- ZIO.attemptBlocking(child.finish(api))
          _ <- if (receipt.status.phase == DispatchPhase.Completed) ZIO.unit else quarantine(owner, publication.attempt.id)
        } yield receipt.acknowledged
        case None => for {
          committed <- ZIO.attemptBlocking(publication.queue.finalized)
          _ <- if (committed) ZIO.unit else quarantine(owner, publication.attempt.id) *> ZIO.attemptBlocking(reconcile(directory, publication, run.ownership))
          count <- ZIO.attemptBlocking(publication.queue.flush(api))
        } yield count
      }).either
    }
    attached <- (if (run.ownership == SessionOwnership.Attached) ZIO.attemptBlocking(new AttachedUsage(directory, run, clock).recover(api)) else ZIO.succeed(SessionDeliveryReport(0, Nil))).either
    codex <- ZIO.attemptBlocking(Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, clock))(_.recover(api))).either
    spans <- ZIO.attemptBlocking(SessionDeliveryReport(new SpanDelivery(directory.resolve("spans"), run.project.project).flush(api), Nil)).either
    recovered <- independent(checked ++ delivered.map(_.map(count => SessionDeliveryReport(count, Nil))) ++ List(attached, codex, spans))
  } yield SessionDeliveryReport(recovered.map(_.acknowledged).sum, inventory.incompleteTickets ++ recovered.flatMap(_.incompleteTickets))
}
