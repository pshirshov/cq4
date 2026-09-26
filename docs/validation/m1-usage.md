# M1 usage audit increment

This increment implements service/repository accounting, not the completed M1 interface or a live harness collector. The model remains the single mutable `cq.api` 0.1.0 version required by the user.

## Verification

All commands ran from the implementation repository with `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation`.

| Command | Result | Evidence directory |
| --- | --- | --- |
| `./dev/check contracts` | Pass: deterministic single-version generation, strict TS/Scala compilation, codec exchange and existing MCP proof examples | `20260926T174715-contracts` |
| `./dev/check fast` | Pass: 14 scenarios, including 6 audit scenarios; no skips | `20260926T174755-fast` |
| `./dev/check postgres` | Pass: the same 14 scenarios against PostgreSQL 18.6, followed by existing JVM HTTP/WS/MCP proof clients | `20260926T174812-postgres` |

Each evidence directory contains source hashes, command/exit records, result manifest and logs. The PostgreSQL run applies the complete current initial DDL on a fresh isolated database. Earlier intermediate PostgreSQL verification also passed 13 scenarios in `20260926T174451-postgres`; the final run adds the bounded-summary scenario.

The [audit contract](../design/usage-audit.md) describes verified behavior and remaining scope. `UsageContractTest` uses identical public-service scenarios with dummy and real repositories through distage. No mock database interactions are asserted.

## Concrete accounting evidence

- Shared T1/T2 execution: 1,000 tokens. Direct work: T1 200, T2 300. Project total: 1,500. An appended correction changes shared usage to 1,100; project total becomes 1,600. Eight concurrent correction deliveries produce one receipt/effect. Original observations and frozen membership remain visible. Cancellation, regrouping and archive do not erase spend. Item history remains unchanged by audit operations.
- Raw exclusive input 48, cache read 100, cache write 10, output 20 with reasoning 13 normalize to inclusive input 158 and total 178. Supplemental aggregate evidence remains in the audit without increasing usage or cost.
- Resumed cumulative input/output/cost subtract the frozen baseline. Out-of-order observations do not add another cumulative total. Current and older explicit corrections preserve original records.
- Zero, missing/unsupported fields, incomplete collection, unknown monetary values and attempts without meters remain distinct. Failed validation leaves the report/cursor unchanged. A collector can upload after claim release but cannot mutate the task.
- A report covering 201 shared meters aggregates all 201 and returns 200 shared-assignment references with `sharedAssignmentsTruncated = true`. This exercises the second repository page on both adapters.

## Reproductions retained

`20260926T174338-fast/dummy-contract.log` records a cumulative-cost validation failure: a lower uncorrected cost was admitted. The service now applies the same monotonicity rule to cumulative cost and token counts. Corrections remain explicitly allowed.

`20260926T174623-fast/dummy-contract.log` records an unbounded-reference failure: 201 references were returned despite the 200-reference budget. The report now caps the reference list, explicitly marks truncation, and continues aggregation. Final dummy/PostgreSQL runs pass both regressions.

## Remaining scope

Authenticated transport/host ingestion, CLI/browser audit views, lifecycle metadata/outcome drill-down, payload/cost-group budgets, artifact storage/retention and real installed-harness collectors remain open. Project/attempt-scoped credential enforcement is not established by these service-scope tests. Existing transport verification still covers the M0 probe, not audit endpoints. Query-plan/scale measurements remain M3 work; native product execution remains required after integration. These synthetic fixtures make no collector-completeness or efficiency claim.

M1 is not complete and has not had its milestone Astra review. Keep the evidence directories through release acceptance. Reference snapshots remain unchanged.
