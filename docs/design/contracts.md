# Contract boundary

Authoritative schema: `models/cq-api.baboon`, the single `cq.api` version `0.1.0`. By explicit user instruction on 2026-09-26, edit this model in place and permit breaking changes; bump the version only when the user explicitly requests it. Historical schema copies, conversions and backward compatibility are outside the current development scope.

`dev/generate` pins Baboon 0.0.196 by SHA-256, replaces generated output, refreshes the current model signature, emits Scala/TypeScript codecs and applies the documented Scala runtime compiler fixes. The signature records the current shape without freezing it. Generated files are ignored and regenerated. The [ledger contracts](ledgers.md) describe M1's typed content and transaction services.

## Wire representation

The generated codecs encode `i64` as decimal JSON strings. JavaScript consumers use `bigint` internally. Always call generated codecs; generated record `toJSON()` is not the wire encoder. `ProjectId` wraps a UUID. `Revision` wraps a signed 64-bit integer. The proof includes 9,007,199,254,740,993 and the signed 64-bit maximum, Unicode text, canonical identifier parsing and a typed conflict error.

HTTP application/host requests require `CQ-Protocol-Version: 0.1.0`; `/api/hello` advertises that single version. Clients/server must be built from the same development schema. `/api/call` accepts generated `Command` and returns `Result`; `/api/usage` accepts host-only `HostUsageInput`. `/ws` exchanges correlated `ClientFrame`/`ServerFrame` values with committed replay cursors and heartbeat nonces. [Authority and identity details](../validation/m1-interfaces.md).

Earlier evolution fixtures were removed under the single-version policy. Their prior results remain historical evidence in the M0/M1 validation records. Current-schema history and backup/restore remain required; compatibility with records written by superseded development schemas is not promised.

## MCP capabilities

Stateless Streamable HTTP exposes `search`, `read`, `change`, `claim`, and `usage`, with concrete generated input/output schemas and role-filtered discovery plus call enforcement. Initialization negotiates 2025-03-26, 2025-06-18 or 2025-11-25. The optional SSE GET returns 405. These protocol versions are separate from the single CQ model version. The independent TypeScript MCP SDK 1.30.1 exercises discovery and calls.

Baboon OpenAPI generation has a reproduced ADT wrapper mismatch. `dev/schema.py` performs a checked correction before packaging the schemas; generated codec examples cover every branch. Schema closure keeps unrelated definitions out of each tool's advertisement. Protocol behavior follows the [transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) and [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) specifications. Harness integration/cancellation evidence remains for M2.

## Verification

`./dev/check contracts` checks deterministic generation, strict TypeScript and Scala compilation, JSON/UEBA round trips, lossless integers and every generated schema branch. `./dev/check postgres` runs shared scenarios and actual HTTP/WS/MCP/CLI clients against an isolated database. See [interface evidence and remaining gates](../validation/m1-interfaces.md).
