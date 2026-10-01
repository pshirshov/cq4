package cq.core

import cq.api.*

final case class DomainFailure(fault: Fault) extends RuntimeException(fault.toString)
final case class Scope(project: ProjectId, actor: Actor)
final case class CanonicalEdge(source: ItemId, relation: Relation, target: ItemId)
final case class StoredRequest(fingerprint: String, acknowledgement: ChangeAck)

trait LedgerRepository[F[_, _]] {
  def initialize(project: Project): F[Throwable, Project]
  def projects(after: Option[ProjectId], limit: Int): F[Throwable, ProjectPage]
  def catalogueCursor: F[Throwable, CatalogueCursor]
  def itemCursor(project: ProjectId): F[Throwable, ChangeCursor]
  def transact[A](project: ProjectId)(operation: LedgerTransaction => A): F[Throwable, A]
}

trait LedgerTransaction {
  def project: Project
  def renameProject(project: Project): Unit
  def cursor: ChangeCursor
  def allocate(ledger: Ledger): ItemId
  def get(id: ItemId): Option[Item]
  def summary(id: ItemId): Option[ItemSummary]
  def browseItem(id: ItemId): Option[BrowseItem]
  def put(item: Item): Unit
  def refs(id: ItemId): List[ItemRef]
  def edge(edge: CanonicalEdge, present: Boolean): Boolean
  def historical(id: ItemId, revision: Revision): Option[HistoryEntry]
  def history(id: ItemId, before: Revision, limit: Int): ReadPage[HistoryEntry]
  def append(entry: HistoryEntry): Unit
  def request(actor: Actor, id: RequestId): Option[StoredRequest]
  def acknowledge(actor: Actor, value: StoredRequest): Unit
  def publish(request: RequestId, items: List[ItemRevision]): ChangeCursor
  def changes(after: ChangeCursor, limit: Int): ReadPage[ChangeEvent]
  def scan(query: QueryExpression, after: Option[ItemId], limit: Int): ReadPage[ItemSummary]
  def browse(query: QueryExpression, order: ItemOrder, after: Option[BrowseItem], limit: Int): ReadPage[BrowseItem]
  def counts: List[LedgerCount]
  def completeItems(prefix: SearchPrefix, archive: ArchiveFilter, limit: Int): List[ItemSummary]
  def completeLabels(prefix: SearchPrefix, limit: Int): List[String]
  def claim(id: ItemId): Option[Claim]
  def claimById(id: ClaimId): Option[Claim]
  def claimMembers(id: ClaimId): Set[ItemId]
  def insertClaim(claim: Claim): Unit
  def updateClaim(claim: Claim): Unit
  def admission(attempt: AttemptId): Option[ResultAdmission]
  def insertAdmission(value: ResultAdmission): Unit
  def integration(id: IntegrationId): Option[IntegrationRecord]
  def pendingIntegration(item: ItemId): Option[IntegrationHold]
  def insertIntegration(value: IntegrationRecord): Unit
  def resolveIntegration(value: IntegrationRecord): Unit
  def nextFence(): Long
  def workset(id: WorksetId): Option[StoredWorkset]
  def insertWorkset(value: StoredWorkset): Unit
  // Open (unarchived, non-terminal) items without an open DerivedFrom producer or PartOf milestone, in (ledger, number) order.
  def candidateRoots(after: Option[ItemId], limit: Int): ReadPage[ItemSummary]
}
