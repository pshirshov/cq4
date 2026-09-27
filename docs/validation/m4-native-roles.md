# M4 native Explorer and Planner capabilities

Scope: actual Claude Code 2.1.280, Codex 0.156.1 and Pi 0.87.1 invocations through the production adapters, guardian, isolated workspaces, authenticated CQ HTTP/MCP and PostgreSQL. These extend the [M2 Worker/Reviewer probes](m2-adapters.md); they do not complete the M4 consumer corpus.

The fixture retrieves an unpredictable CQ artifact and requires the exact value in the native structured result. Restricted roles are asked to attempt a native write if available, and the forbidden file must remain absent. Before launch, the fixture directly verifies that the role credential cannot mutate the ledger. Each successful probe requires settled successful process/native completion and at least one operational usage observation. Publication and exported summaries use the ordinary CQ audit service.

## Live results

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence directory | Harness/model; role | Result | Inclusive input + output tokens | USD provider estimate |
| --- | --- | --- | --- | --- |
| `20260927T142650-adapter-claude-explorer` | Claude / `claude-opus-5-5`; Explorer | PASS | 19,611 | 0.0876668 |
| `20260927T142718-adapter-claude-planner` | Claude / `claude-opus-5-5`; Planner | PASS | 19,631 | 0.022040999999999998 |
| `20260927T142750-adapter-codex-explorer` | Codex / `gpt-6-sol`; Explorer | PASS | 47,342 | Unknown |
| `20260927T142824-adapter-codex-planner` | Codex / `gpt-6-sol`; Planner | PASS | 60,538 | Unknown |
| `20260927T142900-adapter-pi-explorer` | Pi / `gpt-5.5` via `openai-codex`; Explorer | PASS | 5,739 | 0.025883000000000002 |
| `20260927T142933-adapter-pi-planner` | Pi / `gpt-5.5` via `openai-codex`; Planner | PASS | 5,760 | 0.032950000000000003 |
| `20260927T142542-adapter-codex-reviewer` | Codex / `gpt-6-sol`; Reviewer replay | PASS | 64,010 | Unknown |

`20260927T142933-adapter-pi-planner/m4-role-probes.json` records these audit-derived values and matching non-documentation source hashes for all seven runs. Native output, job records, attempts, outcomes and audit exports remain in each evidence directory. Every run has one partial meter and zero attempts without a meter. Work is explicitly unattributed capability-fixture activity. Cache/reasoning subsets are not added twice; provider estimates lack pricing revisions and are not actual billing. Different models, cache state and single observations establish no comparative efficiency result.

## Runner correction and verification

The original `dev/adapter-probe codex Reviewer` failed before model launch: `20260927T142447-adapter-codex-reviewer/result.json` retains `Checks.database() missing 1 required positional argument: 'settings'`. The runner now supplies an explicit empty settings list. Repeating that command produced the successful Codex Reviewer run above. The runner also accepts Explorer/Planner and rejects success without retained usage observations. Production adapters, contracts and permissions are unchanged in this increment.

Reproduce a specific live probe with:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation dev/adapter-probe claude Explorer
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation dev/adapter-probe codex Planner
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation dev/adapter-probe pi Explorer
```

Each command invokes a real model. Both new roles were run for all three harnesses in the table. The shared deterministic role-policy scenarios already cover all four roles. Final `dev/check fast` evidence `20260927T143042-fast` passes 155 Scala scenarios plus bridge/evaluation checks. The aggregate manifest verifies all 200 current non-documentation files match that gate and all seven live runs. Independent Astra approved this scoped increment with no blocking or major finding after verifying source manifests, retained observations and the final fast result.

Remaining M4 evidence includes native named-check use by candidate reviewers, complete worked processes, and the refreshed nine-route consumer corpus. These capability probes exercise the adapter role boundary with a minimal fixture prompt, not installed semantic role instructions or the full ChildRunner lifecycle. Those lifecycle/mode/claim/proposal boundaries have separate deterministic fixtures; no full-process or milestone-completion claim follows here.
