# Real-harness driver cases against real CQ ledgers (T59), 2026-10-04

This record covers T59: the auto-driver cases that T9 revision 6 and G1 require for real harnesses on real CQ servers and ledgers. Those cases are two concurrent sessions, exact start and resume activations, and the three Failure stops that leave the ledger unchanged (skipped directive, untracked activation, out-of-set change). The cases must pass for Claude Code as well as Codex.

**Provenance.** Everything below comes from private fixtures that the governing sessions observed. Nobody has independently reread the private originals. The readable evidence is the credential-free projection in [`evidence/t59-driver/`](evidence/t59-driver/README.md). It is copied unchanged from `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/t59-evidence-sanitized`. A reviewer can recompute this record's statements from those files, but the files are projections and not the originals. The originals stay private under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/{claude-driver-cases,claude-cycles}`, with the recording digests listed in the evidence README. The `token` values in `cycle-resume-evidence.json` are one-time directive tokens of finished cycles that were shown on screen. They are not credentials. The `*-before.json` and `*-after.json` snapshots named by the result files stay private, and only their comparison result is projected.

**What this record does not do.** It does not declare G1 Achieved. G1 still needs independent review of this record. It is not formal I12 qualification, and it covers no package later than `e5dba67`.

## Setup

- **Harness:** real Claude Code 2.1.285, with the assets `cq configure claude` generated from the `release-e5dba67` package.
- **Server and ledger:** a private CQ server from that package, with a private ledger (project `261e0d41-…` for the driver cases).
- **Date:** 2026-10-04.
- **Recordings:** each Claude session was recorded with asciinema. Both connected casts and the cycles cast end with exit event 0.
- **Uploads:** the native session uploads exited 0.

The Codex counterparts were recorded earlier the same day by T59/T60 on Codex 0.159.2, in the same kind of private fixture. They are summarised in [Codex counterparts](#codex-counterparts). That evidence is retained privately (`evaluations/codex-recorded/probe-report.json`) and is not part of the copied projection.

## Claude Code cases

| Case | Evidence file | Observed (from the file) | Observed by |
| --- | --- | --- | --- |
| Two concurrent sessions | `isolation-evidence.json`, `isolation-bound-projection.json` | Session A (key `025c7fb0-…`, attached `cb681df6-…`) is On for D1 through Explore, with one managed child. Session B (key `9f225874-…`, attached `e15d0f82-…`) binds its own targets, Q1 through Plan, and stops `Quiescent` while A stays On. The two sessions have distinct keys, attached sessions and frozen targets. That snapshot shows one active claim and one managed attempt, both belonging to A's child. | Codex governing session |
| Skipped directive | `skipped-result.json` | Driver of B on D2 through Explore: stop `Failure` "directive not started: the start directive of cycle 1 was not submitted". `allItemRevisionsAndSummariesUnchanged: true`. | Codex governing session |
| Untracked activation | `untracked-result.json` | Same driver: stop `Failure` "untracked activation: the bound attached session activated a workflow without a start or resume token". Ledger unchanged. | Codex governing session |
| Out-of-set change | `out-of-set-result.json` | Same driver: stop `Failure` "out-of-set change: I1 is outside the advanceable set stored for cycle 1". Ledger unchanged. | Codex governing session |
| Independent park | `park-both-on.json`, `park-a-only.json`, `park-both-off.json` | First both are On (Q2 and Q3 through Plan). Parking A sets A Off/`Parked` while B stays On. Parking B then sets both Off. The requested hold never ran; the state snapshots are what establish park isolation. | Codex governing session |
| Resume | `cycle-resume-evidence.json` | In drives 1 and 2 the session ended a turn with a child running. The Stop hook issued a resume directive, the session ran it, and the same run continued: cycles `6f07c00b` and `b81fe47f`, with no second run and no duplicate child. | Claude governing session (operator proxy) |
| Consecutive cycles | `cycle-resume-evidence.json` | In drive 4 the applied proposal (M1, G1, T1) changed the advanceable set. The Stop hook reported "the advanceable set changed to 3 items; added G1,T1" and issued a second start directive in the same drive: cycle `05b31b9b`, then cycle `2e14173a`. | Claude governing session (operator proxy) |
| Exact activations | `cycle-resume-evidence.json` | Five start and two resume directive tokens were seen on screen. The ledger has five `Advance` workflow records (roots `Ideas1`, through `Plan`), one for each start, and none for the two resumes. | Claude governing session (operator proxy) |
| Settled afterwards | `cycle-resume-evidence.json` (`final`, `children`) | The four children (two Claude Planners, two Codex plan Reviewers) are `Completed`/`Settled`. After upload: `activeClaims` 0, `managedAttempts` 0, `pendingIntegrations` 0. | Claude governing session (operator proxy) |

For the driver-cases fixture, the governing session also observed zero active claims, managed attempts and pending integrations after both sessions exited (`handoff-after-exit.json`). That file is private and is not part of the projection.

The ledger-unchanged result has a stated scope in each result file (`snapshotScope`). It compares the complete Browse page of item revisions and summaries before and after. It is not a raw database byte comparison. Setup inputs that were rejected (upper-case `through` phases) changed no driver and are not counted as failure-stop evidence (`isolation-evidence.json`).

## Limits

These limits are copied from `cycle-resume-evidence.json` `limits` and the evidence README:

- Resume and consecutive cycles were observed in **separate drives of one session**, on the same frozen targets (I1 through plan): Resume in drives 1 and 2, two consecutive cycles in drive 4. They were not observed within one drive.
- Every cycles drive ended `Quiescent`, because each bounded turn ended with nothing in flight and no ledger change. This is the turn shape the session was instructed to use; it is not a defect observation.
- The cycles session ran with transcript saving off (an inherited child-session marker), so its outer usage was not captured.
- The host binary `release-e5dba67` predates T70 and T71. No package later than `e5dba67` was exercised.
- Pi was not run against a real server in these driver cases.
- The governing sessions observed all of this evidence; it has not been independently reread. The Claude cases were not run inside one consumer evaluation run.

## Codex counterparts

T59 recorded these cases earlier on Codex 0.159.2, against a private real CQ server and ledger (source `daf189a8`, run `eval-20261004-combined`, scenario `codex-driver`). The governing session observed them; they were not independently reread.

- Two concurrent real Codex sessions kept distinct native session keys and frozen worksets. The secondary session reached quiescence while the primary stayed active.
- Skipped start directive, untracked activation and out-of-set mutation (denying an I1 change) each ended in an explicit Failure stop. The entire Browse item revisions and content were unchanged, and no managed child started.
- Final fixtures had zero active claims, managed attempts and pending integrations.

The Claude and Codex initial and follow-up consumer integrations, and the Pi initial and follow-up integrations, are recorded separately in [the evaluation protocol](../evaluation-protocol.md#7-dependency-handling). They are not driver failure-stop evidence.

## Checks

This change is documentation and evidence only. No source code changed, and the agent ran no tests or gates for it. The host runs the configured `cq-fast` and `cq-ui` checks on the candidate. Those checks do not verify this empirical evidence, so acceptance rests on inspection and on independent review.
