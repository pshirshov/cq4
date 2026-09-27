# M3 query parser foundation

The [shared query language](../design/query-language.md) now has generated recursive AST/diagnostic types, a bounded parser and shared Unicode text normalization. It remains in `cq.api` 0.1.0. Repository compilation, client wiring, completion and measured query plans are still open; R15 and M3 are incomplete.

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Check | Evidence directory | Result |
| --- | --- | --- |
| Initial parser corpus | `20260927T033423-fast` | Expected failure: 77/78 scenarios passed; `version2` incorrectly failed with `Unknown item prefix` |
| Corrected parser and existing regressions | `20260927T034013-fast` | 78/78 Scala scenarios, Node bridge, consumer evidence and evaluation-suite fixtures passed |
| Generated contracts | `20260927T034053-contracts` | Deterministic generation, TypeScript compilation, 298 schema definitions and five MCP capabilities passed; nested query JSON traveled Scala → TypeScript → Scala, and Scala UEBA round-tripped |

Bare-ID recognition now requires a fixed ledger prefix. Explicit `id:` values still require canonical positive 64-bit IDs, and recognized malformed IDs still fail. The parser corpus also checks Boolean precedence, implicit conjunction, archive defaults, every ledger/relation, quoted escaping, UTF-16 diagnostic spans, input/AST/depth limits and Unicode normalization. Text checks cover phrases after many repeated words and barriers at oversized document words.

Astra approved the bounded parser increment after confirming both gates and matching the reviewed implementation/test files to their source manifests, with no outstanding conditions. This review does not cover the pending repository/client integration or query performance.

Reproduce from the repository root:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
```
