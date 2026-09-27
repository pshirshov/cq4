# M3 durable result admission

The [admission design](../design/result-admission.md) connects claim validity to durable result acceptance without treating artifact storage as admission. It preserves operational usage independently of the result decision. Git integration remains separate R27 work.

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T060754-claim-admission-repro` | Real PostgreSQL/server/supervisor defect reproduced before correction: release committed before result upload, yet child Completed with a result handle. Usage delivered. Failing standalone script and observation retained |
| `20260927T061732-fast` | Test compilation stopped at an incorrect fixture type name (`CohortId` instead of the existing UUID); no behavior result |
| `20260927T061841-fast` | All 110 scenarios pass with server admission foundation |
| `20260927T090038-fast` | All 110 pass after connecting live publication, shared recovery and prior-result enforcement |
| `20260927T090134-claim-admission-repro` | Unchanged original reproduction passes: released claim produces Failed, no result handle and delivered usage |
| `20260927T090338-fast` | All 111 pass with accepted/rejected lost-admission-ACK and lost-outcome-ACK recovery through `SessionDelivery` |
| `20260927T090444-fast` | All 111 pass with reacquisition after rejection, partial-seal recovery, and absent/rejected/mismatched prior-result checks |
| `20260927T091204-fast` | Mismatch reproduction fails as expected: a sealed Ready result accepted a separately supplied Blocked result artifact. All other 111 scenarios pass |
| `20260927T091728-fast` | All 112 pass after deriving the result artifact from the sealed intent and rejecting caller-supplied result artifacts |
| `20260927T091829-contracts` | Deterministic generation, Scala/TypeScript round trips, 369 schema definitions and six ordinary MCP capabilities pass |
| `20260927T091902-postgres` | All 65 PostgreSQL scenarios, HTTP/MCP/CLI, supervisor/dispatch, four live admission cases, stalled-I/O shutdown/recovery and SIGKILL/restart checks pass |

Shared service scenarios verify concurrent decision replay, complete assignment/role/session checks, no item-history mutation, changed-revision rejection, and decisions stable after later release or reacquisition. Recovery seals token observations before acknowledgement failures, then changes the native file's 100 input tokens to 900: replay still reports the original 131 total tokens and original outcome timestamp, with one outcome and stable receipt.

The earlier `20260927T090627-postgres` gate passed all 65 PostgreSQL scenarios and the first three actual supervisor admission cases. The final `20260927T091902-postgres` gate repeats these after the sealed-result correction: release-before produces Failed/no handle; accepted acknowledgement loss produces pending/no handle then recovers Completed after release; rejected acknowledgement loss stays Failed after reacquisition. Both acknowledgement-loss cases preserve exactly 131 direct task tokens after the native file changes.

The fourth case releases a claim while a Probe child is Running and would otherwise sleep for 60 seconds. It observes Cancelled/Settled, no candidate/result, a quarantined workspace and exactly 131 direct task tokens **20.169 seconds** after release, within its 40-second fixture bound. The guardian reports settled cancellation even though the child handles SIGTERM by exiting zero; that does not become successful work. Evidence is `jvm-admission/running-loss/observed.json` beneath the final PostgreSQL directory.

Claim renewal runs every 20 seconds; each HTTP call has a 10-second deadline. Cancellation then uses the configured process grace/kill bounds. The 40-second check measures this fixture with responsive storage and 300 ms / 2 s grace/kill settings; it is not a universal durable-publication latency guarantee. The separate held-I/O scenarios exercise forced unresolved supervisor exit and subsequent recovery. Server rejection still prevents late result admission independently of process cleanup.

Commands, run sequentially from the repository:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
```

Source hashes are retained with each gate. Only documentation changes follow these final runtime gates. Astra independently verified the final gate outcomes, four live observations and runtime/test manifest agreement, and approved this increment with no remaining blocking/major finding. These deterministic harness fixtures do not count as new real Claude/Codex/Pi consumer evaluations. Native release verification and Git integration remain open.
