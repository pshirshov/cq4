# M3 connected reviewed-candidate integration

This increment connects the existing reservation and Git coordinator to the governing supervisor. All deterministic gates pass. Astra independently approved final source and evidence with no remaining blocking or major finding. It does not complete R27/M3: the connected two-session combination/validation/review scenario remains open.

## Implemented boundary

- The existing governor-only local `dispatch` tool accepts `PrepareIntegration(id, reviewer)`, `Integrate(id)` and `IntegrationStatus(id, waitMillis)`. Children still receive only their workspace capability.
- `integrationTarget` is an explicit optional full branch reference in supervisor settings and governing input. `null` keeps candidate-only operation. Preparation resolves accepted worker/reviewer artifacts behind handles, renews the exact claim, reads exact member revisions and freezes the narrative-preserving completion request. The server independently validates it before reservation.
- Compact status reports only identity, target, expected/candidate commits, member revisions, reviewer handle, phase, next action and a short blocker. Full prompts, reports, drafts and completion requests stay in host/server storage. Preparation and application are asynchronous; status waits are bounded to 20 seconds and the session inventory to 32 operations.
- The integration controller owns admission and shutdown. The existing watchdog bounds shutdown while filesystem or network operations remain unresolved. Whole hierarchy termination is allowed; successful cleanup is never inferred from timeout.
- `cq job upload --session DIRECTORY` holds the existing exclusive job journal while reconciling retained integrations. Its Git adapter cannot launch jobs. It never reserves new work or renews an ordinary claim. Missing or ambiguous evidence remains unresolved. A previously prepared, unattempted operation with no reservation has no effect to replay.

## Review reproductions

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T103907-fast` | Initial implementation: 131 fast scenarios pass |
| `20260927T104027-fast` | Expected failure: 131/132 pass; a retained execution marker with no server reservation was incorrectly classified as preparation only. The correction rejects missing counterpart evidence after execution admission or observation |
| `20260927T104152-process` | Expected failure: 32/33 Scala process scenarios and 16 guardian checks pass; the real admission gate prevented job registration, but the coordinator retained an ambiguous pending reservation. A dedicated rejection raised before registration now seals `NotApplied`; other execution errors remain conservative |
| `20260927T104419-fast` | All 133 fast scenarios pass, including both corrections |
| `20260927T104534-postgres` | All 72 PostgreSQL scenarios, actual HTTP/MCP/CLI/supervisor fixtures, connected integration/SIGKILL recovery, admission, shutdown and server restart checks pass |
| `20260927T105215-process` | All 33 Scala process scenarios and 16 guardian checks pass; shared coordinator scenarios exercise the actual admission gate, Git, journal and guardian |
| `20260927T105247-contracts` | Deterministic generation, Scala/TypeScript round trips, 397 schema definitions and six ordinary MCP capabilities pass |

Both corrections now have shared regression scenarios. Astra inspected the reproductions and approved the corrected source pending final gates. The connected fixture also injects actual supervisor SIGKILL after Git incorporation but before local-observation fsync, checks live-session recovery denial, then requires exact acknowledgement replay with no additional job or ref update. The fixture passes inside `20260927T104534-postgres/jvm-dispatch.log`: maximum integration reply is 594 bytes, the configured target contains the candidate, and the governing HEAD, staged index bytes and unrelated files are unchanged. Repeated prepare/apply returns the same outcome. Child integration calls and recovery while the old supervisor still owns the journal are denied.

The actual SIGKILL case retains its session under `20260927T104534-postgres/jvm-dispatch/sessions/e324d0d5-9d45-4905-bbaa-4a40a6cbf699`. At interruption the branch already contains candidate `d6d847ac5af74f6d929b8b8a1722bc3886f44d29`, the local execution marker is true and the committed observation is absent. Recovery records task revision 2 and returns the same acknowledgement on replay, with zero new Git jobs and zero further reflog changes. The complete PostgreSQL gate passes.

## Verification

Run the gates sequentially:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check process
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
```

The PostgreSQL gate includes real HTTP and supervisor processes using a deterministic harness fixture. This is separate from the three retained real-model consumer evaluations. No new native release or real-model verdict is claimed by this increment.

Astra verified all four final gate outcomes, the retained SIGKILL sequence, compact traffic measurement and runtime/test manifest agreement. Only documentation changed after the passing gates. This approval covers the connected first-candidate path; it does not close R27/M3 or the remaining combination, native and real-model evaluation work.
