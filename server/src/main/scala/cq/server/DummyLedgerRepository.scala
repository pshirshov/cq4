package cq.server

import cq.api.*
import cq.core.*
import distage.Lifecycle
import zio.{IO, Ref, Task, ZIO}

private final case class DummyCatalogue(cursor: CatalogueCursor, projects: Map[ProjectId, DummyLedgerState])

final class DummyLedgerResource extends Lifecycle.LiftF[Task, LedgerRepository[IO]](
  Ref.Synchronized.make(DummyCatalogue(CatalogueCursor(0L), Map.empty)).map { states =>
    new LedgerRepository[IO] {
      override def projects(after: Option[ProjectId], limit: Int): IO[Throwable, ProjectPage] = states.get.map { current =>
        val found = current.projects.valuesIterator.map(_.project).filter(p => after.forall(a => p.id.value.toString > a.value.toString))
          .toList.sortBy(_.id.value.toString).take(limit + 1)
        val selected = found.take(limit)
        ProjectPage(selected, selected.lastOption.map(_.id), found.size > limit, current.cursor)
      }
      override def catalogueCursor: IO[Throwable, CatalogueCursor] = states.get.map(_.cursor)
      override def itemCursor(project: ProjectId): IO[Throwable, ChangeCursor] = states.get.flatMap { current =>
        ZIO.fromOption(current.projects.get(project)).orElseFail(DomainFailure(Fault.Missing("Project not initialized"))).map(state => ChangeCursor(state.cursor))
      }
      override def initialize(project: Project): IO[Throwable, Project] = states.modify { current =>
        current.projects.get(project.id) match {
          case Some(existing) => (existing.project, current)
          case None =>
            val state = DummyLedgerState(project, 0L, 0L, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, List.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)
            (project, current.copy(cursor = CatalogueCursor(Math.addExact(current.cursor.value, 1L)), projects = current.projects.updated(project.id, state)))
        }
      }
      override def transact[A](project: ProjectId)(operation: LedgerTransaction => A): IO[Throwable, A] = states.modifyZIO { current =>
        ZIO.attempt {
          val state = current.projects.getOrElse(project, throw DomainFailure(Fault.Missing("Project not initialized")))
          val tx = new DummyLedgerTransaction(state)
          val result = operation(tx)
          val cursor = if (tx.result.project == state.project) current.cursor else CatalogueCursor(Math.addExact(current.cursor.value, 1L))
          ((result, tx.committed), current.copy(cursor = cursor, projects = current.projects.updated(project, tx.result)))
        }
      }.map { (result, committed) => committed.foreach(_()); result }
    }
  }
)

private final case class DummyLedgerState(
  project: Project,
  cursor: Long,
  fence: Long,
  counters: Map[Ledger, Long],
  items: Map[ItemId, Item],
  edges: Set[CanonicalEdge],
  history: Map[(ItemId, Revision), HistoryEntry],
  requests: Map[(Actor, RequestId), StoredRequest],
  events: List[ChangeEvent],
  claims: Map[ClaimId, Claim],
  members: Map[ItemId, ClaimId],
  admissions: Map[AttemptId, ResultAdmission],
  integrations: Map[IntegrationId, IntegrationRecord],
  reserved: Map[ItemId, IntegrationId],
  worksets: Map[WorksetId, StoredWorkset],
)

private final class DummyLedgerTransaction(initial: DummyLedgerState) extends LedgerTransaction {
  private var state = initial
  private var effects = List.empty[() => Unit]
  def result: DummyLedgerState = state
  def committed: List[() => Unit] = effects.reverse
  override def afterCommit(effect: () => Unit): Unit = effects = effect :: effects
  override def project: Project = state.project
  override def renameProject(project: Project): Unit = {
    require(project.id == state.project.id, "Project identity cannot change")
    state = state.copy(project = project)
  }
  override def cursor: ChangeCursor = ChangeCursor(state.cursor)
  override def allocate(ledger: Ledger): ItemId = {
    val next = Math.addExact(state.counters.getOrElse(ledger, 0L), 1L)
    state = state.copy(counters = state.counters.updated(ledger, next))
    ItemId(project.id, ledger, next)
  }
  override def get(id: ItemId): Option[Item] = state.items.get(id)
  override def summary(id: ItemId): Option[ItemSummary] = state.items.get(id).map(LedgerPolicy.summary)
  private def browseRow(item: Item): BrowseItem =
    ItemBrowse.project(item, state.edges.collectFirst { case edge if edge.source == item.id && edge.relation == Relation.PartOf => edge.target })
  override def browseItem(id: ItemId): Option[BrowseItem] = state.items.get(id).map(browseRow)
  override def put(item: Item): Unit = { state = state.copy(items = state.items.updated(item.id, item)) }
  override def refs(id: ItemId): List[ItemRef] = state.edges.toList.flatMap { edge =>
    if (edge.source == id) List(ItemRef(edge.relation, edge.target))
    else if (edge.target == id) List(ItemRef(LedgerPolicy.inverse(edge.relation), edge.source))
    else Nil
  }.sortBy(r => (r.relation.toString, r.target.ledger.toString, r.target.number))
  override def edge(edge: CanonicalEdge, present: Boolean): Boolean = {
    val changed = state.edges.contains(edge) != present
    state = state.copy(edges = if (present) state.edges + edge else state.edges - edge)
    changed
  }
  override def historical(id: ItemId, revision: Revision): Option[HistoryEntry] = state.history.get((id, revision))
  override def history(id: ItemId, before: Revision, limit: Int): ReadPage[HistoryEntry] =
    ReadPage.select(state.history.iterator.collect { case ((`id`, revision), value) if revision.value < before.value => value }.toList.sortBy(e => -e.item.item.revision.value).iterator, limit, HistoryEntry_JsonCodec)
  override def append(entry: HistoryEntry): Unit = {
    val key = (entry.item.item.id, entry.item.item.revision)
    require(!state.history.contains(key), "Duplicate history revision")
    state = state.copy(history = state.history.updated(key, entry))
  }
  override def request(actor: Actor, id: RequestId): Option[StoredRequest] = state.requests.get((actor, id))
  override def acknowledge(actor: Actor, value: StoredRequest): Unit = {
    val key = (actor, value.acknowledgement.request)
    require(!state.requests.contains(key), "Duplicate request acknowledgement")
    state = state.copy(requests = state.requests.updated(key, value))
  }
  override def publish(request: RequestId, items: List[ItemRevision]): ChangeCursor = {
    val next = ChangeCursor(Math.addExact(state.cursor, 1L))
    state = state.copy(cursor = next.value, events = state.events :+ ChangeEvent(next, request, items))
    next
  }
  override def changes(after: ChangeCursor, limit: Int): ReadPage[ChangeEvent] = ReadPage.select(state.events.iterator.filter(_.cursor.value > after.value), limit, ChangeEvent_JsonCodec)
  private def matches(query: QueryExpression, item: Item): Boolean = query match {
    case QueryExpression.All() => true
    case QueryExpression.Text(words, phrase) => SearchText.contains(SearchText.document(item.draft.title, item.draft.body), words, phrase)
    case QueryExpression.Id(id) => item.id.ledger == id.ledger && item.id.number == id.number
    case QueryExpression.LedgerIs(ledger) => item.id.ledger == ledger
    case QueryExpression.Status(value) => LedgerPolicy.status(item.draft.content).equalsIgnoreCase(value)
    case QueryExpression.Tag(value) => item.draft.labels.contains(value)
    case QueryExpression.Project(id) => item.id.project == id
    case QueryExpression.Archive(ArchiveFilter.Active) => !item.draft.archived
    case QueryExpression.Archive(ArchiveFilter.Archived) => item.draft.archived
    case QueryExpression.Archive(ArchiveFilter.All) => true
    case QueryExpression.Reference(relation, target) => refs(item.id).contains(ItemRef(relation, ItemId(project.id, target.ledger, target.number)))
    case QueryExpression.Not(expression) => !matches(expression, item)
    case QueryExpression.And(left, right) => matches(left, item) && matches(right, item)
    case QueryExpression.Or(left, right) => matches(left, item) || matches(right, item)
  }
  override def scan(query: QueryExpression, after: Option[ItemId], limit: Int): ReadPage[ItemSummary] = {
    val candidates = state.items.valuesIterator.filter { item =>
      matches(query, item) && after.forall(id => Ordering[(String, Long)].gt(LedgerPolicy.key(item.id), LedgerPolicy.key(id)))
    }.toList.sortBy(i => LedgerPolicy.key(i.id)).iterator.map(LedgerPolicy.summary)
    ReadPage.select(candidates, limit, ItemSummary_JsonCodec)
  }
  override def browse(query: QueryExpression, order: ItemOrder, after: Option[BrowseItem], limit: Int): ReadPage[BrowseItem] = {
    val ordering = ItemBrowse.ordering(order)
    val candidates = state.items.valuesIterator.filter(matches(query, _)).map(browseRow)
      .filter(item => after.forall(ordering.lt(_, item))).toList.sorted(ordering)
    ReadPage.select(candidates.iterator, limit, BrowseItem_JsonCodec)
  }
  override def counts: List[LedgerCount] = {
    val counts = state.items.valuesIterator.filterNot(_.draft.archived).toList.groupMapReduce(_.id.ledger)(_ => 1L)(_ + _)
    Ledger.all.map(ledger => LedgerCount(ledger, counts.getOrElse(ledger, 0L)))
  }
  override def completeItems(prefix: SearchPrefix, archive: ArchiveFilter, limit: Int): List[ItemSummary] = state.items.valuesIterator
    .filter(matches(QueryExpression.Archive(archive), _))
    .filter(item => prefix.matches(LedgerPolicy.prefix(item.id.ledger) + item.id.number))
    .toList.sortBy(item => LedgerPolicy.prefix(item.id.ledger) + item.id.number).take(limit).map(LedgerPolicy.summary)
  override def completeLabels(prefix: SearchPrefix, limit: Int): List[String] = state.items.valuesIterator
    .flatMap(_.draft.labels).filter(prefix.matches).toSet.toList.sorted(SearchPrefix.ordering).take(limit)
  override def claim(id: ItemId): Option[Claim] = state.members.get(id).flatMap(state.claims.get)
  override def claimById(id: ClaimId): Option[Claim] = state.claims.get(id)
  override def claimMembers(id: ClaimId): Set[ItemId] =
    state.claims.get(id).toList.flatMap(_.members).filter(item => state.members.get(item).contains(id)).toSet
  override def insertClaim(claim: Claim): Unit = {
    require(!state.claims.contains(claim.fence.claim), "Claim identity already exists")
    state = state.copy(claims = state.claims.updated(claim.fence.claim, claim), members = state.members ++ claim.members.map(_ -> claim.fence.claim))
  }
  override def updateClaim(claim: Claim): Unit = {
    require(state.claims.get(claim.fence.claim).exists(_.fence == claim.fence), "Claim fence does not exist")
    state = state.copy(claims = state.claims.updated(claim.fence.claim, claim))
  }
  override def admission(attempt: AttemptId): Option[ResultAdmission] = state.admissions.get(attempt)
  override def insertAdmission(value: ResultAdmission): Unit = {
    require(!state.admissions.contains(value.artifact.attempt), "Result admission already exists")
    state = state.copy(admissions = state.admissions.updated(value.artifact.attempt, value))
  }
  override def nextFence(): Long = {
    val next = Math.addExact(state.fence, 1L)
    state = state.copy(fence = next)
    next
  }
  override def integration(id: IntegrationId): Option[IntegrationRecord] = state.integrations.get(id)
  override def pendingIntegration(item: ItemId): Option[IntegrationHold] =
    state.reserved.get(item).map(id => IntegrationPolicy.hold(state.integrations(id).intent))
  override def insertIntegration(value: IntegrationRecord): Unit = {
    require(!state.integrations.contains(value.intent.id) && value.resolution == IntegrationResolution.Pending(), "Integration is already registered or resolved")
    require(value.intent.members.forall(ref => !state.reserved.contains(ref.id)), "Integration overlaps reserved work")
    state = state.copy(integrations = state.integrations.updated(value.intent.id, value),
      reserved = state.reserved ++ value.intent.members.map(ref => ref.id -> value.intent.id))
  }
  override def resolveIntegration(value: IntegrationRecord): Unit = {
    require(state.integrations.get(value.intent.id).exists(old => old.intent == value.intent && old.resolution == IntegrationResolution.Pending()) &&
      value.resolution != IntegrationResolution.Pending(), "Integration resolution requires the exact pending intent")
    require(value.intent.members.forall(ref => state.reserved.get(ref.id).contains(value.intent.id)), "Integration membership is inconsistent")
    state = state.copy(integrations = state.integrations.updated(value.intent.id, value), reserved = state.reserved -- value.intent.members.map(_.id))
  }
  override def workset(id: WorksetId): Option[StoredWorkset] = state.worksets.get(id)
  override def insertWorkset(value: StoredWorkset): Unit = {
    require(!state.worksets.contains(value.id), "Workset identity already exists")
    state = state.copy(worksets = state.worksets.updated(value.id, value))
  }
  override def candidateRoots(after: Option[ItemId], limit: Int): ReadPage[ItemSummary] = {
    val candidates = state.items.valuesIterator.map(LedgerPolicy.summary).filter { item =>
      LedgerPolicy.open(item) && after.forall(id => Ordering[(String, Long)].gt(LedgerPolicy.key(item.id), LedgerPolicy.key(id))) &&
        !refs(item.id).exists(ref => Set[Relation](Relation.DerivedFrom, Relation.PartOf).contains(ref.relation) && summary(ref.target).exists(LedgerPolicy.open))
    }.toList.sortBy(item => LedgerPolicy.key(item.id))
    ReadPage.select(candidates.iterator, limit, ItemSummary_JsonCodec)
  }
}
