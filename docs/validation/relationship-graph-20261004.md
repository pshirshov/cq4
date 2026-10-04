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
