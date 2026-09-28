# Combined M2 and M6 human review

**D1–D8 evaluation corrections are delivered and independently approved.** The [delivery record](human-evaluation.md) covers scoped service/protocol, browser, CLI, native and actual hostname checks. The user considers the current UI a prototype requiring the [queued redesign](../design/evaluation-follow-up.md); earlier technical approvals do not establish usability acceptance. Earlier harness executions retain their original artifact identities with independently reviewed source applicability. Human M2 and M6 verdicts remain pending. Per the user's instruction, this guide combines both evaluation scopes.

## Artifact and scope

The locally runnable Linux x86-64 distribution is:

```text
/home/pavel/work/safe/cq4/cq4/.local/release
```

Its native executable SHA-256 is `10e1befb1d48038dc7e002095f24a0dca0ffc406392b6fe3166179380d794619`; its manifest SHA-256 is `efd72ac4d62a4c54fccf320a638da64965224bac385009bd0cac6d0a002bbdd8`. Current correction commits: browser interactions `3080c5e`, CLI endpoint lookup `acf8f0f`, unified live updates `f4ab727`. HTTP login, compact CSS and permanent launcher corrections remain included. The current increment changes browser interactions, catalogue/item/usage subscriptions and CLI endpoint lookup; it has fresh checks for those service/protocol paths. Harness launch, dispatch and workflow implementations are unchanged. Historical paid evaluations retain their original artifact identities; no new paid harness matrix was run for this correction. CQ has one mutable model version, `0.1.0`.

The same native entrypoint provides the server, CLI and supervisor. The release includes the web workspace, fourteen fixed ledgers, query/history/relationship operations, claims and reviewed integration, automatic bounded cohorts, four subagent roles, four workflow entrypoints and the shared operational usage audit. The [requirement coverage](../requirement-coverage.md) maps every R01–R31 entry to implementation and retained evidence. [Roles and commands](../design/workflows.md) describe their inventory and harness differences.

## Verified surfaces

| Surface | Evidence |
| --- | --- |
| Contracts and service behavior | Deterministic generated contracts; 190 fast Scala scenarios; 102 PostgreSQL scenarios and actual client/process checks |
| Database access | 126 measured operations across 100, 10,000 and 100,000 unrelated items; sampled budgets pass |
| Web workspace | Chromium checks for editing, history/relationships, query completion, delayed replies, reconnect/resnapshot, project drafts, usage updates and narrow layouts; [M5 review](m5-review.md) |
| Native runtime | Current scoped native transport/CLI/watch/browser checks pass at `20260928T155140-native`, with fresh changed-path tracing and verified reuse for unchanged paths. The preceding full native gate at `20260928T112548-native` retains its original identity. |
| Installed distribution | Current package hashes, all 182 content-type transitions, explicit data-preserving update/backup proof and actual hostname delivery pass; [exact evidence](human-evaluation.md). The preceding source-isolated 25-command installed gate at `20260928T113259-installed` retains its original identity. |
| Native harness routes | Three independently accepted Python/Go cohorts cover all nine governing/child routes; [current candidates and inspection](m2-review.md#current-acceptance-evidence) |
| Complete processes | Go and defect processes pass native independent whole-process assessments and Astra replay; Go includes a bounded new-session Handoff closeout preserving its failed producer |

The retained suite, executed on `cq-release-workflow-resources`, has **16 accepted selected stages, no pending stages and five independently accepted tracks**. Its shared audit retains **91 attempts / 28,271,431 known tokens**. The instrumentation verdict is **`corpus-usage-incomplete`**, with three absent meters, 88 partially populated meters and 40 unknown-cost contributions. Accepted quality does not make those spending observations complete. [Final corpus details](m6-package.md#final-native-process-assessment-and-release-corpus) preserve all rejected branches and process deviations.

The paid harness corpus retains its original package identity and reviewed applicability to unchanged harness paths. The [current correction evidence](human-evaluation.md) records the changed service/protocol and browser behavior, scoped native checks and operator installation. The preceding [release evidence manifest](m6-release-evidence.json) remains a historical record of its exact artifacts and source applicability. Earlier artifact and consumer failures remain historical evidence; later replacements do not change their original verdicts.

## Run and verify

Run `./run-local.sh` from the CQ repository. The [local quickstart](../quickstart.md) supplies the permanent database/server launcher and a small project walkthrough. [Human evaluation corrections](human-evaluation.md) record current checks, trace reuse, backup and installed identities.

Follow the distribution's `README.md` to import and retain the exported Nix runtime closure. The application requires no Java runtime or CQ checkout. PostgreSQL, Git, configured harness credentials and consumer build tools are external dependencies.

The [operations guide](../design/operations.md) gives exact commands for configuring and starting the installed server, signing into the browser, initializing an unrelated consumer, exporting harness commands, running a supervised session, reconciling usage and taking/restoring a settled backup. The [M2 inspection guide](m2-review.md#run-and-inspect) checks an exact accepted consumer in a separate checkout without changing retained evidence.

Inspect the manifest and executable locally:

```sh
cd /home/pavel/work/safe/cq4/cq4/.local/release
sha256sum manifest.json bin/cq
./bin/cq --help
```

Replay the settled harness evidence without new model calls from its recorded evaluator checkout. This preserves the original package identity; the later browser correction uses scoped evidence reuse:

```sh
cd /home/pavel/work/safe/cq4/cq4
git worktree add --detach /srv/nvme/tmp/cq4-release-replay 02c0846
cd /srv/nvme/tmp/cq4-release-replay
PATH=/srv/nvme/tmp/cq4-implementation/m6-harness-bin:$PATH \
./dev/evaluate --suite release \
  --release /srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources \
  --resume /srv/nvme/tmp/cq4-implementation/20260928T074651-release \
  --report-only
```

The replay verifies retained proofs and provenance and regenerates reports. It preserves instrumentation gaps and does not provide a human acceptance verdict. The source tree must match the latest admitted evaluator snapshot. The replay worktree above is already prepared on this machine; skip its creation when reusing it. A fresh replay after the HTTP correction exits successfully, retaining `corpus-usage-incomplete` and no pending stages; its output is `/srv/nvme/tmp/cq4-http-ui-20260928/retained-replay.log`.

## Limits to retain in the verdict

- Linux x86-64 is the verified release platform; the guardian requires Linux 5.9 or newer.
- The original native artifact is rejected for omitted workflow resources. Use the corrected artifact above.
- The failed Go continuations have three started attempts without supported authoritative usage events. Known spending remains incomplete; the possible Claude partial source has unverified accounting semantics. [Exact interruption evidence](m6-package.md#reviewed-go-continuation-correction-and-interrupted-usage) remains in the report.
- The inherited Go assessment rubric contains a stale ChangesRequested premise; the native assessor checked the actual Accepted Plan artifacts and explicitly rejected it. Later direct governor edits remain disclosed. Frozen evidence is retained, and this historical premise must not be reused as a fact in a fresh experiment.
- Provider estimates are not billing. Unknown counters/prices remain unknown. No token-efficiency improvement is claimed.
- Managed batch sessions collect governing and child usage; universal collection for arbitrary existing interactive sessions is not claimed.
- A database-only backup covers settled state. Session reconciliation additionally requires the retained journals, artifacts/workspaces and consumer Git repositories. There is no automatic retention/deletion job.
- Privilege separation enforces MCP and harness restrictions for cooperative agents; it is not a hostile-subagent security boundary.

## Human verdicts

M2 first usable slice: **pending**. M6 complete release candidate: **pending**. Independent technical approval and permission to implement cannot supply either verdict. The implementation plan requires these designated human verdicts before the overall goal can be completed.
