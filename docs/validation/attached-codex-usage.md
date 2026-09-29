# Attached Codex accounting — D42

The native MCP request supplies the exact thread UUID. CQ binds that identity to
one canonical local rollout and records per-response `token_usage_record` events
in the operational usage audit. The observer reads incrementally, with bounded
discovery, file/line sizes and sample inventories. It never exports prompt text.

## Evidence

Root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.

- `codex-before`: the completed human trial has 25 native response records and
  zero outer-attempt CQ observations. Its native header reports Codex 0.157.1.
- `codex-probe`: actual Codex 0.156.1 supplies `threadId` and
  `x-codex-turn-metadata` on MCP calls. Its MCP process environment contains no
  native thread identity. The parser supports the two observed versions.
- `codex-ownership-before.log`: durable-claim loss and conflicting duplicate
  counters both fail before correction. Global ownership now retains canonical
  response evidence atomically; abrupt recovery replays it without rereading
  potentially resumed native work.
- `codex-clock-before.log`: a shutdown clock rollback invalidated a retained
  sample. The final window now bounds new discovery only. Existing durable
  attribution survives rollback.
- `codex-tests-final.log`: ten focused scenarios pass, using a manual source
  implementation and real incremental filesystem input. They cover overlap,
  replay after acknowledgement loss, reconnect, delayed writes, clock rollback,
  duplicates, foreign threads, invalid counters and file identity changes.
- `codex-http` and `codex-http-corrected`: actual host checks exposed monitor
  cancellation and an oversized audit-gap message. The monitor has an explicit
  interruptible lifetime; publication uses separate bounded gap strings.
- `codex-native-jvm`: exploratory real Codex → Pi consumer completed its child
  investigation and reconciled 14 retained outer responses / 562,506 tokens.
  The native log had 15 responses; teardown left no frozen end window. The
  retained subset is explicitly partial. Recovery then replayed zero batches.
  Source changed during this exploratory run; it is not final artifact evidence.

- `codex-http-final`: actual MCP/HTTP/PostgreSQL accounting now passes. Duplicate
  records contribute 130 inclusive tokens once; a lost acknowledgement replays
  once, then zero, with identical audit summary. Pi accounting, child cancellation
  and blocked-I/O shutdown deadlines also pass.

Astra approved the source corrections and the initial ten focused scenarios.
The contract round trip passes at `20260928T231707-contracts`. Subsequent
boundary and final package checks are recorded below.

The final boundary check reproduced a missing-directory failure for ephemeral
Codex homes (`codex-ephemeral-before.log`). An unbound observer now reports
unavailable usage and permits CQ operations; an existing binding still enforces
its identity. All eleven local scenarios pass in `codex-ephemeral-corrected.log`,
and Astra approves this correction. The MCP fixture checks the actual Context
response for a fresh native home. The preliminary `20260928T231858-native` run
was deliberately interrupted for this correction; it remains failed with a
separate interruption record and is excluded from delivery evidence.

Final delivery checks now pass: `20260928T235722-native`, the source-isolated
`20260929T000819-installed`, and `codex-packaged-final` on their exact executable.
The real Codex → Pi investigation completed; 14 retained outer responses / 544,669
tokens reconcile against 15 native responses. Native teardown left no frozen
window; one pending delivery batch replays, then repeated recovery replays zero.
The missing final response is an explicit coverage gap. Astra approves technical
delivery; operator installation and actual-hostname verification pass. D42 is
Resolved with ModelDeclared evidence; human acceptance remains pending. [Aggregate evidence](remaining-defects.md).

## Accounting boundary

- Native response IDs deduplicate contributions. Thread/turn/model/provider and
  counters must agree on repeats, including repeats across CQ host restarts.
- One host holds native-thread ownership at a time. Durable global response
  claims under `$CODEX_HOME/cq-usage` prevent reconnect/clock-overlap double
  counting and contain enough evidence to repair interrupted local publication.
- CQ counts inclusive input/output exactly once; cache and reasoning subsets
  remain separate. Cumulative turn/thread totals are excluded.
- The outer attempt remains session-unattributed and its model grouping remains
  unknown. Per-response artifacts/source labels retain observed model/provider.
  Managed children retain their existing task/cohort accounting.
- Cost and auxiliary, compaction, unreported and final-tail coverage remain
  unknown. No complete-spending claim is made. Ephemeral sessions have no
  retained native rollout; unsupported versions/identities produce explicit gaps.
- Normal shutdown freezes a half-open discovery window. After abrupt shutdown
  with no frozen end, recovery replays durable samples only. It does not import
  later work from a resumed native thread.

Custom `CODEX_HOME` is forwarded by newly generated Codex MCP configuration. The
directory must be readable, with its `cq-usage` journal writable. In yolo, forward
that environment variable and bind the directory when using a custom location.
Run `cq job upload --session PATH` after the native owner has stopped to recover
retained delivery. Reconfiguration is required to update older generated MCP
environment forwarding.
