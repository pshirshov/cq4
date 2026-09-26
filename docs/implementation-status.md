# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Verification passed; final Astra review pending | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | Not started | — |
| M2 first usable agent slice | Not started | — |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

The pinned environment, generated contracts, schema evolution, Scala/TypeScript wire exchange, shared dummy/PostgreSQL scenario, and real JVM/native HTTP/WS/MCP checks pass. Astra's preliminary source review found no blocking or major M0 finding; final review of the committed increment is pending. No user decision is currently required.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). The baseline remains `7e3076a`; the M0 working increment has not yet been committed.

Current evidence: [M0 manifest](validation/m0-stack.md), including exact commands, source hashes, native executable identity and gaps. Next: commit this verified increment and complete Astra review, then implement M1 durable contracts, transactions, history, claims and usage accounting.

## Acceptance

M2 and M6 human acceptance are pending; neither evidence package exists. No implementation milestone has been completed or reviewed.
