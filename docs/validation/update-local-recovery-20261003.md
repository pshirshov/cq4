# Local updater UI failure, 2026-10-03

The operator's update attempt at source `a849e3a086ead3e2acd7df6a962c266014612732` stopped in the UI gate before installation. The failed receipt is `/srv/nvme/tmp/cq4-playground/updates/20261003T190701-af8375f9/receipt.json`. Its `oldManifest` equals the observed SHA-256 of the installed `.local/release/manifest.json`, `3994687fa831f6a321d495edd747e654ed03690b411714093f2567a6d7c171b2`. The dirty-tree warning did not cause the failed density assertion. No replacement package was installed in that attempt.

Evidence root for this correction: `/srv/nvme/tmp/cq4-navigation-evidence`.

## Navigation density

The operator's actual browser component failed with Upstream at y707–735, height 28, outside the 1280×720 viewport. The standalone fixture mounts the actual App and stylesheet in Chromium, with a controlled hello response and rejected WebSocket upgrades. It makes no database or provider calls. Its original assertion failed with the same coordinates before the stylesheet changed (`density-before.log`). The strengthened pane-bound assertion also failed at 1366×768: the last button extended below the navigation pane's bottom at y727 (`pane-before.log`). This catches footer clipping that the viewport-only assertion missed.

Reducing only navigation-entry vertical padding from 5px to 3px preserves the existing 24px minimum control height. All 21 controls fit: the last button is y630–654 at both sizes, inside the pane ending at y727 or y679 respectively. No controls are hidden or omitted. The standalone check passes both viewports (`density-after.log`, `after/density-results.json`), and its 1280×720 screenshot was inspected. The shared density check now asserts pane bounds and minimum height as well as viewport bounds and absence of horizontal overflow. The early standalone fixture is registered in the existing UI check; the actual-server density check remains in place.

## Live usage fixture expectation

After the density correction, the original source-server browser component reached a second failure: `usageLiveChecks` timed out waiting for `Superseded` on a routine heartbeat from the unchanged healthy old connection. The captured events show the replacement remaining pending, then reaching its heartbeat deadline (`usage-before/browser-regression.log`, `usage-before/usage-live-results.json`). That assertion contradicts the documented D129 correction; it is not evidence that D129 should be reversed.

The fixture now controls both overlapping Pong deliveries. It first delivers the old socket's routine Pong and asserts that the verified old socket and unverified candidate both remain, that usage continues through the unchanged old watch, and that the candidate has no watch. It then delivers that same candidate's Pong and verifies one fresh watch, continued usage updates, and the retained invalid search state. Coalescing and query-independence assertions remain.

With only the D129 production guard temporarily reverted in this same worktree, the revised fixture failed for the expected reason: `A healthy heartbeat must preserve the unverified replacement`, actual connection count 1, expected 2 (`usage-regression-controlled/usage-regression.log`). The captured events contain `Superseded`. The production guard was restored exactly; `web/src/connection.ts` has no final diff. An earlier focused run with the corrected behavior passed all four usage cases (`usage-after/usage-regression.log`).

## Verification and delivery

The final original `dev/browser.mjs` component passes against the source JVM and disposable PostgreSQL (`browser/browser-regression.log`, exit 0). This includes both density sizes, all four revised live-usage cases, the six driver/workset cases, all 182 content-type transitions, question/live-resync checks and final heartbeat/recovery scenarios. Captured browser fixture failures, browser page errors and connection page errors are empty. The actual-server 1280×720 screenshot was inspected. `npm run check`, Node syntax checks, Python compilation of `dev/check`, and `git diff --check` passed. No configured or delivery gate, native package build, updater installation, model/schema edit, version bump, or playground database filesystem/SQL operation was performed by the implementing agent. Source-server checks use owned disposable PostgreSQL clusters and stop them afterward.

The live ledger API returned Connection refused while the launcher was stopped. Two Open Defect intake mutations are captured in [pending intake](update-local-20261003-pending-intake.json) for replay once the operator restarts the server; no live Defect IDs or status changes are claimed. Source corrections do not establish installed verification. After the correction is merged, the operator reruns `./update-local.sh`, then starts `./run-local.sh` and reloads the browser after successful installation.
