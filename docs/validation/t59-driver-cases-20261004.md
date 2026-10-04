# Real-harness driver cases against real CQ ledgers (T59), 2026-10-04

This record covers T59: the auto-driver cases that T9 revision 6 and G1 require for real harnesses on real CQ servers and ledgers. Those cases are two concurrent sessions, exact start and resume activations, and the three Failure stops that leave the ledger unchanged (skipped directive, untracked activation, out-of-set change). The cases must pass for Claude Code as well as Codex.

**Provenance.** Everything below comes from private fixtures that the governing sessions observed. Nobody has independently reread the private originals or the recordings. The readable evidence is the credential-free projection in [`evidence/t59-driver/`](evidence/t59-driver/README.md). It has two parts:

- **The first projection.** It was copied unchanged from `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/t59-evidence-sanitized`. It holds the Claude Code result files, the isolation and park snapshots, and `cycle-resume-evidence.json`. Its index is kept as [`first-projection-README.md`](evidence/t59-driver/first-projection-README.md).
- **The second projection.** It answers audit 12ae7bb1 and was copied unchanged from `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/t59-evidence-sanitized-2`. It holds the activation crosswalks for both harnesses, the readable Codex evidence (`codex/`) and the complete before and after item pages of every Failure-stop case on both harnesses (`codex/*-before.json`, `codex/*-after.json`, `claude-before-after/`). Its README is now the directory's index.

A reviewer can recompute this record's statements from those files, but the files are projections, not the originals. The originals stay private under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/{claude-driver-cases,claude-cycles,codex-recorded}`. The recording digests are in the two READMEs and the crosswalk files. The `token` values are one-time directive tokens of finished cycles that were shown on screen. They are not credentials.

**What this record does not do.** It does not declare G1 Achieved. G1 still needs independent review of this record. It is not formal I12 qualification, and for Claude Code it covers no package later than `e5dba67`.

## Setup

- **Harnesses:** real Claude Code 2.1.285, with the assets `cq configure claude` generated from the `release-e5dba67` package. Real Codex 0.159.2 (`codex-recorded-activation-crosswalk.json` `fixture`; `codex/driver-acceptance-progress.json` `harness`).
- **Server and ledger:** private CQ servers with private ledgers. The Claude driver cases used project `261e0d41-…`, and the Codex cases used project `d7328d62-…`. Both report installation version `0.1.0` in their item pages (`installation.version`).
- **Date:** 2026-10-04. The Codex recordings were made between 14:16 and 15:13 UTC, and the Claude recordings between 20:25 and 21:41 UTC.
- **Recordings:** each session was recorded with asciinema. The Claude connected casts and the cycles cast end with exit event 0. The digests are in the evidence READMEs and crosswalk files.
- **Uploads:** the native Claude session uploads exited 0.

## Per-activation crosswalk

**Method** (from the `method` field of each crosswalk file). Issued directives are every distinct token the hook printed in the terminal recording, with the time each first appeared. Accepted activations are the workflow records of the attached host session directory, each with the driver cycle it was admitted under. A start directive is paired with the next workflow record in time; for Codex, this must happen within one minute. A resume directive is paired with the run and cycle current at that time, and it writes no workflow record. The pairing is **by time**. The recordings themselves are not in the repository.

### Claude Code 2.1.285, cycles fixture

Source: [`claude-cycles-activation-crosswalk.json`](evidence/t59-driver/claude-cycles-activation-crosswalk.json) (recording sha256 `b11ae728…`). Attached session `ec0758d4-…`. Every accepted activation is workflow `Advance`, roots `Ideas 1`, through `Plan`. The file counts 7 directives issued and 5 `Advance` workflow records, with no unpaired `Advance` record. The only record without a cycle is the session's `Begin` (run `6d8977a5`).

| # | Drive | Issued: kind, token, shown (UTC) | Accepted: run | Roots / phase | Cycle | Record (UTC) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | start `12b5cc80`, 21:33:43 | `0e25386a` (new record) | Ideas 1 / Plan | `6f07c00b` | 21:33:52 |
| 2 | 1 | resume `a20269c3`, 21:34:35 | resumed `0e25386a`, no new record | same run | `6f07c00b` | none |
| 3 | 2 | start `95c0995f`, 21:35:41 | `7c51e0f8` (new record) | Ideas 1 / Plan | `b81fe47f` | 21:35:49 |
| 4 | 2 | resume `3c133b48`, 21:36:38 | resumed `7c51e0f8`, no new record | same run | `b81fe47f` | none |
| 5 | 3 | start `fda41327`, 21:38:25 | `bd5f2c2a` (new record) | Ideas 1 / Plan | `f7f63f61` | 21:38:36 |
| 6 | 4 | start `237d4d5c`, 21:40:01 | `e4bb5138` (new record) | Ideas 1 / Plan | `05b31b9b` | 21:40:08 |
| 7 | 4 | start `f0880b3d`, 21:40:27 | `c6bb9381` (new record) | Ideas 1 / Plan | `2e14173a` | 21:40:30 |

Rows 6 and 7 are the two consecutive cycles in one drive: same roots and phase, 26 seconds apart. Rows 2 and 4 are the resumes. Each continued the run of the preceding start without writing a second workflow record. The drive column comes from the `observations` of `cycle-resume-evidence.json` (first projection), which places cycles `6f07c00b` and `b81fe47f` in drives 1 and 2, `f7f63f61` in drive 3, and `05b31b9b` and `2e14173a` in drive 4. The crosswalk itself does not number drives.

`cycle-resume-evidence.json` `directiveTokensSeenOnScreen` lists only five tokens (three start, two resume). It omits the start tokens `95c0995f` and `237d4d5c`. The crosswalk lists all seven, and it is the authoritative list for directives issued. The run IDs and record times of the five `Advance` records agree between the two files.

### Codex 0.159.2, recorded fixture

Source: [`codex-recorded-activation-crosswalk.json`](evidence/t59-driver/codex-recorded-activation-crosswalk.json). It has two recordings. No Codex resume directive was recorded.

**Main recording** `session-cq-only.cast` (sha256 `93257638…`), session A, attached session `d965219b-…`:

| # | Issued: kind, token, shown (UTC) | Accepted: run | Roots / phase | Cycle | Record (UTC) |
| --- | --- | --- | --- | --- | --- |
| 1 | start `afd27262`, 14:16:47 | `dd3fca96` (`Advance`) | Ideas 1 / Plan | `39a1b469` | 14:16:59 |
| 2 | start `499a8060`, 14:22:24 | `5d8c438a` (`Advance`) | Ideas 1 / Plan | `a26826e9` | 14:22:35 |
| 3 | start `aafaa66b`, 14:39:51 | `3f0ce894` (`Advance`) | Ideas 1 / Integrate | `41fe1b6e` | 14:39:59 |
| 4 | start `00045a33`, 15:13:06 | `36e96ca1` (`Advance`) | Ideas 2 / Integrate | `9835fe40` | 15:13:12 |

The projection README calls rows 1 and 2 consecutive cycles on Ideas 1 through Plan. Session A's driver then stopped `Quiescent` on cycle `a26826e9` with "The previous cycle changed nothing in the advanceable set, its context or its readiness" (`codex/drivers-after-isolation.json`).

**Isolation recording** `session-isolation.cast` (sha256 `e12ade39…`), session B, attached session `3aeaadf9-…`:

| # | Issued: kind, token, shown (UTC) | Accepted | Case | Driver cycle at the stop (result file) |
| --- | --- | --- | --- | --- |
| 1 | start `dfabc0a6`, 14:26:08 | none: no workflow record within one minute | skipped directive | `b91acc44`, stopped 14:26:11 |
| 2 | start `e808eca5`, 14:29:28 | none: no workflow record within one minute | untracked activation | `783c3b18`, stopped 14:29:30 |
| 3 | start `8f635117`, 14:33:02 | run `c79f166e` (`Advance`), Defects 1 / Explore, cycle `5b015c3d`, recorded 14:33:12 | out-of-set change | `5b015c3d`, stopped 14:33:20 |

The case assignment follows from the times and cycles. Each directive appears 2–18 seconds before the stop of the matching case in `codex/{skipped,untracked,out-of-set}-result.json`. The accepted out-of-set activation carries cycle `5b015c3d`, which is the cycle in `codex/out-of-set-result.json`. The crosswalk notes do not name the case of each unaccepted directive; the README names them only together.

## Claude Code cases

| Case | Evidence file | Observed (from the file) | Observed by |
| --- | --- | --- | --- |
| Two concurrent sessions | `isolation-evidence.json`, `isolation-bound-projection.json` | Session A (key `025c7fb0-…`, attached `cb681df6-…`) is On for D1 through Explore, with one managed child. Session B (key `9f225874-…`, attached `e15d0f82-…`) binds its own targets, Q1 through Plan, and stops `Quiescent` while A stays On. The two sessions have distinct keys, attached sessions and frozen targets. That snapshot shows one active claim and one managed attempt, both belonging to A's child. | Codex governing session |
| Skipped directive | `skipped-result.json`, `claude-before-after/skipped-{before,after}.json` | Driver of B on D2 through Explore: stop `Failure` "directive not started: the start directive of cycle 1 was not submitted", cycle `cc261e1f`. Before the case, B's driver is `Quiescent` on Q1 (revision 85); after it, B's driver is `Failure` (revision 96). | Codex governing session |
| Untracked activation | `untracked-result.json`, `claude-before-after/untracked-{before,after}.json` | Same driver: stop `Failure` "untracked activation: the bound attached session activated a workflow without a start or resume token", cycle `0f9e1aa7` (driver revision 96 → 101). | Codex governing session |
| Out-of-set change | `out-of-set-result.json`, `claude-before-after/out-of-set-{before,after}.json` | Same driver: stop `Failure` "out-of-set change: I1 is outside the advanceable set stored for cycle 1", cycle `9ac66998` (driver revision 101 → 107). | Codex governing session |
| Independent park | `park-both-on.json`, `park-a-only.json`, `park-both-off.json` | First both are On (Q2 and Q3 through Plan). Parking A sets A Off/`Parked` while B stays On. Parking B then sets both Off. The requested hold never ran; the state snapshots are what establish park isolation. | Codex governing session |
| Resume | `claude-cycles-activation-crosswalk.json`, `cycle-resume-evidence.json` | In drives 1 and 2 the session ended a turn with a child running. The Stop hook issued a resume directive (crosswalk rows 2 and 4), the session ran it, and the same run continued: cycles `6f07c00b` and `b81fe47f`, with no second run and no duplicate child. | Claude governing session (operator proxy) |
| Consecutive cycles | `claude-cycles-activation-crosswalk.json`, `cycle-resume-evidence.json` | In drive 4 the applied proposal (M1, G1, T1) changed the advanceable set. The Stop hook reported "the advanceable set changed to 3 items; added G1,T1" and issued a second start directive in the same drive (crosswalk rows 6 and 7): cycle `05b31b9b`, then cycle `2e14173a`. | Claude governing session (operator proxy) |
| Exact activations | `claude-cycles-activation-crosswalk.json` | Seven directives were issued: five start and two resume. There are five `Advance` workflow records (roots `Ideas 1`, through `Plan`), one for each start, none for the two resumes, and no unpaired record. | Claude governing session (operator proxy) |
| Settled afterwards | `cycle-resume-evidence.json` (`final`, `children`) | The four children (two Claude Planners, two Codex plan Reviewers) are `Completed`/`Settled`. After upload: `activeClaims` 0, `managedAttempts` 0, `pendingIntegrations` 0. | Claude governing session (operator proxy) |

For the driver-cases fixture, the governing session also observed zero active claims, managed attempts and pending integrations after both sessions exited (`handoff-after-exit.json`). That file is private and is not part of the projection.

The Claude Failure-stop cases come from the `claude-driver-cases` fixture. Neither crosswalk covers that fixture's recordings, so their directives are not mapped per activation. The crosswalk covers the Claude cycles fixture only.

## Codex cases

The Codex governing session observed these cases ([`codex/driver-acceptance-progress.json`](evidence/t59-driver/codex/driver-acceptance-progress.json), [`codex/negative-source-verification.json`](evidence/t59-driver/codex/negative-source-verification.json) `origin`: "Governor observed retained primary snapshots; no independent child reinspection implied"). The Failure-stop item pages are copied in full.

| Case | Evidence file | Observed (from the file) |
| --- | --- | --- |
| Two concurrent sessions | `codex/driver-acceptance-progress.json`, `codex/drivers-after-isolation.json` | Session A: attached `d965219b-…`, native session key `01a10721-…`, targets Ideas 1 through Plan. Session B: attached `3aeaadf9-…`, native session key `01a10745-…`, targets Questions 1 through Plan. Session B's `Context`/`Driver` first returned null. B's own Q1 Plan bind stopped `Quiescent` ("No item of the advanceable set is ready to advance") at 14:19:02 UTC (`stoppedAt` 1791123542164), with no cycle. A then admitted its second cycle `a26826e9` at 14:22:35 (crosswalk) and stopped `Quiescent` at 14:22:45 (`stoppedAt` 1791123765205). The listing shows distinct keys, distinct attached sessions and distinct frozen targets. |
| Skipped directive | `codex/skipped-result.json`, `codex/skipped-{before,after}.json` | Driver of B on Defects 1 through Explore: stop `Failure` "directive not started: the start directive of cycle 1 was not submitted", cycle `b91acc44`, `activeChildren` 0. Before the case, B's driver is `Quiescent` on Q1 (revision 15); after it, B's driver is `Failure` (revision 32). |
| Untracked activation | `codex/untracked-result.json`, `codex/untracked-{before,after}.json` | Same driver: stop `Failure` "untracked activation: the bound attached session activated a workflow without a start or resume token", cycle `783c3b18`, `activeChildren` 0 (driver revision 32 → 37). |
| Out-of-set change | `codex/out-of-set-result.json`, `codex/out-of-set-{before,after}.json` | Same driver: stop `Failure` "out-of-set change: I1 is outside the advanceable set stored for cycle 1", cycle `5b015c3d`, `activeChildren` 0 (driver revision 37 → 43). |

In all three Codex before and after pages, session A's driver is unchanged: Off, `Quiescent`, cycle `a26826e9`, revision 28. B's Failure stops did not touch A.

The Codex pages chain byte for byte. `skipped-after.json` is identical to `untracked-before.json` (sha256 `154afdde…`), and `untracked-after.json` is identical to `out-of-set-before.json` (sha256 `f1159a08…`). These are the file digests that `codex/negative-source-verification.json` records. The Claude pages are separate snapshots (distinct `at`), and B's driver state chains across them as listed above.

## Before/after comparisons

The ledger-unchanged result compares the complete Browse page of item revisions and summaries before and after a case. Every page has `hasMore: false`. It is not a raw database byte comparison (`snapshotScope` in each result file). Setup inputs that were rejected (upper-case `through` phases) changed no driver and are not counted as failure-stop evidence (`isolation-evidence.json`).

[`before-after-recomputed.json`](evidence/t59-driver/before-after-recomputed.json) is the Claude governing session's recomputation from the copied pages. It compares the sorted list of (ledger, number, revision, status, title) for every item:

| Harness | Case | Items | Identical | Canonical sha256 (governing session) |
| --- | --- | --- | --- | --- |
| Codex | skipped | 8 | yes | `8ebab492…` |
| Codex | untracked | 8 | yes | `8ebab492…` |
| Codex | out-of-set | 8 | yes | `8ebab492…` |
| Claude Code | skipped | 15 | yes | `14fea255…` |
| Claude Code | untracked | 15 | yes | `14fea255…` |
| Claude Code | out-of-set | 15 | yes | `14fea255…` |

The items compared are as follows:

- **Codex:** Defects 1 r4 Open, Goals 1 r2 Open, Ideas 1 r5 Proposed, Memories 1 r1 and 2 r1 Current, Milestones 1 r1 Open, Questions 1 r2 Answered, Tasks 1 r1 Ready.
- **Claude Code:** Defects 1 and 2 r1 Open; Goals 1 and 2, Ideas 1 and 2, and Milestones 1 and 2 at their terminal statuses; Questions 1–3 r2 Answered; Tasks 1–4 r2 Done.

Both lists show the out-of-set target I1 unchanged.

The T72 worker recomputed the same comparison from the copied files and also compared every complete `summary` object. Both comparisons were identical in all six cases. The canonical serialization behind the governing session's digests is not recorded, so those two digests were not reproduced. `codex/negative-source-verification.json` states the Codex result in its own terms: `unchangedCompleteItemRevisionsAndSummaries: true` in all three cases, with a different canonical digest (`9d232125…`) for the same reason.

The pass results (`result: Passed`, `itemsAndRevisionsUnchanged` / `allItemRevisionsAndSummariesUnchanged: true`) in the result files are model-declared by the governing session that observed them. The copied before and after pages are what make the comparison recomputable.

## Limits

These limits come from the second projection README, `cycle-resume-evidence.json` `limits` and the first-projection README:

- The recordings are not in the repository; their digests are in the crosswalk files. Directive-to-record pairing is by time.
- No Codex Resume was observed. Resume is Claude Code evidence only.
- On Claude Code, Resume and consecutive cycles were observed in **separate drives of one session**, on the same frozen targets (I1 through plan): Resume in drives 1 and 2, two consecutive cycles in drive 4. They were not observed within one drive.
- Every Claude cycles drive ended `Quiescent`, because each bounded turn ended with nothing in flight and no ledger change. This is the turn shape the session was instructed to use; it is not a defect observation.
- The Claude cycles session ran with transcript saving off (an inherited child-session marker), so its outer usage was not captured.
- The host binary `release-e5dba67` predates T70 and T71. No package later than `e5dba67` was exercised by Claude Code.
- `codex/driver-acceptance-progress.json` describes its fixture as preparatory evidence, not the qualified formal I12 matrix. Its line "Claude equivalent cases remain unexecuted" was true when it was written. The Claude cases above were run later the same day.
- Pi was not run against a real server in these driver cases.
- The governing sessions observed all of this evidence, and it has not been independently reread. The Claude cases were not run inside one consumer evaluation run.

The Claude and Codex initial and follow-up consumer integrations, and the Pi initial and follow-up integrations, are recorded separately in [the evaluation protocol](../evaluation-protocol.md#7-dependency-handling). They are not driver failure-stop evidence.

## Checks

This change is documentation and evidence only. No source code changed. The agent ran no tests or gates for it: no behaviour changed, so no fail-before test applies. The host runs the configured `cq-fast` and `cq-ui` checks on the candidate. Those checks do not verify this empirical evidence, so acceptance rests on inspection and on independent review.
