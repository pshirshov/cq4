# All remaining defects — 2026-09-29

The user authorized all remaining defects, superseding the earlier D25–D27 exclusion. The live intake contains D25, D26, D27 and D66, all Open. Release acceptance remains pending; no record is closed by this source increment.

Evidence root: `/srv/nvme/tmp/cq4-all-defects-20260929`.

## Reproductions and corrections

| Record | Observed failure | Correction and verification |
| --- | --- | --- |
| D26 | Keyboard guidance was in navigation; the status bar contained no keycaps. | On desktop, guidance uses outlined keycaps with spoken key names in the persistent footer. The narrow layout (≤920px) hides the guidance to keep D50's compact status bar; see the status-bar conflict below. Browser checks cover desktop and 920/390/320-pixel layouts. |
| D27 | The navigation separator changed width from 200 to 216 pixels. | Navigation has a fixed desktop width; results/detail pointer and keyboard resizing and orientation persistence remain. |
| D66 | The new service test fails because bare/empty terms suggest actual items. | Items are offered only for `id:` and relationship values. Direct ID suggestions respect archive scope; relationship targets may be archived. Archive filtering precedes the suggestion limit. |
| D25 | Original dependency initialization emits Unsafe warnings. A repeated-access probe of the previously rejected Sloth transformation succeeds once, then hangs. | Following the user's `.jvmopts` simplification, suppress the warning through the supported JVM option. The dependency still uses deprecated calls; no bytecode transformation or dependency upgrade is adopted. |

`layout-before.json` and the corresponding screenshot capture D26/D27 before correction. `completion-before.log` captures the expected failing D66 assertion. `scoped-1` passes completion/parser checks against dummy and PostgreSQL repositories, workspace/query/redesign browsers, and all 309 character-prefix checks. A subsequent browser case also checks selecting an archived suggestion and finding it; it passes in `scoped-3`. The first attempt (`scoped-2`) used an incomplete expected button label; the retained trace already shows the correct archived item response.

Astra's source review requested an access-bound measurement. `archive-before` confirms that archived completion scans 100,010 rows to return three items. `archive-after` uses the new `(project_id, archived, display_id)` index and visits three rows for either scope. The actual HTTP access fixture now includes opposite-scope populations; the full gate passes in `access`, including all existing workloads at 100/10,000/100,000 rows. The new actual completion requests visit 7/8 rows across two statements (including project lookup), within the existing 64-visit budget. The current schema is edited in place at version 0.1.0. Updating the operator database requires the reviewed backup-preserving update procedure before launching the new artifact.

## D25 investigation

`repeated-lazy.json` and `astra-d25/fields-after-first.json` establish the rejected transform's failure: String's initializer reads its own lazy cell but uses Boolean's VarHandle for CAS. The first call leaves String null and writes String's codec into Boolean; later reads spin. Sloth's positional offset mapping loses the mapping detected from bytecode.

The transformation investigation reproduced additional Sloth defects: ordinary storage names containing `bitmap` were misclassified, and nested objects without a static initializer received uninitialized handles. A corrected 33-jar corpus and concurrency/null/retry proofs passed, but integrating it exposed additional cache boundaries and unsupported ScalaTest transformations. The entire attempted production wiring is retained in `rejected-production-transform`; it has been removed from the build. Earlier failures and passing reductions remain evidence, not a shipped remedy.

The user then proposed `.jvmopts` to simplify D25. The current approach puts `--sun-misc-unsafe-memory-access=allow` there and forwards those lines to sbt's forked JVMs through `ThisBuild / javaOptions`. Application/toolchain versions and dependency bytes remain unchanged. This supersedes the prior plan's requirement to remove calls without suppressing their warning: it resolves the reported startup noise, while dependency removal remains upstream work. The current pinned JDK supports the option; this makes no promise about future Java versions.

`jvmopts-comparison.json` records the same original CQ classpath exiting successfully both ways: warning present by default, absent with the configured option. `jvmopts-sbt.log` records successful `server/run --help` and `server/runMain cq.server.Main --help` without the warning and confirms propagation to test JVM options. Raw Java invocations must pass the option explicitly, as documented in README; native packages need no JVM option. sbt reads `.jvmopts` for its own process ([sbt 2 documentation](https://www.scala-sbt.org/2.x/docs/en/reference/sbt.html)); forked applications receive `javaOptions` ([forking documentation](https://www.scala-sbt.org/1.x/docs/Forking.html)).

The existing native applicability comparison now includes `.jvmopts`. Its prior omission is reproduced in `jvmopts-provenance-before.log`; the corrected release checks pass in `jvmopts-provenance-after.log`.

An additional D66 regression was reproduced in `group-before-2`: `id:` offered T1, while `(id:` and `((id:` offered nothing. `group-after` passes. Completion-only expression parsing now accepts unfinished groups for archive-scope analysis. Submitted queries retain strict parentheses validation. Dual-service coverage includes nested and negated archive scopes.

Astra independently approves the D26/D27/D66 source increment after inspecting `scoped-3` and the access report, with no remaining blocking or major findings in that scope. Astra also approves the simplified D25 configuration and D66 unfinished-group correction. `scoped-4` passes the final dummy/PostgreSQL/parser checks, workspace/query/redesign browser checks and 309 character-prefix cases. Native delivery and the guarded operator schema update remain pending.

D25's expected behavior was revisionally updated to record the user's simpler warning-policy requirement (`d25-scope-before/request/result/after.json`, revision 3, still Open). The earlier requirement remains in history.

The combined scoped sbt invocation reported six parser tests and only six shared service tests. Separate forks in `dual-completion-2` explicitly pass all six completion tests once with the dummy repository and once with PostgreSQL; the suite identity and production repository wiring are retained in each log. The first follow-up wrapper expected a nonexistent dummy implementation class name; its six actual tests passed, and the corrected wrapper verifies the named suite and repository distinction.

## Relocation and final review corrections

On 2026-09-29 at 16:15 BST the checkout moved from `/home/pavel/work/safe/cq4/cq4` to `/home/pavel/work/safe/flakes/cq4` while the native gate `20260929T144308-native` was running. That gate failed on the missing directory, and the candidate pipeline stopped with it. Their logs are retained as `native-final-relocation-failed.log` and `candidate-pipeline-relocation-failed.*`. README, quickstart and interactive guidance now use the new path; historical records keep the paths they observed. The git-excluded harness configurations (`.mcp.json`, `.codex/config.toml`, `.pi/extensions/cq-host.json`, `.local/interactive/settings.json`) still name the old path and must be regenerated with `cq configure` after installation.

The relocation exposed a non-hermetic build task. `server/runtimeClasspath` returned a cached `String` of absolute paths. When the inputs matched an earlier build at the old location, sbt's disk cache returned old-path jars, and the JVM server failed with `ClassNotFoundException: cq.server.Main` (`keycaps-before-stale-classpath`). A minimal sbt 2.0.9 build reproduced the same stale-path behavior, and it disappears with `Def.uncached`. sbt's built-in classpath keys relocate correctly, so this is not an upstream defect and nothing was filed. The key is now `Def.uncached`.

Astra was not reachable from this session. Final review instead used a separate read-only Claude reviewer; this is not an Astra approval. Its source/updater review (verdict: approve with minor findings) led to these corrections:

- **D66 regression in the increment.** Archive-scope analysis re-parsed the whole query, so an unfinished part after the cursor (for example editing `T4` in `id:T4 AND `) removed every ID suggestion. The new cursor-positioned test cases failed on both repositories before the correction (`suffix-before`). If the whole query cannot be parsed, the scope now comes from the text up to the value; if that also fails, it uses the language's default active scope. `suffix-after` passes six Dummy and six PostgreSQL tests, and `scoped-5` passes parser, service, browser and 309 prefix checks. Residual: text before the value that cannot be parsed makes archived IDs unavailable until it is corrected, and some tautological expressions (such as `archived:true OR id:`) conservatively return all scopes.
- **D26 accessibility.** Keycap names were `aria-label`s on generic elements. A standalone Chromium probe of the original markup presents `↵ select Esc return` in the ARIA snapshot (`keycaps-aria-before`). The workspace browser run before the correction (`keycaps-before`) failed on the new structural assertion and never reached the ARIA-snapshot assertion. The glyph is now hidden from assistive technology, the spoken key name is visually hidden text, and the guidance is a named group.
- **Updater.** Recovery now decides which package renames to undo from the filesystem, not from flags that a signal could interrupt. SIGHUP is handled like SIGINT/SIGTERM. An HTTP error response from the candidate fails immediately instead of being retried as a startup delay. The reviewed pre-correction script is retained as `update-local-reviewed-design.py`.

### Status-bar conflict (D26 against D50) — `7d5a0a9`

The native browser suite exposed a conflict with the delivered D50 check: the status bar must stay at most 48px tall. With D26's guidance in it, the footer measured 41px on desktop, 64px at 390px and 83px at 320px (`footer-before`). Two alternatives did not fit:

- A single scrolling guidance line kept the footer at 45px without a selected project (`footer-after`). With a selected project, however, the data/usage metrics take two rows at 390px, so the check still failed (`scoped-7`).
- Containing the visually hidden key names also required care: without it, clipped keycaps caused page overflow (`scoped-6`).

The narrow stacked layout (≤920px) therefore hides the keyboard guidance. Desktop keeps D26's full outlined keycaps. `scoped-8` passes the workspace (including an assertion that narrow layouts hide the guidance), query, redesign, prefix and D50 polish suites. This is a scoped product decision, open to the user's override.

### Native, package and updater evidence

- The first native gate at `07acd60` (`gates/20260929T153724-native`) completed fresh tracing and every non-browser native fixture. Its browser launch failed because the gate was started outside the dev shell: `java`/`sbt` were already on PATH, so `dev/check` skipped its `nix develop` re-exec and Playwright lacked `PLAYWRIGHT_BROWSERS_PATH`.
- The resumed browser suite (`...-native-resumed`) then failed on the D50 check described above.
- `gates/7d5a0a9-native-web` rebuilt the binary from the base gate's hash-checked trace snapshots. The runtime delta is only `web/style.css`. On the rebuilt binary it passes native transport and artifacts, the complete browser suite, the HTTP browser and project archives.
- At the user's direction, the installed-package check is scoped (`gates/installed`, `package-check-scoped.py`): the relocated package with hidden sources and classpath, transport, completion, prefix and HTTP browser checks. It passes. The full installed corpus and the paid harness matrix were not rerun.
- `operator-rehearsal` passes the exact old/candidate updater pair, data and credential preservation, and separate restoration of the backup.
- The first rollback rehearsal (`operator-rollback-session-mismatch`) did roll back correctly. Its old-package restart verification failed because it used a new CQ session, while idempotency is scoped to the session's actor. The rehearsals now share the seed session. `operator-rollback` passes: an injected failure at the candidate rename restores the database rows, the old schema and index inventory, and a runnable old package.
- Candidate manifest: `33245e5e4b41c98160448b45d1f4934656b167486568674c43817b7bea3bfd86`. The pinned host wrapper is `/tmp/exchange/cq-all-defects-update.sh`.

Residual hypotheses, disclosed and not corrected:

- A signal that lands between `Popen` returning and its assignment could orphan the candidate smoke-test server. This is a bytecode-sized window.
- Short landscape phone viewports were not measured.
- The fingerprint logs contain the same row data as the protected backup in the same 0700 evidence directory.

## Remaining delivery work

The final independent delivery review (read-only Claude reviewer, not Astra) approves this candidate and wrapper with minor findings. It recomputed every hash in the chain: the wrapper pins, both manifests and their files, the schema checksums at `df19bdd` and `7d5a0a9`, and the binary against native evidence. Its disclosed residuals:

- The rollback rehearsal covers only the failed candidate rename. The both-renamed and signal paths are verified by reading only.
- An interruption between creating the recovery marker and entering the updater's recovery block leaves a marker that requires manual inspection.
- A connection reset during candidate startup polling causes a spurious (safe) rollback.
- `bin/cq-guardian` embeds a checkout-relative library path, and `examples/supervisor.json` names the pre-rename candidate path. Both are pre-existing packaging properties.

## Installation and closeout

The operator ran the pinned wrapper on the host. All four pinned hashes checked, and `operator-update/receipt.json` records `installed`:

- old manifest `27608b97…` → new manifest `33245e5e…`
- schema checksum `88fefc58…` → `237d7df2…`
- existing table data unchanged, with the backup and the `release-before-all-defects` rollback package retained
- recovery marker cleared, and the launcher reported `CQ ready` at `http://vm.home.7mind.io:8080`

Actual-hostname checks:

- `operator-completion-after.json` passes. Empty and bare terms offer no items; `id:`, `(id:` and `((id:` offer only the active D25/D26/D27/D66; `archived:true` and `archived:all` scopes and archived relationship targets behave as specified.
- The first run (`operator-completion-after-lowercase-not.json`) used lowercase `not`, which the grammar treats as a text term; submitted search agrees. The corrected `NOT` case passes.
- `operator-live-after.json` passes in the browser: footer keycaps, fixed navigation with results resizing, completion behavior and narrow layouts. Screenshot: `operator-live-after.png`.
- The pre-install baselines (`operator-completion-before.json`, `operator-live-before.json`) failed on the old package.

`closeout.py` resolved D25 (revision 4), D26 (2), D27 (2) and D66 (3) in one change, with model-declared delivery descriptions and commit/file citations. Before/request/result/after records are retained. D25's resolution states that it is a warning-suppression mitigation and that the deprecated dependency calls remain. After closeout, the live project has no Open defects. Human release acceptance remains pending.

The git-excluded harness configurations still name the old checkout path; regenerate them with `cq configure`.

## D25 reopened, 2026-09-30

The D25 closeout above claimed that native packages need no JVM option; that was never tested and was wrong. The installed native executable still printed the warning, so D25 was reopened at revision 7. The correction and its evidence are recorded in [the lazy-values investigation](scala-lazyvals-warning.md#native-correction-2026-09-30) and [the session/UI delivery](session-ui.md).
