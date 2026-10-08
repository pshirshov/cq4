# Probe of ponygirls-subagents in a real Pi process

Recorded 2026-10-08 for [native dispatch](../../design/native-dispatch.md) (Task 75, Idea 36, Decision 13). The statements of the design that rest on a run cite a file of this directory.

**What was real and what was not.** Pi, the extension, its SDK worker processes, Git, bubblewrap and the file system were real. **No model took part and no login was read.** The model provider was a scripted local imitation of the OpenAI Responses API (`scripts/probe.mjs.txt`, function `script`), which answers a request by what the request holds (its tool list and the calls already in its history) with a fixed call or fixed text, and reports invented token counts (`100 + n` input, `10 + n` output, request number `n`). The key in `auth.json` is a placeholder. What a real model does with the extension's tools, and what a real provider reports as usage, is **not observed** (see [not observed](#not-observed)).

## Versions and command lines

| | |
| --- | --- |
| ponygirls | `7aa0caab568f82a90653f701e60e7f30aae027b6` (repository head, 2026-10-08T21:35:25+01:00); the extension directory `nix/pkg/pi-extensions/ponygirls-subagents` last changed in `200bcff471827797ae8a5cbda8f9cf0873a029a1` (2026-10-07T21:18:58+01:00); its `package.json` says `0.1.0`. Cloned with `GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git clone https://github.com/7mind/ponygirls` (the user's Git configuration rewrites `https://github.com/` to SSH, which fails in the worker's sandbox) |
| Pi | 1.0.0, `/nix/store/lxcvk96h5ki359rz8f9vv7kj0r8p1xfk-pi-coding-agent-1.0.0/bin/pi` (`pi --version` printed `1.0.0`) |
| Pi, supplementary | 1.1.0, `/nix/store/hqw3jmpzzhzi7kdhfzd8cczbppjkbf6q-pi-coding-agent-1.1.0/bin/pi`, the version the operator's installed `pi` wrapper runs (`pi --version` on this machine prints `1.1.0`) |
| Node | v24.20.0 (`node --version`; the extension's workers are forked with this interpreter) |
| Git | 2.55.0; bubblewrap 0.12.0 |
| Kernel | Linux 7.1.9 |

Commands (the Pi command line of each run is in `command.txt` of its directory; `PATH` is elided there):

```sh
# Pi 1.0.0, scenarios A to F in one RPC session; exit status 0
BRIDGE=scripts/bridge.mjs PI_ROOT=/nix/store/lxcvk96h5ki359rz8f9vv7kj0r8p1xfk-pi-coding-agent-1.0.0 \
  EXT=<ponygirls>/nix/pkg/pi-extensions/ponygirls-subagents node probe.mjs OUT          # scripts/probe.mjs.txt
# Pi 1.0.0, governing session killed with SIGKILL, restarted with --continue; exit status 0
PI_ROOT=...-1.0.0 EXT=... node recovery.mjs OUT                                       # scripts/recovery.mjs.txt
# Pi 1.1.0, scenarios A to F again; exit status 0
BRIDGE=... PI_ROOT=/nix/store/hqw3jmpzzhzi7kdhfzd8cczbppjkbf6q-pi-coding-agent-1.1.0 EXT=... node probe.mjs OUT
```

The scripts are kept with the suffix `.txt` because they are evidence, not part of the build. Each run uses a fresh Git repository of one commit (`data.txt`), a private `HOME`, a private `PI_CODING_AGENT_DIR` holding `models.json` (provider `openai` pointed at the stub), `auth.json` (placeholder key) and `subagents-policy.json` (`allowedModels: null`, one registered repository `proj`), `PI_SUBAGENTS_SDK_ROOT` set to the Pi installation's `lib/node_modules/pi-monorepo`, and `pi --approve --offline --provider openai --model gpt-5.5 --mode rpc --no-extensions --extension <ponygirls-subagents> [--extension bridge.mjs] --no-skills --no-context-files --no-prompt-templates`. The governing session is driven by RPC `prompt` commands; the stub's governing model calls the extension's tools.

## Files

| Directory | Contents |
| --- | --- |
| `pi-1.0.0/` | `marks.json` (the observations the script printed), `provider-requests.jsonl` (every request the stub saw: time, tool names, the stub's reply, the tail of the request's input), `scenario-{A,C,D,E,F}-tool-results.json` (the governing session's tool results), `journal-excerpt.jsonl` (journal records of the extension's root store), `root-dir-listing.txt` (the store layout), `command.txt`, `pi-stderr.txt` (empty) |
| `pi-1.0.0-recovery/` | the same for the abrupt-death run, with `scenario-R2-tool-results.json` |
| `pi-1.1.0/` | `marks.json`, `provider-requests.jsonl`, `command.txt` of the supplementary run |
| `scripts/` | `probe.mjs.txt`, `recovery.mjs.txt`, `bridge.mjs.txt` |

## Scenarios and what was observed (Pi 1.0.0)

| Scenario | What the stub's governing model did | Observed | Evidence |
| --- | --- | --- | --- |
| A, reader and writer | `spawn_agent` reader (`isolation` omitted), `spawn_agent` writer, `wait_agent all_settled`, `list_agents`, `read_agent result` twice | Both spawn calls returned at once with `agentId`, `path`, `taskRunId`, `generation`, `isolation: worktree`, `workdir`, `status: running`. The reader's `workdir` is the governing checkout itself; the writer's is `<agent dir>/subagents/<session id>/worktrees/wt-project-<12 hex of base>-<epoch ms>`, a detached worktree at the checkout's `HEAD` (base `d575c03…`). The writer wrote `out.txt` there; the governing checkout's `git status --short` stayed empty; `git worktree list` of the governing checkout lists the extension's worktree. The result of each child is its last assistant text (`READER-RESULT: …`, `WRITER-RESULT: wrote out.txt`) with `outcome: succeeded` and an empty `detail`. `list_agents` and `read_agent` give per agent `usage: {inputTokens, outputTokens, cost, unknown}` (reader 207/27, writer 321/51; the stub reported 100+n and 10+n per request, so these are sums over two and three requests). A child's request carried 8 tools (reader: `read grep find ls send_message wait_agent list_agents read_agent`) or 11 (writer: plus `write edit bash`) and none of the governing session's other tools | `pi-1.0.0/scenario-A-tool-results.json`, `provider-requests.jsonl` (requests 1 to 12), `marks.json` (`A-*`, `git-*`) |
| B, idle parent | `spawn_agent` of a reader that the stub holds for 5 s, then the end of the turn; the session then stays idle 12 s; then a second prompt | Between the end of the turn (21:38:47.76) and the second prompt (21:38:59.83) the child finished (journal `task.terminal` 21:38:52.97) and **Pi emitted no event at all** (`B-events-while-idle-after-child-finished: []`). The second prompt's request (stub request 17) then carried `[subagents]\n/root/slow task … ended succeeded` as a user-role message after the prompt. The notices of children that ended during a running turn are appended at `turn_end` (the notices of scenario A appear in the requests that follow the `wait_agent` call), the others at the next `input` | `pi-1.0.0/marks.json` (`B-*`), `provider-requests.jsonl` (requests 14 to 17) |
| C, gated writer | `spawn_agent` writer with `gate` (reviewer model `openai/gpt-5.5`, `maxRounds: 2`, check `exists` = `test -f out.txt && echo present`, `promisedOutputs: ["out.txt"]`), `wait_agent`, `list_agents`, `read_agent events` | The gate ran in order: writer settled, candidate `candidate-1` recorded with a fingerprint of `out.txt` (path, kind `untracked`, mode, SHA-256) over base `d575c03…`, check evidence `exists: exit 0, "present"`, a reviewer agent `/root/gated/__gate` started (a fresh session file under `sessions/<reviewer id>/`), the reviewer's request carried exactly 5 tools (`read grep find ls submit_gate_decision`), it called `submit_gate_decision` (`approve`), and the writer's task ended with `taskOutcome: passed`, detail `approved candidate candidate-1`. **Nothing was committed**: the candidate is a fingerprint of the worktree, which stays dirty. The reviewer's usage is listed on its own agent row. | `pi-1.0.0/scenario-C-tool-results.json`, `journal-excerpt.jsonl` (`gate.*`, `task.phase`), `provider-requests.jsonl` (requests 18 to 27) |
| D, sandbox | `spawn_agent` writer, `isolation: sandbox`, `repo_id: proj` | Worked in this process: the writer got `wt-proj-…` under the root store, ran `write` and `bash` (the `bash` result shows the worktree path as `pwd` and `?? out.txt` from `git status`), and ended `succeeded`. Bubblewrap with user namespaces therefore ran in this worker's launch. **That is the worker's launch, not the operator's governing session**, whose namespace permissions were not observed | `pi-1.0.0/scenario-D-tool-results.json`, `provider-requests.jsonl` (requests 28 to 34) |
| E, interrupt | `spawn_agent` of a delayed reader, `interrupt_agent` at once, `wait_agent`, `read_agent status` | `interrupt_agent` returned `accepted: true` and the previous snapshot; the child settled `taskOutcome: interrupted` with `usage` 0/0 and `unknown: false` (no request had been made) | `pi-1.0.0/scenario-E-tool-results.json` |
| F, another extension starts a child | a tool `probe_bridge` of a second extension (`scripts/bridge.mjs.txt`) calls `ctx.executeTool("spawn_agent", …)`, `executeTool("wait_agent", …)`, `executeTool("read_agent", …)` | `typeof ctx.executeTool` is `function`; the tools callable from it include `spawn_agent list_agents read_agent wait_agent interrupt_agent close_agent manage_gate`; the nested `spawn_agent` returned the same result as a model call and the writer child ran to `succeeded` with a result and usage | `pi-1.0.0/scenario-F-tool-results.json` |
| R, abrupt death | two children running (a reader and a writer, both held 5 to 8 s by the stub); the Pi process is killed with `SIGKILL`; Pi is started again with `--continue` in the same agent directory | Both SDK worker processes were gone 1.5 s after the kill (their IPC channel closed with Pi); `root.lock/owner.json` named the dead Pi's pid and boot id. After `--continue` the extension bound the same session id and notified `subagents: recovered root (settled 2, quarantined 0, queued 0)`. `list_agents` then showed both agents `settled` with `taskOutcome: interrupted` and `usage: {0, 0, cost: null, unknown: true}`; the journal holds a second `root.init`, a `recovery.event` pair (`started`, `completed`), and per agent `usage.reported`, `generation.settled`, `task.terminal`. Nothing was replayed: the only requests after the restart are the governing session's own (stub requests 6 to 8); stub request 3 is the reader's request that was already in flight when Pi was killed, logged when the stub's held reply was released at 21:40:42, and no request for either task followed it. The writer's worktree stayed registered | `pi-1.0.0-recovery/marks.json`, `scenario-R2-tool-results.json` |

On Pi 1.1.0 scenarios A to F gave the same tool results, the same sequence of stub requests and the same idle-parent behaviour (`pi-1.1.0/marks.json`, `provider-requests.jsonl`). Only Pi 1.0.0 is claimed by the design; 1.1.0 is a supplementary observation (the extension's own check suite runs against 1.1.0 in the ponygirls flake, `flake.nix` check `ponygirls-subagents`, while the extension's worker states `PI_VERSION = "1.0.0"` and its documentation names 1.0.0 as the supported version).

## Not observed

- **A real model.** Whether a real model calls the tools correctly, how it words a result, what usage a real provider reports (cache fields, reasoning tokens), and whether a real provider's `cost` matches CQ's `Money` bases: not observed. The `cost` values above are computed by Pi from its model price table for the invented counts.
- **CQ in the loop.** No CQ host, server, attached `cq/pi-attached.mjs` extension, `cq_dispatch` call, claim, workspace, candidate capture or admission took part. Scenario F shows that a tool of another extension can start and read children; it does not show the CQ extension doing so.
- **The operator's governing session.** Not the operator's `pi` wrapper configuration, `subagents-policy.json`, `settings.json`, trust file or sandboxed launch. Whether bubblewrap with user namespaces works inside the operator's sandboxed launch is **unknown**; scenario D proves it only for this worker's launch.
- **`sandbox` isolation confinement.** That the sandboxed `bash` cannot reach the network or the host's files was not tested; the scenario shows only that a sandboxed writer runs.
- **Concurrency limits.** `maxRunnable` 4, `maxResidentWorkers` 8, `maxAgentsCreated` 32 (`src/scheduler.ts`) were read, not exercised.
- **Nesting, `close_agent`, `manage_gate` actions, `send_message`/question round trips, `resume_review`, reload of an idle agent.** Read in source, not run.
- **The `/agents` screen.** Not exercised (RPC mode has no TUI).
- **Pi in `--print`/`--no-session` mode with the extension** (what a managed Governor launched by `PiAdapter` would be). Not run; the design relies only on the source statement (`docs/ponygirls-subagents.md`, root lifetime) that recovery has no native session there.
- **Pi 0.99.1 and 0.87.1.** Not run.
