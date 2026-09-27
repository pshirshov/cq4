# M2 governing supervisor role

Scope: one batch governor under the common distage role entrypoint, local job lifecycle, bounded native result parsing, multipart evidence and replayable operational-audit delivery. Child control/dispatch and the full M2 usable slice remain pending.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence | Observation |
| --- | --- |
| `20260926T235322-fast` | PASS, 61 Scala scenarios plus Pi HTTP bridge fixture; includes immutable delivery replay after a lost acknowledgement, retained receipts, raw-byte multipart reconstruction and native result validation |
| `20260926T235450-postgres` | 30 PostgreSQL scenarios and existing HTTP/MCP/WS/role/CLI/deadline checks pass; the new supervisor fixture then stops on its Python syntax error before executing the role. Overall run failed; restart step was not reached |
| `20260926T235910-supervisor-role` | PASS through a fresh real HTTP/PostgreSQL service: shorthand/native supervisor invocation, role graph isolation, isolated governor, publication, audit and malformed-result rejection |
| `20260927T000029-supervisor-role` | Reproduces acceptance of a configured startup budget exceeding the scoped credential lifetime; earlier scenarios, including lost-acknowledgement replay, pass |
| `20260927T000144-supervisor-role` | PASS after combined deadline-budget validation and matching grant duration |
| `20260927T000335-contracts` | PASS deterministic single-version generation, TypeScript checks, generated schemas and cross-language codecs |
| `20260927T001108-fast` | PASS final 62 Scala scenarios plus Pi bridge, including the new constrained-heap parser regression |
| `20260927T001209-native-result-replay` | PASS 64 MiB native-event rejection and production-parser replay of all six retained Claude/Codex/Pi worker/reviewer outputs |
| `20260927T001950-supervisor-role` | Reproduces both Astra findings: valid native output followed by a deadline and zero exit is admitted; killing the guardian yields an `Uncertain` receipt but zero unknown audit outcomes |
| `20260927T002125-supervisor-role` | PASS after shared job-outcome classification: deadline output is rejected with its stop reason, and guardian loss increments unknown audit coverage |
| `20260927T002231-fast` | PASS 63 Scala scenarios plus Pi bridge, including the shared classifier across every stop reason, invalid results and uncertain cleanup |

The new supervisor fixture uses a deterministic executable emitting the already-verified Codex event format. It is **not a model evaluation**. The child checks the absence of the host root/database environment, explicit model selection and read-only governor profile. The test process runs with server/database settings removed while talking to an independently running CQ server. Successful execution proves that selecting the supervisor did not acquire those local server resources.

The fixture confirms a valid result artifact and exactly 131 fixture tokens in the operational usage log. It removes a private delivery acknowledgement, replays the batch through a freshly granted collector, and requires identical totals and audit cursor. A second run exits its process normally but returns invalid structured content: CQ retains usage, publishes no valid result handle and exits the supervisor command unsuccessfully. This distinguishes process completion from result validity.

Two fixture defects were corrected before those passes: a mismatched Python brace (`235450-postgres`) and a hand-written `SessionOnly` filter using `session` instead of its actual generated field `id` (`235759-supervisor-role`). The latter occurred after successful supervisor launch/publication. Neither was a product role failure.

The deadline reproduction used a 24-hour startup limit with additional execution/cleanup time; the command incorrectly succeeded. The role now validates startup + execution + grace + kill + delivery margin together and grants that duration. It rejects an impossible budget before starting a governing run.

`native-output-bounds/before.log` reproduced heap exhaustion from splitting a 4 MiB newline-only output before checking event count. The parser now checks line/event limits while scanning and retains only the required terminal/assistant event. The exact 64 MiB JVM reproduction passes in the final replay evidence above and is a permanent fast regression. The real-output replay makes no new model calls.

Astra requested corrections to outcome classification at `d89fc70`. Both source-derived predictions reproduced in the actual role fixture before correction. The shared classifier now distinguishes normal exit, cancellation, known failure and unconfirmed termination. The role refuses result admission after a deadline even if the harness handles SIGTERM by exiting zero. Guardian loss retains `Unknown` through publication instead of reporting a known failure. Astra independently approved `cc04d7f` and the batch-supervisor increment after inspecting both passing evidence sets; no blocking or major findings remain in this scope. This is not M2 acceptance. [Run instructions and explicit remaining limits](../design/supervisor-role.md).
