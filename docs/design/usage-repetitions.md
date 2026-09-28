# Matched operational usage baseline

This is the predeclared R09 repetition protocol, approved by independent Astra review. It supplements the existing accepted nine-route corpus. It establishes a baseline only for the configuration below; it does not establish thresholds for the other configurations or demonstrate token savings.

## Fixed experiment

Run exactly three fresh instances of the existing two-task Python word-frequency consumer with Claude governor, Codex Planner, Claude Worker and Pi Candidate Reviewer, followed by independent Codex/Astra whole-scope assessment through the existing evaluator. Freeze runtime/evaluator sources, specification, oracle, role assets, models/providers, harness versions, settings and declared checks before the first run. Each run gets a fresh project, repository and session identities. Run sequentially. Preserve the invocation order and every attempted producer/assessor session.

Use the configured provider caches normally and report their observed counters per run. This is a natural-cache experiment: cache flushing, a controlled cold/warm condition and provider billing are not assumed. Configuration or source changes start a different comparison group. Do not replace failed runs to obtain three successes. Run the independent assessment whenever a candidate is eligible; a failed producer remains in the denominator and its spend remains in the numerator.

## Measures and outcomes

Use CQ's existing operational usage audit and query services. Count each attempt and independent assessment once. Report producer, assessor and combined components: known input/output/total tokens, cache and reasoning counters with availability, direct/shared/governing attribution, calls/retries/drill-downs, elapsed wall time, and provider-estimated costs with their recorded bases and unknown portions. Cache and reasoning subsets are not added again to token totals. Payload bytes are separate from model tokens.

Elapsed time starts with the producer invocation and ends after its final assessment/archive, including setup, correction/retry work and assessment. Also report producer and assessor elapsed components separately. Preserve missing or ineligible assessment state explicitly. Report accepted quality as a count out of three. A scenario is accepted only when the unchanged objective checks and independent whole-scope quality assessment pass; lower spend cannot compensate for a failed requirement.

Report all-run median, minimum/maximum and sample standard deviation for comparable observed usage and timing. Report successful-run statistics only as a separately labelled subset. The combined known tokens per accepted scenario use **all** attempts' known tokens divided by accepted scenarios; the value is undefined when none are accepted. Zero accepted scenarios fail baseline quality. Keep optional unavailable counters/pricing distinct from missing required input/output observations, which fail instrumentation. Three samples provide a small descriptive baseline, not a statistically reliable population estimate.

## Predeclared regression handling

- Objective correctness, accepted process quality, missing required instrumentation, attribution/deduplication defects and dispatch contract-bound violations are independent failures.
- For a later matched run of this configuration, combined known token usage or total elapsed time above **twice the baseline three-run maximum** triggers investigation. This is a coarse operational tolerance, not a significance test. Investigate cache-condition/configuration drift and retained failures before interpreting a difference.
- Compare parent-visible normal dispatch payloads against the existing host bounds, retaining explicit full-body drill-downs separately. An end-to-end savings claim requires an additional matched comparator with accepted quality and all child/assessor work included; this protocol makes no such claim.
- UI-only M5 increments reuse this and the existing harness evidence. Run affected checks when harnesses, dispatch or shared contracts change. M6 verifies the packaged executable and its declared release corpus separately.

Record the exact three invocations, frozen configuration/source manifest, run IDs, final outcomes, formulas and derived report beside the retained evidence before claiming this protocol executed.

## Executed group and next comparison group

The first group at `/srv/nvme/tmp/cq4-implementation/20260928-m4-usage-repetitions` executed exactly three repetitions at `feffdc6`. Its frozen protocol/report records two native acceptances, one rejection and complete required input/output instrumentation. Subsequent mixed-help reproduction and an explicit argument-error precedence clarification yield zero accepted candidates under that clarification. The original report remains unchanged; separate quality adjudication records the failed baseline. See [evidence](../validation/m4-usage-repetitions.md).

After the oracle clarification is verified and committed, start a separately declared group at `/srv/nvme/tmp/cq4-implementation/20260928-m4-usage-clarified`. Run exactly three fresh repetitions using the same roles/models/harnesses, natural-cache policy, accounting, timing and assessment procedure above, with the current clarified specification and 136-case oracle frozen before launch. Retain failures and do not add replacement repetitions. Apply the same predeclared statistics and 2×maximum investigation rule only to this group's configuration and accepted-quality result. Preserve the first group's spending as experimental work; do not pool the two groups as comparable samples or claim savings from their difference. A separate native Pi/Python cohort and assessment will restore its affected corpus quality evidence; the exact retained Go candidates already pass the corrected oracle. This is scoped quality correction, not a rerun of all nine routes.
