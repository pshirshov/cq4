# All remaining defects — 2026-09-29

The user authorized all remaining defects, superseding the earlier D25–D27 exclusion. The live intake contains D25, D26, D27 and D66, all Open. Release acceptance remains pending; no record is closed by this source increment.

Evidence root: `/srv/nvme/tmp/cq4-all-defects-20260929`.

## Reproductions and corrections

| Record | Observed failure | Correction and verification |
| --- | --- | --- |
| D26 | Keyboard guidance was in navigation; the status bar contained no keycaps. | Guidance now uses outlined, accessible keycaps in the persistent footer. Browser checks cover desktop and 920/390/320-pixel layouts. |
| D27 | The navigation separator changed width from 200 to 216 pixels. | Navigation has a fixed desktop width; results/detail pointer and keyboard resizing and orientation persistence remain. |
| D66 | The new service test fails because bare/empty terms suggest actual items. | Items are offered only for `id:` and relationship values. Direct ID suggestions respect archive scope; relationship targets may be archived. Archive filtering precedes the suggestion limit. |
| D25 | Original dependency initialization emits Unsafe warnings. A repeated-access probe of the previously rejected Sloth transformation succeeds once, then hangs. | Investigation continues. No dependency transformation or warning suppression is adopted. |

`layout-before.json` and the corresponding screenshot capture D26/D27 before correction. `completion-before.log` captures the expected failing D66 assertion. `scoped-1` passes completion/parser checks against dummy and PostgreSQL repositories, workspace/query/redesign browsers, and all 309 character-prefix checks. A subsequent browser case also checks selecting an archived suggestion and finding it; it passes in `scoped-3`. The first attempt (`scoped-2`) used an incomplete expected button label; the retained trace already shows the correct archived item response.

Astra's source review requested an access-bound measurement. `archive-before` confirms that archived completion scans 100,010 rows to return three items. `archive-after` uses the new `(project_id, archived, display_id)` index and visits three rows for either scope. The actual HTTP access fixture now includes opposite-scope populations; the full gate passes in `access`, including all existing workloads at 100/10,000/100,000 rows. The new actual completion requests visit 7/8 rows across two statements (including project lookup), within the existing 64-visit budget. The current schema is edited in place at version 0.1.0. Updating the operator database requires the reviewed backup-preserving update procedure before launching the new artifact.

## D25 investigation

`repeated-lazy.json` and `astra-d25/fields-after-first.json` establish the rejected transform's failure: String's initializer reads its own lazy cell but uses Boolean's VarHandle for CAS. The first call leaves String null and writes String's codec into Boolean; later reads spin. Sloth's positional offset mapping loses the mapping detected from bytecode.

A scratch correction uses detected mappings and rejects ambiguity. It exposes a second defect: the detector mistakes storage names containing `bitmap` for actual bitmap fields. Narrowing that match is under evaluation. JDK 25 classfiles also exceed this tool's pinned ASM reader; transformation is being retried on the pinned Nix graph's JDK 21, followed by execution on CQ's JDK 25. The 33-jar corpus passes repeated Logstage access and CQ startup with Unsafe denied; a separate Scala 3.3.8 fixture passes repeated, independent, concurrent, null and exception/retry initialization. These are scratch results; production integration remains pending. Original sources, failed outputs and logs are retained. Application dependency versions remain unchanged.

Astra independently approves the D26/D27/D66 source increment after inspecting `scoped-3` and the access report, with no remaining blocking or major findings in that scope. Native delivery and the guarded operator schema update remain pending.

## Remaining delivery work

- Establish and verify a safe D25 correction, including repeated and concurrent lazy initialization.
- Build and verify the native/package candidate and appropriate consumer coverage for dependency changes.
- Rehearse the precise operator update, obtain independent delivery review, install through the host boundary and verify the running artifact.
- Resolve defect records only after verified delivery, retaining citations and history.
