# First UI redesign increment

The user authorized a selected redesign batch in this session, reserving simple defects for later CQ discipline testing. Scope: **D28 and D31–D37**. **D26 (status bar) and D27 (fixed navigation width) remain Open and deliberately reserved.** D29/D30 navigation/results redesign, D38–D40 CLI work, I2 backup/restore, D41 integration into checked-out main and D42 outer Codex usage remain separate work.

## Changes

| Defect | Implementation |
| --- | --- |
| D28 | A header `+` next to the project selector opens the project-creation dialog. |
| D31 | The detail pane docks right or below results; its orientation persists. |
| D32 | Shared semantic field order and document rendering replace nested outlined forms and JSON content. Evidence, citations, metadata and item references have dedicated presentation. Editing uses the same field order; conflict comparison and restore preview reuse the viewer. |
| D33 | New-item creation uses a dialog. Dismissal preserves the local draft and existing selection; explicit discard removes the draft. |
| D34 | History opens a revision table and read-only semantic content/relationships, with the existing guarded restore preview. |
| D35 | Relationship targets can be discovered by title or entered by ID; keyboard selection and explicit preview/confirmation remain available. |
| D36 | Selected-item usage is automatic and tabular. Project/session/cohort usage opens a dialog without replacing the selected item. Costs are tabular; detailed audit payloads remain behind explicit drill-downs. |
| D37 | Panel widths and the bottom-layout height persist in local storage and are bounded to the viewport. |

The model remains **0.1.0**. Backend, harness adapters, dependency pins and reference snapshots are unchanged. Technical checks do not establish human usability acceptance.

## Reproduction and verification

Evidence root: `/srv/nvme/tmp/cq4-ui-redesign-20260928`.

- `before/redesign-results.json`: all eight original behavior checks failed before implementation, including absent dialogs/semantic rendering and panel width returning from 356px to 340px after reload. Screenshots and browser trace are retained.
- `after-1/`: all eight focused checks pass. `after-2/` additionally verifies structured evidence/citation roundtrip through an actual save, read-only historical evidence and persisted bottom-layout height.
- `20260928T203505-ui/`: the first full frontend run caught displacement of the connection indicator at 320px. Grouping the project selector and its plus control corrected it; subsequent scrolling/placement checks pass. `after-2/` also retains an old fixture attempting to click background controls through a native modal; its interactions were updated without dropping the draft/catalogue assertions.
- `search-rejection-before/`: an actual pending relationship search rejected by socket closure surfaced an obsolete alert after switching items. Typed server faults already respected ownership; their distinct reproduction passed before correction. The new rejection handler uses the same project/generation fence. Both cases are retained in `dev/graph-browser.mjs`.
- `20260928T204115-ui/`: the complete frontend gate passes, including all existing concurrency/draft/usage scenarios, connection checks and plain-HTTP operation.
- `20260928T204154-native/`: fresh native build, semantic UI/roundtrip/layout and plain-HTTP checks pass against the embedded assets.
- `packaged-graph-final/`: the packaged executable passes all six graph scenarios. The final fixture waits until the withheld search is the only unforwarded call and the new item's usage has rendered before closing the socket. This is the sole fixture change after the full UI gate; runtime source hashes are identical.
- `unfenced-rejection-final/`: supplemental causal check, using an explicitly rebuilt browser bundle with only the rejection guard removed. It fails with one obsolete alert under that same isolated condition. This is an ablation, not a claim of a second historical baseline.

The intermediate `packaged-graph*` failures are retained. The browser-level protocol spy observed a withheld response on the underlying socket, so its pending list was unsuitable for this route-interception precondition; the final fixture tracks forwarded replies at the interceptor. A subsequent wait expected diagnostic text while its popover was closed; the final fixture opens the popover before asserting the recorded close. These were fixture corrections, with no further product edits.

`dev/redesign-browser.mjs` is a Behavioral-Active, Blackbox, Good-Communication regression fixture using the real CQ server, PostgreSQL and Chromium. It runs from `./dev/check ui`, alongside delayed response ownership, local drafts and uncertain acknowledgements, all 182 content-type transitions, relationship restore, live catalogue/usage, query completion, keyboard/resizing, laptop/narrow layout, connection recovery and ordinary HTTP checks. No model calls are required.

## Native build scope

`build-native.py` in the evidence root requires every runtime delta from the installed package to be under `web/`, and requires the tracing runner to be unchanged. It verifies and copies all 146 immutable native metadata snapshots from the installed package's passing native evidence, merges them, rebuilds with the current assets, and exercises semantic UI and plain-HTTP behavior. The provenance explicitly records **no fresh JVM tracing and no full backend/harness corpus rerun**. The native result carries its narrower verification scope; it is not a new full-release acceptance.

## Package and review

Astra independently approves the scoped source and UI/native evidence after the reproduced rejection correction. Astra also approves final package delivery in `final-review.json`, with no remaining major findings. `dev/package` verifies the native result and exact runtime sources; every installed file is hash-checked. Installation receipt: `installed-package.json`.

- Installed package: `/home/pavel/work/safe/cq4/cq4/.local/release`.
- Executable SHA-256: `ed4bd811c9f1261186cb66c1767e2cac6d6b084b4a997ce3904c34b706f0e9d9`.
- Manifest SHA-256: `45dfea561b40d24793724a2f4bf1bd62f48b371d1306106437348759a51d892b`.
- Previous package retained at `.local/release-before-ui-redesign`.
- The host server has **not** been restarted by this session. No operator database transformation or credential change was required.

CQ readback confirms D28 and D31–D37 **Resolved at revision 2**, with model-declared test evidence and the restart limitation. D26/D27 remain **Open at revision 1**. Requests, acknowledgement and readbacks are `resolution-request.json`, `resolution-ack.json` and `resolution-readback.json`.

Reproduce the full affected frontend gate with `nix develop -c ./dev/check ui`. The scoped native runner and exact package/graph commands are retained in the evidence root. Backend and paid harness evaluations were not rerun.

## Run and inspect

After installation, stop the existing server with Ctrl-C, wait for `CQ stopped`, then run `./run-local.sh` and reload the browser. The current server process must restart to serve the new embedded assets. Local project data and credentials are preserved.

For further CQ testing, start the directly integrated harness with the authenticated work profile:

```sh
yolo --profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex
```

Use the project `.agents/skills/cq-begin/SKILL.md` with **D26 or D27**. D41 still prevents automatic integration into this checked-out `main`; preserve reviewed candidates and report that blocker rather than detaching the checkout.
