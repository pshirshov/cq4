# Table sizing, completion and Idea lifecycle

User evaluation follow-up, 2026-09-29. Filed as D58–D65 and I5. D25–D27 remain
excluded. Version stays 0.1.0, with no historical model copies or compatibility
layer. Implementation is in scoped verification; operator delivery remains pending.

## Accepted behavior

- D58/D59: the table fills its results viewport; Title absorbs changes in available
  width. Metadata columns fit their headers and current values without wrapping.
  Manual overrides exist only for the current page and reset on refresh. At widths
  below the intrinsic column minimums, the results pane scrolls horizontally.
- D60: Proposed/Accepted Ideas are nonterminal. Implemented satisfies completion
  dependencies; Implemented/Declined/Withdrawn are terminal. Accepted cannot be
  archived. The existing implemented Ideas must be recorded as Implemented, with
  their delivery evidence retained.
- D61: remove the item document's arbitrary maximum width and centered margins.
- D62: put the repeat-entry shortcut inside its button; keep its accessible name
  and Ctrl/Meta+Enter action.
- D63–D65: keep completion visible during updates, anchor to the caret within
  viewport bounds and allow the list to use available height. Stale suggestions
  cannot be accepted. Escape, explicit submission and focus dismissal still work.
- I5: derive plain enum-value hints from generated frontend contracts immediately;
  merge/deduplicate backend results without losing current keyboard selection.
  Quoted/escaped forms and project-dependent items/tags use backend completion.

## Reproductions and checks

Evidence root: `/srv/nvme/tmp/cq4-ui-sizing-20260929`.

- `reproduction.json`: the installed table remained 550px wide with a 180px Title
  as the pane changed from 1592 to 1158px. Metadata fixed defaults were confirmed
  in source. At 1800px viewport, the document had 316px automatic margins on each
  side and zero padding. Implemented was absent, and the shortcut was outside its
  button. An earlier probe failed to locate the subsequently archived D51; the
  corrected probe explicitly includes archived items.
- `popup-before.json`: a keystroke hid the popup; caret movement left its x
  coordinate unchanged at 12px. The list was capped at 280px with 702px available.
- `idea-before.log`: the new service test failed on Accepted's stored outcome,
  `ItemOutcome(true, true)` versus required `ItemOutcome(false, false)`.
  `idea-after.log` passes the focused dummy-backed lifecycle test.
- `browser-2`: sizing, temporary pointer/keyboard overrides, dynamic metadata,
  document width, Implemented Idea creation and in-button shortcut pass. Completion
  checks pass synchronous local hints, deduplication, preserved selection, pending
  stale-option disabling, obsolete response rejection, caret anchoring, long-input
  scrolling, narrow viewport bounds and available-height use.
- `browser-1` reached repeated creation but its new wait predicate dereferenced a
  transient absent editor. The fixture now waits for the next editor explicitly;
  no production correction was made for that fixture error.

Checks follow Behavioral/Active/Blackbox classification: lifecycle through both
repository implementations, local enum comparison through the real API, and browser
interaction through an isolated database/server. The full `browser-3` continuation also passes. All 485 local enum prefix/caret
cases agree with backend replacements, and all 309 API/browser prefixes pass.
`gates/scoped/result.json` passes deterministic generation, Scala/TypeScript
roundtrips including every Idea status, schemas, and 60 ledger/query/workset/
termination tests across dummy and PostgreSQL. Native and relocated-package
checks remain pending. The paid harness matrix is outside
this change's scope; changed model behavior must still pass affected contract/domain
checks. Astra approves source, subject to the delivery boundary below.

## Required delivery boundary

Idea outcomes are stored in `cq_items.summary`; current PostgreSQL search/browse
and dependency traversal read that projection. Changing the policy alone is
insufficient for existing Accepted Ideas. The exact updater must reconcile **all**
current Accepted Ideas revisionally while holding the launcher lock and before
exposing the new server. Known implemented I1–I4 can move to Implemented, preserving
archive state and evidence. Other Accepted Ideas retain their status and receive
fresh current projections; any such archived items must be unarchived. Historical
records remain unchanged. A partial failure must restore the pre-update backup
before the old package can be used again.

The rehearsal must seed both archived implemented Ideas and an unarchived Accepted
Idea with a dependent using the old package, demonstrate the stale outcome under
the new package, and verify corrected projections/dependency behavior, preserved
history and backup restoration. Operator catalogue inspection is currently
unavailable (connection refused); no operator restart or data repair has been
attempted. Human acceptance remains pending.
