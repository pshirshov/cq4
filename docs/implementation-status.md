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

The usage audit now has immutable assignments/attempts/observations/outcomes, increment/cumulative normalization, correction/deduplication, indexed scope filters and separate direct/shared/unattributed summaries. Fourteen dummy and PostgreSQL scenarios pass, including the required 1,500 → 1,600 fixture. [Evidence and remaining scope](validation/m1-usage.md). Authenticated ledger/audit transport and CLI project initialization now pass actual PostgreSQL/client checks. See [interface evidence](validation/m1-interfaces.md). The [minimal browser](validation/m1-browser.md) now passes real Chromium workflow and recovery checks. Nested validation/provenance and full relationship restoration now pass shared dummy/PostgreSQL checks; see [core evidence](validation/m1-core.md). Project rename and whole-server SIGKILL/restart now pass real client checks; see [interface evidence](validation/m1-interfaces.md). Compact search and byte-bounded history/change/audit pages pass [dummy, PostgreSQL, browser and contract checks](validation/m1-read-bounds.md). Three [Astra interim findings](validation/m1-astra-interim.md) are corrected and accepted on recheck; Chromium draft/retry regressions pass. All 22 scenarios pass on both adapters; current contracts and full Chromium checks pass. Attempt outcome coverage and paginated lifecycle views pass all 23 dummy/PostgreSQL scenarios, actual clients, Chromium and contracts; Astra approved the fourth correction. Cost projection/summary bounds and exact monetary arithmetic pass all 27 dummy/PostgreSQL scenarios, actual clients, Chromium and contracts, with Astra source approval. Next: isolated workspace requirements and full M1 review.

M0 is implemented and verified at `1801c2a`, with Astra approval. The first M1 increment adds all fourteen typed content/status models, project initialization/reattachment in the service, atomic counters, idempotent batches, immutable history, canonical references, committed change cursors and explicit-set claims. Contracts, dummy checks and real PostgreSQL checks pass. This increment does not complete M1 and has not received a milestone review. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 retains workspace-isolation work and its full milestone review. Query-plan/scale verification remains M3 work.

## Acceptance

M0 has independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
