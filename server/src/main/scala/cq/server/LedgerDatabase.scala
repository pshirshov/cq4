package cq.server

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    val resource = "/db/001-ledgers.sql"
    val bytes = Using.resource(Option(getClass.getResourceAsStream(resource)).getOrElse(throw new IllegalStateException(s"Missing $resource")))(_.readAllBytes())
    val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
    sql.query("SELECT checksum FROM cq_schema_migrations WHERE version = 1")(_ => ())(_.getString(1)).headOption match {
      case Some(previous) => require(previous == checksum, "Applied migration 1 checksum differs")
      case None =>
        sql.execute(new String(bytes, StandardCharsets.UTF_8))(_ => ())
        sql.execute("INSERT INTO cq_schema_migrations(version, checksum) VALUES (1, ?)")(_.setString(1, checksum))
    }
    ()
  }
}

private[server] final class Jdbc(connection: Connection) {
  def execute(sql: String)(bind: PreparedStatement => Unit): Int = Using.resource(connection.prepareStatement(sql)) { statement =>
    bind(statement)
    statement.execute()
    statement.getUpdateCount
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
