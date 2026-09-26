# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | In progress | [Ledger/claim checks](validation/m1-core.md), [usage accounting checks](validation/m1-usage.md); [authenticated clients and CLI](validation/m1-interfaces.md); [browser foundation](validation/m1-browser.md) passes Chromium checks; remaining invariants follow |
| M2 first usable agent slice | Not started | — |
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

Latest workspace/core verification: `20260926T205412-fast` passes 34 scenarios. Final contracts verification passes at `20260926T205927-contracts`. Accounting/UI evidence is in [read bounds](validation/m1-read-bounds.md) and [interim corrections](validation/m1-astra-interim.md).

Full Astra M1 review requested two corrections: canonical workspace containment and complete HTTP response deadlines. Both reproduced and corrected. Containment checks and the complete HTTP/client/restart pipeline pass (`20260926T205703-postgres`). Final contracts verification passes; M1 approval is pending. Astra found no additional blocking/major application finding on recheck. M1 awaits final verification and approval; no user decision is currently required. See [workspace/deadline evidence](validation/m1-workspaces.md).

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 retains its full milestone review; the isolated workspace foundation is implemented. Query-plan/scale verification remains M3 work.

## Acceptance

M0 has independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
