# Independent review of the evaluation protocol, 2026-10-05

This record covers the two independent reviews of [the evaluation protocol](../evaluation-protocol.md) after its revision for the T61 matrix of 2026-10-04/05 ([matrix record](t61-evaluation-matrix-20261005.md)).

**Provenance.** Both reviews were read-only. Each was made by a separate reviewer session, not the author of the revision, dispatched by the operator's governing Claude session on 2026-10-05. The author of the revision wrote this record from the verdicts and findings as that governing session relayed them; the author did not see the reviewers' own output.

**Limits.** The reviewers are model sessions. The evidence they read is private fixtures under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/`. They could not reach the ledger, so no ledger state is verified by them. The review accepts the protocol page; it does not decide Goal G4, whose audit is separate.

## Review 1, of commit `7d078c5`

Verdict: **Accepted with required edits.** About 40 figures were sampled against the six run reports and the raw files.

Required edits:

1. Governor share: word the threshold as a provisional reference ceiling at 11.3×, not a tolerance, and give the ratios on the all-input basis as well.
2. State the unknown cache-read counters of the Pi run exactly, and call the ratio approximately an upper bound.
3. Planning rework: D145 explains only the failed, unretried Pi attempt; say that the other G1 rounds were not examined.
4. Section 7, G1: state per run which drive commands were typed.
5. Add a normative rule for a quiescent stop with a root blocked from outside the set: escalate and wait, and never change the evaluation ledger through the operator API.
6. Make the layout the matrix used binding in sections 2 and 5, and the 45-second idle rule in section 1.
7. Document the in-sandbox launch form the matrix used, with pinned executable paths and private configuration directories.
8. Define the order of S5, with the Codex exit line read from the cast and the server stopped last.
9. Give the command sequence with its syntax for each harness.

Lesser findings: whether per-attempt token sizes are obtainable; `formal2-codex` was ended by the driver session's judgment and not by a stop rule; the operator gave instructions during the matrix; the line counts of the final screens; one date form for the matrix; the Governor-share flag recorded as Idea I33.

All were applied in commit `e468547`.

## Review 2, of commit `e468547`

Verdict: **Accepted: may be marked ready.** The nine required edits were found satisfied. The reviewer recomputed the Governor-share ratios (7.215, 11.275 and 6.079 on cache reads; 6.339, 9.023 and 5.178 on all input) and checked the six proxy logs, the `launch.sh` rows, the S5 order against `s5.sh`, the unknown counters and the exit-line totals.

Four minor, non-blocking inaccuracies:

1. Section 5: the monitor saved idle screens into `formal/stops/<harness>-<epoch>.txt` of the matrix tool directory, not into `screens/stop-<epoch>.txt` of every root.
2. Layout variants: some snapshots are named `s<n>-stop.json`, and `formal3-codex` has no `snapshots/`.
3. Section 1: `formal3-codex` took its provider credential from an environment variable named in the provider definition, not from a login file.
4. Section 8: the run reports quote the model instructions only; the budget allowance is recorded in the Setup section of the matrix record.

These four were corrected in the commit that added this record and set the status line of the protocol to ready. **No reviewer has reread that commit.**
