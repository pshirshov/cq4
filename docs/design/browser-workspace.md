# Browser workspace completion

M5 completes R02, R23 and R24 and the browser portion of R31. This document is an implementation and verification plan; the checks below are not yet executed evidence.

## Boundaries and increments

Reuse the generated current `cq.api` 0.1.0 contracts and existing services, extending that same version for the usage notification described below. The browser owns presentation, transient navigation and durable local drafts. PostgreSQL owns revisions, relationships, query semantics and usage accounting. Browser convenience actions edit the visible query; they do not introduce a second filter model.

1. **Selection and synchronization:** reproduce delayed item/history/usage replies and project changes in Chromium. Bind each response to its project generation, requested selection and request generation. Keep the active draft independent from refreshed item detail. Verify the existing committed-mutation acknowledgement recovery and stale search-cursor resnapshot paths.
2. **Workspace and query:** place project selection, the query editor, scoped metrics and connection health in the top bar. Use navigation, results and detail as three independently scrolling panes. Add cursor-aware completion, positioned diagnostics, keyboard focus/navigation and resizable panes; provide a narrow layout without horizontal page overflow.
3. **Editing and relationships:** present typed content, navigable relationships and paginated history. Use the existing optimistic mutation APIs for relationship changes and historical restore. Surface conflicts with both the draft's base revision and the latest revision. Preserve local drafts across selection, project changes, live events, reconnect and reload; make resumption and discard explicit.
4. **Usage:** expose task, cohort execution, session and project scopes with links from recorded attempts/assignments. Show the accounting cursor and observation freshness separately from item synchronization. Refresh after usage changes, including upload and outcome correction, without modifying item history. Preserve audit/cost pagination, shared-work attribution, missing measurements and pricing basis.
5. **Connection diagnostics:** finish the requested `resilient-ws-ui` behavior and visible diagnostics. Verify nonce deadlines, replacement limits, retry/lifecycle/terminal states and teardown with actual WebSockets. Present transport health and data freshness separately.

Each increment gets targeted checks and a verified commit. Independent Astra reviews the milestone and any material contract boundary. Retained Claude/Codex/Pi route evidence remains applicable to UI-only changes. Shared service/API changes get their affected checks; harness checks run when harness behavior or its shared contracts change. The packaged release corpus remains M6 work.

## Request and draft ownership

- A project change invalidates the previous project's detail, history, usage, audit and completion requests immediately. Panels either show data for the new scope or a labeled loading/empty/error state.
- Selecting B after A prevents A's delayed replies from replacing B's detail, history, usage or audit. New requests for the same selection also supersede older requests. Capture scope and labels before awaiting a response.
- Refreshing a result list does not move keyboard focus or replace unsaved editor fields. Query submission resets pagination; invalid syntax preserves the input and exposes the server's diagnostic span.
- Persist drafts under their project/item identity with the original expected revision. A conflict does not silently rebase or overwrite concurrent edits. An explicitly selected current revision may become the base only after the user can inspect the difference.
- Persist a mutation's request identity before sending. An uncertain acknowledgement keeps the exact request available for retry and prevents a different mutation from being substituted. Report a committed result even if navigation moved elsewhere, without moving the current selection back.
- Historical restore and relationship changes use the current expected revisions, including required neighbors. Preview the concrete effect before sending; stale revisions produce the existing domain conflict. A restored record is a new revision.

## Query, layout and accessibility

The query editor calls `QueryComplete` with the current text and caret position. Suggestions carry server-defined replacement spans and cover fields, operators, values, IDs and relation types. Apply only a response for the matching project/text/caret generation. Arrow keys choose suggestions, Enter accepts a suggestion, Escape dismisses and Tab preserves ordinary focus traversal. Submitting the query remains explicit. Expose the popup as a combobox/listbox with an active descendant and announce diagnostics without moving focus.

Navigation shortcuts write a complete, visible query and use the same submission path. Result rows expose selection and status. Keyboard navigation stays within the visible page, with an explicit path into detail and back. Splitters support pointer and keyboard resizing with minimum widths. At narrow widths, stack or switch pane presentation while preserving selected item, query and draft; do not keep invisible focusable controls. Long IDs, queries, code and structured values wrap or scroll within their pane.

The results table has a milestone column between Severity and Last modified. Its header shows the milestone icon, named "Milestone" for assistive technology only, and its cells show `M<n>` for members of a milestone and stay empty otherwise; they contain no controls. The grouping checkbox in that header (named "Group by milestone" for assistive technology only; the operator asked for the icon instead of the word) reissues the browse with `ItemOrder.grouped`, so grouping is the server's major sort key and holds across keyset pages: milestone number ascending, then rows without a milestone (milestones themselves included), each group in the selected sort order. Every run of rows is preceded by a group row (the icon and `M<n>`, or the icon and a dash for rows without one) that holds no focusable element, so result keyboard navigation moves between item rows only. Group rows are reconciled by group like item rows, so live updates neither duplicate nor strand them.

The results table keeps its view state per browser: the sort field, direction and grouping mode are stored in local storage under `cq-items-view` as the `ItemOrder` JSON and applied to the first browse request after a reload. An unreadable or outdated value yields ID ascending without a notification and is replaced by the next change; a failed write is reported like a failed pane-layout write.

Top-bar metrics identify their scope and last successful observation. Loading and stale values are labeled; missing counters are not rendered as zero. Forms expose labels, validation errors and pending-save state. Draft notices identify the project/item they belong to.

## Usage and synchronization

Usage has its own project cursor. Item change events cannot establish that usage is current, and usage updates must not create item events. Use bounded cursor invalidation for the subscribed project and fetch only the currently displayed usage scope. Reconnect refreshes both snapshots. Guard all usage responses against project/scope/request changes; an obsolete reply cannot change a caption or cursor.

Extend the generated WebSocket protocol with a project usage watch and a notification containing its subscription identity, project and usage cursor. Keep this watch independent of query validity and item pagination. Each connection owns at most one usage watch, replacing it on project change; teardown releases it. The existing server poll loop reads the indexed project usage clock through an authorized application/service boundary, sends an initial cursor and then only changed cursors. It does not compute summaries or scan observations on each tick. Scope/credential failures stop the watch with an explicit typed error. The browser coalesces notifications while one scoped summary request is in flight, compares the returned cursor with the latest notification and fetches again if needed. Display the last successful observation time and stale/loading state. Verify the generated frames, authorization, bounded read, reconnect and upload/outcome-correction behavior; no role MCP operation or harness dispatch contract needs to change.

Keep the accounting interpretation from [usage-audit.md](usage-audit.md): direct, shared and unattributed totals are distinct; shared runs are counted once; missing counters, estimates and actual billing remain distinct. Drill-down identifies frozen execution membership, attempt outcomes, correction history and optional raw evidence. Discover session/cohort links from recorded attempts rather than requiring users to invent identifiers.

## Connection behavior

Follow the requested skill and retain bounded resources: at most two live/replacement connections, finite jittered retries, nonce-correlated liveness and a stale grace period. Hidden/frozen/offline states defer work honestly; resumption rechecks liveness. Close codes distinguish retryable failures from stopped states. Destroying a manager prevents late callbacks and reconnects.

The stable compact indicator shows text as well as color and a deadline bar derived from the actual phase start/duration. Expanded diagnostics show each connection, the active connection, uptime, in-flight heartbeats, loss/RTT windows, backoff, last close and a bounded timestamped event log. Rendering must not rebuild open controls on every tick. Background timer throttling is an explicit browser constraint, not evidence of continuous monitoring while suspended.

## Browser verification plan

Run a focused browser gate against the real server and isolated PostgreSQL, preserving a Playwright trace, assertions, console errors and desktop/narrow screenshots. It must not launch model consumers or unrelated supervisor/process fixtures.

| Behavior | Observable check |
| --- | --- |
| Late replies | Delay A's actual WebSocket detail/history/usage replies, select B, release A; every panel stays on B. Repeat across projects and two requests for the same scope. |
| Live editing | Keep local text and original base revision while another client edits the record; saving returns a conflict without changing the concurrent revision. |
| Draft navigation | Change selection/project and reload with a draft; explicitly resume the correct project/item draft and preserve its text/base. |
| Lost acknowledgement | Commit a mutation, suppress its reply and disconnect; retry after reconnect/reload, observing one write and one resulting revision. |
| Search resnapshot | Invalidate a paginated search cursor with a concurrent mutation; the client restarts from a fresh snapshot without retaining mixed pages. |
| Completion | Exercise each suggestion kind, caret replacement in the middle of a query, keyboard acceptance/dismissal, stale completion replies and positioned syntax errors. |
| History/relationships | Add/remove a relation, follow it, restore an older revision and reject a stale preview; verify exact revisions/history through the API. |
| Usage | Upload and correct usage while an item stays unchanged; observe updated scoped totals/cursor, shared references and audit pages, with the exact item revision/history preserved. |
| Connection | Delay/drop/duplicate/out-of-order heartbeats, recover an old connection during replacement, exercise terminal close/manual retry and lifecycle resumption; enforce resource bounds and truthful UI states. |
| Layout/accessibility | Use keyboard alone, resize panes, inspect focus and scrolling at desktop/narrow widths, and check long content for horizontal page overflow. Review retained screenshots. |

## Preparation observations

Source inspection at `feffdc6` found a minimal three-column layout, generic typed forms, history display, project/task usage and nonce-based recovery. Completion popups, relationship/restore actions, cohort/session navigation, independent usage invalidation and full connection diagnostics are absent. Detail/history/usage request guards appear insufficient for rapid same-project selection changes; these are hypotheses until the delayed-reply checks reproduce them. No M5 behavior is claimed verified by this inspection.

Independent Astra approved this implementation plan with no blocking/major finding. Its minor request to specify usage invalidation is addressed by the explicit watch contract above. This is design approval only; browser execution and visual evidence remain pending.
