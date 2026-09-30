# Item view close: D71 (T15, per D7)

The item pane now closes. The pane actions include a "Close item view" control.
Escape on a results row closes the pane while an item is selected. Escape inside
the pane still returns focus to the results row first. Closing clears the
selection and hides the pane and its separator. F6 skips the hidden pane, and
selecting an item reopens it.

## Fail-then-pass evidence

The retained outputs are in `item-view-close/`. Absolute workspace paths are
replaced with `<workspace>/`. Both runs used `nix develop -c ./dev/check ui` in
the same managed workspace, based on main `bc474e2`. The browser case in
`dev/workspace-browser.mjs` was identical in both runs
(sha256 `b7a42fa4…54d43`).

- **Unfixed UI** (evidence run `20260930T153622-ui`): `web/src/app.ts`,
  `web/src/workspace.ts` and `web/style.css` were restored to their `bc474e2`
  contents. The recorded source hashes `a11aee80…`, `a8cf01a6…` and `bd48781a…`
  match `bc474e2`. The check failed with exit 1. `unfixed-ui-browser.log` shows
  `locator.click: Timeout 30000ms exceeded` while waiting for
  `getByRole('button', { name: 'Close item view' })` at
  `dev/workspace-browser.mjs:51`. `unfixed-workspace-results.json` records only
  the two earlier cases. `unfixed-check-ui.out` is the full check output.
- **Fixed UI** (evidence run `20260930T153836-ui`): the candidate sources were
  used (hashes `c09ec55f…`, `9bb83fc8…` and `f23a2489…`). The check printed
  `PASS: ui` (`fixed-check-ui.out`). `fixed-workspace-results.json` includes the
  case "item view close button and Escape close clear selection, hide the pane,
  F6 skips it and selection reopens it".
