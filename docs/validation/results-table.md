# D29/D30: navigation and sorted results

Scope: the user authorized all remaining defects except D26/D27. The reserved
keyboard status bar and navigation resizer exercises remain open.

## Behavior

- Results use a semantic table: ID, type icon, title, status, severity. Click a
  column heading to sort; click again to reverse it. Sorting covers the entire
  matching query, with 40-row initial batches and infinite scrolling.
- ID order uses the displayed prefix and numeric suffix. Text uses deterministic
  Unicode code-point order. Equal primary keys retain ledger/number order.
  Severity starts with Critical, then High, Medium, Low; absent severity stays
  last in either direction. Query changes and sorting fence obsolete responses.
- Navigation has left-aligned labels, icons and right-aligned counts. Counts
  cover **unarchived items in the selected project**, independent of the query.
  They update through the existing live subscription, including during invalid
  searches. Workspace/Items headings are removed.
- A compact typed browse response supplies severity without fetching item
  documents. The existing machine search remains a lean summary query.

## Reproduction and verification

Evidence root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.

- `table-before`: installed preceding package fails the expected three checks:
  missing table, missing counts, redundant headings.
- `table-first/browse-contract.log`: four shared behavior tests pass against
  memory and PostgreSQL. Its subsequent browser startup failed because resource
  generation/build overlapped classpath collection; the failed attempt is kept.
- `20260928T211757-ui`: full scoped UI gate passes, including existing keyboard,
  narrow layout, draft, graph, live-update, stale-reply and scroll regressions.
  The five new browser cases cover sorting unseen items, every heading, all
  three batches in descending order, and independent archive/count updates.
- Astra identified body-read amplification in the first severity projection.
  Its isolated PostgreSQL measurement (`astra-browse-cost`) holds 200 summaries
  constant while increasing narrative size: warm sort latency rises from
  0.324–0.373 ms / 14 buffer hits to 19.889–21.482 ms / 11,501 hits.
- The correction stores severity in a compact scalar and reads continuation
  anchors from summary/severity only. Astra's independent `compact` rerun gives
  0.274–0.329 ms / 18 hits with large bodies. These are SQL fixture measurements,
  not complete application latency. Astra approves the source correction.
- `table-compact`: both repository implementations and all five browser cases
  pass after that correction and the final sort-arrow/count-accessibility edits.
- `20260928T212434-contracts`: deterministic generation, Scala/TypeScript
  round trips and MCP schema checks pass. Astra approves the implementation
  increment and inspected offline preservation evidence with no major findings.

## Delivery boundary

The current schema is edited in place at version 0.1.0. Before deploying this
increment over the preceding operator installation, severity must be backfilled
from the unchanged item content in a backed-up, offline transaction. The
development schema checksum must match the exact inspected predecessor. No
runtime compatibility fallback or historical schema is introduced.

- `projection-update`: an old-package fixture is dumped and restored into a
  separate database before applying the checksum-guarded projection update.
  Every original table row remains fingerprint-identical, including histories,
  claims, receipts, usage and artifact bytes. The new server passes persistence
  verification and reads the backfilled severity. Repeating the update is
  rejected. This rehearsal is not an update of the operator's live database.

Final native delivery is pending. The installed package has not yet been replaced;
CQ records remain open until delivery evidence is complete. CLI, backup/restore,
integration, Codex usage and dependency-warning work continues under the
[remaining-defect plan](../drafts/20260928-remaining-defects.md).
