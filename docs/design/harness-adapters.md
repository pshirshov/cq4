# Harness adapter inventory

M2 adapter implementation, 2026-09-26. `HarnessAdapter` builds explicit launch arguments, an isolated environment and private configuration assets. All three adapters have [live worker/reviewer capability evidence](../validation/m2-adapters.md), with their output collected through the shared operational usage audit. The [batch supervisor role](supervisor-role.md) composes them through distage; [local dispatch](local-dispatch.md) and the [first real consumer candidate](../validation/m2-consumer-evaluations.md) now execute through it. Installed CLI help is retained under `/srv/nvme/tmp/cq4-implementation/m2-capabilities-20260926/`.

| Installed harness | Observed batch/output controls | Observed capability controls |
| --- | --- | --- |
| Claude Code 2.1.280, 2.1.285 | `--print`, `--output-format stream-json`, explicit session ID; optional structured final response | Built-in tool allowlist, permission allow/deny lists, strict MCP configuration and settings-source controls. `--bare` explicitly changes authentication behavior, so it cannot be assumed compatible with the configured account. |
| Codex 0.156.1, 0.159.2, 0.160.0 | `exec --json`, stdin prompt, final-message file, optional output schema; `--ignore-user-config` keeps authentication while omitting user config | Sandbox selection plus configuration overrides; MCP tool allowlists. CQ selects `danger-full-access` for every role, so the Codex sandbox restricts nothing ([Codex sandbox mode](#codex-sandbox-mode)). A live reviewer reached a patch handler although freeform patch presentation was disabled; with this launch nothing in Codex refuses that write. |
| Pi 0.87.1, 0.99.1, 1.0.0 | `--print`, `--mode json`, explicit session identity | Built-in/extension tool allowlists and discovery-disable flags. The bundled CQ extension implements the bounded MCP bridge; live scoped reads and worker writes pass. |

`HarnessUsage.versions` lists the verified versions per harness; a profile or usage collection with any other version is refused. Codex 0.160.0 joined the list on 2026-10-05 without a model run: `codex --version` prints `codex-cli 0.160.0`, `codex exec --help` lists every flag of the adapter's argument list, and `codex execpolicy check` accepts the generated `.codex/rules/cq.rules`; its output and usage records were not probed. The 2026-10-01 D73 probes ran each installed version with the adapter argument lists above, no MCP endpoints and a structured one-word reply: all three accepted every adapter flag, finished with exit 0 and produced their final structured result. Claude 2.1.285 adds `system`/`thinking_tokens` events, a `user` tool-result event for `StructuredOutput` and `wire_tool_inputs`/`fallback_credit` fields; the `result` record is unchanged. Codex 0.159.2 and Pi 0.99.1 emit the same events as their earlier versions. Reduced stdout fixtures are retained under `server/src/test/resources/harness-usage/`. Pi 1.0.0 joined the list on 2026-10-06 without a model run: real 1.0.0 processes ran the adapter's argument list against a local stub provider, accepted every flag and emitted, event for event, what 0.99.1 emits for a completed turn, a recovered retry and eighteen provider refusals; the bridge extension and the generated attached extension were exercised in real 1.0.0 processes ([Pi 1.0.0](../validation/pi-1.0.0-20261006.md)). No Pi 1.0.0 session with a real model has been recorded.

Official Codex documentation describes JSON event output and usage in terminal turn events, plus separate final-message capture. The local collector must verify the installed output rather than treating a sample as a completeness guarantee. [Non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode).

Codex HTTP MCP configuration supports a bearer-token environment reference and tool allowlists. These controls complement CQ's server-enforced role permissions. [MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).

The earlier [usage probes](../drafts/20260926-usage-observability.md) establish configured harness observability for those specific calls; they are not CQ consumer evaluations. M2 must implement and evaluate process ownership, bounded launch/drain/termination, role restrictions, prompt/result handles and host collectors for each adapter. Credentials remain with their configured harnesses; inspection did not print authentication files or keys.

## Common launch boundary

Profiles require an explicit executable, installed version, model and provider. No adapter changes model or harness on failure. Claude currently accepts only the verified `anthropic` route; Codex passes `model_provider`, and Pi passes `--provider`. Pi can load an explicit inventory of trusted provider extensions while disabling discovery of unrelated extensions. Alternate providers/extensions need their own runtime capability evidence.

A launch takes its model, provider and reasoning effort from a `ModelRoute` and everything else (executable, version, provider extensions, provider environment) from the settings entry of the route's harness; a route without a provider takes the entry's. A settings entry by itself states the route of its own model and provider with no effort, which leaves the harness's default level. The attempt records the route: `Attempt.provider`, `model` and `effort`.

| Harness | Effort argument | Levels |
| --- | --- | --- |
| Claude Code 2.1.285 | `--effort <level>` | low, medium, high, xhigh, max |
| Codex 0.160.0 | `-c model_reasoning_effort="<level>"` | minimal, low, medium, high, xhigh, max, ultra |
| Pi 0.99.1, 1.0.0 | `--thinking <level>` | off, minimal, low, medium, high, xhigh, max |

An adapter refuses a level its harness does not name: Claude Code and Pi answer an unknown level with a warning and run their default, and Codex takes any text. The harness may still map a level to what the model offers (observed against a stub provider: Codex sent `max` for `ultra` and Pi sent `xhigh` for `max` and `low` for `minimal` with the models probed, `gpt-6-sol` and `gpt-5.5`).

Pi reads the text after the last colon of `--model` as a thinking level when it is one of its levels, unless its catalogue holds the whole name: `x:high` runs as model `x` when Pi knows `x`, and also when no `--thinking` is passed. A Pi model name with such an ending therefore selects no one model; the agent configuration reports it (`AgentProblem.ModelAmbiguous`) and the adapter refuses it. Any other colon in a name is passed on as written.

Each invocation supplies host-owned system instructions, a result schema, scoped MCP endpoints and a private asset directory. Only Worker receives native editing/shell tools. That is tool policy: it says what a role is meant to do, and it is not filesystem protection. Governor has the six CQ domain tools and local `dispatch`; Explorer, Planner, Worker and Reviewer have `search`, `read`, `usage` and local `workspace`. Worker/reviewer dispatch is implemented; explorer/planner execution remains M4 work. CQ independently denies child domain writes even when called directly.

The environment allowlist retains runtime, proxy/certificate and configured harness-home variables. Provider-specific variables must be named explicitly. CQ root/controller credentials and inherited harness control flags are excluded. Codex receives scoped bearer tokens through dedicated environment variables; Claude/Pi receive them in private configuration files. Tokens never appear in process arguments. Assets use 0700 directories and 0600 files, are forced to disk and accept only byte-identical retries; changed or symbolic replacements fail explicitly.

The inherited `__NIXOS_SET_ENVIRONMENT_DONE` marker is retained for harness and validation commands: without it, NixOS shell startup replaces the supplied toolchain PATH. A production-adapter regression reproduces missing tool discovery before this correction.

These controls implement the cooperative-agent threat model. Worker shell access is not a security boundary against an agent deliberately inspecting credentials or escaping its workspace. For Codex this holds for every role, not Worker alone: a Codex Governor, Explorer, Planner, Worker or Reviewer is launched with `danger-full-access` and can write files wherever the operator's outer sandbox lets the host process write ([Codex sandbox mode](#codex-sandbox-mode)). Server authorization remains authoritative for CQ writes. System-managed harness policy can further restrict a launch; failure is reported without privilege fallback.

## Abstention

An attempt abstains when it could not run its model for a reason that is none of the work's, so that another model may run the same input. It ends in state `Abstained` (dispatch phase `Abstained`, `next: ResolveBlocker`) with the gap and blocker `Abstained (<reason>): <detail>`. It is registered in usage like every attempt. Its input is released as it was: no fault artifact is published and nothing is compared for repetition. Nothing retries on an abstention yet. A Worker that abstained after changing files keeps its partial-work capture, and its workspace is quarantined as a failed one's is: the capture is bounded, and the tree is the only whole copy of what the Worker left.

| Reason | When |
| --- | --- |
| `Unconfigured` | The session settings have no entry for the route's harness, or the entry names an unverified version. |
| `Launch` | The installed version differs from the entry's, or the executable cannot be run; a `providerEnvironment` name is unset; the adapter refuses the route (effort, Pi model name, Claude provider); the guardian could not start the process (`StopReason.LaunchFailed`). |
| `Credential`, `Quota`, `RateLimit`, `Unavailable` | The harness ran, ended by itself and its native output ends in a provider refusal of that class. |

`AbstentionClassifier` reads the provider classes from the native events, for the harness versions whose refusals were captured (Claude Code 2.1.285, Codex 0.160.0, Pi 0.99.1 and 1.0.0) and no other. Pi 1.0.0 states every captured refusal as 0.99.1 does, so one set of patterns reads both. The transcripts are retained under `server/src/test/resources/harness-usage/abstention/`; their README says how each was produced. A stopped, cancelled or uncertain job is judged by how it was stopped, whatever its output says. Everything the classifier does not recognise is a failure and follows the rules for failures: a model the provider does not know, a 403, a malformed report, a rejected admission, a crash.

| Harness | Read from | Credential | Quota | RateLimit | Unavailable |
| --- | --- | --- | --- | --- | --- |
| Claude Code | the terminal `result` with `is_error` and `terminal_reason: "api_error"`, and the `error` of the last assistant event marked `is_api_error_message` | `authentication_failed` | `billing_error`; `rate_limit` after a `rate_limit_event` whose status is `rejected` (an exhausted plan window) | `rate_limit` otherwise | `server_error` (500, 503, 529 observed) |
| Codex | the message of the last `turn.failed` | `unexpected status 401 Unauthorized: …` | `Quota exceeded. Check your plan and billing details.`; `You’ve hit your usage limit. …` | `exceeded retry limit, last status: 429 Too Many Requests` | `We’re currently experiencing high demand, …` (500); `unexpected status 502`/`503`/`504 …` |
| Pi | the last assistant `message_end`: `stopReason: "error"` and `errorMessage`, after Pi's own retries | `anthropic-messages`: 401 `authentication_error`; `openai-responses`: 401 `invalid_api_key` | 400 with Anthropic's credit-balance message; 429 `insufficient_quota`; `openai-codex-responses`: `You have hit your ChatGPT usage limit …` | 429 `rate_limit_error`; 429 `rate_limit_exceeded` | 500, 503, 529 (`anthropic-messages`); 500, 502, 503, 504 `server_error` (`openai-responses`) |

Pi's `openai-codex-responses` API reports every 429 as its usage-limit sentence, so a request-rate refusal there reads as `Quota`; its other errors carry the provider's message alone and are not classified. Pi passes on the reply of whichever provider it calls: a provider API other than these three is not classified.

## Harness differences

- **Claude:** restricted mode, explicit built-in and MCP allowlists, strict MCP configuration, disabled slash commands/native subagents, fresh explicit session identity and no session persistence. `--safe-mode` would disable required MCP; `--bare` would change configured authentication. Neither is used. The live native inventory contained only the requested tools plus `StructuredOutput`.
- **Codex:** fresh ephemeral JSON execution, explicit model/provider, ignored user configuration/rules, disabled native agents/plugins/apps/memories/web access, role-dependent shell tools, and `--sandbox danger-full-access` for every role. Code-mode hosting remains enabled because the installed runtime requires it for MCP calls. Disabling it reproduced an unusable tool host. Disabling freeform patch presentation alone does not remove all patch access: the live reviewer reached a patch handler. With `danger-full-access` nothing in the launch refuses such a write, so a Codex reviewer, explorer, planner or governor can write files ([Codex sandbox mode](#codex-sandbox-mode)). Configuration keys are checked against the [pinned upstream schema](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/core/config.schema.json).
- **Pi:** fresh JSON print mode, discovery disabled for extensions/skills/context/prompts/themes, explicit CQ bridge/provider extensions and a role-specific tool allowlist. The bridge initializes MCP protocol 2025-03-26, completes the initialization notification, verifies the requested inventory and registers only those tools. HTTP operations have a 30-second deadline, 2 MiB request/response bounds, strict UTF-8 and no redirects; scoped credentials are not forwarded to redirect targets. Tool cancellation propagates. CQ's text tool results are supported. Pi lacks a native result-schema switch, so the host must validate its final JSON.

All launchers consume the assembled prompt through stdin. The existing guardian and `JobSupervisor` own process drainage, deadlines, cancellation and worktree isolation. The current live fixture explicitly validates native completion, final JSON, scoped reads and filesystem effects, then preserves audit evidence. The [batch supervisor role](supervisor-role.md) now uses the shared native result parser, byte-preserving logs and a replayable publication queue. Complete interruption recovery remains pending. Test-only probe entrypoints are not product executables.

### Codex sandbox mode

Decision: Question 26 (revision 3), correcting Defect 104. The operator chose "No Codex sandbox at all (danger-full-access) for every role, relying on the operator's outer sandbox." `CodexAdapter.launch` therefore passes `--sandbox danger-full-access` for Governor, Explorer, Planner, Worker and Reviewer. The launch previously passed `workspace-write` to Worker and `read-only` to every other role; Codex's Linux sandbox refuses the connection to `/nix/var/nix/daemon-socket/socket` in both, so no Codex child could run `nix develop`. Nothing else in the launch changed: `approval_policy` stays `never`, and the builtin tool settings and MCP allowlists per role are still those of `HarnessTools.policy`. No other restriction replaces the sandbox.

Three things bound a Codex child, and they are not interchangeable:

- **Filesystem boundary.** The operator's outer sandbox, and nothing else. A Codex child of any role, including Reviewer, Explorer, Planner and Governor, can write files wherever the outer sandbox lets the host process write. CQ adds no filesystem protection of its own for Codex children.
- **Workflow constraints.** The tool policy (`HarnessTools.policy`) and the role instructions say what a role is meant to do: only Worker gets shell and patch tools, and the other roles are told not to edit. They do not prevent a write. A live Codex reviewer reached a patch handler although freeform patch presentation was disabled.
- **Candidate capture.** It controls what the host integrates. It prevents no write, and it is not filesystem protection.

What the host does with files that a non-editing Codex child writes, read from the code:

- Every attempt runs in its own Git worktree, `workspaces/<attempt>/tree` under the session directory, at the attempt's base commit.
- `CandidateWorkspace.capture` runs only for a `Work` report with a `CandidateReady` member, and `ChildContracts.report` accepts a `Work` report only from a Worker in Implement or ResolveConflict mode. The tree of an Explorer, Planner, Reviewer or Probe Worker is never captured. Workspace evidence and partial-work collection also read Worker trees only.
- A candidate Reviewer's result carries the candidate commit of the result it reviewed, unchanged. The declared checks it requests run in separate worktrees created at that commit, not in the Reviewer's tree.
- The batch Governor's worktree is created at the session base and is never captured.
- A completed attempt's worktree is removed. A failed or cancelled attempt's worktree is quarantined and stays on disk for inspection.

So a file that a non-editing child writes inside its own worktree does not enter a candidate and does not reach the integration target through the host. This follows from capture, not from any protection of the tree.

A write outside that worktree is a different matter. The worktree shares the repository's Git common directory, which holds the objects, `refs/cq/candidates/*` and the integration target's ref, and the session directory, which the host itself writes, is equally within reach. Nothing in the Codex launch prevents a child of any role from writing there, and this document does not claim that the host detects such a write. Only the operator's outer sandbox bounds it.

Observed with Codex 0.159.2 and no model call: `codex sandbox -c sandbox_mode="danger-full-access" -- nix store info` succeeds; the same command with `read-only` or `workspace-write` fails with `cannot connect to socket at '/nix/var/nix/daemon-socket/socket': Operation not permitted`. Defect 104 remains Open until a managed Codex child has run a `nix develop` command successfully after integration.

### Codex tool approval and schema compaction

The Codex-governed consumer run reproduced `auto` MCP approval mode requesting approval for CQ writes under a noninteractive `never` policy. CQ configures its already-allowlisted endpoints with `default_tools_approval_mode = "approve"`; this does not add tools or change server permissions. The next run passed this boundary. The [official MCP configuration](https://learn.chatgpt.com/docs/extend/mcp) documents the per-server and per-tool controls.

Codex 0.156.1 applies a [hardcoded 5,000-byte normalized input-schema budget](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/tools/src/json_schema/compaction.rs). Its lossy passes replace local references with empty schemas and discard definitions. The CQ change schema is 14,937 bytes and usage is 5,571; the actual governor guessed fields and failed request validation. No bypass was found in the inspected MCP conversion path.

For this pinned harness, the host adds a complete generated argument-schema guide to the installed instructions for visible tools above a conservative 4,000-byte threshold. Definitions are deduplicated and must agree; authorization still determines the tool inventory. Governor currently receives change, usage and dispatch contracts; children receive only usage. Claude/Pi instructions are unchanged. The existing 48-KiB UTF-8 instruction bound is enforced before launch, and the actual augmented instructions are published as the prompt artifact with its SHA-256. The real Codex → Pi → Claude consumer chain passes with this guide. Its measured addition is 23,340 UTF-8 bytes including the header; this establishes compatibility, not a token-efficiency benefit.
