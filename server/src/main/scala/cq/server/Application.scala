package cq.server

import cq.api.*
import cq.core.{ArtifactService, DomainFailure, LedgerRepository, LedgerService, ResultAdmissionService, IntegrationService, ProposalService, UsageService}
import zio.{IO, Task, ZIO}

final class Application(ledger: LedgerService[IO], repository: LedgerRepository[IO], usage: UsageService[IO], artifacts: ArtifactService[IO], admissions: ResultAdmissionService[IO], integrations: IntegrationService[IO], proposals: ProposalService[IO], authorization: Authorization) {
  def execute(authority: Authority, command: Command): Task[Result] = ZIO.attempt(authorization.check(authority)).flatMap { _ =>
    command match {
      case Command.Projects(after, snapshot, limit) =>
        ZIO.attempt {
          authority.requireRoot()
          if (limit <= 0 || limit > 200) throw DomainFailure(Fault.Invalid("Page size must be 1–200"))
          if (after.nonEmpty && snapshot.isEmpty) throw DomainFailure(Fault.Invalid("Continuation requires catalogue snapshot cursor"))
        } *> repository.projects(after, limit).flatMap { page =>
          if (snapshot.exists(_ != page.cursor)) ZIO.fail(DomainFailure(Fault.Resync("Catalogue changed; restart project list")))
          else ZIO.succeed(Result.Projects(page))
        }
      case Command.Initialize(config) =>
        ZIO.attempt { authority.requireRoot(); authority.scope(config.project) }.flatMap(ledger.initialize(_, config.name)).map(Result.Initialized.apply)
      case Command.RenameProject(project, expected, name) =>
        ZIO.attempt { authority.requireRoot(); authority.scope(project) }.flatMap(ledger.rename(_, expected, name)).map(Result.Initialized.apply)
      case Command.Search(input) => scoped(authority, input.project) { scope =>
        ledger.search(scope, input.query, input.after, input.limit).flatMap { page =>
          if (input.snapshot.exists(_ != page.cursor)) ZIO.fail(DomainFailure(Fault.Resync("Snapshot changed; restart search")))
          else if (input.after.nonEmpty && input.snapshot.isEmpty) ZIO.fail(DomainFailure(Fault.Invalid("Continuation requires snapshot cursor")))
          else ZIO.succeed(Result.Found(page))
        }
      }
      case Command.Read(input) => scoped(authority, input.project) { scope => input.selection match {
        case ReadSelection.Browse(query, order, after, snapshot, limit) => ledger.browse(scope, query, order, after, snapshot, limit).map(Result.Browsed.apply)
        case ReadSelection.Counts() => ledger.counts(scope).map(Result.Counts.apply)
        case ReadSelection.Proposal(id) => proposals.preview(scope, id).map(Result.Proposal.apply)
        case ReadSelection.Integration(id) => integrations.get(scope, id).map(Result.Integration.apply)
        case ReadSelection.Admission(attempt) => admissions.get(scope, attempt).map(Result.Admission.apply)
        case ReadSelection.Claims(members) => ledger.claimPreview(scope, members).map(Result.Claims.apply)
        case ReadSelection.Termination(roots, intent) => ledger.termination(scope, roots, intent).map(Result.Termination.apply)
        case ReadSelection.ArchivePreview(query, limit) => ledger.archivePreview(scope, query, limit).map(Result.ArchivePreview.apply)
        case ReadSelection.QueryComplete(query, cursor, limit) => ledger.complete(scope, query, cursor, limit).map(Result.QueryAnalyzed.apply)
        case ReadSelection.ArtifactInfo(id) => artifacts.metadata(scope, id).map(Result.ArtifactInfo.apply)
        case ReadSelection.ArtifactText(id, offset, limit) => artifacts.page(scope, id, offset, limit).map(Result.ArtifactText.apply)
        case ReadSelection.ItemDetail(id) => ledger.get(scope, id).map(Result.Detail.apply)
        case ReadSelection.ItemDetails(members, bytes) => ledger.details(scope, members, bytes).map(Result.Details.apply)
        case ReadSelection.History(id, before, limit) => ledger.history(scope, id, before, limit).map(Result.History.apply)
        case ReadSelection.Changes(after, limit) => ledger.changes(scope, after, limit).map(Result.Changes.apply)
      }}
      case Command.Graph(input) => scoped(authority, input.project) { scope =>
        ledger.workset(scope, input.roots, input.after, input.snapshot, input.limit).map(Result.Workset.apply)
      }
      case Command.Change(input) => scoped(authority, input.project)(scope => ledger.change(scope, input.change).map(Result.Changed.apply))
      case Command.ApplyProposal(input) => scoped(authority, input.project)(scope => proposals(scope, input.result).map(Result.Changed.apply))
      case Command.ClaimWork(input) => scoped(authority, input.project) { scope => input.action match {
        case ClaimAction.Takeover(id, owner, members, duration, snapshot) => ledger.takeover(scope, id, owner, members, duration, snapshot).map(Result.Claimed.apply)
        case ClaimAction.Acquire(id, members, duration) => ledger.acquire(scope, id, members, duration).map(Result.Claimed.apply)
        case ClaimAction.Renew(fence, duration) => ledger.renew(scope, fence, duration).map(Result.Claimed.apply)
        case ClaimAction.Release(fence) => ledger.release(scope, fence).map(Result.Claimed.apply)
      }}
      case Command.Usage(input) => scoped(authority, input.project) { scope => input.selection match {
        case UsageSelection.Costs(filter, after, snapshot, limit) => usage.costs(scope, filter, after, snapshot, limit).map(Result.UsageCosts.apply)
        case UsageSelection.Summary(filter) => usage.summary(scope, filter).map(Result.UsageSummary.apply)
        case UsageSelection.Attempts(filter, after, snapshot, limit) => usage.attempts(scope, filter, after, snapshot, limit).map(Result.UsageAttempts.apply)
        case UsageSelection.Outcomes(attempt, after, limit) => usage.outcomes(scope, attempt, after, limit).map(Result.UsageOutcomes.apply)
        case UsageSelection.Audit(filter, after, limit) => usage.audit(scope, filter, after, limit).map(Result.UsageAudit.apply)
      }}
    }
  }.catchSome { case DomainFailure(fault) => ZIO.succeed(Result.Failed(fault)) }

  private def scoped[A](authority: Authority, project: ProjectId)(operation: cq.core.Scope => Task[A]): Task[A] =
    ZIO.attempt(authority.scope(project)).flatMap(operation)

  def liveRevision(authority: Authority, scope: LiveScope): Task[LiveRevision] = for {
    _ <- ZIO.attempt { authorization.check(authority); if (scope.catalogue) authority.requireRoot() }
    catalogue <- if (scope.catalogue) repository.catalogueCursor.map(Some(_)) else ZIO.none
    project <- ZIO.foreach(scope.project) { project => scoped(authority, project) { permitted =>
      for { items <- repository.itemCursor(project); cursor <- usage.cursor(permitted) } yield ProjectCursors(project, items, cursor)
    }}
  } yield LiveRevision(catalogue, project)

  def upload(authority: Authority, input: ArtifactUpload): Task[ArtifactMetadata] =
    ZIO.attempt(authorization.check(authority)) *> scoped(authority, input.project)(artifacts.upload(_, input))

  def admit(authority: Authority, input: HostAdmissionInput): Task[ResultAdmission] =
    ZIO.attempt(authorization.check(authority)) *> scoped(authority, input.project)(admissions.admit(_, input))

  def integrate(authority: Authority, input: HostIntegrationInput): Task[IntegrationRecord] =
    ZIO.attempt(authorization.check(authority)) *> scoped(authority, input.project) { scope => input.operation match {
      case HostIntegration.Reserve(intent) => integrations.reserve(scope, intent)
      case HostIntegration.Observe(id, observation) => integrations.observe(scope, id, observation)
    }}

  def ingest(authority: Authority, input: HostUsageInput): Task[HostUsageResult] =
    ZIO.attempt(authorization.check(authority)) *> scoped(authority, input.project) { scope => input.operation match {
      case HostUsage.Assign(value) => usage.assign(scope, value).map(HostUsageResult.Assigned.apply)
      case HostUsage.Start(value) => usage.start(scope, value).map(HostUsageResult.Started.apply)
      case HostUsage.Meter(value) => usage.meter(scope, value).map(HostUsageResult.Metered.apply)
      case HostUsage.Ingest(value) => usage.ingest(scope, value).map(HostUsageResult.Ingested.apply)
      case HostUsage.Finish(value) => usage.finish(scope, value).map(HostUsageResult.Finished.apply)
    }}
}
