package cq.core

import cq.api.{Probe, ProjectId}
import izumi.functional.bio.{Error2, F}

trait ProbeRepository[F[_, _]] {
  def put(value: Probe): F[Throwable, Unit]
  def get(project: ProjectId): F[Throwable, Option[Probe]]
}

trait ProbeService[F[_, _]] {
  def exchange(value: Probe): F[Throwable, Probe]
}

object ProbeService {
  final class Impl[F[+_, +_]: Error2](repository: ProbeRepository[F]) extends ProbeService[F] {
    override def exchange(value: Probe): F[Throwable, Probe] = for {
      _ <- repository.put(value)
      stored <- repository.get(value.project)
      result <- F.fromEither(stored.toRight(new IllegalStateException("Persisted probe disappeared")))
    } yield result
  }
}
