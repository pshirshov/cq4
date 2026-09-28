# M5 top bar and workspace

This increment puts project selection, query editing, connection/data state and scoped usage observations in the top bar. Navigation, results and detail are independently scrolling panes on desktop. Pointer and keyboard splitters enforce minimum navigation/results/detail widths. At 920 pixels and below, panes stack and splitters leave the accessibility/focus tree.

Ledger shortcuts replace the visible query and use the normal search path. Result rows expose their status and selected state. Arrow/Home/End keys navigate result controls; Enter selects, Right enters detail and Escape returns. F6/Shift+F6 move between panes; Ctrl+K focuses the query. Metric text distinguishes Direct/Shared/Unattributed known amounts, unknown/estimated measurement counts and the last successful observation/cursor. Disconnect marks that observation stale. Independent usage invalidation and cohort/session scopes remain subsequent work.

## Reproduce before correction

At `e7526cd`, `/srv/nvme/tmp/cq4-implementation/20260928T023327-ui` reproduces loss of keyboard focus after an external mutation refreshes results. The assertion fails with `Live result refresh must retain keyboard focus: false !== true`. The renderer replaced all controls on each refresh. Result rows now retain identity by project/item; updates reuse their controls, and a removed focused row falls back to the result group.

## Verification

Focused command: `./dev/check ui`. It type-checks the frontend and runs real Chromium against the actual server and isolated PostgreSQL, with no model consumers or unrelated supervisor fixtures.

The workspace fixture seeds real records and checks nine behavior groups: live result focus; keyboard selection/detail return/pane/query focus; visible-query shortcuts; pointer/keyboard splitters and viewport bounds; independent result scrolling; draft text/focus during external edits; narrow layouts with unbroken text; draft persistence across reload/project switching; and observed/stale usage labels. It grows the result set to fifty records and checks the forty-item page. Page and pane horizontal bounds pass at 920, 390 and 320 pixels; hidden splitters are absent from accessible-role queries. Browser assertions use public DOM/layout and real server updates (Behavioral/Blackbox/Good-Communication).

Evidence root: `/srv/nvme/tmp/cq4-implementation/20260928T024002-ui`. The workspace corpus passes with no page errors. Retained `workspace-desktop.png` and `workspace-narrow.png` were visually inspected: the former shows the three panes, selected row, top query/project and scoped observation; the latter shows stacked scrolling navigation/results, preserved edit form and wrapped long content. Generic editor presentation remains basic, and actual screen-reader operation is not established by these checks.

The full gate passes, including the existing ownership/query/draft/usage/connection corpus. Independent Astra approved the bounded increment with no blocking or major finding after verifying the completed gate, all nine workspace groups, matching source manifests and both screenshots. This is a bounded layout increment; relationship editing/restore/conflict interaction, independent usage watch/full scopes, full connection diagnostics and final M5 acceptance remain open.
