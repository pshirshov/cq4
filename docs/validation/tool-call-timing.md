# Tool-call timing in native harness output (T87, for R21 / I35)

Question (Q77's answer): can the start and end of a tool call be read from the native output of Claude Code, Codex and Pi, paired and timed, and what do receipt times of the output lines show? This document records evidence only. It changes no production code and concludes nothing for R21.

**No real model took part.** The harnesses are the installed executables, run against a local stub provider (`stub_provider.py`) whose replies, token counts and identifiers are invented; no real login was read (private empty `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`, `HOME`, a wrong key). The harnesses' own events are real. Everything below holds for the versions and the stub named here.

All files are under `server/src/test/resources/harness-usage/tool-timing/` (cited as `<directory>/<file>:L<line>`; `README.md` beside it lists the files). Versions, read from each executable (`<directory>/version.txt`): Claude Code 2.1.295, Codex 0.162.0, Pi 1.1.0.

## Method and clock

- Argument lists follow the Worker launch of `host/src/main/scala/cq/host/HarnessAdapter.scala` (Claude `:148-154`, Codex `:196-199`, Pi `:227-232`); the exact commands are in `<directory>/command.txt`. Differences: no CQ MCP endpoints and no Pi bridge extension (case c adds the stub MCP server `stub`), local provider URLs, an invented model name.
- **One clock**: CLOCK_REALTIME of this machine, seconds since the epoch. Python's `time.time()` (receipt times, the stubs' logs) and `date +%s.%N` (markers) read it. Absolute values are not compared across runs.
- **Execution interval**: from the markers written by the scripted shell command itself (`markers/<label>.start|.end`: `date`, `sleep 2`, `date`), and, for case c, from the stub MCP server's log (`mcp-calls.jsonl`, the `tools/call` request's arrival and the completion of its reply). These are independent of the harness's output.
- **Receipt interval**: `stdout.times` line N is the time at which the read of the harness's stdout pipe returned the complete line N of `stdout.jsonl` (unchanged). It says when a line arrived, not when the tool ran. A receipt interval is never presented below as an execution duration.
- Figures are printed from the files by `figures.py` (pairing by the keys below) and `analyze.py`. Cases: `a` one shell call sleeping 2 s; `b` one model response with two shell calls, each sleeping 2 s; `c` one call of the stub MCP tool `stub_wait` (2 s); `a-rollout` Codex case a without `--ephemeral`.
- All nine captures were made; none failed. Every run exited 0 (`<directory>/exit.txt`).

## Events, names and pairing keys, per harness and version

### Claude Code 2.1.295 (`--output-format stream-json --verbose`)

- **Start**: an `assistant` event whose `message.content` holds a `tool_use` block (`claude-2.1.295-a/stdout.jsonl:L2`). It is emitted when the model response has been parsed, **before** the tool is run (see the figures below). With two tool_use blocks in one response there is one `assistant` event per block (`claude-2.1.295-b/stdout.jsonl:L2`, `:L3`, same `message.id`).
- **End**: a `user` event whose `message.content` holds a `tool_result` block (`claude-2.1.295-a/stdout.jsonl:L3`); `tool_use_result` and `tool_result_meta` accompany it.
- **Name and command**: `tool_use.name` (`Bash`; an MCP tool as `mcp__stub__stub_wait`, `claude-2.1.295-c/stdout.jsonl:L2`); for a shell call the command is `tool_use.input.command` (`claude-2.1.295-a/stdout.jsonl:L2`; the same input again in the event's `wire_tool_inputs`).
- **Pairing key**: `tool_use.id` = `tool_result.tool_use_id` (`toolu_stub_1_0` at `claude-2.1.295-b/stdout.jsonl:L2` and `:L4`, `toolu_stub_1_1` at `:L3` and `:L5`).
- **Timestamp**: the `assistant` and `user` events carry `timestamp` (ISO 8601, millisecond); the `system`/`init` and `result` events do not. This differs from the retained 2.1.285 fixtures, which have no `timestamp` key (`grep -c '"timestamp"` on `claude-2.1.285.jsonl`, `claude.jsonl`: 0). What it denotes: it is the time the event was produced, a few ms before the line reached the reader, not the time the tool started or ended. In case a: `:L2` timestamp 18:40:40.748Z, receipt 18:40:40.753 (marker start 65 ms later); `:L3` timestamp 18:40:42.833Z, receipt 42.836, marker end 42.822. In case b the start event of the second call (`:L3`) has timestamp 18:40:44.305Z, 2.07 s before that call began to execute. The two events of a call thus have distinct timestamps, but the start one is the time of the model response, not of execution.
- Case a/b/c also hold a final `StructuredOutput` tool_use/tool_result pair (`--json-schema`; `claude-2.1.295-a/stdout.jsonl:L4-L5`) that is not work. The `result` event has `duration_ms`, `duration_api_ms`, `num_turns` for the whole run only (`:L6`).

### Codex 0.162.0 (`exec --json`)

- Codex 0.162.0 offers the model a JavaScript `exec` tool (code mode); a shell command is run from it as `tools.exec_command`, an MCP tool as `tools.mcp__stub__stub_wait`. The stub therefore scripts `exec` calls (the tool catalog is in `requests.jsonl`, `tools`; the script in `stub_provider.py`).
- **Start**: `item.started` with `item.type` `command_execution` (`codex-0.162.0-a/stdout.jsonl:L8`) or `mcp_tool_call` (`codex-0.162.0-c/stdout.jsonl:L8`). **End**: `item.completed` with the same `item.id` (`:L9`, `exit_code` and `aggregated_output` for a command, `result` for an MCP call). The `exec` wrapper call itself and its `custom_tool_call` appear as no event of the stdout.
- **Name and command**: command: `item.command`, the shell invocation as one string (`/run/current-system/sw/bin/zsh -c '<command>'`); MCP: `item.server` and `item.tool` (`stub`, `stub_wait`).
- **Pairing key**: `item.id` (`item_1` at `:L8` and `:L9`; `item_1`/`item_2` at `codex-0.162.0-b/stdout.jsonl:L8-L11`).
- **Timestamp**: not available. No stdout event carries a time or duration (`codex-0.162.0-a/stdout.jsonl`, all 11 lines).
- Before the work, lines 3-6 (`error`) and 7 (`item.completed`, `item.type` `error`) report the refusal of the WebSocket upgrade by the stub (404, `codex-0.162.0-a/stderr.txt`) and the fall back to HTTPS; this delays the first provider request by about 6 s in every Codex run and is an effect of the stub.

### Pi 1.1.0 (`--mode json --print`)

- **Start**: `tool_execution_start` (`pi-1.1.0-a/stdout.jsonl:L13`; `tool_execution_update` follows, `:L14`). **End**: `tool_execution_end` (`:L15`), which carries `durationMs`. The model's request is also in the assistant `message_end` as a `toolCall` content block (`:L12`).
- **Name and command**: `toolName` (`bash`) and `args.command` (`:L13`); an assistant `toolCall` block has `name` and `arguments`.
- **Pairing key**: `toolCallId` (`call_stub_1_0|fc_stub_1_0` at `:L13`, `:L15`, and in the `toolResult` message at `:L17`); in case b `call_stub_1_0|...` at `pi-1.1.0-b/stdout.jsonl:L16`, `:L20` and `call_stub_1_1|...` at `:L17`, `:L21`.
- **Timestamp**: `tool_execution_start`/`tool_execution_end` carry no `timestamp`; `tool_execution_end` carries `durationMs` (2024 in case a, `pi-1.1.0-a/stdout.jsonl:L15`; 2021 and 2017 in case b, `pi-1.1.0-b/stdout.jsonl:L20`, `:L21`). `message_start`/`message_end` carry a millisecond `timestamp`, equal in the two events of one message (assistant message 1791571234121 at `pi-1.1.0-a/stdout.jsonl:L8` and `:L12`, 30 ms before the stub received the request; `toolResult` message 1791571236193 at `:L16`, `:L17`, 5 ms after the end marker). The evidence is consistent with creation of the message; Pi's source was not read. `durationMs` exceeds the marker interval by 19 ms (2024 ms against 2005 ms in case a), so it covers more than the marked `sleep`; its definition was not established.

## Case (b): two shell calls from one model response

The conclusion of concurrency rests on the marker files alone (`figures.py`; times are seconds, `…` is the epoch prefix 1791571…):

| Harness | Interval b1 (marker) | Interval b2 (marker) | Overlap | Result |
|---|---|---|---|---|
| Claude Code 2.1.295 (`claude-2.1.295-b/markers`) | 244.330215 – 246.334408 (2.004 s) | 246.371085 – 248.375613 (2.005 s) | 0.000 s | **sequential**; b2 began 0.037 s after b1 ended |
| Codex 0.162.0 (`codex-0.162.0-b/markers`) | 274.603699 – 276.608528 (2.005 s) | 276.629904 – 278.634194 (2.004 s) | 0.000 s | **sequential**; b2 began 0.021 s after b1 ended |
| Pi 1.1.0 (`pi-1.1.0-b/markers`) | 237.217417 – 239.221217 (2.004 s) | 237.220265 – 239.223781 (2.004 s) | 2.001 s | **overlapped** (b2 began 0.003 s after b1) |

Receipt times of the paired start/end lines, as separate figures (the receipt gap is the end line's arrival minus the start line's arrival):

| Harness, call | Start line (receipt) | End line (receipt) | Receipt gap | Marker interval | Difference |
|---|---|---|---|---|---|
| Claude, call 1 | `…-b/stdout.jsonl:L2` (244.275990) | `:L4` (246.355353) | 2.079 s | 2.004 s | +0.075 s |
| Claude, call 2 | `:L3` (244.307037) | `:L5` (248.383737) | 4.077 s | 2.005 s | +2.072 s |
| Codex, call 1 | `codex-0.162.0-b/stdout.jsonl:L8` (274.757308) | `:L9` (276.609793) | 1.852 s | 2.005 s | −0.152 s |
| Codex, call 2 | `:L10` (276.777781) | `:L11` (278.634757) | 1.857 s | 2.004 s | −0.147 s |
| Pi, call 1 | `pi-1.1.0-b/stdout.jsonl:L16` (237.200726) | `:L20` (239.223846) | 2.023 s | 2.004 s | +0.019 s |
| Pi, call 2 | `:L17` (237.201863) | `:L21` (239.224489) | 2.023 s | 2.004 s | +0.019 s |

Receipt-interval reading, per capture: in Claude Code both start events arrived (`:L2`, `:L3`) before either command ran, and the second call's start-to-end receipt gap (4.077 s) is twice its execution time because the call waited for the first; Claude's start events therefore mark the model's request, not the beginning of execution, and the events alone would show two calls open at once while the markers show them in sequence. In Codex the second `item.started` (`:L10`) arrived after the first `item.completed` (`:L9`): the events agree with the markers on sequential execution. In Pi both `tool_execution_start` lines arrived within 1.2 ms of each other and both ends within 0.7 ms: the events agree with the markers on overlap. The pairing of the labels b1/b2 with the call order rests on the command text in the events (`args.command` / `input.command` / `command`).

## Case (a) and case (c): one call

Execution interval from the marker (a) or from the stub MCP log (c); receipt gap of the paired start and end lines; difference = receipt gap − execution interval.

| Harness | Case | Execution interval | Start line (receipt vs execution start) | End line (receipt vs execution end) | Receipt gap | Difference |
|---|---|---|---|---|---|---|
| Claude Code | a | 240.817842 – 242.822399 (2.005 s) | `claude-2.1.295-a/stdout.jsonl:L2` (−0.065 s) | `:L3` (+0.013 s) | 2.082 s | +0.078 s |
| Claude Code | c | 255.089001 – 257.089395 (2.000 s; `claude-2.1.295-c/mcp-calls.jsonl`) | `claude-2.1.295-c/stdout.jsonl:L2` (−0.010 s) | `:L3` (+0.010 s) | 2.021 s | +0.020 s |
| Codex | a | 265.516367 – 267.521373 (2.005 s) | `codex-0.162.0-a/stdout.jsonl:L8` (+0.162 s) | `:L9` (+0.006 s) | 1.848 s | −0.157 s |
| Codex | c | 286.407256 – 288.407655 (2.000 s; `codex-0.162.0-c/mcp-calls.jsonl`) | `codex-0.162.0-c/stdout.jsonl:L8` (−0.004 s) | `:L9` (+0.004 s) | 2.009 s | +0.009 s |
| Pi | a | 234.182650 – 236.188076 (2.005 s) | `pi-1.1.0-a/stdout.jsonl:L13` (−0.015 s) | `:L15` (+0.005 s) | 2.025 s | +0.020 s |
| Pi | c | not captured (Pi has no case c here) | — | — | not available | not available |

In these captures line arrival approximated the execution interval to within 0.02 s (Pi, Claude Code case c, Codex case c), to within 0.08 s (Claude Code case a: the start event arrives before the process was started, by 65 ms), and Codex's `command_execution` start line arrived 0.15–0.16 s *after* the command had begun (its receipt gap is shorter than the execution by about 0.15 s, in a, b and a-rollout). The cause of Codex's delay was not established. These are single runs per case on an idle machine with a stub provider that answers within milliseconds; they do not establish how arrival behaves under load, with a slow provider or with output larger than the pipe buffer. No capture showed events arriving only afterwards or together: in every one the end line arrived within 0.021 s of the end marker.

## Codex rollout (non-ephemeral run)

`codex-0.162.0-a-rollout` is case a without `--ephemeral`; it wrote one rollout file, `codex-0.162.0-a-rollout/rollout/rollout-2026-10-09T19-41-28-01a121f8-62e0-7a20-980e-10b41bee0e0e.jsonl`.

- Every record carries a millisecond `timestamp`.
- Tool records: `response_item` `custom_tool_call` (the model's `exec` call, name and JavaScript input; `:L9`, timestamp 18:41:35.170Z, 0.18 s before the command began), `event_msg` `item_completed` with item type `CommandExecution` (`:L11`, timestamp 18:41:37.358Z, 2.3 ms after the end marker, with the command as argument vector, `exit_code` and `duration` 1.857823208 s), `response_item` `custom_tool_call_output` (`:L12`). **There is no record of the start of the command**; its start is not stated. The rollout's `duration` (1.858 s) is 0.147 s shorter than the marker interval (2.005 s) and equals the stdout receipt gap (1.859 s); what it measures was not established.
- Child attempts launched by CQ pass `--ephemeral` (`host/src/main/scala/cq/host/HarnessAdapter.scala:196`) and so have no rollout.

## What CQ stores today (read, not changed)

- **Receipt time of native output lines: not stored.** The guardian (`host/native/guardian.c`) reads the child's pipes in chunks and appends them to the `stdout` and `stderr` files (`drain`, `:142-158`); its only clock is `CLOCK_MONOTONIC`, used for deadlines (`now_ms`, `:34-37`). The driver reads the guardian's own lifecycle protocol, not the child's lines (`host/src/main/scala/cq/host/GuardianDriver.scala:152`, `drain(child.getInputStream, true)`). Collection reads the finished file line by line without times (`host/src/main/scala/cq/host/HarnessUsage.scala:29-60`), with one `collectedAt` for the whole collection (`server/src/main/scala/cq/server/AttemptSettlement.scala:149`). The only time observable per stream is the modification time of the files, read for `quietMillis` on request (`server/src/main/scala/cq/server/DispatchController.scala:159-163`; `docs/design/process-guardian.md`, the `quietMillis` paragraph).
- **End time of a settled attempt: persisted.** `AttemptOutcome.finishedAt` (`models/cq-api.baboon:256`) is set from the settled job's `updatedAt` (`server/src/main/scala/cq/server/AttemptSettlement.scala:169-170`; `updatedAt` is the supervisor's `clock.millis()` at the last record change, `host/src/main/scala/cq/host/JobSupervisor.scala:47`), stored in `cq_usage_outcomes.body` (`server/src/main/resources/db/001-ledgers.sql:201-210`, written by `PostgresUsageRepository.putOutcome`, `server/src/main/scala/cq/server/PostgresUsageRepository.scala:176`) and summed with the attempt's `startedAt` into `wallMillis` (`core/src/main/scala/cq/core/UsageService.scala:258`). Host spans have their own `started_at`/`finished_at` columns (`001-ledgers.sql:214-222`, `models/cq-api.baboon:297`).
- **The `cq_host` tool endpoint** is served by `LocalControl` (`server/src/main/scala/cq/server/LocalControl.scala`): the MCP route `POST /mcp` (`:108`), `rpc` handling `tools/call` (`:96`), `call` running `dispatch` and `workspace` (`:53` onward); its server name is `cq_host` (`:92`), started as `LocalControlServer` (`:130` onward). A call's arrival and its reply are both inside that one request handler, so both are observable there; **no code there records a time or a duration** (no clock is used in the file). Calls to the domain tools (`cq`) are not served by this class. Neither `cq` nor `cq_host` calls are visible in the harnesses' output as anything but the harness's own tool events above.

## Real Claude Code attempt, artifact 76893f08-da3a-3dec-a5a2-2487155a49ce

Not counted. The artifact is a manifest (355 bytes) of four base64 parts (a79eb0ef-1a95-3ad9-906d-0fbaaf632d11, 11fa7b86-ab0d-34d5-b2ed-0ec1a8f56842, c34456d7-e8be-3308-89ee-4fdeaa1d789b, 19a47a6e-029f-3ff2-8f64-bb533bce9e6d), 452,590 bytes of decoded stream. The worker could read the manifest through the CQ read tool, but that tool returns text pages to the model only; the worker's environment has no file or command path to the artifact store, and decoding about 600 KB of base64 by hand to count blocks would not be a method. The numbers of tool_use blocks, tool results and of tool calls in one assistant message are therefore not established here. A later task with access to the artifact bytes can count them with `figures.py`' pairing rules (a `tool_use` block named other than `StructuredOutput` per call).

## Findings on the present collector

`ToolTimingUsageLocal` (`server/src/test/scala/cq/server/ToolTimingUsageTest.scala`) collects each of the nine transcripts:

- `HarnessUsage.collect` **refuses** every one under the version that produced it: `Unverified harness usage format version` (`HarnessUsage.scala:31`; accepted versions are listed at `:97-100`: Claude 2.1.280/2.1.285, Codex 0.156.1/0.159.2/0.160.0, Pi 0.87.1/0.99.1/1.0.0). The installed Claude Code 2.1.295, Codex 0.162.0 and Pi 1.1.0 are not accepted.
- Read as the newest accepted version of its harness, each transcript yields a terminal result, and token counts equal to the sums the stub sent (input 300, output 17, cache read 30): Claude Code and Pi without native failure.
- **Codex transcripts yield `nativeFailure = true`** with the gap "Codex reported a failed turn or stream error", because of the `error` events the stub's refused WebSocket upgrade caused (`codex-0.162.0-a/stdout.jsonl:L3-L7`). With a real provider that accepts the upgrade these events would be absent; this capture does not show how the collector treats a real reconnect that ends in success. It matters because the host admits a result only with `terminalSeen && !nativeFailure` (`server/src/main/scala/cq/server/ChildRunner.scala:131`, Memory 26). Reported, not fixed.
- Pi 1.1.0 adds a `role: "system"` message at the start of the stream (`pi-1.1.0-a/stdout.jsonl:L4`); the collector's output is unaffected.

## Not verified

- **No real model**: provider replies, token counts, identifiers, latencies (milliseconds) and the model name are invented; behaviour with real streaming latency, long outputs, large tool results, permission prompts or tool failures is unknown for every harness.
- **Claude Code 2.1.295**: one run per case. Concurrency of read-only tools (e.g. `Read`, `Grep`) was not captured; only `Bash` pairs. Whether Claude Code could run two `Bash` calls concurrently in another configuration (e.g. background) is not established. The meaning of `timestamp` is inferred from comparison with markers, not from source. Event order or arrival under a heavy output stream is not established. Real-attempt tool-call counts: not available (artifact above). Claude Code versions other than 2.1.295 were not captured (the retained 2.1.285 fixtures hold no tool call and no `timestamp`).
- **Codex 0.162.0**: case b ran two separate `exec` calls; one `exec` call that runs two commands with `Promise.all` was not captured, so Codex's concurrency inside one JavaScript cell is not established. Codex's use of `shell` (without code mode) was not captured. The cause of the 0.15 s delay of `item.started` and the meaning of the rollout `duration` are not established. The WebSocket transport (the default when the provider accepts it) was not exercised. Rollout: only the non-ephemeral run; child attempts have none.
- **Pi 1.1.0**: case c not captured (the task asks for it only for Claude Code and Codex); the CQ bridge extension (`pi-bridge.mjs`) and its MCP tool calls were not exercised. The definition of `durationMs` and of the message `timestamp` was not read from Pi's source. Pi 1.0.0 and 0.99.1 were not captured with tool calls.
- **Buffering**: stdout was a pipe read directly by the capture script. Whether the guardian's file copy or CQ's reading changes the arrival pattern was not tested; the captures show arrival at the reader of a pipe, which is not CQ's path.
- **Collector**: the behaviour on a real Codex reconnect, and on versions not in the accepted list, is described only for these transcripts. The tool-call information in these transcripts is not read by any collector.
- **Real attempt artifact** 76893f08: not counted (see above).
