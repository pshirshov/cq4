package cq.server

import cq.api.*
import cq.core.*
import distage.Lifecycle
import java.nio.charset.StandardCharsets.UTF_8
import zio.{IO, Ref, Task, ZIO}

final class DummyArtifactResource extends Lifecycle.LiftF[Task, ArtifactRepository[IO]](
  Ref.Synchronized.make(Map.empty[(ProjectId, ArtifactId), StoredArtifact]).map { state =>
    new ArtifactRepository[IO] {
      override def put(value: StoredArtifact): IO[Throwable, ArtifactMetadata] = state.modifyZIO { current => ZIO.attempt {
        val key = (value.metadata.project, value.metadata.id)
        current.get(key) match {
          case Some(previous) => (ArtifactService.replay(previous, value), current)
          case None => (value.metadata, current.updated(key, value))
        }
      }}
      override def get(project: ProjectId, id: ArtifactId): IO[Throwable, Option[StoredArtifact]] = state.get.map(_.get((project, id)))
    }
  }
)

final class PostgresArtifactResource(repository: PostgresArtifactRepository, database: LedgerDatabase)
  extends Lifecycle.LiftF[Task, ArtifactRepository[IO]](database.initialize.as(repository))

final class PostgresArtifactRepository(database: LedgerDatabase) extends ArtifactRepository[IO] {
  private def read(sql: Jdbc, project: ProjectId, id: ArtifactId): Option[StoredArtifact] =
    sql.query("SELECT metadata::text, content FROM cq_artifacts WHERE project_id = ? AND artifact_id = ?") { statement =>
      statement.setObject(1, project.value); statement.setObject(2, id.value)
    } { row => StoredArtifact(Wire.decode(ArtifactMetadata_JsonCodec, row.getString(1)), new String(row.getBytes(2), UTF_8)) }.headOption

  override def put(value: StoredArtifact): IO[Throwable, ArtifactMetadata] = database.transaction { connection =>
    val sql = new Jdbc(connection)
    val meta = value.metadata
    sql.execute("INSERT INTO cq_artifacts(project_id, artifact_id, attempt_id, metadata, content) VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT DO NOTHING") { statement =>
      statement.setObject(1, meta.project.value); statement.setObject(2, meta.id.value); statement.setObject(3, meta.attempt.value)
      statement.setString(4, Wire.encode(ArtifactMetadata_JsonCodec, meta)); statement.setBytes(5, value.body.getBytes(UTF_8))
    }
    ArtifactService.replay(read(sql, meta.project, meta.id).getOrElse(throw new IllegalStateException("Persisted artifact disappeared")), value)
  }

  override def get(project: ProjectId, id: ArtifactId): IO[Throwable, Option[StoredArtifact]] =
    database.transaction(connection => read(new Jdbc(connection), project, id))
}
