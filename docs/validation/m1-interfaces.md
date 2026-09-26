# M1 authenticated interfaces increment

M1 remains in progress. This increment replaces the temporary probe transport with authenticated ledger, claim and usage operations. It adds a CLI; it does not yet deliver the browser or finish the M1 invariants.

## Observed checks

- `/srv/nvme/tmp/cq4-implementation/20260926T185941-fast`: sixteen shared scenarios pass against manual dummies, including signed authority and application snapshot scenarios.
- `/srv/nvme/tmp/cq4-implementation/20260926T190035-postgres`: sixteen scenarios pass against PostgreSQL 18.6. Real HTTP, WebSocket and MCP SDK clients exercise two projects, independent counters, idempotency, history, revision/fence rejection, role/project denial, browser cookies/origin checks, heartbeat exchange and committed event delivery.
- `/srv/nvme/tmp/cq4-implementation/20260926T190339-postgres`: repeats the real service/client checks and adds real CLI processes for concurrent initialization, shared Git worktree identity, directory moves, explicit UUID reattachment, identity conflict rejection, query and usage/audit reads.
- Schema reproduction: `/srv/nvme/tmp/cq4-implementation/m1-openapi-repro.log`. Baboon 0.0.196 OpenAPI describes ADT branch fields without the wrapper required by its JSON codecs. An actual generated `Content.Task` round trip fails the raw schema for the missing `status` field. `dev/schema.py` corrects only the generated ADT wrapper shape and normalizes OpenAPI integer formats. The corrected fixture passes in `m1-schema-corrected.log`.
- `dev/contracts.mjs` checks generated codec output for all 192 schema definitions (including every ADT branch), plus concrete input/output schemas for five capabilities. Its targeted pass is in `m1-schema-fixtures.log`. The full contract entrypoint passes in `/srv/nvme/tmp/cq4-implementation/20260926T190504-contracts`, including deterministic generation and both language compilers.

Every full check directory includes command/exit records and a source hash manifest. The checks above do not prove whole-server restart recovery or native operation of this increment. Those remain explicit gates.

## Contract and authority

One configured operator secret signs expiring browser sessions and project/actor/role grants. Only operator authority can issue grants or initialize/list projects. A bearer operator request supplies a stable `CQ-Session` UUID; signed grants carry their own session. The server derives provenance from that verified identity. Role tools cannot ingest usage. Worker/explorer/planner/reviewer tool profiles omit mutation and claim capabilities; calling an omitted tool is also rejected. Existing core service permissions provide the second enforcement boundary.

Browser login issues an HttpOnly, SameSite=Strict cookie (Secure on HTTPS). Cookie-authenticated writes and WebSocket handshakes require the configured Origin. Tokens are not placed in URLs. Grant expiry is checked on each operation and by live sessions. Rotating the operator secret invalidates signed sessions/grants; per-grant revocation is not implemented.

HTTP commands, MCP and WebSocket calls invoke `Application`, which uses the existing ledger/usage services. Continuation search requires a matching snapshot cursor or returns `Resync`. The five ordinary capabilities are `search`, `read`, `change`, `claim`, and `usage`. Host ingestion is a separate HTTP endpoint. The 2 MiB inbound HTTP/frame bound is implemented; complete output and aggregate budgets remain open.

WebSocket subscriptions carry a request identity and committed cursor. Subscription setup/replacement and polling serialize per connection. Clients receive correlated replies and committed replay pages, or explicit resynchronization errors. A bounded output queue and nonce-correlated heartbeat provide the server foundation. The minimal browser now has recovery checks; the full lifecycle corpus remains M5 work. See [browser evidence](m1-browser.md).

## CLI identity

`cq init` stores generated `ProjectConfig` in the Git common directory's `cq/project.json`, or `.cq/project.json` for a directory outside Git. Git worktrees share the common-directory configuration. Moving the repository preserves it. Clones do not automatically copy Git-local configuration: explicit `--project-id` or transferring the configuration reattaches to existing server identity. Independently initialized copies receive independent UUIDs. The basename supplies the first display name; reattachment preserves the server name.

A file lock serializes initialization and session creation; write/fsync/atomic rename publishes a complete configuration. A failed network initialization leaves the identity available for retry. A mismatched explicit UUID fails rather than silently replacing an established identity. Endpoint replacement with `init --endpoint` is explicit. `cq query`, `cq status`, `cq status audit`, and `cq web` use this configuration. `cq init --name TEXT` explicitly renames server display metadata using the current project revision. Reattachment without `--name` refreshes the local cache from the server and does not rename it. UUID, created time, item counters and references remain unchanged. Concurrent name edits with stale revisions are rejected. Rename is an operator command, outside ordinary role MCP capabilities.

## Remaining M1 work

Response/aggregate budgets and service-level paging; isolated workspace foundation; independent Astra milestone review. Browser, nested validation, relationship restore and restart evidence are covered by subsequent increments. Harness restriction and collector behavior belong to M2 and later. No M1 completion or human acceptance is claimed.

## Project rename and process restart

The CLI name mismatch was reproduced in `20260926T194155-postgres/jvm-cli.log`: `cq init --name` returned the old server name and revision 1 while changing the local configuration. The correction adds an explicit operator rename command with expected project revision; CLI initialization serializes local changes and refreshes the cached name after the server response.

- `20260926T194347-fast`: all 19 dummy scenarios pass, including rename authority/revision checks and unchanged item counters.
- `20260926T194532-postgres`: all 19 PostgreSQL scenarios and CLI rename checks passed; the new restart fixture then failed schema validation because it used incorrect reference field names. Corrected to the generated contract's `expectedSource`/`expectedTarget` fields.
- `20260926T194635-postgres`: all 19 scenarios, actual transport/CLI rename checks, and whole-process crash/restart verification pass.
- `20260926T194814-contracts`: deterministic generation, Scala/TypeScript compilation, current codecs and all concrete MCP schemas pass.

The restart scenario uses a fresh server process, eight concurrent allocations and repeated delivery of a fixed request; it then records an inverse relationship, claim, renamed project and controlled usage observation. The runner sends SIGKILL to that server process and starts another against the same database and credentials. Verification compares original acknowledgements and claims, checks counter continuation/history/both reference directions, and replays the usage observation without changing totals. This is server-process crash recovery, not a PostgreSQL crash or power-loss test.
