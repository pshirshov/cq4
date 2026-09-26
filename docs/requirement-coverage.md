# Requirement coverage

Requirements: [R01–R31](drafts/20260926-0957-cq-requirements-prompt.md). Milestones: [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planned ownership is not implementation evidence.

| Requirement | Owning milestones | State | Implementation and evidence |
| --- | --- | --- | --- |
| R01 Stack | M0, M6 | In progress | [M0 JVM/native stack proof passes](validation/m0-stack.md); complete release packaging remains M6 |
| R02 Live frontend | M1, M5 | Not started | — |
| R03 Baboon contracts | M0, M6 | In progress | Generated Scala/TS 0.2.0 ledger/claim/audit models compile; locked 0.1.0 history retained; [M1 core checks](validation/m1-core.md); [increment evidence](validation/m1-core.md) |
| R04 HTTP MCP | M0, M1 | In progress | Real SDK initialize/list/call passes on JVM/native; production tools and roles pending |
| R05 Fixed ledgers | M1 | In progress | Fourteen typed content/status branches and outcome classification; examples pass on both adapters; nested validation remains; [increment evidence](validation/m1-core.md) |
| R06 No custom ledgers | M1 | In progress | Closed generated Content/Ledger types and server allocation; no registration capability; transport exposure pending; [increment evidence](validation/m1-core.md) |
| R07 Provenance/gating | M1, M4 | In progress | Actor/session/request provenance persisted with history; host-observed evidence enforcement remains; [increment evidence](validation/m1-core.md) |
| R08 Process relationships | M4 | Not started | — |
| R09 Real evaluations | M2, M4, M6 | Not started | Earlier harness probes were not CQ evaluations |
| R10 History | M1, M3 | In progress | Creation/edit/archive/edge history and content restore tested; full relationship restore remains; [increment evidence](validation/m1-core.md) |
| R11 Atomic IDs | M1 | In progress | Concurrent per-project/ledger allocation and duplicate request replay pass; actual-client restart evidence remains; [increment evidence](validation/m1-core.md) |
| R12 PostgreSQL | M0, M1, M6 | In progress | Versioned DDL and real PostgreSQL transactions pass shared ledger scenarios; backup/restore remains M6; [increment evidence](validation/m1-core.md) |
| R13 Lean MCP | M1, M4 | Not started | — |
| R14 Archive attribute | M1 | In progress | Archive field, default omission, direct read and restoration pass shared scenarios; clients remain; [increment evidence](validation/m1-core.md) |
| R15 Query language | M1, M3 | In progress | Typed ledger/archive filter and stable ID ordering implemented; complete shared grammar remains M3; [increment evidence](validation/m1-core.md) |
| R16 Cohorts | M4 | Not started | — |
| R17 Canonical refs | M1, M3 | In progress | Canonical/inverse rows, endpoint history, duplicate normalization and project checks implemented; graph checks remain; [increment evidence](validation/m1-core.md) |
| R18 Worksets | M3 | Not started | — |
| R19 Bounded work | M3 | Not started | — |
| R20 Project identity | M1 | In progress | UUID project service initialization/reattachment tested; filesystem init/CLI and rename remain; [increment evidence](validation/m1-core.md) |
| R21 Supervision | M2 | Not started | — |
| R22 Privilege separation | M1, M2, M4 | In progress | Service mutation roles enforced; authenticated role credentials and harness restrictions remain; [increment evidence](validation/m1-core.md) |
| R23 Query editor | M5 | Not started | — |
| R24 Three panes | M5 | Not started | — |
| R25 Correction/reopening | M1, M4 | In progress | No transition gates; archive/status correction and content restoration pass; readiness/review applicability remains; [increment evidence](validation/m1-core.md) |
| R26 Termination | M3 | Not started | — |
| R27 Claims/integration | M1, M3 | In progress | Atomic sets, monotonic fences, expiry, renewal/release foundation; retained release-retry regression; integration remains; [increment evidence](validation/m1-core.md) |
| R28 Roles | M2, M4 | Not started | — |
| R29 Commands | M2, M4 | Not started | — |
| R30 Handle dispatch | M2, M4 | Not started | — |
| R31 Usage audit | M1, M2, M4, M5 | In progress | Initial typed assignment/attempt/observation models generated; audit persistence and accounting are next; [increment evidence](validation/m1-core.md) |
