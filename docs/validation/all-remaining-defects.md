# All remaining defects — 2026-09-29

The user authorized all remaining defects, superseding the earlier D25–D27 exclusion. The live intake contains D25, D26, D27 and D66, all Open. Release acceptance remains pending; no record is closed by this source increment.

Evidence root: `/srv/nvme/tmp/cq4-all-defects-20260929`.

## Reproductions and corrections

| Record | Observed failure | Correction and verification |
| --- | --- | --- |
| D26 | Keyboard guidance was in navigation; the status bar contained no keycaps. | Guidance now uses outlined, accessible keycaps in the persistent footer. Browser checks cover desktop and 920/390/320-pixel layouts. |
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

## Remaining delivery work

- Build and verify the native/package candidate and scoped runtime coverage; dependency/harness implementations are unchanged.
- Rehearse the precise operator update, obtain independent delivery review, install through the host boundary and verify the running artifact.
- Resolve defect records only after verified delivery, retaining citations and history.
