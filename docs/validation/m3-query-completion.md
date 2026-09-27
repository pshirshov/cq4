# M3 query completion metadata

The [shared query service](../design/query-language.md) now returns cursor-aware completion through HTTP, the existing MCP read capability and the CLI. This increment supplies metadata for the later M5 editor; it does not complete M3.

## Implementation

- Generated suggestion kinds, replacement spans, diagnostics and truncation metadata remain in `cq.api` 0.1.0.
- Local grammar context selects fixed vocabulary, labels and archived/reference IDs under the authenticated project. Full-query diagnostics remain independent of completion.
- Item-ID and label prefix queries use indexed, C-collated scalar ranges with a limit-plus-one bound. Label membership counts update transactionally with changed items; no item catalog reload is introduced.
- All draft text is validated as scalar Unicode without NUL before persistence. Completion insertion text preserves label quoting/escaping and UTF-16 cursor boundaries.

## Evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Check | Directory | Result |
| --- | --- | --- |
| Initial fast corpus | `20260927T041857-fast` | Passed; 86 Scala scenarios plus bridge/evaluator fixtures |
| Generated contracts | `20260927T042105-contracts` | Passed; deterministic generation, TypeScript compilation, Scala/TypeScript round trips, 303 definitions and five MCP capabilities |
| Initial PostgreSQL/client gate | `20260927T042130-postgres` | Passed; 40 service scenarios, actual HTTP/MCP/CLI completion, permissions, process/role/recovery and server SIGKILL/restart |
| Invalid draft text reproduction | `20260927T042655-fast` | Expected failure; 86/87 scenarios passed, invalid draft strings were not consistently rejected |
| PostgreSQL reproduction | `20260927T042747-postgres` | Expected failure; 40/41 scenarios passed, NUL produced database exceptions and unpaired surrogates were accepted |
| Corrected fast corpus | `20260927T042905-fast` | Passed; 87 Scala scenarios plus bridge/evaluator fixtures |
| Corrected PostgreSQL/client gate | `20260927T042930-postgres` | Passed; 41 service scenarios and the complete actual client/process/recovery/restart corpus |

The shared completion corpus covers cursor replacement within tokens, delimiters, incomplete quotes, UTF-16 boundaries, fields/operators/statuses, archived references, project isolation, literal wildcard characters, Unicode range endpoints, deterministic pagination, label edits/last-member removal/restoration and transaction rollback. Actual clients compare HTTP and MCP responses and reject cross-project access; CLI checks cover completion output, invalid cursors and incompatible continuation flags.

Astra identified the representability mismatch in draft validation. The regression sends NUL, lone high surrogates and lone low surrogates in labels, title, body and nested task acceptance. The correction validates every string in the generated draft JSON before repository writes; it preserves the same encoded value for the existing byte-size bound. The regression additionally requires no allocated items/catalog rows and the next valid item to receive T1. Both final correction gates pass; runtime and test files match their source manifests. Astra independently approved the complete increment after inspecting both final gates and their source manifests, with no remaining blocking or major findings.

Measured access plans and contention remain separate M3 work. Existing index definitions are not evidence of planner choices or latency bounds. The browser popup remains M5 work.

Reproduce sequentially from the repository root:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check postgres
```
