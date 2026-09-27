# M3 integration reservations

This implements the server reservation and exact domain-recording boundary in the [Git integration design](../design/git-integration.md). The connected Git executor, durable local integration journal, combined-candidate workflow and real two-session acceptance scenario remain open. No branch update runs in these service fixtures.

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T094043-fast` | Existing 112 scenarios pass after model/service implementation and extraction of transaction-local mutation application |
| `20260927T094542-fast` | New fixture failed compilation due to an extra closing parenthesis; no behavior result |
| `20260927T094618-fast` | 118 scenarios pass, including six new shared reservation scenarios |
| `20260927T094750-fast` | Astra's request-identity finding reproduced before correction: an unrelated ordinary create consumed the reserved request ID and allocated T3. 118 other scenarios pass |
| `20260927T095313-fast` | New HTTP fixture failed compilation due to an incorrect generated field name; no behavior result |
| `20260927T095346-fast` | All 119 scenarios pass after the request-identity guard and typed host HTTP method; HTTP fixture compiles but is not executed by this gate |
| `20260927T095501-contracts` | Deterministic generation, Scala/TypeScript round trips, 387 schema definitions and six ordinary MCP capabilities pass |
| `20260927T095549-postgres` | All 72 PostgreSQL service scenarios, actual HTTP/MCP/CLI, typed integration endpoint, supervisor/dispatch, four live admission cases, stalled-I/O shutdown and SIGKILL/restart checks pass |
| `20260927T100214-access` | All 54 sampled operations pass the existing access budgets at 100, 10,000 and 100,000 unrelated items/claims per project, including the added reservation lookups; cross-project lock isolation passes |

## Scope and invariants

The owning session's Collector may reserve an immutable intent only with the full current Governor claim, current member revisions, durably admitted worker and independent reviewer results, an exact accepted candidate and successful applicable validation observations. The server reconstructs the permitted task-completion request and compares it to the frozen request. Existing titles, narratives, labels, acceptance criteria and earlier evidence are preserved; integration evidence is host-observed.

Pending reservations block ordinary changes, production, relationship updates/restoration, claim release/replacement, takeover and termination over the affected items, including operations by the original owner and operations after lease expiry. Claim and termination previews expose the blocking integration IDs. Unrelated item work remains available. An unresolved host can leave affected items pending indefinitely; timeout does not release their reservation.

Recording applies only the frozen domain request, retains its ordinary idempotency acknowledgement, appends history and resolves the reservation in one project transaction. That specific authority survives ordinary lease expiry without renewing the claim or authorizing new work. An exact committed acknowledgement can replay normally. A definitive host observation of non-application resolves the hold without changing items. Actual host settlement enforcement is subsequent work; this service trusts the owning Collector's observation.

The shared dummy/PostgreSQL suite covers competing reservations, replay and altered intent, expiry, release/takeover in both orders, concurrent release versus reservation, role/session/project denial, altered validation and domain requests, rollback of recording, and lost recording acknowledgement. A transaction wrapper injects failure both before commit and after commit to distinguish rollback from lost delivery. Repeated recording retains one completion revision and one acknowledgement.

The final access gate observes 16 SQL statements per measured ordinary mutation at every size. At 10,000/100,000 unrelated items and claims those statements visit eight rows; the largest fixture uses 188–192 shared buffers. Its termination preview uses eight statements/seven row visits and claim preview uses seven statements/seven visits. An unrelated project completes in 28.7 ms while the first project's row lock blocks its own read. These are retained samples, not latency guarantees; the small 100-item fixture uses PostgreSQL's chosen sequential scans within the existing small-table budget.

Astra identified that pending-item checks alone did not reserve the domain request identity: a request containing only `Create` never checks an existing item. The failing reproduction confirmed the resulting allocation. The correction checks the pending integration ID and exact owner before any ordinary mutation/allocation, after the committed-acknowledgement replay branch. The same reproduction now rejects with `IntegrationPending`, leaves the change stream unchanged, allows creation under a fresh request ID and successfully records the original integration.

## Checks

Run sequentially from the repository:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
```

The PostgreSQL gate passes all 72 service scenarios, including the seven new reservation scenarios, and `IntegrationApiCheck` passes through the actual typed host endpoint and ordinary read/change/claim clients. Process/restart and sampled access checks also pass. The access corpus grows unrelated items and claims, not the number of integration reservations; integration-specific scale and the larger affected-closure/usage-ingestion work remain open. Astra independently verified the corrected source, reproduction, all four final gates and runtime/test manifest agreement, and approved this increment with no remaining blocking or major finding. Only documentation changes follow those gates. Neither integration fixture calls a model or executes Git. These checks do not count as additional real Claude/Codex/Pi evaluations or complete R27/M3.
