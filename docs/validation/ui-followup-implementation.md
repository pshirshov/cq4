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

Implementation is in scoped verification. CQ records remain Open/Proposed until
verified delivery. TypeScript and the initial focused checks pass in `browser-3`;
question/reference and uncertain-create checks pass in `browser-5` and `browser-6`.

Astra's first review predicted three response-ownership races. All three failed
for the expected reason in `races-before`: question settlement after revisiting
the same question, an obsolete question fault after switching projects, and an
old graph acknowledgement closing a new project's preview. Corrections pass in
`browser-6` and have independent source approval. The transport-disconnect variant
then reproduced a missing reload retry in `transport-before`; all four cases pass
in `browser-7`. The broader run also found a duplicate conflict heading, now removed. The `browser-8` continuation passes all three draft recovery scenarios, archival, rapid entry, references/questions and all four response-order cases. Astra approves the source increment; fresh native/browser and delivery evidence remain pending.

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
