# Harness-owned interactive CQ host

Implemented and installed, 2026-09-28, for accepted I1/K1. Native and relocated package checks, three packaged consumer routes and actual interactive ownership/failure checks pass. The user's host-level yolo trial and overall release acceptance remain pending.

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

Native project configuration discovery and cross-harness dispatch pass in the evaluations below. Interactive TUI parent chains were measured separately below.

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

All three pass in `core-6`. Astra approved these scoped lifecycle corrections and subsequently the source/native-harness scope. Final delivery review is recorded below.

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

## Packaged artifact and delivery

- `native-build-2/20260928T181002-native`: passed tracing JVM, GraalVM compilation, produced native executable and browser/HTTP checks. Attached fixtures include running-child disconnect and blocked-I/O deadlines; existing batch/workflow/cohort/dispatch/combination/integration/admission/shutdown checks also pass. The first native attempt is retained: the fixture supplied a Java executable instead of a CQ wrapper; that test setup was corrected before the passing rerun.
- `release-attached`: built only after the native gate passed and runtime sources matched. Native SHA-256: `d1f6c38a4abf57dcd20adafa75a78056592def4ea313d0327261af618e734779`; guardian: `f56efb4626cb8e0fab0cc4b27b1460e75cea275890b5209df0a7f312ddf029bc`; manifest: `eb3877ba96212e11a614605d3fd92ecd72fcf08ba1815b5673755079b2c26ece`.
- `installed-attached/20260928T183858-installed`: passed relocated native corpus with the repository and Coursier hidden and the runtime closure imported into a fresh private Nix store. Settled database backup/restore compares 25 tables, API output, counters and idempotency. Its verifier sources remained unchanged.
- `native-self-configuration`: installed executable discovery passes for all three integrations without `--executable`.

All three packaged consumers use the exact native and guardian hashes above. Each dispatches one completed Explorer child through the generated native integration, with no second Governor or surviving owned processes. These are three cyclic routes, not a new nine-route matrix or full worked-project evaluation.

| Evidence | Route | Retained delivery |
| --- | --- | --- |
| `packaged-claude-consumer` | Claude → Codex | Final delivery recovered explicitly; one pending batch acknowledged, repeat zero. |
| `packaged-codex-consumer` | Codex → Pi | Final batch sealed locally; one pending acknowledgement recovered, repeat zero. |
| `packaged-pi-consumer` | Pi → Claude | Final batch acknowledged during shutdown; recovery zero; finalized-assistant usage recorded. |

`normalExitPublished` in evaluator JSON indicates a locally sealed final delivery, not acknowledgement of every audit batch. The recovery result establishes whether delivery remained pending.

| Packaged TUI evidence | Frozen owner: host exit | Killed owner: host exit |
| --- | --- | --- |
| `packaged-claude-tui` | 38.302 s | 0.139 s |
| `packaged-codex-tui` | 38.053 s | 0.596 s |
| `packaged-pi-tui` | 38.560 s | 0.152 s |

Each TUI was responsive for 42 seconds before interruption; its native process was the CQ host's immediate parent. These idle-owner checks are separate from the running-child disconnect fixture. They make no new model requests.

The verified package is installed at `.local/release`, with the previous package preserved at `.local/release-before-attached-host`. `operator-installation.json` records matching hashes and runtime sources. `operator-configuration/` records native setup for all three harnesses and generated file hashes. Machine-specific configuration is excluded locally via `.git/info/exclude`; it contains no token values. Existing host server/PostgreSQL processes were not signaled from this sandbox; restarting `./run-local.sh` uses the new package and existing state.

### Remaining human verification

The exact [direct harness/yolo instructions](../interactive.md) are ready for the user's host-level trial. Overall release acceptance remains pending, and D26–D40/I2 remain queued for their separate CQ session.

The installed yolo script uses `--clearenv`: exporting CQ variables in the host shell is insufficient. Its `--env KEY=VALUE`, `--ro PATH` and `--rw PATH` options must precede `codex`, `claude` or `pi`. The intended credential forwarding is `--env CQ_TOKEN_FILE=/absolute/token`, with that file readable inside the sandbox. The guide distinguishes verified native setup from the pending host-level invocation.

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

### Integration-target review correction

Astra found that `refs/heads/main` was still checked out in the governing worktree. `operator-target-before.json` captures the failed precondition; `IntegrationCoordinator` correctly refuses such an update. Delivery keeps the chosen target and safety check: finish source commits on `main`, detach the governing checkout at that tip, and verify no worktree has `main` checked out. The guide documents the unchanged governing files after integration, refresh between sessions and return to manual development. This is a setup correction, not a change to Git integration behavior.

## Independent delivery review and operator check

Astra approved the scoped delivery at `11f3829` on 2026-09-28 with no remaining blocking or major findings. The reviewer independently confirmed package hashes, the installed gate, clean detached HEAD and no checked-out `main`, and accurate startup/recovery/usage instructions. `operator-target-after.json` records the corrected target precondition; final documentation bookkeeping is followed by the same check in `operator-target-final.json`.

`operator-smoke/result.json` uses the installed generated Codex command/settings against the actual `cq4` server: nine expected tools, Context with `refs/heads/main` and `cq-ui`, scoped I1 read, EOF cleanup and two uploads acknowledging zero pending batches. It creates an operational audit attempt but launches no model or managed child and edits no ledger item. `operator-ledger/` separately retains the expected-revision update and readback of I1 revision 3 and K1 revision 2, recording delivery while preserving Accepted/Adopted status and the pending human trial.

The reviewer’s approval covers this attached-host delivery, not the queued redesign or human release acceptance. The final bookkeeping commit changes documentation only; installed runtime source hashes remain unchanged.

## Superseding operator decision: keep main checked out

After delivery, the user rejected detached HEAD and requested reattachment, integration and a defect record. `HEAD` and `refs/heads/main` both already pointed at `1083344`; `git switch main` reattached the checkout and the ancestry check confirmed all implementation commits were included. No merge, reset or history rewrite was needed. This supersedes the earlier detached-checkout operator setup, while preserving its historical verification evidence.

**D41 Open / High** records safe reviewed integration into the normal checked-out target branch. The current runtime guard remains intact and will refuse that automatic integration until the defect is corrected. `operator-main-defect/` contains checkout observations, the creation receipt and readback, plus corrected I1 revision 4 / K1 revision 3. The direct harness startup remains available; the product integration limitation remains unresolved.
