# M3 transient worksets

The [workset service](../design/worksets.md) implements R18 through generated HTTP/WebSocket contracts, a read-only MCP `graph` capability and `cq query --roots`. The same mutable 0.1.0 model now exposes the existing outcome policy in compact item summaries. Graph reads do not persist membership, obtain claims or change item state.

## Scope

- Iterative selected-work closure follows `Produces` and `Contains`; one-hop contextual relationships never expand siblings or unrelated children.
- Shared descendants appear once with outside-producer reasons. Explicit roots remain marked; archived/terminal ancestors do not hide active descendants.
- Direct prerequisite satisfaction is distinct from terminality and archival. Readiness is informational, independent of claim ownership and host admission.
- Root/project-bound snapshots prevent mixing selection pages; project changes, including new descendants, invalidate continuation.
- At most 64 roots and 1,024 visited selected/context items. Larger traversal fails explicitly. Pages obey count and encoded-byte limits while using summary-only item reads.

## Evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Gate | Directory | Result |
| --- | --- | --- |
| Initial compile/fast corpus | `20260927T045441-fast` | Compiled; 86/87 scenarios passed. The old MCP inventory assertion lacked the new read-only graph capability and was updated |
| Shared workset/fast corpus | `20260927T050026-fast` | Passed; 91 Scala scenarios plus bridge/evaluator fixtures |
| Generated contracts | `20260927T050322-contracts` | Passed; deterministic generation, TypeScript compilation, cross-language round trips, 317 definitions and six MCP capabilities |
| PostgreSQL and actual clients | `20260927T050348-postgres` | Passed; 45 shared scenarios, HTTP/MCP and CLI graph pagination/scope checks, roles, dispatch, bounded shutdown, recovery and restart |
| Instrumented database access | `20260927T050824-access` | Passed; raw PostgreSQL plans, endpoint workload, contention and graph budgets |

The four shared workset scenarios cover selected/context separation, explicit milestone expansion, shared descendants, prerequisite/review/producer context, cycles, archive/terminal distinctions, empty roots, scope checks, source-bound continuation, newly attached descendants, the 1,024/1,025 visited-item boundary, and lossless byte-bounded pages over summaries whose narratives are 64 KiB. Large read-bound fixtures populate repositories directly; ordinary topology/readiness/snapshot scenarios use the application service's normal mutations.

The final client checks compare HTTP/MCP pages, deny a foreign-project root and resume CLI pagination with the same roots in a different order. Fast verification predates those fixture additions; contracts, PostgreSQL and access evidence include them. Runtime/test source stayed unchanged throughout these final gates.

The actual graph workload reads one selected task and its prerequisite context at each unrelated-project size:

| Items/project | Planned statements | Scan visits | Shared buffers | Response bytes | Observed ms |
| --- | --- | --- | --- | --- | --- |
| 100 | 6 | 402 | 17 | 1,384 | 36.9 |
| 10,000 | 6 | 5 | 18 | 1,388 | 14.7 |
| 100,000 | 6 | 5 | 20 | 1,389 | 15.4 |

Small-table edge scans explain the first row; larger tables use both endpoint indexes. The gate rejects full-body item reads and enforces statement, scan-visit and response-size budgets. These are sampled local measurements under `auto_explain`, not a latency guarantee or a large-closure benchmark. The shared 1,024-item scenario establishes traversal correctness/bounds separately. Measurement definitions and the calibrated small-table allowance follow [query access evidence](m3-query-access.md).

Astra independently approved the increment after inspecting source/manifests and all final gates, with no unresolved blocking/major findings. This is R18 increment approval, not full M3 approval. Browser workset controls, whole-subgraph termination, extended claims and integration remain later work.

Reproduce sequentially:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
```
