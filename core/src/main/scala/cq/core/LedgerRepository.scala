package cq.core

import cq.api.*

final case class DomainFailure(fault: Fault) extends RuntimeException(fault.toString)
final case class Scope(project: ProjectId, actor: Actor)
final case class CanonicalEdge(source: ItemId, relation: Relation, target: ItemId)
final case class StoredRequest(fingerprint: String, acknowledgement: ChangeAck)
final case class LedgerCursors(items: ChangeCursor, work: Long)

/** The kinds of per-project configuration document; a project holds at most one document of each kind. */
enum ProjectSettingKind { case Requirements }
object ProjectSettingKind {
  def of(value: ProjectSetting): ProjectSettingKind = value match {
    case _: ProjectSetting.Requirements => ProjectSettingKind.Requirements
  }
}
final case class StoredSetting(revision: Revision, value: ProjectSetting, actor: Actor, updatedAt: Long)

trait LedgerRepository[F[_, _]] {
  def initialize(project: Project): F[Throwable, Project]
  def projects(after: Option[ProjectId], limit: Int): F[Throwable, ProjectPage]
  def catalogueCursor: F[Throwable, CatalogueCursor]
  def cursors(project: ProjectId, now: Long): F[Throwable, LedgerCursors]
  def transact[A](project: ProjectId)(operation: LedgerTransaction => A): F[Throwable, A]
}

trait LedgerTransaction {
  def project: Project
  // Runs `effect` once this transaction has committed, and never when it fails or is rolled back. For state held outside the repository.
  def afterCommit(effect: () => Unit): Unit
  def renameProject(project: Project): Unit
  def cursor: ChangeCursor
  def allocate(ledger: Ledger): ItemId
  def get(id: ItemId): Option[Item]
  def summary(id: ItemId): Option[ItemSummary]
  def browseItem(id: ItemId, now: Long): Option[BrowseItem]
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
  def scan(query: QueryExpression, after: Option[ItemId], limit: Int, now: Long): ReadPage[ItemSummary]
  def browse(query: QueryExpression, order: ItemOrder, after: Option[BrowseItem], limit: Int, now: Long): ReadPage[BrowseItem]
  // The project's fence counter plus its claims that are released or expired at `now`: it changes when a claim is acquired, taken over,
  // released or expires, and not when one is renewed. It only grows while `now` does.
  def workCursor(now: Long): Long
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
  def setting(kind: ProjectSettingKind): Option[StoredSetting]
  // Replaces the project's document of the value's kind.
  def putSetting(value: StoredSetting): Unit
  // Open (unarchived, non-terminal) items without an open DerivedFrom producer or PartOf milestone, in (ledger, number) order.
  def candidateRoots(after: Option[ItemId], limit: Int): ReadPage[ItemSummary]
}
