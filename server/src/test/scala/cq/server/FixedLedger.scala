package cq.server

import cq.core.*
import java.time.{Clock, Instant, ZoneOffset}
import zio.IO

object FixedLedger {
  /** A ledger service over `repository` that reads `clock`; driver state belongs to `repository`. */
  def service(repository: LedgerRepository[IO], clock: Clock): LedgerService[IO] = service(repository, clock, ProcessModePolicy.Release)

  def service(repository: LedgerRepository[IO], clock: Clock, modes: ProcessModePolicy): LedgerService[IO] = {
    val parser = new QueryParser
    val worksets = new WorksetTraversal
    val termination = new TerminationPlanner(worksets)
    val planner = new WorksetPlanner(worksets)
    val registry = new DriverRegistry
    val boundary = new DriverBoundary(registry, planner)
    new LedgerService.Impl[IO](repository, clock, parser, new QueryCompleter(parser), worksets, termination, new ClaimPlanner,
      new LedgerMutation(termination, boundary), new DriverService(registry, planner), boundary, modes)
  }

  def at(repository: LedgerRepository[IO], millis: Long): LedgerService[IO] =
    service(repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC))
}
