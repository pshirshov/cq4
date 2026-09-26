# Immutable artifact handles

Artifacts are server-owned records outside ledger history and usage counters. A handle is an `ArtifactId` within an authenticated project. Uploads identify an already registered attempt, kind, media type and exact text. Kinds distinguish prompt, input, result, transcript and validation parts; they do not assert semantic correctness of the content.

Only a host collector for the attempt's session, or a human operator, may publish through `POST /api/artifact`. Model roles cannot publish, including through direct HTTP calls. All project roles may read metadata or explicitly request bounded text through the existing `read` capability. Host publication is absent from MCP discovery. Server credentials and host assembly remain separate from narrative content.

Publication is immutable and idempotent by project/handle. An exact replay from the same publisher returns the original receipt time. Changing bytes, attempt, kind, media type or publisher yields a conflict. PostgreSQL insertion and conflict comparison run in one transaction; concurrent equivalent uploads converge. The record retains authenticated actor, receipt time, attempt, SHA-256, UTF-8 byte count and Unicode code-point count.

One part is limited to 256 KiB of valid UTF-8. Bodies are stored as `bytea`, preserving control characters as well as supplementary Unicode characters. Malformed Unicode and oversized parts fail explicitly. Larger transcripts must be divided into referenced parts by the host; this increment does not implement stream assembly or manifests. Allowed media types are `text/plain`, `text/markdown`, `application/json` and `application/x-ndjson`; the label alone does not validate application content.

`ArtifactInfo` returns metadata only. `ArtifactText` requires an explicit code-point offset and a limit of 1–8192. Pages never split a Unicode character and expose the next offset and whether more remains. The per-part storage bound also bounds database transfer and application memory for a read. No automatic artifact deletion or retention expiration is implemented; preservation is indefinite in the current schema. M6 must verify the complete retention policy.

The artifact store supplies a prerequisite for dispatch without narrative forwarding. Host prompt assembly, manifest/result contracts, summary budgets, harness execution and measured parent traffic remain M2 work. Artifact existence alone does not establish successful execution, claim ownership or permission to integrate code.
