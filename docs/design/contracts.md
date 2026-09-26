# Contract boundary

Authoritative production schema: `models/cq-api.baboon`, currently `cq.api` 0.1.0. `dev/generate` pins Baboon 0.0.196 by SHA-256, enforces the schema lockfile, emits Scala/TypeScript codecs and applies the documented Scala runtime compatibility patch. Generated files are ignored and regenerated; no handwritten conversion belongs in generated output.

## Wire representation

The generated codecs encode `i64` as decimal JSON strings. JavaScript consumers use `bigint` internally. Always call generated codecs; generated record `toJSON()` is not the wire encoder. `ProjectId` wraps a UUID. `Revision` wraps a signed 64-bit integer. The proof includes 9,007,199,254,740,993 and the signed 64-bit maximum, Unicode text, canonical identifier parsing and a typed conflict error.

HTTP requests to `/api/probe` require `CQ-Protocol-Version: 0.1.0`; unsupported or missing versions receive a typed `UnsupportedVersion` error. `/api/hello` advertises supported CQ schema versions. An authenticated caller cannot exchange a probe for another project. All current application routes require a bearer credential, and any supplied Origin must match configuration.

The separate test schema `cq.fixture` evolves from 0.1.0 to 0.2.0 by adding an optional annotation. Generated conversions preserve the old revision/text and initialize the new field to absent. This fixture is compiled in the Scala test scope and exercises TypeScript conversion; it is not a production ledger model.

## MCP proof

M0 exposes one temporary `probe` tool with a concrete input schema and generated-codec argument decoding. It uses stateless Streamable HTTP JSON responses, initialization, tool discovery/calls and a 405 response for the optional SSE GET. Supported protocol versions are 2025-03-26, 2025-06-18 and 2025-11-25. These are explicit compatibility versions, not a claim of supporting the newest MCP protocol. The independent TypeScript SDK 1.30.1 is the test client.

The [versioned transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) defines the Origin check, POST/GET behavior and protocol header. The [lifecycle specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) defines initialization negotiation. Full capability schemas, role permissions and installed harness compatibility remain required before consumer evaluations.

## Verification

`./dev/check contracts` regenerates with locked signatures, compiles Scala and strict TypeScript, exchanges fixture files in both directions, and checks binary encoding and schema evolution. `./dev/check postgres` runs the shared repository behavior and real HTTP/WS/MCP client path against an isolated database. These checks do not establish the future ledger, synchronization or permission contracts.
