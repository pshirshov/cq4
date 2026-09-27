# M2 local dispatch increment

Scope: local parent/child MCP, installed instructions, isolated worker candidate capture, configured host validation, reviewer chaining by handle, compact status, parent-linked audit, delivery replay and bounded governing shutdown. This is not the complete M2 consumer slice.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence | Observation |
| --- | --- |
| `dispatch-list-codegen/before.log`, `after.log` | Reproduced generated Scala `List` name shadowing; renamed the new workspace command to `Entries`, then generation/compilation passed |
| `20260927T010734-dispatch-role` | Fixture setup failed because browser assets had not been rebuilt after generation; no dispatch scenario ran |
| `20260927T011248-dispatch-role` | Browser build reproduced generated TypeScript strict-mode rejection of `ValidationCheck.arguments`; changed the field to `command` |
| `20260927T011341-dispatch-role` | PASS actual distage supervisor, HTTP/MCP and PostgreSQL: idempotent start, candidate/check execution, handle-only reviewer, permissions, cancellation, child usage and lost-ack replay |
| `20260927T011521-fast` | PASS 68 Scala scenarios plus Pi bridge; includes real filesystem read boundaries and compact Unicode/cohort projections |
| `20260927T011850-dispatch-shutdown` | Reproduced an indefinitely waiting role with child ticket `fsync` held: still alive after the fixture's 25-second shutdown allowance |
| `20260927T012112-dispatch-shutdown` | PASS stalled ticket persistence, stalled child job preparation and stalled governor-exit persistence: exit 75, no result admission or late continuation, owned guardian/harness processes drained; normal owner exit cancels and accounts for a running child |
| `20260927T012313-contracts` | PASS deterministic generation, Scala/TypeScript compilation, cross-language JSON/UEBA and 282 schema definitions/five domain MCP capabilities |
| `20260927T012409-postgres` | PASS 31 PostgreSQL scenarios, real HTTP/MCP/WS, role/CLI/deadline/supervisor/dispatch/shutdown fixtures and whole-server SIGKILL/restart |

The deterministic harness fixture runs real native processes and real application transports; it does not call a model or establish harness consumer quality. Its worker emits a large narrative containing a sentinel; the sentinel never reaches the governor's native output. The largest structured dispatch reply was 594 bytes. That measurement covers `DispatchReply`, not the full MCP envelope or model tokens. The reviewer reads the captured candidate and receives prior validation through the immutable result handle. The governing checkout remains untouched.

The four attempts (governor plus worker, reviewer and cancelled worker) retain three parent links and no running audit attempts. Replaying a child batch after deleting its acknowledgement preserves the exact usage summary and audit cursor. Fixture token counts are synthetic and are not efficiency evidence.

The shutdown reproduction interposes `fsync` only in the tested supervisor process, using an explicit release latch. No production test hook is required. Corrected full-process elapsed times were 16.044 seconds for ticket persistence, 20.450 seconds for child input persistence, 15.242 seconds for governor exit persistence, and 4.972 seconds for normal shutdown. These include role startup and the governing fixture's work; they are not measurements of the watchdog interval alone. Held writes are released only after the supervisor exits. Forced cases produce no successful session/child receipt. The corpus also waits for owned guardian/harness commands to disappear and checks for late child publication. It does not equate exit 75 with confirmed durable quarantine or delivered telemetry.

Astra first required correction of the unbounded child join. It then inspected the source and failing/passing shutdown evidence and approved the local-dispatch increment with no remaining blocking or major findings, subject to completing the contracts/PostgreSQL gates. This review explicitly excludes M2 acceptance, candidate integration, pre-spool recovery and real consumer evaluations. Both contract and full PostgreSQL gates have passed.

## Reproduce

From the CQ checkout:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check postgres
```

The PostgreSQL corpus includes `dev/dispatch-check.py` and `dev/dispatch-shutdown-check.py`. Linux, the pinned Nix environment and a compiler are required. The latter compiles a fixture-only shared library to interpose stalled writes; the native release must verify this process-exit behavior separately in M6.

Run the current artifact using the [supervisor settings and commands](../design/supervisor-role.md); detailed boundaries are in [local dispatch](../design/local-dispatch.md). Remaining M2 work includes pre-spool reconciliation, actual Claude/Codex/Pi consumer evaluations and the designated human checkpoint. Branch integration and the full later process/UI/native release remain required by the goal.
