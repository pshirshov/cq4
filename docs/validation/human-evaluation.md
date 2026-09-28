# Human evaluation corrections

The user's reports are recorded in the running CQ project's Defects ledger, project `20eb436e-1a4d-4bb6-a4b4-d151e5c1dc04` (`cq4`). Readback confirms the records. Receipt, original requests and readback: `/srv/nvme/tmp/cq4-human-evaluation-20260928`.

| Defect | Scope | State |
| --- | --- | --- |
| D1 | Append results while scrolling instead of First/Next page controls | Open; original behavior reproduced, frontend implementation in progress |
| D2 | Top-right connection indicator | Open; original placement measured, frontend implementation in progress |
| D3 | Hover connection diagnostics with keyboard access | Open |
| D4 | Live project creation/rename in selectors and unified scoped subscriptions | Open; missing catalogue watch confirmed in source |
| D5 | `cq init` defaults to `CQ_ORIGIN` | Open; current environment lookup omits it |
| D6 | Automatic open usage views; remove manual Refresh usage | Open; summary watch already exists |
| D7 | Switching Task → Idea exposes invalid Ready status during draft persistence | Open; reported, event ordering identified as hypothesis |
| D8 | Partial ledger query offers contextual hints before errors | Open; reported, completion/diagnostic presentation under investigation |
| D9 | Remote-HTTP UUID login failure | Resolved; `39f4d29`, retained native/browser evidence |
| D10 | Laptop control density | Resolved; `b551e0b`, both laptop viewport checks pass |
| D11 | Detached database inherits launcher lock | Resolved; `e7a4226`, before/after and host recovery evidence |
| D12 | Repeated interrupts abort cleanup | Resolved; `1064a10`, double-interrupt and closed-pipeline regressions pass |

The later sign-in report was a wrong hostname; the user confirmed login works at the configured origin. It required no application correction. Overall M2/M6 human acceptance remains pending.

## Execution and verification scope

1. Reproduce the new interaction failures on the existing package, then correct scrolling, header/hover behavior, ledger switching and query hints. Verify the affected browser behavior and existing frontend corpus.
2. Add one replaceable, explicitly scoped WebSocket watch covering independent catalogue/item/usage revisions. Catalogue pages need bounded snapshot consistency; reconnect and delayed replies must preserve scope and selection. Add the `CQ_ORIGIN` fallback with explicit endpoint precedence. Verify affected repository contracts against dummy/PostgreSQL, CLI behavior and actual browser/protocol interactions.
3. Preserve the single mutable `0.1.0` model. A catalogue clock requires editing the current schema; any existing-database transformation must be explicit, checksum-guarded and preserve operator data. Rebuild the native asset bundle and exercise the affected installed behavior. Do not rerun unrelated backend suites or the paid harness matrix.
4. Keep defect status Open until the corresponding behavior is verified. Record resolution evidence, exact delivery identity and independent scoped review. The existing uncommitted edits do not constitute a delivered fix.

Astra's read-only design review recommends independent durable counters, one watch generation, cursor-bearing bounded snapshots and coalesced refresh. It identifies the Task→Idea input-before-change event ordering and distinguishes existing query suggestions from their premature diagnostic display. These hypotheses still require actual reproductions.

## Verified frontend increment

D1, D2, D3, D7 and D8 pass the affected Chromium corpus and TypeScript checks at `/srv/nvme/tmp/cq4-scroll-ui-20260928/20260928T152521-ui`. Astra independently approved the exact source/fixture hashes. They remain Open pending installed delivery.

- Before-fix evidence: `before-layout` captures original pagination/placement; `before-interactions` reproduces hover failure, the exact Idea/Ready decoder exception and the premature `ledger:t` diagnostic.
- Results append in bounded batches while scrolling. Live refresh retains the loaded range, keyboard focus and scroll position; a stale continuation starts a fresh snapshot. Header placement passes at 1280px and 320px.
- Diagnostics open on hover or keyboard focus, allow pointer transfer, and close with Escape. Touch opening is source-reviewed, without a dedicated touch-device run.
- ADT branch selection now synchronizes before a bubbling input event can persist the form. The encoded tag and branch fields always belong to the same selection. Idea drafts persist and restore with `Proposed` status.
- Completion suggestions take precedence when their replacement covers a nonempty diagnostic span. Submitted invalid queries still produce errors. The first UI run exposed an overbroad rule hiding the trailing `alpha AND` error; the narrowed rule passes the existing query corpus.

`before-cli` reproduces first initialization failing with CQ_ORIGIN alone. `before-live` reproduces missing external project creation and a stale open audit despite a live summary. Those corrections are separate work.

## Additional reports

- **D25**: [legacy dependency lazy-value warning](scala-lazyvals-warning.md); investigated, still Open.
- **I1**: an attachable interactive CQ host for yolo use; Proposed for design discussion. The existing [launcher analysis](../design/agent-protocol.md#5-why-cq-run-codex--exists) distinguishes execution ownership from the current batch Governor wrapper.
- D13–D24 are archived duplicate filings. Re-running the original filing script with a different actor session did not reuse its request identity; canonical D1–D12 remain unchanged. Exact duplicate/archive receipts and readback are retained in the human-evaluation evidence directory.

D5 source verification passes in `after-cli` beside the reproductions: first init with CQ_ORIGIN, explicit endpoint precedence, saved endpoint precedence, legacy CQ_ENDPOINT-only use, conflicting environment variables, concurrent init and common-Git-directory behavior. Installed delivery remains pending.
