# Typed ledgers and durable transactions

Implemented contract: the single [cq.api 0.1.0 model](../../models/cq-api.baboon). Edit it in place during development; version bumps require explicit user instruction. These contracts are shared by the service, authenticated transport, CLI and browser.

## Content and outcomes

Every item has a required title, Markdown body, label set, archive flag, typed content and citation list. Empty body/citation/label collections are valid. Titles contain 1–300 characters; bodies at most 65,536; at most 32 nonempty labels of at most 80 characters. The content branch determines the ledger. Callers cannot register a ledger or change an existing item's ledger. Operator intake records need only a title: an Idea's outcome and motivation, a Defect's observed, expected and reproduction texts, and a Goal's outcome, acceptance and scope may be empty (still bounded in length) and are completed by planning.

Archival keeps finished work visible while it is still needed: a terminal item cannot be archived while any item related to it in either direction is unarchived and not terminal, and the archive preview (`ReadSelection.ArchivePreview`) lists such retained items with the open items that keep them.

The schema lists exact required, optional and collection fields. Every content branch has its own closed status type. Collections are required even when empty; `opt` fields explicitly represent absence. Acceptance lists for goals/tasks are nonempty. A review identifies at least one item revision or candidate commit. Required narrative fields are nonempty; present optional narratives must also be nonempty. Nested collections contain at most 64 entries. URL citations require absolute HTTP(S) addresses, file citations a nonempty path, and commit citations a nonempty repository and hexadecimal commit identifier. Review subjects must identify existing revisions in the same project. A complete draft is limited to 262,144 encoded UTF-8 bytes. Status values never impose transition restrictions.

| Ledger/prefix | Domain fields beyond status | Terminal statuses | Dependency-satisfying status |
| --- | --- | --- | --- |
| milestones/M | objective | Complete, Cancelled | Complete |
| ideas/I | outcome, motivation | Accepted, Declined, Withdrawn | Accepted |
| defects/D | severity, observed, expected, reproduction, optional cause, resolution evidence | Resolved, NotReproducible, Rejected, Withdrawn | Resolved |
| goals/G | outcome, acceptance, scope | Achieved, Abandoned | Achieved |
| tasks/T | acceptance, optional result, validation evidence | Done, Cancelled | Done |
| researches/RS | question, findings, optional conclusion/recommendation | Concluded, Inconclusive, Cancelled | Concluded |
| hypothesis/H | claim, rationale, evidence, optional adjudication | Supported, Refuted, Inconclusive, Withdrawn | Supported, Refuted |
| questions/Q | prompt, context, alternatives, optional answer | Answered, Withdrawn | Answered |
| decisions/K | choice, rationale, alternatives | Superseded, Withdrawn | Adopted |
| reviews/R | reviewed revisions, optional candidate, findings, optional summary | Approved, ChangesRequested, Cancelled | Approved |
| handoffs/HO | outcome, remaining work, blockers | Accepted, Cancelled | Accepted |
| operatorActions/OA | action, expected evidence, optional confirmation, observed evidence | Observed, Failed, Cancelled | Observed |
| memories/MEM | knowledge, applicability, evidence | Superseded, Retracted | Current |
| upstream/U | component, version, reproduction, optional report/outcome | Resolved, Declined, Withdrawn | Resolved |

These are nominal outcome classifications. Readiness must additionally explain missing evidence and stale reviews; a status does not establish that validation was observed. In particular, operator confirmation is not observed completion. Facts may be corrected and records reopened. Archive is independent of these classifications.

Executable examples for all fourteen ledgers are in `LedgerContractTest`. They create records through the same application service using either the dummy or PostgreSQL repository. The schema represents citations separately from canonical ledger relations. Typed review subjects pin applicability to a revision; they do not create a second mutable graph.

## Transactions and identity

`LedgerService[F]` implements BIO operations against `LedgerRepository[F]`. A transaction is scoped to one authenticated project. The PostgreSQL adapter acquires that project's row lock, executes bounded row operations, and commits or rolls back the entire operation. Reads currently acquire the same lock for a coherent cursor/content view. This deliberately serializes a project's transactions; [access/lock measurements](../validation/m3-query-access.md) verify same-project blocking and independent-project progress, without claiming same-project parallel throughput. It does not load or rewrite the project.

The migration has relational primary/foreign keys for projects, counters, items, edges, history, requests, committed changes and claims. Generated Baboon JSON is persisted as JSONB with schema versions. Metadata indexes support project/ledger/archive/status access; full-text search uses a normalized word stream and a generated GIN-indexed word array. The [shared query language](query-language.md) implements Boolean expressions, text/phrases, typed attributes, canonical/inverse relationship filters, snapshot pagination and completion metadata.

Creation allocates from the project/ledger counter inside the same transaction as the item, revision-one history, change event and request acknowledgement. The stable compound identity is project UUID, ledger and positive signed 64-bit number. A replay under the same actor/session/request returns the original acknowledgement. Reusing that identity with a different payload is a conflict. Failed batches leave no history, change event or acknowledgement. An ordinary batch contains 1–64 operations and currently may change each existing item only once.

The committed change cursor is incremented under the project lock and published in the transaction. This avoids treating a sequence allocated before commit as a committed watermark. A snapshot returns its cursor; subsequent events can be read after it in ascending order. Changes are retained without automatic truncation. Invalid cursors explicitly require resynchronization. Search returns generated summaries without full narratives; PostgreSQL stores the summary with each changed item. Search/history/change pages are bounded by 200 rows and 512 KiB, including an envelope allowance, with whole-record continuation. Search continuation supplies the original snapshot cursor and explicitly resynchronizes if the project changed. Live transport replays committed changes from the snapshot cursor.

History stores complete item content and its inverse-derived reference view for every changed endpoint. Pagination is by revision descending. Restore creates a new revision while retaining prior history and creation time. It reconstructs content and incident relationships from the selected history entry. Callers must supply current revisions for exactly the changed relationship endpoints; claims and graph invariants apply to every endpoint. All affected neighbors receive history revisions, preserving their own content. Conflicts reject the entire transaction. The maximum touched set is 512 items per change request.

## References

Canonical relations are DerivedFrom, PartOf, BlockedBy, Reviews, Supports, Contradicts, Supersedes and RelatesTo. Their inverses are Produces, Contains, Blocks, ReviewedBy, SupportedBy, ContradictedBy, SupersededBy and RelatesTo respectively. Symmetric edges use deterministic endpoint order. Inverse mutations normalize to one row. Duplicate additions/removals produce an acknowledgement without a new item revision. Actual membership changes revise both endpoints atomically.

All targets must exist in the same project, including archived records. Self references are rejected. PartOf targets a milestone, permits process artifacts rather than milestones/intake/goals as members, and permits at most one milestone per member. Reviews originates at a review. Other relation endpoint types are unrestricted. Cycles are permitted; [worksets](worksets.md) implement bounded visited-set traversal, separate context and informational readiness, while [termination](termination.md) applies relation-specific selection and preservation rules. Each item currently supports at most 200 incident references, with explicit failure beyond that bound.

## Claims and authority

Only Human/Governor service scopes may mutate ledgers or claims. Authenticated scope is a trusted adapter input, never mutation payload. Signed project/role credentials enforce the same restrictions at transport boundaries. Declared evidence cannot fabricate human or host provenance. Only a Human actor can introduce HumanReported evidence or operator confirmation; unchanged recorded evidence can be preserved. [Result admission](result-admission.md) validates host-observed evidence against admitted execution artifacts.

Claims atomically cover explicit sets of 1–64 existing items. The owner includes subject, session and role. Leases last at most thirty minutes and use monotonically increasing project fences. Overlapping active claims are rejected as a set; no partial acquisition remains. An active claim requires its owner and fence on edits. An explicitly supplied expired/released/replaced fence is rejected even if the item is otherwise unclaimed. Ordinary authorized corrections can proceed after a claim ends without supplying a stale job fence.

Claim identity retries return the original active claim. Renewal checks ownership and expiry. Release replay does not rewrite membership, preventing an old release from displacing a new owner. A new acquisition after expiry/release needs a new claim identity. [Producer/descendant coordination and reviewed takeover](claims.md), [host result admission](result-admission.md), [hierarchy cancellation](local-dispatch.md), [filesystem isolation](workspaces.md) and [Git integration](git-integration.md) are implemented. Claim fencing controls ledger authority; the local supervisor/guardian owns process cancellation and reconciliation.

Whole-subgraph [termination](termination.md) uses the same transactional change journal and typed status policy. Its preview preserves factual artifacts, exposes exclusions and claim effects, and applies only the reviewed snapshot. It does not add a transition gate to ordinary corrections.
