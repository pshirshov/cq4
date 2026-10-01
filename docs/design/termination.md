# Whole-subgraph termination

Implementation contract for R26; verification evidence is recorded separately. The current model remains 0.1.0.

## Preview and apply

Use `read` with `ReadSelection.Termination` to preview explicit roots and `Cancel` or `Complete`. The bounded response contains every visited summary, its effect, all active claims intersecting the final selected set to release and their full membership (including collateral members outside the selection), and a snapshot. `change` applies a single `Mutation.Terminate` with those same roots/intent/snapshot, exactly the preview's claim fences, a reason and an idempotency key. It cannot be mixed with other mutations.

The snapshot binds the project change cursor, caller identity, canonical roots, intent, effects and active claim identities/membership. Renewal of the same active claim does not invalidate it; expiry, release, acquisition or takeover does. Any committed item/reference change conservatively invalidates the preview, including a newly attached descendant. Apply recomputes under the project transaction lock and either commits the exact reviewed effects or rejects the whole operation. Lost acknowledgements replay through the existing change-request journal before checking current graph state.

No preview table, mutable plan cache or additional MCP tool is required. Preview output is capped at 512 KiB and 1,024 visited items; at most 64 explicit roots and 512 status changes are accepted. Exceeding the traversal/change/byte bounds returns `Limit`, with no partial preview or write.

## Selection

Start from the bounded workset closure, following only produced work and selected milestone membership. Prerequisite/context edges never expand work. Shared descendants with producers outside the selection require explicit inclusion as roots. A non-root item that is a prerequisite of any work in the original closure also requires explicit inclusion, even if a production path reaches it.

Remove these protected nodes, recompute reachability from roots, and repeat outside-producer exclusion until stable. This prevents an excluded shared branch from pulling in its own descendants; cycles settle by visited sets and a monotonically shrinking selection. Explicit roots override these exclusions. Contextual milestones do not include siblings. Every visited excluded item remains in the preview with a context, shared-producer, prerequisite or excluded-branch reason.

Archived nodes are traversed and keep their archive attribute. Existing terminal records are preserved regardless of intent; cancellation never rewrites a factual result into a different conclusion. The preview can therefore preserve a terminal ancestor while changing active descendants.

## Terminal mappings

Only status fields change. Narratives, citations, findings, confirmation and recorded provenance evidence are retained; the mutation itself has the normal new revision and actor/request provenance.

| Ledger | Cancel active record | Complete active record |
| --- | --- | --- |
| Milestones | Cancelled | Complete |
| Ideas | Withdrawn | Conflict: acceptance needs an explicit decision |
| Defects | Withdrawn | Conflict: resolution needs explicit evidence |
| Goals | Abandoned | Achieved |
| Tasks | Cancelled | Done |
| Researches | Cancelled | Conflict: conclusion needs an explicit assessment |
| Hypothesis | Withdrawn | Conflict: adjudication needs an explicit assessment |
| Questions | Withdrawn | Conflict: answering needs an explicit answer |
| Decisions | Withdrawn | Conflict: adoption needs an explicit decision |
| Reviews | Cancelled | Conflict: approval needs an applicable independent review |
| Handoffs | Cancelled | Conflict: acceptance needs an explicit decision |
| OperatorActions | Cancelled | Conflict: observed success needs explicit evidence |
| Memories | Retracted | Conflict: a current memory has no completion; supersede or retract it explicitly |
| Upstream | Withdrawn | Conflict: resolution needs an explicit outcome |

`DefectStatus.Withdrawn` expresses abandoned investigation without asserting rejection or non-reproducibility. Generic completion is an authorized declared management outcome for milestone/goal/task records; it does not fabricate validation, approval or host observations. Unsupported effects block the entire apply; callers can record the explicit typed factual result through an ordinary revision-checked edit and preview again. These bulk-operation rules do not restrict schema-valid corrections or reopening.

## Active work

A governor may release only its own claims; another owner produces a visible conflict. A human may terminate claimed work regardless of owner. Both must echo exactly the reviewed claim fence set; extra or expired fences are rejected. Claims solely on excluded/context items remain untouched. Foreign-owner conflicts and unsupported effects are visible in the preview (`permitted = false` / `Unsupported`), not deferred until apply. Releasing a claim fences its whole membership, which is shown explicitly even when some members are excluded from status changes. Claim release and item/history effects commit atomically.

The server's active-work observation is the claim registry, not proof of OS process liveness. The local supervisor detects claim loss on its bounded renewal loop and cancels the owned hierarchy; release invalidates subsequent fence checks. Authorized late usage still enters the separate audit. The existing runner has a check-to-publication interval, so this transaction alone does not establish race-free result admission. R27 must reproduce release after the final claim check and before publication, then establish atomic result/integration admission or quarantine. The preview cannot promise that a stale or unreachable process has already stopped. Process settlement/quarantine remains the supervisor's responsibility and requires process-level evidence separately from this ledger transaction.
