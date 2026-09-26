# M1 interim Astra review

Reviewed source: `4c743cb`, read-only interim review by the existing Astra reviewer. This is not a milestone exit approval. Findings are source-derived; runtime reproductions and correction review are pending.

| Finding | Reproduction to execute | State |
| --- | --- | --- |
| Operator confirmation applicability | Human confirms action A; Governor changes action or expected evidence while preserving the confirmation string | Open |
| Persisted draft concurrency/retry identity | Restore a revision-1 draft after another session writes revision 2; separately suppress a committed create acknowledgement and reload/retry | Open |
| Direct usage within a cohort | Register shared execution C and a direct member attempt associated with C; current Direct assignment validation rejects C | Open |
| Attempt coverage gaps hidden | Finish after one complete observation with a cancellation gap; current summary/audit omit that outcome gap | Open |

The reviewer withdrew a suggested defect about Governor corrections without fences after claim release. New ordinary corrections and admission of results from old attempts have different authority contexts. The latter remains M2/M3 work. The test labelled “Human correction” under a Governor scope should be renamed or use a Human scope.

Remaining planned gaps (not additional findings): compact results/bounds, isolated workspaces, attempt-bound result admission, and deferred M5 interactions. Compact results/bounds are being implemented after this review. Request a full M1 review after required work and reproductions are complete.

## Follow-up design review

Astra accepted separate paginated attempt and outcome-history views for the coverage correction; the observation audit need not combine lifecycle events. Required conditions: include attempts without observations, use a server-assigned outcome sequence, keep idempotent replay from changing effective state, retain traversable previous outcomes, report running/attempt gaps separately from meter completeness, define explicit authorized outcome correction, and do not let late samples clear unrelated outcome gaps. Direct assignments may carry cohort execution identity and retain direct task attribution. UUID-ordered attempt pagination must declare its snapshot behavior. Implementation review remains pending.
