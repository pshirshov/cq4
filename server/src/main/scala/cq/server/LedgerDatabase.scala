package cq.server

import baboon.runtime.shared.BaboonJsonCodec
import cq.core.ReadPage
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.util.Using
import zio.{IO, ZIO}

final class LedgerDatabase(config: DatabaseConfig) {
  def transaction[A](operation: Connection => A): IO[Throwable, A] = ZIO.attemptBlocking {
    Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
      connection.setAutoCommit(false)
      try {
        val result = operation(connection)
        connection.commit()
        result
      } catch {
        case error: Throwable =>
          try connection.rollback() catch { case rollback: Throwable => error.addSuppressed(rollback) }
          throw error
      }
    }
  }

  def initialize: IO[Throwable, Unit] = transaction { connection =>
    val sql = new Jdbc(connection)
    sql.query("SELECT pg_advisory_xact_lock(hashtextextended(current_schema() || ':cq:migrations', 0))")(_ => ())(_ => ())
    sql.execute("CREATE TABLE IF NOT EXISTS cq_schema_migrations (version integer PRIMARY KEY, checksum text NOT NULL)")(_ => ())
    val schema = SchemaIdentity.current()
    sql.query("SELECT checksum FROM cq_schema_migrations WHERE version = 1")(_ => ())(_.getString(1)).headOption match {
      case Some(previous) => require(previous == schema.sha256, "Applied migration 1 checksum differs")
      case None =>
        sql.execute(schema.sql)(_ => ())
        sql.execute("INSERT INTO cq_schema_migrations(version, checksum) VALUES (1, ?)")(_.setString(1, schema.sha256))
    }
    ()
  }

  def installation: IO[Throwable, cq.api.InstallationInfo] = transaction { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    val sql = new Jdbc(connection)
    val attached = PersistedAttempts.unobserved("")
    sql.query("SELECT (SELECT checksum FROM cq_schema_migrations WHERE version=1), " +
      "current_setting('server_version_num')::integer / 10000, " +
      "current_setting('fsync')::boolean, current_setting('synchronous_commit'), current_setting('full_page_writes')::boolean, " +
      "(SELECT count(*) FROM cq_claims WHERE NOT released AND expires_at > (extract(epoch FROM statement_timestamp()) * 1000)::bigint), " +
      s"(SELECT count(*) FROM cq_usage_attempts WHERE effective_outcome IS NULL AND NOT COALESCE(($attached), false)), " +
      "(SELECT count(*) FROM cq_integrations WHERE jsonb_exists(body->'resolution','Pending'))")(_ => ()) { row =>
      val applied = Option(row.getString(1)).getOrElse(throw new IllegalStateException("Applied schema identity is missing"))
      cq.api.InstallationInfo(cq.api.Command.baboonDomainVersion, SchemaIdentity.current().sha256, applied, row.getInt(2),
        row.getBoolean(3), row.getString(4), row.getBoolean(5), ProducingBuild.value, row.getLong(6), row.getLong(7), row.getLong(8))
    }.head
  }
}

private[server] final class Jdbc(connection: Connection) {
  def execute(sql: String)(bind: PreparedStatement => Unit): Int = Using.resource(connection.prepareStatement(sql)) { statement =>
    bind(statement)
    statement.execute()
    statement.getUpdateCount
  }

  def page[A](sql: String, limit: Int, codec: BaboonJsonCodec[A])(bind: PreparedStatement => Unit): ReadPage[A] =
    pageBy(sql, limit, codec)(bind)(rows => Wire.decode(codec, rows.getString(1)))

  def pageBy[A](sql: String, limit: Int, codec: BaboonJsonCodec[A])(bind: PreparedStatement => Unit)(read: ResultSet => A): ReadPage[A] =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      statement.setFetchSize(1)
      bind(statement)
      Using.resource(statement.executeQuery()) { rows =>
        val values = Iterator.continually(rows.next()).takeWhile(identity).map(_ => read(rows))
        ReadPage.select(values, limit, codec)
      }
    }

  def query[A](sql: String)(bind: PreparedStatement => Unit)(read: ResultSet => A): List[A] =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement)
      Using.resource(statement.executeQuery()) { rows =>
        val result = List.newBuilder[A]
        while (rows.next()) result += read(rows)
        result.result()
      }
    }
}
