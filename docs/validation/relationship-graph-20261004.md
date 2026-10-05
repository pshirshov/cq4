# I31: relationship graph source validation

The selected item's Relationship graph opens a large dialog. Its default view separates prerequisites and dependents: a Blocks arrow runs from prerequisite to dependent. All relationships exposes the other named relations; RelatesTo has a symmetric line. IDs, titles and statuses identify neighboring nodes. Enter activates a node, Back in graph returns to the previous center, and Open item selects it in the main pane. Refresh reads new relationships explicitly; the graph identifies the center's observed revision rather than claiming a global snapshot.

Each page contains at most 12 reference edges, with bounded summary lookup through the existing search endpoint including archived items. Nodes retain native buttons and headings; SVG arrows are decorative, with the edge's endpoints and relation available as text. Missing summaries are explicitly unavailable. Closing or replacing a graph invalidates pending reads. No database, API model, dependency or ledger mutation was added.

Focused source validation used the actual App, JVM server, Chromium and private PostgreSQL. Evidence: `/srv/nvme/tmp/cq4-final-wave-20261004/i31-final/` and `i31-final.log`. Five cases passed with zero page errors:

- Blocks direction, readable identity and escaped literal HTML in a title.
- All 16 dependency neighbors across pages of 12 and 4, with correct page bounds.
- Keyboard recentering, back navigation and an additional symmetric relation.
- A held reply from a closed graph cannot overwrite a newly centered graph.
- 640-pixel layout, opening a related main-pane item and unchanged ledger revisions/references after navigation.

TypeScript checking and web compilation passed. Desktop and narrow screenshots and the browser trace are retained; the desktop screenshot was inspected. Configured host checks and combined native/installed acceptance remain pending. This is source completion, not deployed delivery.

## I32: layout, keyboard and default view (2026-10-05)

The description above is I31 as delivered. I32 changed the default view to all relationships, replaced the three stacked columns by the ring layout described in the [browser workspace design](../design/browser-workspace.md#relationship-graph), added arrow-key movement between nodes and a hint for a view without edges, and raised the results table's minimum title width to 200 px.

`dev/relationship-graph-browser.mjs` now also asserts, at 1440×900 and 1280×720 with 12 neighbours whose titles wrap: the centre's box within the middle third of the graph both ways, no two node boxes intersecting, every node inside the graph and at least 180 px wide, and a dialog body that does not scroll; every arrow key from every node reaching the nearest node within 45° of its direction, a focus ring of at least 2 px, and full accessible names on clamped buttons; the graph opening on all relationships, and the hint and its switch for an item whose only relationship is not a dependency; a title column of at least 200 px beside the open item pane at both sizes. Against the I31 code the new cases failed on the 80 px title column, the dependency default, a graph of 1325 px in a 759 px (597 px) body, an arrow key that moved nothing, and the missing hint.
