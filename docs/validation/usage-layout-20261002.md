# Usage layout and item close control, 2026-10-02

D121/D122 source changes began at `01712f7` (an explicitly unverified WIP). Continued verification used the same worktree, `/srv/nvme/tmp/cq4-x3-usage`.

## Reproduction and correction

The original fail-before logs are `/srv/nvme/tmp/cq4-x3-usage-evidence/D121-failing-first.log` and `D122-failing-first.log`. D121 reported missing table headings and scroll containers, left-aligned ungrouped numeric values, and wrapped amounts. D122 reported a text Close button in the document action row, below the pane corner.

Usage tables now use content-sized columns with horizontal scrolling, grouped tabular numbers, right-aligned numeric values, short headings and totals. Cost basis appears once for uniform tables and alongside mixed-basis values. Missing costs remain Unknown or absent, never zero. Item close is an accessible icon control beside the dock control at the top right; keyboard return and focus behavior are preserved.

## Verification

`npm run check` passed. Focused fixtures ran through the private `.work/x3/harness.py` server and disposable PostgreSQL database, not the installed playground:

- `usageLayoutChecks`: project and item surfaces at 1366/1920 px, narrow 700 px scrolling, expanded attempt/audit details; passed.
- `workspaceChecks`: corner close control, keyboard navigation, focus return, resizing, draft preservation; passed.
- `usageChecks`: live attempts/outcomes, corrections, per-phase totals, truncated/paginated costs and unchanged item revision; passed after scoping its newly added cost-basis selector to the phase section. The initial run failed on a strict selector matching three legitimate notes; production code was unchanged for this correction.
- `usageScopeChecks`: project/session/cohort/task links and shared accounting; passed.
- `usageLiveChecks`: independent live usage, catch-up and connection replacement; passed.

Evidence: `/srv/nvme/tmp/cq4-x3-usage-evidence/continued-after/` and `continued-usage/`. The project-usage and item-header screenshots at 1366 px were visually inspected: aligned grouped numeric values, unwrapped costs and the close icon beside the dock control are visible.

## Limits

The configured `./dev/check ui` gate was not run: current AGENTS.md reserves it for the CQ host. No package, installation or actual-hostname verification has occurred for this increment. D121/D122 remain Open pending delivery evidence.
