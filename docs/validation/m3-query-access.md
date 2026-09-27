# M3 database access measurements

`dev/check access` runs actual HTTP requests against the normal JVM server and an isolated PostgreSQL 18 cluster. PostgreSQL `auto_explain` records execution plans, actual scan-node row activity, shared-buffer accesses and WAL counters. The driver preserves raw JSON logs, each operation's plans, operation timings, source hashes and a compact report. [PostgreSQL auto_explain](https://www.postgresql.org/docs/18/auto-explain.html), [JSON logging](https://www.postgresql.org/docs/18/runtime-config-logging.html#RUNTIME-CONFIG-LOGGING-JSONLOG).

The fixture grows two projects to 100, 10,000 and 100,000 unrelated items each, with label catalogs, history rows and unrelated edges. Bulk SQL seeding is outside measured requests. At each size it performs five replacements of the same item with one incident reference and a removed/added label, retries one exact request, and exercises ID/text/tag/reference/negative/page queries and ID/label completion. It verifies unchanged unrelated revisions and history row counts, one history entry/change event per replacement, compact acknowledgements and exact replay. A held project-row lock must block a same-project read while an independent project read finishes.

Astra independently approved this increment after the retained failures, corrections, passing access gate and final semantic/client gates. No blocking or major findings remain for this increment; full M3 is still incomplete.

## Budgets and interpretation

- Replacements: equal planned-statement counts at all sizes, at most 32 planned statements and 4,096 shared-buffer accesses. At 10,000/100,000 items, at most 256 scan-node visits.
- At 100 items, permit 1,024 scan-node visits. This is an explicit calibration after the retained initial gate found 606 visits from small-table sequential scans; the same operation used six visits at the larger sizes. PostgreSQL planner choices are left enabled.
- Completion: at most 64 scan-node visits for a page of 20 plus lookahead and project metadata. The isolated reference query must stay within 256 at the larger sizes.
- Scan visits sum rows emitted/filtered/rechecked, multiplied by loops, across every scan node, including parent scans. This is node work, not distinct rows; bitmap index/heap work can count a row more than once. Shared-buffer totals use each statement's root plan and therefore do not sum inclusive parent/child buffer counters repeatedly.
- Planned-statement counts exclude transaction/control statements without plans. End-to-end timings are observations of this local instrumented fixture, including HTTP/JDBC overhead and warm-up effects; they are not a general latency guarantee. Broad negations and arbitrary Boolean combinations may scan project data. No exact-count operation is hidden in a page.

These checks cover the sampled ordinary replacement and query operations. Larger affected closures, graph termination, claim/integration contention, and operational-usage ingestion remain separate work. The project lock deliberately serializes same-project reads and writes; this fixture proves that boundary rather than claiming absence of contention.

## Retained failures and corrections

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

- `20260927T043742-access`: fixture failure before measurement; reference input used incorrect generated field names. The retained logs also reproduced the JSON log filename and timestamp parsing errors. `reporter-reproduction.log` demonstrates that omitting composite scan parents undercounts node work. These are fixture corrections.
- `20260927T043937-access`: all actual workload and contention assertions passed. Reporting failed the initial small-table 256-visit budget. Raw plans account for 606 visits at size 100 and six at the two larger sizes, with 14 planned statements and 161–178 shared-buffer accesses per replacement. The small-table allowance above documents the calibration.
- The same run exposed a production reference-query access defect: `blocked-by:T2` returned one item after filtering 100,002 unrelated item rows at size 100,000. The correction replaces two item-correlated `EXISTS` branches with a union of project-bound edge endpoints inside tuple membership. The independent outer project predicate remains in place.
- `20260927T044156-access`: all workload, access budgets and contention checks passed. At 100,000 items the corrected reference query uses four scan-node visits and 16 shared-buffer accesses, compared with 100,006 and 17,304 before correction.
- `20260927T044400-fast`: 87 Scala scenarios plus bridge/evaluator fixtures passed. The expanded shared relation corpus checks every relation/inverse under AND, OR and NOT; another project's identical IDs retain matching edges after the selected project's edge is removed. This verifies both predicate semantics and branch scoping. Its source contains these additional test assertions; production source matches the passing access gate.
- `20260927T044424-postgres`: all 41 service scenarios and actual HTTP/MCP/CLI, role, supervisor, dispatch, shutdown/recovery and server SIGKILL/restart checks passed. Final source and test files match the fast/PostgreSQL manifests; only documentation was updated afterward.

| Unrelated items per project | Replacement planned statements | Replacement scan-node visits | Replacement shared buffers | Reference scan-node visits | Completion scan-node visits |
| --- | --- | --- | --- | --- | --- |
| 100 | 14 | 606 | 161–166 | 401 | 22 |
| 10,000 | 14 | 6 | 166–170 | 4 | 22 |
| 100,000 | 14 | 6 | 174–178 | 4 | 22 |

The 15 measured replacement latencies (five per size) span 18.3–25.5 ms in this run. The independent-project read completed in 15.5 ms while the other project was locked. These are instrumented local observations with a small sample. At each size, retry used two planned statements and two scan-node visits; completion used the expected project/display-ID or project/label indexes. Full raw plans preserve search observations beyond this table.

Run sequentially from the repository root:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
```

`access` needs the local isolated cluster; it refuses `CQ_TEST_DATABASE_URL` because its instrumentation settings must not change a shared server.
