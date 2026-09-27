# Stored proposals and the remaining child roles

M4 implementation contract; independently reviewed by Astra with no remaining substantive finding after authority/replay, semantic-preview and zero-eligible-member corrections. Not yet implemented. This extends the existing [dispatch input](dispatch-input.md), [durable result admission](result-admission.md) and ledger mutation transaction. It does not introduce a proposal ledger or an additional ownership protocol.

## Authority and representation

A proposal is typed data inside an immutable child result. Host code binds the result to its attempt, dispatch request, exact member revisions and claim fence, then uses ordinary result admission. The child supplies proposed content; it cannot supply the authenticated applying actor, request-journal identity, acceptance decision or replacement claim authority.

The first proposal vocabulary is deliberately the existing local mutation vocabulary: create a draft, replace an assigned member's draft, produce new descendants of an assigned member, or add/remove a relation between assigned members. Proposed operations omit request identity, fences and expected revisions; the service derives those from the admitted envelope and current applying authority. Restore and whole-subgraph termination retain their existing dedicated reviewed inputs and cannot be smuggled into a proposal. Guidance references provide context only and never authorize a write.

All existing endpoints of a proposal must be assigned members. Proposed `Produce` operations atomically create and attach descendants under an assigned producer. Newly allocated IDs return through the existing compact acknowledgement. Relations involving newly allocated items can be proposed in the next round using those returned IDs/revisions; there are no caller-allocated IDs or speculative local-ID resolver. A proposal may contain several operations, but the existing prohibition on changing an item twice in one batch remains explicit. The complete proposal commits atomically or fails without item/history/event/allocation effects.

The same current schema remains 0.1.0. Proposed changes, compact previews and child reports are generated Baboon types, not embedded arbitrary JSON fields. Normal request, batch, draft, reference and encoded-byte bounds apply; no result can bypass them by residing in an artifact.

## Preview and application

1. The governor requests a proposal preview by admitted result handle. The server loads and strictly decodes the result, checks its registered admission and assigned role/report, then validates every existing endpoint against the frozen assignment. A human may inspect the same preview; inspection is not application authority.
2. The preview returns the immutable result identity, author role, member IDs/revisions and bounded semantic operation summaries: new item ledger/title and producer, changed item/title, and exact relation endpoints/direction. Replacements include old/proposed typed status and archive state, plus explicit content/evidence/provenance-change indicators. A narrative edit is visibly different from declaring completion or replacing evidence. Full drafts/evidence stay behind the result handle. Omitted detail is explicit; source bodies are repeatably readable through ordinary bounded artifact reads.
3. Application supplies that handle. It requires the original admitted governor actor. The server derives a deterministic request identity from the immutable result handle and the exact normal `ChangeRequest` from its proposal. The child cannot select the applying identity or pass its own fences.
4. Authentication, original-governor ownership, exact admitted artifact/envelope binding and the derived request fingerprint are always checked, including on replay. Inside one project transaction, an identical already committed application returns its original acknowledgement before rechecking now-stale member revisions or leases. An ordinary change using the same derived identity with different content must conflict; it must never supply an unrelated acknowledgement. For a new application the complete claim must still be active and owned, and every assigned revision must still match. No write outside that set is permitted, apart from server-allocated new records.
5. The existing mutation engine checks data/provenance, revisions, pending integration reservations, endpoint rules and incident-reference bounds, then writes items, history, one change event and the request acknowledgement atomically. Proposal content cannot manufacture `HostObserved` evidence or an integration result. Lost acknowledgements replay the same request; a fresh result handle is a fresh proposal intent.

Preview is informational and leaves no durable approval token. Immutability identifies the content, and application repeats the necessary checks under the transaction lock. A stale result is retained for inspection; the host must obtain a current assignment/result before it can be applied. Manual schema-valid corrections keep their ordinary permissions.

The public surface adds `apply`, taking only project and admitted result handle, and exposes preview through `read/Proposal`. This yields seven domain MCP capabilities plus the existing local `dispatch` capability for governors. `apply` is denied to child roles even on direct calls; ordinary `change` retains its existing semantics. The ten-capability ceiling and native advertised-schema size gate remain enforced. The host never sends full proposal bodies through the parent as application arguments.

## Four roles, one lifecycle

| Role | Typed work | Result and capability boundary |
| --- | --- | --- |
| Explorer | Investigate or Research | Per-member evidence, uncertainties and requested probes; read-only CQ/source tools; no execution, source edits, domain writes or child dispatch |
| Planner | Plan | Per-member proposed/blocked/abstained outcomes and optional typed proposal; read-only CQ/source tools; no execution, edits, writes or dispatch |
| Worker | Implement, Probe or ResolveConflict | Existing isolated executable workspace and per-member outcomes; probe results must distinguish evidence from implementation candidates |
| Reviewer | Candidate, Plan or Audit | Per-member verdict/findings against exact candidate or result/revisions; read-only source and declared check execution; no candidate edits, writes or dispatch |

All four use the same reference-only request, claim maintenance, native launch, output validation, result admission, audit assignment and bounded projection. Role/mode selects installed semantic instructions and tool policy. A previous result is passed by handle; host materialization checks that its assignment/revisions and kind are appropriate for the mode. Candidate review continues to require the exact worker candidate; plan review takes the planner's result. No new role can turn an unaccepted or failed process into an admitted proposal.

Native Claude, Codex and Pi restrictions remain separately tested. Shared role contracts do not imply identical CLI flags or permission enforcement. Explorer/planner must not inherit reviewer execution permissions merely because all three avoid editing source.

Proposal eligibility is explicit:

| Role/mode | Report | Applicable proposal |
| --- | --- | --- |
| Explorer / Investigate, Research | Exploration | None; evidence/probe requests can feed a planner by handle |
| Planner / Plan | Plan | Optional; at least one member must be Proposed. Existing mutation endpoints must belong to Proposed members; Blocked/Abstained members remain untouched |
| Worker / all modes | Work | None; candidate/probe evidence feeds planning or review by handle |
| Reviewer / Candidate | Review | None; verdict/findings refer to the exact existing candidate |
| Reviewer / Plan, Audit | Review | Optional follow-up; at least one member must be ChangesRequested. Existing mutation endpoints must belong to ChangesRequested members; Accepted/Blocked members remain untouched |

Every report still covers every frozen member exactly once. Admission establishes successful process/shape and current ownership, not semantic reviewer acceptance. A reviewer does not accept its own proposed changes by emitting them. Where the process calls for independent review, the amended proposal/candidate receives a separate review of its exact identity. The governor owns the application decision; existing correction permissions are preserved.

Reviewer execution is limited to host-configured declared checks against the exact candidate, exposed through a bounded host operation. It does not receive unrestricted native shell tools. Such checks can have effects inside their assigned disposable workspace; they do not prove a hostile read-only OS sandbox. Explorer/planner receive neither that check operation nor native execution tools. Real probes must verify direct-call denial as well as advertised tool inventories.

## Verification criteria

- One shared service corpus against dummy and PostgreSQL verifies actual proposal application, per-member scope, stale/replaced claims, stale revisions, malformed/undeclared fields, inadmissible results, foreign project/session/actor, role denial and provenance denial.
- A proposal with only creations still requires an eligible Proposed/ChangesRequested member. All-Blocked/Abstained planner reports and all-Accepted/Blocked reviewer reports cannot carry applicable proposals.
- Concurrent identical application and uncertain acknowledgement yield one item/history/event effect. Failure after a preceding operation rolls back allocation and history. Released claims or later item edits do not prevent replay of an already committed application. Foreign actors remain denied on replay; an ordinary request with the same deterministic identity and another body conflicts.
- Actual HTTP/MCP/CLI exercise compact preview/application and direct forbidden calls. Growing draft/evidence bodies does not grow normal handle-only application traffic; preview count and byte limits are explicit.
- Controlled dispatch exercises each role/mode through the existing lifecycle, malformed outputs and permission profiles. Required real native role probes and nine-route evaluations remain M4 gates, separate from deterministic checks.
- Worked process examples use staged proposals to derive goals from intake and create milestone-organized work, and to turn investigation/research evidence into a reviewed fix. The parent forwards result handles rather than reconstructing planner output.
