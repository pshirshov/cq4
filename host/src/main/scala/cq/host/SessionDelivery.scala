package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import java.io.ByteArrayInputStream
import java.nio.file.{Files, LinkOption, Path}
import java.time.Clock
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.{IO, Task, ZIO}

final case class SessionDeliveryReport(acknowledged: Int, incompleteTickets: List[Path])

/** The caller holds the journal's exclusive ownership for this entire operation. */
final class SessionDelivery(journal: JobRepository, workspaces: WorkspaceService[IO], clock: Clock) {
  private val MaxChildren = 32
  private val MaxPartialTicketFiles = 32
  private val MaxRecordBytes = 64 * 1024
  private val MaxOutputBytes = 32 * 1024 * 1024
  private val MaxGaps = 32
  private val Interrupted = "Supervisor publication was interrupted; retained output is a bounded snapshot, process settlement and remaining usage are unknown"
  private final case class Publication(assignment: Assignment, attempt: Attempt, version: String, queue: DeliveryQueue, child: Option[ChildPublicationDelivery])

  private final case class Inventory(publications: List[Publication], incompleteTickets: List[Path])

  private def inventory(directory: Path, run: SupervisorRun): Inventory = {
    require(run.assignment.project == run.project.project && run.attempt.assignment == run.assignment.id &&
      run.attempt.parent.isEmpty && run.attempt.role == Role.Governor, "Invalid governing publication identity")
    val governing = Publication(run.assignment, run.attempt, run.harnessVersion, new DeliveryQueue(directory.resolve("delivery")), None)
    val root = directory.resolve("children")
    val (children, incomplete) = if (!Files.exists(root)) (Nil, Nil) else {
      require(Files.isDirectory(root) && !Files.isSymbolicLink(root), "Child delivery root must be a directory")
      val paths = Using.resource(Files.list(root))(_.iterator().asScala.take(MaxChildren + 1).toList)
      require(paths.size <= MaxChildren, "Child delivery inventory exceeds its bound")
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
          ticket.attempt.role == ChildContracts.role(ticket.request.work) && ticket.attempt.harness == ticket.profile.harness &&
          ticket.attempt.model == ticket.profile.model && ticket.attempt.provider == ticket.profile.provider,
          "Child delivery ticket has another assignment or governing owner")
        Publication(ticket.assignment, ticket.attempt, ticket.profile.version, new DeliveryQueue(child.resolve("delivery")), Some(new ChildPublicationDelivery(child, ticket)))
      }
      (publications, incomplete)
    }
    Inventory(governing :: children, incomplete)
  }

  private def quarantine(owner: Scope, attempt: AttemptId): Task[Unit] =
    workspaces.get(owner, attempt).flatMap { value =>
      if (value.admission == WorkspaceAdmission.Quarantined) ZIO.unit
      else workspaces.quarantine(owner, attempt, Interrupted).unit
    }.catchSome { case DomainFailure(_: Fault.Missing) => ZIO.unit }

  private def snapshot(path: Path): (Array[Byte], List[String]) = {
    if (!Files.exists(path)) (Array.emptyByteArray, List(s"Native ${path.getFileName} was absent at reconciliation"))
    else {
      require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Native snapshot source must be a regular file")
      val bytes = Using.resource(Files.newInputStream(path))(_.readNBytes(MaxOutputBytes))
      (bytes, Nil)
    }
  }

  private def reconcile(directory: Path, publication: Publication, ownership: SessionOwnership): Unit = {
    if (ownership == SessionOwnership.Attached && publication.child.isEmpty) {
      val project = publication.assignment.project
      val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(publication.attempt.id, "outcome").value), publication.attempt.id,
        AttemptState.Unknown, math.max(publication.attempt.startedAt, clock.millis()),
        List("Attached owner observation interrupted; outer model completion and remaining usage are unobserved; retained Pi usage samples are replayed independently; no native Governor process was launched"), None)
      publication.queue.commit(List(HostDelivery.Usage(HostUsageInput(project, HostUsage.Assign(publication.assignment))),
        HostDelivery.Usage(HostUsageInput(project, HostUsage.Start(publication.attempt))),
        HostDelivery.Usage(HostUsageInput(project, HostUsage.Finish(outcome)))))
      return
    }
    val attempt = publication.attempt
    val project = publication.assignment.project
    val payload = directory.resolve("payload").resolve(attempt.id.value.toString)
    val (stdout, outGaps) = snapshot(payload.resolve("stdout"))
    val (stderr, errGaps) = snapshot(payload.resolve("stderr"))
    val (nativeId, outParts) = NativeArtifacts.binary(project, attempt.id, "stdout", "application/x-ndjson", stdout)
    val (_, errParts) = NativeArtifacts.binary(project, attempt.id, "stderr", "application/octet-stream", stderr)
    val collectedAt = math.max(attempt.startedAt, clock.millis())
    val usage = new HarnessUsage().collect(new ByteArrayInputStream(stdout), UsageCollectionRequest(attempt.id,
      attempt.harness, publication.version, UsageOrigin.Fresh, collectedAt, nativeId))
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
          found.sortBy(_.getFileName.toString)
        }
      }
      results <- ZIO.foreach(paths) { path => ZIO.attemptBlocking {
        val name = path.getFileName.toString
        val id = AttemptId(NativeArtifacts.id(publication.attempt.id, "declared-check-job-" + name).value)
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
            ticket.check.outputBytes > 0 && ticket.check.outputBytes <= 1024 * 1024 && ticket.fingerprint.matches("[0-9a-f]{64}") &&
            ticket.workspace == WorkspaceSpec(run.project.project, run.attempt.session, id, run.repository, native.workspace.base),
            "Declared check ticket differs from its owner, configuration or reviewed candidate")
          val pending = new DeclaredCheckPublication(path, ticket, directory.resolve("payload"))
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
      ZIO.attemptBlocking {
        if (record.phase != JobPhase.Uncertain) journal.replace(record, record.copy(target = JobTarget.Stop, phase = JobPhase.Uncertain,
          problem = Some(Interrupted), revision = Math.addExact(record.revision, 1), updatedAt = math.max(record.updatedAt, clock.millis())))
      } *> quarantine(owner, record.workspace.attempt)
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
    attached <- (if (run.ownership == SessionOwnership.Attached) ZIO.attemptBlocking(new AttachedUsage(directory, run, clock).recover(api)) else ZIO.succeed(0)).either
    recovered <- independent(checked ++ (delivered :+ attached).map(_.map(count => SessionDeliveryReport(count, Nil))))
  } yield SessionDeliveryReport(recovered.map(_.acknowledged).sum, inventory.incompleteTickets ++ recovered.flatMap(_.incompleteTickets))
}
