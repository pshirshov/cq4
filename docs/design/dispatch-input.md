# Dispatch input and child result contracts

This M2 increment implements host-side reference materialization and worker/reviewer result validation. It is a library boundary; the local dispatch endpoint, prompt assets, launch orchestration, compact status projections and candidate integration are still pending. The batch supervisor does not advertise these contracts yet. All types remain in the single mutable `cq.api` 0.1.0 model.

## Parent request

`DispatchRequest` contains request identity, a typed worker mode or candidate reviewer, configured harness, exact member revisions, guidance revisions, artifact handles, an optional previous-result handle, a claim fence and process limits. It contains no prompt, task narrative, result body, executable command, environment or output schema. Worker modes are `Implement`, `Probe` and `ResolveConflict`. Explorer/planner contracts and the remaining review modes are M4 work; their absence here does not change the required four-role inventory.

The contract admits 1–16 members, up to 16 guidance references and 8 artifact handles. Item references must be unique across members/guidance and belong to the governing project. Artifact handles must be distinct. The serialized request is bounded to 16 KiB. Generated JSON schemas expose collection limits and reject undeclared fields, including inside empty reviewer records. Wire properties are explicit: use `previous: null` when there is no preceding result. The strict host decoder rejects unknown/noncanonical fields and validates relational constraints and execution limits.

## Materialization

`InputAssembler` depends on `ServerApi`, the authenticated governing scope and a clock. It renews the supplied claim and requires the exact owner actor, fence and member set. It fetches each requested item and requires its exact revision. Guidance is input data and does not expand the work assignment.

Each input artifact is bounded to 128 KiB and fetched by Unicode code-point pages. Every page must preserve metadata, offsets, progress and the final-page flag. The assembled bytes must match the immutable artifact's byte count and SHA-256. Invalid Unicode, mismatched metadata, oversized inputs and stale references fail explicitly. The combined `ChildInput` is bounded to 192 KiB, including all materialized data and the previous result. It is for the host/child path, not the parent response.

Assembly checks a 60-second budget before each server call. An in-flight call retains the HTTP adapter's own deadline; this is not a hard 60-second cancellation timer. A final renewal rechecks the claim before returning. These renewals do not replace claim maintenance during execution or revision/fence checks at final admission. Reads establish the requested individual revisions; they do not claim an atomic multi-item snapshot.

## Result chaining

`ChildReport.Work` has exactly one disposition and bounded summary per assigned member. `ChildReport.Review` has exactly one verdict and bounded findings per member. Non-accepted reviews require findings. Reports cannot switch role, omit/add/duplicate members or introduce undeclared fields. Generated codecs are checked against the original JSON so permissive unknown-field decoding cannot admit extra instructions or authority.

The host-owned `ChildResult` envelope binds the report to the attempt, dispatch request, base and optional exact candidate commit. A candidate-ready worker or any candidate review requires a candidate. The envelope and report are bounded to 128 KiB. Candidate object existence, workspace ownership, observed validation and final admission belong to subsequent dispatch/integration work; a syntactically valid commit ID or report is not that evidence.

The previous-result handle must reference a JSON result artifact. The envelope must name the artifact's attempt and exactly the new request's member revisions. Candidate review requires a worker result containing a candidate. Host code loads and validates the full prior result directly into the next child input. The parent can forward its handle without reading the body. Results remain immutable and repeatably readable.

## Verification boundary

The shared assembly scenario runs over the production application services against both the handwritten in-memory repositories and PostgreSQL. It exercises paginated Unicode, exact claim ownership, stale revisions, repeatable resolution, candidate chaining and rejected released claims. A separate contract scenario exercises malformed role/member/result shapes. Schema checks reject copied prompt fields and oversized reference collections.

The request serialization stays the same size when only the referenced artifact body grows. This is a request-level invariant, not a measurement of complete parent dispatch traffic or model token efficiency. Those require the local control transport and real consumer evaluations. See [evidence](../validation/m2-dispatch-input.md).
