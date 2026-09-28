# M6 distribution preparation

The original native distribution passed its then-current native/installed checks and nine-route cohort corpus. A later packaged Upstream startup exposed missing workflow resources; that artifact is not an accepted release candidate. The reproduced correction and fresh verification are described below.

Historical artifact: `/srv/nvme/tmp/cq4-implementation/cq-release`. It contains the 109,250,776-byte native CQ executable, 26,208-byte guardian and 37,719,072-byte Nix archive for four runtime paths (glibc, libgcc, libidn2 and libunistring), plus hashed instructions/examples. `dev/package` validates source and artifact identities. The recorded installed gate passed at `20260928T055336-installed` with a fresh private Nix store; imported runtime paths were mounted over their original locations for native execution. External fixture tools used the existing host installation. The later missing-resource reproduction limits this gate's coverage as described below.

## Implemented preparation

- `dev/package --native-evidence DIR --output DIR` requires a passing full native gate, its executable hash and matching current application/build inputs. It copies the native binary, builds the same pinned-source guardian, exports the resolved Nix library closure, and writes artifact hashes plus installation/configuration instructions. The directory is locally installable; copying an ELF alone is not the distribution contract.
- `dev/package-check --release DIR --evidence-root OUTSIDE_CQ_CHECKOUT` relocates the directory, hides the source/build tree and the observed Coursier classpath directory with Linux mount namespaces, probes those empty mounts, and runs the guardian and native transport/role/supervisor/dispatch/browser/restart/backup corpus. It uses the packaged guardian. The earlier real run passes the complete transport/dispatch/browser corpus but fails its restart startup; the lifecycle correction and focused restart/backup proof are recorded below. The subsequent coherent full gate passes; Astra independently approves this installed result.
- `--release DIR` on `consumer-eval`, `consumer-assess`, `process-eval`, `process-assess` and `defect-eval` selects the native artifact explicitly. Native execution does not generate or build JVM/guardian replacements. Continuations reject a changed runtime kind or changed artifact hashes. Install paths may change while artifact identities remain equal. Static `defect-eval verify-plan` replays retained evidence without executing CQ and does not accept `--release`.
- Eight deterministic process fixtures accept `CQ_GUARDIAN_TEST_BINARY`; normal checks still build their fixture guardian when no explicit binary is supplied. This allows installed verification to exercise the shipped executable.

## Retained controlled checks

`/srv/nvme/tmp/cq4-implementation/m6-package` contains the provenance checks. Four behavioral/effectual cases use a controlled filesystem and deliberately synthetic evidence manifests; they do **not** establish that a CQ release artifact works:

1. Failed native gates, changed application/build inputs and changed native bytes are rejected. `build-input-before.log` reproduces omitted sbt build-logic provenance; including the complete tracked `project/` build-input prefix makes that check pass.
2. Relocation preserves artifact identity; changed/missing files are rejected.
3. The explicit native consumer path returns the installed command/guardian without any build, enforces prior hashes and rejects a missing explicit release on a packaged continuation.
4. Assessment identity excludes the installation directory but includes manifest/executable/guardian hashes. The original draft's full-dictionary comparison rejects relocation in `relocation-before.log`; the correction passes in `provenance-check.log`.

Independent Astra found no blocking/major source finding in the package assembler, consumer/assessor selection or supplied-guardian fixture path. That review supports actual verification; it is not native/distribution approval. Astra also reviewed the worked-process/defect wiring and installation runner. Its missing verifier-source manifest finding reproduced through an actual controlled `package-check` failure: `provenance-before` lacks the required manifest. The correction freezes non-documentation verifier/support/fixture source hashes before loading support and compares them after execution, including failure. `provenance-after` preserves matching manifests; `provenance-changed` detects exactly the deliberately altered temporary fixture and rejects the run. Astra approved the correction with no remaining finding; actual installed/native approval is pending. The nine affected Python check commands (release provenance, consumer evidence/cohorts, process evidence/assessment/reopening, defect evidence/assessment and evaluator suite) also pass in this directory.

## Remaining gates

Execute the complete release consumer suite using the same artifact across producers, assessments and recovery; reconcile spending through CQ's shared usage audit; finish operational instructions and independent release review; present concrete evidence for human acceptance.

The corrected distribution is `/srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources`, built from the passing full native gate `20260928T071654-native`. Its executable SHA-256 is `2178d813e17ebce0693209d308d8cc082ae4ebc55b93860f8d985efd31c9f555`; the guardian and runtime closure are unchanged. The fresh installed gate at `20260928T074034-installed` passes all 24 commands with 292 unchanged source hashes: source/classpath isolation, imported runtime, all four workflows, browser, restart and settled 24-table backup. Independent Astra approves both corrected native and installed evidence. Delivery and relocated package hashes match. The fresh live corpus is running at `20260928T074651-release`; the original artifact and original release suite remain historical evidence.

## Settled database backup

The targeted real native checks at `/srv/nvme/tmp/cq4-implementation/20260928T051410-backup-native` and `20260928T052720-backup-native` pass. They seed an isolated database, release ownership, finish the controlled usage attempt and archive an item before graceful shutdown. A custom-format dump restores into another empty database. All **24 table** fingerprints match. There are zero PostgreSQL sequences; the independent application-counter check continues at item 10. Native API reads preserve all nine records, their histories/relationships, archived state, usage/outcome/audit data, exact artifact bytes and retry acknowledgement. The latter run also exports the ordinary operational meter projection and reconciles it to the API summary and role breakdown. The original `20260928T051322` failure was a fixture comparison of wire-encoded i64 text with an integer; explicit numeric decoding corrects that assertion.

Astra approved the settled-backup verifier and targeted evidence. It does not establish recovery of a running supervisor's journal, Git workspace or unresolved integration reservation. Focused installed restart and backup now pass at `20260928T055213-installed-tail`; the complete coherent installed gate also passes at `20260928T055336-installed`.

## Reproduced installation failures

`20260928T053407-installed` fails before application execution: the multi-user Nix daemon refuses this unsigned local closure. The distribution instructions now specify a trusted importing user for such installations. The verifier imports into a fresh owned [local store](https://releases.nixos.org/nix/nix-2.34.8/manual/store/types/local-store.html) instead, without changing daemon trust. `20260928T053647-installed` successfully imports all four runtime paths but fails NativePRNG initialization. Its `entropy-probe.json` reproduces `PermissionError` reading `/dev/urandom` under the original root bind and succeeds with an explicit device bind. The verifier now preserves `/dev` access; the native application needs no code change. `20260928T053759-installed` passes the full transport/roles/supervisor/workflow/cohort/dispatch/browser corpus but fails the following restart startup with a connection reset. Original manifests, archives and failures remain retained.

The default host Claude command now reports 2.1.283, while CQ and prior accepted evaluations pin 2.1.280. The exact prior Nix executable still reports 2.1.280; `/srv/nvme/tmp/cq4-implementation/m6-harness-bin` provides stable links to that Claude executable and the prior Codex 0.156.1 executable. Pi remains 0.87.1. Release runs will use those pins; no collector upgrade or reused newer-version observability claim is made.

## Release suite readiness

`dev/evaluate --suite release --release DIR` now schedules sixteen stages: three managed cohorts plus independent audits, question/resume/assessment for the worked process, and seven defect-process stages. `--resume SUITE` replays successful proof without launching those stages again. The question checkpoint requires `--answer-file`; other tracks continue while it is pending. `--retry STAGE=REASON` and `--adopt STAGE=EVIDENCE` append explicit attempts. Package hashes, evaluator inputs, dependency identities and every adopted ancestor's manifest are checked; failed/rejected stages remain retained.

The reporter reads CQ's original session summaries, audit and operational meter projections, including restored correction ancestors. Cumulative restored summaries are not counted again. It reconciles per-role, parent/child and independent-assessor token metrics; reports qualified cache fractions, exact partial-cost groups, unavailable fields, native calls/repeated dispatch IDs, explicit artifact reads, payload bytes and invocation durations. These different tasks/routes are descriptive observations, not a matched savings comparison. The existing M4 matched baseline remains separate.

`/srv/nvme/tmp/cq4-implementation/m6-suite-check` retains five scheduling/admission checks, four accounting/failed-traffic checks and two metrics checks. The missing-ancestor reproduction undercounts three attempts as one before correction. Actual retained Go correction lineage now includes 38 attempts and 15,648,420 known tokens; the separately replayed matched group retains 18 attempts and 5,013,661 known tokens. A truncated failed transcript first aborts JSON decoding; after correction it preserves spending and records unavailable traffic with an explicit instrumentation gap. StartChoice's distinct wire shape first fails the traffic parser; the corrected parser passes equivalent three-harness fixtures and actual retained Claude/Codex/Pi transcripts. The current affected Python suites pass. Astra approved this scoped readiness after reviewing source and evidence; paid package runs and final acceptance remain pending.

## Installed process lifecycle correction

The `m6-package/wrapper-signals` controlled reproduction shows that terminating Bubblewrap itself bypasses the child SIGTERM handler; targeting its reported child instead allows graceful shutdown. Child SIGKILL produces raw wrapper exit 137 and an observed settled child. The installed verifier now obtains and validates the child identity, retains its pidfd for signals, bounds metadata/startup/settlement waits and retains raw wrapper status. Only an explicitly sent child SIGKILL with observed termination maps 137 to the restart fixture's signal status; an ordinary child exit 137 remains 137.

Astra identified a wrapper-timeout cleanup path. `stopped-wrapper-before.json` reproduces a settled child with a stopped, unreaped wrapper. The correction kills/reaps that owned wrapper on timeout while preserving the failed check and raw status. `after.log` passes both the lifecycle cases and this cleanup regression. The shared development server launcher only gains a process-construction hook; installed-specific lifecycle behavior is confined to the distribution verifier.

The actual focused installed restart/backup run `20260928T055213-installed-tail` passes with unchanged source manifests, the same relocated distribution and imported library mounts. It verifies forced-stop recovery and settled restore through the native API. The subsequent timeout-only correction has its separate controlled check; `20260928T055336-installed` passes the coherent full run for final source. No native application bytes changed.

## Coherent installed result

`20260928T055336-installed/result.json` is `package-checked`, with `changedSources: []`. All 24 root commands pass, as do the nested backup commands. Fresh private-store import includes all four runtime paths; the application executes with checkout/build and Coursier directories hidden. Native transport/API, all three exported harness command sets, roles/CLI, process supervision/recovery, workflows/cohorts, dispatch/growth/combination/admission/shutdown and browser checks pass. Browser error reports are empty. Process observations retain child settlement, raw wrapper status 143 after graceful SIGTERM and raw 137 after deliberate SIGKILL. The latter maps to the forced-stop fixture only after observed child termination. Restart preserves the seeded state; the installed backup matches all 24 tables and API snapshots, preserves artifacts/idempotency and continues the item counter.

The [operations guide](../design/operations.md) covers exact install/start, consumer configuration, recovery, backup/restore and retention. Astra source review found no blocking/major issue and requested the now-explicit recorded recovery-endpoint boundary. Its SQL preflight was executed against the actual restored backup in an isolated PostgreSQL cluster: `m6-package/operations-preflight` passes settled state and rejects separately introduced running attempts, active claims and pending integration memberships. These are database checks only; OS settlement remains a separate operator precondition.

Astra independently approved the installed increment after checking all 291 source hashes against before/after/current files, the artifact identities, imported paths, every command, empty browser error reports, process settlement and restored table/API evidence. There are no remaining blocking/major findings in this scope. This is not final release or human acceptance.

Astra also approved the final operations guide after verifying the unchanged executed SQL and all four preflight outcomes, including the recorded-session endpoint clarification.

## Packaged live execution

Implementation/evaluator/runbook commit: `94f4a52`. `20260928T060135-release` is running via the documented release suite and `/srv/nvme/tmp/cq4-implementation/m6-harness-bin` pins. The first Claude-governed Python cohort has started real Codex Planner and Claude Worker children. All three executable version checks pass. This is launch evidence only; accepted candidates, all nine observed routes, independent assessments and final operational usage reporting remain pending. The process question/resume branch still requires its supplied-answer provenance.

The actual launch command from this checkout is:

```sh
PATH=/srv/nvme/tmp/cq4-implementation/m6-harness-bin:$PATH \
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation \
./dev/evaluate --suite release --release /srv/nvme/tmp/cq4-implementation/cq-release
```

After that invocation finishes, resume its retained suite without repeating accepted stages:

```sh
PATH=/srv/nvme/tmp/cq4-implementation/m6-harness-bin:$PATH \
./dev/evaluate --suite release \
  --release /srv/nvme/tmp/cq4-implementation/cq-release \
  --resume /srv/nvme/tmp/cq4-implementation/20260928T060135-release \
  --answer-file /absolute/supplied-answer.json
```

The answer file has exactly four fields: `question` copied verbatim from the new checkpoint's `question-checkpoint.json` (the whole item/revision reference), `answer` equal to `Python` or `Go`, `verbatim` containing the actual supplied reply, and `source` identifying where that reply was received. Do not substitute another checkpoint's question reference or invent a reply. The runner retains the file's bytes and commits its provenance into the consumer candidate. Without this file, independent defect stages can run while the resume/assessment branch remains pending. `--report-only` on a finished suite replays evidence without new model calls. A concurrent invocation fails the suite's exclusive lock.

The first producer and independent cohort assessment are accepted (`20260928T060145-cohort-consumer-claude-python`, `20260928T060702-assess-20260928T060145-cohort-consumer-claude-python`). Producer accounting reconciles four attempts and 1,414,811 known tokens, with zero unknown/estimated input-output contributions; its two observed cost groups coexist with one unknown-cost contribution. The separate assessor has two attempts and retains its own partial cost coverage. `m6-package/first-packaged-metrics.json` retains the successful role/counter reconciliation and native traffic parse. The Codex-governed cohort is running next; this first accepted track is not the full release verdict.

The provisional [release evidence manifest](m6-release-evidence.json) binds implementation commit, package hashes, verified gates, retained-gate applicability and remaining work. Astra approved the manifest and reconciled dispatch/usage/supervisor documentation without a substantive finding. Final PostgreSQL verification at `20260928T060301-postgres` passes 102 scenarios and the full JVM transport/process/restart corpus; all 291 current non-documentation inputs match. Final access verification passes at `20260928T061234-access`, with all 291 inputs matching and access-work budgets satisfied through 100,000 unrelated items.

The Codex-governed Go producer (`20260928T060837-cohort-consumer-codex-go`) also passes its candidate/cohort proof; its independent assessment is running. These progress snapshots retain the live suite as the current state authority.

Astra approved the final deterministic evidence and provisional manifest: 102 PostgreSQL tests and all 25 recorded commands pass; access measures 126 operations over 100/10,000/100,000 background sizes with all budget checks passing. Current source applicability and every referenced evidence hash were verified. No blocking/major finding remains in this scope; packaged live completion, final release review and designated human acceptance remain open.

All six packaged cohort producer/assessment stages are now accepted. Astra independently replays all nine governor-to-child routes and reconciles 18 distinct attempts to **4,641,813 known tokens**: 3,430,819 producer / 1,210,994 independent-assessor tokens, or 2,932,369 governor / 1,709,444 child tokens. Required input/output coverage is complete and no meter is absent; all 18 retain optional-field limitations. Partial provider estimates coexist with seven unknown-cost contributions and do not establish billing. Native traffic has 59 dispatch calls, five explicit artifact drill-downs, no repeated dispatch IDs and no missing/orphan replies. The Pi producer/assessment contributes 782,403 / 425,233 known tokens. This scoped replay is approved; worked-process and final suite acceptance remain pending.

The packaged question checkpoint passes at `20260928T062332-process-begin`: I1@3 → G1@2 → Q1@1, project `556e23a3-3962-4098-a0dd-0ad51d574181`, asks which implementation language to use, with exact alternatives Python/Go. Archive errors are empty. At that checkpoint the suite skipped dependent resume/assessment stages and started `20260928T062725-defect-begin` independently. The language provenance was subsequently resolved below.


## Retained probe rejection and evaluator amendment

The suite ends `incomplete` after `20260928T062725-defect-begin` passes and `20260928T063520-defect-probe` is rejected. The latter exits zero and reconciles two attempts, but fails the unchanged predicate `Probe left unexpected untracked files`: its workspace contains both the required `cq-probe-observations.json` and an additional `cq-probe-supplement.json`. The exact recorder execution, observations, tracked-file preservation and HEAD checks passed before that predicate. The extra file contains supplemental empirical results. The original prompt did not state that only one output file was permitted; the correction states that restriction explicitly and directs supplemental observations into the Result artifact. The failed evidence and spending remain retained.

`dev/release-evaluate --amendment FILE --resume SUITE` adds a reviewed source snapshot without replacing the original suite source map. The amendment records exact `beforeSources` / `afterSources`, a reason, affected stages, the rejected invocation ID and result hash, and a retained accepted review path/hash. The review must bind the same source maps and stages. Only the five evaluator instruction/scheduling/reporting files and their checks may change through this mechanism; runtime, fixture, recorder, oracle and acceptance predicates stay protected. Each new invocation records its source epoch; previously registered evidence retains its original epoch and complete file hashes. Unknown evidence must match current inputs. Historical proofs are replayed and file snapshots checked before reuse. Reporting exposes both source epochs as a corrected execution series and retains failed spending.

Controlled checks first failed because the amendment CLI was absent, then all eleven scheduling/provenance checks and four accounting/report checks passed. They cover no repeated accepted routes, retained rejection, protected/unlisted source changes, altered historical evidence/review, stale new invocation sources, and explicit adoption of a registered historical epoch. Logs: `/srv/nvme/tmp/cq4-implementation/m6-package/probe-amendment/{before,after}.log`. Astra reproduced a missing freeze for nonzero exits; the fail-first correction freezes failed evidence independently of acceptance and marks incomplete provenance when required files are absent. Astra approved the corrected source with no remaining blocking or major finding. No new paid attempts were launched during this verification.


The reviewed amendment was admitted using `--report-only`. All eight accepted proofs replay unchanged, all nine retained invocation records are byte-equivalent as JSON values, and the operational report preserves exactly 33 attempts / 9,089,492 known tokens, including the rejected probe’s two attempts / 405,360 tokens. Full snapshots freeze 11,775 files in nine evidence directories. `m6-package/probe-amendment/admission-proof.json`, `amendment.json`, `astra-review.json` and `admission.log` preserve this check. The original suite and usage report are copied alongside as `before-suite.json` and `before-usage-report.json`.

After amendment admission, retry only the rejected probe and allow its remaining dependent stages to proceed:

```sh
PATH=/srv/nvme/tmp/cq4-implementation/m6-harness-bin:$PATH \
./dev/evaluate --suite release \
  --release /srv/nvme/tmp/cq4-implementation/cq-release \
  --resume /srv/nvme/tmp/cq4-implementation/20260928T060135-release \
  --retry 'defect-probe=Clarified output-file restriction; Astra-approved evaluator amendment'
```

Do not resubmit `--amendment` after it has been admitted: its before/after chain is appended once. The source epoch is evaluation provenance; the CQ model remains the single `0.1.0` version. The previously approved native/installed/SQL/UI evidence still applies to unchanged product code. Only the five reviewed evaluator files changed.

Independent Astra also verified the actual amendment admission: unchanged original invocations/proofs/accounting and all 11,775 frozen files. The targeted retry may proceed; no accepted cohort rerun is required.

The targeted retry at `20260928T065309-defect-probe` passes the unchanged strict file-output/recorder/fixture predicate. Its research continuation at `20260928T065443-defect-research` also passes. The suite launches reviewed planning next, retaining all accepted cohorts and the original rejected probe.


The prior actual user reply **Go** to `call_npnylia4LEY7tw7W2K76AA0p` supplies the same word-frequency preference. Independent Astra confirmed there is no per-rerun fresh-reply requirement; the earlier carry-forward confirmation request was unnecessary. `supplied-go-answer.json` in the suite binds that actual verbatim reply and identifiable original source to the new Q1@1, explicitly stating that no fresh reply or milestone acceptance occurred. `supplied_answer()` validates all four fields. The separate packaged resume starts at `20260928T070043-process-resume` while the defect chain runs; it will be explicitly adopted into the suite with the same source/package and predecessor checks. No answer is inferred from elapsed time.


## Native workflow-resource failure and corrected candidate

The original artifact fails `20260928T070858-defect-upstream` before a model starts: `WorkflowAssets.instructions` cannot load the installed Upstream instructions. Its tracing metadata retained Begin, Advance, common and entrypoint assets but omitted Review and Upstream. Command export reads only the entrypoint template, so successful exports did not prove instruction inclusion. The original artifact, metadata and failed attempt remain unchanged.

A new deterministic fixture runs all four workflows through actual supervisor startup. Begin dispatches an Explorer through Select/StartChoice and retains its admitted result; Review uses that real current subject. Each harness verifies the exact hash of common plus workflow-specific instructions and successful receipt/publication. Against the old binary, `/srv/nvme/tmp/cq4-implementation/m6-package/workflow-resources-before` passes Begin/Advance and fails exactly Review/Upstream with the expected missing-resource error. The correction explicitly includes `cq/workflows/*.md` through native-image reachability metadata. Astra approves the source/regression design conditional on corrected native/installed execution. The new native gate at `20260928T071654-native` passes all 40 commands, with independent Astra approval; the corrected installed result is recorded above.

The original nine-route corpus remains historical evidence for its actual artifact. Under the same-artifact M6 contract, the rebuilt candidate requires fresh packaged routes, assessments and worked processes; old successes cannot be relabeled as new-artifact execution. JVM/service/UI evidence retains scoped applicability where inputs are unchanged. The single CQ model stays `0.1.0`.

The old-artifact Go resume `20260928T070043-process-resume` also fails its acceptance predicate: both tasks advance from integrated Done@3 to Done@4 when the shared Handoff relationships are added. It is retained as a failed experiment, not current quality acceptance. Its actual supplied Go provenance remains valid for the same preference in the fresh run.

The missing native transcript additionally reproduced a reporter `FileNotFoundError`. The fail-first correction preserves failed-attempt accounting with unavailable traffic and an unknown (`null`) byte count; a successful stage with no transcript still fails reporting. Five focused checks pass and Astra approves. Original reports remain unchanged. A separate derived report at `/srv/nvme/tmp/cq4-implementation/m6-package/rejected-artifact-accounting` includes the external Go run and all original suite stages/lineage: **62 distinct attempts / 21,547,362 known tokens**, with the missing upstream transcript explicitly reported. `analysis-provenance.json` binds original inputs and corrected reporter hashes. This is historical experimental spending, separate from the corrected candidate’s acceptance denominator.


Astra inspected both task histories and approved an instruction-only clarification for the fresh suite: create the shared Handoff relationships with truthful pending content before implementation/review/integration; afterward preserve the acknowledged task revisions and update only Handoff content. The task-revision predicate and consumer oracle remain unchanged. Eleven release scheduling/provenance checks and five reporting checks pass. Astra independently replays the historical accounting total and verifies every recorded provenance hash; both derived analysis statuses remain incomplete.


## Corrected-artifact live progress

The fresh suite at `20260928T074651-release` accepts the Claude/Python producer and independent assessment: six attempts / 1,426,557 known tokens, with no missing meters. The first Codex/Go producer passes the 136-case behavior oracle, but its independent whole-scope Audit at `20260928T075831-assess-20260928T075230-cohort-consumer-codex-go` rejects T2: the README omits the explicitly required option-prefix and oversized-number examples. T1 is accepted. This is retained candidate-quality rejection, not evidence of a CQ runtime defect.

Astra approves a declared targeted additional Go experiment with the same frozen sources/specification/oracle/artifact. The separate producer at `20260928T080347-cohort-consumer-codex-go` passes after its internal review/correction loop; independent whole-scope Audit `20260928T081220-assess-20260928T080347-cohort-consumer-codex-go` also passes. All original and new spending remains included. It will be explicitly adopted after the active suite invocation settles. `m6-package/corrected-codex-retry.json` records the scope and pending admission. Manual outer invocation duration was not measured; the evaluator retains governor duration. This is not a corrected original candidate, first-pass success or matched efficiency sample.


The scoped `m6-package/corrected-cohort-replay.json` independently replays all three exact producer/Audit pairs and all nine routes. Eight session reports include the original rejected Go branch: **26 distinct attempts / 7,479,737 known tokens**, with complete required input/output coverage, no missing meters and optional-field limitations on all 26 meters. Astra approves this scoped evidence for explicit adoption. It remains pre-admission while the active suite holds its lock; overlapping runs and the unmeasured manual outer duration support no matched timing claim.

The new worked-process checkpoint at `20260928T080611-process-begin` preserves I1@3 → G1@2 → Q1@1 in project `8d4a5a73-03c5-44ef-b22e-836e603c9ed7`. The user explicitly confirms reuse of Go in `call_FTCGWZinwe7DEVI87FxqboBc`, item 0. The new suite’s `supplied-go-answer.json` binds this question revision to the original verbatim Go reply and that confirmation; it does not claim milestone acceptance. The independent continuation at `20260928T081149-process-resume` is running alongside the defect track. `m6-package/corrected-process-resume-invocation.json` retains its actual invocation timing and outcome when settled.


The Go continuation `20260928T081149-process-resume` fails at the configured 900,000 ms execution deadline (observed governor duration 902.749 s). It archives without errors after child hierarchy termination, retaining all planning/review attempts and spending. The trace is under review before choosing another action; no completion or infrastructure-capacity diagnosis is inferred from the deadline alone. The main defect track continues independently. The user requests a single human evaluation after all planned technical work; both M2/M6 verdicts remain pending until that point.


### Reviewed Go continuation correction and interrupted usage

Independent Astra traced the deadline failure to repeated planning without complete current Goal/Decision guidance, decomposition repairs, an oversized claim duration, mismatched choice claims and compatibility invalidation after relationship edits. No CQ runtime defect was established. The approved correction at `bb1ccc7` states the ordinary mutation-claim rules and 300,000 ms bound, records the actual-answer Decision and full scope before planning, supplies current Goal/Decision guidance to Planner and Plan Reviewer, completes relationships before compatibility assessment, and claims exactly the selected members. Runtime, package, fixture, oracle, predicates and the 900,000 ms execution deadline are unchanged.

The amendment mechanism adds only `dev/process-eval` to its existing five-file allowlist. A new resume-specific preservation check first failed with `Protected evaluation inputs changed`, then all twelve scheduling/provenance checks passed. `/srv/nvme/tmp/cq4-implementation/m6-package/process-guidance-amendment/{before,after}.log` and `astra-review.json` retain the reproduction, verification and exact reviewed source maps. Admission must follow settlement of the active suite and adoption of both the external accepted Go cohort/Audit and failed process continuation under their original source epoch. Only the process continuation and its dependent assessment will rerun; accepted cohort and defect stages remain retained.

The failed Go begin/resume lineage contains **16 attempts / 2,044,506 known tokens**, including **two started attempts without meters**. Claude Governor `ee8f57d0-34e9-494d-8fb3-1fd760bc47e2` exited at the deadline with no final `result.modelUsage`; its retained stream contains 133 assistant usage objects over 75 distinct message IDs, all with null stop reasons. Their possible partial-accounting semantics are unverified, so summation cannot currently establish authoritative output totals or safe reconciliation with a final aggregate. This potential partial source remains retained, not declared wholly irrecoverable. Codex Planner `f123472c-6319-4c9d-bf01-825eee3efdd2` was cancelled with no usage object or `turn.completed`; no counters are recoverable from the inspected retained stream. Both final delivery queues are sealed; ordinary upload replay cannot add observations. Missing counters remain unknown, never zero.

Astra's independent criteria review distinguishes configured collectors interrupted before supported completion events from a missing collector (R09). R31 requires truthful observable accounting and incomplete totals after interruption; M6 requires retained failures and explicit gaps. The corpus must retain `corpus-usage-incomplete` if all quality gates later pass, and cannot supply a complete spending or efficiency baseline. Technical release approval may separately consider the supported collectors and interruption handling once completed executions have required coverage and all quality/process gates pass. That determination and human acceptance remain pending.
