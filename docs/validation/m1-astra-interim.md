# M1 interim Astra review

Reviewed source: `4c743cb`, read-only interim review by the existing Astra reviewer. This is not a milestone exit approval. Findings are source-derived; runtime reproductions and correction review are pending.

| Finding | Reproduction to execute | State |
| --- | --- | --- |
| Operator confirmation applicability | Human confirms action A; Governor changes action or expected evidence while preserving the confirmation string | Corrected; Astra recheck accepted; dummy/PostgreSQL pass |
| Persisted draft concurrency/retry identity | Restore a revision-1 draft after another session writes revision 2; separately suppress a committed create acknowledgement and reload/retry | Corrected; three Chromium regressions pass; Astra recheck accepted |
| Direct usage within a cohort | Register shared execution C and a direct member attempt associated with C; current Direct assignment validation rejects C | Corrected; Astra recheck accepted; dummy/PostgreSQL pass |
| Attempt coverage gaps hidden | Finish after one complete observation with a cancellation gap; current summary/audit omit that outcome gap | Open |

The reviewer withdrew a suggested defect about Governor corrections without fences after claim release. New ordinary corrections and admission of results from old attempts have different authority contexts. The latter remains M2/M3 work. The test labelled “Human correction” under a Governor scope should be renamed or use a Human scope.

Remaining planned gaps (not additional findings): compact results/bounds, isolated workspaces, attempt-bound result admission, and deferred M5 interactions. Compact results/bounds are being implemented after this review. Request a full M1 review after required work and reproductions are complete.

## Follow-up design review

Astra accepted separate paginated attempt and outcome-history views for the coverage correction; the observation audit need not combine lifecycle events. Required conditions: include attempts without observations, use a server-assigned outcome sequence, keep idempotent replay from changing effective state, retain traversable previous outcomes, report running/attempt gaps separately from meter completeness, define explicit authorized outcome correction, and do not let late samples clear unrelated outcome gaps. Direct assignments may carry cohort execution identity and retain direct task attribution. UUID-ordered attempt pagination must declare its snapshot behavior. Implementation review remains pending.

## First correction reproductions

`20260926T200009-fast/dummy-contract.log` reproduces two findings: the changed operator action is admitted (expected Denied), and a direct cohort member assignment fails with `Direct work requires exactly one item and no cohort`.

The confirmation check now preserves non-human confirmation only while both the action and expected evidence match the recorded human-confirmed action. Ordinary title edits remain possible; changing the action can proceed after removing the inapplicable confirmation. Direct assignments now allow a cohort identity while retaining exactly one member. `20260926T200116-fast` passes all 22 scenarios, including both corrections. PostgreSQL verification and reviewer recheck subsequently passed. Browser failure reproductions are running separately.

Browser reproductions: `20260926T200258-browser` confirms both failures against the original client. Reloading a stale draft changes the server to revision 3 instead of preserving revision 2; retrying an uncertain create produces two items instead of one. Traces and screenshots for both cases are retained.

`BrowserDraft` now persists the original item revision and exact pending `ChangeRequest` before transmission. Unresolved requests lock editing/discard until the retry outcome is known; authoritative domain rejection unlocks the draft. A completion from a previously selected editor cannot clear the current editor. `20260926T200529-browser` passes both reproductions plus the existing workflow/connection checks. Retry across a fresh sign-in is being checked separately because session identity participates in mutation deduplication.

`20260926T200719-browser` reproduces the additional sign-in boundary: a pending create retried after logout/login creates a second item because a new session changes the acknowledgement key. Login now requires `CQ-Session` after validating the operator secret. The browser stores a non-credential session UUID and reuses it when obtaining a new signed cookie. `20260926T200810-browser` passes stale-base, uncertain-create and uncertain-login checks, plus the existing workflow/connection corpus.

## Astra correction recheck

Astra inspected the corrections, failing reproductions and successful follow-ups and accepted the first three corrections without additional blocking/major findings. The reviewer explicitly requires PostgreSQL verification before closing the service corrections. The fourth finding (attempt coverage/outcome gaps) remains open. This is not M1 approval.

`20260926T200950-postgres` passes all 22 scenarios plus actual clients and SIGKILL restart, closing the service verification condition from the correction review. `20260926T201115-contracts` passes deterministic generation, strict Scala/TypeScript compilation and generated codec/schema checks.
