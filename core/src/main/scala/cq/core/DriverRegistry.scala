package cq.core

import cq.api.*

object DriverRecords {
  // One driven advance cycle. `snapshot` is the advanceable set computed when the directive was issued; nothing recomputes it later.
  extension (cycle: CycleRecord) {
    def advanceable: Set[ItemId] = cycle.snapshot.advanceable.map(_.item.id).toSet
    def boundary: Set[ItemId] = cycle.advanceable ++ cycle.created
    def active: Boolean = cycle.state == CycleState.Active
    // Work dispatched from the run that has not settled; while any remains, the run is still active.
    def inFlight: List[LineageEntry] = cycle.lineage.filter(entry => !entry.settled && !entry.member.isInstanceOf[LineageMember.Run] && !cycle.resting(entry.member))
    // Unsettled work that waits for the session, such as a prepared integration that was not applied. `prompted` is the work the last resume directive was issued for.
    def held: Set[LineageMember] = cycle.lineage.filter(entry => !entry.settled && cycle.resting(entry.member)).map(_.member).toSet
    // The attempts in flight, and each request in flight that has none: a unit before its first attempt, between two of them, or
    // whose last attempt the host has reported while the end of the unit itself is still to come. A stop waits for each of these.
    def activeChildren: Int = {
      val attempts = cycle.inFlight.filter(_.member.isInstanceOf[LineageMember.Attempt])
      attempts.size + cycle.inFlight.count(entry => entry.member.isInstanceOf[LineageMember.Request] && !attempts.exists(_.parent.contains(entry.member)))
    }
    def delegated(session: SessionId): Boolean = cycle.lineage.exists(entry => !entry.settled && entry.member == LineageMember.Session(session))
    def tokens: Set[DriverToken] = cycle.resumed.keySet ++ cycle.resumeToken ++ cycle.startToken
  }
  // `attached` outlives a stop so the session can still read why its driver stopped; only an On driver is bound.
  // `carried` holds the integrations that earlier drives left unsettled, by the attached session whose host prepared them.
  extension (record: DriverRecord) {
    def on: Boolean = record.state == DriverState.On
    // The integrations the bound session may settle before its start directive is accepted: those earlier drives of that session left
    // unsettled. There are none unless the start directive is pending.
    def settleable(session: SessionId): Option[Set[IntegrationId]] =
      Option.when(record.cycle.exists(_.state == CycleState.Pending))(record.carried.getOrElse(session, Set.empty))
    def carries(session: SessionId, integration: IntegrationId): Boolean = record.settleable(session).exists(_.contains(integration))
  }
  def summary(record: DriverRecord): DriverSummary = DriverSummary(record.drive, record.key, record.state, record.attached, record.workset,
    record.targets, record.through, record.cycle.map(_.id), record.cycle.filter(_.active).fold(0)(_.activeChildren),
    record.stopped, record.touchedAt, record.stoppedAt, record.revision)
  def restored(record: DriverRecord, now: Long): DriverRecord = DriverPolicy.stopped(record,
    DriverStopped(DriverStop.RestoredArchive, "Restored from a project archive; control tokens invalidated"), false, now)
    .copy(cycle = record.cycle.map(cycle => DriverPolicy.ended(cycle).copy(startToken = None, resumeToken = None, resumed = Map.empty)),
      carried = DriverPolicy.outstanding(record), revision = Revision(Math.addExact(record.revision.value, 1L)))
}

final case class DriverStopIntent(project: ProjectId, key: DriverKey, detail: String, now: Long) extends RuntimeException(detail)

final class DriverRegistry {
  import DriverRecords.*
  def get(project: ProjectId, key: DriverKey)(using tx: LedgerTransaction): Option[DriverRecord] = {
    require(project == tx.project.id, "Driver transaction project invariant violated")
    tx.driver(key)
  }
  def all(project: ProjectId)(using tx: LedgerTransaction): List[DriverRecord] = {
    require(project == tx.project.id, "Driver transaction project invariant violated")
    tx.drivers
  }
  def put(record: DriverRecord)(using tx: LedgerTransaction): Unit = {
    tx.putDriver(record.copy(revision = tx.nextDriverRevision()))
  }
  def update(project: ProjectId, key: DriverKey)(change: DriverRecord => DriverRecord)(using tx: LedgerTransaction): Unit =
    get(project, key).foreach(record => put(change(record)))
  // The record is deleted; its drive ends with a Removed period at `now`, whatever its state, so that no drive without a live record is left open.
  def remove(record: DriverRecord, now: Long)(using tx: LedgerTransaction): Unit = {
    period(record, DrivePeriodState.Removed, None, now)
    tx.removeDriver(record.key)
  }
  // The period of the state a record entered at `at`.
  def period(record: DriverRecord, state: DrivePeriodState, reason: Option[DriverStop], at: Long)(using tx: LedgerTransaction): Unit =
    tx.appendDrivePeriod(record.drive, record.key, record.attached, state, reason, at)
  // The record is put as entered: Binding or On for a start, On for a bind.
  def enter(record: DriverRecord, now: Long)(using tx: LedgerTransaction): Unit = {
    put(record)
    period(record, if (record.state == DriverState.Binding) DrivePeriodState.Binding else DrivePeriodState.On, None, now)
  }
  // Every stop: the record turns off with `value` and its drive gets an Off period.
  def stop(record: DriverRecord, value: DriverStopped, announced: Boolean, now: Long)(using tx: LedgerTransaction): DriverRecord = {
    val next = DriverPolicy.stopped(record, value, announced, now)
    put(next)
    period(next, DrivePeriodState.Off, Some(value.reason), now)
    next
  }
  def bound(project: ProjectId, session: SessionId)(using tx: LedgerTransaction): Option[DriverRecord] = all(project).find(record => record.on && record.attached.contains(session))

  // Stops the driver with reason failure and rejects the operation that caused it.
  def fail(record: DriverRecord, detail: String, now: Long): Nothing = {
    throw DriverStopIntent(record.project, record.key, detail, now)
  }

  // The active cycle a write or lineage operation names, for the bound session or a session delegated under that cycle.
  def lineage(project: ProjectId, id: CycleId, session: SessionId, now: Long)(using tx: LedgerTransaction): (DriverRecord, CycleRecord) = {
    val record = all(project).find(_.cycle.exists(_.id == id))
      .getOrElse(throw DomainFailure(Fault.Denied("Unknown CQ driver cycle; a write with unknown lineage is rejected")))
    val cycle = record.cycle.get
    def reject(detail: String): Nothing = if (record.on) fail(record, detail, now) else throw DomainFailure(Fault.Denied(detail))
    if (!record.on || !cycle.active) reject(s"cycle ${cycle.number} has ended; a write with ended lineage is rejected")
    if (!record.attached.contains(session) && !cycle.delegated(session)) reject(s"a session outside the lineage of cycle ${cycle.number} presented its cycle ID")
    (record, cycle)
  }
}

object DriverRejection {
  def persist(tx: LedgerTransaction, intent: DriverStopIntent): DomainFailure = {
    require(tx.project.id == intent.project, "Driver stop project invariant violated")
    val original = tx.driver(intent.key).getOrElse(throw new IllegalStateException("Rejected driver was not committed before its operation"))
    require(original.state == DriverState.On, "Rejected driver was not on before its operation")
    new DriverRegistry().stop(original, DriverStopped(DriverStop.Failure, intent.detail), false, intent.now)(using tx)
    DomainFailure(Fault.Denied(s"CQ driver stopped with reason failure: ${intent.detail}"))
  }
}
