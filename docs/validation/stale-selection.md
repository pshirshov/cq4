# D72: stale item view after a navigation filter switch

Decision 8: after a results refresh, if the selected item is not among the loaded rows and no further
pages remain, clear the selection and hide the item pane through the D71 close path.

- Fix: `App.refresh()` in `web/src/app.ts` re-selects only when the loaded rows contain the selection or
  `page.hasMore` is true; otherwise it calls `hideItem()`. That is the focus-neutral part of the D71
  `closeItem()` path (`choose(null)` followed by `Workspace.setDetailOpen(false)`).
- Test: `dev/workspace-browser.mjs` (run by `dev/browser.mjs` in `./dev/check ui`). It seeds Tasks T1/T2 and Defect D1, then selects T2.
  - **Still-listed case:** switch to Tasks. The heading stays visible and T2 stays `aria-current`.
  - **Hide case:** switch to Defects. The item pane is hidden, the heading is removed and no row is `aria-current`.
  - **Afterwards:** return to All items. No stale detail is shown, and reselecting reopens the pane.

## Fail-then-pass

- `unfixed-check-ui.out`: `nix develop -c ./dev/check ui` run with the base `refresh()`. It fails with
  `AssertionError: stale task detail remains visible after switching to Defects`. The recorded state is
  `unfixed-stale-selection.json` (`detailVisible: true, headingCount: 1, taskRowPresent: 0, selectedRows: 0`).
- `fixed-check-ui.out`: the same command with the fix prints `PASS: ui`. The recorded state is
  `fixed-stale-selection.json` (`detailVisible: false, headingCount: 0`).

Known scope: the check also runs on live-update refreshes. Selection is kept while `hasMore` is true. The
hasMore case is not covered by a browser test.
