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

## Work in progress

I24. An item is in progress exactly while an active claim covers it. Activity is the rule above, evaluated at the server clock: not released, unexpired, and every original member still mapped to the claim (`ClaimPolicy.active`; the SQL form is `QuerySql.activeClaim`). This is derived on every read. No agent writes a status, no stored type or table changed, and the statuses named Active or Investigating remain values nothing in the system sets ([ledgers](ledgers.md)). Claims already precede every child ([local dispatch](local-dispatch.md)) and end without a writer, so the mark needs no cooperation from agents and does not survive a crashed host beyond the lease.

- A governor that holds a claim while no child runs keeps its items in progress; the mark names the owner.
- A running attempt without an active claim marks nothing, so an attempt that a crashed host left Running leaves no permanent mark.
- The mark means "claimed": a governor that holds a long lease and does not release it keeps the mark until the lease expires.
- An attached host (`cq host`) that ends in order releases the claims its session still holds (D148): after its children, integrations and combinations have settled or were cancelled and its Finish usage is committed to the local delivery queue, it releases every claim that a reply to its Governor's `claim` commands granted and none showed released (`SessionClaims`). A claim under which a child's sealed result still awaits admission (the child is `PublicationPending`) is not released, because the server admits a result only under its active claim and the replay by `cq job upload` or the next host startup would otherwise be rejected with `ClaimLost`; that claim ends with its lease (`DispatchController.undelivered`). A claim under a pending integration reservation stays as well: the server refuses its release with `IntegrationPending`. A combination whose publication is pending keeps nothing, since its replay only uploads the frozen plan. The release is best-effort: a refusal is logged, an unanswered request ends the attempt, and none of them fails the shutdown. A host that is killed, and a managed `cq run` session, whose Governor talks to the server directly, leave their claims to lease expiry. Who may take over a claim is unchanged.

Three read surfaces carry it:

- **Browse rows.** `BrowseItem.work` is an `ItemWork` (claim identity, owner, member count, lease expiry) on covered rows and absent otherwise. `Application` adds `ItemWork.attempt` (role, harness, start time): the latest attempt without a recorded outcome whose assignment covers the item and whose session is the claim owner's. Child attempts carry the governing session, so an attempt of an earlier session that never recorded an outcome is not attached to a later claim.
- **Query.** `wip:true` and `wip:false` ([query language](query-language.md)); the search tool uses the same predicate.
- **Work cursor.** `BrowsePage.work` and `ProjectCursors.work` are the project's fence counter, plus its claims that are released or expired at the read time, plus its attempts started, plus its attempts with a recorded outcome.

| Event | Cursor |
| --- | --- |
| Acquire | +1 (fence) |
| Takeover | +1 (fence) and +1 for each replaced claim (released) |
| Release | +1; a repeated release changes nothing |
| Expiry | +1 once the server clock reaches the lease end |
| Renewal | unchanged: only an unexpired, unreleased claim renews, and a renewal never shortens a lease |
| Attempt start, first outcome | +1 each; an outcome correction changes nothing |

The cursor only grows while the server clock does. After a backward clock adjustment, claims whose lease ended within the step count as unexpired again and the cursor steps back; the same can happen for one poll when a renewal commits just as a poll counts the lease as expired. Clients therefore compare it for difference, not order. The remaining gap: a backward step that cancels exactly against other claim or attempt events between two polls leaves the value unchanged, and loaded marks then stay as they are until the next event or reload.

The cursor is computed per live poll (every 500 ms per socket), without a browser timer, by counting the project's claims and attempts; both sets only grow, since nothing deletes them. Measured on PostgreSQL with one project of 3,000 items (2026-10-02, `EXPLAIN ANALYZE`, warm cache): 1.1 ms for the claim part and 1.2 ms for the attempt part at 5,000 claims and 5,000 attempts, 9.0 ms and 9.6 ms at 50,000 each. No index serves the counts; at tens of thousands of claims the next schema edit should replace them with stored counters or a per-project cache shared by the sockets.

