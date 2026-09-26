# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | Complete; Astra approved `439fc50` | [Milestone review](validation/m1-review.md); [Ledger/claim checks](validation/m1-core.md), [usage accounting checks](validation/m1-usage.md); [authenticated clients and CLI](validation/m1-interfaces.md); [browser foundation](validation/m1-browser.md) passes Chromium checks; remaining invariants follow |
| M2 first usable agent slice | In progress | [Artifacts](validation/m2-artifacts.md), shared typed host HTTP client and [process guardian/driver](validation/m2-process.md) implemented; durable local jobs implemented; role wiring, collectors and real evaluations next |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

User correction applied and verified at `fbb7918`: maintain one mutable `cq.api` 0.1.0 model; permit breaking changes and bump only on explicit instruction. Historical model copies and evolution fixtures are removed. Generation refreshes the current signature and rejects unrequested extra versions.

M1 now includes:

- Fourteen typed ledgers, nested/provenance validation, canonical references, complete history/restore, project identity/rename, atomic IDs, idempotency and explicit-set claims.
- A separate operational usage audit with immutable attribution, correction history, attempt coverage, exact monetary arithmetic and bounded discovery/audit/cost pages.
- Authenticated HTTP/MCP/WebSocket/CLI clients and a minimal browser with live updates, durable drafts/retries, accounting drill-down and connection recovery.
- A local isolated-workspace foundation with frozen ownership/base, detached worktrees, separate indexes and durable quarantine after uncertain creation or changed identity.

M1 is complete at `439fc50`, with independent Astra approval after correction loops. [Final evidence and scope](validation/m1-review.md): 34 fast scenarios, 27 PostgreSQL scenarios plus actual clients/deadline/restart checks, contracts, and the unchanged browser corpus all pass.

M2 has an implemented [immutable artifact boundary](design/artifacts.md): host publication, compact metadata, bounded explicit text reads and persistence across server SIGKILL. [Artifact/HTTP evidence](validation/m2-artifacts.md) includes 30 PostgreSQL scenarios, actual clients/restart and generated contracts. The [Linux guardian and Scala driver](design/process-guardian.md) have Astra approval. The [durable local job service](design/local-jobs.md) now binds them to immutable journal records, cancellation and workspace quarantine: [42 fast scenarios and 16 helper plus 19 Scala process scenarios pass](validation/m2-jobs.md), including actual supervisor SIGKILL/recovery and stalled journal writes. Generated-contract verification passes. Astra approved this foundation at `cd0c6e5` after the reproduced storage/cancellation corrections. Unified distage role composition, local control transport and harness adapters are next. The [installed harness inventory](design/harness-adapters.md) records observed CLI controls and documentation; no CQ consumer evaluation has run yet. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 has milestone approval; its isolated workspace foundation is available for M2. Query-plan/scale verification remains M3 work.

## Acceptance

Queued for M6 at the user's request: replace manual Baboon compiler downloading with a pinned upstream flake input, retaining deterministic generation and contract verification. Current implementation work continues first.

M0 and M1 have independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
