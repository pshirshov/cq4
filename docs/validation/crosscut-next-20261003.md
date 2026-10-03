# Continued cross-cut, 2026-10-03

D129 has a reproduced connection correction and focused passing source evidence. It accounts for a controlled variant of D70, not a confirmed reconstruction of the operator's original circumstances. I26 now has private PostgreSQL measurements; no database configuration is changed. I30's authority choice is recorded as Q50 and remains unanswered. Product delivery is still pending.

## D129: routine heartbeat cancels resume replacement

Evidence root: `/srv/nvme/tmp/cq4-crosscut4-evidence`.

Before editing production, `node dev/connection-model.mjs` failed the added case with `A routine heartbeat must not discard the pending replacement`, actual connection count **1**, expected **2** (`connection-before.log`). A Pong on the unchanged ALIVE connection closed the NEW replacement started by resume. No activation callback ran, so no new snapshot or subscriptions were established.

The actual App, source JVM and disposable PostgreSQL also reproduced stale main-pane state. The fixture withheld one Updated notification after answering a selected Question, then dispatched resume. Its item pane and result row stayed Open while the server was Answered and the footer said Data: current. Frame capture retained only the old socket's Watches. The timeout and DOM/frame capture are in `browser-focused.log` and `d70/question-live-failure.json`; `d70/question-live-failure.png` was inspected. Two other orderings passed before the fix: answering while an older detail reply was held, and connection replacement during a held read.

`ConnectionManager.promote` now closes other connections only when the active connection changes or recovers. A routine heartbeat keeps the pending replacement. Its verified Pong promotes it, closes the old connection and invokes the existing activation callback, which refreshes the browse results and selected detail. Existing old-socket recovery behavior and connection bounds remain covered.

Focused verification:

- Controlled connection model: **11 passed**, including the new ordering (`connection-after.log`).
- Actual Chromium/native WebSockets: **3 passed**, covering handshake blackhole, short/long event-loop pauses and teardown (`connection-native.log`).
- Actual App/server/private-database fixture: **3 passed, zero page errors** (`d70/question-live-results.json`, `browser-after.log`, final `browser-final.log`). The final case also waits for Data: current after refreshing. The Answered pane/row screenshot was inspected; the held-read scenario can surface the existing connection-replacement cancellation notification.
- `npm run check` passed (`typescript.log`); `npm run build` passed (`browser-build.log`). Python and Node syntax checks and `git diff --check` passed.

The browser fixture is registered in the existing browser suite, including the host's UI check. No configured or delivery gate was run by the agent. D129 stays Open until delivered and verified on the installed release. D70 remains Open because its original trigger is unknown. Neither the withheld notification nor the controlled resume ordering establishes what happened in the operator's original session.

## I26: durability measurements

Two disposable PostgreSQL 18 profiles were compared: stock durability (`fsync`, `synchronous_commit` and `full_page_writes` on) versus all three off. Each profile used its own freshly initialized private cluster; every cluster was stopped afterward. The playground database and local launcher were untouched.

| Measurement | Durable median | Disposable profile median |
| --- | ---: | ---: |
| 400 sequential BEGIN/UPDATE/COMMIT transactions, three samples | 0.765931 s | 0.064526 s |
| One transaction in that sample | 1.914828 ms | 0.161314 ms |
| LedgerContractPostgres suite duration, three alternating samples, 25 tests each | 8.905 s | 8.025 s |
| Whole sbt invocation for that suite | 25.809829 s | 20.485128 s |

The isolated commit measurement is about 11.87 times faster with durability disabled. The real ledger suite's median duration falls about **9.9%**, and every invocation reports **25 passed**. Six focused invocations executed 150 tests. Warmup, invocation overhead and host variance affect these small samples; these figures do not predict full gate duration or establish a production tuning preset. Scripts, settings readbacks and raw results are retained under `i26/`, `i26-contract/`, `postgres-measure.py` and `postgres-contract-measure.py` in the evidence root.

The measured defaults are `max_connections=100`, `random_page_cost=4`, shared buffers 128 MiB and effective cache estimate 4 GiB. No connection exhaustion occurred with exact suite selection. No live query plans, index selectivity, concurrent workload, cache residency or memory-pressure measurements were made.

PostgreSQL documents disabling fsync/full-page writes as sacrificing crash safety; any such preset must be restricted to recreatable functional-test clusters. Restart/restore evidence must retain the durability settings whose behavior it claims to verify. [PostgreSQL 18 WAL configuration](https://www.postgresql.org/docs/18/runtime-config-wal.html).

Planner costs describe a workload average, and effective_cache_size is a planner estimate rather than a memory allocation. Commit timings cannot justify setting random_page_cost to 1.1 or sizing buffers for the local launcher. Measure representative plans and workload before choosing those settings. [PostgreSQL 18 planner configuration](https://www.postgresql.org/docs/18/runtime-config-query.html).

Recommendation: retain local durability and current defaults pending those measurements. If I26 advances to implementation, separate disposable functional checks from durable restart/restore checks before introducing an explicit nondurable test preset; do not change `Checks.database` globally. I26 remains Proposed.

## Remaining work and delivery

Q50 asks whether I30 relaxes planning/phase sequencing while retaining isolated Workers, independent review and host validation, or also authorizes direct governing implementation. Mandatory ledger maintenance applies to both. Implementation depends on that answer; no new authority rule is inferred from elapsed time.

D124's original intermittent failure remains unexplained; its lost assertion reporting was corrected in the preceding batch. I17 still awaits configuration comments, I27 depends on it, and I12 remains evaluation planning without authorization for new paid runs. I28 remains proposed; this batch does not implement declarative modules or doctor.

No schema/model edits, version bump, native build, updater or live database filesystem/SQL operation was performed. The operator runs configured and delivery gates. After a validated candidate, park/reconcile prior drives, stop the launcher, run `./update-local.sh`, restart using `./run-local.sh`, and reload the browser. Restarting alone continues to serve the existing package.
