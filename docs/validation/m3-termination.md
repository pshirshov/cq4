# M3 whole-subgraph termination

The [termination contract](../design/termination.md) uses existing `read` and `change` capabilities, the same project transaction and request journal, and the single mutable 0.1.0 model. No persistent preview table or additional MCP tool is introduced.

## Behavior under verification

- Typed cancellation mappings for all active ledgers; existing terminal facts and every non-status draft field are preserved. Generic completion changes milestone/goal/task management outcomes; other active ledgers report explicit unsupported-outcome conflicts.
- Shared descendants, prerequisites and descendants behind excluded branches require explicit roots. Contextual milestone membership cannot expand siblings. Archived/terminal ancestors still expose active descendants.
- Exact caller/roots/intent/graph/claim snapshots. Claim renewal is stable; graph edits, new descendants, acquisition, release and expiry invalidate the relevant reviewed plan.
- Human authority can release a foreign claim after reviewing its full collateral membership. Governors cannot release another owner. Excluded-only claims remain untouched. The supplied fence set must exactly match the preview.
- One atomic apply with ordinary history/provenance, concurrent request replay and competing-plan rejection. A later mutation does not prevent replay of an already committed acknowledgement.
- Bounds: 64 roots, 1,024 visited selected/context records, 512 changed items and 512 KiB preview output. Shared fixtures exercise the exact 512-change apply, 513-change rejection, and byte overflow without returning partial results. Large bound fixtures seed the repository directly; ordinary semantic scenarios use normal service mutations.

## Reproductions and evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Gate | Directory | Observation |
| --- | --- | --- |
| Initial compile | `20260927T051634-fast` | Missing `TerminationPlanner` import in the DI module; corrected |
| Shared scenario compile | `20260927T052018-fast` | Test helper returned `Assertion` where `Unit` was required; corrected |
| Traversal regression | `20260927T052118-fast` | 96/97 passed; stale continuation assertion failed after the graph exceeded its bound; retained XML identifies the failing assertion |
| Explicit failure observations | `20260927T052317-fast` | 96/98 passed. Captured stale traversal returned `Limit`; a supplied expired termination fence incorrectly returned `ChangeAck` |
| Corrected fast corpus | `20260927T052450-fast` | Passed; 98 Scala scenarios, bridge and evaluator fixtures |
| Expanded schema-guide fixture | `20260927T052526-contracts` | Generation/compilation passed; old native-guide inventory expectation lacked `cq.read` after its schema crossed the existing guide threshold |
| Final generated contracts | `20260927T052628-contracts` | Passed; deterministic generation, cross-language round trips, 351 schema definitions and six MCP capabilities |
| PostgreSQL and actual clients/processes | `20260927T052650-postgres` | Passed; 52 shared scenarios, HTTP/MCP preview/apply/history/retry/worker denial, CLI, supervisor, dispatch, bounded shutdown/recovery and restart |
| Instrumented database access | `20260927T053126-access` | Passed; summary-only preview, graph and existing mutation/query/completion budgets at all three sizes |

The traversal refactor had moved snapshot validation after expansion. Validation now happens before following edges, preserving `Resync` even when the new graph exceeds the bound. Termination also checks the project cursor before recomputing a changed graph.

The initial bulk apply checked only that every active reviewed fence was supplied. An extra expired fence was ignored when a fresh preview contained no active claims. Exact equality of reviewed/supplied fence sets now rejects that stale authority before any claim or item write. Both reproductions remain in the shared dummy/PostgreSQL suite.

The fast gate passes all 98 scenarios and the generated-contract rerun passes. The native-guide inventory fixture now validates the generated `ReadInput` contract as well. The PostgreSQL corpus passes all 52 shared scenarios, actual clients, process-facing checks and restart. The access gate passes. Runtime and Scala test sources match the final fast gate; the later native-guide fixture edit is covered by the final contracts gate. Seven shared termination scenarios exercise mappings, exclusions, concurrency, history, owner/expiry behavior and bounds. The actual HTTP/MCP fixture compares previews, applies and replays through different transports, verifies history and denies worker mutation. The access fixture checks summary-only preview reads at 100/10,000/100,000 unrelated items per project.

| Items/project | Preview statements | Scan visits | Shared buffers | Reply bytes | Observed ms |
| --- | --- | --- | --- | --- | --- |
| 100 | 7 | 402 | 19 | 1,230 | 43.0 |
| 10,000 | 7 | 5 | 20 | 1,234 | 15.8 |
| 100,000 | 7 | 5 | 22 | 1,235 | 15.3 |

These are one-selected/one-prerequisite-context samples under local PostgreSQL instrumentation, not latency guarantees or large-apply performance measurements. The graph operation retains six statements at each size. Preview fetches no full item bodies. The calibrated small-table scan allowance and counting method are described in [query access evidence](m3-query-access.md). Larger closures are verified semantically through the 512-item apply fixture; their amplification and usage-ingestion scale remain part of the broader R19/M3 gate.

The retained generated Codex guide in `20260927T052526-contracts/wire/native-guides.json` is 27,062 encoded UTF-8 bytes for Governor and 9,175 for Worker/Reviewer (compact JSON). This is fixed schema overhead, not an efficiency improvement; real total-token comparisons remain R09/R30 work.

Astra approved the amended design and both reproduced corrections at source level, with no additional blocking/major finding. Astra independently approved the R26 increment after verifying all four final gates and source manifests, with no unresolved blocking/major findings. This is not full M3 approval.

## Limits and next work

This increment establishes the ledger transaction, not process settlement. Claim release invalidates subsequent fence checks and the existing supervisor renewal loop triggers cancellation. A release between the runner's final check and result publication remains an explicit R27 reproduction/admission gate. Git integration, full milestone review and human acceptance remain open. UI termination controls remain M5 work.

Reproduce sequentially:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
```
