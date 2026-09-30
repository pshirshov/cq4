# Work claims and producer coordination

R27 implementation contract. Claims cover explicit assignment sets, distinct from optimistic revisions, project transaction locks and Git target concurrency.

## Explicit membership

Acquire 1–64 existing IDs atomically for the authenticated Human/Governor actor. An overlap with any active claim fails the entire acquisition, including overlap with another claim held by the same actor. Disjoint task claims can run concurrently even when the tasks share a producer or milestone. A root workset is a selection aid, not an implicit hierarchical lock: execution must claim the exact assigned member set.

Claims preserve owner, membership, the complete original acquisition intent (including requested duration), and a project-monotonic fence. Leases last at most thirty minutes. The host renews the claim covering a running child every 20 seconds ([local dispatch](local-dispatch.md)) and the one covering an integration or combination while it prepares them (a renewal never shortens a lease), so a governor renews only claims it holds outside running work. Renewal cannot change the owner/members/fence. Release invalidates the complete set. Ordinary edits require the current owner/fence for each actively claimed endpoint. Supplied expired/released/replaced fences cannot authorize a result. Scope checks remain independent of queries or supplied IDs.

Activity also requires every original member still to map to that claim. Reassignment of even one member invalidates the old claim as a whole, including after a backward wall-clock adjustment. Residual mappings do not revive an active conflict. An indexed, bounded membership read establishes this invariant; updating lease or release state never rewrites member ownership. Replaying an already completed release remains idempotent.

## Atomic production

`Mutation.Produce` takes an existing producer revision and 1–64 new drafts. It requires an active producer claim owned by the caller and the exact current fence in the change request. The transaction allocates child IDs, creates their `DerivedFrom` relationships, revises the producer once and records full history for all affected records. It publishes one change acknowledgement; an uncertain retry returns the same allocated IDs and cannot create duplicate descendants.

New children do not silently expand the producer claim or an already frozen execution assignment. They can be claimed separately once created. Two producers may explicitly reference the same existing child through ordinary revision/fence-checked relationship edits; the child's own claim prevents another session from independently starting it. Normal manual corrections retain their existing permissions: adding/removing a relationship checks both endpoints, including any producer claim. No implicit ancestor lock or milestone-sibling lock is introduced.

Atomic production avoids an orphan creation if the producer claim/revision is lost between allocating an item and attaching its provenance edge. Bounds, endpoint constraints and draft validation are checked in the same transaction; a partial failed production leaves no allocated IDs, references, revisions, history or acknowledgement.

## Reviewed human takeover

`ReadSelection.Claims` accepts the exact desired member set. It returns their revisions, every active overlapping claim with complete membership/owner, and a caller-bound snapshot. Collateral members outside the requested set are explicitly visible because takeover releases an overlapping claim as a whole. Expired/released claims are not active conflicts. The preview is bounded to 64 requested IDs, at most 64 overlapping claims and 512 KiB; it never silently omits a claim or member.

`ClaimAction.Takeover` is restricted to Human authority and specifies a new claim identity, the desired Human/Governor owner, members, duration and reviewed snapshot. The server recomputes the preview under the project lock. Any item/reference change, new/released/expired overlapping claim or different fence/owner/membership rejects it. Renewal of the same active claim does not invalidate the preview. Changes elsewhere in the project conservatively invalidate the item cursor.

Applying releases the reviewed claims and acquires the requested set with a new project fence in one transaction. Collateral members become unclaimed; they are not silently reassigned. The new claim records its acquisition origin and the human who authorized takeover. Idempotent retry returns that same active claim for identical owner, membership and origin (including duration, reviewed snapshot and authorizing human); different intent under the same identity conflicts. Expired/released claims require a new identity. Ordinary acquire cannot replay a takeover identity or vice versa.

Takeover/release does not assert OS process settlement. The supervisor observes claim loss, cancels within its documented deadlines and quarantines unconfirmed cleanup. [Durable result admission](result-admission.md) rejects stale ownership; [conditional Git integration](git-integration.md) separately controls target updates and reconciliation. A ledger lease cannot prevent an arbitrary stale process from writing its isolated files.
