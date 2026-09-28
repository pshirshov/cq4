# Human evaluation corrections

The user's reports are recorded in the running CQ project's Defects ledger, project `20eb436e-1a4d-4bb6-a4b4-d151e5c1dc04` (`cq4`). Readback confirms the records. Requests, readback and retained receipt provenance: `/srv/nvme/tmp/cq4-human-evaluation-20260928`.

| Defect | Scope | State |
| --- | --- | --- |
| D1 | Append results while scrolling instead of First/Next page controls | Resolved; bounded scroll/native checks and operator delivery verified |
| D2 | Top-right connection indicator | Resolved; laptop/narrow placement checks and actual host placement verified |
| D3 | Hover connection diagnostics with keyboard access | Resolved; hover/focus native checks and actual host hover verified |
| D4 | Live project creation/rename in selectors and unified scoped subscriptions | Resolved; dual-service, protocol, native browser and installed Watch/Updated verification pass |
| D5 | `cq init` defaults to `CQ_ORIGIN` | Resolved; JVM/native CLI checks pass and matching executable is installed |
| D6 | Automatic open usage views; remove manual Refresh usage | Resolved; live audit/attempt/outcome/cost native checks pass; installed controls verified |
| D7 | Content switching persists fields from the preceding type | Resolved; both reported failures reproduced, all 182 transitions pass, installed draft checks pass |
| D8 | Partial ledger query offers contextual hints before errors | Resolved; focused/native query checks and actual host completion verified |
| D9 | Remote-HTTP UUID login failure | Resolved; `39f4d29`, retained native/browser evidence |
| D10 | Laptop control density | Resolved; `b551e0b`, both laptop viewport checks pass |
| D11 | Detached database inherits launcher lock | Resolved; `e7a4226`, before/after and host recovery evidence |
| D12 | Repeated interrupts abort cleanup | Resolved; `1064a10`, double-interrupt and closed-pipeline regressions pass |

The later sign-in report was a wrong hostname; the user confirmed login works at the configured origin. It required no application correction. Overall M2/M6 human acceptance remains pending.

## Execution and verification scope

1. Reproduce the new interaction failures on the existing package, then correct scrolling, header/hover behavior, ledger switching and query hints. Verify the affected browser behavior and existing frontend corpus.
2. Add one replaceable, explicitly scoped WebSocket watch covering independent catalogue/item/usage revisions. Catalogue pages need bounded snapshot consistency; reconnect and delayed replies must preserve scope and selection. Add the `CQ_ORIGIN` fallback with explicit endpoint precedence. Verify affected repository contracts against dummy/PostgreSQL, CLI behavior and actual browser/protocol interactions.
3. Preserve the single mutable `0.1.0` model. A catalogue clock requires editing the current schema; any existing-database transformation must be explicit, checksum-guarded and preserve operator data. Rebuild the native asset bundle and exercise the affected installed behavior. Do not rerun unrelated backend suites or the paid harness matrix.
4. Keep defect status Open until the corresponding behavior is verified. Record resolution evidence, exact delivery identity and independent scoped review. Unverified source edits do not constitute a delivered fix.

Astra's read-only design review recommended independent durable counters, one watch generation, cursor-bearing bounded snapshots and coalesced refresh. The Task→Idea input-before-change event ordering and premature query diagnostic hypotheses were subsequently reproduced; before/after evidence follows.

## Verified frontend increment

D1, D2, D3, D7 and D8 pass the affected Chromium corpus and TypeScript checks at `/srv/nvme/tmp/cq4-scroll-ui-20260928/20260928T152521-ui`. Astra independently approved the exact source/fixture hashes. Subsequent operator delivery is verified below; the CQ records are now Resolved.

- Before-fix evidence: `before-layout` captures original pagination/placement; `before-interactions` reproduces hover failure, the exact Idea/Ready decoder exception and the premature `ledger:t` diagnostic.
- Results append in bounded batches while scrolling. Live refresh retains the loaded range, keyboard focus and scroll position; a stale continuation starts a fresh snapshot. Header placement passes at 1280px and 320px.
- Diagnostics open on hover or keyboard focus, allow pointer transfer, and close with Escape. Touch opening is source-reviewed, without a dedicated touch-device run.
- ADT branch selection now synchronizes before a bubbling input event can persist the form. The encoded tag and branch fields always belong to the same selection. Idea drafts persist and restore with `Proposed` status.
- Completion suggestions take precedence when their replacement covers a nonempty diagnostic span. Submitted invalid queries still produce errors. The first UI run exposed an overbroad rule hiding the trailing `alpha AND` error; the narrowed rule passes the existing query corpus.

`before-cli` reproduces first initialization failing with CQ_ORIGIN alone. `before-live` reproduces missing external project creation and a stale open audit despite a live summary. Their corrections now pass the evidence below.

## Unified live updates and native candidate

Evidence root: `/srv/nvme/tmp/cq4-scroll-ui-20260928`.

- **`20260928T154734-live` passes**: the catalogue contract separately against dummy and PostgreSQL (one scenario each); the authenticated application contract separately against both implementations (three scenarios each); actual HTTP/MCP/WebSocket transport; indexed watch polling; and the complete affected browser corpus.
- **`live-wire-current` passes**: Scala export, TypeScript roundtrip and Scala verification of the current `0.1.0` wire model, including 64-bit usage cursors. No version bump or compatibility layer.
- The replaceable `Watch` scope covers independent catalogue, item and usage counters through `Updated`. Reconnect and delayed replies retain subscription/selection ownership. Catalogue pages pair a durable counter with a repeatable-read snapshot; creation and renaming advance the counter transactionally. Stable watches poll bounded indexed reads and send no redundant update frames.
- Open audit, attempt, outcome and cost views refresh automatically, independent of whether the current item query is valid. Checks cover external project creation/rename, delayed catalogue replies, reconnect, replacement watches, project/view changes with in-flight replies, coalescing, and corrections to already displayed costs. The manual Refresh usage button is removed.
- **`20260928T155140-native` passes**: a fresh native executable, actual native HTTP/MCP/WebSocket and CLI checks, and focused catalogue/usage/scroll/interaction browser checks. Verified prior tracing snapshots are reused for unchanged paths; fresh scoped JVM tracing covers changed paths. `trace-provenance.json` records the exact source delta and input hashes. No full backend or paid harness matrix was run.
- Package **`.local/release-candidate`** was built and hash-verified by `dev/package`, then installed as **`.local/release`** by the operator update. Executable SHA-256: `10e1befb1d48038dc7e002095f24a0dca0ffc406392b6fe3166179380d794619`; manifest SHA-256: `efd72ac4d62a4c54fccf320a638da64965224bac385009bd0cac6d0a002bbdd8`. Astra approves the scoped source/JVM increment and independently approves the native/package candidate and transformation proof, without claiming operator delivery.
- **`before-all-branches`** reproduces the additional exact `Unknown DefectStatus variant: Proposed` report. **`packaged-all-branches` passes all 182 content-type transitions** on the packaged binary, asserting persisted branch/status consistency and absence of draft decoder errors. This extends D7 rather than filing a duplicate defect.

The first scoped test attempts exposed fixture problems: an evidence helper omitted its required root argument; distage discovery caused unrelated suites to interfere with an exact global counter assertion; and a pre-existing browser assertion expected a stale audit even after it became automatically current. Those failures remain retained. The catalogue test now has an isolated testkit environment; dummy and PostgreSQL commands are separately selected and counted. The browser assertion now checks automatic current data. The first wire driver used an incorrect bundle output directory; the corrected current-wire run passes.

## Existing operator data and installation

The current schema gains a catalogue clock. CQ's existing checksum validation is unchanged. This development phase keeps one mutable schema; the application does not contain an upgrade path.

The explicitly guarded one-time operator transformation is retained as `transform-catalogue.sql` beside the evidence. **`transform-check` passes**: wrong old checksum is rejected, all existing table data is unchanged, and the retained item is identical under the new application. **`update-check` passes**: active-launcher/PID-file/changed-package rejection; settled backup; exact transformation; old-package retention; new-package relaunch through the permanent helper; retained project/item reads; and actual backup restoration into a separate database with an identical item under the old binary.

The updater uses an ephemeral loopback maintenance port while the normal launcher is stopped, holds the existing launcher lock and stops its owned database before changing package directories. It preserves the old package and a custom-format PostgreSQL backup. The user ran the host update and confirmed readiness. `operator-delivery/receipt.json` records the exact installed manifest, unchanged existing table data and the settled backup SHA-256. The host-run step was necessary because the launcher and PostgreSQL are outside the agent sandbox's PID namespace. Reload any browser tab opened before the update once because the development wire model changed in place.

Astra independently approves the exact tested updater and the additional 182-transition evidence. The one-time host entrypoint is `/tmp/exchange/cq-update-live.sh`; it checks the updater/SQL/configuration hashes, performs the update, then invokes the existing permanent `run-local.sh`. The user completed it after stopping the old launcher. Its output at `/tmp/exchange/cq-update-live.out` confirms all script hashes and `CQ ready: http://vm.home.7mind.io:8080`. The retained receipt and backup are under `operator-delivery` in the evidence root. This one-time update is complete; subsequent starts use `./run-local.sh` as before.

## Actual operator delivery verification

**`operator-verification/result.json` passes** against `http://vm.home.7mind.io:8080`:

- Installed manifest/executable hashes match the approved package; served JavaScript/CSS hashes match the verified build assets.
- Fresh plain-HTTP browser login succeeds, the existing `cq4` project and D1–D8 records are retained, and the current scoped `Watch`/`Updated` frames flow.
- Top-right placement, hover diagnostics, `ledger:t` completion, valid Idea/Defect/Task browser-local drafts, and removal of manual item-page/usage-refresh controls pass. The check creates no test project or server-side item.
- After those checks, one revision-checked change resolves D1–D8 with model-declared verification evidence and source citations. The already-open browser observes D1 becoming Resolved without reload. Exact request, acknowledgement and all eight record readbacks are retained in the same directory.

Astra independently approves the operator delivery and scoped D1–D8 closure after inspecting installed identities, updater receipts and actual-host browser evidence. All eight CQ records are at revision 2. The queued redesign and overall human acceptance remain open.

The actual backup is `operator-delivery/before.dump`, SHA-256 `d27b121d32adb3018d575b67c6c204161fe33bdb2693b01ead3d3507a84297f2`. The old package remains at `.local/release-before-live-update`; its database format is the pre-update format preserved in that backup. No rollback was performed.

## Additional reports

- **D25**: [legacy dependency lazy-value warning](scala-lazyvals-warning.md); investigated, still Open.
- **I1**: an attachable interactive CQ host for yolo use; Proposed for design discussion. The existing [launcher analysis](../design/agent-protocol.md#5-why-cq-run-codex--exists) distinguishes execution ownership from the current batch Governor wrapper.
- D13–D24 are archived duplicate filings. Re-running the original filing script with a different actor session did not reuse its request identity; canonical D1–D12 remain unchanged. Exact duplicate/archive receipts and readback are retained in the human-evaluation evidence directory.
- **D26–D40 and I2**: the [new UI/CLI redesign intake](../design/evaluation-follow-up.md) maps every new report to its durable CQ record. These remain user-reported, awaiting independent reproduction and implementation. The user does not accept the current UI as a viable release interface; technical test results do not substitute for that verdict.

D5 source verification passes in `after-cli` beside the reproductions: first init with CQ_ORIGIN, explicit endpoint precedence, saved endpoint precedence, legacy CQ_ENDPOINT-only use, conflicting environment variables, concurrent init and common-Git-directory behavior. The same checks pass against the native executable now installed.
