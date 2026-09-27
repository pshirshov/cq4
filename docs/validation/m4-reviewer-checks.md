# M4 declared reviewer checks

Scope: [named reviewer checks](../design/reviewer-checks.md), their publication and recovery, and fresh reviewer evidence at integration. This is an increment within M4; complete workflows/cohorts, native role probes and all nine harness routes remain open.

## Implemented boundaries

- Candidate reviewers request a configured check by name through the existing local workspace capability. Other roles/modes are denied; non-reviewer schemas omit the operation. Commands, environment, candidate and execution limits are host-owned.
- Duplicate calls observe one job. Waiting-call interruption does not stop its owned execution. Checks use separate candidate workspaces and durable per-check publication queues.
- Reviewer exit closes admission and freezes whether every requested check already had published terminal evidence. Later publication cannot retroactively satisfy that boundary.
- Native and check jobs share dispatch cancellation ownership. Failed/uncertain observations cannot be hidden by an Accepted model verdict.
- Integration verifies original worker observations and fresh reviewer observations against the same policy; completion cites both inventories without duplicate artifact references.
- Recovery validates retained declarations, candidate/workspace identity and job fingerprints, publishes available evidence without execution, and continues independent publications after failures. Missing jobs remain unknown without fabricated validation observations.

## Reproductions and corrections

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T134137-fast` | New test compilation failed on an incorrect JobRecord field name; corrected to `exit` |
| `20260927T134802-fast`, `20260927T135105-fast` | Recovery exception wrapping changed the established IOException contract; preserved the original exception with additional failures suppressed. Independent recovery then completed a second child as intended, requiring the former fail-fast fixture expectation to change |
| `20260927T135247-process`, `20260927T135532-process` | Check publication failure requested child stop but left the native reviewer running. The corrected failure path cancels every owned job and retains cleanup uncertainty |
| `20260927T135450-fast` | Astra's extra-check finding reproduced: the unchanged overlay omitted a requested failed check outside the inherited inventory. Review preparation now rejects mismatched configured names and overlay rejects unmatched evidence |
| `20260927T135659-process` | Astra's cancellation-persistence finding reproduced: close returned the injected IOException before completing owned cleanup. Close now retains cancellation failures, waits for check ownership to settle and reports uncertainty |
| `20260927T135924-fast` | PASS 155 scenarios plus bridge/evaluation checks; includes shared integration/recovery scenarios and immutable check publication tests. Later process-fixture edits require final rerun |
| `20260927T140140-process` | The corrected close assertion passed, but the persistent-write-failure fixture also received the expected supervisor resource-finalization failure. The fixture now verifies both observations explicitly |
| `reviewer-evaluation-before.log`, `reviewer-evaluation-after.log` | The consumer evaluator rejected valid fresh reviewer observations because it required equality with worker validation. The corrected predicate validates both authors and exact candidate/declaration/job evidence; the reproduction now passes, with six evaluator regression cases in the final fast gate |

## Final verification

Run from the repository root with `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation dev/check CHECK`, one check at a time.

| Check | Evidence directory | Result |
| --- | --- | --- |
| `fast` | `20260927T141216-fast` | PASS 155 Scala scenarios, bridge and evaluation checks, including six consumer-evidence tests |
| `process` | `20260927T141306-process` | PASS 46 Scala process scenarios and 19 guardian checks |
| `contracts` | `20260927T141354-contracts` | PASS deterministic generation, Scala/TypeScript round trips, 439 schema definitions and seven domain MCP capabilities |
| `postgres` | `20260927T141447-postgres` | PASS 85 shared service scenarios, actual HTTP/MCP/CLI/supervisor fixtures, admission, shutdown, recovery and server restart |

`20260927T141447-postgres/m4-source-verification.json` verifies identical current hashes for all 200 non-documentation source files across these four gates. The model stays at `cq.api 0.1.0`.

The actual supervisor fixture retained three separate reviewer-check scenarios in `jvm-dispatch.log`:

- Failed execution with an Accepted model verdict: check phase Completed, validation Failed, accepted count 1, validationFailed count 1, next action Revise.
- Governing cancellation: native reviewer and check jobs both settle with Stop targets; the reviewer is Cancelled.
- Supervisor SIGKILL: recovered check and validation remain Unknown, no completed reviewer receipt is fabricated, and the complete job inventory is unchanged. All three scenarios retain an execution counter of two: one worker check and one reviewer check, with no duplicate or recovery execution.

The normal candidate flow uses a separate check workspace, repeats requests against one job, publishes fresh reviewer validation and preserves both worker/reviewer citations at integration. Maximum measured structured parent reply is 640 bytes; maximum integration reply is 594 bytes. These sizes exclude transport envelopes. Contract export measures the Codex governor instructions at 31,745 UTF-8 bytes; non-governor fixture guides are 10,235 bytes before installed role instructions.

Process scenarios additionally exercise waiting-caller interruption, reviewer close during execution/publication, publication failure cancelling the native reviewer, and cancellation persistence failure with explicit cleanup uncertainty. Shared recovery/publication scenarios cover lost acknowledgements, fingerprint/candidate mismatches, missing jobs, incomplete output and independent replay after one queue fails.

These are deterministic fixtures using real local processes and services, not fresh Claude/Codex/Pi consumer evaluations. Native role probes, all nine harness routes and complete workflows/cohorts remain M4 work. No new live-model quality or measured token-saving claim follows from this increment.

Independent Astra approved the final increment after the correction loop and successful PostgreSQL completion, with no unresolved blocking or major finding. The review confirmed current source manifests and covers authority, lifecycle, evidence, cancellation and recovery boundaries. Overall M4 and designated human acceptance remain open.
