# T59 driver evidence, second projection (after the G1 audit of 2026-10-04)

Added in answer to audit result 12ae7bb1: a per-activation crosswalk, readable Codex evidence, and the before/after ledger pages of every failure-stop case. Credential-free projections of private originals under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/{claude-cycles,claude-driver-cases,codex-recorded}`. Governor-observed; a reviewer can recompute from these files but has not read the originals or the recordings.

| File | Content |
| --- | --- |
| `claude-cycles-activation-crosswalk.json` | Claude Code 2.1.285: all seven directives the hook issued (five start, two resume) with the time each first appeared in the recording, each paired with the accepted activation: run, roots, phase and driver cycle from the attached session's workflow records. Five workflow records for five start directives; the two resume directives returned the run and cycle current at that time and wrote no record. Consecutive cycles in one drive: the fourth and fifth start (cycles 05b31b9b and 2e14173a, 26 seconds apart, same roots and phase). |
| `codex-recorded-activation-crosswalk.json` | Codex 0.159.2: the main recording has four start directives, each paired with its workflow record; the first two are consecutive cycles on Ideas 1 through Plan (39a1b469, a26826e9). The isolation recording has three start directives: the first two have no workflow record (the skipped and the untracked case), the third was activated (the out-of-set case). No Codex resume directive was recorded. |
| `codex/driver-acceptance-progress.json`, `codex/drivers-after-isolation.json` | Codex: two concurrent sessions, distinct native session keys and worksets; B stopped Quiescent while A continued. |
| `codex/{skipped,untracked,out-of-set}-result.json`, `codex/negative-source-verification.json` | Codex failure stops with their Failure details, as observed by the Codex governing session. |
| `codex/*-before.json`, `codex/*-after.json`, `claude-before-after/*` | The complete item pages before and after each failure-stop case, for both harnesses. |
| `before-after-recomputed.json` | The Claude governing session's own recomputation from those pages: identical (ledger, number, revision, status, title) lists in all six cases. |

Limits: the recordings themselves are not in the repository (digests are in the crosswalk files); the pairing of a directive with a workflow record is by time, as the method field states; no Codex Resume was observed; Pi is not covered here.
