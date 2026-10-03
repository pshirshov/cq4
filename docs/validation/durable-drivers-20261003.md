# D123 source implementation and focused validation, 2026-10-03

D123 implements durable driver records, browser driver/workset controls, and the pinned local-update transition. It remains Open pending operator delivery gates and installed verification. The playground was not modified. I29's updater was already merged at `8013286` (implementation `e0432b9`); this increment extends its transition recipe.

Committed driver records now share the ledger transaction and project lock. Ordinary restart preserves binding, cycle, tokens, consumption, lineage and outstanding integrations. A policy rejection rolls the operation and callbacks back to a savepoint, commits the original driver's Failure stop, then returns the original Denied fault. Ordinary failures roll back driver state as well as ledger state. Narrow, token-free summaries serve browser polling. A durable project driver clock prevents an evicted/recreated key from accepting an old revision.

Archives include driver records but restore them Off with an explicit archive-restored stop, invalidated binding/start/resume tokens, retained lineage and outstanding integration evidence, and fresh revisions. This follows Q46/Q47. Persistence does not establish external child liveness; existing host reconciliation remains required.

The Drivers/worksets dialog lists committed summaries, opens referenced items/worksets, previews frozen cycle snapshots, and parks through a human operation with an expected revision and hold confirmation. Explicit IDs, selected items and submitted queries produce server previews; query resolution uses every page of one snapshot. Exact previews can be stored, and an explicitly labeled workset filter can be cleared. The existing maximum of 64 roots is retained. D127 Markdown rendering is filed only and was not implemented.

The API remains `cq.api 0.1.0`, with the current model/schema edited in place and signature regenerated. The pinned updater accepts the two known previous model fingerprints and their exact schema. It refuses unsettled claims, attempts and integrations, backs up before transformation, verifies old table data is preserved, and restores the old schema/data/package after a candidate-integrity failure. Previous in-memory drivers cannot be recovered after that process exits: park and reconcile them before stopping the old release.

Focused evidence lives under `/srv/nvme/tmp/cq4-d123-evidence`:

- `restart-fail-before.log`: the new restart contract failed before persistence, returning None instead of the committed On record.
- `eviction-fail-before.log`: an evicted/recreated key reused revision 1 before the durable clock correction.
- `postgres-browser/browser-fail-before.log`: the browser fixture failed with the previous UI restored in the same worktree, because the Drivers/worksets control was absent. The new UI was then restored.
- `postgres-browser/driver-dummy.log` and `driver-postgres.log`: **55 passed each**, zero failed, including restart/token/expiry, rollback, durable rejection, callbacks, stale revisions and eviction.
- `postgres-browser/archive-postgres.log`: **5 passed**, zero failed, including actual archive restoration of stopped/token-invalidated records. An initial inventory-order assertion failed; table ordering was corrected before the passing run.
- `integration-focused.log`: **21 IntegrationContractDummy cases passed**, zero failed.
- `updater-focused.log`: **13 Python updater cases passed**, including disposable PostgreSQL transformation and restoration after candidate tampering.
- `browser-final/drivers-browser.json`: **6 browser cases passed**, covering summaries, preview/store/filter/clear, hold-to-park, submitted queries, pagination (63 roots over 40/23 pages), and snapshot drift refusal.
- `browser-final/process-restart.json`: stopping and restarting the actual JVM server over the same disposable PostgreSQL preserved a Binding driver's status exactly. Full On-cycle/token state was verified by the shared repository contracts; this process probe does not establish external harness recovery.
- `browser-final/drivers-worksets.png`: inspected screenshot of the final dialog.
- `pinned-update/result.json`: exact pinned SQL over the previous full schema and a retained project passed both rollback and installation rehearsals. Package files were disposable fixtures, not native release evidence.

TypeScript checking, browser build, shell syntax, Python compilation and whitespace checks passed. No configured fast/UI gate or native/package/process delivery gate was run by the agent. DriverIntegrationProcess requires the operator's fixture binaries and was not run. Full release packaging, actual playground update and hostname verification remain pending; use the operator-run `./update-local.sh` after stopping the launcher.

Pinned fingerprints:

| Artifact | SHA-256 |
| --- | --- |
| Current model | `b74cb9095b127cf383ccb46e79d42a3f70b4497cbcd59a7c9a036733b42f488a` |
| Current schema | `f0aaf1d080e961fdd09b6a098cbcaac3e108afca1af1df2a4515fa03ff924945` |
| Update SQL | `ca5b2203ee0363debf26013a81fa6b226fa438de5ee0220f844836ab58be5676` |
