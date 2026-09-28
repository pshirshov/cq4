# M2 technical review and human checkpoint

Tested implementation: **`8c37c6c`**. Independent Astra verdict: **approved**, with no outstanding blocking or major technical findings in M2 scope. **Human acceptance: pending.** This milestone is the first usable agent slice; the complete release remains unfinished.

## Current acceptance evidence

The sections below retain the original M2 technical review. Later [mixed-help reproduction](m4-usage-repetitions.md) invalidates the original Claude/Python candidate under the clarified current oracle; its old acceptance is historical. Replacement M4 evidence and the [packaged nine-route corpus](m6-package.md#packaged-live-execution) provide current independently accepted Python/Go candidates with operational accounting. Human M2 acceptance is still pending and must use that qualified/current evidence. The current native distribution and exact startup/recovery instructions are linked from the [operations guide](../design/operations.md).

## Original verified behavior

- Server, CLI and local supervisor share one distage `RoleAppMain`. Client/supervisor fixtures run without local server/database configuration.
- All three native adapters have actual worker/reviewer permission probes. Real consumer builds cover Claude, Codex and Pi as governors and children.
- The host assembles prompts from references, stores full results, runs configured consumer checks and chains worker-to-reviewer handles. Normal parent replies are bounded; explicit drill-down remains available.
- Governing, child, assessor and correction usage flows through the same operational audit. Token/cost reconciliation includes failed assessments and corrections. Coverage gaps and unknown prices remain explicit.
- Guardian ownership, deadlines, hierarchy shutdown, quarantine, atomic publication and interrupted-session reconciliation pass controlled process/filesystem checks, including actual JVM SIGKILL and lost acknowledgement.

## Original runtime evidence

All directories below are under `/srv/nvme/tmp/cq4-implementation/`. Each check retains commands, source hashes and its pass/failure manifest.

| Check | Final evidence |
| --- | --- |
| Current fast gate | `20260927T031912-fast`: 72 Scala scenarios, Node bridge, five candidate/correction predicates and four suite-runner fixtures |
| PostgreSQL and actual clients/process fixtures | `20260927T032004-postgres`: 32 service scenarios plus HTTP/WS/MCP, artifacts, role isolation, CLI/deadlines, supervisor, dispatch, shutdown/recovery and server SIGKILL/restart |
| Current generated contract shape | `20260927T020754-contracts`: deterministic generation, Scala/TypeScript round trips and 282 schema definitions; no contract shape changed in the later evaluator/publication/recovery increments |
| Native adapter behavior | [Six actual capability probes](m2-adapters.md), with native tool restrictions and shared-audit publication |
| Ownership and recovery | [Guardian/driver](m2-process.md), [journal](m2-jobs.md), [atomic publication](m2-publication.md), [interrupted reconciliation](m2-recovery.md) |

The final runtime gate includes refused upload while a supervisor is live, partial usage after SIGKILL, and stable audit totals/cursors on replay. Held ticket/input/exit writes end with unresolved exit 75 in approximately 16/20/15 seconds; normal governor exit cancels its hierarchy in 4.722 seconds. An uncommitted ticket is retained and explicitly reported after valid publications recover. This does not establish process settlement or complete usage from a quiet output file.

## Originally accepted consumers

At the original M2 boundary, every exact candidate below passed the then-current 110-case word-frequency oracle and an independent Codex/Astra assessment. The Python and Go projects have their own configured tests and validation commands. [Full routes, accounting, retained failures and correction lineage](m2-consumer-evaluations.md).

| Governor → worker → reviewer | Candidate | Build/correction evidence directory |
| --- | --- | --- |
| Claude → Codex → Pi | `020f8070a327a123f3bbe2c612612362071a7809` | `20260927T022912-consumer-claude-python` |
| Codex → Pi → Claude | `614a6a5bbc52cf4f204ed0958b3b7a3ef529b62a` | `20260927T020845-consumer-codex-go` |
| Pi → Claude → Codex | `2246cedae57ad401f77db5736fae354e7288dd24` | `20260927T024220-correct-20260927T023433-consumer-pi-python` |

Each directory retains `result.json`, candidate/check/review artifacts, native session files, audit exports, `cq-database.dump` and the consumer Git repository. The corresponding assessment directories are linked in the consumer evidence index. Earlier rejected candidates remain rejected; later corrections do not rewrite their evidence. All observed meters have coverage limitations, Codex monetary cost is unknown, and other reported costs are provider estimates. No billed-total or comparative efficiency claim is made.

## Run and inspect

The current native distribution is `/srv/nvme/tmp/cq4-implementation/cq-release`; use the [operations guide](../design/operations.md) for exact install/start/consumer/recovery commands. [JVM development instructions](../../README.md#run-the-current-development-server) remain available. The old M0 native executable is only a historical stack proof.

Re-run deterministic gates from the CQ checkout:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check postgres
```

To inspect and check the currently accepted packaged Python candidate in a separate checkout, without changing retained evidence:

```sh
nix develop /home/pavel/work/safe/cq4/cq4 -c bash
git clone --no-hardlinks /srv/nvme/tmp/cq4-implementation/20260928T060135-release/20260928T060145-cohort-consumer-claude-python/consumer /srv/nvme/tmp/cq4-m2-human-python
git -C /srv/nvme/tmp/cq4-m2-human-python fetch /srv/nvme/tmp/cq4-implementation/20260928T060135-release/20260928T060145-cohort-consumer-claude-python/consumer 86d5efe8d41924b6da28f0ac497d7643316f4e33
git -C /srv/nvme/tmp/cq4-m2-human-python checkout --detach FETCH_HEAD
cd /srv/nvme/tmp/cq4-m2-human-python
python3 /home/pavel/work/safe/cq4/cq4/dev/consumer-oracle.py python
```

Expected oracle result: 136 behavior cases, consumer tests passed, status passed. The clone/fetch/checkout and current oracle were rerun in a separate scratch checkout; `/srv/nvme/tmp/cq4-implementation/m6-package/human-candidate-check.json` retains the passing commands and output. The first-slice model suite can be rerun with `./dev/evaluate --suite first-slice`; it incurs new model usage. Its underlying live build/assessment stages have been exercised individually, while the aggregate runner's stage sequencing/failure behavior has deterministic process fixtures.

## Acceptance scope and remaining work

The requested verdict is acceptance of **M2's first usable agent slice and its evidence**. M3–M5 technical reviews are approved. M6 native packaging, installed verification and nine-route assessments pass; the complete packaged worked processes, final release review and designated M6 acceptance remain open. Interactive usage collection and matched efficiency comparisons are not claimed. Human acceptance of M2 does not complete the release goal or replace the later M6 checkpoint.
