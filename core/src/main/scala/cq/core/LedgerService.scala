package cq.core

import cq.api.*
import izumi.functional.bio.Error2
import java.time.Clock
import java.nio.charset.StandardCharsets

trait LedgerService[F[_, _]] {
  def initialize(scope: Scope, name: String): F[Throwable, Project]
  def rename(scope: Scope, expected: Revision, name: String): F[Throwable, Project]
  def requirements(scope: Scope): F[Throwable, ProjectRequirements]
  def replaceRequirements(scope: Scope, expected: Revision, text: String): F[Throwable, ProjectRequirements]
  def mode(scope: Scope): F[Throwable, ProjectMode]
  def replaceMode(scope: Scope, expected: Revision, value: ProjectSetting.Mode): F[Throwable, ProjectMode]
  def change(scope: Scope, request: ChangeRequest): F[Throwable, ChangeAck]
  def get(scope: Scope, id: ItemId): F[Throwable, ItemView]
  def details(scope: Scope, members: List[ItemRevision], bytes: Int): F[Throwable, ItemViews]
  def search(scope: Scope, query: String, after: Option[ItemId], limit: Int): F[Throwable, ItemPage]
  def browse(scope: Scope, query: String, order: ItemOrder, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int): F[Throwable, BrowsePage]
  def counts(scope: Scope): F[Throwable, LedgerCounts]
  def cursors(scope: Scope): F[Throwable, LedgerCursors]
  def complete(scope: Scope, query: String, cursor: Int, limit: Int): F[Throwable, QueryAnalysis]
  def termination(scope: Scope, roots: Set[ItemId], intent: TerminationIntent): F[Throwable, TerminationPreview]
  def archivePreview(scope: Scope, query: String, limit: Int): F[Throwable, ArchivePlan]
  def workset(scope: Scope, roots: Set[ItemId], after: Option[ItemId], snapshot: Option[WorksetSnapshot], limit: Int): F[Throwable, WorksetPage]
  def subgraphs(scope: Scope, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int): F[Throwable, SubgraphPage]
  def storeWorksetPreview(scope: Scope, preview: WorksetPreview): F[Throwable, StoredWorkset]
  def browseWorkset(scope: Scope, query: String, order: ItemOrder, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int, workset: WorksetId): F[Throwable, BrowsePage]
  def createWorkset(scope: Scope, targets: Set[ItemId], through: WorkflowPhase): F[Throwable, StoredWorkset]
  def lookupWorkset(scope: Scope, id: WorksetId): F[Throwable, StoredWorkset]
  def storedWorksets(scope: Scope, after: Option[WorksetId], limit: Int): F[Throwable, StoredWorksetPage]
  def previewWorkset(scope: Scope, target: WorksetTarget): F[Throwable, WorksetPreview]
  // Evaluates the workset on the ledger state after `change` inside one transaction that is always rolled back.
  def previewWorksetAfter(scope: Scope, target: WorksetTarget, change: ChangeRequest): F[Throwable, WorksetPreview]
  def history(scope: Scope, id: ItemId, before: Revision, limit: Int): F[Throwable, HistoryPage]
  def changes(scope: Scope, after: ChangeCursor, limit: Int): F[Throwable, ChangePage]
  def claimPreview(scope: Scope, members: Set[ItemId]): F[Throwable, ClaimPreview]
  def takeover(scope: Scope, id: ClaimId, owner: Actor, members: Set[ItemId], durationMillis: Long, snapshot: ClaimSnapshot): F[Throwable, Claim]
  def acquire(scope: Scope, id: ClaimId, members: Set[ItemId], durationMillis: Long): F[Throwable, Claim]
  def renew(scope: Scope, fence: Fence, durationMillis: Long): F[Throwable, Claim]
  def release(scope: Scope, fence: Fence): F[Throwable, Claim]
  // Driver control (the CQ hook commands and the Pi extension) and the driver operations of attached sessions.
  def drive(scope: Scope, request: DriverRequest): F[Throwable, DriverReply]
}

object LedgerService {
  private final case class Hypothetical(preview: WorksetPreview) extends RuntimeException("Hypothetical workset evaluation", null, false, false)

  final class Impl[F[+_, +_]: Error2](repository: LedgerRepository[F], clock: Clock, queries: QueryParser, completions: QueryCompleter, worksets: WorksetTraversal, terminationPlanner: TerminationPlanner, claimPlanner: ClaimPlanner, mutations: LedgerMutation, drivers: DriverService, boundary: DriverBoundary) extends LedgerService[F] {
    import LedgerPolicy.*
    import LedgerAccess.*

    override def initialize(scope: Scope, name: String): F[Throwable, Project] = {
      import izumi.functional.bio.{F, *}
      for {
        _ <- F.fromEither(scala.util.Try { write(scope); invalid(name.trim.nonEmpty && name.length <= MaxTitle, "Invalid project name") }.toEither)
        project <- repository.initialize(Project(scope.project, name, Revision(1), clock.millis()))
      } yield project
    }

    override def rename(scope: Scope, expected: Revision, name: String): F[Throwable, Project] = repository.transact(scope.project) { tx =>
      if (scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Project rename requires human authority"))
      invalid(name.trim.nonEmpty && name.length <= MaxTitle, "Invalid project name")
      if (tx.project.revision != expected) throw DomainFailure(Fault.Conflict("Project revision changed; refresh before renaming"))
      if (tx.project.name == name) tx.project
      else {
        val next = tx.project.copy(name = name, revision = Revision(Math.addExact(expected.value, 1L)))
        tx.renameProject(next)
        next
      }
    }

    // A project without a stored document has the empty text at revision 0.
    private def standing(tx: LedgerTransaction): ProjectRequirements = tx.setting(ProjectSettingKind.Requirements) match {
      case Some(StoredSetting(revision, ProjectSetting.Requirements(text), actor, updatedAt)) =>
        ProjectRequirements(tx.project.id, revision, text, Some(RequirementsChange(actor, updatedAt)))
      case Some(other) => throw new IllegalStateException(s"The standing requirements row holds a ${ProjectSettingKind.of(other.value)} document")
      case None => ProjectRequirements(tx.project.id, Revision(0), "", None)
    }

    override def requirements(scope: Scope): F[Throwable, ProjectRequirements] = repository.transact(scope.project)(standing)

    override def replaceRequirements(scope: Scope, expected: Revision, text: String): F[Throwable, ProjectRequirements] = repository.transact(scope.project) { tx =>
      if (scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Standing requirements change requires human authority"))
      validateRequirements(text)
      val current = standing(tx)
      if (current.revision != expected)
        throw DomainFailure(Fault.Conflict(s"Standing requirements changed: expected revision ${expected.value}, actual ${current.revision.value}; reload before saving"))
      if (current.text == text) current
      else {
        tx.putSetting(StoredSetting(Revision(Math.addExact(expected.value, 1L)), ProjectSetting.Requirements(text), scope.actor, clock.millis()))
        standing(tx)
      }
    }

    // A project without a stored document is Rigorous at revision 0.
    private def processMode(tx: LedgerTransaction): ProjectMode = tx.setting(ProjectSettingKind.Mode) match {
      case Some(StoredSetting(revision, ProjectSetting.Mode(value, selfReviewWithoutChecks), actor, updatedAt)) =>
        ProjectMode(tx.project.id, revision, value, selfReviewWithoutChecks, Some(ModeChange(actor, updatedAt)))
      case Some(other) => throw new IllegalStateException(s"The process mode row holds a ${ProjectSettingKind.of(other.value)} document")
      case None => ProjectMode(tx.project.id, Revision(0), ProcessModePolicy.Default.value, ProcessModePolicy.Default.selfReviewWithoutChecks, None)
    }

    override def mode(scope: Scope): F[Throwable, ProjectMode] = repository.transact(scope.project)(processMode)

    override def replaceMode(scope: Scope, expected: Revision, value: ProjectSetting.Mode): F[Throwable, ProjectMode] = repository.transact(scope.project) { tx =>
      if (scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Process mode change requires human authority"))
      ProcessModePolicy.validate(value)
      val current = processMode(tx)
      if (current.revision != expected)
        throw DomainFailure(Fault.Conflict(s"Process mode changed: expected revision ${expected.value}, actual ${current.revision.value}; reload before saving"))
      if (ProjectSetting.Mode(current.mode, current.selfReviewWithoutChecks) == value) current
      else {
        tx.putSetting(StoredSetting(Revision(Math.addExact(expected.value, 1L)), value, scope.actor, clock.millis()))
        processMode(tx)
      }
    }

    override def change(scope: Scope, request: ChangeRequest): F[Throwable, ChangeAck] =
      repository.transact(scope.project)(tx => mutations(tx, scope, request, clock.millis()))

    override def get(scope: Scope, id: ItemId): F[Throwable, ItemView] = repository.transact(scope.project) { tx =>
      ItemView(required(tx, scope, id), tx.refs(id))
    }

    override def details(scope: Scope, members: List[ItemRevision], bytes: Int): F[Throwable, ItemViews] = repository.transact(scope.project) { tx =>
      invalid(members.nonEmpty && members.size <= CohortBounds.Candidates && members.map(_.id).distinct.size == members.size, "Batch read requires 1–32 distinct members")
      invalid(bytes > 0 && bytes <= CohortBounds.CandidateBytes, "Batch read content budget must be 1–262144 bytes")
      val items = List.newBuilder[ItemView]
      val omitted = List.newBuilder[ItemRevision]
      var remaining = bytes
      members.foreach { member =>
        val item = required(tx, scope, member.id)
        if (item.revision != member.revision) throw DomainFailure(Fault.Conflict("Batch read member revision changed"))
        val view = ItemView(item, tx.refs(member.id))
        val size = ItemView_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, view).noSpaces.getBytes(StandardCharsets.UTF_8).length
        if (size <= remaining) { items += view; remaining -= size } else omitted += member
      }
      ItemViews(items.result(), omitted.result())
    }

    private val ArchiveScanLimit = 5000
    private def page(limit: Int): Unit = invalid(limit > 0 && limit <= MaxPage, s"Page size must be 1–$MaxPage")

    override def complete(scope: Scope, query: String, cursor: Int, limit: Int): F[Throwable, QueryAnalysis] =
      repository.transact(scope.project)(tx => completions.complete(tx, query, cursor, limit))

    override def termination(scope: Scope, roots: Set[ItemId], intent: TerminationIntent): F[Throwable, TerminationPreview] =
      repository.transact(scope.project)(tx => terminationPlanner.preview(tx, scope, roots, intent, clock.millis()))

    // Scans the query in ID order and partitions bulk-eligible matches into archivable members and retained ones.
    override def archivePreview(scope: Scope, query: String, limit: Int): F[Throwable, ArchivePlan] = {
      import izumi.functional.bio.{F, *}
      F.fromEither(queries.parse(query).left.map(error => DomainFailure(Fault.QuerySyntax(error)))).flatMap { expression =>
        repository.transact(scope.project) { tx =>
          invalid(limit > 0 && limit <= LedgerPolicy.MaxTouchedItems, s"Archive preview size must be 1–${LedgerPolicy.MaxTouchedItems}")
          val members = List.newBuilder[ItemSummary]
          val retained = List.newBuilder[ArchiveRetention]
          var selected = 0
          var scanned = 0
          var after: Option[ItemId] = None
          var more = true
          var truncated = false
          while (more && !truncated && scanned < ArchiveScanLimit) {
            val page = tx.scan(expression, after, MaxPage, clock.millis())
            page.entries.foreach { item =>
              scanned += 1
              if (!item.archived && LedgerPolicy.bulkArchivable(tx, item.id, item.status)) {
                if (selected >= limit) truncated = true
                else {
                  val open = LedgerPolicy.openRelated(tx, item.id)
                  if (open.isEmpty) members += item else retained += ArchiveRetention(item, open)
                  selected += 1
                }
              }
            }
            after = page.entries.lastOption.map(_.id)
            more = page.hasMore && page.entries.nonEmpty
          }
          ArchivePlan(members.result(), retained.result(), scanned, truncated || more, tx.cursor)
        }
      }
    }

    override def workset(scope: Scope, roots: Set[ItemId], after: Option[ItemId], snapshot: Option[WorksetSnapshot], limit: Int): F[Throwable, WorksetPage] =
      repository.transact(scope.project)(tx => worksets.page(tx, roots, after, snapshot, limit))

    private val planner = new WorksetPlanner(worksets)

    override def subgraphs(scope: Scope, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int): F[Throwable, SubgraphPage] =
      repository.transact(scope.project) { tx =>
        invalid(after.isEmpty || snapshot.nonEmpty, "Subgraph continuation requires its snapshot")
        if (snapshot.exists(_ != tx.cursor)) throw DomainFailure(Fault.Resync("Items changed; restart subgraph discovery"))
        planner.discover(tx, after, limit)
      }

    override def createWorkset(scope: Scope, targets: Set[ItemId], through: WorkflowPhase): F[Throwable, StoredWorkset] =
      repository.transact(scope.project) { tx =>
        write(scope)
        planner.create(tx, scope.actor, WorksetId(java.util.UUID.randomUUID()), targets, through, clock.millis())
      }

    override def storeWorksetPreview(scope: Scope, preview: WorksetPreview): F[Throwable, StoredWorkset] = repository.transact(scope.project) { tx =>
      write(scope)
      val current = planner.evaluate(tx, preview.targets, preview.through, preview.workset)
      if (current != preview) throw DomainFailure(Fault.Resync("Workset preview changed; preview again before storing"))
      planner.create(tx, scope.actor, WorksetId(java.util.UUID.randomUUID()), preview.targets, preview.through, clock.millis())
    }

    override def browseWorkset(scope: Scope, query: String, order: ItemOrder, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int, workset: WorksetId): F[Throwable, BrowsePage] = {
      import izumi.functional.bio.{F, *}
      F.fromEither(queries.parse(query).left.map(error => DomainFailure(Fault.QuerySyntax(error)))).flatMap { expression =>
        repository.transact(scope.project) { tx =>
          page(limit)
          invalid(after.isEmpty || snapshot.nonEmpty, "Browse continuation requires its snapshot")
          if (snapshot.exists(_ != tx.cursor)) throw DomainFailure(Fault.Resync("Items changed; restart sorted browse"))
          val selected = planner.resolve(tx, WorksetTarget.Stored(workset)).advanceable.map(_.item.id)
          def union(entries: List[QueryExpression]): QueryExpression = entries match {
            case Nil => QueryExpression.Not(QueryExpression.All())
            case one :: Nil => one
            case others =>
              val (left, right) = others.splitAt(others.size / 2)
              QueryExpression.Or(union(left), union(right))
          }
          val restriction = union(selected.map(id => QueryExpression.Id(QueryItem(id.ledger, id.number))))
          val query = QueryExpression.And(expression, restriction)
          val now = clock.millis()
          val anchor = after.map { id =>
            inScope(scope, id)
            tx.browseItem(id, now).getOrElse(throw DomainFailure(Fault.Missing("Browse continuation item does not exist")))
          }
          val found = tx.browse(query, order, anchor, limit, now)
          BrowsePage(found.entries, tx.cursor, found.entries.lastOption.map(_.summary.id), found.hasMore, tx.workCursor(now))
        }
      }
    }

    override def lookupWorkset(scope: Scope, id: WorksetId): F[Throwable, StoredWorkset] =
      repository.transact(scope.project)(tx => planner.stored(tx, id))

    override def storedWorksets(scope: Scope, after: Option[WorksetId], limit: Int): F[Throwable, StoredWorksetPage] =
      repository.transact(scope.project) { tx =>
        page(limit)
        val found = tx.storedWorksets(after, limit)
        StoredWorksetPage(found.entries, found.entries.lastOption.map(_.id), found.hasMore)
      }

    override def previewWorkset(scope: Scope, target: WorksetTarget): F[Throwable, WorksetPreview] =
      repository.transact(scope.project)(tx => planner.resolve(tx, target))

    override def previewWorksetAfter(scope: Scope, target: WorksetTarget, change: ChangeRequest): F[Throwable, WorksetPreview] = {
      import izumi.functional.bio.{F, *}
      repository.transact(scope.project) { tx =>
        mutations.hypothetical(tx, scope, change, clock.millis())
        throw Hypothetical(planner.resolve(tx, target))
      }.catchSome { case Hypothetical(preview) => F.pure(preview) }
    }

    override def search(scope: Scope, query: String, after: Option[ItemId], limit: Int): F[Throwable, ItemPage] = {
      import izumi.functional.bio.{F, *}
      F.fromEither(queries.parse(query).left.map(error => DomainFailure(Fault.QuerySyntax(error)))).flatMap { expression =>
        repository.transact(scope.project) { tx =>
          page(limit)
          after.foreach(inScope(scope, _))
          val found = tx.scan(expression, after, limit, clock.millis())
          ItemPage(found.entries, tx.cursor, found.entries.lastOption.map(_.id), found.hasMore)
        }
      }
    }

    override def browse(scope: Scope, query: String, order: ItemOrder, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int): F[Throwable, BrowsePage] = {
      import izumi.functional.bio.{F, *}
      F.fromEither(queries.parse(query).left.map(error => DomainFailure(Fault.QuerySyntax(error)))).flatMap { expression =>
        repository.transact(scope.project) { tx =>
          page(limit)
          invalid(after.isEmpty || snapshot.nonEmpty, "Browse continuation requires its snapshot")
          if (snapshot.exists(_ != tx.cursor)) throw DomainFailure(Fault.Resync("Items changed; restart sorted browse"))
          val now = clock.millis()
          val anchor = after.map { id =>
            inScope(scope, id)
            tx.browseItem(id, now).getOrElse(throw DomainFailure(Fault.Missing("Browse continuation item does not exist")))
          }
          val found = tx.browse(expression, order, anchor, limit, now)
          BrowsePage(found.entries, tx.cursor, found.entries.lastOption.map(_.summary.id), found.hasMore, tx.workCursor(now))
        }
      }
    }

    override def counts(scope: Scope): F[Throwable, LedgerCounts] = repository.transact(scope.project)(tx => LedgerCounts(tx.counts, tx.cursor))

    override def cursors(scope: Scope): F[Throwable, LedgerCursors] = repository.cursors(scope.project, clock.millis())

    override def history(scope: Scope, id: ItemId, before: Revision, limit: Int): F[Throwable, HistoryPage] = repository.transact(scope.project) { tx =>
      page(limit)
      required(tx, scope, id)
      val entries = tx.history(id, before, limit)
      HistoryPage(entries.entries, entries.hasMore)
    }

    override def changes(scope: Scope, after: ChangeCursor, limit: Int): F[Throwable, ChangePage] = repository.transact(scope.project) { tx =>
      page(limit)
      if (after.value < 0 || after.value > tx.cursor.value) throw DomainFailure(Fault.Resync("Cursor outside retained stream"))
      val entries = tx.changes(after, limit)
      ChangePage(entries.entries.lastOption.map(_.cursor).getOrElse(after), entries.entries, entries.hasMore)
    }

    private def duration(value: Long): Unit = invalid(value > 0 && value <= MaxClaimMillis, s"Claim duration must be 1–$MaxClaimMillis ms")

    override def acquire(scope: Scope, id: ClaimId, members: Set[ItemId], durationMillis: Long): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      write(scope)
      duration(durationMillis)
      invalid(members.nonEmpty && members.size <= MaxBatch, "Invalid claim membership")
      val now = clock.millis()
      tx.claimById(id) match {
        case Some(c) =>
          if (c.owner != scope.actor || c.members != members || c.origin != ClaimOrigin.Acquire(durationMillis))
            throw DomainFailure(Fault.Conflict("Claim identity reused with different acquisition intent"))
          if (!ClaimPolicy.active(tx, c, now)) throw DomainFailure(Fault.StaleFence("Acquire a new claim identity after expiry or release"))
          c
        case None =>
          members.foreach(id => inScope(scope, id))
          IntegrationPolicy.unreserved(tx, members)
          members.foreach { item =>
            required(tx, scope, item)
            if (tx.claim(item).exists(ClaimPolicy.active(tx, _, now))) throw DomainFailure(Fault.Conflict("Claim overlaps active work"))
          }
          val claim = Claim(Fence(id, tx.nextFence()), scope.actor, members, Math.addExact(now, durationMillis), false, ClaimOrigin.Acquire(durationMillis))
          tx.insertClaim(claim)
          boundary.claimed(tx, scope.actor.session, id, now)
          claim
      }
    }

    override def drive(scope: Scope, request: DriverRequest): F[Throwable, DriverReply] = {
      import izumi.functional.bio.{F, *}
      request match {
        // A status read touches no ledger state: it takes no project transaction, so status-line polling never waits for a ledger write.
        case DriverRequest.Control(key, origin, _: DriverControl.Status) => repository.driverRecords(scope.project).flatMap(records => F.fromEither(scala.util.Try(drivers.read(scope, key, origin, records)).toEither))
        case DriverRequest.Session(_: DriverSession.Status) => repository.driverRecords(scope.project).map(records => drivers.own(scope, records))
        case DriverRequest.Session(_: DriverSession.Settleable) => repository.driverRecords(scope.project).map(records => drivers.settleable(scope, records))
        case DriverRequest.Snapshot(key, expected) => repository.driverRecords(scope.project).flatMap { records =>
          F.fromEither(scala.util.Try {
            val record = records.find(_.key == key).getOrElse(throw DomainFailure(Fault.Missing("Driver does not exist")))
            if (record.revision != expected) throw DomainFailure(Fault.Conflict("Driver changed; refresh its snapshot"))
            val cycle = record.cycle.getOrElse(throw DomainFailure(Fault.Missing("Driver has no cycle snapshot")))
            DriverReply.Snapshot(cycle.snapshot, cycle.outcomes)
          }.toEither)
        }
        case _: DriverRequest.Summaries => repository.driverSummaries(scope.project).map(DriverReply.Listed.apply)
        case _ => repository.transact(scope.project) { tx =>
          val now = clock.millis()
          request match {
            case DriverRequest.Control(key, origin, action) => drivers.control(tx, scope, key, origin, action, now)
            case DriverRequest.Session(DriverSession.Change(cycle, change)) => DriverReply.Changed(cycle, mutations.attributed(tx, scope, change, now, cycle))
            case DriverRequest.Park(key, expected) => drivers.park(tx, scope, key, expected, now)
            case _: DriverRequest.Snapshot => throw new IllegalStateException("Driver snapshot is a read")
            case _: DriverRequest.Summaries => throw new IllegalStateException("Driver list is a read")
            case DriverRequest.Session(action) => drivers.session(tx, scope, action, now)
          }
        }
      }
    }

    override def claimPreview(scope: Scope, members: Set[ItemId]): F[Throwable, ClaimPreview] =
      repository.transact(scope.project)(tx => claimPlanner.preview(tx, scope, members, clock.millis()))

    override def takeover(scope: Scope, id: ClaimId, owner: Actor, members: Set[ItemId], durationMillis: Long, snapshot: ClaimSnapshot): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      if (scope.actor.role != Role.Human) throw DomainFailure(Fault.Denied("Claim takeover requires human authority"))
      if (!Set[Role](Role.Human, Role.Governor).contains(owner.role)) throw DomainFailure(Fault.Denied("Claim owner must be a human or governor"))
      invalid(owner.subject.trim.nonEmpty && owner.subject.length <= MaxTitle && !owner.subject.contains('\u0000') &&
        StandardCharsets.UTF_8.newEncoder().canEncode(owner.subject), "Invalid claim owner subject")
      duration(durationMillis)
      invalid(members.nonEmpty && members.size <= MaxBatch, "Invalid claim membership")
      val now = clock.millis()
      val origin = ClaimOrigin.Takeover(scope.actor, snapshot, durationMillis)
      tx.claimById(id) match {
        case Some(claim) =>
          if (claim.owner != owner || claim.members != members || claim.origin != origin)
            throw DomainFailure(Fault.Conflict("Claim identity reused with different takeover intent"))
          if (!ClaimPolicy.active(tx, claim, now)) throw DomainFailure(Fault.StaleFence("Acquire a new claim identity after expiry or release"))
          claim
        case None =>
          if (tx.cursor != snapshot.cursor) throw DomainFailure(Fault.Conflict("Claim preview items changed; review a fresh preview"))
          val preview = claimPlanner.preview(tx, scope, members, now)
          if (preview.snapshot != snapshot) throw DomainFailure(Fault.Conflict("Claim preview changed; review a fresh preview"))
          IntegrationPolicy.unreserved(tx, members ++ preview.claims.flatMap(_.members))
          preview.claims.foreach(c => tx.updateClaim(c.copy(released = true)))
          val claim = Claim(Fence(id, tx.nextFence()), owner, members, Math.addExact(now, durationMillis), false, origin)
          tx.insertClaim(claim)
          claim
      }
    }

    private def owned(tx: LedgerTransaction, scope: Scope, fence: Fence): Claim = {
      write(scope)
      val c = tx.claimById(fence.claim).getOrElse(throw DomainFailure(Fault.StaleFence("Unknown claim")))
      if (c.owner != scope.actor || c.fence != fence || c.expiresAt <= clock.millis()) throw DomainFailure(Fault.StaleFence("Claim no longer owned"))
      if (!c.released && !ClaimPolicy.active(tx, c, clock.millis())) throw DomainFailure(Fault.StaleFence("Claim membership replaced"))
      c
    }

    override def renew(scope: Scope, fence: Fence, durationMillis: Long): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      duration(durationMillis)
      val c = owned(tx, scope, fence)
      if (c.released) throw DomainFailure(Fault.StaleFence("Claim released"))
      // A renewal never shortens the lease: the host renews running work for minutes while a governor may hold a longer claim.
      val next = c.copy(expiresAt = Math.max(c.expiresAt, Math.addExact(clock.millis(), durationMillis)))
      tx.updateClaim(next)
      next
    }

    override def release(scope: Scope, fence: Fence): F[Throwable, Claim] = repository.transact(scope.project) { tx =>
      val current = owned(tx, scope, fence)
      IntegrationPolicy.unreserved(tx, current.members)
      val next = current.copy(released = true)
      if (!current.released) tx.updateClaim(next)
      next
    }
  }
}
