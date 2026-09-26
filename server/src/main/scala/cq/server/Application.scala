package cq.server

import cq.api.*
import cq.core.{DomainFailure, LedgerRepository, LedgerService, UsageService}
import zio.{IO, Task, ZIO}

final class Application(ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], authorization: Authorization) {
  def execute(authority: Authority, command: Command): Task[Result] = ZIO.attempt(authorization.check(authority)).flatMap { _ =>
    command match {
      case Command.Projects(after, limit) =>
        ZIO.attempt {
          authority.requireRoot()
          if (limit <= 0 || limit > 200) throw DomainFailure(Fault.Invalid("Page size must be 1–200"))
        } *> repository.projects(after, limit + 1).map { found =>
          val selected = found.take(limit)
          Result.Projects(ProjectPage(selected, selected.lastOption.map(_.id), found.size > limit))
        }
      case Command.Initialize(config) =>
        ZIO.attempt { authority.requireRoot(); authority.scope(config.project) }.flatMap(ledger.initialize(_, config.name)).map(Result.Initialized.apply)
      case Command.RenameProject(project, expected, name) =>
        ZIO.attempt { authority.requireRoot(); authority.scope(project) }.flatMap(ledger.rename(_, expected, name)).map(Result.Initialized.apply)
      case Command.Search(input) => scoped(authority, input.project) { scope =>
        ledger.search(scope, input.filter, input.after, input.limit).flatMap { page =>
          if (input.snapshot.exists(_ != page.cursor)) ZIO.fail(DomainFailure(Fault.Resync("Snapshot changed; restart search")))
          else if (input.after.nonEmpty && input.snapshot.isEmpty) ZIO.fail(DomainFailure(Fault.Invalid("Continuation requires snapshot cursor")))
          else ZIO.succeed(Result.Found(page))
        }
      }
      case Command.Read(input) => scoped(authority, input.project) { scope => input.selection match {
        case ReadSelection.ItemDetail(id) => ledger.get(scope, id).map(Result.Detail.apply)
        case ReadSelection.History(id, before, limit) => ledger.history(scope, id, before, limit).map(Result.History.apply)
        case ReadSelection.Changes(after, limit) => ledger.changes(scope, after, limit).map(Result.Changes.apply)
      }}
      case Command.Change(input) => scoped(authority, input.project)(scope => ledger.change(scope, input.change).map(Result.Changed.apply))
      case Command.ClaimWork(input) => scoped(authority, input.project) { scope => input.action match {
        case ClaimAction.Acquire(id, members, duration) => ledger.acquire(scope, id, members, duration).map(Result.Claimed.apply)
        case ClaimAction.Renew(fence, duration) => ledger.renew(scope, fence, duration).map(Result.Claimed.apply)
        case ClaimAction.Release(fence) => ledger.release(scope, fence).map(Result.Claimed.apply)
      }}
      case Command.Usage(input) => scoped(authority, input.project) { scope => input.selection match {
        case UsageSelection.Summary(filter) => usage.summary(scope, filter).map(Result.UsageSummary.apply)
        case UsageSelection.Attempts(filter, after, snapshot, limit) => usage.attempts(scope, filter, after, snapshot, limit).map(Result.UsageAttempts.apply)
        case UsageSelection.Outcomes(attempt, after, limit) => usage.outcomes(scope, attempt, after, limit).map(Result.UsageOutcomes.apply)
        case UsageSelection.Audit(filter, after, limit) => usage.audit(scope, filter, after, limit).map(Result.UsageAudit.apply)
      }}
    }
  }.catchSome { case DomainFailure(fault) => ZIO.succeed(Result.Failed(fault)) }

  private def scoped[A](authority: Authority, project: ProjectId)(operation: cq.core.Scope => Task[A]): Task[A] =
    ZIO.attempt(authority.scope(project)).flatMap(operation)

  def ingest(authority: Authority, input: HostUsageInput): Task[HostUsageResult] =
    ZIO.attempt(authorization.check(authority)) *> scoped(authority, input.project) { scope => input.operation match {
      case HostUsage.Assign(value) => usage.assign(scope, value).map(HostUsageResult.Assigned.apply)
      case HostUsage.Start(value) => usage.start(scope, value).map(HostUsageResult.Started.apply)
      case HostUsage.Meter(value) => usage.meter(scope, value).map(HostUsageResult.Metered.apply)
      case HostUsage.Ingest(value) => usage.ingest(scope, value).map(HostUsageResult.Ingested.apply)
      case HostUsage.Finish(value) => usage.finish(scope, value).map(HostUsageResult.Finished.apply)
    }}
}
