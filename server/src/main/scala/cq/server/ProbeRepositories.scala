package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.{Probe, Probe_JsonCodec, ProjectId}
import cq.core.ProbeRepository
import distage.Lifecycle
import io.circe.parser.parse
import java.sql.DriverManager
import scala.util.Using
import zio.{IO, Ref, Task, ZIO}

final case class DatabaseConfig(url: String, user: String, password: String)

final class PostgresProbeRepository(config: DatabaseConfig) extends ProbeRepository[IO] {
  override def put(value: Probe): IO[Throwable, Unit] = ZIO.attemptBlocking {
    Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
      Using.resource(connection.prepareStatement(
        "INSERT INTO cq_probe(project_id, body) VALUES (?, ?::jsonb) " +
          "ON CONFLICT(project_id) DO UPDATE SET body = EXCLUDED.body"
      )) { statement =>
        statement.setObject(1, value.project.value)
        statement.setString(2, Probe_JsonCodec.encode(BaboonCodecContext.Default, value).noSpaces)
        statement.executeUpdate()
        ()
      }
    }
  }

  override def get(project: ProjectId): IO[Throwable, Option[Probe]] = ZIO.attemptBlocking {
    Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
      Using.resource(connection.prepareStatement("SELECT body::text FROM cq_probe WHERE project_id = ?")) { statement =>
        statement.setObject(1, project.value)
        Using.resource(statement.executeQuery()) { result =>
          if (result.next()) {
            val decoded = for {
              json <- parse(result.getString(1))
              probe <- Probe_JsonCodec.decode(BaboonCodecContext.Default, json)
            } yield probe
            Some(decoded.fold(throw _, identity))
          } else None
        }
      }
    }
  }
}

final class DummyProbeRepository extends Lifecycle.LiftF[Task, ProbeRepository[IO]](
  Ref.make(Map.empty[ProjectId, Probe]).map { state =>
    new ProbeRepository[IO] {
      override def put(value: Probe): IO[Throwable, Unit] = state.update(_.updated(value.project, value))
      override def get(project: ProjectId): IO[Throwable, Option[Probe]] = state.get.map(_.get(project))
    }
  }
)

final class PostgresProbeResource(repository: PostgresProbeRepository, setup: DatabaseSetup)
    extends Lifecycle.LiftF[Task, ProbeRepository[IO]](setup.initialize.as(repository))

final class DatabaseSetup(config: DatabaseConfig) {
  def initialize: IO[Throwable, Unit] = ZIO.attemptBlocking {
    Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        statement.execute("CREATE TABLE IF NOT EXISTS cq_probe (project_id uuid PRIMARY KEY, body jsonb NOT NULL)")
        ()
      }
    }
  }
}
