package cq.server

import cq.api.*
import cq.core.*
import distage.Lifecycle
import zio.{IO, Ref, Task, ZIO}

final class DummyLedgerResource extends Lifecycle.LiftF[Task, LedgerRepository[IO]](
  Ref.Synchronized.make(Map.empty[ProjectId, DummyLedgerState]).map { states =>
    new LedgerRepository[IO] {
      override def projects(after: Option[ProjectId], limit: Int): IO[Throwable, List[Project]] = states.get.map { current =>
        current.valuesIterator.map(_.project).filter(p => after.forall(a => p.id.value.toString > a.value.toString))
          .toList.sortBy(_.id.value.toString).take(limit)
      }
      override def initialize(project: Project): IO[Throwable, Project] = states.modify { current =>
        current.get(project.id) match {
          case Some(existing) => (existing.project, current)
          case None =>
            val state = DummyLedgerState(project, 0L, 0L, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, List.empty, Map.empty, Map.empty)
            (project, current.updated(project.id, state))
        }
      }
      override def transact[A](project: ProjectId)(operation: LedgerTransaction => A): IO[Throwable, A] = states.modifyZIO { current =>
        ZIO.attempt {
          val state = current.getOrElse(project, throw DomainFailure(Fault.Missing("Project not initialized")))
          val tx = new DummyLedgerTransaction(state)
          val result = operation(tx)
          (result, current.updated(project, tx.result))
        }
      }
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
)

private final class DummyLedgerTransaction(initial: DummyLedgerState) extends LedgerTransaction {
  private var state = initial
  def result: DummyLedgerState = state
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
  override def completeItems(prefix: SearchPrefix, limit: Int): List[ItemSummary] = state.items.valuesIterator
    .filter(item => prefix.matches(LedgerPolicy.prefix(item.id.ledger) + item.id.number))
    .toList.sortBy(item => LedgerPolicy.prefix(item.id.ledger) + item.id.number).take(limit).map(LedgerPolicy.summary)
  override def completeLabels(prefix: SearchPrefix, limit: Int): List[String] = state.items.valuesIterator
    .flatMap(_.draft.labels).filter(prefix.matches).toSet.toList.sorted(SearchPrefix.ordering).take(limit)
  override def claim(id: ItemId): Option[Claim] = state.members.get(id).flatMap(state.claims.get)
  override def claimById(id: ClaimId): Option[Claim] = state.claims.get(id)
  override def saveClaim(claim: Claim): Unit = {
    state = state.copy(claims = state.claims.updated(claim.fence.claim, claim), members = state.members ++ claim.members.map(_ -> claim.fence.claim))
  }
  override def nextFence(): Long = {
    val next = Math.addExact(state.fence, 1L)
    state = state.copy(fence = next)
    next
  }
}
