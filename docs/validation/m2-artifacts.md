# M2 artifact foundation

This increment implements immutable artifact publication, metadata handles and bounded explicit text reads. It does not establish complete dispatch or token efficiency. [Contract and storage design](../design/artifacts.md).

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Check | Retained evidence | Observed result |
| --- | --- | --- |
| `./dev/check fast` | `20260926T211028-fast` | 37 scenarios pass, including three shared artifact scenarios |
| `./dev/check postgres` | `20260926T211229-postgres` | 30 PostgreSQL scenarios plus HTTP/MCP/WS/CLI and server SIGKILL/restart pass |
| `./dev/check contracts` | `20260926T211438-contracts` | Deterministic single-version generation, Scala/TypeScript codecs and all generated schema branches pass |
| `./dev/check postgres` | `20260926T214817-postgres` | Shared Scala HTTP client: typed grants, usage registration, artifact publication/retry/read and worker publication denial; all 30 PostgreSQL scenarios, transport/CLI deadline/restart checks pass |

Artifact scenarios exercise exact concurrent retry receipts, immutable-content conflicts, same-session collector authorization, forbidden model-role uploads, cross-project reads, missing attempts, 256 KiB parts, control characters, malformed Unicode rejection and pagination across supplementary characters. Actual HTTP uploads include a maximum-size all-NUL body, which expands substantially under JSON escaping, and actual MCP reads remain bounded. Server SIGKILL/restart preserves exact artifact bytes, metadata, retry receipt and the usage observation's evidence handle.

The CLI and future supervisor share `HttpServerApi`, with typed calls, grants, host usage and artifact endpoints. Requests and responses are bounded to 2 MiB and one deadline covers the complete response body. The existing real CLI timeout fixtures cover this shared implementation.

No new browser behavior was added or verified in this increment. Browser and native release checks, real harness output collection, stream manifests, prompt assembly and complete dispatch remain pending. M2 has no milestone approval or human acceptance yet.
