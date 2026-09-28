# M5 browser milestone review

M5 implements R02/R23/R24 and the browser portion of R31 under the [implementation plan](../drafts/20260926-1549-cq-implementation-plan.md). Independent Astra approved the technical milestone with no remaining blocking or major finding. M2 human acceptance and the M6 packaged release/human acceptance remain open.

## Exit evidence

| Required behavior | Implemented and observed evidence |
| --- | --- |
| Query-driven workspace | [Query editor](m5-query-editor.md): ten actual browser scenarios, five completion kinds, replacement spans, keyboard selection/dismissal, positioned diagnostics and delayed response ownership. [Workspace](m5-workspace.md): top bar/three panes, keyboard traversal, resizable separators, independent scroll, live-update focus, narrow layout/long-content bounds and scoped metrics. |
| Live editing and drafts | [Response ownership](m5-response-ownership.md) and [edit completion/conflicts](m5-edit-completion.md): delayed item/project replies, local edits preserved during remote updates, visible optimistic conflicts, explicit current-base selection, acknowledgement completion without reversing navigation. Persisted drafts/exact pending requests survive reload and authentication loss. |
| Relationships/history | [Graph actions](m5-graph-actions.md): typed reference preview/add/remove/follow, historical content/edge restore, stale-neighbor rejection and exact retries after actual committed-acknowledgement loss. Revisions/history are verified through the actual API. |
| Snapshot/replay/resync | [Stale query snapshot](m5-graph-actions.md#stale-search-snapshot) restarts from a new first page after an actual stale continuation rejection. Existing item watch and reconnect fixtures restore current data independently of connection health. |
| Usage scope/accounting | [Recorded scopes](m5-usage-scopes.md): task/cohort/session/project navigation, frozen assignment membership and filtered audit, with direct/shared/unattributed totals separate. [Usage watches](m5-usage-watch.md): usage-only uploads/corrections update through an invalid query, coalesced catch-up, reconnection rewatch and stale audit captions; item revision/history remain unchanged. Exact costs and audit pages are retained. |
| Connection lifecycle/diagnostics | [Connection increment](m5-connections.md): nonce liveness, phase budgets, bounded pool/retries/diagnostics, native handshake timeout, short/long scheduling gaps, page/network/visibility handlers, terminal/manual recovery, teardown and desktop/narrow diagnostics. Simulated timing/lifecycle boundaries are explicitly distinguished from actual socket execution. |
| Final ownership corrections | [Connection increment](m5-connections.md#reproductions-and-corrections): immediately remove old project rows while its replacement search is pending; ignore old subscription errors after project/generation changes. Both failures are retained before correction. |

## Verification applicability

All M5 increments have individual retained browser evidence and independent Astra review. The final connection gate reruns the entire browser corpus against the current application source, real Scala HTTP/WebSocket server and isolated PostgreSQL. It adds ten controlled-boundary connection cases and a three-part native socket fixture. Final gate **`.work/evidence/20260928T035540-ui` passes**, with all 279 non-documentation/non-README source hashes matching the reviewed implementation. Ten selection cases now include both project-boundary corrections; the full corpus and native/controlled connection checks pass with empty browser/connection errors. Source manifests bind those observations to the implementation.

The usage protocol/service extension has separate passing generated-contract, dual-service, actual socket authorization and indexed-clock evidence in [m5-usage-watch.md](m5-usage-watch.md); the final presentation/connection increment does not change those inputs.

Per the user's verification-scope instruction, UI changes reuse the retained M4 Claude/Codex/Pi evidence. They do not rerun the expensive nine-route matrix. M6 must still execute the required packaged consumer release corpus.

## Limits and remaining release gates

- Chromium interactions, native sockets, generated codecs and the actual server are exercised. Browser-clock advancement and synthetic visibility/pagehide/freeze events are controlled simulations; physical sleep, BFCache admission and Firefox/WebKit behavior are not claimed.
- Keyboard/DOM accessibility assertions and inspected screenshots are evidence of those checks, not an assistive-technology human verdict.
- Dynamic forms cover all fourteen content branches; the browser fixture renders them all but does not create every ledger type through a form. Typed creation/validation of all ledgers has separate core/service evidence.
- Suspension can delay heartbeat detection. Deadline misses are application-probe observations, not inferred network packet loss. Usage unknowns/estimates remain labeled; no billing or efficiency-improvement claim is made from partial counters.
- Native packaging, Baboon flake integration, complete release checks, packaged consumer evaluations, operational release instructions and designated human acceptance are M6 work.

## Independent verdict

Two independent Astra reviews approved the final increment and M5 technical exit. The milestone reviewer independently recomputed all 279 source hashes with zero differences, checked the passed obsolete-subscription case and empty error reports, and inspected final desktop/narrow screenshots. Its interim subscription finding was reproduced before correction and now passes. Approval covers R02/R23/R24 and browser R31; designated human acceptance and M6 remain open. No harness/model evaluation was repeated for this UI-only milestone.
