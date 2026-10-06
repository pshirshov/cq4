# Durable child result admission

R27 correction design and implementation independently approved by Astra. The server decision, shared host finalizer and final verification pass; see [evidence](../validation/m3-result-admission.md).

## Reproduced race

`debug/20260927-060700-claim-admission.py` runs the real PostgreSQL server and unified supervisor with a deterministic harness. A forwarding proxy commits the governor's claim release immediately before forwarding the child result-artifact upload. At `444edf5`, the child still reports `Completed` with a result handle and delivered usage. Evidence: `/srv/nvme/tmp/cq4-implementation/20260927T060754-claim-admission-repro/observed.json`. The script fails with `Released claim admitted a late child result`. No production correction preceded this reproduction. The unchanged reproduction passes at `20260927T090134-claim-admission-repro`: the child is Failed, its result is absent, and usage is delivered.

## Server decision

Artifact upload stores immutable evidence; it does not itself admit a child result. A host-only admission request references the result artifact and governing owner. The server validates the canonical bounded child result, artifact provenance, registered attempt and frozen assignment. These records are immutable and may be read before entering the ledger transaction.

Inside the same project transaction used by release and takeover, admission first replays an identical existing decision keyed by project/attempt. Otherwise it checks full current claim membership, exact owner/fence/member IDs and all frozen item revisions, then persists an accepted or rejected decision atomically. The record binds the artifact identity and server-computed digest, owner, fence and revisions. Changed intent under the same attempt conflicts. Authorization and malformed envelopes fail without creating a decision.

The registered attempt's role is the role of the result's work. The one other case is the governing session's own attempt in a project in the YOLO mode, decided inside the transaction; see [process modes](workflows.md#process-modes).

Only a Collector credential owning the registered session may request admission. Governors and subordinate roles have no admission capability. Authorized reads may inspect its bounded record. Prior-result consumption requires the matching accepted admission in addition to the existing artifact digest, member and current-claim checks.

Acceptance records validity at the admission transaction's linearization point. A later claim release does not rewrite the decision or defeat an identical retry after an uncertain acknowledgement. Later ledger application and Git integration still require their own current fences, revisions and target checks.

## Host publication and recovery

`ResultAdmissionService` is the core service contract; its implementation and HTTP transport are injected through the existing distage graph. `/api/admission` is host-only; `read/Admission` exposes the bounded decision without admission authority.

`ChildPublicationDelivery` is the shared host finalizer used by `ChildRunner` and `SessionDelivery`:

1. Seal and force a complete immutable `ChildPublication` intent before publishing any new result-related evidence. Freeze artifact/observation identities, bytes or durable references, collection time, admission identity, and all final-outcome derivation inputs.
2. Upload artifacts and usage observations. This stage contains no terminal success outcome.
3. Obtain or replay the durable admission decision.
4. Derive and commit the terminal usage outcome and bounded receipt solely from the sealed inputs and decision. Rejected results never become parent-visible result handles; their usage still publishes.

The evidence queue has its own atomic final-directory seal, followed by a small `publication.json` intent. The finalizer derives the canonical result upload directly from the intent's result; callers cannot supply a second result body. The initial delivery queue registers the assignment/attempt and input evidence; its final stage receives the derived terminal outcome. Evidence cannot be flushed by the child finalizer until both seals are durable.

An unknown admission acknowledgement leaves `PublicationPending` without a result. Recovery recognizes sealed intents before the generic interrupted-publication `Unknown` fallback and replays them without recollecting output or choosing new timestamps. Pending local status is separate from the immutable final receipt, so successful replay can finish it without overwriting a final fact.

## Required checks

- Release before admission rejects the result while preserving usage. Admission before release remains accepted and replayable.
- Concurrent admission requests produce one immutable decision; altered artifact, owner or assignment conflicts or fails authorization.
- Both accepted and rejected decisions survive acknowledgement loss and later expiry/release.
- Partial intent sealing produces no external candidate publication and follows existing unknown-recovery behavior.
- A sealed intent replays identical bytes and timestamps even if native output files later grow.
- Final-outcome acknowledgement loss preserves the same outcome identity/body and does not duplicate spend.
- Prior-result assembly rejects absent/rejected/mismatched decisions.
- An independently supplied result artifact cannot disagree with the result used to derive the receipt.
- PostgreSQL and manual in-memory adapters run the same service scenarios; actual supervisor/recovery checks exercise the publication boundary.
