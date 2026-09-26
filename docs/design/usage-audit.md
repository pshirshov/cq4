# Operational usage audit

The `UsageService[F]` / `UsageRepository[F]` boundary implements accounting separately from `LedgerService`. PostgreSQL stores immutable assignments, attempts, meter definitions, observations and attempt outcomes. Mutable meter projections and observation-head pointers are derived accounting state. These tables are outside the fourteen workflow ledgers. No usage operation creates an item revision or ledger change event.

All types remain in the single mutable `cq.api` 0.1.0 model. Breaking development changes are permitted; version bumps require explicit user instruction.

## Identity and attribution

- Assignment identity freezes project, member IDs, direct/shared/unattributed classification, optional cohort execution and optional evaluation/scenario/assessor identity. Direct work has exactly one member; shared work has two or more; unattributed overhead has no members. Registration checks that members exist in the authenticated project. Archive and later regrouping cannot alter an assignment.
- An attempt freezes assignment, parent attempt, session, role, harness/provider/model, collector version and start time. A retry that performs model work gets a new attempt. Repeated registration of the same identity/content is idempotent; changed content is a conflict.
- A meter identifies one contributing counter stream within an attempt. It freezes increment/cumulative semantics and the baseline for resumed cumulative counters. A counter reset requires a new meter. Increment meters have zero token baselines and no cost baseline.
- An observation has a UUID, native source identity/position, occurrence time, raw counter fields, native inclusion semantics, cost basis, completeness/gaps and optional evidence artifact. Receipt time is assigned by the service. Uploads retain both raw and normalized numerical evidence. Host callers register meters and upload observations; the governing model does not forward these values.

Task views return direct totals and references to shared assignments. Shared totals are explicitly separate and must not be added across task views. Project/cohort/session/evaluation views visit each matching meter once. A declared work assignment is not a provider measurement of causal cost for individual members.

The report returns at most 200 shared assignment references and an explicit `sharedAssignmentsTruncated` flag. Meter aggregation continues over all matching pages. Observation drill-down has pages of 1–200 entries. Lifecycle metadata/outcome drill-down and transport response-size enforcement remain part of M1 interface work.

## Normalization and corrections

Normalized input includes cache reads/writes. Normalized output includes reasoning. Cache/reasoning counters remain available as subsets; total tokens add input and output only. Raw fields remain unchanged in the observation record. A missing cache category cannot silently become zero when converting exclusive input into inclusive input.

Each counter distinguishes observed, estimated, missing and unsupported values. Observed/estimated require a nonnegative integer; missing/unsupported omit it. Summaries expose known counts, unknown contribution counts and estimated contribution counts. A partial total retains any known input/output lower bound and an unknown marker. Measured zero has zero unknown contributions. Attempts without meters and meters without observations are explicitly incomplete.

Costs use exact decimal strings, currency, provider-estimate/price-table/actual-billing/unknown basis and optional pricing version. Price-table estimates require a version. Totals remain grouped by currency, basis and pricing version; billing and estimates are not combined. Unknown costs have no amount or currency and are counted separately. No subscription price or missing monetary value is converted into a billing claim.

For increment meters, each source position contributes once. For cumulative meters, the greatest source position replaces the previous cumulative contribution after subtracting the frozen baseline. Older delivery is retained without inflating the current total. Uncorrected decreases in cumulative tokens or cost are rejected.

A correction appends a new observation at the same meter position and must name the current observation it supersedes. The original remains immutable. The effective head and the affected meter projection change in the same transaction. Repeating an observation UUID returns its original receipt; repeating the same contributing source-position content also returns the existing receipt. A conflicting UUID or an unlinked replacement is rejected. Correction retries cannot apply the delta twice.

Overlapping aggregate/component evidence can be uploaded as `Detail` against the same meter with an explicit exclusion reason. It remains in the raw audit and does not contribute to the meter total. Installed-harness collectors must select the authoritative contribution stream from observed harness semantics; that adapter work is M2 and is not established by the synthetic accounting fixtures.

## Storage, access and retention

The initial PostgreSQL DDL includes indexes for assignment membership, cohort, evaluation/scenario, session, attempt and source position. Writes serialize on a separate project audit cursor, not the ledger change cursor. Ingestion accesses the target meter, relevant observation/head, and that meter's projection. It does not reload item content or update unrelated task/cohort aggregates. Cost-group projection size and transport payload budgets still need explicit interface limits before M1 closes.

Summary reads use PostgreSQL repeatable-read snapshots without locking the audit clock. They fold matching meter projections in pages of 200; raw drill-down uses the committed audit sequence. Total work is proportional to the matching meters rather than every historical raw observation. Query-plan/scale measurements remain M3 work. A project-wide report naturally reads that project's matching meters.

Only Collector/Human service scopes may register or ingest audit data. Governor/worker ledger permissions do not confer telemetry-writing permission. An authorized late observation is admitted after claim release or result rejection because usage ingestion has no work-claim precondition. Transport authentication and per-attempt collector credentials remain M1/M2 work; `Scope` is currently a trusted adapter input.

This increment retains all numerical records, frozen membership and corrections indefinitely. There is no deletion or retention job. Optional native evidence artifacts will have separate retention in the artifact service; removing a bulky artifact must retain its reference, numerical evidence and explicit detail-availability state. Artifact storage and retention execution are not implemented yet.

## Executable scenarios

`UsageContractTest` runs identical public-service scenarios against the in-memory and PostgreSQL adapters. They cover:

- 1,000 shared tokens for T1/T2 plus 200 and 300 direct tokens: project total 1,500. Correct shared usage to 1,100: project total 1,600. Preserve original records, unknown cost, cancellation, membership, and item history through duplicate delivery, concurrent correction retries, regrouping and archive.
- Exclusive input 48 + cache read 100 + cache write 10, output 20 including reasoning 13: normalized total 178. Retain an overlapping native aggregate without adding it again.
- Cumulative resumed usage with a nonzero baseline, out-of-order delivery, corrected current and historical observations, and exact decimal cost differences.
- Pending collection, measured zero, missing/unsupported metrics, source-position replay, rejected malformed values and unchanged state after failed ingestion.
- Late collection after claim release, denied model/collector cross-permission writes, project-filter denial, and multi-page summaries with bounded shared references.

These are deterministic accounting scenarios. They are not real Claude/Codex/Pi consumer evaluations, collector-completeness evidence or an efficiency claim.
