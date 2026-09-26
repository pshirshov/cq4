# Implementation status

Goal: complete the first CQ release under the [implementation plan](drafts/20260926-1549-cq-implementation-plan.md). Planning baseline: `7e3076a`.

| Milestone | State | Evidence |
| --- | --- | --- |
| M0 stack and contracts | Complete; Astra approved `1801c2a` | [Four checks and native artifact](validation/m0-stack.md) |
| M1 durable core | Complete; Astra approved `439fc50` | [Milestone review](validation/m1-review.md); [Ledger/claim checks](validation/m1-core.md), [usage accounting checks](validation/m1-usage.md); [authenticated clients and CLI](validation/m1-interfaces.md); [browser foundation](validation/m1-browser.md) passes Chromium checks; remaining invariants follow |
| M2 first usable agent slice | In progress | [Artifacts](validation/m2-artifacts.md), shared typed host HTTP client, [guardian/driver](validation/m2-process.md), [durable local jobs](validation/m2-jobs.md), [unified server/client roles](validation/m2-roles.md) and [native usage collection](validation/m2-collectors.md) and [three harness adapters](validation/m2-adapters.md) implemented; supervisor role, handle dispatch, durable collector attachment and real evaluations next |
| M3 graph and concurrency | Not started | — |
| M4 process and cohorts | Not started | — |
| M5 complete UI | Not started | — |
| M6 native release candidate | Not started | — |

## Current increment

User correction applied and verified at `fbb7918`: maintain one mutable `cq.api` 0.1.0 model; permit breaking changes and bump only on explicit instruction. Historical model copies and evolution fixtures are removed. Generation refreshes the current signature and rejects unrequested extra versions.

M1 now includes:

- Fourteen typed ledgers, nested/provenance validation, canonical references, complete history/restore, project identity/rename, atomic IDs, idempotency and explicit-set claims.
- A separate operational usage audit with immutable attribution, correction history, attempt coverage, exact monetary arithmetic and bounded discovery/audit/cost pages.
- Authenticated HTTP/MCP/WebSocket/CLI clients and a minimal browser with live updates, durable drafts/retries, accounting drill-down and connection recovery.
- A local isolated-workspace foundation with frozen ownership/base, detached worktrees, separate indexes and durable quarantine after uncertain creation or changed identity.

M1 is complete at `439fc50`, with independent Astra approval after correction loops. [Final evidence and scope](validation/m1-review.md): 34 fast scenarios, 27 PostgreSQL scenarios plus actual clients/deadline/restart checks, contracts, and the unchanged browser corpus all pass.

M2 has an implemented [immutable artifact boundary](design/artifacts.md): host publication, compact metadata, bounded explicit text reads and persistence across server SIGKILL. [Artifact/HTTP evidence](validation/m2-artifacts.md) includes 30 PostgreSQL scenarios, actual clients/restart and generated contracts. The [Linux guardian and Scala driver](design/process-guardian.md) have Astra approval. The [durable local job service](design/local-jobs.md) now binds them to immutable journal records, cancellation and workspace quarantine: [42 fast scenarios and 16 helper plus 19 Scala process scenarios pass](validation/m2-jobs.md), including actual supervisor SIGKILL/recovery and stalled journal writes. Generated-contract verification passes. Astra approved this foundation at `cd0c6e5` after the reproduced storage/cancellation corrections. The [unified server/client role entrypoint](validation/m2-roles.md) passes real client, configuration-isolation, deadline and restart checks. Supervisor role wiring and local control transport are next. The [harness adapter design](design/harness-adapters.md) records observed CLI controls and live capability evidence; no CQ consumer evaluation has run yet. No user decision is currently required.

Finalized-output collectors now preserve Claude/Codex cumulative scopes and Pi response identities, explicit unknown counters/costs and bounded evidence. [Collector verification](validation/m2-collectors.md) passes 55 fast scenarios and retained-probe HTTP/PostgreSQL replay without duplicate accounting. Astra approved this bounded foundation at `7491114`, with no blocking or major findings. Live job attachment, durable upload recovery, interactive collection and actual consumer evaluation are still pending. No efficiency improvement is claimed from those probes.

The three harness adapters now pass six actual Worker/Reviewer capability probes through isolated workspaces and scoped CQ MCP. Workers write the exact retrieved artifact; reviewers leave the forbidden file absent, including an observed Codex patch rejection. All probes publish through the shared operational usage audit. [Evidence and limits](validation/m2-adapters.md) distinguish this from consumer evaluations and record the reproduced Codex runtime, Pi handshake and Claude provider-route corrections. The full fast check passes 58 Scala scenarios plus the Node bridge fixture. Astra independently approved this adapter increment at `7e1f87d`, with no blocking or major findings.

See [dependency evidence and compatibility patches](design/dependencies.md), [architecture](design/architecture.md), and [contracts](design/contracts.md). Planning baseline: `7e3076a`; verified M0 implementation: `1801c2a`.

Current evidence: [M0 manifest](validation/m0-stack.md), [M1 core increment](validation/m1-core.md), and [M1 usage increment](validation/m1-usage.md). Native evidence currently applies to M0 only. M1 has milestone approval; its isolated workspace foundation is available for M2. Query-plan/scale verification remains M3 work.

## Acceptance

Queued for M6 at the user's request: replace manual Baboon compiler downloading with a pinned upstream flake input, retaining deterministic generation and contract verification. Current implementation work continues first.

M0 and M1 have independent Astra approval. M2 and M6 human acceptance are pending; neither evidence package exists. No later milestone is complete.
