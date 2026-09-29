# Query and table follow-up, 2026-09-29

The user requested filing four further UI defects and closing already-fixed
reports. D51–D54 are filed **Open**, with reproductions from the installed browser.
No implementation change is included. D13–D20 are **Resolved**, with individual
resolution evidence and references to their canonical reports. A subsequent intake
adds Open D55–D57 and Proposed I3/I4, detailed below. Human release acceptance
remains pending.

## New reports

| Report | Observed on the installed package | Required behavior |
| --- | --- | --- |
| D51 | `ledger:d` produces `decisions` and `defects`, with zero selected options and no active descendant. Enter submits the unchanged invalid query. | Select the first current suggestion initially; Enter accepts it. |
| D52 | Headers expose sorting buttons but no column resize affordances. | Resize individual columns independently of the results pane, without triggering sorting or selection. |
| D53 | Date and time are separate block-level spans, forcing two lines in Last modified. | Allow date and time to occupy one line when width permits. |
| D54 | A separate result-count line precedes the table. Header top moves from 118 to 68 to 67 pixels at scroll offsets 0, 50 and 150; pane top remains 67. | Move the count to the right of the search field and keep headers fixed from the first scroll. Preserve loaded-count versus complete-count semantics. |

D54 is an initial movement defect: the headers **do eventually stick**. The probe
used `ledger:Defects archived:all`, with `40 items · more available`. Browser
inspection was read-only; the subsequent filing and closeout are separately
recorded API mutations.

## Verified duplicate closeout

These records were created by a replay of the original filing script with a new
actor session. They already identified themselves as duplicates. The terminal-only
archival repair had unarchived them while preserving their Open status. This
closeout changes each from Open revision 3 to Resolved revision 4 and leaves it
unarchived. It also corrects the stale body text that claimed it was archived.

| Duplicate | Canonical report | Verification used |
| --- | --- | --- |
| D13 | D1 | Bounded scrolling, retained earlier results and live loaded-range preservation. |
| D14 | D2 | Top-right connection indicator on laptop and narrow layouts. |
| D15 | D3 | Hover, pointer transfer, keyboard and Escape diagnostics; touch remains source-reviewed only. |
| D16 | D4 | External project creation/rename, delayed catalogue responses and reconnect catch-up. |
| D17 | D5 | `CQ_ORIGIN`, saved/explicit endpoint precedence and `CQ_ENDPOINT` checks. |
| D18 | D6 | Live usage updates, coalescing, watch races and correction/view lifecycle checks. |
| D19 | D7 | Valid Task→Idea and Idea→Defect drafts; all 182 content-type transitions. |
| D20 | D8 | Partial-ledger hints before diagnostics. D51's initial selection behavior is a separate requirement. |

The retained installed browser and CLI checks use the exact current package:
manifest SHA-256
`403f5896da455213f3d2709b27a355b337b307282540f509686ec6bf7153041e`.
Relevant command receipts passed. That aggregate attempt subsequently failed an
obsolete nonterminal backup fixture; the corrected exact-package backup
continuation passed separately. See [delivery evidence](evaluation-polish.md).
These are model-declared technical checks, not human acceptance.

All twelve mutations were applied in one revision-checked transaction. Direct
readback verifies the four new reports, eight status changes, unchanged canonical
D1–D8, and unchanged D25/D26/D27. Immediately after this closeout, the Open defect set was **D25, D26,
D27, D51, D52, D53, D54**. D25 remains the dependency blocker; D26/D27 remain
reserved for CQ exercises.

## Retained evidence

Root: `/srv/nvme/tmp/cq4-evaluation-followup-20260929`.

- `reproduce.mjs`, `reproduction.json`, `reproduction.log`, `completion-before.png`,
  `table-before.png`: actual-hostname browser observations.
- `evidence-binding.json`: installed artifact identity and prior gate qualification.
- `record-followup.py`, `actor-session.json`, `followup-request.json`,
  `followup-result.json`: filing/closeout procedure and exact transaction receipt.
- `closeout-before.json`, `details-after.json`, `result.json`: revisions, resolution
  evidence, preservation assertions and complete remaining Open set.

Reused browser/CLI evidence is under
`/srv/nvme/tmp/cq4-evaluation-polish-20260929/gates/20260929T092944-installed`;
the backup continuation is `gates/installed-backup-resumed` under the same root.
No package rebuild, backend gate or paid harness evaluation was needed for filing
and verified report closeout.

## Further intake: messages, rapid entry and restored features

The user requested filing these reports, not implementing them in this increment.
All five were created in one transaction and verified by direct item readback.

| Record | State | Requested behavior |
| --- | --- | --- |
| D55 | Open | Keep operational messages out of the item pane. Archive success belongs in a toast; route other messages to a suitable status bar, toast or action dialog. |
| I3 | Proposed | Quick Idea, Goal and Defect selection buttons in the new-item popup header. |
| I4 | Proposed | A Save item and create next button and discoverable keyboard shortcut, preserving retry and draft safety. |
| D56 | Open | Restore a dialog for answering multiple Open questions, with context, alternatives, progress and per-question save/advance. |
| D57 | Open | Highlight inline item references such as D5; activation opens a semantic content popup while preserving the current selection. |

### Notification audit

`web/src/app.ts` mounts its shared notice directly in the item pane. Its writers
include archive success, item-save success, graph-change success, restored draft
and unresolved-save notices, and application errors. Errors are additionally copied
into open dialogs. Pending graph operations and edit conflicts also render inline
recovery instructions and controls; D55 includes checking their placement while
preserving actionable recovery.

The live browser reproduced the restored-draft notice in `#detail-pane`: open New
item, type an unsaved title, cancel, reopen, then close. The notice remains after
selecting another item. The exact `Archived 37 items.` message is user-reported;
its routing is source-confirmed, and no archival mutation was repeated.

### Current and original feature observations

The installed new-item header has only Close; its editor has Save item, Cancel edit
and Discard local draft, with all fourteen types in a combobox. D17's narrative
contains D5 but no matching interactive element. These observations come from an
isolated browser context with no ledger mutations or changes to the user's drafts.

The original snapshot's `ledger-web/src/App.tsx` contains `openBatch`, `batchSave`
and an answer-open-questions modal, including prompt/context, alternatives,
progress and previous/next navigation. Current code has only the generic Question
form/view. The live project contains no Questions, so no multi-question runtime
sequence was exercised. The original application was inspected, not run.

The original `itemReferences.ts`, `ItemReferenceChip.tsx` and `Markdown.tsx` scan
reference tokens and render interactive chips. Their interaction is hover/focus
preview plus click navigation; D57 records the user's explicitly requested
click-to-popup behavior rather than claiming the original code did exactly that.
Reference snapshots remain unchanged.

Evidence root: `/srv/nvme/tmp/cq4-evaluation-intake-20260929`.
`inspect-browser.mjs`, `browser-findings.json`, `browser-inspection.log` and two
screenshots retain current observations; `source-findings.json` records the source
audit and installed manifest hash. `filing-request.json`, `actor-session.json`,
`filing-result.json`, `readback.json` and `result.json` retain the exact filing and
verification. D25/D26/D27 and earlier follow-up reports remain untouched.
