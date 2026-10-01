package cq.core

import cq.api.*

final case class BindOffer(token: DriverToken, expiresAt: Long)

// One driven advance cycle. `snapshot` is the advanceable set computed when the directive was issued; nothing recomputes it later.
final case class CycleRecord(
  id: CycleId, number: Int, roots: Set[ItemId], through: WorkflowPhase, snapshot: WorksetPreview, state: CycleState,
  startToken: DriverToken, resumeToken: Option[DriverToken], resumed: Map[DriverToken, RequestId], run: Option[RequestId],
  created: List[ItemId], lineage: List[LineageEntry],
) {
  val advanceable: Set[ItemId] = snapshot.advanceable.map(_.item.id).toSet
  def boundary: Set[ItemId] = advanceable ++ created
  def active: Boolean = state == CycleState.Active
  // Work dispatched from the run that has not settled; while any remains, the run is still active.
  def inFlight: List[LineageEntry] = lineage.filter(entry => !entry.settled && !entry.member.isInstanceOf[LineageMember.Run])
  def activeChildren: Int = inFlight.count(_.member.isInstanceOf[LineageMember.Attempt])
  def delegated(session: SessionId): Boolean = lineage.exists(entry => !entry.settled && entry.member == LineageMember.Session(session))
  def tokens: Set[DriverToken] = resumed.keySet ++ resumeToken + startToken
}

// Driver state of one harness session. It lives in the server process: a server restart turns every driver off.
// `attached` outlives a stop so the session can still read why its driver stopped; only an On driver is bound.
final case class DriverRecord(
  project: ProjectId, key: DriverKey, state: DriverState, attached: Option[SessionId], workset: Option[WorksetId],
  targets: Set[ItemId], through: WorkflowPhase, bind: Option[BindOffer], cycle: Option[CycleRecord],
  directives: Int, stopped: Option[DriverStopped], announced: Boolean, touchedAt: Long,
) {
  def on: Boolean = state == DriverState.On
}

final class DriverRegistry {
  private var drivers = Map.empty[(ProjectId, DriverKey), DriverRecord]

  def get(project: ProjectId, key: DriverKey): Option[DriverRecord] = synchronized(drivers.get((project, key)))
  def all(project: ProjectId): List[DriverRecord] = synchronized(drivers.valuesIterator.filter(_.project == project).toList)
  def put(record: DriverRecord): Unit = synchronized { drivers = drivers.updated((record.project, record.key), record) }
  def remove(record: DriverRecord): Unit = synchronized { drivers = drivers - ((record.project, record.key)) }

  def bound(project: ProjectId, session: SessionId): Option[DriverRecord] = all(project).find(record => record.on && record.attached.contains(session))

  // Stops the driver with reason failure and rejects the operation that caused it.
  def fail(record: DriverRecord, detail: String, now: Long): Nothing = {
    put(DriverPolicy.stopped(record, DriverStopped(DriverStop.Failure, detail), false, now))
    throw DomainFailure(Fault.Denied(s"CQ driver stopped with reason failure: $detail"))
  }

  // The active cycle a write or lineage operation names, for the bound session or a session delegated under that cycle.
  def lineage(project: ProjectId, id: CycleId, session: SessionId, now: Long): (DriverRecord, CycleRecord) = {
    val record = all(project).find(_.cycle.exists(_.id == id))
      .getOrElse(throw DomainFailure(Fault.Denied("Unknown CQ driver cycle; a write with unknown lineage is rejected")))
    val cycle = record.cycle.get
    def reject(detail: String): Nothing = if (record.on) fail(record, detail, now) else throw DomainFailure(Fault.Denied(detail))
    if (!record.on || !cycle.active) reject(s"cycle ${cycle.number} has ended; a write with ended lineage is rejected")
    if (!record.attached.contains(session) && !cycle.delegated(session)) reject(s"a session outside the lineage of cycle ${cycle.number} presented its cycle ID")
    (record, cycle)
  }
}
