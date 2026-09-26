package cq.core

import cq.api.*

final case class DomainFailure(fault: Fault) extends RuntimeException(fault.toString)
final case class Scope(project: ProjectId, actor: Actor)
final case class CanonicalEdge(source: ItemId, relation: Relation, target: ItemId)
final case class StoredRequest(fingerprint: String, acknowledgement: ChangeAck)

trait LedgerRepository[F[_, _]] {
  def initialize(project: Project): F[Throwable, Project]
  def transact[A](project: ProjectId)(operation: LedgerTransaction => A): F[Throwable, A]
}

trait LedgerTransaction {
  def project: Project
  def cursor: ChangeCursor
  def allocate(ledger: Ledger): ItemId
  def get(id: ItemId): Option[Item]
  def put(item: Item): Unit
  def refs(id: ItemId): List[ItemRef]
  def edge(edge: CanonicalEdge, present: Boolean): Boolean
  def historical(id: ItemId, revision: Revision): Option[HistoryEntry]
  def history(id: ItemId, before: Revision, limit: Int): List[HistoryEntry]
  def append(entry: HistoryEntry): Unit
  def request(actor: Actor, id: RequestId): Option[StoredRequest]
  def acknowledge(actor: Actor, value: StoredRequest): Unit
  def publish(request: RequestId, items: List[ItemRevision]): ChangeCursor
  def changes(after: ChangeCursor, limit: Int): List[ChangeEvent]
  def scan(filter: ItemFilter, after: Option[ItemId], limit: Int): List[Item]
  def claim(id: ItemId): Option[Claim]
  def claimById(id: ClaimId): Option[Claim]
  def saveClaim(claim: Claim): Unit
  def nextFence(): Long
}
