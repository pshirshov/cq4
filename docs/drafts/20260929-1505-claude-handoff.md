# Handoff to Claude — finish delivery of all remaining defects

Snapshot: 2026-09-29 15:05 UTC. The user requested this handoff because the current session is near its context limit. Continue the existing work; implementation is complete, delivery checks are still running. Do not restart the investigation.

## Task and constraints

The user authorized fixing **all remaining defects**, revoking the earlier D25–D27 exclusions. The live intake was D25, D26, D27 and D66. They remain Open until verified installation. Overall human release acceptance is still pending; do not claim that acceptance or mark the original release goal complete.

The latest steering was: “I believe we could just use .jvmopts to simplify this shit?..” We adopted that simplification for D25 and removed the experimental dependency bytecode transformation. The warning is suppressed; deprecated dependency calls remain. Do not describe this as eliminating Unsafe usage or revive the transformation.

- Repository: `/home/pavel/work/safe/cq4/cq4`, branch `main`.
- HEAD: `09692d2` — Simplify JVM warning policy and complete grouped ID hints.
- Previous increment: `8edaae0` — Fix remaining workspace and contextual completion defects.
- Version remains **0.1.0**. No bump unless explicitly requested. Internal compatibility may be broken; reference snapshots must remain unchanged.
- Follow the user's AGENTS.md instructions, especially reproduction before correction, narrow changes and evidence before claims.
- User explicitly requested independent Astra reviews/correction loops earlier. Source and updater design reviews have passed; final delivery review is outstanding.
- Do not run a paid three-harness matrix for this increment: harness implementations, dependencies and protocols are unchanged. Astra approved retaining prior consumer evidence.
- `.gitignore` has a pre-existing user edit: **do not stage, revert or overwrite it**.
- Current additional unstaged files: `docs/validation/all-remaining-defects.md`, `docs/validation/scala-lazyvals-warning.md`, plus this handoff. These are intentional documentation updates.
- Keep `docs/implementation-status.md` and `docs/requirement-coverage.md` current and commit verified increments.

## First action: inspect the running checks

Persistent evidence root:

```
/srv/nvme/tmp/cq4-all-defects-20260929
```

At handoff these two processes are running, with parent PID 2 in this sandbox. PIDs are observations, not durable identities; verify commands before any process action. Leave them running.

| Process | Observed PID | Progress/evidence |
| --- | --- | --- |
| `python3 .../dev/check native` | 3088144 | `native-final.log`; `gates/20260929T144308-native/result.json` |
| `python3 .../finish-candidate.py` | 3090144 | `candidate-pipeline.log`; `candidate-pipeline.json` |

The native gate most recently reached **native-agent-admission** after passing the earlier metadata-collection fixtures. Native compilation and native execution checks follow. CLI metadata fixtures make many JVM invocations and take minutes; lack of top-level log movement alone is not a failure. Inspect the current fixture log and processes before deciding a check is stalled.

Read-only starting commands:

```bash
cd /home/pavel/work/safe/cq4/cq4
git status --short
cat /srv/nvme/tmp/cq4-all-defects-20260929/candidate-pipeline.json
cat /srv/nvme/tmp/cq4-all-defects-20260929/gates/20260929T144308-native/result.json
tail -n 30 /srv/nvme/tmp/cq4-all-defects-20260929/native-final.log
tail -n 30 /srv/nvme/tmp/cq4-all-defects-20260929/candidate-pipeline.log
ps -eo pid,ppid,etime,args | rg 'finish-candidate|dev/check native|native-image|rehearse-update|rehearse-rollback'
```

Current Codex tool-session IDs were 90232 (native) and 27121 (pipeline), but Claude should use filesystem logs and OS process inspection, not assume tool sessions transfer.

`finish-candidate.py` runs automatically after the native gate passes:

1. Package native evidence into `.local/release-all-defects`.
2. Run the full source-isolated `dev/package-check` against that candidate.
3. Record why prior real consumer evidence remains applicable.
4. Substitute the exact candidate manifest hash into `update-local.py`.
5. Run `rehearse-update.py`, including restore of the backup into a separate database.
6. Run `rehearse-rollback.py`, injecting failure at candidate-package rename and verifying old database/package recovery.
7. Create pinned `operator-config.json` and `/tmp/exchange/cq-all-defects-update.sh`.
8. Finish with state `verified-awaiting-review`.

Read that script before modifying/resuming it. **It is not blindly restartable**: it expects one `CANDIDATE_MANIFEST_PENDING` placeholder, and packages/rehearsals create durable directories. If it fails, preserve evidence, inspect completed steps and resume deliberately. A passed native result alone is not delivery completion.

Native source provenance was captured before commit 09692d2, so a baseline label may reference 8edaae0. Exact runtime source hashes, not a commit label, establish applicability. Only documentation changed after the gate started.

## What is implemented and verified

Primary record: `docs/validation/all-remaining-defects.md`.

| Defect | Correction | Main verification |
| --- | --- | --- |
| D25 | `.jvmopts` contains `--sun-misc-unsafe-memory-access=allow`; one build setting forwards its lines to forked JVMs. Raw Java launch documented; `.jvmopts` included in package provenance. | Same original classpath emits warning by default and no warning with the option; successful sbt run/runMain and test-option propagation; provenance failure first, then pass. |
| D26 | Keyboard guidance moved to persistent footer with outlined accessible keycaps. | Before screenshot/reproduction; desktop and 920/390/320-pixel browser checks. |
| D27 | Desktop navigation fixed at 200px. Results/detail resizing and persisted orientation remain. | Before nav drag changed 200→216px; corrected pointer/keyboard/layout browser checks. |
| D66 | No arbitrary item suggestions for bare/empty expressions. `id:` suggestions respect archive scope; relationship targets can include archived items. Filtering happens before the limit. Completion parsing tolerates unfinished groups; submitted parsing remains strict. | Service tests against both dummy and PostgreSQL; actual HTTP before/after grouped queries; 309 character-prefix cases; browser selection; measured archive-query access bounds. |

Important source details:

- `QueryCompleter.scala` computes archive scope by replacing the current value span with T1 and analyzing the expression's possible archive truth under And/Or/Not. Other predicates remain unknown.
- `QueryParser.completionExpression` allows missing closing parentheses at EOF **only for completion**. Execution parsing remains strict.
- `LedgerTransaction.completeItems(prefix, archive, limit)` filters before limiting in both implementations.
- Current `001-ledgers.sql` adds exactly one index:
  `CREATE INDEX cq_items_archive_display ON cq_items (project_id, archived, display_id);`
- Existing operator database therefore needs the guarded index/checksum update **before starting the candidate**.
- `build.sbt` setting: `ThisBuild / javaOptions ++= IO.readLines((ThisBuild / baseDirectory).value / ".jvmopts")`.
- No Sloth dependency, alternate JDK, transform hook, transform cache or transform source file remains wired into production.

Evidence under the persistent root:

- `layout-before.*`, `completion-before.log`: expected failures before correction.
- `archive-before`: 100,010 rows visited for three results. `archive-after`: three rows using the index.
- `access/result.json`: full 100/10,000/100,000-row access gate passes; actual completion requests visit 7/8 rows in two statements, within the existing 64-visit budget.
- `scoped-4/result.json`: TypeScript/build, parser/service and workspace/query/redesign browser checks pass; all 309 prefix cases pass. Desktop and narrow screenshots were visually inspected.
- **Test-count caveat:** combined sbt test selection ran six parser and only six service tests, not both service variants. `dual-completion-2/result.json` explicitly runs separate forks and verifies six Dummy and six PostgreSQL completion tests. Use those logs for the dual-adapter claim. Earlier `dual-completion` wrapper failed on an incorrect concrete dummy class-name assertion, though its tests passed; retained.
- `group-before-2/results.json`: `id:` had a hint, `(id:`/`((id:` did not. `group-after` passes. Earlier `group-before` had an invalid seed fixture, not the target failure.
- `jvmopts-comparison.json`, `jvmopts-sbt.log`: actual warning-policy and fork propagation evidence.
- `jvmopts-provenance-before.log`, `jvmopts-provenance-after.log`: omitted provenance input reproduced and corrected.
- `d25-scope-before/request/result/after.json`: live D25 expected behavior updated at revision 3 to the user's simpler warning-policy requirement, still Open. Original stronger requirement remains in history.

### D25 investigation that must not be resumed

The attempted bytecode transformation found several genuine Sloth defects and later ScalaTest/cache incompatibilities. Its exact attempted production wiring is preserved in `rejected-production-transform/`; old scratch logs and jars remain as historical evidence. All production changes from it were removed. Earlier failed/passed `*-lazy` gates are **not** release evidence for the current approach. Original dependencies remain pinned. No upstream issue was sent. The current policy addresses startup noise only and makes no future-JDK promise.

## Operator environment and safety boundary

- Origin: `http://vm.home.7mind.io:8080`.
- Project: `20eb436e-1a4d-4bb6-a4b4-d151e5c1dc04`, name `cq4`.
- Local project config: `.git/cq/project.json`.
- Operator state: `/srv/nvme/tmp/cq4-playground`.
- Token: `/srv/nvme/tmp/cq4-playground/token`. Read only as necessary for requests; **never print or copy into documentation**.
- Installed package: `.local/release`; permanent launcher: `./run-local.sh`.
- Candidate: `.local/release-all-defects`.
- Current installed manifest SHA-256:
  `27608b970b71505d5d50ee5d93def789d6473df79112bb09a8f506f9ad8c8351`.
- Old schema checksum: `88fefc58f169b793ca0dce027ed6b603a41bf9f3b62c534ddf4b01668611a799`.
- New schema checksum: `237d7df227903d1f8e9ac12e6a9a2097656f5d1a141f5e7c208361cae0bbe53c`.

This session is in yolo (`SMIND_SANDBOXED=1`); the running operator launcher/database are in the host PID namespace. Apply `/home/pavel/.codex/skills/environment/SKILL.md` for host-bound work. Build/test tools run via `nix develop --command ...`. Do not kill guessed host PIDs, replace live package files, or launch a second database against live state. The update must be a reviewed host exchange script executed by the user after clean shutdown. Explain that environment-skill/PID-namespace reason when requesting the step.

Other skills already applied in this work: constructive-test-taxonomy, dual-tests, izumi, Baboon. Read applicable instructions if you change their governed areas. Model/version changes are not planned.

## Updater and review state

Prepared scripts in the evidence root:

- `update-local.py`: exact old/candidate manifest checks, owned paths, launcher lock, refuse existing recovery marker/postmaster; fsynced recovery marker before mutation; isolated loopback PostgreSQL; reject other clients; checked custom backup; table fingerprints; transactional index/schema-checksum update; candidate `/api/hello`; all data fingerprints and terminal-archive invariant; stop owned processes; swap packages. On failure restore backup and old package, verify old checksum/index absence/fingerprints; retain marker if recovery fails. No credential output.
- `rehearse-update.py`: private old/new package copies and database, seed data, invoke exact updater, verify credentials/persistence, restore backup separately and compare all 24 CQ tables plus old schema.
- `rehearse-rollback.py`: private state, inject one failure at candidate→release rename, require rollback receipt, restored old manifest/database, cleared recovery marker, no leftover DB, and successful old-native restart.
- `finish-candidate.py`: pipeline described above; generates hash-pinned wrapper/config after rehearsals.

In the previous Codex session, read-only `/root/astra_review` approved source corrections and the simplified D25 policy with no remaining blockers. It also approved updater design/source, conditional on the concrete package rehearsals. **Final exact-artifact/script delivery approval has not happened.** Agent conversation handles may not transfer to Claude; use an available independent Astra review route or report that specific limitation. Do not invent a review receipt. Existing approvals were received in conversation; final review should be saved as durable evidence.

## Remaining work, in order

1. Observe native completion and pipeline outcome. On failure inspect the specific log, reproduce/correct only the demonstrated defect, preserve failed evidence and resume appropriate checks.
2. Inspect final native, installed, normal-update/backup-restoration and injected-rollback receipts yourself. Confirm exact candidate hashes/source applicability.
3. Obtain independent Astra final delivery review of the actual artifact evidence, updater and exact generated host script; fix findings and loop as needed.
4. Update evidence/status/coverage documents with exact results and commit them, excluding the user's `.gitignore`. This handoff can be committed with the documentation. Do not claim installation yet.
5. Ask the user to end CQ harness sessions, Ctrl-C the existing launcher once, wait for “CQ stopped”, then run `bash /tmp/exchange/cq-all-defects-update.sh`, leave it running and reply “ready” after “CQ ready”. Explain the environment skill's host PID boundary. Ask only after the result is concrete, rehearsed and reviewed.
6. After reply **read `/tmp/exchange/cq-all-defects-update.out`**, operator receipt and installed identity. Do not infer success from “ready” alone. If a new sandbox lost `/tmp/exchange`, regenerate the exact reviewed wrapper from durable inputs and recheck hashes.
7. Verify actual-hostname browser/API behavior: footer keycaps, fixed nav/results resizing, empty/bare completion without items, active/archived explicit ID suggestions and unfinished groups. Existing fixture helpers and prior `operator-live` scripts in earlier evidence roots may be reused after inspection.
8. Revisionally resolve D25/D26/D27/D66 only after delivery, with correct expected revisions, delivery evidence, commit/file citations and explicit D25 mitigation limitation. Preserve history and archive state.
9. Query the current live open set to catch concurrent user additions. At original intake these four were the only Open defects. Do not falsely claim all current records closed without checking.
10. Commit final evidence/status/coverage closeout. Report delivered changes and D25's retained dependency calls concisely. The original M2/M6 human release verdict remains pending.

## Live CQ record access

Inspect and import the existing helper, without running it as main (main overwrites original evidence):

```python
import runpy
ns = runpy.run_path('/srv/nvme/tmp/cq4-evaluation-followup-20260929/inspect.py')
project = ns['config']['project']
view = ns['read'](25)  # ItemView; item holds id, revision, draft
result = ns['call'](command)
```

Last observed revisions: D25 Open rev3, D26 Open rev1, D27 Open rev1, D66 Open rev2. **Re-read before mutation.** Change envelope:

```text
{"Change":{"input":{"project":project,"change":{
  "request":{"value":NEW_UUID},"fences":[],"reason":REASON,
  "mutations":[{"Replace":{"id":item.id,"expected":item.revision,"draft":updatedDraft}}]
}}}}
```

The changed acknowledgment is `result["Changed"]["ack"]["items"]`. Use declared model fields; inspect earlier closeout scripts for resolution structure. Citation forms:

```json
{"Commit":{"repository":"/home/pavel/work/safe/cq4/cq4","hash":"09692d2"}}
{"File":{"path":"docs/validation/all-remaining-defects.md","revision":null}}
```

Preserve before/request/result/after evidence, optimistic revisions and tokens' confidentiality. Closeout is an authorized part of the user's defect-fixing request; no extra permission is needed for verified record updates.

## Documentation navigation

- `docs/validation/all-remaining-defects.md`: current batch evidence and remaining work.
- `docs/validation/scala-lazyvals-warning.md`: historical investigation, final section describes current `.jvmopts` policy.
- `docs/implementation-status.md`, `docs/requirement-coverage.md`: current top paragraphs and historical milestone record.
- `docs/drafts/20260928-remaining-defects.md`: D25 plan updated to the user's simplification.
- `docs/validation/query-clear.md`: immediately previous delivered increment, useful operator verification precedent.
- `docs/drafts/20260926-1549-cq-implementation-plan.md` and `docs/drafts/20260926-0957-cq-requirements-prompt.md`: original release context. Earlier design-only restriction was explicitly superseded by implementation authorization.

No host installation, live record closeout or final acceptance is claimed by this handoff.
