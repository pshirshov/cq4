package cq.server

import cq.api.*
import cq.core.*
import distage.Lifecycle
import java.sql.{Connection, PreparedStatement, Types}
import java.util.UUID
import zio.{IO, Task}

final class PostgresUsageRepository(database: LedgerDatabase) extends UsageRepository[IO] {
  override def transact[A](project: ProjectId)(operation: UsageTransaction => A): IO[Throwable, A] = database.transaction { connection =>
    val sql = new Jdbc(connection)
    sql.execute("INSERT INTO cq_usage_clock(project_id, cursor) SELECT project_id, 0 FROM cq_projects WHERE project_id = ? ON CONFLICT DO NOTHING")(_.setObject(1, project.value))
    val clock = sql.query("SELECT cursor FROM cq_usage_clock WHERE project_id = ? FOR UPDATE")(_.setObject(1, project.value))(_.getLong(1))
    if (clock.isEmpty) throw DomainFailure(Fault.Missing("Project not initialized"))
    operation(new PostgresUsageTransaction(connection, project))
  }

  override def read[A](project: ProjectId)(operation: UsageReader => A): IO[Throwable, A] = database.transaction { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    connection.setReadOnly(true)
    val exists = new Jdbc(connection).query("SELECT 1 FROM cq_projects WHERE project_id = ?")(_.setObject(1, project.value))(_.getInt(1))
    if (exists.isEmpty) throw DomainFailure(Fault.Missing("Project not initialized"))
    operation(new PostgresUsageTransaction(connection, project))
  }
}

final class PostgresUsageResource(repository: PostgresUsageRepository, database: LedgerDatabase)
    extends Lifecycle.LiftF[Task, UsageRepository[IO]](database.initialize.as(repository))

private final class PostgresUsageTransaction(connection: Connection, project: ProjectId) extends UsageTransaction {
  private val sql = new Jdbc(connection)
  private def projectKey(s: PreparedStatement): Unit = s.setObject(1, project.value)
  private def identity(s: PreparedStatement, id: UUID): Unit = { projectKey(s); s.setObject(2, id) }
  private def meterKey(s: PreparedStatement, key: MeterKey): Unit = { identity(s, key.attempt.value); s.setString(3, key.meter) }
  private def optionalText(s: PreparedStatement, index: Int, value: Option[String]): Unit = value match {
    case Some(text) => s.setString(index, text)
    case None => s.setNull(index, Types.VARCHAR)
  }
  private def optionalId(s: PreparedStatement, index: Int, value: Option[UUID]): Unit = value match {
    case Some(id) => s.setObject(index, id)
    case None => s.setNull(index, Types.OTHER)
  }
  private def tick(): Long = sql.query("UPDATE cq_usage_clock SET cursor = cursor + 1 WHERE project_id = ? RETURNING cursor")(projectKey)(_.getLong(1)).head
  override def cursor: Long = sql.query("SELECT cursor FROM cq_usage_clock WHERE project_id = ?")(projectKey)(_.getLong(1)).headOption.getOrElse(0L)
  override def assignment(id: AssignmentId): Option[Assignment] = sql.query("SELECT body::text FROM cq_usage_assignments WHERE project_id = ? AND assignment_id = ?")(identity(_, id.value))(r => Wire.decode(Assignment_JsonCodec, r.getString(1))).headOption
  override def attempt(id: AttemptId): Option[Attempt] = sql.query("SELECT body::text FROM cq_usage_attempts WHERE project_id = ? AND attempt_id = ?")(identity(_, id.value))(r => Wire.decode(Attempt_JsonCodec, r.getString(1))).headOption
  override def meter(key: MeterKey): Option[(UsageMeter, MeterProjection)] = sql.query("SELECT body::text, projection::text FROM cq_usage_meters WHERE project_id = ? AND attempt_id = ? AND meter = ?")(meterKey(_, key))(r => (Wire.decode(UsageMeter_JsonCodec, r.getString(1)), Wire.decode(MeterProjection_JsonCodec, r.getString(2)))).headOption
  override def observation(id: ObservationId): Option[RecordedUsage] = sql.query("SELECT body::text FROM cq_usage_records WHERE project_id = ? AND observation_id = ?")(identity(_, id.value))(r => Wire.decode(RecordedUsage_JsonCodec, r.getString(1))).headOption
  override def sample(key: MeterKey, position: Long): Option[RecordedUsage] =
    sql.query("SELECT r.body::text FROM cq_usage_heads h JOIN cq_usage_records r USING(project_id, observation_id) WHERE h.project_id = ? AND h.attempt_id = ? AND h.meter = ? AND h.position = ?") { s => meterKey(s, key); s.setLong(4, position) }(r => Wire.decode(RecordedUsage_JsonCodec, r.getString(1))).headOption
  override def outcome(request: RequestId): Option[AttemptOutcome] = sql.query("SELECT body::text FROM cq_usage_outcomes WHERE project_id = ? AND request_id = ?")(identity(_, request.value))(r => Wire.decode(RecordedOutcome_JsonCodec, r.getString(1)).value).headOption

  override def putAssignment(value: Assignment, actor: Actor, receivedAt: Long): Unit = {
    sql.execute("INSERT INTO cq_usage_assignments(project_id, assignment_id, attribution, cohort, evaluation_run, evaluation_scenario, actor, received_at, body) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)") { s =>
      identity(s, value.id.value); s.setString(3, value.attribution.toString); optionalId(s, 4, value.cohort)
      optionalText(s, 5, value.evaluation.map(_.run)); optionalText(s, 6, value.evaluation.map(_.scenario))
      s.setString(7, Wire.encode(Actor_JsonCodec, actor)); s.setLong(8, receivedAt); s.setString(9, Wire.encode(Assignment_JsonCodec, value))
    }
    value.members.foreach { item =>
      sql.execute("INSERT INTO cq_usage_members(project_id, assignment_id, ledger, number) VALUES (?, ?, ?, ?)") { s =>
        identity(s, value.id.value); s.setString(3, item.ledger.toString); s.setLong(4, item.number)
      }
    }
    tick()
    ()
  }

  override def putAttempt(value: Attempt, actor: Actor, receivedAt: Long): Unit = {
    sql.execute("INSERT INTO cq_usage_attempts(project_id, attempt_id, assignment_id, parent_id, session_id, actor, received_at, body) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)") { s =>
      identity(s, value.id.value); s.setObject(3, value.assignment.value); optionalId(s, 4, value.parent.map(_.value)); s.setObject(5, value.session.value)
      s.setString(6, Wire.encode(Actor_JsonCodec, actor)); s.setLong(7, receivedAt); s.setString(8, Wire.encode(Attempt_JsonCodec, value))
    }
    tick()
    ()
  }

  override def putMeter(value: UsageMeter, projection: MeterProjection, actor: Actor, receivedAt: Long): Unit = {
    sql.execute("INSERT INTO cq_usage_meters(project_id, attempt_id, meter, actor, received_at, body, projection) VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb)") { s =>
      meterKey(s, MeterKey(value.attempt, value.key)); s.setString(4, Wire.encode(Actor_JsonCodec, actor)); s.setLong(5, receivedAt)
      s.setString(6, Wire.encode(UsageMeter_JsonCodec, value)); s.setString(7, Wire.encode(MeterProjection_JsonCodec, projection))
    }
    tick()
    ()
  }

  override def projectMeter(key: MeterKey, projection: MeterProjection): Unit = {
    val changed = sql.execute("UPDATE cq_usage_meters SET projection = ?::jsonb WHERE project_id = ? AND attempt_id = ? AND meter = ?") { s =>
      s.setString(1, Wire.encode(MeterProjection_JsonCodec, projection)); s.setObject(2, project.value); s.setObject(3, key.attempt.value); s.setString(4, key.meter)
    }
    require(changed == 1, "Accounting meter disappeared")
  }

  override def append(value: UsageUpload, normalized: TokenCounts, actor: Actor): RecordedUsage = {
    val record = RecordedUsage(value, normalized, actor, tick())
    val observation = value.observation
    sql.execute("INSERT INTO cq_usage_records(project_id, observation_id, sequence, attempt_id, meter, source, position, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)") { s =>
      identity(s, observation.id.value); s.setLong(3, record.sequence); s.setObject(4, observation.attempt.value); s.setString(5, value.meter)
      s.setString(6, observation.source); s.setLong(7, observation.position); s.setString(8, Wire.encode(RecordedUsage_JsonCodec, record))
    }
    record
  }

  override def head(value: RecordedUsage): Unit = {
    sql.execute("INSERT INTO cq_usage_heads(project_id, attempt_id, meter, position, observation_id) VALUES (?, ?, ?, ?, ?) ON CONFLICT(project_id, attempt_id, meter, position) DO UPDATE SET observation_id = EXCLUDED.observation_id") { s =>
      meterKey(s, MeterKey(value.upload.observation.attempt, value.upload.meter)); s.setLong(4, value.upload.observation.position); s.setObject(5, value.upload.observation.id.value)
    }
    ()
  }

  override def putOutcome(value: AttemptOutcome, actor: Actor, receivedAt: Long): Unit = {
    val recorded = RecordedOutcome(value, actor, receivedAt, tick())
    val encoded = Wire.encode(RecordedOutcome_JsonCodec, recorded)
    sql.execute("INSERT INTO cq_usage_outcomes(project_id, request_id, attempt_id, actor, received_at, sequence, body) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)") { s =>
      identity(s, value.request.value); s.setObject(3, value.attempt.value); s.setString(4, Wire.encode(Actor_JsonCodec, actor)); s.setLong(5, receivedAt)
      s.setLong(6, recorded.sequence); s.setString(7, encoded)
    }
    val changed = sql.execute("UPDATE cq_usage_attempts SET effective_outcome = ?::jsonb WHERE project_id = ? AND attempt_id = ?") { s =>
      s.setString(1, encoded); s.setObject(2, project.value); s.setObject(3, value.attempt.value)
    }
    require(changed == 1, "Attempt disappeared during outcome admission")
  }

  override def latestOutcome(attempt: AttemptId): Option[RecordedOutcome] =
    sql.query("SELECT effective_outcome::text FROM cq_usage_attempts WHERE project_id = ? AND attempt_id = ?")(identity(_, attempt.value))
      (r => Option(r.getString(1)).map(Wire.decode(RecordedOutcome_JsonCodec, _))).headOption.flatten

  override def outcomes(attempt: AttemptId, after: Long, limit: Int): ReadPage[RecordedOutcome] =
    sql.page("SELECT body::text FROM cq_usage_outcomes WHERE project_id = ? AND attempt_id = ? AND sequence > ? ORDER BY sequence LIMIT ?", limit, RecordedOutcome_JsonCodec) { s =>
      identity(s, attempt.value); s.setLong(3, after); s.setInt(4, limit + 1)
    }

  override def attempts(filter: UsageFilter, after: Option[AttemptId], limit: Int): ReadPage[AttemptView] = {
    val pagination = after.fold("")(_ => " AND t.attempt_id > ?")
    sql.pageBy("SELECT a.body::text, t.body::text, t.effective_outcome::text FROM cq_usage_attempts t JOIN cq_usage_assignments a USING(project_id, assignment_id) WHERE t.project_id = ?" +
      filterSql(filter) + pagination + " ORDER BY t.attempt_id LIMIT ?", limit, AttemptView_JsonCodec) { s =>
      val index = bindFilter(s, filter)
      after match {
        case Some(id) => s.setObject(index, id.value); s.setInt(index + 1, limit + 1)
        case None => s.setInt(index, limit + 1)
      }
    } { r => AttemptView(Wire.decode(Assignment_JsonCodec, r.getString(1)), Wire.decode(Attempt_JsonCodec, r.getString(2)), Option(r.getString(3)).map(Wire.decode(RecordedOutcome_JsonCodec, _))) }
  }

  override def coverage(filter: UsageFilter): AttemptCoverage =
    sql.query("SELECT count(*) FILTER (WHERE t.effective_outcome IS NULL), " +
      "count(*) FILTER (WHERE t.effective_outcome->'value'->>'state' = 'Unknown'), " +
      "count(*) FILTER (WHERE jsonb_array_length(t.effective_outcome->'value'->'gaps') > 0) " +
      "FROM cq_usage_attempts t JOIN cq_usage_assignments a USING(project_id, assignment_id) WHERE t.project_id = ?" + filterSql(filter))
      (s => { bindFilter(s, filter); () })(r => AttemptCoverage(r.getLong(1), r.getLong(2), r.getLong(3))).head

  private def filterSql(filter: UsageFilter): String = filter match {
    case _: UsageFilter.ProjectAll => ""
    case _: UsageFilter.TaskOnly => " AND EXISTS (SELECT 1 FROM cq_usage_members member WHERE member.project_id = a.project_id AND member.assignment_id = a.assignment_id AND member.ledger = ? AND member.number = ?)"
    case _: UsageFilter.CohortOnly => " AND a.cohort = ?"
    case _: UsageFilter.SessionOnly => " AND t.session_id = ?"
    case UsageFilter.EvaluationOnly(_, scenario) => " AND a.evaluation_run = ?" + scenario.fold("")(_ => " AND a.evaluation_scenario = ?")
  }

  private def bindFilter(s: PreparedStatement, filter: UsageFilter): Int = {
    projectKey(s)
    filter match {
      case _: UsageFilter.ProjectAll => 2
      case UsageFilter.TaskOnly(item) => s.setString(2, item.ledger.toString); s.setLong(3, item.number); 4
      case UsageFilter.CohortOnly(id) => s.setObject(2, id); 3
      case UsageFilter.SessionOnly(id) => s.setObject(2, id.value); 3
      case UsageFilter.EvaluationOnly(run, scenario) =>
        s.setString(2, run)
        scenario match { case Some(value) => s.setString(3, value); 4; case None => 3 }
    }
  }

  private val JoinedScope = " JOIN cq_usage_attempts t USING(project_id, attempt_id) JOIN cq_usage_assignments a ON a.project_id = t.project_id AND a.assignment_id = t.assignment_id "

  override def meters(filter: UsageFilter, after: Option[MeterKey], limit: Int): List[MeterView] = {
    val afterSql = after.fold("")(_ => " AND (m.attempt_id, m.meter) > (?, ?)")
    sql.query("SELECT a.body::text, t.body::text, m.body::text, m.projection::text FROM cq_usage_meters m" + JoinedScope +
      "WHERE m.project_id = ?" + filterSql(filter) + afterSql + " ORDER BY m.attempt_id, m.meter LIMIT ?") { s =>
      val index = bindFilter(s, filter)
      after match {
        case Some(key) => s.setObject(index, key.attempt.value); s.setString(index + 1, key.meter); s.setInt(index + 2, limit)
        case None => s.setInt(index, limit)
      }
    } { r => MeterView(Wire.decode(Assignment_JsonCodec, r.getString(1)), Wire.decode(Attempt_JsonCodec, r.getString(2)), Wire.decode(UsageMeter_JsonCodec, r.getString(3)), Wire.decode(MeterProjection_JsonCodec, r.getString(4))) }
  }

  override def attemptsWithoutMeters(filter: UsageFilter): Long =
    sql.query("SELECT count(*) FROM cq_usage_attempts t JOIN cq_usage_assignments a USING(project_id, assignment_id) WHERE t.project_id = ?" + filterSql(filter) +
      " AND NOT EXISTS (SELECT 1 FROM cq_usage_meters m WHERE m.project_id = t.project_id AND m.attempt_id = t.attempt_id)")(s => { bindFilter(s, filter); () })(_.getLong(1)).head

  override def audit(filter: UsageFilter, after: Long, limit: Int): ReadPage[RecordedUsage] =
    sql.page("SELECT r.body::text FROM cq_usage_records r" + JoinedScope + "WHERE r.project_id = ?" + filterSql(filter) + " AND r.sequence > ? ORDER BY r.sequence LIMIT ?", limit, RecordedUsage_JsonCodec) { s =>
      val index = bindFilter(s, filter); s.setLong(index, after); s.setInt(index + 1, limit + 1)
    }
}
