# M4 automatic cohort selection foundation

Baseline `7fea532`; one mutable `cq.api 0.1.0`. This implements the selector foundation of the [reviewed cohort contract](../design/cohorts.md). Final deterministic gates pass; the remaining R16 acceptance work is listed below.

Local dispatch now supports selection and selection-backed starts. A decision carries bounded choices and a retained evidence artifact; a choice fixes the operation, exact members, context handles and cohort identity independently of the subsequently acquired claim. Workflow runs require choices. Direct runs retain explicitly assigned dispatch. The existing server read capability now supports at most 32 exact revisions under a 256 KiB aggregate content allowance, returning omitted references explicitly. Server work is bounded by those 32 requested records; only fitting full views reach the selector.

Progress fingerprints exclude request/fence/harness identities and cosmetic item metadata. Offer fairness and inspection continuation are distinct: inspecting excluded candidates does not count as offering them. Original host inputs bind Planner compatibility to frozen task content, configured check definitions and Git base. Explicit assessments can group tasks without a direct common producer. Larger prior plans partition through their declared groups, retaining the original result as context. Per-member progress prevents regrouping from resetting unchanged work, and independent feedback affects the relevant member. Exact candidates retain their identity or return an explicit continuity reason; a fresh subgroup choice identifies its session-base restart.

## Retained evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence directory | Observation |
| --- | --- |
| `20260927T160229-fast` | First implementation compiles; 166 existing/new fast scenarios pass. This predates the fuller selection corpus |
| `20260927T160537-fast` | 167 pass, one fails: 32 foreign-claimed candidates repeatedly hide free task 33. The selection loop subsequently gained an inspection cursor; its regression passes in `20260927T162304-fast` |
| `20260927T160722-contracts` | Generated selector contracts make the Codex native guide exceed its 32 KiB system-instruction bound |
| `20260927T160846-contracts` | Guide reference aliasing passes canonical schema graph equivalence and wire round trips: 465 schema definitions, seven domain capabilities; governor guide 29,979 bytes. This predates later selector edits |
| `20260927T161119-cohort-boundary` | Actual publication ACK loss reproduced: first upload commits, its acknowledgement is suppressed, identical Select recomputes different choice IDs and conflicts under the same artifact ID |
| `20260927T161324-cohort` | Corrected ACK replay passes with identical publication bytes/choice identity, delivered governing usage and no child attempt for an unused choice |
| `20260927T161641-cohort` | ACK replay passes again. The connected workflow fails because Worker selection also offers its organizing goal as implementation work. Operation eligibility subsequently restricts automatic implementation to tasks and reports ineligible summary counts |
| `20260927T161818-cohort` | Corrected operation eligibility and the positive shared Planner → Worker → host validation → candidate Reviewer chain pass. Unknown compatibility supplied as `previous` incorrectly permits a shared Worker; exact-previous assessment policy subsequently corrected |
| `20260927T162120-cohort` | All connected cases pass: ACK replay, positive shared chain, Unknown singleton choices, and Unknown prior-chain splitting. Raw workflow Start, altered StartChoice arguments and wrong-fence admission are denied |
| `20260927T162304-fast` | Both shared selection scenarios pass, including 32-to-eight fairness across a changed graph and the excluded-pool regression. One of 169 scenarios fails: different host validation states on an independent reviewer result produce the same progress fingerprint |
| `20260927T162420-fast` | Validation-state regression passes; the remaining fingerprint regression shows that selection metadata incorrectly permits reconsideration |
| `20260927T162610-fast` | All 169 fast scenarios pass after both fingerprint corrections |
| `20260927T163107-fast` | Reproduces three admission gaps: fresh audit grouping without a joint prior result, regrouping bypass of member deferral, and readiness drift after a prerequisite reopens. A fourth failure is an invalid milestone fixture, not a product failure |
| `20260927T163243-fast` | All 173 fast scenarios pass. Deferral now records each member's operative input as well as the group; fresh audit reviews stay separate; start verification repeats graph readiness and snapshot checks. The corrected fixture also verifies whole-group producer intersection and contextual sibling exclusion |
| `20260927T163424-fast` | Two further failures: assessment groups without a common producer become singletons; a six-member prior plan with three valid two-member groups is reassessed as one six-member group |
| `20260927T163604-fast` | All 175 fast scenarios pass after assessment-driven group discovery and prior-plan partitioning. The same scenarios reject grouping when the original check command or Git base differs |
| `20260927T163757-fast` | Reproduces cross-member feedback contamination in progress fingerprints and selection of all six members despite five Accepted reviewer outcomes and one ChangesRequested outcome |
| `20260927T163948-fast` | Member feedback projection passes; exact previous selection still reports mixed outcomes as a claim/content failure |
| `20260927T164137-fast` | All 176 scenarios pass with explicit candidate continuity and fresh-from-base subgroup selection. A subsequent Astra review identified review applicability against changed check inventory/candidate as a further reproduction target |
| `20260927T164351-fast` | Reproduces stale Accepted-review suppression after adding a required check without changing task revisions |
| `20260927T164514-fast` | All 176 scenarios pass with original host input, check-definition/inventory and candidate binding before review-based suppression |
| `20260927T164600-cohort` | Connected supervisor fixtures pass again after the selection, member-progress, partition and review-applicability changes |
| `20260927T164808-fast` | Reproduces ignored explicit research context from a disjoint assignment and repeated first-eight offers from a sixteen-member Unknown prior plan |
| `20260927T164913-fast` | All 177 fast scenarios pass. Disjoint supplied results remain shared context; overlapping feedback is projected per member; a partitioned prior plan uses ordinary offer/inspection ordering |
| `20260927T164947-contracts` | Generation stability, Scala/TypeScript round trips and native guide schema equivalence pass: 465 schema definitions and seven domain capabilities |
| `20260927T165135-postgres` | All 97 shared PostgreSQL scenarios and all actual transport/CLI/role/supervisor/workflow/cohort/dispatch/combination/admission/shutdown/restart fixtures pass |
| `20260927T165957-process` | All 46 Scala process scenarios and 19 native guardian checks pass |

The current PostgreSQL fixture retains `20260927T165135-postgres/m4-selection-fixtures.json`. The initial decision is published twice with identical bytes after acknowledgement loss, with no child ticket for the unused choice. Compatible selection starts three children (Planner, Worker, candidate Reviewer); Unknown selection starts one Planner and returns singleton implementation choices. All four child assignments remain Shared over their original two members, and all three governing receipts report successful processes and delivered usage. The maximum structured dispatch reply is 1,327 bytes. This excludes MCP/native framing and does not measure tokens or efficiency; fixture usage is synthetic.

All four final gates match the same 220 current non-documentation source files. `20260927T165135-postgres/m4-selection-source-verification.json` records every digest and the zero-difference comparison, excluding `docs/` and `README.md`.

Independent Astra identified the ACK-replay and excluded-pool starvation cases before their reproductions. Further focused reviews identified large-prior-plan partitioning, member-specific feedback, explicit candidate continuity, review applicability and shared-context fingerprints; their corrections have retained evidence above. Astra approved the final selector-foundation increment with no blocking or major findings after independently checking all four PASS results, all 220 current source hashes and connected fixture evidence. This is scoped approval, not R16 or M4 completion.

The no-progress implementation currently normalizes admitted result envelopes, member order, cosmetic item metadata and independent per-member feedback. It does **not** yet normalize collector envelopes embedded in explicitly supplied raw `Validation` or `Combination` artifact bodies. Such envelope changes can still change the fingerprint; this is an unresolved R16 gap. Connected mixed-outcome splitting with historical-cost invariance, connected fairness/cosmetic-refresh scenarios, and real multi-item accepted-quality evaluations also remain required. This increment does not establish full R16 acceptance or measured token savings.

Targeted fixture command: `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check cohort`. The same fixture is included in PostgreSQL/native transport verification. Reproduce the final gates with `./dev/check fast`, `./dev/check contracts`, `./dev/check postgres` and `./dev/check process`; set the same evidence-root variable to retain results outside the checkout.
