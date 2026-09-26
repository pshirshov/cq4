# Requirement coverage

Requirements: [R01–R31](drafts/20260926-0957-cq-requirements-prompt.md). Milestones: [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planned ownership is not implementation evidence.

| Requirement | Owning milestones | State | Implementation and evidence |
| --- | --- | --- | --- |
| R01 Stack | M0, M6 | In progress | [M0 JVM/native stack proof passes](validation/m0-stack.md); complete release packaging remains M6 |
| R02 Live frontend | M1, M5 | In progress | Generated WebSocket browser, live changes, truthful health/sync and heartbeat recovery pass Chromium checks. Full lifecycle corpus remains M5. [Evidence](validation/m1-browser.md) |
| R03 Baboon contracts | M0, M6 | In progress | One 0.1.0 model; edits and breaking changes in place per user correction; no compatibility gate. See [current contract policy](design/contracts.md) and [increment evidence](validation/m1-core.md) |
| R04 HTTP MCP | M0, M1 | Implemented; M1 approved | Five generated-schema ledger/audit tools and enforced role profiles pass real SDK checks on JVM; earlier native evidence covers M0 only. [Interface evidence](validation/m1-interfaces.md) |
| R05 Fixed ledgers | M1 | Implemented; M1 approved | Fourteen typed content/status branches and outcome classification; nested narrative/citation/evidence validation passes on both adapters; [increment evidence](validation/m1-core.md) |
| R06 No custom ledgers | M1 | Implemented; M1 approved | Closed generated Content/Ledger types and server allocation; no registration capability; actual clients use generated closed contracts; [increment evidence](validation/m1-core.md) |
| R07 Provenance/gating | M1, M4 | In progress | Actor/session/request provenance persisted; fabricated human/host evidence is denied and recorded evidence preserved; operator confirmation stays bound to its action/expected evidence; host artifact admission and readiness remain; [increment evidence](validation/m1-core.md) |
| R08 Process relationships | M4 | Not started | — |
| R09 Real evaluations | M2, M4, M6 | Not started | Earlier harness probes were not CQ evaluations |
| R10 History | M1, M3 | In progress | Creation/edit/archive/edge history and full content/relationship restore pass both adapters, including neighbor revisions, claims and rollback; [increment evidence](validation/m1-core.md) |
| R11 Atomic IDs | M1 | Implemented; M1 approved | Concurrent per-project/ledger allocation and duplicate request replay pass; whole-server SIGKILL/restart preserves counters and acknowledgements; [increment evidence](validation/m1-core.md) |
| R12 PostgreSQL | M0, M1, M6 | In progress | Versioned DDL and real PostgreSQL transactions pass shared ledger scenarios; backup/restore remains M6; [increment evidence](validation/m1-core.md) |
| R13 Lean MCP | M1, M4 | In progress | Five ordinary capabilities; generated schemas include only referenced definitions. Compact search projections and byte-bounded pages implemented; dispatch and measured token budgets remain. [Interface evidence](validation/m1-interfaces.md) |
| R14 Archive attribute | M1 | Implemented; M1 approved | Archive field, default omission, direct read and restoration pass shared scenarios; browser/CLI archive filters implemented; [increment evidence](validation/m1-core.md) |
| R15 Query language | M1, M3 | In progress | Typed ledger/archive filter and stable ID ordering implemented; complete shared grammar remains M3; [increment evidence](validation/m1-core.md) |
| R16 Cohorts | M4 | Not started | — |
| R17 Canonical refs | M1, M3 | In progress | Canonical/inverse rows, endpoint history, duplicate normalization and project checks implemented; graph checks remain; [increment evidence](validation/m1-core.md) |
| R18 Worksets | M3 | Not started | — |
| R19 Bounded work | M1, M3 | In progress | Summary-only discovery and byte-bounded streaming pages; affected-row mutations. Fixed-size meter token projections and separately paginated cost aggregates implemented; scale/query-plan evidence remains. [Read bounds](validation/m1-read-bounds.md) |
| R20 Project identity | M1 | Implemented; M1 approved | UUID project service initialization/reattachment tested; CLI concurrent init, worktrees, moves, explicit reattachment and revision-checked display rename pass, including whole-server restart. [Interface evidence](validation/m1-interfaces.md); [increment evidence](validation/m1-core.md) |
| R21 Supervision | M2 | In progress | Guardian and driver foundations have Astra approval. Durable job journal, cancellation, exclusive ownership and workspace quarantine pass real process checks including a separate supervisor JVM receiving SIGKILL. Local transport, harness lifecycle and native integration remain. [Guardian evidence](validation/m2-process.md), [job evidence](validation/m2-jobs.md) |
| R22 Privilege separation | M1, M2, M4 | In progress | Service mutation roles enforced; signed project/role credentials and tool-call restrictions tested; native harness restrictions remain. [Interface evidence](validation/m1-interfaces.md); [increment evidence](validation/m1-core.md) |
| R23 Query editor | M5 | Not started | — |
| R24 Three panes | M5 | Not started | — |
| R25 Correction/reopening | M1, M4 | In progress | No transition gates; archive/status correction and content restoration pass; readiness/review applicability remains; [increment evidence](validation/m1-core.md) |
| R26 Termination | M3 | Not started | — |
| R27 Claims/integration | M1, M3 | In progress | Atomic sets, monotonic fences, expiry, renewal/release foundation; retained release-retry regression; isolated detached workspaces, ownership and quarantine pass real Git checks; integration remains; [workspace evidence](validation/m1-workspaces.md); [increment evidence](validation/m1-core.md) |
| R28 Roles | M2, M4 | Not started | — |
| R29 Commands | M2, M4 | Not started | — |
| R30 Handle dispatch | M2, M4 | In progress | Immutable artifact handles and bounded explicit reads pass dummy/PG, actual HTTP/MCP and SIGKILL restart checks; prompt assembly, dispatch, result chaining and measured traffic remain. [Evidence](validation/m2-artifacts.md) |
| R31 Usage audit | M1, M2, M4, M5 | In progress | Immutable audit, frozen attribution, normalization, corrections, idempotency, exact monetary arithmetic and bounded scoped summaries pass dummy/PG scenarios, including 1,500 → 1,600; authenticated host/read endpoints and CLI audit view implemented; bounded CLI/browser attempt and outcome-history views, explicit corrections and coverage gaps pass both adapters and Chromium; collectors, live usage invalidation and complete scoped UI remain. [Interface evidence](validation/m1-interfaces.md) [Evidence](validation/m1-usage.md) |
