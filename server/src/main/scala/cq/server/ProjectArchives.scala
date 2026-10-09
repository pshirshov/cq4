package cq.server

import cq.api.*
import cq.core.{DomainFailure, DriverRecords, LedgerPolicy, ProcessModePolicy, ProjectSettingKind}
import java.io.{FilterInputStream, FilterOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.sql.{Connection, SQLException}
import java.time.Clock
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import org.postgresql.PGConnection
import scala.util.Using
import zio.{Task, ZIO}

trait ProjectArchives {
  def backup(project: ProjectId, destination: Path): Task[BackupManifest]
  def restore(source: Path): Task[BackupManifest]
}

private[server] object ArchiveLimits {
  val MaxBytes = 512L * 1024 * 1024
  val ManifestBytes = 64 * 1024
  val BufferBytes = 64 * 1024
  def invalid(message: String): Nothing = throw DomainFailure(Fault.Invalid(message))
  def check(condition: Boolean, message: String): Unit = if (!condition) invalid(message)
  def bounded(size: Long): Unit = if (size > MaxBytes) throw DomainFailure(Fault.Limit("Project archive exceeds 512 MiB"))
}

final class PostgresProjectArchives(database: LedgerDatabase, clock: Clock, modes: ProcessModePolicy) extends ProjectArchives {
  import ArchiveLimits.*
  private val ValidationFetchRows = 32
  private val tables = List(
    BackupTable.Projects -> "cq_projects", BackupTable.Counters -> "cq_counters", BackupTable.Items -> "cq_items",
    BackupTable.Labels -> "cq_labels", BackupTable.Edges -> "cq_edges", BackupTable.History -> "cq_history",
    BackupTable.Requests -> "cq_requests", BackupTable.Changes -> "cq_changes", BackupTable.Claims -> "cq_claims",
    BackupTable.ClaimMembers -> "cq_claim_members", BackupTable.UsageClock -> "cq_usage_clock",
    BackupTable.UsageAssignments -> "cq_usage_assignments", BackupTable.UsageMembers -> "cq_usage_members",
    BackupTable.UsageAttempts -> "cq_usage_attempts", BackupTable.UsageMeters -> "cq_usage_meters",
    BackupTable.UsageCosts -> "cq_usage_costs", BackupTable.UsageRecords -> "cq_usage_records",
    BackupTable.UsageHeads -> "cq_usage_heads", BackupTable.UsageOutcomes -> "cq_usage_outcomes",
    BackupTable.UsageSpans -> "cq_usage_spans",
    BackupTable.Artifacts -> "cq_artifacts", BackupTable.ResultAdmissions -> "cq_result_admissions",
    BackupTable.Integrations -> "cq_integrations", BackupTable.IntegrationMembers -> "cq_integration_members",
    BackupTable.Worksets -> "cq_worksets", BackupTable.Drivers -> "cq_drivers", BackupTable.DrivePeriods -> "cq_drive_periods", BackupTable.Settings -> "cq_project_settings",
  )
  private def schema(sql: Jdbc): String = sql.query("SELECT checksum FROM cq_schema_migrations WHERE version = 1")(_ => ())(_.getString(1)).head
  private def columns(sql: Jdbc, table: String): String = sql.query(
    "SELECT quote_ident(attname) FROM pg_attribute WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped AND attgenerated = '' ORDER BY attnum"
  )(_.setString(1, table))(_.getString(1)).mkString(",")
  private def setup(sql: Jdbc): Unit = {
    sql.execute("SET LOCAL statement_timeout = '120s'")(_ => ())
    sql.execute("SET LOCAL lock_timeout = '5s'")(_ => ())
  }
  private def settled(sql: Jdbc, project: ProjectId, prefix: String): Unit = {
    val active = sql.query(s"SELECT EXISTS (SELECT 1 FROM ${prefix}cq_claims WHERE project_id = ? AND NOT released AND expires_at > ?) OR " +
      // An attached governing attempt without an outcome is open, not running: no CQ host observes it, and it may never get one.
      s"EXISTS (SELECT 1 FROM ${prefix}cq_usage_attempts WHERE project_id = ? AND effective_outcome IS NULL AND NOT COALESCE((${PersistedAttempts.unobserved("")}), false)) OR " +
      s"EXISTS (SELECT 1 FROM ${prefix}cq_integrations WHERE project_id = ? AND jsonb_exists(body->'resolution', 'Pending'))") { s =>
      s.setObject(1, project.value); s.setLong(2, clock.millis()); s.setObject(3, project.value); s.setObject(4, project.value)
    }(_.getBoolean(1)).head
    check(!active, "Project has active claims, running attempts or pending integrations; settle work before backup/restore")
  }
  private final class Budget {
    private var bytes = 0L
    def add(count: Int): Unit = { bytes += count; bounded(bytes) }
  }
  private final class Digest {
    private val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    def add(buffer: Array[Byte], offset: Int, length: Int, budget: Budget): Unit = {
      budget.add(length); bytes += length; digest.update(buffer, offset, length)
    }
    def hex: String = digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }
  private def safe[A](task: Task[A]): Task[A] = task.mapError {
    case error: SQLException => DomainFailure(Fault.Invalid(s"Project archive database operation failed (SQL state ${error.getSQLState}); verify the project before retrying restore because commit acknowledgement may be uncertain"))
    case error: java.util.zip.ZipException => DomainFailure(Fault.Invalid("Invalid project archive ZIP"))
    case error => error
  }

  override def backup(project: ProjectId, destination: Path): Task[BackupManifest] = safe(database.transaction { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    connection.setReadOnly(true)
    val sql = new Jdbc(connection); setup(sql)
    check(sql.query("SELECT 1 FROM cq_projects WHERE project_id = ?")(_.setObject(1, project.value))(_.getInt(1)).nonEmpty, "Project does not exist")
    settled(sql, project, "")
    val budget = new Budget
    val temporary = Files.createTempDirectory("cq-backup-")
    try {
      val entries = tables.map { case (kind, table) =>
        val digest = new Digest
        val rows = Using.resource(Files.newOutputStream(temporary.resolve(table))) { output =>
          val measured = new FilterOutputStream(output) {
            override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = { digest.add(bytes, offset, length, budget); out.write(bytes, offset, length) }
            override def write(value: Int): Unit = write(Array(value.toByte), 0, 1)
          }
          connection.unwrap(classOf[PGConnection]).getCopyAPI.copyOut(
            s"COPY (SELECT ${columns(sql, table)} FROM $table WHERE project_id = '${project.value}'::uuid) TO STDOUT (FORMAT binary)", measured)
        }
        BackupEntry(kind, rows, digest.bytes, digest.hex)
      }
      val manifest = BackupManifest(project, schema(sql), connection.getMetaData.getDatabaseMajorVersion, clock.millis(), entries)
      Using.resource(new ZipOutputStream(Files.newOutputStream(destination))) { zip =>
        zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(Wire.encode(BackupManifest_JsonCodec, manifest).getBytes(UTF_8)); zip.closeEntry()
        tables.foreach { case (_, table) =>
          zip.putNextEntry(new ZipEntry(table + ".copy")); Files.copy(temporary.resolve(table), zip); zip.closeEntry()
        }
      }
      bounded(Files.size(destination))
      manifest
    } finally {
      tables.foreach { case (_, table) => Files.deleteIfExists(temporary.resolve(table)) }
      Files.deleteIfExists(temporary)
    }
  })

  override def restore(source: Path): Task[BackupManifest] = safe(database.transaction { connection =>
    bounded(Files.size(source))
    val sql = new Jdbc(connection); setup(sql)
    Using.resource(new ZipInputStream(Files.newInputStream(source))) { zip =>
      check(Option(zip.getNextEntry).exists(_.getName == "manifest.json"), "Project archive must start with manifest.json")
      val bytes = zip.readNBytes(ManifestBytes + 1)
      check(bytes.length <= ManifestBytes, "Archive manifest exceeds its limit")
      val manifest = scala.util.Try(Wire.decode(BackupManifest_JsonCodec, new String(bytes, UTF_8))).getOrElse(invalid("Invalid archive manifest"))
      check(manifest.schemaSha256 == schema(sql), "Archive does not match the current CQ schema")
      check(manifest.postgresMajor == connection.getMetaData.getDatabaseMajorVersion, "Archive requires the same PostgreSQL major version")
      check(manifest.entries.map(_.table) == tables.map(_._1), "Archive table inventory differs from the current schema")
      check(manifest.entries.forall(e => e.rows >= 0 && e.bytes >= 0 && e.bytes <= MaxBytes && e.sha256.matches("[0-9a-f]{64}")), "Invalid archive entry metadata")
      bounded(manifest.entries.map(_.bytes).sum)
      check(sql.query("SELECT 1 FROM cq_projects WHERE project_id = ?")(_.setObject(1, manifest.project.value))(_.getInt(1)).isEmpty,
        "Project already exists; restore never overwrites a project")
      val budget = new Budget
      tables.zip(manifest.entries).foreach { case ((_, table), entry) =>
        check(Option(zip.getNextEntry).exists(_.getName == table + ".copy"), s"Missing or unexpected archive entry: $table")
        sql.execute(s"CREATE TEMP TABLE restore_$table (LIKE $table INCLUDING GENERATED INCLUDING CONSTRAINTS) ON COMMIT DROP")(_ => ())
        val digest = new Digest
        val measured = new FilterInputStream(zip) {
          override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
            val count = in.read(bytes, offset, length)
            if (count > 0) digest.add(bytes, offset, count, budget)
            count
          }
          override def read(): Int = { val one = new Array[Byte](1); if (read(one, 0, 1) == -1) -1 else one(0) & 0xff }
          override def close(): Unit = ()
        }
        val rows = connection.unwrap(classOf[PGConnection]).getCopyAPI.copyIn(s"COPY restore_$table (${columns(sql, table)}) FROM STDIN (FORMAT binary)", measured)
        check(rows == entry.rows && digest.bytes == entry.bytes && digest.hex == entry.sha256, s"Archive integrity check failed: $table")
        check(!sql.query(s"SELECT EXISTS (SELECT 1 FROM restore_$table WHERE project_id IS DISTINCT FROM ?)")(_.setObject(1, manifest.project.value))(_.getBoolean(1)).head,
          s"Archive contains another project's data: $table")
      }
      check(zip.getNextEntry == null, "Unexpected extra archive entry")
      check(manifest.entries.head.rows == 1, "Archive requires exactly one project")
      settled(sql, manifest.project, "restore_")
      Using.resource(connection.prepareStatement("SELECT body, summary, archived FROM restore_cq_items")) { statement =>
        statement.setFetchSize(ValidationFetchRows)
        Using.resource(statement.executeQuery()) { rows =>
          while (rows.next()) {
            val item = Wire.decode(Item_JsonCodec, rows.getString(1))
            LedgerPolicy.validate(item.draft)
            // Persisted outcomes may predate the current terminal policy; the comparison uses the recomputed outcome like every read.
            val summary = PersistedItems.summary(rows.getString(2))
            check(rows.getBoolean(3) == item.draft.archived && summary == LedgerPolicy.summary(item),
              "Archive current item projections disagree with its content")
          }
        }
      }
      // The work cursor reads the usage clock's count of attempt events instead of counting the attempts (D154).
      check(sql.query("SELECT COALESCE((SELECT attempt_events FROM restore_cq_usage_clock), 0) = (SELECT count(*) + count(effective_outcome) FROM restore_cq_usage_attempts)")(_ => ())(_.getBoolean(1)).head,
        "Archive usage clock disagrees with the attempts the archive holds")
      // The host delivers a stored setting as it is, so a restored one passes the checks of the write path.
      sql.query("SELECT kind, body::text FROM restore_cq_project_settings")(_ => ())(row => (row.getString(1), row.getString(2))).foreach { case (kind, body) =>
        val setting = scala.util.Try(Wire.decode(ProjectSetting_JsonCodec, body)).getOrElse(invalid("Archive holds a project setting that cannot be decoded"))
        check(ProjectSettingKind.of(setting).toString == kind, "Archive project setting kind disagrees with its content")
        setting match {
          case ProjectSetting.Requirements(text) => LedgerPolicy.validateRequirements(text)
          case mode: ProjectSetting.Mode => modes.validate(mode)
          case ProjectSetting.Agents(text) => LedgerPolicy.validateAgents(text)
        }
      }
      sql.query("SELECT body::text, summary::text, harness, session_key, revision FROM restore_cq_drivers")(_ => ()) { row =>
        val record = scala.util.Try(Wire.decode(DriverRecord_JsonCodec, row.getString(1))).getOrElse(invalid("Archive holds an undecodable driver"))
        scala.util.Try(PersistedDrivers.validate(record, manifest.project)).getOrElse(invalid("Archive driver content violates its invariants"))
        check(DriverRecords.summary(record) == Wire.decode(DriverSummary_JsonCodec, row.getString(2)) &&
          record.key.harness.toString == row.getString(3) && record.key.session == row.getString(4) && record.revision.value == row.getLong(5),
          "Archive driver identity or summary disagrees with its content")
        record
      }
      check(!sql.query("SELECT EXISTS (SELECT 1 FROM restore_cq_drivers d JOIN restore_cq_projects p USING(project_id) WHERE d.revision > p.driver_clock)")(_ => ())(_.getBoolean(1)).head,
        "Archive driver revision exceeds its project clock")
      tables.foreach { case (_, table) =>
        val fields = columns(sql, table)
        sql.execute(s"INSERT INTO $table ($fields) SELECT $fields FROM restore_$table")(_ => ())
      }
      PersistedDrivers.records(sql, manifest.project).foreach { record =>
        val revision = Revision(sql.query("UPDATE cq_projects SET driver_clock = driver_clock + 1 WHERE project_id = ? RETURNING driver_clock")(_.setObject(1, manifest.project.value))(_.getLong(1)).head)
        val now = clock.millis()
        PersistedDrivers.put(sql, manifest.project, DriverRecords.restored(record, now).copy(revision = revision))
        // A drive that was live in the archive ends here; one that already rested ended with its own Off period.
        if (record.state != DriverState.Off)
          PersistedDrivers.append(sql, manifest.project, record.drive, record.key, record.attached, DrivePeriodState.Off, Some(DriverStop.RestoredArchive), now)
      }
      sql.execute("UPDATE cq_catalogue_clock SET cursor = cursor + 1 WHERE singleton")(_ => ())
      manifest
    }
  })
}
