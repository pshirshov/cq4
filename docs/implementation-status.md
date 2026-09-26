# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | Complete; Astra approved `439fc50` | [Milestone review](validation/m1-review.md); [Ledger/claim checks](validation/m1-core.md), [usage accounting checks](validation/m1-usage.md); [authenticated clients and CLI](validation/m1-interfaces.md); [browser foundation](validation/m1-browser.md) passes Chromium checks; remaining invariants follow |
| M2 first usable agent slice | In progress | [Installed harness inventory](design/harness-adapters.md); supervisor, artifacts, collectors and real evaluations next |
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

M2 has an implemented [immutable artifact boundary](design/artifacts.md): host publication, compact metadata, bounded explicit text reads and persistence across server SIGKILL. [Evidence](validation/m2-artifacts.md): 37 fast scenarios, 30 PostgreSQL scenarios plus actual clients/restart, and generated contracts pass. The [Linux process guardian and Scala driver](design/process-guardian.md) pass 16 helper scenarios and eight driver scenarios; Astra approved both foundations. Durable job control and workspace quarantine integration are next. The [installed harness inventory](design/harness-adapters.md) records observed CLI controls and documentation; no CQ consumer evaluation has run yet. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 has milestone approval; its isolated workspace foundation is available for M2. Query-plan/scale verification remains M3 work.

## Acceptance

M0 and M1 have independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
