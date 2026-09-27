# M3 shared query search

The [query language](../design/query-language.md) now reaches both repositories through the shared service. `SearchInput.query` replaces `ItemFilter` in the existing 0.1.0 contract. HTTP, MCP, CLI and browser submit the same text; authorization is applied separately from the query. Completion metadata and measured query plans remain open, so this increment does not complete R15 or M3.

## Implementation

- `LedgerService` uses the injected `QueryParser`; syntax failures return a typed diagnostic with UTF-16 spans.
- PostgreSQL binds every value, evaluates Boolean predicates and exact attributes, and probes canonical edges in both directions. A project clause cannot widen the transaction's scope.
- Each changed item gets its normalized search stream. PostgreSQL derives its word array and maintains GIN indexes for words and labels; phrase matching rechecks the stream. The in-memory repository evaluates the same semantics.
- Existing stable ordering, page byte/count bounds and snapshot invalidation remain in effect. The CLI uses `--query`; the browser preserves invalid text and displays the diagnostic. MCP describes the common grammar.

## Evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Check | Directory | Result |
| --- | --- | --- |
| Initial integration fast corpus | `20260927T035025-fast` | 82/82 Scala scenarios plus bridge/evaluator fixtures passed |
| Browser diagnostic reproduction | `20260927T035121-browser` | HTTP/MCP, CLI and process fixtures passed; Chromium failed because periodic events replaced the invalid-query state |
| Corrected fast corpus | `20260927T035606-fast` | Passed; 82 Scala scenarios plus bridge/evaluator fixtures |
| Generated contracts | `20260927T035627-contracts` | Passed; deterministic generation, TypeScript compilation, 298 definitions, five MCP capabilities and recursive query round trips |
| PostgreSQL gate | `20260927T035649-postgres` | Passed; 36/36 service scenarios, actual HTTP/MCP/CLI/role checks, supervisor/dispatch/shutdown/recovery and server SIGKILL/restart |
| Corrected browser gate | `20260927T040113-browser` | Passed; actual query/diagnostic UI, existing draft/usage/connection corpus and client/process fixtures |

The reproduced browser failure was `Periodic updates must preserve query error state`, expected one invalid-query indicator, observed zero. Query submission now detaches the previous local subscription identity; only a successfully loaded query establishes a new subscription. This prevents old events from declaring the rejected query current. The same Chromium regression now passes. `query-diagnostic.png` was visually inspected; it shows preserved input, the source span, and invalid-query state. The run retains its screenshots and Playwright traces.

The shared repository corpus covers Boolean/attribute combinations, escaped SQL-shaped tags, project intersections, exact IDs, archive defaults, normalized words/phrases after 18,000 repeated words, oversized-word phrase barriers, every relation/inverse pair, archived reference targets, symmetric references, edits/restores and pagination. Actual client checks compare HTTP and MCP responses and syntax spans, exercise CLI queries and nonzero syntax-error exit, and submit valid/invalid queries in Chromium.

Astra approved the query integration increment after checking all four gates, the reproduced browser correction and the source manifests, with no remaining substantive findings. After the four gates, the browser's unchanged 4,096-character limit was extracted to a named constant; `npm run check` and `npm run build` passed again (`query-ui-named-limit/check.log`). Completion metadata, measured plans and full R15/M3 approval remain open.

Reproduce from the repository root, sequentially:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check browser
```
