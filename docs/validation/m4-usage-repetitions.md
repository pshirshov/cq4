# M4 matched usage repetitions and quality correction

**The first group does not establish an accepted-quality baseline under the clarified argument-error contract.** All three planned repetitions ran; the frozen native assessments accepted two. Subsequent deterministic reproduction found mixed-help argument errors in all three candidates. Preserve both observations: the original contract did not explicitly state mixed-invocation precedence, and the current clarification requires argument errors to take precedence.

Evidence root: `/srv/nvme/tmp/cq4-implementation/20260928-m4-usage-repetitions`. Runtime/evaluator baseline: `feffdc6`. The frozen `protocol.md`, `experiment.json`, `run.py`, `report.py` and `checks.py` retain the predeclared experiment, 262 source hashes, script hashes, exact commands/configuration and all six invocations. Independent Astra approved setup after three boundary checks and historical native replay. The runner executed exactly three fresh Claude/Python two-task cohorts sequentially, each followed by Codex/Astra whole-scope assessment. No failed repetition was replaced.

## Frozen execution and accounting

| Run | Producer / assessor evidence timestamps | Producer known tokens | Assessor known tokens | Combined | Full invocation seconds | Original native verdict |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1 | `012020` / `012500` | 1,261,349 | 389,757 | 1,651,106 | 380.479499 | Both tasks accepted |
| 2 | `012640` / `013043` | 1,131,364 | 428,943 | 1,560,307 | 347.943584 | Both tasks accepted |
| 3 | `013228` / `013643` | 1,210,007 | 430,744 | 1,640,751 | 365.087591 | T1 accepted; T2 changes requested |

All timestamps are on 2026-09-28 UTC. Producer directories end in `-cohort-consumer-claude-python`; assessor directories identify their producer. `experiment.json` contains complete paths. Timing includes setup, eligibility replay and final archive, not just model execution.

The unchanged reporter passed full retained producer/accepted-assessor replay, unique attempt checks, frozen settings/source comparisons and producer-plus-assessor summary reconciliation. `summary.json` records **18 distinct attempts, 4,852,164 known tokens**, no missing required input/output observations and no attempts without meters. All 18 meters are partial because optional fields are unavailable. Counts are 4,795,070 input and 57,094 output; observed cache-read 4,436,945, cache-write 131,275 and reasoning 5,302 remain subsets, not additions. Optional cache-read/cache-write unknown counts are 1/28.

Attribution totals are 0 Direct, 1,539,924 Shared and 3,312,240 Unattributed known tokens. In these fixtures, Unattributed assignments are verified Governor activity; this is not a general equivalence. The report retains exact rational sums of provider estimates: approximately USD 1.3728046 Shared and 1.7945614 Unattributed, with six unknown-cost contributions and no pricing revision. These are partial estimates, not total cost or actual billing.

All-run known-token median/min/max/sample standard deviation are 1,640,751 / 1,560,307 / 1,651,106 / 49,703.9931. Elapsed seconds are 365.087591 / 347.943584 / 380.479499 / 16.2758. The frozen report's native-accepted subset is explicitly separate. Its 2,426,082 known tokens per native-accepted scenario includes spending on the rejected third run. **That denominator is not valid for the later clarified-quality result.**

Each producer made 14 dispatch calls, with maximum 679-byte arguments and 1,106-byte observed reply text. Assessors made 4/5/5 dispatch calls, with maximum 942-byte arguments and 693/1,770/1,770-byte replies. Producers made 3/2/3 explicit artifact-text drill-downs; assessors made none. Each producer received one rejected overlong claim-duration request. No repeated explicit Start/Select request identity was observed; implicit provider retries are unknown. Payload sizes exclude envelopes, duplicated structured content and model tokenization.

## Reproduction and operational clarification

Run 3's native Astra report `29de7c29-0e2e-3e25-990b-9182746ad9fd` requests changes because argument parsing returns help before validating errors. It predicts failures for unknown options, positional arguments and out-of-range `--top` combined with help; the Audit itself does not execute those cases.

`mixed-help-reproduction.json` records actual commands, candidate hashes, exit codes and stdout/stderr against all three unchanged retained candidates. Runs 1/2 reject errors before help but accept `--help --bogus`; run 3 accepts errors on either side. Standalone help succeeds in all three. `oracle-gap-before.json` records the original oracle accepting run 3 with 110 cases despite these observations.

The fixture now explicitly requires argument errors to take precedence in either order, with standalone help successful. The oracle tests every existing invalid class both before and after help: **136 behavior cases**. `oracle-gap-after.json` records all three candidates rejected at `--help --top` with the expected incorrect success output. The correction adds no CQ runtime or harness behavior.

`quality-adjudication.json` binds the original report and reproductions by hash. Under the explicit interpretation it records **0/3 accepted**, the same 18 attempts and 4,852,164 known tokens spent, and **undefined tokens per accepted scenario**. The original descriptive 2×maximum values, 3,302,212 tokens and 760.958998 seconds, are not promoted as an accepted-quality baseline. A new comparison group must be declared separately; it cannot replace these runs or be pooled as though the specification/oracle were unchanged.

## Applicability to earlier evidence

`prior-candidate-recheck.json` runs the corrected oracle in fresh clones checked out at the exact recorded candidate commit. It rejects the earlier M4 Claude/Python and Pi/Python cohorts, while the Codex/Go cohort at `2fc3ca15` and worked Go reopening at `d6f61c11` both pass all 136 cases. Original native execution, permissions, accounting and process-history evidence remains retained. Accepted-quality claims for the two Python candidates require renewed evidence.

The initial supplemental attempt is separately retained as `initial-prior-candidate-recheck.json`; it is not authoritative. One checkout was at its seed commit, and that attempt also tried additional valid-help combinations outside the narrow correction. The final recheck pins every candidate and uses only the stated error-precedence clarification.

`m2-candidate-recheck.json` separately checks all three exact M2 accepted candidates. The Codex/Go `614a6a5b` and corrected Pi/Python `2246ceda` pass all 136 cases; Claude/Python `020f8070` fails at `--help --top`. Its historical acceptance is qualified; renewed Claude/Python quality evidence is required before the human checkpoint can close.

The final fast gate `.work/evidence/20260928T014403-fast` passes 189 Scala scenarios plus bridge/evaluator checks; `source-comparison.json` records all 262 runtime/build/evaluator sources matching the final correction. Independent Astra approved the oracle clarification, frozen accounting, separate quality adjudication, final prose and scoped restoration plan. Its request to label the old thresholds is incorporated as `invalidatedHistoricalThresholdCalculations`, with promoted thresholds still null. Its source-applicability wording correction is incorporated in the milestone index. Full M4 approval, a new accepted-quality matched group and the affected native Python quality evidence remain open. No full nine-route rerun is implied by the oracle correction.
