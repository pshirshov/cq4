# Evaluation polish: D43–D50

Implementation in progress. Evidence: `/srv/nvme/tmp/cq4-evaluation-polish-20260929`.

## Scope and checks

1. Whole-row selection, sortable last-modified column, square controls → actual Chromium selection, keyboard and sorting checks.
2. Stable query completion popup and complete partial-prefix handling → character-by-character API/browser cases, positioned genuine errors and stale-reply checks.
3. Archive terminal items matching the current applied filter → explicit snapshot preview, exact revision validation, atomic mutation, retained request replay; shared dummy/PostgreSQL contracts and actual browser checks.
4. Enforce terminal-only archival on current drafts and backup restore → mutation/import regression checks; preserve historical revisions. Revisioned unarchive repairs existing Open archives without changing their status.
5. Semantic attempts, outcome history and audit tables → actual accounting scope, live updates, unknown/partial usage and navigation checks.
6. Independent Astra review/corrections, scoped native delivery and rehearsed update. No paid cross-harness matrix: harness execution is outside this change.

## Reproductions before correction

- `before/polish-results.json`: all eight checks fail on the installed package. Clicking cell padding does not select; Modified and bulk archive are absent; the header grows from 86 to 207 px during partial query entry; suggestion count is visible; controls have 4 px rounding; the API accepts an archived Open defect.
- `prefix-before/query-prefix-results.json`: 13 of 309 prefixes lose hints while typing quoted JSON escapes or an escaped surrogate pair.
- `archive-before.log`: a checksummed backup with an archived Ready task is accepted by the old restore path.
- `usage-before.json`: Attempts contains five raw JSON blocks and no table; Usage audit contains a raw JSON block and no table.
- `archived-before.json`: D13–D20 are Open and archived. Revisioned repair now passes: `archive-repair-before.json`, `archive-repair-result.json`, `archive-repair-after.json`. Only the archive flags changed; exact retry returns the same acknowledgement. Delivery remains pending.

D25 remains a dependency blocker; D26/D27 remain reserved for CQ trials. The overall release still awaits designated human acceptance.

## Verification in progress

- `current-2/domain.log`: 104 focused dummy/PostgreSQL/parser scenarios pass. Legacy archived fixtures now use terminal status; their original query/traversal assertions are retained.
- `browser-1`: 309 API and 309 browser prefixes pass; all nine polish checks pass; whole-query table checks pass. Archive checks pass delayed query/project invalidation, all-or-none stale-member refusal, replay after committed-but-unacknowledged response and a capped 512-member batch with the 513th untouched.
- The broader browser corpus reproduced a new overlay defect: a late submitted-query diagnostic reopened while the input was blurred and intercepted New item. Correction restricts popup display to the focused input; verification is pending.
- TypeScript type checking passes. Astra source review reports no blocking/major findings; complete source/native delivery approval remains pending.

`order/browse-contract.log` adds six passing dummy/PostgreSQL ordering scenarios, including unequal timestamps (9 vs 100) and pagination after a later revision. The first contract-generation/browser-3 attempts were invalidated by overlapping generation-dependent jobs: generated files were removed while another compiler was reading them. Their failed logs remain; generation-dependent gates are now sequenced. These are check orchestration failures, not product assertions.

The existing live-usage replacement fixture assumed the new connection always wins. Repeated current-JVM failures and a passing installed-native comparison prompted a narrower probe. Diagnostics confirm the old connection can win its heartbeat race and close the candidate as `Superseded`, while remaining healthy. The fixture now controls Pong delivery to verify both winners: `live-both-winners-2/usage-live-results.json` passes both routes, query independence and coalesced updates. Its usage lifecycle check also passes corrections, outcome history, exact costs and unchanged item revisions. Connection-manager code is unchanged. Earlier fixture-selector and nullable catalogue-watch mistakes are retained in the intermediate logs.

`contracts-resumed/result.json` passes TypeScript type checking, 504 schema definitions, seven MCP capabilities and Scala/TypeScript round trips. Its generation proof matches every current generated file to the earlier successful identical-generation comparison; it resumes the interrupted gate without regenerating beneath active compilers.

The complete JVM project-archive fixture passes at `live-race-proof-2/archive-project-archives/result.json`, including rejected nonterminal current records, exact snapshot restoration, corruption/scope/collision rejection and uncertain commit acknowledgement. Astra independently approves the source increment with no blocking/major findings. This approval excludes pending native/package/operator delivery and human acceptance.

## D50: data and usage status placement

The follow-up report is recorded as D50. `status-before/placement.json` and
`status-before.log` reproduce data/usage/freshness inside the search header
(y=65–79 at 1366×768) and fail the expected footer assertion. The correction
moves the existing live elements into a compact footer. Usage text can truncate
with its complete text available on hover; freshness remains distinct. D26's
keyboard hints and D27 remain reserved.

`status-after-layout.log` passes at 1366×768, 1280×720 and 390×844, including
stale/reconnect updates. These are read-only checks against the actual hostname
with the new generated JavaScript/CSS supplied through browser routes; they do
not claim installation. TypeScript passes. The 1280×720 screenshot was visually
inspected. The native gate already in progress still builds the preceding UI;
the final artifact will explicitly reuse its unchanged JVM tracing evidence,
rebuild the embedded assets and run new browser/installed checks.

Astra independently approves D50's source/layout and the scoped trace-reuse plan.
On narrow screens the footer follows page content; laptop layouts anchor it at
the viewport bottom. Rebuilt artifact and installed verification remain pending.
