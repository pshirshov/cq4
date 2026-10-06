# Pi 1.0.0

Recorded 2026-10-06. Pi 1.0.0 (`/nix/store/lxcvk96h5ki359rz8f9vv7kj0r8p1xfk-pi-coding-agent-1.0.0/bin/pi`) joins Pi 0.99.1 (`/nix/store/6v3ax4h8wsnmn0nhz4c62hbahmmj39mf-pi-coding-agent-0.99.1/bin/pi`) in the verified versions. Evidence root: `/srv/nvme/tmp/cq4-crosscut4/logs/x7-pi1/`.

**No model took part and no login was read.** Every Pi process below was real and ran with a private empty `HOME` and `PI_CODING_AGENT_DIR` and a wrong key. Its provider was a local imitation of the provider's HTTP API, except in the two `credential` cases, where the provider's own endpoint answered the wrong key. What a real model does in a Pi 1.0.0 session, and what a real provider sends it, is not part of this record; [what is not verified](#not-verified) lists what such a session has to show.

## Differences between 0.99.1 and 1.0.0

From the two installations: the package's `CHANGELOG.md` (entries 0.99.2 and 1.0.0), a file-by-file comparison of the modules under `dist/` and of the bundled `@earendil-works/pi-ai` and `pi-agent-core` packages, `--help` of both executables, and the runs below. The executable runs the bundle under `dist/bundle/`, whose chunks are rebuilt in every release; where a row calls a file identical it means the module the bundle is built from, and the runs are what shows the behaviour. Both versions are published as `@earendil-works/pi-coding-agent`; the package namespace did not change between them.

| Area | 0.99.1 | 1.0.0 | Effect on CQ |
| --- | --- | --- | --- |
| Command-line flags | — | `--help` differs in two lines: the texts of `--provider` and `--tui-mode` | None on the flags CQ passes: `--offline`, `--mode json`, `--print`, `--no-session`, `--session-id`, `--provider`, `--model`, `--thinking`, `--no-extensions`, `--no-skills`, `--no-context-files`, `--no-prompt-templates`, `--prompt-template`, `--no-themes`, `--approve`, `--no-approve`, `--tools`, `--extension`, `--system-prompt` are all present and unchanged. |
| `--provider` without `--model` | Ignored without a message; Pi ran its default model | Refused: `Error: --provider requires --model (…)` | None on a managed launch, which always passes both. An interactive launch that names a provider must name a model. |
| Default TUI mode | `regular` (terminal scrollback) | `fullscreen` (alternate screen); `--tui-mode regular` or the `tuiMode` setting restores the earlier one | The CQ footer line shows in both modes. A recording that reads the terminal's scrollback finds only the visible screen in the fullscreen mode. |
| JSON event stream (`--mode json`) | — | Same event types, order and fields in every captured case; `session.version` is 3 in both | None. The usage collector and the abstention classifier read 1.0.0 with the code that reads 0.99.1. |
| Retry events | `auto_retry_start` (`attempt`, `maxAttempts`, `delayMs`, `errorMessage`), the answer, then `auto_retry_end` (`attempt`, `success`) | Same | None. The recovered retry is admitted on both. |
| Usage in `message_end` | `input`, `output`, `cacheRead`, `cacheWrite`, `reasoning`, `totalTokens`, `cost` | Same fields, same derivation from the provider's counts | None. |
| Error text of a refusal | — | Same for the Anthropic, OpenAI and ChatGPT-backend APIs in all eighteen cases | None. |
| Provider retry delay | An unparseable `Retry-After` date retried at once | Exponential backoff (changelog 0.99.2) | None on what CQ reads. |
| `--model` with a colon, `--thinking` levels | — | Same model and effort sent to the provider in all fifteen cases | None. `AgentResolution.piThinkingSuffix` and the effort set hold for both. |
| Extension API | — | `core/extensions/types.d.ts` differs in one comment and one new optional field of a tool group (`instructions`); `registerCommand` now refuses a command without a name or a handler | None. The generated extension is one file for both versions. |
| `turn_end.outcome`, `agent_settled`, `sendMessage`/`triggerTurn`, `sendUserMessage`/`deliverAs`, `ctx.isIdle()`, `ctx.ui.setStatus`, `session_shutdown` | — | Unchanged in source; exercised on both (below) | None. |
| Project trust | `trust.json` of the agent directory, canonical paths, nearest decision applies; `/trust` saves, `--approve` does not | `dist/core/trust-manager.js`, `project-trust.js` and `docs/security.md` are identical | None. `cq doctor harness pi` reads the same file the same way. |
| Session files | — | `docs/session-format.md` and `dist/core/session-manager.js` are identical | None; CQ reads no Pi session file. |
| `@earendil-works/pi-agent-core` | Exported an experimental harness, sessions and compaction | Those exports are removed; `Agent`, the agent loop and the proxy stream remain | None. The CQ extensions import nothing from Pi's packages. |
| MCP tool names | `mcp__my-server__x` | `-` becomes `_` in MCP tool and namespace names (changelog 0.99.2) | None. CQ registers its tools through the extension API, not as a Pi MCP server, and their names hold no `-`. |
| Codemode | — | Shorter tool descriptions, new `models.generateImages()` | None observed: with the adapter's `--tools` allowlist the provider received exactly the allowed tools. An interactive session keeps Pi's default tools, as before. |

## What changed in CQ

- `HarnessUsage.versions(Harness.Pi)` is `0.87.1`, `0.99.1`, `1.0.0`; a settings entry of either of the last two launches and is collected.
- `AbstentionClassifier.captured` lists the versions whose refusals were captured, and Pi has two. One set of patterns reads both.
- The extensions (`pi-attached.mjs`, `pi-bridge.mjs`), the adapter's argument list, the generated assets and the doctor are unchanged.
- `dev/pi-real-check.mjs` is new: the check of the extensions in a real Pi process described below.

## Captures

`capture/run.sh` and `capture/stub.py` under the evidence root are the scripts of the earlier abstention capture with the 1.0.0 executable and three scripted cases added to the stub. Each case keeps its command, stdout, stderr, exit status and the stub's request log under `capture/out/<case>/`. Every case exits 0 on both versions.

| Cases | Result on 1.0.0 | Retained as |
| --- | --- | --- |
| OpenAI API: 401 (provider's endpoint), 403, 404, 429 rate limit, 429 quota, 500, 502, 503, 504 | Event for event the 0.99.1 transcript | `abstention/pi-1.0.0-openai-*.jsonl` |
| Anthropic API: 401 (provider's endpoint), 400 credit balance, 404, 429, 500, 503, 529 | Same | `abstention/pi-1.0.0-anthropic-*.jsonl` |
| ChatGPT backend: usage limit, 500 | Same, apart from the store path in a stack trace | `abstention/pi-1.0.0-openai-codex-*.jsonl` |
| ChatGPT backend: 401, 429, 503 | Same; not retained for 0.99.1 either | — |
| A completed turn | Same as 0.99.1 | `pi-1.0.0-stub.jsonl` |
| First request answered 500, second completed | `error`, `auto_retry_start`, the answer, `auto_retry_end` with `success: true`, `agent_settled`, on both versions | `pi-1.0.0-stub-recovered-retry.jsonl`, `pi-0.99.1-stub-recovered-retry.jsonl` |
| First request answered 429, second completed | Same sequence | — |
| `--model` with a colon (seven cases), `--thinking` (eight levels) | The model and effort sent to the stub equal those of 0.99.1 | — |

The fixtures are under `server/src/test/resources/harness-usage/`; its README says how each was produced. `AbstentionClassifierLocal` reads every refusal transcript of both versions as the same class, and `HarnessUsageLocal` collects the completed turn and admits the recovered retry on both.

## The extensions in a real Pi process

`dev/pi-real-check.mjs` starts the Pi executable it is given, in RPC mode with the documented launch flags, in a project laid out as `cq configure pi` leaves it. The provider and the CQ host are scripted; Pi is real. It passed on Pi 1.0.0 and on Pi 0.99.1, each with fixture tool contracts and with the tool contracts and prompts that `cq configure pi` generated for a scratch project (logs `pi-real-<version>-<contracts>.log`):

```sh
CQ_PI_EXECUTABLE=/nix/store/lxcvk96h5ki359rz8f9vv7kj0r8p1xfk-pi-coding-agent-1.0.0/bin/pi CQ_PI_VERSION=1.0.0 \
  CQ_PI_HOST_CONFIG=/absolute/project/.pi/extensions/cq-host.json node dev/pi-real-check.mjs
```

| Observed | How |
| --- | --- |
| Pi loads `.pi/extensions/cq-host.js`; `/cq:drive` and `/cq:park` are extension commands and `/cq:advance` a prompt | `get_commands` |
| The footer reads `CQ driver off` at start | `setStatus` request of the extension UI |
| The nine `cq_*` tools reach the provider with their contracts | The provider's request |
| A `cq_dispatch` call of the model reaches the host with its arguments, and the host's reply reaches the model | The host's log and the provider's next request |
| Each assistant response is reported to the host with Pi's session identifier, response identifier, stop reason and usage | The host's `cq/piUsage` log against the provider's counts |
| A reply that shows a running attempt starts `cq wait --session … --attempt … --json` | The waiter's log |
| In an idle session, the waiter's end posts `CQ: attempt … ended: …` and a turn starts without any command | `agent_start` with no RPC command, and the provider's request holding the line |
| `/cq:drive` sends `Start`, then `Continue`; the directive is submitted and Pi expands it through the `/cq:advance` prompt; `turn_end` refreshes the status; after `agent_settled` the host is asked again and its stop is shown | The host's `cq/driver` log, the provider's request, the footer texts and the notice |
| The session key is the same in every driver and usage request and equals Pi's `sessionId` | `get_state` |
| Closing Pi closes the host's connection; a launch with `--approve` writes no `trust.json` | The host's log and the agent directory |
| Without `--approve`, the extension loads when `trust.json` holds `true` for the project, or for a parent with `null` for the project, and not when the project's entry is `false` or no entry exists | `get_commands` of four launches |
| A managed child with the adapter's argument list and the bridge extension: the provider receives exactly `read` and `cq_read` and the effort of `--thinking`; the `cq_read` call reaches the MCP endpoint with its bearer token; the output begins with `session` carrying `--session-id` and ends with `agent_settled` | The provider's and the endpoint's logs and Pi's stdout |

With `triggerTurn` set to `false` in the extension the check fails at the idle-session step (`Pi did not report agent_start`), so that step observes the mechanism and not a default.

On a pseudo-terminal, interactive Pi 1.0.0 with the same flags entered the alternate screen and showed `CQ driver off`; with `--tui-mode regular` it did not enter the alternate screen and showed the same line, as 0.99.1 does by default.

`cq doctor harness pi` on a scratch project configured for 1.0.0, with the real executable and an empty read-only home, reported `Settings`, `Harness version` and all six generated files `Current`. `Hook trust` was `Current` with a `trust.json` holding `true` for the project or for its parent, and `Failed` with `false` for the project or without `--harness-config` (logs `doctor-*.log`). The files were written by hand in the format Pi's trust store writes; the launches above show that Pi 1.0.0 reads that format the same way.

## Not verified

A session of Pi 1.0.0 with a real model and a real provider has not been run. It has to show:

- **Usage of a real response.** `message_end.message.usage` of a real provider response, with its `responseId` and Pi's cost, against the retained 0.99.1 fixture `pi-0.99.1.jsonl`, and the usage record the attached host stores for it.
- **A real refusal.** The `errorMessage` of a real rate limit, exhausted quota or outage, in particular of the ChatGPT backend, whose replies the stub imitates from memory.
- **Retries of a real stream.** The D140 sequence came from a WebSocket error of the ChatGPT backend; the stub reproduces a retry after an HTTP 500 only.
- **The interactive TUI.** The footer through a drive, the `Working` busy line, the toggle key `ctrl+alt+a`, `/trust`, and how a recording captures the fullscreen mode.
- **A busy session.** The waiter's message reaching a model that is in a turn, at its next tool-call boundary.
- **A real host.** The extension in real Pi 1.0.0 connected to `cq host pi` and a server; `dev/pi-driver-session.mjs` covers that host with a stand-in Pi runtime, and the check above covers real Pi with a scripted host.
- **A drive.** A model following the directive and the workflow prompt through child dispatch, a waiting stop and its continuation, park, `escape`, and two concurrent sessions with distinct keys.
- **A managed child.** A child launched by the supervisor on Pi 1.0.0 with a real model: its structured result, its usage record and, on a provider refusal, its abstention.
