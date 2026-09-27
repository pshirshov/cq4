# M3 graph and concurrency review

M3 implements the [plan's graph, query and concurrency scope](../drafts/20260926-1549-cq-implementation-plan.md). Runtime baseline is `cd02171`; the closing increment extends physical database access verification without changing runtime contracts or services. The schema remains the single mutable `cq.api` 0.1.0.

## Evidence by requirement

| Requirement | Implemented boundary | Verification |
| --- | --- | --- |
| R15 shared search | Typed Boolean AST, exact IDs/text/scalar/ref/archive filters, scope-independent SQL, snapshots, diagnostics and completion | [Parser](m3-query-parser.md), [search](m3-query-search.md), [completion](m3-query-completion.md), current shared service corpus and actual HTTP/MCP/CLI |
| R17 canonical references | One edge with normalized inverses, endpoint validation, atomic endpoint revisions/history, restore and incident traversal | [Core](m1-core.md), full relation corpus in [query access](m3-query-access.md), workset/termination/production scenarios |
| R18 transient worksets | Bounded selected closure with separate context/readiness, shared descendants and no contextual sibling expansion | [Worksets](m3-worksets.md): shared dummy/PostgreSQL scenarios, byte/count limits and actual clients |
| R19 bounded work | Affected-row writes, indexed membership/observation queries, fixed usage projections and one affected-reference change event | [Initial access](m3-query-access.md), [extended access](m3-extended-access.md): raw plans at 100/10,000/100,000 unrelated rows/project; 4/32/128-member closures; history/retry and project-lock assertions |
| R26 termination | Typed outcomes/exclusions, exact stale-plan rejection including new descendants, reviewed claims and one atomic idempotent apply | [Termination](m3-termination.md): full shared corpus, actual preview/apply clients and increasing-size application measurements |
| R27 claims and integration | Exact membership/fencing/takeover/production, durable result admission, bounded claim-loss cancellation, reserved conditional Git update, revalidated combined candidates and reconciliation | [Claims](m3-claims.md), [admission](m3-result-admission.md), [reservations](m3-integration-reservations.md), [Git coordinator](m3-git-coordinator.md), [connected integration](m3-connected-integration.md), [combination](m3-combination.md) |

The current PostgreSQL corpus includes stale descendant membership, exclusions, rollback, replay, takeover, clock rollback and late admission. Actual supervisor fixtures cover claim loss and result rejection. Two distinct governors use real scratch Git repositories and PostgreSQL: one candidate advances the target, the other combines against it, validates and obtains a new review. Clean and conflicting cases preserve both changes; another advancement forces another combination. Actual supervisor SIGKILL after Git incorporation is reconciled to one recorded effect without relaunch or another Git update. These scenarios use controlled harness executables; live-model combined-candidate evaluation remains later work.

## Final gates

All evidence directories are under `/srv/nvme/tmp/cq4-implementation`.

| Command | Evidence | Result |
| --- | --- | --- |
| `dev/check contracts` | `20260927T120110-contracts` | Passed generation/round trips, 406 schema definitions, six domain MCP capabilities and actual governor prompt-size bound |
| `dev/check fast` | `20260927T120159-fast` | Passed 136 Scala scenarios plus bridge/evaluator fixtures |
| `dev/check process` | `20260927T120218-process` | Passed 41 Scala process scenarios and 19 guardian checks |
| `dev/check postgres` | `20260927T120243-postgres` | Passed 73 Scala service scenarios plus actual transport, roles, CLI, supervisor, dispatch, integration/combination, admission, shutdown/recovery and restart fixtures |
| `dev/check access` | `20260927T122805-access` | Passed all 126 measured operations, 72 extended operations, seed and side-effect invariants, bounded-work budgets and lock isolation |

The four earlier gates share the final runtime source; the closing increment changes only access fixtures and documentation. The final access manifest includes both fixtures and matches current executable source. `20260927T122805-access/m3-source-verification.json` records the comparison. Reproductions, fixture errors, actual production corrections and superseded runs remain in the linked increment reports.

Run gates sequentially from the repository root, with `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c` before each command. The access gate requires its isolated local instrumented PostgreSQL cluster.

## Verdict and remaining scope

Independent Astra approved the R19 increment and M3 technical milestone after reviewing the requirements, current source, runtime evidence and final access gate/manifests. No unresolved blocking or major finding remains. This satisfies M3's technical exit criteria.

M2's designated human acceptance remains pending. M4 full process, adaptive cohorts, all nine live harness routes, and stored proposals; M5 complete UI; and M6 native release/backup/package verification and designated human acceptance remain open. This report does not claim release completion, production latency guarantees or measured token savings. Real first-slice cross-harness evidence is retained in the [M2 review](m2-review.md).
