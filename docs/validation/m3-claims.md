# M3 producer coordination and reviewed takeover

Implements the [claim contract](../design/claims.md) under R27, with explicit assignment sets and no implicit ancestor locks. This increment does not complete R27: atomic child-result admission, bounded claim-loss settlement and conditional Git integration remain separate work.

## Implemented behavior

- `Mutation.Produce` atomically creates children, adds provenance edges and revises the claimed producer. It preserves frozen claim membership and returns the same allocated IDs on request replay.
- `ReadSelection.Claims` reports the exact requested revisions and full overlapping claim membership, including collateral items. `ClaimAction.Takeover` binds Human authorization, target owner, members, duration and reviewed snapshot, then releases whole overlaps and acquires the requested set atomically.
- Claim origins retain complete acquisition intent. Renewal preserves that origin. Changed duration or origin under an existing identity conflicts.
- Claim activity requires the complete original membership still to be current. Initial assignment is separate from lease/release updates. The PostgreSQL membership lookup has an index on `(project_id, claim_id)` and a bounded result.
- The existing six MCP capabilities expose these generated contracts. No model version was added; `cq.api` remains the mutable 0.1.0 development model.

## Reproductions and corrections

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observed result | Correction |
| --- | --- | --- |
| `20260927T053738-fast` | 98/99 scenarios passed; reusing acquisition identity with a different duration incorrectly returned the old claim | Persist the original duration in `ClaimOrigin` and compare complete intent on replay |
| `20260927T054709-fast` | 104/105 passed; after expiry and reassignment, a backward clock change let the old claim renew and replace ownership | Verify full current membership and separate initial assignment from record updates |
| `20260927T055304-fast` | 104/106 passed; the partial-replacement reproduction also showed a residual member reviving the old group in preview | Use the same activity predicate in overlap checks, mutations, production and both previews |
| `20260927T055447-fast` | All 106 passed after correction | Retained regressions exercise old renewal/acquisition/release, partial replacement, residual edits/claims and replacement ownership |

The `20260927T054305-fast` generation attempt detected a local `Result.ClaimPreview` name shadowing the root type; the result branch is now `Result.Claims`. `20260927T055235-fast` stopped at a parenthesis error in the new partial-replacement test. Neither attempt is counted as a behavioral reproduction.

## Verification

The nine claim coordination scenarios run through the same public service contract against the manual in-memory adapter and PostgreSQL. They cover concurrent retries/takeovers, permissions and project scope, expiry/renewal/clock changes, stale snapshots, atomic allocation/history rollback, producer reference limits, frozen membership and collateral byte bounds. The byte-bound fixture includes 64 distinct claims of 64 members each; an excessive preview fails explicitly and a narrower preview preserves all collateral members.

`20260927T055659-fast` passes all 107 scenarios, the browser build, Pi bridge and evaluator fixtures. `20260927T055740-contracts` passes deterministic generation, Scala/TypeScript round trips, all 360 schema definitions and six MCP capabilities. The rendered native guide JSON is 27,834 UTF-8 bytes for Governor and 9,517 for Worker/Reviewer; these are fixed schema payload measurements, not token counts or efficiency improvements. `20260927T055836-postgres` passes all 61 PostgreSQL scenarios, actual HTTP/MCP production/replay/takeover/denial/collateral checks, roles and CLI, local dispatch/shutdown/recovery, and server SIGKILL/restart. `20260927T060328-access` passes all 54 measured operations and project-lock isolation. Astra independently approved the final increment after verifying the source, all four gates and the measured access evidence. No blocking or major finding remains in this increment; full R27/M3 approval remains open.

Access measurements use two projects, each seeded with 100, 10,000 and 100,000 unrelated items and claims. Preview requests one item overlapping a two-member claim; renewal checks both members. Recorded visits include rows removed by filters and loop counts, not just returned rows.

| Unrelated rows per project | Operation | Statements | Scan visits | Shared buffers | Reply bytes | Elapsed ms |
| --- | --- | --- | --- | --- | --- | --- |
| 100 | Claim preview | 5 | 412 | 15 | 823 | 20.8 |
| 100 | Renewal | 4 | 207 | 21 | 543 | 26.5 |
| 10,000 | Claim preview | 5 | 7 | 19 | 826 | 11.4 |
| 10,000 | Renewal | 4 | 5 | 23 | 544 | 12.8 |
| 100,000 | Claim preview | 5 | 7 | 23 | 827 | 15.8 |
| 100,000 | Renewal | 4 | 5 | 26 | 545 | 13.3 |

PostgreSQL chose sequential scans on the small membership table, within the existing 1,024-visit small-table budget. Both larger stages used `cq_claim_members_owner`; their visit counts stayed constant. The other project completed a read in 14.4 ms while the first project's lock was held. These are local samples, not production latency guarantees. Production source matches the final fast/contracts/PostgreSQL manifests; the access fixture subsequently restricted its index-choice assertion to the larger stages, consistent with the existing small-table policy. Later documentation changes do not alter executable behavior.

Run sequentially from the repository:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check access
```

Later workflow/UI work must expose takeover review and production through their complete user flows. These service/client checks do not constitute a new live harness evaluation or a token-efficiency claim.
