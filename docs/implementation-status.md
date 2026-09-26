# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | In progress | [Ledger/claim checks](validation/m1-core.md), [usage accounting checks](validation/m1-usage.md); authenticated interfaces and remaining invariants are next |
| M2 first usable agent slice | Not started | — |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

User correction applied and verified at `fbb7918`: maintain one mutable `cq.api` 0.1.0 model; permit breaking changes and bump only on explicit instruction. Historical model copies and evolution fixtures are removed. Generation refreshes the current signature and rejects unrequested extra versions.

The usage audit now has immutable assignments/attempts/observations/outcomes, increment/cumulative normalization, correction/deduplication, indexed scope filters and separate direct/shared/unattributed summaries. Fourteen dummy and PostgreSQL scenarios pass, including the required 1,500 → 1,600 fixture. [Evidence and remaining scope](validation/m1-usage.md). Next: complete M1 authenticated ledger/audit transport, CLI project initialization and the minimal browser, together with the remaining validation/restore/snapshot/workspace requirements.

M0 is implemented and verified at `1801c2a`, with Astra approval. The first M1 increment adds all fourteen typed content/status models, project initialization/reattachment in the service, atomic counters, idempotent batches, immutable history, canonical references, committed change cursors and explicit-set claims. Contracts, dummy checks and real PostgreSQL checks pass. This increment does not complete M1 and has not received a milestone review. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 retains open validation, full relationship restore, snapshot paging, interface bounds and workspace-isolation work listed in its evidence manifests.

## Acceptance

M0 has independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
