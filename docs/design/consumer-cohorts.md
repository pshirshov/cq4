# Live consumer cohort evaluations

Astra reviewed 2026-09-27 after source approval of JsonRoundtrip and connected cohort fixtures. The evaluator extension is implemented and under verification; real executions and independent candidate verdicts remain pending.

`./dev/consumer-eval HARNESS python|go --cohort` extends the existing consumer evaluator with an explicit cohort mode, retaining the existing 110-case wordfreq oracle and Python/Go language corpus. Seed a goal and two separate ready tasks using the ordinary authenticated API: counting/tokenization/sorting versus CLI/top/error/help, both with their own test/documentation acceptance. Store exact seed identities/revisions, all relationship mutations/history and source specification.

Run the installed managed advance workflow through review. Require selection-backed starts, exact claims and a compatible whole-group Planner assessment. Route runs:

| Governor | Planner | Worker | Reviewer | Language |
| --- | --- | --- | --- | --- |
| Claude | Codex | Claude | Pi | Python |
| Codex | Pi | Codex | Claude | Go |
| Pi | Claude | Pi | Codex | Python |

Verify routes from actual parent-linked attempts; these cover nine directed governor-child pairs only if all required children execute and qualify. Require the exact seeded task IDs/revisions, applicable compatibility and criterion/check mappings, retained decision provenance for Worker and Reviewer, shared assignments, separate member outcomes and complete host oracle success. Require fresh consumer-oracle validation authored by the candidate reviewer attempt; an inherited worker check or merely different handle is insufficient. Existing accepted_chain permits inherited checks, so add a cohort-specific acceptance predicate without weakening the original predicate.

Retain all failed attempts, native transcripts/configuration, repository/candidate, PostgreSQL archive, parent-visible dispatch measurements and ordinary operational usage exports. Require an audit observation with an Observed input/output counter and explicit coverage per attempt; estimated-only counters do not establish observed coverage. Release claims without editing exact reviewed revisions for the existing independent Astra assessment runner. After the governor exits, retain an atomic Claims preview and require unchanged exact member revisions with no active claim or integration hold before marking the candidate ready; the same check applies to solo consumer runs. That assessment must accept both member scopes on the exact retained candidate. Audit reconciliation must include assessor usage exactly once.

Add focused negative acceptance tests for unrelated seed, wrong revision, missing/inapplicable Planner assessment, missing/mismatched choice, wrong actual route/parent, inherited or falsely authored reviewer check, mixed per-member outcome and missing meter. Keep the joint 110-case oracle unchanged; one green command alone does not establish independent per-member acceptance.

Scope: live R16 related-item execution, actual named reviewer checks and refreshed nine-route evidence. No matched efficiency improvement is claimed. This increment does not establish R08 full intake/investigation/handoff/upstream process, integration, complete M4 or human acceptance; those remain subsequent work.
