# Transient worksets

`graph` takes an authenticated project, explicit root IDs and page controls. It returns selected work, context and informational readiness using compact summaries. A workset is computed for the request; no membership or ownership is stored on items.

## Selection and context

- Empty roots produce an empty workset. A missing root fails with `Missing`; cross-project roots or continuation IDs fail with `Denied`. Selecting all project work requires explicit roots, within the same bounds.
- Starting with every root, follow `Produces` and `Contains`. The latter expands milestone members only when the milestone is selected through roots or produced work. A milestone reached only through a member's `PartOf` reference is context and does not expand siblings.
- Include other incident neighbors as one-hop context, with the selected source and relationship that explain their presence. Context is not recursively expanded. A prerequisite's produced work, a contextual review's neighbors, or another producer's children do not become selected work.
- Shared descendants appear once. Producers outside the selected closure appear as context and generate `Shared` reasons; this does not automatically exclude the descendant from workset selection. Whole-subgraph termination has a separate, stricter shared-work exclusion policy.
- Visited sets settle cycles without recursion. Archived or terminal records remain visible and traversal continues through them, so active descendants are not lost behind an archived ancestor. Explicit roots are marked separately from other selected nodes.

## Readiness and authority

A selected item is ready when it is not archived, is not terminal, and every direct `BlockedBy` prerequisite satisfies dependencies under the existing per-ledger outcome policy. Cancelled work can be terminal without satisfying a dependency; successfully completed archived prerequisites still satisfy it. `Archived`, `Terminal` and `Blocked` reasons explain a non-ready selection. Context always has `ready = false` because it is not scheduled work. Shared membership is informational.

Readiness here concerns record state and direct dependencies. It does not assert claim availability, active-process settlement, independent-review applicability, or permission to mutate/integrate. Claims remain the authority for acquiring work. A ready active item can be continued by its current owner; this field is not a state transition gate. Corrections and reopening remain ordinary authorized mutations.

`ItemSummary.outcome` is generated from the typed content whenever an item changes. PostgreSQL returns that projection without loading full narratives; the dummy evaluates the same policy. No second status interpretation is introduced in graph traversal.

## Bounds and continuation

At most 64 roots and 1,024 distinct selected/context items are accepted. Each selected item has the existing maximum of 200 incident references. A traversal exceeding the item bound fails explicitly with `Limit` and produces no partial graph. Narrower roots are required; a small page size does not bypass the traversal bound.

The complete bounded closure is computed before pagination. Entries have stable `(ledger, number)` order and retain the existing 200-entry/512-KiB page ceiling. Selected/context/ready counts refer to this closure, not the current page or entire project. `hasMore` indicates remaining complete entries; full narratives are omitted.

The snapshot combines the project change cursor with a SHA-256 fingerprint of project identity and the canonical root set. Continuation requires that snapshot and an `after` ID in the selection/context. Changing roots is invalid; any committed item/reference change returns `Resync`, including newly attached descendants. Unrelated project-item edits conservatively invalidate the cursor too. Root ordering does not affect the fingerprint. The hash is a pagination consistency value, not authorization or a durable handle.

Each page recomputes the bounded closure under the existing project transaction lock. PostgreSQL reads the project cursor, one summary for each visited item and two indexed incident-edge views for each selected item. It does not scan project items or fetch their full bodies for traversal. Claims and their expiry are deliberately absent from the snapshot; callers check them separately before execution. [Measured access evidence](../validation/m3-worksets.md) records six SQL statements for a one-selected/one-context graph at each sampled project size; 10,000 and 100,000-item projects both visit five scan rows and fetch no narratives. Larger closures remain subject to the explicit traversal bound.

## Interfaces

HTTP and WebSocket use generated `Command.Graph` / `Result.Workset`; MCP exposes one read-only `graph` capability. Governing harness profiles permit it. Existing worker/reviewer native tool allowlists remain limited to their assignment reads. Server project scope is enforced independently of the supplied roots.

The administrative CLI uses the existing query command:

```sh
cq query --roots T1,M1 --limit 50
cq query --roots ''
```

Continue with the same roots, `--after <ID>` and `--snapshot '<returned snapshot JSON>'`. Roots cannot be combined with `--query` or `--complete`. The later M5 workset UI will consume this same operation.
