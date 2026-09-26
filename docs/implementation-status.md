# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | In progress | Typed ledger and durable transaction contracts are next |
| M2 first usable agent slice | Not started | — |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

M0 is implemented and verified at `1801c2a`. Astra approved that commit after checking all four current result manifests, matching source hashes, the native executable hash and runtime logs. No blocking or major finding remains for M0. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), including exact commands, source hashes, native executable identity and gaps. Next: implement M1 durable contracts, transactions, history, claims and usage accounting, then the minimal browser/CLI interfaces.

## Acceptance

M0 has independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
