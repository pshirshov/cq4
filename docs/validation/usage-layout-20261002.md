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

The supervising agent did not run the configured `./dev/check ui` gate: current AGENTS.md reserves it for the CQ host. The later host pass is recorded below. No package, installation or actual-hostname verification has occurred for this increment. D121/D122 remain Open pending delivery evidence.

## Configured-check follow-up

The trial drive's T5 candidate exposed one additional D121 fixture expectation in `selectionChecks`: three delayed-audit cases expected `33 · Observed` / `22 · Observed` / `11 · Observed`, while the redesigned audit cells render the qualifier first (`Observed 33`, etc.). Both host cq-ui attempts failed on this assertion. The host validation observation is artifact `999a827e-37f1-35fb-97fe-d61390d9661f`; its decoded stderr was retained at `/srv/nvme/tmp/cq4-drive9-evidence/t5-cq-ui-decoded.stderr`.

Updated that assertion to the intended qualifier-first format; the race/ownership checks are otherwise unchanged. The focused `selectionChecks` fixture then passed all eleven scenarios, including all three audit cases, using the same private server harness. Evidence: `/srv/nvme/tmp/cq4-x3-usage-evidence/continued-selection/`. No production change or configured gate was run by the supervising agent. The original five-fixture verification was incomplete for this format change; the failure was in an inherited fixture, not T5's catalog implementation.

The CQ host subsequently ran its configured UI check on T5 candidate `1113eaeeb3a2fe6f56c4653500bf1514cb0c091f`: observation artifact `d08c6f06-044a-32dd-9583-785cd74f250f` records a settled exit 0 with no stderr. The candidate has the same `web/` sources and browser fixtures as main `0e6029b` (verified by an empty scoped diff). This supplies full configured UI-check evidence for the merged D121/D122 changes. The observation was copied to `/srv/nvme/tmp/cq4-drive9-evidence/t5-fresh-cq-ui.json`.
