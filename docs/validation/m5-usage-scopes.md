# M5 usage scope navigation

The browser's usage scope is independent of the selected item. Recorded attempts expose session, cohort execution and member-task links, along with their immutable assignment identity and frozen membership. Selecting a usage scope preserves the item view; the selected-item and project actions return to those summaries. Ordinary same-item refreshes retain the explicit scope. Scope changes invalidate both summary and audit requests before loading new data.

Direct, shared and unattributed totals remain separate. Shared assignments are identified, with an explicit label if the bounded identity list is truncated. Shared costs/tokens are not divided among members. Existing missing-measurement, estimate, cost-basis, attempt/outcome and audit views use the selected filter.

## Evidence

`./dev/check ui` passes at `.work/evidence/20260928T031445-ui`. All three changed implementation/fixture files match its source manifest. The actual Chromium/server/PostgreSQL fixture creates two task records and three observed usage contributions: 100 shared tokens across both tasks, 40 direct tokens for T1 in the same cohort execution, and 7 unattributed tokens outside that execution. Each has a distinct recorded session.

| Scope reached through the browser | Direct | Shared | Unattributed |
| --- | ---: | ---: | ---: |
| Project | 40 | 100 | 7 |
| Shared attempt's session | 0 | 100 | 0 |
| Cohort execution | 40 | 100 | 0 |
| T1 | 40 | 100 | 0 |
| T2 | 0 | 100 | 0 |

The T2 audit contains the shared observation and excludes the direct observation. Navigation retains the selected T1 detail. A held session-summary reply arrives after a scope-only switch back to project usage; after actual delivery and request settlement it cannot overwrite the newer caption or totals. Authoritative item views are unchanged. The fixture has no page errors; the full retained graph/query/draft/connection/browser corpus also passes.

`usage-scopes-results.json` retains fixture identities and assertions; `usage-scopes.zip` retains the trace. Visual inspection of `usage-scopes.png` confirms project scope, accounting cursor, separated totals and unknown costs. Independent Astra approved the final source and evidence with no blocking or major finding. Independent usage invalidation and full M5 acceptance remain open; this increment does not change harnesses, generated contracts or services and does not launch model consumers.
