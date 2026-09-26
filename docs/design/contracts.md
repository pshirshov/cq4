# Contract boundary

Authoritative schema: `models/cq-api.baboon`, the single `cq.api` version `0.1.0`. By explicit user instruction on 2026-09-26, edit this model in place and permit breaking changes; bump the version only when the user explicitly requests it. Historical schema copies, conversions and backward compatibility are outside the current development scope.

`dev/generate` pins Baboon 0.0.196 by SHA-256, replaces generated output, refreshes the current model signature, emits Scala/TypeScript codecs and applies the documented Scala runtime compiler fixes. The signature records the current shape without freezing it. Generated files are ignored and regenerated. The [ledger contracts](ledgers.md) describe M1's typed content and transaction services.

## Wire representation

The generated codecs encode `i64` as decimal JSON strings. JavaScript consumers use `bigint` internally. Always call generated codecs; generated record `toJSON()` is not the wire encoder. `ProjectId` wraps a UUID. `Revision` wraps a signed 64-bit integer. The proof includes 9,007,199,254,740,993 and the signed 64-bit maximum, Unicode text, canonical identifier parsing and a typed conflict error.

HTTP requests to `/api/probe` require `CQ-Protocol-Version: 0.1.0`; unsupported or missing versions receive a typed `UnsupportedVersion` error. `/api/hello` advertises that single CQ schema version. During development, clients and server must be built from the same schema; the version label does not promise compatibility across development commits. An authenticated caller cannot exchange a probe for another project. All current application routes require a bearer credential, and any supplied Origin must match configuration.

Earlier evolution fixtures were removed under the single-version policy. Their prior results remain historical evidence in the M0/M1 validation records. Current-schema history and backup/restore remain required; compatibility with records written by superseded development schemas is not promised.

## MCP proof

M0 exposes one temporary `probe` tool with a concrete input schema and generated-codec argument decoding. It uses stateless Streamable HTTP JSON responses, initialization, tool discovery/calls and a 405 response for the optional SSE GET. Supported protocol versions are 2025-03-26, 2025-06-18 and 2025-11-25. These are explicit compatibility versions, not a claim of supporting the newest MCP protocol. The independent TypeScript SDK 1.30.1 is the test client.

The [versioned transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) defines the Origin check, POST/GET behavior and protocol header. The [lifecycle specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) defines initialization negotiation. Full capability schemas, role permissions and installed harness compatibility remain required before consumer evaluations.

## Verification

`./dev/check contracts` verifies deterministic generation, compiles Scala and strict TypeScript, exchanges fixture files in both directions, and checks binary encoding and typed errors. `./dev/check postgres` runs shared repository/service behavior and the real HTTP/WS/MCP probe client against an isolated database. Ledger client, synchronization and authenticated role coverage remain open.
