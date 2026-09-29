# UI follow-up implementation

Authorized scope, 2026-09-29: implement Open D51–D57 and Proposed I3/I4.
D25–D27 remain excluded. Version remains 0.1.0; reference snapshots and the
operator's existing data remain unchanged during development.

## Work and verification

1. D51–D54: initial completion selection, resizable columns, single-line dates,
   count in search and fixed headers. Verify keyboard completion, pointer/keyboard
   column resizing and scroll geometry in the real browser.
2. D55, I3/I4: route notices out of item content; quick type selection and repeated
   creation. Verify draft preservation, valid type changes, save acknowledgement
   ownership and uncertain-acknowledgement retries.
3. D56/D57: question batch answering and item-reference popups. Verify persisted
   answers, skips, concurrency conflicts, project scoping and reference navigation.
4. Run the affected browser suite and TypeScript compilation; obtain independent
   Astra review and correct findings. Build and check the native package with
   retained evidence for unchanged backend/harness paths. Rehearse the operator
   updater before asking for the host restart required by the sandbox boundary.

Reproductions preceding implementation are retained in
`/srv/nvme/tmp/cq4-evaluation-followup-20260929` (D51–D54) and
`/srv/nvme/tmp/cq4-evaluation-intake-20260929` (D55–D57 and I3/I4).
Current scope snapshot and new check results belong under
`/srv/nvme/tmp/cq4-ui-followup-implementation-20260929`.

Browser checks are Behavioral / Active / Blackbox / Good Communication, using
isolated local PostgreSQL and current application code. No paid harness route
matrix is planned for this browser-only increment. Human acceptance remains open.

## Status

Source implementation `dce6b14` and technical package delivery have independent
Astra approval. CQ records remain Open/Proposed until verified operator installation. TypeScript and the initial focused checks pass in `browser-3`;
question/reference and uncertain-create checks pass in `browser-5` and `browser-6`.

Astra's first review predicted three response-ownership races. All three failed
for the expected reason in `races-before`: question settlement after revisiting
the same question, an obsolete question fault after switching projects, and an
old graph acknowledgement closing a new project's preview. Corrections pass in
`browser-6` and have independent source approval. The transport-disconnect variant
then reproduced a missing reload retry in `transport-before`; all four cases pass
in `browser-7`. The broader run also found a duplicate conflict heading, now removed. The `browser-8` continuation passes all three draft recovery scenarios, archival, rapid entry, references/questions and all four response-order cases. Astra approves the source increment. The fresh native/browser, relocated package and updater results below now pass.

The fixtures for graph recovery and narrow layouts were updated for the requested
dialogs and column resizing. Existing data-integrity and request-replay assertions
remain in place. Earlier attempts and their failures are retained.

## Operator behavior

- Completion initially highlights the first option; Enter accepts it. Arrow keys
  change the option, and Escape dismisses completion before literal submission.
- Drag column boundaries or focus one and use arrow keys to resize. Widths persist
  in browser storage; the results pane scrolls horizontally when needed.
- The creation popup offers Idea/Goal/Defect shortcuts and **Save item and create
  next**. **Ctrl+Enter / ⌘+Enter** invokes that action, retaining the type and
  focusing the next title only after acknowledgement.
- **Answer open questions** opens a bounded batch of the first 100 Open questions
  plus retained pending answers. Skipping does not answer a question. Answers and
  uncertain requests survive reload; conflicts require explicitly adopting the
  newer question as the draft base. The dialog discloses when more questions remain.
- Inline item references open a read-only popup, with Back for nested references.
  The main selection is preserved. Unknown targets are reported in the popup.
- Archive/save confirmations use dismissible toasts. Errors and recovery steps use
  persistent notifications or their action dialogs, outside the item content pane.

## Verified package and delivery

Evidence root: `/srv/nvme/tmp/cq4-ui-followup-implementation-20260929`.

| Check | Result and receipt |
| --- | --- |
| Fresh native executable | Passed TypeScript/build, transport, seven browser scripts and actual HTTP origin checks; `gates/native/result.json`, 17 successful command receipts |
| Query prefixes | All 309 API and 309 browser prefixes pass; `gates/native/query-prefix-{results,browser-results}.json` |
| New interactions | Completion, pointer/keyboard columns, fixed headers, repeated entry, question answers/reference navigation and exact uncertain retries pass; `gates/native/{followup,question}-results.json` |
| Response ownership | All four held-response/disconnection cases pass; `gates/native/dialog-races-results.json` |
| Relocated package | Fresh closure import, hidden checkout/JVM cache, transport, affected browser/race checks and HTTP origin pass; `gates/installed/result.json`, nine successful command receipts, no verifier changes |
| Exact updater | Old/new package swap, credentials and table contents preserved, backup restored to separate database with all 24 data-table fingerprints and schema equal; `operator-rehearsal/result.json` |
| Independent review | Astra approves source, technical delivery and host-run wrapper; `source-review.json` and `final-review.json` |

The packaged candidate is `.local/release-ui-followup`, version **0.1.0**.

- Manifest SHA256: `d4b7a8a114e2f5bb0e2b37c43d1af5c768cffa712abf6855008e3cadc1ec3ed9`
- Native executable SHA256: `7f02262ae9af18fad8fe38c117c13949b68210b469b9720d11d947b319fbeec2`
- Guardian SHA256: `f56efb4626cb8e0fab0cc4b27b1460e75cea275890b5209df0a7f312ddf029bc`

Native tracing metadata and unchanged backend/harness evidence are reused from the
preceding verified package. `gates/native/trace-reuse.json` records immutable trace
hashes and source comparisons. Runtime changes are limited to `web/`; the runner's
only change adds the three browser scripts. No new paid harness matrix or full
backend gate is claimed for this UI-only increment.

### Install into the operator workspace

Actual operator installation is pending. The environment skill requires a host-run
step because the current launcher/database are outside this sandbox's PID namespace.

1. End other CQ harness sessions. Stop the server launcher with **Ctrl-C once** and
   wait for **CQ stopped**.
2. In the host terminal, run:

   ```sh
   bash /tmp/exchange/cq-ui-followup-update.sh
   ```

3. Leave it running after **CQ ready**, then reload the browser once. Future launches
   continue to use `./run-local.sh`.

The wrapper verifies its updater/configuration hashes. The updater verifies both
packages, refuses an active launcher/database, creates a restorable backup and
preserves state and credentials. The preceding package is retained at
`.local/release-before-ui-followup`; it does not change the application version.
The live receipt will be `operator-update/receipt.json` in the evidence root.

After installation, run the prepared read-only hostname check:

```sh
nix develop --command node /srv/nvme/tmp/cq4-ui-followup-implementation-20260929/operator-live.mjs
```

The check exercises the actual login, first-completion Enter behavior, table
controls, quick creation types and question dialog without saving project changes.
Native isolated fixtures cover mutations and retries. Revision-checked CQ closeout
follows verified installation; it preserves D25–D27 and labels evidence as
model-declared. Human acceptance remains pending.
