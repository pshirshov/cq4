# Harness-owned interactive CQ host

Implementation in progress, 2026-09-28. Implements accepted I1/K1. The installed operator package has **not** yet been replaced. Native harness evaluations, native packaging and the user's yolo trial are pending.

## Implemented development interface

- `cq host HARNESS [--settings FILE]` is a stdio MCP distage role started by the harness. `CQ_SETTINGS` can supply the settings path. The existing interactive assistant is the Governor; no second Governor process is launched.
- `cq configure HARNESS --settings FILE [--executable FILE] [--directory DIR] [--replace]` installs the integration and four workflow commands. An installed native binary discovers its own executable; a development JVM invocation requires an executable wrapper explicitly.
- Claude: project `.mcp.json`, preserving unrelated entries. Codex: project `.codex/config.toml`; a user-owned existing file is refused, even with `--replace`. Export into an empty directory and merge its `mcp_servers.cq` table manually in that case. Pi: project `.pi/extensions/cq-host.mjs` plus generated tool configuration.
- `CQ_TOKEN_FILE` accepts an absolute credential-file path. `CQ_TOKEN` remains supported and takes precedence. Generated configurations contain environment **names**, not credential values. Managed children retain their existing restricted environment and scoped tools.
- The outer connection exposes the seven Governor domain tools, `session` and `dispatch`. `session Context` supplies project/routes/checks/limits and instructions, including Codex's complete argument guide. `session Workflow` activates typed scope. The command exports use these tools directly. `cq run` remains the batch path.
- Workflow receipts are idempotent. A new activation requires quiescent child/check/integration/combination work. Old cohort selections and integration/combination identities cannot cross activation generations. Retrying an older activation returns its receipt without reactivating it. Context reports the active workflow. Status/cancel remain available.
- EOF, owner death, missed heartbeat, operation deadline and absolute eight-hour session lifetime close the attached session. Closing starts hierarchy cancellation; existing guardians bound child termination. The independent watchdog forces unresolved exit if I/O prevents orderly draining. Reconnection creates a new session; it never adopts an uncertain process.
- The attached outer attempt has no fabricated native Governor output or completion. Claude/Codex interactive usage is unavailable. Pi's extension records finalized assistant usage metadata, including observed model/provider and available counters/cost, without assistant content. Auxiliary/compaction/unfinished responses remain gaps. All child accounting is unchanged. `cq job upload` replays retained observations and marks interrupted outer outcomes Unknown.

The process graph is:

```text
yolo → interactive Claude/Codex/Pi → cq host → guardian → child harness
                                            ↘ durable CQ API (separate server)
```

The actual native parent chains and native configuration discovery still require the checks below before this is a verified product claim.

## Verified development checks

Evidence root: `/srv/nvme/tmp/cq4-attached-host-20260928`.

| Evidence | Observed result |
| --- | --- |
| `local-3.log` | 16 tests pass: transport framing/owner/heartbeat/operation deadline; generated integration files; workflow commands; dummy-backed recovery including Pi usage deduplication. |
| `pi-extension.log` | Actual Node child/stdio fixture passes initialization, tool calls, server ping, metadata-only Pi usage, idempotent shutdown and abort. |
| `core-6/usage-postgres.log` | Four shared recovery tests pass against PostgreSQL, including attached Pi samples, duplicate native response suppression and lost-ack replay. |
| `core-6/attached.log` | Actual JVM role + CQ HTTP server + PostgreSQL + Git + guardian/fixture child: activation retries, scope fencing, retained-choice dispatch, child permission checks, compact results, no second Governor, unknown outer coverage, token-file credentials, replay, stalled startup and stalled operation deadlines pass. |
| `core-6/batch-regression.log` | Existing batch role, workflow input, usage replay and interrupted native recovery pass. |

These are deterministic process/service checks, not real model evaluations. The child in `attached-check.py` is an explicit fixture.

### Reproductions and corrections

1. `core-2/attached.log`: first child failed with “Local control service has not started.” The new role had not made the local HTTP service a reachable lifecycle dependency. `AttachedProgram` now explicitly acquires it.
2. `core-4/attached.log`: held initial fsync survived EOF beyond the drain deadline. Stdio/owner monitoring now starts before initial durable publication.
3. `core-5/attached.log`: a held cohort-selection fsync survived the request deadline while native heartbeat remained responsive. `StdioPeer` now monitors operation deadlines independently. Closure immediately arms the watchdog, starts asynchronous hierarchy shutdown and closes subsequent driver admission.

All three pass in `core-6`. Astra approved these scoped lifecycle corrections; overall integration approval is still pending.

## Remaining verification

- Actual Claude/Codex/Pi project integration, Context consumption, cross-harness child dispatch and Pi native usage observations.
- Interactive process ownership, frozen/dead owners, disconnect/restart and descendant cleanup for the actual three integrations.
- A release artifact including the added role/resources; installed setup and affected batch/workflow/integration checks.
- Concrete yolo commands with non-secret environment forwarding and required binds, followed by the user's trial.

The installed yolo script uses `--clearenv`: exporting CQ variables in the host shell is insufficient. Its `--env KEY=VALUE`, `--ro PATH` and `--rw PATH` options must precede `codex`, `claude` or `pi`. The intended credential forwarding is `--env CQ_TOKEN_FILE=/absolute/token`, with that file readable inside the sandbox. Exact verified setup/run commands will be supplied with the package.

Configuration references inspected: [Codex stdio MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [Claude project MCP](https://code.claude.com/docs/en/mcp), and the installed Pi 0.87.1 extension declarations/docs. These establish configuration/hook APIs; they do not substitute for native lifecycle measurements.
