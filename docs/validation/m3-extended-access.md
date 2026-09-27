# M3 affected-closure, integration and usage access

Extends the [ordinary mutation/query measurements](m3-query-access.md) through `dev/check access`. Requests use the actual HTTP application and isolated PostgreSQL 18 with `auto_explain`; this is a performance/effectual/local-communication fixture. Raw plans, source hashes, operation windows, seed assertions and failed runs are retained under `/srv/nvme/tmp/cq4-implementation`.

## Workload and limits

At each of 100, 10,000 and 100,000 unrelated rows per project, two projects contain unrelated items, incident edges, claims, history, pending integration memberships, settled integrations and usage assignments/attempts/meters/costs/observations. The measured task also has that many retained non-contributing usage details. SQL seeding and invariant queries are outside measured HTTP windows. `access-seeds.json` records table counts and verifies pending membership/hold/current-intent-fence consistency at every stage.

Bulk integration rows are synthetic physical-access fixtures copied from one application-admitted reservation. Their historical worker/reviewer admission evidence and completion-change fences still belong to the template. They establish the size and shape of queried indexes, not 100,000 successful application-level reservations. All measured reservations and observations use actual admission and integration APIs. Their Git IDs are synthetic; real Git behavior is covered by the [connected integration](m3-connected-integration.md) and [combination](m3-combination.md) fixtures.

Each size includes:

- Graph, termination preview, apply and exact retry over 4, 32 and 128 affected items. Bodies contain a 4 KiB narrative marker; graph/preview neither return it nor select full item bodies. Each apply adds one change event and one history entry/revision per member. Retry adds none.
- Reservation, incorporation recording, retry and read for one and 16 members, with separately admitted worker and reviewer handles. Reservation changes no item/history/event; recording adds the exact member effects and one event; retry/read preserve them.
- Incremental usage insertion, exact duplicate delivery, correction of an earlier contribution and task-only summary. Expected known totals are 25, 38 and 51. Fixed projections and direct-head lookup avoid replaying retained details.
- The existing ordinary replacements, queries, completion, claim reads/renewal and cross-project lock isolation checks.

These samples measure transaction-side notification publication: one immutable change event containing affected revision references. The current WebSocket service separately polls bounded change pages; this fixture has no connected subscribers and makes no fan-out throughput claim. Same-project ledger operations deliberately serialize on the project row. The lock check proves an independent project can continue while that row is held; it does not establish same-project parallel throughput.

## Budgets

For each extended operation let `n` be its affected membership, or one for usage. Planned statements must remain identical across unrelated dataset sizes and stay within `12n + 16`; shared buffers must stay within `128n + 256`. At 10,000 and 100,000 unrelated rows, scan-node visits must stay within `8n + 64`. At size 100 the allowance is `2048n + 64`, because PostgreSQL may choose repeated sequential scans of small tables. Planner settings remain enabled. Existing ordinary-operation budgets are unchanged.

Scan visits include emitted/filtered/rechecked rows multiplied by loops, including composite scan parents. They measure scan-node work, not unique rows. Root plan buffer totals avoid repeatedly counting inclusive child counters. Timings include local HTTP/JDBC overhead and instrumentation; they are observations, not latency guarantees. The sample closures are stars, not every possible topology. Semantic closure limits and exclusions have separate shared tests.

## Retained attempts

- `20260927T121908-access`: the workload reached integration observation and HTTP rejected the fixture's `Incorporated.candidate` field; the generated contract requires `target`. Corrected the fixture. This was not a production defect.
- `20260927T122245-access`: all HTTP behavior, seed counts and pending-membership consistency assertions passed. Reporting rejected the initial small-table closure budget: 4-member apply used 4,864 visits, 32-member apply used 42,852. Raw plans show repeated sequential scans of approximately 200–233-row edge, claim-member and integration-member tables. At 10,000/100,000 rows the corresponding operations used 28/32 and 224/228 visits. Astra inspected these plans and accepted the explicit small-table calibration while tightening larger-stage visits, statement and buffer budgets. No production correction was indicated.

- `20260927T122805-access`: final gate passed all 126 measured HTTP operations, including 72 extended operations in 24 groups. Seed, item/history/event, usage total, retry and project-lock assertions passed. Executable source matches this manifest; runtime sources also match the prior final contracts/fast/process/PostgreSQL manifests. Only the access fixtures changed executable behavior in this increment.

| Operation | Members | Statements at every size | Visits at 100 | Visits at 10,000 | Visits at 100,000 | Buffers at 100,000 |
| --- | --- | --- | --- | --- | --- | --- |
| Termination apply | 4 | 52 | 4,864 | 28 | 32 | 385 |
| Termination apply | 32 | 360 | 42,852 | 224 | 228 | 2,202 |
| Termination apply | 128 | 1,416 | 52,608 | 896 | 900 | 8,500 |
| Integration recording | 1 | 17 | 411 | 11 | 13 | 240 |
| Integration recording | 16 | 122 | 3,726 | 86 | 88 | 1,081 |
| Usage insertion | 1 | 11 | 409 | 6 | 9 | 109 |
| Usage exact retry | 1 | 4 | 4 | 4 | 6 | 35 |
| Usage correction | 1 | 13 | 613 | 9 | 12 | 128 |
| Task usage summary | 1 | 7 | 903 | 47 | 57 | 180 |

At the largest stage, closure applications took 29.2/67.1/179.3 ms for 4/32/128 members; usage insertion/correction/summary took 25.3/17.5/22.7 ms. The independent project read completed in 30.0 ms while the other project was locked. These are single local observations. All operation plans and response sizes are retained in `access-report.json` and the per-operation plan files.

The larger stages use item/edge endpoint indexes, pending-membership identity and owner indexes, observation identity/head indexes and task-attribution indexes. Termination apply uses `11n + 8` planned statements in this fixture; recording integration uses `7n + 10`. Retained non-contributing usage details are not loaded for insertion, correction or summary. A task summary's work can still grow with that task's actual assignments/meters; this fixture holds that applicable set constant.

Independent Astra approved this R19 increment and the M3 technical milestone after checking the final plans, all 24 operation groups, six seed snapshots, side-effect invariants and source manifests. No unresolved blocking or major finding remains. M2 human acceptance and M4–M6 remain open.

Reproduce from the repository root:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
```

This command needs its own instrumented cluster and rejects `CQ_TEST_DATABASE_URL`. Live-model calls are not part of this check.
