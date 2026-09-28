# M5 independent usage updates

The single `0.1.0` model adds `ClientFrame.WatchUsage` and `ServerFrame.UsageCursor`. Each WebSocket owns one replaceable usage watch, independent of its item subscription. The server authenticates and scopes the request, reads the project usage clock through `Application`/`UsageService`, sends the initial cursor, and sends another notification only when that cursor changes. A typed scope/missing-project failure stops the watch. Socket teardown stops its polling fiber. Role MCP operations, dispatch contracts and harness adapters do not change.

The browser registers a watch for the active project on every active-connection callback. Project/connection changes invalidate previous subscription and summary/audit ownership. Usage requests are independent of query epochs, including invalid queries. Notifications coalesce behind a read for the current scope; a newer scope can start immediately, and obsolete results cannot alter it. If the returned summary is behind an observed cursor, one catch-up read continues toward that cursor. Existing audit/cost/attempt/outcome pages retain their snapshot cursor and acquire a stale label when a later usage cursor is observed; refreshing the summary does not silently replace the open audit page.

## Verification

`./dev/check usage` runs generated frontend checks, the same usage contract against dummy and PostgreSQL repositories, real authorized WebSocket checks with PostgreSQL statement statistics, and the full retained Chromium corpus. It requires an isolated local PostgreSQL cluster. `./dev/check ui` remains the smaller browser-only gate. Neither launches model consumers.

The initial `20260928T032513-usage` gate passes 26 service scenarios, protocol checks and the then-current browser corpus. The new service scenario verifies initial/changed usage cursors, idempotent replay, outcome correction, missing projects and unchanged item/history state against both repositories.

During an isolated stable-watch interval, PostgreSQL records exactly these two application statement shapes, each twice with two rows returned in total:

```sql
SELECT cursor FROM cq_usage_clock WHERE project_id = $1
SELECT $2 FROM cq_projects WHERE project_id = $1
```

Both predicates use the project primary key; source inspection confirms no summary/observation scan in the cursor path. These statistics measure calls and returned rows, not buffer visits or a new cardinality benchmark. Stable polls and idempotent replay emit no duplicate notification. Actual socket checks also verify replacement of the watched project, permitted/denied scoped credentials, and typed missing-project errors.

`20260928T032153-contracts` passes deterministic generation and generated-schema checks, including Scala→TypeScript→Scala round trips for both new frames and a cursor above JavaScript's safe-integer range. The model/lock, generation script, wire fixtures and build/npm inputs are hash-identical to that passing contract gate. Later UI/recovery changes are covered by the final focused gate below, so the unchanged contract evidence is retained.

## Reproduced connection replacement defect

Astra predicted that overlapping healthy connection replacement would lose the watch. `20260928T032840-ui` reproduces it: a browser lifecycle event opens a replacement socket while the old one is still alive. The replacement receives zero watch registrations/cursor frames. A committed usage-only upload cannot advance the displayed total from 22 to 25. The fixture captures both sockets' sent/received frames and the failing wait.

The root cause is relying only on the disconnect callback to invalidate watch identity. Promotion chooses the new active socket before closing the old one, so closing that old socket does not produce an active-disconnect callback. The correction invalidates usage ownership and re-registers the watch on every activation, including replacement/recovery.

The same failing gate already passes two additional cases: a held summary survives an invalid-query submission without another write, and several notifications produce one active read plus one catch-up read. Astra retracted its query-epoch hypothesis after checking the existing independent guard and these runtime results. The final `.work/evidence/20260928T032953-usage` gate passes 26 service scenarios, protocol/SQL checks, all three live-browser cases and the complete retained browser corpus. All 15 changed implementation/model/fixture files match its source manifest. The replacement socket registers exactly one watch for the active project and reaches the later total. `usage-live.png` visibly shows an invalid query beside current usage (25 tokens, cursor 7) and an older audit page labeled stale (cursor 3). Independent Astra approved the final source and evidence with no unresolved blocking or major finding. Full connection diagnostics and M5 acceptance remain open.
