# Whole-subgraph termination

Implementation contract for R26; verification evidence is recorded separately. The current model remains 0.1.0.

## Preview and apply

Use `read` with `ReadSelection.Termination` to preview explicit roots and `Cancel` or `Complete`. The bounded response contains every visited summary, its effect, all active claims intersecting the final selected set to release and their full membership (including collateral members outside the selection), and a snapshot. `change` applies a single `Mutation.Terminate` with those same roots/intent/snapshot, exactly the preview's claim fences, a reason and an idempotency key. It cannot be mixed with other mutations.

The snapshot binds the project change cursor, caller identity, canonical roots, intent, effects and active claim identities/membership. Renewal of the same active claim does not invalidate it; expiry, release, acquisition or takeover does. Any committed item/reference change conservatively invalidates the preview, including a newly attached descendant. Apply recomputes under the project transaction lock and either commits the exact reviewed effects or rejects the whole operation. Lost acknowledgements replay through the existing change-request journal before checking current graph state.

No preview table, mutable plan cache or additional MCP tool is required. Preview output is capped at 512 KiB and 1,024 visited items; at most 64 explicit roots and 512 status changes are accepted. Exceeding the traversal/change/byte bounds returns `Limit`, with no partial preview or write.

## Selection

Start from the bounded workset closure, following only produced work and selected milestone membership. Prerequisite/context edges never expand work. Shared descendants with producers outside the selection require explicit inclusion as roots. A non-root item that is a prerequisite of any work in the original closure also requires explicit inclusion, even if a production path reaches it.

Remove these protected nodes, recompute reachability from roots, and repeat outside-producer exclusion until stable. This prevents an excluded shared branch from pulling in its own descendants; cycles settle by visited sets and a monotonically shrinking selection. Explicit roots override these exclusions. Contextual milestones do not include siblings. Every visited excluded item remains in the preview with a context, shared-producer, prerequisite or excluded-branch reason.

Archived nodes are traversed and keep their archive attribute. Existing terminal records and settled records (adopted decisions, current memories; see [ledgers](ledgers.md)) are preserved regardless of intent; cancellation never rewrites a factual result into a different conclusion, and termination never withdraws, retracts or completes a reference record in its live state. The preview can therefore preserve a terminal ancestor or a produced decision while changing active descendants.

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
| Decisions | Withdrawn (a proposed decision; an adopted one is settled and preserved) | Conflict: adoption needs an explicit decision |
| Reviews | Cancelled | Conflict: approval needs an applicable independent review |
| Handoffs | Cancelled | Conflict: acceptance needs an explicit decision |
| OperatorActions | Cancelled | Conflict: observed success needs explicit evidence |
| Memories | Preserve (a current memory is settled; superseded and retracted ones are terminal) | Preserve; supersede or retract a memory explicitly |
| Upstream | Withdrawn | Conflict: resolution needs an explicit outcome |

`DefectStatus.Withdrawn` expresses abandoned investigation without asserting rejection or non-reproducibility. Generic completion is an authorized declared management outcome for milestone/goal/task records; it does not fabricate validation, approval or host observations. Unsupported effects block the entire apply; callers can record the explicit typed factual result through an ordinary revision-checked edit and preview again. These bulk-operation rules do not restrict schema-valid corrections or reopening.

## Milestone closure gate

A termination does not close a milestone over a Task it leaves non-terminal (see [ledgers](ledgers.md)). It is judged on the state the termination would leave. A contained Task in the final selection becomes Done or Cancelled together with its milestone, so the gate concerns only a contained Ready or Active Task that the selection excludes: one with a producer outside the roots (`Shared`), one that blocks selected work (`Prerequisite`), or one left with an excluded branch (`ExcludedBranch`). A Task contained in a selected milestone is reached from it directly, so in practice the first two shapes occur.

- **Preview.** The milestone's entry carries `Unsupported` instead of `Change`, with the reason and the retained Tasks: "M3 cannot be changed to Complete while it contains non-terminal Tasks: T12 (Ready). Make each Done or Cancelled, or reassign it to another Open milestone, before closing M3. This termination leaves them unchanged; add them to its roots to terminate them with M3". The retained Tasks keep their `Excluded` entries, and `canApply` is false. The other entries are unchanged.
- **Apply.** A `Terminate` carrying that preview's snapshot is refused with `Invalid` and the same text, and commits nothing. The refusal comes after the stale-snapshot and pending-integration checks and before the generic conflict for other unsupported effects.
- **Not gated.** A termination that leaves every contained Task terminal applies as before: an exclusive Task is terminated with its milestone, roots that include the outside producer or the retained Task itself select it, and an excluded Task that is already Done or Cancelled does not hold the milestone open. A termination rooted at a Goal reaches the milestone of its Tasks only as context and leaves it Open.

The caller's remedies are to add the retained Tasks or their outside producers to the roots, to finish or cancel those Tasks first, or to reassign them to another Open milestone, and then to preview again.

## Active work

A governor may release only its own claims; another owner produces a visible conflict. A human may terminate claimed work regardless of owner. Both must echo exactly the reviewed claim fence set; extra or expired fences are rejected. Claims solely on excluded/context items remain untouched. Foreign-owner conflicts, unsupported effects and the milestone closure gate are visible in the preview (`permitted = false` / `Unsupported`), not deferred until apply. Releasing a claim fences its whole membership, which is shown explicitly even when some members are excluded from status changes. Claim release and item/history effects commit atomically.

The server's active-work observation is the claim registry, not proof of OS process liveness. The local supervisor detects claim loss on its bounded renewal loop and cancels the owned hierarchy; release invalidates subsequent fence checks. Authorized late usage still enters the separate audit. The existing runner has a check-to-publication interval, so this transaction alone does not establish race-free result admission. R27 must reproduce release after the final claim check and before publication, then establish atomic result/integration admission or quarantine. The preview cannot promise that a stale or unreachable process has already stopped. Process settlement/quarantine remains the supervisor's responsibility and requires process-level evidence separately from this ledger transaction.
