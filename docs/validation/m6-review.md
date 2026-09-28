# Combined M2 and M6 human review

**Technical release candidate approved by independent Astra.** Human M2 and M6 verdicts remain pending. Per the user's instruction, this guide combines both evaluation scopes.

## Artifact and scope

The locally runnable Linux x86-64 distribution is:

```text
/srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources
```

Its native executable SHA-256 is `2178d813e17ebce0693209d308d8cc082ae4ebc55b93860f8d985efd31c9f555`; its manifest SHA-256 is `3159173b19f4a9db92a4903429f12f8507d63ad55f5dc05d21bef5ab4d5f82f6`. Product correction commit: `302bf34`. Subsequent evaluator corrections retain their own reviewed source epochs. CQ has one model version, `0.1.0`.

The same native entrypoint provides the server, CLI and supervisor. The release includes the web workspace, fourteen fixed ledgers, query/history/relationship operations, claims and reviewed integration, automatic bounded cohorts, four subagent roles, four workflow entrypoints and the shared operational usage audit. The [requirement coverage](../requirement-coverage.md) maps every R01–R31 entry to implementation and retained evidence. [Roles and commands](../design/workflows.md) describe their inventory and harness differences.

## Verified surfaces

| Surface | Evidence |
| --- | --- |
| Contracts and service behavior | Deterministic generated contracts; 190 fast Scala scenarios; 102 PostgreSQL scenarios and actual client/process checks |
| Database access | 126 measured operations across 100, 10,000 and 100,000 unrelated items; sampled budgets pass |
| Web workspace | Chromium checks for editing, history/relationships, query completion, delayed replies, reconnect/resnapshot, project drafts, usage updates and narrow layouts; [M5 review](m5-review.md) |
| Native runtime | 40 commands pass, including actual startup of all four workflows; `20260928T071654-native` |
| Installed distribution | 24 root commands pass with source/classpath hidden, imported runtime closure, native browser/restart and settled 24-table backup/restore; `20260928T074034-installed` |
| Native harness routes | Three independently accepted Python/Go cohorts cover all nine governing/child routes; [current candidates and inspection](m2-review.md#current-acceptance-evidence) |
| Complete processes | Go and defect processes pass native independent whole-process assessments and Astra replay; Go includes a bounded new-session Handoff closeout preserving its failed producer |

The final same-artifact suite has **16 accepted selected stages, no pending stages and five independently accepted tracks**. Its shared audit retains **91 attempts / 28,271,431 known tokens**. The instrumentation verdict is **`corpus-usage-incomplete`**, with three absent meters, 88 partially populated meters and 40 unknown-cost contributions. Accepted quality does not make those spending observations complete. [Final corpus details](m6-package.md#final-native-process-assessment-and-release-corpus) preserve all rejected branches and process deviations.

The [release evidence manifest](m6-release-evidence.json) binds exact evidence hashes and explains retained checks' source applicability. Earlier artifact and consumer failures remain historical evidence; their later replacements do not change their original verdicts.

## Run and verify

The [local quickstart](../quickstart.md) supplies a persistent database/server launch script and a small project walkthrough for hands-on evaluation.

Follow the distribution's `README.md` to import and retain the exported Nix runtime closure. The application requires no Java runtime or CQ checkout. PostgreSQL, Git, configured harness credentials and consumer build tools are external dependencies.

The [operations guide](../design/operations.md) gives exact commands for configuring and starting the installed server, signing into the browser, initializing an unrelated consumer, exporting harness commands, running a supervised session, reconciling usage and taking/restoring a settled backup. The [M2 inspection guide](m2-review.md#run-and-inspect) checks an exact accepted consumer in a separate checkout without changing retained evidence.

Inspect the manifest and executable locally:

```sh
cd /srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources
sha256sum manifest.json bin/cq
./bin/cq --help
```

Replay the settled release evidence without new model calls from the CQ checkout:

```sh
cd /home/pavel/work/safe/cq4/cq4
PATH=/srv/nvme/tmp/cq4-implementation/m6-harness-bin:$PATH \
./dev/evaluate --suite release \
  --release /srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources \
  --resume /srv/nvme/tmp/cq4-implementation/20260928T074651-release \
  --report-only
```

The replay verifies retained proofs and provenance and regenerates reports. It preserves instrumentation gaps and does not provide a human acceptance verdict. The source tree must match the latest admitted evaluator snapshot.

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
