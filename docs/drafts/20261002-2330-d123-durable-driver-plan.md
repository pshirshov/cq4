# D123: durable drivers and browser worksets — proposed implementation

This is a source-informed plan, not an implemented or verified result. Q46 (ordinary restart) and Q47 (archive restore) are Open and linked to D123. The operator already authorized including D123 and durable driver state in this batch. I17 remains outside scope pending the operator's configuration comments.

## Observed boundaries

`DriverRegistry` holds mutable process memory. DriverService and DriverBoundary mutate it during project transactions; LedgerRepository commits or rolls back only ledger state. `DriverRegistry.fail` deliberately records a Failure stop and then throws a Denied fault, so a rejected mutation rolls back while the driver stops. Simply replacing registry.put with SQL inside that transaction would roll back the stop as well.

Status, own-session status and settleable reads currently bypass the project transaction. DriverStatus exposes a full cycle/lineage, but has no touched/stopped time. WorksetAction already supports Discover, Create, Lookup and Preview. DriverOrigin has harness hook/extension origins only: browser Park needs an explicit operator operation rather than impersonating a hook. ProjectArchives includes worksets/settings and excludes external host journals and Git workspaces.

## 1. Durable state and transaction discipline

Edit the current schema in `001-ledgers.sql` and cq.api 0.1.0 in place. Introduce a typed persisted driver record encompassing the entire current DriverRecord and CycleRecord: binding offer and absolute expiry, start/resume tokens and consumption map, frozen preview, created members, lineage/resting/prompted state, carried integrations, attached session, state, stop reason, announcement state and timestamps. Public driver-list summaries contain no tokens.

Use `(project_id, harness, session_key)` as the unique driver identity. Store the typed payload as JSONB, with indexed summary columns needed for project/key/attached/cycle lookup. Keep the existing driver-count and idle-eviction policy unless a separately reviewed change is required; deletions participate in the same operation transaction. Validate cross-project identity and summary/payload consistency at the repository boundary.

Put the repository contract and transaction-scoped driver access in core; implement PostgreSQL and Dummy adapters using the operation's existing LedgerTransaction. Pass the transaction into DriverService and DriverBoundary; remove mutable global registry authority. An aborted ordinary operation must not leak any driver/token/lineage changes.

For a policy rejection that stops a driver, use an operation savepoint after acquiring the project lock. Catch a typed stop intent inside the repository operation, roll back all operation writes and after-commit callbacks to that savepoint, reload the original driver state, persist its Failure stop, and return an error value so that this stop commits. Raise the original Denied fault only after commit. PostgreSQL uses a savepoint; Dummy restores its transaction snapshot and callback list. The project lock stays held across rollback and stop, so another operation cannot continue or replace the failing cycle between them. A failure to persist the stop aborts the transaction and surfaces as a failure. Ordinary faults still roll back normally. This preserves the current rejection invariant without committing the rejected ledger mutation or introducing a second-transaction concurrency window.

Cheap status reads use repository read methods on a consistent committed snapshot without acquiring the project's write lock or touching timestamps. Fetch the narrow list projection for browser polling, and the full committed record only where the current harness status protocol needs it. No cache is required initially.

Q46 controls startup behavior. Recommended: ordinary restart preserves complete committed state; existing absolute expiry and host reconciliation still apply. Persistence is not evidence that an external child is alive.

## 2. Archive and delivery

Add driver records to BackupTable and ProjectArchives inventory and verify checksums and project identity as for other tables. Q47 controls restoration: recommended restore Off with an explicit archive-restored reason, invalid control tokens and retained lineage/carried-integration evidence. Define how invalidated pending-cycle tokens are represented before writing the model; an Off driver cannot activate or resume them.

Prepare a pinned DDL/checksum updater step using wave 5's established pattern. No historical model versions, schema copies or compatibility adapters. Existing in-memory drivers cannot be extracted from a previous process after it has exited: deployment must park/reconcile active drives before replacement and disclose the boundary.

## 3. Operator reads and browser control

Add a project-scoped driver list with key/harness, attached session, targets, phase, state, active-child count, stop reason and touched/stopped times. Read authorization follows existing project access; Park requires Human authority. Browser Park uses a dedicated request and expected driver revision so a stale row cannot park a replacement drive. Preserve existing hook-origin restrictions for harness Start/Continue.

Add a Drivers/worksets dialog following current dialog and table conventions. Refresh the cheap summary while open, with explicit loading/error/stale states. Open a driver's stored workset or preview its frozen targets/through value. Show advanceable/context members, readiness reasons, open Questions, requested OperatorActions and outside blockers from the server preview. Display the workset snapshot/cursor so a preview is not presented as current after a change.

Define worksets from explicit IDs (including the currently selected item) or a submitted query. Resolve every page of a query at one snapshot; reject a changed snapshot instead of silently mixing results. Preview before Create; store the exact previewed targets/phase only if the snapshot still holds. Offer no browser Start operation in this scope: the requested controls are define/preview/store and Park.

Filter results to the evaluated advanceable members of a chosen workset, label the active filter, and provide an explicit clear control. Preserve server ordering/pagination and query semantics; extend Browse with a typed workset constraint if the current API cannot express it. Context items remain separately visible in the workset view. Stored worksets are immutable, identified by ID; naming/renaming/deletion are outside this request.

## Verification and sequence

1. Reproduce state loss with a fresh service/registry over the same repository, then add shared Dummy/PostgreSQL contract cases for restart, consumed-token reuse, expiry, aborted writes, atomic durable failure stops and savepoint callback rollback, eviction and read isolation. Agents run focused Dummy/Local suites, one exact suite per invocation. PostgreSQL variants are delivery evidence for the operator unless a Task explicitly names a gate.
2. Implement persistence and archive behavior after Q46/Q47 are answered; regenerate codecs/signature and run focused checks.
3. Reproduce absent browser controls, then add focused browser checks for driver summaries, preview/store, hold-to-park, stale revisions, query pagination and workset filtering; run npm type checking and the focused fixtures.
4. Operator/host run configured and delivery gates, then package/update/rollback rehearsals, install and verify on the actual hostname. No ledger closure based solely on source or a proposed plan.

## Pending decisions

- Q46: preserve active state on ordinary restart (recommended), or stop explicitly on startup.
- Q47: retain stopped/token-invalidated records on archive restore (recommended), restore exact live state, or exclude drivers from archives.

The plan keeps current eviction limits and harness control rules; no new configuration or model version is proposed. Exact class/schema names are implementation choices, not additional product requirements.
