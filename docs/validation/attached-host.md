# Harness-owned interactive CQ host

Implementation in progress, 2026-09-28. Implements accepted I1/K1. The installed operator package has **not** yet been replaced. Native consumer routes pass; interactive failure checks, native packaging and the user's yolo trial remain in progress.

## Implemented development interface

- `cq host HARNESS [--settings FILE]` is a stdio MCP distage role started by the harness. `CQ_SETTINGS` can supply the settings path. The existing interactive assistant is the Governor; no second Governor process is launched.
- `cq configure HARNESS --settings FILE [--executable FILE] [--directory DIR] [--replace]` installs the integration and four workflow commands. An installed native binary discovers its own executable; a development JVM invocation requires an executable wrapper explicitly.
- Claude: project `.mcp.json`, preserving unrelated entries. Codex: project `.codex/config.toml`; a user-owned existing file is refused, even with `--replace`. Export into an empty directory and merge its `mcp_servers.cq` table manually in that case. Pi: project `.pi/extensions/cq-host.js` plus generated tool configuration.
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

Native project configuration discovery and cross-harness dispatch pass in the evaluations below. Interactive TUI parent chains are being measured separately.

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

## Real native consumers

All runs use the configured, authenticated harness executables inside the existing yolo sandbox. Each creates one task through the installed tools and dispatches one Explorer child by retained choice and claim. No extra Governor is started.

| Evidence directory | Actual route | Result |
| --- | --- | --- |
| `native-claude-2` | Claude → Codex | Completed child, durable result marker, no surviving CQ descendants; explicit final publication recovery acknowledges one batch, repeat zero. |
| `native-codex-3` | Codex → Pi | Same checks pass; explicit interrupted-publication recovery, repeat zero. |
| `native-pi-3` | Pi → Claude | Same checks pass; normal exit publishes final outcome; native finalized-assistant usage samples recorded. |

Claude/Codex native teardown can terminate their stdio host before its final audit publication. This is an interrupted outer outcome, not an observed model completion. Once the owner has stopped, `cq job upload --session DIR` replays retained records without adopting or launching processes. `session Context` supplies DIR; it is also the session UUID directory beneath the configured stateRoot. Partial/incomplete records produce an explicit failure after valid publications are replayed.

Failed runs are retained: `native-codex-1` exceeded the claim limit; the tool description now publishes the existing 300,000 ms bound. `native-claude-1` completed its child but failed an evaluator assertion that incorrectly required native exit to publish synchronously. `native-pi-1/2` did not load the extension: installed Pi discovers `.js`/`.ts`, and its `--no-approve` flag ignores project files. The generated filename is corrected and the evaluator uses `--approve`. `native-codex-2` correctly rejected a Pi report claiming HostObserved provenance. Its role-specific output schema now permits only ModelDeclared; `provenance-schema-before.log` reproduces the mismatch and `provenance-schema-after.log` passes eight adapter checks. No failed spending is represented as zero; failed native transcripts are retained, including runs that never attached to CQ.

Pi restart checks reproduced sequence reuse across new hosts (`pi-restart-before.log`) and now pass (`pi-restart-after.log`). Astra also identified an incomplete final Pi sample preventing recovery of earlier committed samples. `pi-tail-before.log` reproduces it; `pi-tail-after.log` passes 21 focused tests. Recovery now replays earlier samples, preserves the bounded incomplete tail, reports its path and does not invent counters. The PostgreSQL variant passes in `core-7/usage-postgres.log`; Astra approved this correction.

## Interactive TUI ownership

Actual TUI runs (no model requests) initialized MCP, stayed connected beyond the startup/heartbeat deadlines, and then froze or killed the native owner. Each host's immediate parent was the actual interactive harness process. Recovery was idempotent; a restart created a distinct session.

| Evidence | Frozen owner: host exit | Killed owner: host exit |
| --- | --- | --- |
| `tui-claude-5` | 39.717 s | 0.354 s |
| `tui-codex-4` | 40.598 s | 0.836 s |
| `tui-pi-1` | 40.409 s | 0.579 s |

These checks used the JVM development executable. Earlier Claude/Codex TUI attempts stopped at native trust/permission dialogs or terminal capability queries; their transcripts are retained. The driver now implements those terminal responses and explicitly trusts only its own generated test checkout. Packaged native checks remain below.

## Remaining verification

- A release artifact including the added role/resources; installed setup and affected batch/workflow/integration checks.
- Concrete yolo commands with non-secret environment forwarding and required binds, followed by the user's trial.

The installed yolo script uses `--clearenv`: exporting CQ variables in the host shell is insufficient. Its `--env KEY=VALUE`, `--ro PATH` and `--rw PATH` options must precede `codex`, `claude` or `pi`. The intended credential forwarding is `--env CQ_TOKEN_FILE=/absolute/token`, with that file readable inside the sandbox. Exact verified setup/run commands will be supplied with the package.

Configuration references inspected: [Codex stdio MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [Claude project MCP](https://code.claude.com/docs/en/mcp), and the installed Pi 0.87.1 extension declarations/docs. These establish configuration/hook APIs; they do not substitute for native lifecycle measurements.

### Yolo invocation boundary

The native consumers and TUI checks above ran inside the current yolo sandbox. A nested invocation of the actual `yolo --env CQ_TOKEN_FILE=… cmd …` script was attempted (`yolo-visibility.log`); it failed before executing CQ because the outer sandbox lacks `/run/nscd`, which the host-level yolo script binds. This does not establish a failure of the host invocation. Host-level yolo startup remains the user's trial; the installed script was inspected for explicit environment forwarding, project/state binds and native launch flags. No host processes or configuration were modified to bypass this boundary.

## Reproduction commands

From the CQ checkout:

```sh
node dev/pi-attached-check.mjs
nix develop -c sbt --batch --no-colors ';server/testOnly *StdioPeerLocal *AttachedAssetsLocal *HarnessAdapterLocal *WorkflowLocal *SessionDeliveryDummy;exit'
nix develop -c env CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq-attached-verification ./dev/check native
```

The native gate now includes `dev/attached-check.py` against both the tracing JVM and produced executable: all integration exports, Pi metadata collection/replay, running-child disconnect, activation fences and blocked-I/O deadlines. The existing batch/workflow/dispatch/integration checks run in the same gate. Source-isolated installed verification uses `nix develop -c ./dev/package-check --release /absolute/package --evidence-root /srv/nvme/tmp/cq-installed-verification`.

For real model evaluations, `dev/attached-native-eval HARNESS --cq /absolute/package/bin/cq --guardian /absolute/package/bin/cq-guardian --evidence /absolute/new-evidence-directory` requires `CQ_CLAUDE_EXECUTABLE`, `CQ_CODEX_EXECUTABLE`, `CQ_PI_EXECUTABLE` to name the verified route binaries. Run under `nix develop -c env ...` with normal authenticated harness homes available. Add `--lifecycle` for interactive TUI ownership checks without model requests. Evidence directories must be fresh. The evaluator records executable hashes and runtime source hashes; these evaluations spend model tokens unless `--lifecycle` is selected.

### Prepared validation policy

The user selected reviewed integration into `refs/heads/main` with the scoped UI check. `.local/interactive/settings.json` declares `cq-ui` as `nix develop -c ./dev/check ui`, with a ten-minute deadline. That exact command passed from a fresh detached checkout at `3318a1b`; immutable copied evidence is `prepared-ui-evidence/`. The temporary checkout was removed after byte-for-byte evidence verification. No model matrix runs as part of this check. Backend/CLI work requires additional relevant validation declarations.
