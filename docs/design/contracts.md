# Contract boundary

Authoritative schema: `models/cq-api.baboon`, the single `cq.api` version `0.1.0`. By explicit user instruction on 2026-09-26, edit this model in place and permit breaking changes; bump the version only when the user explicitly requests it. Historical schema copies, conversions and backward compatibility are outside the current development scope.

`dev/generate` pins Baboon 0.0.196 by SHA-256, replaces generated output, refreshes the current model signature, emits Scala/TypeScript codecs and applies the documented Scala runtime compiler fixes. The signature records the current shape without freezing it. Generated files are ignored and regenerated. The [ledger contracts](ledgers.md) describe M1's typed content and transaction services.

## Wire representation

The generated codecs encode `i64` as decimal JSON strings. JavaScript consumers use `bigint` internally. Always call generated codecs; generated record `toJSON()` is not the wire encoder. `ProjectId` wraps a UUID. `Revision` wraps a signed 64-bit integer. The proof includes 9,007,199,254,740,993 and the signed 64-bit maximum, Unicode text, canonical identifier parsing and a typed conflict error.

Existing strict checks on local commands and host artifacts verify decode/re-encode losslessness with `JsonRoundtrip.lossless`. Generated Set codecs may reorder JSON arrays, so this validation compares recursive array multisets with multiplicity while retaining exact object keys and scalar values. It neither modifies decoded values nor defines semantic equality: List order, immutable request identity and original artifact digests keep their existing semantics. Duplicate Set members, undeclared fields and noncanonical scalar loss remain rejected; [the connected cohort reproduction](../validation/m4-cohort-selection.md#connected-splitting-accounting-and-fairness) covers the 32-root failure and correction.

HTTP application/host requests require `CQ-Protocol-Version: 0.1.0`; `/api/hello` advertises that single version. Clients/server must be built from the same development schema. `/api/call` accepts generated `Command` and returns `Result`; `/api/usage` accepts host-only `HostUsageInput`. Browser login also supplies a persistent `CQ-Session` UUID after operator authentication so an uncertain mutation can be retried after signing in again. `BrowserDraft` stores the original item revision and exact pending request; full request bodies stay local to that editor. `/ws` exchanges correlated `ClientFrame`/`ServerFrame` values with committed replay cursors and heartbeat nonces. [Authority and identity details](../validation/m1-interfaces.md).

`/api/artifact` accepts host-only `ArtifactUpload` and returns immutable `ArtifactMetadata`. The existing `read` capability supplies metadata and explicit bounded text pages; [artifact bounds and permissions](artifacts.md) apply.

Earlier evolution fixtures were removed under the single-version policy. Their prior results remain historical evidence in the M0/M1 validation records. Current-schema history and backup/restore remain required; compatibility with records written by superseded development schemas is not promised.

## MCP capabilities

Stateless Streamable HTTP exposes `search`, `read`, `graph`, `change`, `claim`, and `usage`, with concrete generated input/output schemas and role-filtered discovery plus call enforcement. Initialization negotiates 2025-03-26, 2025-06-18 or 2025-11-25. The optional SSE GET returns 405. These protocol versions are separate from the single CQ model version. The independent TypeScript MCP SDK 1.30.1 exercises discovery and calls.

The existing `read` capability exposes an exact [termination preview](termination.md); a single `Mutation.Terminate` applies its snapshot through `change`. Unsupported outcomes and foreign claim owners are visible in preview; stale graph/claim/fence state rejects the transaction.

Baboon OpenAPI generation has reproduced ADT wrapper, open-record and nullable-field mismatches. `dev/schema.py` corrects these before packaging the schemas: fixed records, including empty branches, reject undeclared properties; every declared property is required, with explicit `null` for absent optional values, matching the generated Scala decoder. Generated codec examples cover every branch. Dispatch collections and child narratives also carry their advertised bounds. Schema closure keeps unrelated definitions out of each tool's advertisement. Protocol behavior follows the [transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) and [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) specifications. Harness integration/cancellation evidence remains for M2.

## Native structured outputs

`CodexAdapter` translates nested generated `oneOf` unions to `anyOf` for the [OpenAI structured-output subset](https://developers.openai.com/api/docs/guides/structured-outputs). `CodexSchema` requires disjoint declared types (treating integer and number as overlapping), or distinct single-key closed tagged objects. Ambiguous unions and root unions are rejected before launch. It follows local references for this proof and translates schema positions without mistaking property names for keywords. The provider also rejects `uniqueItems`; native schemas replace it with an explicit uniqueness description. The generated canonical schema and strict host decode/re-encode validation still reject duplicate Set members. This is an explicit provider enforcement gap, not equivalent validation: the native schema admits duplicates that CQ rejects. Both `canonical-result-schema.json` and `result-schema.json` are retained in private launch assets. All other constraints and canonical MCP schemas remain intact. Contract checks inspect the actual adapter assets for all four report shapes, verify precisely these two translations and validate each reachable definition/union branch, including the duplicate-admission difference. The [live cohort reproduction](../validation/m4-live-cohorts.md) retains the original provider rejection.

Pi has no native output-schema argument in the adapter. The host appends the complete canonical output contract to its system instructions before recording the prompt artifact and launching the native process. The 32-KiB instruction bound remains enforced; malformed reports still fail normal host validation. This addresses the retained [live Planner shape failures](../validation/m4-live-cohorts.md#pi-full-report-contract-delivery--in-progress).

## Verification

`./dev/check contracts` checks deterministic generation, strict TypeScript and Scala compilation, JSON/UEBA round trips, lossless integers and every generated schema branch. `./dev/check postgres` runs shared scenarios and actual HTTP/WS/MCP/CLI clients against an isolated database. See [interface evidence and remaining gates](../validation/m1-interfaces.md).
