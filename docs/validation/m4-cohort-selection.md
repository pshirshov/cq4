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

At foundation commit `9e74e8a`, the no-progress implementation normalized admitted result envelopes, member order, cosmetic item metadata and independent per-member feedback. Collector envelopes in raw `Validation` and `Combination` artifacts remained an R16 gap; the follow-up below addresses that boundary. Connected mixed-outcome splitting with historical-cost invariance, connected fairness/cosmetic-refresh scenarios, and real multi-item accepted-quality evaluations remain required. This increment does not establish full R16 acceptance or measured token savings.

## Structured evidence normalization follow-up

Implementation now separates substantive Validation/Combination evidence from publication metadata. Validation retains configured check details, candidate, job outcome and output digests; Combination retains repository/target, observed target, candidate and member identities. Referenced validation in independent admitted results uses the same normalization. Original stored bytes and metadata remain unchanged, and own-result suppression still prevents a failed Worker from making its own result count as independent feedback.

| Evidence directory | Observation |
| --- | --- |
| `20260927T170803-fast` | Three expected failures: Validation job metadata and Combination publication metadata change progress fingerprints; republishing identical validation makes deferred work eligible |
| `20260927T171022-fast` | Initial correction passes all 180 scenarios, including changed output permitting reconsideration |
| `20260927T171440-fast` | Test syntax failure while adding malformed/provenance cases; not a product reproduction |
| `20260927T171615-fast` | Astra-directed regressions reproduce Human Validation acceptance and ignored changed output referenced only through an admitted Reviewer result; 180 pass, two fail |
| `20260927T171714-fast` | Extended fail-first assertion confirms all five invalid inputs accepted: workspace project, owner and base mismatches, Human Validation and Human Combination; nested changed-output failure repeats |
| `20260927T171852-fast` | Test syntax failure after updating the fingerprint input type; not a product reproduction |
| `20260927T171923-fast` | All 182 scenarios pass, including five provenance inconsistencies, malformed manifests, foreign output and changed observations reached through admitted Reviewer results |
| `20260927T172020-postgres` | All 98 PostgreSQL scenarios and actual transport/CLI/role/supervisor/workflow/cohort/dispatch/combination/admission/shutdown/restart fixtures pass |

The correction requires host Collector provenance, internally consistent workspace/combination identity and matching result check/candidate references. Malformed manifests and foreign output provenance are rejected. Repeated references are cached within selection. The host manifest's declared output digest is trusted; transcript chunks are not reconstructed during selection. This is a progress comparison, not validation/integration acceptance.

Both final gates match all 221 current non-documentation sources, with zero differences retained in `20260927T172020-postgres/m4-normalization-source-verification.json`. Contracts and process ownership were unchanged, so their prior foundation evidence remains applicable to those boundaries; this follow-up does not claim fresh runs of those gates. Astra independently verified both final PASS results and all 221 current source hashes, approving this scoped increment with no blocking or major finding. Cosmetic refresh, connected adaptation and full R16 acceptance remain open.

## Exact assessment refresh follow-up

Normalization is committed at `b345aee`. The following increment separates exact assessment applicability from semantic Worker retry eligibility. Stale assessment membership can identify a refresh subject without a common producer, while current exact groups take precedence. Only host-generated `AssessmentRequired` Planner choices use a key containing the whole group's semantic input and exact revision set. Every member uses that group key so a single changed revision refreshes the complete assessment. Selection and Start verification use the same retained reason. Ordinary Planner and Worker progress remains semantic.

| Evidence directory | Observation |
| --- | --- |
| `20260927T173128-fast` | 182 pass, one fails: a cosmetic revision loses the only grouping witness and offers no assessment refresh |
| `20260927T173252-fast` | First refresh and unchanged Worker deferral now pass; a second cosmetic revision still receives no refresh because the implicit Planner key ignores exact revisions |
| `20260927T173442-fast` | All 183 scenarios pass, including a second round changing only one member, whole-group refresh, repeated-refresh suppression, Start verification and unchanged unsuccessful Worker deferral |
| `20260927T173644-cohort` | All 12 shared PostgreSQL selector scenarios and actual ACK-replay, Compatible, Unknown and two-round cosmetic-refresh supervisor fixtures pass |
| `20260927T173829-fast` | Final fast gate passes all 183 scenarios on the same sources as the PostgreSQL/actual cohort gate |

Independent Astra approved the refinement, implementation source and final evidence. The targeted `cohort` gate now also runs the shared PostgreSQL selector scenarios. Its actual supervisor fixture completes two cosmetic revision rounds with one unsuccessful Worker and two subsequent assessment children. Together with the initial Planner this produces exactly four children, all Shared over the original two members, all with delivered usage. The second round changes only one member; the complete group is reassessed. Repeating a round offers no duplicate Planner, and refreshed assessments never re-enable the unchanged Worker.

`20260927T173644-cohort/m4-assessment-refresh-fixtures.json` retains the child tickets, statuses, assignments and compact fixture events. Across Compatible, Unknown and refresh cases there are eight children; the largest structured parent reply is 1,442 bytes (1,073 bytes in the refresh case). Fixture counters are synthetic, with no token-efficiency or billing claim. Mixed-outcome split costs, connected fairness and live multi-item accepted-quality execution remain separate R16 requirements.

Both final gates match all 221 non-documentation source files, with zero differences retained in `20260927T173644-cohort/m4-assessment-refresh-source-verification.json`. Independent Astra verified both PASS results and all 221 source hashes, and confirmed two-member refreshes at revision sets `(3,3)` then `(4,3)` with exactly one unsuccessful Worker. No blocking or major finding remains within this scope; full R16 remains open.

Targeted fixture command: `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check cohort`. The same fixture is included in PostgreSQL/native transport verification. Reproduce the final gates with `./dev/check fast`, `./dev/check contracts`, `./dev/check postgres` and `./dev/check process`; set the same evidence-root variable to retain results outside the checkout.
