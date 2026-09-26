# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | In progress | [Typed ledgers, transactions and claim checks](validation/m1-core.md); audit and interfaces remain |
| M2 first usable agent slice | Not started | — |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

User correction applied and verified: maintain one mutable `cq.api` 0.1.0 model; permit breaking changes and bump only on explicit instruction. Removed historical model copies and evolution fixtures. Generation refreshes the current signature and rejects unrequested extra versions. Contracts, dummy scenarios and PostgreSQL/JVM transport checks pass; [evidence](validation/m1-core.md#single-version-consolidation). Next: usage audit implementation.

M0 is implemented and verified at `1801c2a`, with Astra approval. The first M1 increment adds all fourteen typed content/status models, project initialization/reattachment in the service, atomic counters, idempotent batches, immutable history, canonical references, committed change cursors and explicit-set claims. Contracts, dummy checks and real PostgreSQL checks pass. This increment does not complete M1 and has not received a milestone review. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md) and [M1 core increment](validation/m1-core.md). Next: implement append-only usage accounting, then authenticated ledger/audit transport, CLI initialization and the minimal browser. Native evidence currently applies to M0 only. M1 also retains open validation, full relationship restore, snapshot paging and workspace-isolation work listed in its evidence manifest.

## Acceptance

M0 has independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
