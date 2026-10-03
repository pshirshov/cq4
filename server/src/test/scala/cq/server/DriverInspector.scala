package cq.server

import cq.api.*
import cq.core.LedgerRepository
import zio.{IO, Runtime, Unsafe}

// Host protocol tests use a synchronous ServerApi; inspection reads the same committed repository through that boundary.
final class DriverInspector(repository: LedgerRepository[IO]) {
  def get(project: ProjectId, key: DriverKey): Option[DriverRecord] = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(repository.driverRecords(project)).getOrThrowFiberFailure().find(_.key == key)
  }
}
