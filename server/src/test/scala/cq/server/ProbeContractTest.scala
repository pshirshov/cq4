package cq.server

import cq.api.{Probe, ProjectId, Revision}
import cq.core.{ProbeRepository, ProbeService}
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class ProbeContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[ProbeRepository[IO]], DIKey[ProbeService[IO]]),
  )

  "Probe repository and BIO service" should {
    "preserve 64-bit values, Unicode, project isolation and replacement" in {
      (repository: ProbeRepository[IO], service: ProbeService[IO]) =>
        for {
          firstId <- ZIO.succeed(ProjectId(UUID.randomUUID()))
          secondId <- ZIO.succeed(ProjectId(UUID.randomUUID()))
          first = Probe(firstId, Revision(9007199254740993L), "λ first")
          second = Probe(secondId, Revision(Long.MaxValue), "second")
          absent <- repository.get(firstId)
          _ <- assertIO(absent.isEmpty)
          firstRoundTrip <- service.exchange(first)
          _ <- assertIO(firstRoundTrip == first)
          _ <- service.exchange(second)
          replacement = first.copy(text = "replacement")
          _ <- service.exchange(replacement)
          storedFirst <- repository.get(firstId)
          storedSecond <- repository.get(secondId)
          _ <- assertIO(storedFirst.contains(replacement) && storedSecond.contains(second))
        } yield ()
    }
  }
}

final class ProbeContractDummy extends ProbeContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class ProbeContractPostgres extends ProbeContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
