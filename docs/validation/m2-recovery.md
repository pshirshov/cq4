# M2 interrupted-session reconciliation

Baseline: `c7a9c73`. Evidence root: `/srv/nvme/tmp/cq4-implementation/`. The final fast and PostgreSQL/real-process gates pass. Astra independently approved this recovery increment and the M2 technical milestone; human acceptance remains pending.

| Evidence | Observation |
| --- | --- |
| `20260927T031115-fast` | Test setup compilation failed: fixture supplied a list for the generated environment set; corrected before runtime conclusions |
| `20260927T031156-fast`, `recovery-incomplete-ticket/before.log` | Expected failure: a child directory with an uncommitted ticket prevented all recovery; the focused reproduction reports `Host record must be a regular file` |
| `20260927T031405-fast` | PASS 72 Scala scenarios plus Node bridge and evaluator checks after incomplete-ticket inventory/reporting |
| `20260927T031520-postgres` | 32 service scenarios and actual transport, roles, CLI, live-owner denial, supervisor SIGKILL recovery and dispatch pass. Held-ticket recovery then reproduces a second inventory defect: two pending ticket/cancellation files prevent governing publication. Overall gate failed; restart was not reached |
| `20260927T031912-fast` | PASS final 72 Scala scenarios plus Node bridge, five candidate/correction predicates and four suite-runner fixtures after recognizing bounded cancellation records |
| `20260927T032004-postgres` | PASS final 32 service scenarios, HTTP/WS/MCP, artifacts, client/supervisor role isolation, CLI/deadlines, live-owner denial, supervisor SIGKILL recovery, dispatch, all shutdown/recovery modes and server SIGKILL/restart |

`cq job upload` remains a client task under the common distage entrypoint. It acquires the existing session journal's exclusive lock before granting a collector credential and holds ownership throughout reconciliation and replay. It does not acquire server/database resources or restart a model.

The shared scenario executes against the in-memory and PostgreSQL application services, with corresponding dummy/real Git workspace repositories. It covers a governing snapshot containing a valid usage event followed by truncated JSON and non-UTF-8 stderr, a child ticket without a job, an already committed result, and an unrelated unfinished validation workspace. It injects lost acknowledgement after the governing outcome reaches the server. Appending different native output before retry does not change the committed observations or totals. The first snapshot's 131 fixture tokens remain counted once; two unresolved model outcomes remain unknown; the previously committed result stays completed; the unfinished validation job does not become a fabricated model attempt. Quarantine and raw retained bytes are checked.

The actual role fixture refuses upload while a running JVM owns the journal. After SIGKILL, upload captures partial evidence, records unknown coverage, transitions its job to `Uncertain`, quarantines the workspace and admits no result. Repeated upload leaves the audit cursor and totals unchanged. This uses a deterministic executable; it is not a live-model evaluation.

Astra's first inventory finding reproduced before correction. The second reproduced through the existing native `fsync` stall: ticket and cancellation writes both remained pending after supervisor exit 75. Recovery now recognizes at most 32 regular, bounded pending files or an exact cancellation record in an incomplete ticket directory. It preserves and reports that directory, publishes valid identities, and exits nonzero with unresolved paths. Malformed committed tickets and unexpected evidence remain explicit errors.

Run and recovery semantics, including the absence of any process-settlement inference from a journal lock, are documented in [the supervisor design](../design/supervisor-role.md#interrupted-publication). A recovery snapshot cannot establish complete usage coverage; later uncollected bytes remain available locally but are not silently substituted into committed observations.

The final held-write scenarios exit 75 after 15.893 seconds (ticket), 20.299 seconds (input) and 15.092 seconds (governor exit acknowledgement). Ticket recovery acknowledges the governing batch and explicitly returns nonzero for the uncommitted child identity. Input/exit recovery each acknowledges both valid attempts successfully. Every repeated recovery acknowledges zero new batches. Normal governor exit cancels its hierarchy and returns successfully in 4.722 seconds. Astra verified final production-file hashes against the retained source manifests before approval.
