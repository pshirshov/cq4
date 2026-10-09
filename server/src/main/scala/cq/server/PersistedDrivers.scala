package cq.server

import cq.api.*
import cq.core.{DriverPolicy, DriverRecords}
import java.sql.Types

private[server] object PersistedDrivers {
  def validate(record: DriverRecord, project: ProjectId): Unit = {
    DriverPolicy.key(record.key)
    require(record.project == project && record.targets.nonEmpty && record.targets.forall(_.project == project), "Driver project identity disagrees with its content")
    require(record.revision.value > 0 && record.touchedAt >= 0, "Invalid persisted driver revision/time")
    require((record.state == DriverState.Off) == record.stoppedAt.nonEmpty, "Driver stop time disagrees with its state")
    record.state match {
      case DriverState.Binding => require(record.bind.nonEmpty && record.attached.isEmpty && record.cycle.isEmpty && record.stopped.isEmpty, "Invalid binding driver")
      case DriverState.On => require(record.attached.nonEmpty && record.bind.isEmpty && record.stopped.isEmpty, "Invalid on driver")
      case DriverState.Off => require(record.bind.isEmpty && record.stopped.nonEmpty && record.cycle.forall(_.state == CycleState.Ended), "Invalid stopped driver")
    }
    record.cycle.foreach { cycle =>
      require(cycle.roots == record.targets && cycle.through == record.through && cycle.snapshot.targets == record.targets, "Driver cycle targets disagree with its record")
      require((cycle.created ++ cycle.snapshot.advanceable.map(_.item.id) ++ cycle.snapshot.context.map(_.item.id)).forall(_.project == project), "Driver cycle includes another project's data")
      require(cycle.state == CycleState.Ended || cycle.startToken.nonEmpty, "Live cycle requires a start token")
    }
  }

  def records(sql: Jdbc, project: ProjectId): List[DriverRecord] = sql.query(
    "SELECT d.harness, d.session_key, d.revision, d.state, d.attached, d.cycle_id, d.touched_at, d.stopped_at, d.summary::text, d.body::text, p.driver_clock FROM cq_drivers d JOIN cq_projects p USING(project_id) WHERE project_id = ?"
  )(_.setObject(1, project.value)) { row =>
    val record = Wire.decode(DriverRecord_JsonCodec, row.getString(10))
    validate(record, project)
    require(record.revision.value <= row.getLong(11), "Driver revision exceeds the project clock")
    require(record.key.harness.toString == row.getString(1) && record.key.session == row.getString(2) && record.revision.value == row.getLong(3) &&
      record.state.toString == row.getString(4) && record.attached.map(_.value) == Option(row.getObject(5, classOf[java.util.UUID])) &&
      record.cycle.map(_.id.value) == Option(row.getObject(6, classOf[java.util.UUID])) && record.touchedAt == row.getLong(7) &&
      record.stoppedAt == Option(row.getObject(8, classOf[java.lang.Long])).map(_.longValue) &&
      DriverRecords.summary(record) == Wire.decode(DriverSummary_JsonCodec, row.getString(9)), "Driver summary columns disagree with its content")
    record
  }

  private val PeriodColumns = "sequence, drive, harness, session_key, attached, state, reason, at"
  private def period(row: java.sql.ResultSet): DrivePeriod = {
    val key = DriverKey(Harness.parse(row.getString(3)).getOrElse(throw new IllegalStateException("Invalid persisted drive period harness")), row.getString(4))
    DrivePeriod(row.getLong(1), DriveId(row.getObject(2, classOf[java.util.UUID])), key, Option(row.getObject(5, classOf[java.util.UUID])).map(SessionId(_)),
      DrivePeriodState.parse(row.getString(6)).getOrElse(throw new IllegalStateException("Invalid persisted drive period state")),
      Option(row.getString(7)).map(reason => DriverStop.parse(reason).getOrElse(throw new IllegalStateException("Invalid persisted drive period reason"))), row.getLong(8))
  }

  // The period takes the next sequence of the project; the caller holds the project's transaction, which serialises the appends.
  def append(sql: Jdbc, project: ProjectId, drive: DriveId, key: DriverKey, attached: Option[SessionId], state: DrivePeriodState, reason: Option[DriverStop], at: Long): Unit = {
    require((state == DrivePeriodState.Off) == reason.nonEmpty, "Only an Off drive period carries a stop reason")
    sql.execute(s"INSERT INTO cq_drive_periods(project_id, $PeriodColumns) SELECT ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?, ?, ?, ?, ? FROM cq_drive_periods WHERE project_id = ?") { statement =>
      statement.setObject(1, project.value); statement.setObject(2, drive.value); statement.setString(3, key.harness.toString); statement.setString(4, key.session)
      statement.setObject(5, attached.map(_.value).orNull); statement.setString(6, state.toString); statement.setString(7, reason.map(_.toString).orNull)
      statement.setLong(8, at); statement.setObject(9, project.value)
    }
    ()
  }

  def periods(sql: Jdbc, project: ProjectId, drive: DriveId, after: Option[Long], limit: Int): List[DrivePeriod] = sql.query(
    s"SELECT $PeriodColumns FROM cq_drive_periods WHERE project_id = ? AND drive = ? AND sequence > ? ORDER BY sequence LIMIT ?") { statement =>
    statement.setObject(1, project.value); statement.setObject(2, drive.value); statement.setLong(3, after.getOrElse(0L)); statement.setInt(4, limit)
  }(period)

  def drives(sql: Jdbc, project: ProjectId, before: Option[Long], limit: Int): List[DriveEntry] = sql.query(
    s"SELECT $PeriodColumns FROM cq_drive_periods f WHERE f.project_id = ? AND f.sequence < ? AND f.sequence = " +
      "(SELECT MIN(m.sequence) FROM cq_drive_periods m WHERE m.project_id = f.project_id AND m.drive = f.drive) ORDER BY f.sequence DESC LIMIT ?") { statement =>
    statement.setObject(1, project.value); statement.setLong(2, before.getOrElse(Long.MaxValue)); statement.setInt(3, limit)
  }(period).map { first =>
    val latest = sql.query(s"SELECT $PeriodColumns FROM cq_drive_periods WHERE project_id = ? AND drive = ? ORDER BY sequence DESC LIMIT 1")(
      statement => { statement.setObject(1, project.value); statement.setObject(2, first.drive.value) })(period).head
    DriveEntry(first.drive, first.key, first, latest)
  }

  def put(sql: Jdbc, project: ProjectId, record: DriverRecord): Unit = {
    validate(record, project)
    require(record.revision.value <= sql.query("SELECT driver_clock FROM cq_projects WHERE project_id = ?")(_.setObject(1, project.value))(_.getLong(1)).head, "Driver revision exceeds the project clock")
    sql.execute("INSERT INTO cq_drivers(project_id,harness,session_key,revision,state,attached,cycle_id,touched_at,stopped_at,summary,body) " +
      "VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(project_id,harness,session_key) DO UPDATE SET " +
      "revision=EXCLUDED.revision,state=EXCLUDED.state,attached=EXCLUDED.attached,cycle_id=EXCLUDED.cycle_id,touched_at=EXCLUDED.touched_at,stopped_at=EXCLUDED.stopped_at,summary=EXCLUDED.summary,body=EXCLUDED.body") { statement =>
      statement.setObject(1, project.value); statement.setString(2, record.key.harness.toString); statement.setString(3, record.key.session)
      statement.setLong(4, record.revision.value); statement.setString(5, record.state.toString); statement.setObject(6, record.attached.map(_.value).orNull)
      statement.setObject(7, record.cycle.map(_.id.value).orNull); statement.setLong(8, record.touchedAt)
      record.stoppedAt.fold(statement.setNull(9, Types.BIGINT))(statement.setLong(9, _))
      statement.setString(10, Wire.encode(DriverSummary_JsonCodec, DriverRecords.summary(record)))
      statement.setString(11, Wire.encode(DriverRecord_JsonCodec, record))
    }
    ()
  }
}
