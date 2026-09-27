# M4 Planner cohort assessments

Baseline `03b2da1`; one mutable `cq.api 0.1.0`. This increment implements the assessment boundary in the [cohort design](../design/cohorts.md). Automatic selection, adaptation and fairness remain subsequent work.

Planner reports can assess disjoint groups of two to four exact task revisions without proposing ledger mutations. Each group carries a Compatible, Unknown or Incompatible disposition, an objective, dependency/interference reasoning and a complete per-member acceptance mapping. Zero-based indexes reference frozen criteria without duplicating their text. The host validates configured check names and the original materialized task content; server admission independently checks current revisions and complete criterion coverage inside the ledger transaction.

Reviewer/Plan accepts assessment-only reports by handle. Proposal preview/application still requires actual proposed mutations. Compact parent outcomes expose assessed counts and a grouping next action; Unknown/Incompatible assessments retain an explicit bounded blocker. Full assessment narratives stay behind result handles. An assessment grants neither mutation authority nor task acceptance.

## Verification

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Check | Evidence directory | Result |
| --- | --- | --- |
| Fast | `20260927T153427-fast` | 162 Scala scenarios, bridge and evaluation checks pass |
| Contracts | `20260927T153906-contracts` | Deterministic generation, Scala/TypeScript round trips, 452 schema definitions and seven domain MCP capabilities pass |
| PostgreSQL and actual supervisor fixtures | `20260927T153941-postgres` | 86 shared scenarios and all transport/CLI/supervisor/workflow/dispatch/combination/admission/shutdown/restart fixtures pass |

Local scenarios reject incomplete, overlapping, stale and singleton groups, inconsistent member dispositions, duplicate/missing criterion indexes and unconfigured check names. Shared dummy/PostgreSQL scenarios admit a valid assessment-only result, deny proposal application, and reject invalid criterion coverage without ledger changes. Projection checks keep parent traffic identical when a private objective grows and preserve Unknown as uncertainty.

The actual dispatch fixture exercises Compatible and Unknown Planner results followed by Reviewer/Plan, and a separate unconfigured-check failure. It checks that private assessment text does not appear in the governing transcript, child usage is delivered and the governing checkout is unchanged. All five child assignments retain both members with Shared attribution. `20260927T153941-postgres/m4-assessment-fixtures.json` records these observations: the largest parent dispatch reply is 799 bytes while the retained Planner publications exceed 10 KiB. This is a payload measurement, not an end-to-end token-efficiency result.

All three gates match the same 213 non-documentation sources (excluding `docs/` and `README.md`); `20260927T153941-postgres/m4-assessment-source-verification.json` records the comparison. Process-driver implementation is unchanged; the PostgreSQL gate reruns actual ownership/cancellation/recovery fixtures.

Retained failure: `20260927T153009-fast` rejected two surplus parentheses in the newly added tests. The corrected test sources pass the final fast gate. `20260927T153219-fast` also passed before the final explicit Unknown/Incompatible blocker prefix.

## Review and remaining work

Independent Astra approved the source and retained evidence with no blocking or major finding, conditional on final PostgreSQL PASS. That gate has passed. Astra independently compared all 213 source hashes and inspected the actual assessment fixtures. The forthcoming selector must revalidate exact revisions, original host input/check definitions and Git base before using an assessment. Accepted admission alone does not establish continuing applicability. Automatic shared implementation, no-progress handling, fairness, live multi-item processes and refreshed nine-route evaluations remain required; this increment does not close R16 or M4.
