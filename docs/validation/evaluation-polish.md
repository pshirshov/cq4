# Evaluation polish: D43–D50

Source, native, relocated package and actual-hostname verification pass. The package is installed; D43–D50 are Resolved at revision 2. Evidence: `/srv/nvme/tmp/cq4-evaluation-polish-20260929`.

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
- `archived-before.json`: D13–D20 are Open and archived. Revisioned repair now passes: `archive-repair-before.json`, `archive-repair-result.json`, `archive-repair-after.json`. Only the archive flags changed; exact retry returns the same acknowledgement.

D25 remains a dependency blocker; D26/D27 remain reserved for CQ trials. The overall release still awaits designated human acceptance.

## Source verification

- `current-2/domain.log`: 104 focused dummy/PostgreSQL/parser scenarios pass. Legacy archived fixtures now use terminal status; their original query/traversal assertions are retained.
- `browser-1`: 309 API and 309 browser prefixes pass; all nine polish checks pass; whole-query table checks pass. Archive checks pass delayed query/project invalidation, all-or-none stale-member refusal, replay after committed-but-unacknowledged response and a capped 512-member batch with the 513th untouched.
- The broader browser corpus reproduced a new overlay defect: a late submitted-query diagnostic reopened while the input was blurred and intercepted New item. Correction restricts popup display to the focused input; the resumed and final native browser corpora pass.
- TypeScript type checking passes. Astra source review approves the implementation; final delivery evidence is below.

`order/browse-contract.log` adds six passing dummy/PostgreSQL ordering scenarios, including unequal timestamps (9 vs 100) and pagination after a later revision. The first contract-generation/browser-3 attempts were invalidated by overlapping generation-dependent jobs: generated files were removed while another compiler was reading them. Their failed logs remain; generation-dependent gates are now sequenced. These are check orchestration failures, not product assertions.

The existing live-usage replacement fixture assumed the new connection always wins. Repeated current-JVM failures and a passing installed-native comparison prompted a narrower probe. Diagnostics confirm the old connection can win its heartbeat race and close the candidate as `Superseded`, while remaining healthy. The fixture now controls Pong delivery to verify both winners: `live-both-winners-2/usage-live-results.json` passes both routes, query independence and coalesced updates. Its usage lifecycle check also passes corrections, outcome history, exact costs and unchanged item revisions. Connection-manager code is unchanged. Earlier fixture-selector and nullable catalogue-watch mistakes are retained in the intermediate logs.

`contracts-resumed/result.json` passes TypeScript type checking, 504 schema definitions, seven MCP capabilities and Scala/TypeScript round trips. Its generation proof matches every current generated file to the earlier successful identical-generation comparison; it resumes the interrupted gate without regenerating beneath active compilers.

The complete JVM project-archive fixture passes at `live-race-proof-2/archive-project-archives/result.json`, including rejected nonterminal current records, exact snapshot restoration, corruption/scope/collision rejection and uncertain commit acknowledgement. Astra independently approves the source increment with no blocking/major findings. This source-stage approval covered implementation; final package/operator verification is recorded below. Human acceptance remains separate.

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
inspected. The native gate then in progress built the preceding UI. The final
artifact below explicitly reuses its unchanged JVM tracing evidence, rebuilds
the embedded assets and passes new browser/installed checks.

Astra independently approves D50's source/layout and the scoped trace-reuse plan.
On narrow screens the footer follows page content; laptop layouts anchor it at
the viewport bottom. Rebuilt artifact and installed verification results follow.

## Native verification continuation

The first native run completed tracing, compilation and runtime checks, then
failed three delayed-audit selection assertions because the test still searched
for JSON `<pre>` blocks. `gates/20260929T084029-native/selection-results.json`
retains the failure. The fixture now checks the semantic Input column with the
same held replies and expected values (33, 22, 11); all eleven selection cases
pass in `gates/native-runtime-resumed/selection-results.json`.

The continuation retains the exact executable and earlier passing receipts,
reruns the browser corpus and finishes HTTP/archive verification. The failed
original is preserved. Its first preflight also rejected an incorrect assumption
that the failed browser command was last: successful database cleanup followed
it. Receipt selection was corrected explicitly. The original executable source
manifest is separate from the current verifier manifest; supplemental capture
after launch is disclosed and verifies that all files predate launch, then
requires unchanged hashes at completion. D50's build waits for that proof.

## Final artifact and delivery checks

- Source commits: `51c910e` (D43–D49), `9d542f2` (D50), `b7ea94b`
  (semantic audit regression selector), `49d0bee` (D50 in the regular polish suite).
- `gates/native-runtime-resumed/result.json` passes the retained original ELF
  (`9d06f7fd…`). `source-epochs.json` binds all 169 runtime inputs to `51c910e`;
  `verification-provenance.json` confirms 346 verifier inputs and the runner
  remain unchanged through completion. Astra approves the qualified supplemental
  capture; it does not claim a pre-launch hash capture.
- `gates/statusbar-native/result.json` passes the final UI rebuild. Its
  `trace-reuse.json` proves the runtime delta is exactly `web/src/app.ts` and
  `web/style.css`, retains immutable metadata hashes and identifies inherited
  tracing. Fresh transport, full browser, 309 API/browser prefixes, exact archive
  preview/retry, HTTP and status-bar stale/reconnect checks pass. The regular
  polish suite has ten passing cases. Its D50 fixture wiring was added after
  build-manifest capture but before invocation; `statusbar-fixture-epoch.json`
  records this explicitly. No paid harness evaluations were rerun.
- Installed package: `.local/release` (prepared as `.local/release-polish`), model version **0.1.0**. Manifest SHA-256:
  `403f5896da455213f3d2709b27a355b337b307282540f509686ec6bf7153041e`.
  Executable SHA-256:
  `f1e58656d85a4805404408d9eef5eebd899064396b6280ed023b5968952eb92a`.
- `operator-rehearsal/result.json` passes the exact old/new package updater,
  native persistence and severity checks. All 24 backed-up data tables restore
  to identical fingerprints. Data, schema and credentials remain unchanged.
  The updater retains the previous package and refuses an active launcher or
  database; it does not signal host processes.
- Source-isolated relocated runtime, browser and restart checks pass at
  `gates/20260929T092944-installed`. Its final backup fixture then attempted to
  archive a Ready task and was correctly rejected. The failed result remains.
  The fixture now marks its seeded Task Done before archiving. The exact same
  package passes the complete backup fixture at `gates/installed-backup-resumed`,
  with fresh runtime import and source isolation. All 25 tables, API reads,
  counters and idempotent acknowledgements survive restoration; verifier inputs
  remain unchanged. The continuation permits only that fixture source delta and
  references the earlier passing receipts. The updater rehearsal's 24 data-table
  count excludes the schema-migration table; the backup fixture includes it.
- The generated host script is `/tmp/exchange/cq-polish-update.sh`, SHA-256
  `5548e5541b37177c07f7e2974939005f1a7a4fa1b2ce2bb6edf556c7688e40f6`.
  Astra approves scoped technical delivery and this wrapper (`final-review.json`).
  Host update, actual-hostname checks and D43–D50 closeout now pass.

## Operator update and closeout

The operator stopped the prior launcher and ran the reviewed one-time
`/tmp/exchange/cq-polish-update.sh` from the host terminal. Its captured output
reports **CQ ready** at `http://vm.home.7mind.io:8080`, listening on
**0.0.0.0:8080**. The environment skill required the host step because the
existing launcher and PostgreSQL process were outside the agent's PID namespace.
The exchange output was read before verification.

- `operator-update/receipt.json`: exact reviewed package installed, database
  schema and all existing data preserved. Installed package files and retained
  backup match their recorded SHA-256 values.
- Backup: `/srv/nvme/tmp/cq4-evaluation-polish-20260929/operator-update/before.dump`.
  Previous package: `.local/release-before-evaluation-polish`.
- `operator-live.json`: actual-hostname login and WebSocket readiness; six table
  columns, padding selection, last-modified sorting, stable partial-query hints,
  square controls, readonly terminal preview excluding Open D17, semantic
  attempts/audit tables and footer geometry all pass. No browser errors, page
  overflow or project mutations. Table and attempts screenshots were inspected.
- `closeout-before.json`, `closeout-request.json`, `closeout-result.json` and
  `closeout-after.json`: D43–D50 Resolved at revision 2, with model-declared evidence
  and implementation citations. D25 remains Open at revision 2; D26/D27 remain
  untouched and Open at revision 1. Human release acceptance remains pending.

Reload the browser once to load the current assets. Future starts use:

```sh
/home/pavel/work/safe/cq4/cq4/run-local.sh
```
