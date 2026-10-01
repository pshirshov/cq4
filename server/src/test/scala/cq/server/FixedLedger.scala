package cq.server

import cq.core.*
import java.time.{Clock, Instant, ZoneOffset}
import zio.IO

object FixedLedger {
  /** A ledger service over `repository` that reads `clock`; services sharing `registry` share their driver state. */
  def service(repository: LedgerRepository[IO], clock: Clock, registry: DriverRegistry): LedgerService[IO] = {
    val parser = new QueryParser
    val worksets = new WorksetTraversal
    val termination = new TerminationPlanner(worksets)
    val planner = new WorksetPlanner(worksets)
    val boundary = new DriverBoundary(registry, planner)
    new LedgerService.Impl[IO](repository, clock, parser, new QueryCompleter(parser), worksets, termination, new ClaimPlanner,
      new LedgerMutation(termination, boundary), new DriverService(registry, planner), boundary)
  }

  def at(repository: LedgerRepository[IO], millis: Long): LedgerService[IO] =
    service(repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), new DriverRegistry)
}
