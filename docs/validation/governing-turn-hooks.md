# Do the UserPromptSubmit and Stop hooks bound a governing turn of Claude Code?

Task 91 (Part of Idea 35; operator answer to Question 90; follows Research 23). Evidence only: no production code changed.

## Method

- **Harness**: Claude Code **2.1.295**, the pinned installed package (`claude --version` prints `2.1.295 (Claude Code)`; executable
  `/nix/store/56vld046mr6h92aik233d13wagl9a0ld-claude-code-2.1.295/libexec/claude-code/claude`).
- **Provider**: `server/src/test/resources/governing-turns/stub.py`, a local imitation of the Anthropic Messages API. **No real model took
  part, no login was read, and every reply is invented.** The harness, its hook invocations and its tools (a real `sleep 2`, a real
  background shell command, a real subagent) are real. The process ran in a private empty `HOME` and `CLAUDE_CONFIG_DIR` with a wrong key.
- **Hooks**: the 33 events this version lists (`PreToolUse, PostToolUse, PostToolUseFailure, PostToolBatch, Notification,
  UserPromptSubmit, UserPromptExpansion, SessionStart, SessionEnd, Stop, StopFailure, SubagentStart, SubagentStop, PreCompact,
  PostCompact, PreModelSwitch, PostModelSwitch, PermissionRequest, PermissionDenied, Setup, TeammateIdle, TaskCreated, TaskCompleted,
  Elicitation, ElicitationResult, ConfigChange, WorktreeCreate, WorktreeRemove, InstructionsLoaded, CwdChanged, FileChanged,
  DirectoryAdded, MessageDisplay`; the array is in the executable) all run `hook.py`, which logs its unchanged stdin and a time. CQ's own
  hook program is not used. Events that fired: `SessionStart, UserPromptSubmit, PreToolUse, PostToolUse, PostToolBatch, MessageDisplay,
  SubagentStart, SubagentStop, Stop, SessionEnd`; the others did not fire in any case (no `Notification`, no `StopFailure`).
- **Clock**: one clock for hooks and stub, `time.time() * 1000` (CLOCK_REALTIME of the one machine), epoch milliseconds. Times below
  are milliseconds after the case's first `UserPromptSubmit` (written `+n`) unless absolute.
- **Files**: `server/src/test/resources/governing-turns/<mode>-<case>/` (see its `README.md`). `hooks.jsonl:N` and `stub.jsonl:N` are
  line numbers; `#k` is the stub's request sequence number.
- **Modes**: *print* is `claude -p --output-format stream-json --verbose` (cases d and f with `--input-format stream-json`);
  *interactive* is the TUI under a pseudo-terminal (Python `pty`), prompts typed into the input box, left with `/exit`.
- **Repeat in a real logged-in session**, one command (it starts no stub, keeps the caller's environment and login, and spends real
  model usage):
  `python3 server/src/test/resources/governing-turns/capture.py --real --out /tmp/governing-turns-real`
  The prompts carry `[stub:...]` markers that a real model reads as text. In real mode there is no `stub.jsonl`: the provider's request
  times are then not recorded, and only the hook times and the transcript are.

## Results

"Interval" is UserPromptSubmit time to the last Stop time before the next UserPromptSubmit. "Requests inside" counts the provider
requests (stub lines `arrive` and `done`) that arrive after the UserPromptSubmit and are complete at the last Stop.

### (a) One prompt, one final answer

| Mode | Ordered events (hooks.jsonl line) | Requests | Inside the interval |
|---|---|---|---|
| print | SessionStart 1 (-144), UserPromptSubmit 2 (+0), MessageDisplay 3 (+304), Stop 4 (+339), SessionEnd 5 (+375) | #1 arrives +80, done +281 (stub.jsonl:1-2) | 1 of 1; done 58.5 ms before Stop |
| interactive | SessionStart 1 (-3564), UserPromptSubmit 2, MessageDisplay 3 (+341), Stop 4 (+343), SessionEnd 5 (+2953, `reason: prompt_input_exit`) | #1, #2 arrive +99/+100, done +302 (stub.jsonl:1-4) | 2 of 2; done 41 ms before Stop |

In interactive mode each prompt also sends a request to the small model (`claude-haiku-5-5`, text prefixed `<session>`, no tools) beside
the main one; both lie inside.

### (b) A tool call that sleeps 2 s, then a final answer

Print (`print-b`): SessionStart 1, UserPromptSubmit 2 (+0), PreToolUse 3 (+88), PostToolUse 4 (+2160), PostToolBatch 5 (+2193),
MessageDisplay 6 (+2439), **Stop 7 (+2471)**, SessionEnd 8. Requests #1 +51..+52 (the tool call), #2 +2213..+2414 (the final
answer) (stub.jsonl:1-4): 2 of 2 inside. **Stop fired once**, at +2471, 57 ms after the final answer was complete, and not between the two
responses (nothing between h2 and h7 is a Stop). Interactive (`interactive-b`): one Stop (line 7, +2845), 3 of 3 requests inside; the
tool ran +145..+2305.

### (c) A Stop hook that blocks once

Print (`print-c`): UserPromptSubmit 2 (+0); **Stop 4 (+305, `stop_hook_active: false`)** answered
`{"decision":"block","reason":"Capture hook: continue once more and answer again."}`; the session read the reason as a new message
(request #2, prompt text begins `Stop hook feedback:`, arrives +323, done +524); **Stop 6 (+590, `stop_hook_active: true`)**; SessionEnd 7.
The interval ends at the last Stop, +590: 2 of 2 requests inside it, but request #2 arrives 18 ms *after* the first Stop, so the first
Stop does not end the turn. Interactive (`interactive-c`): Stop 4 (+306, false), Stop 6 (+566, true); 3 of 3 requests inside (#3 arrives
+332, after the first Stop). The first Stop (`stop_hook_active: false`) is not the end of the turn; the last one is.

### (d) Two prompts in one session

| Mode | Turn 1 | Turn 2 | Relation |
|---|---|---|---|
| print (`--input-format stream-json`) | UPS 2 +0, Stop 4 +305 | UPS 5 +345, Stop 7 +616 | **Disjoint**; the second UserPromptSubmit follows the first Stop by 40.4 ms |
| interactive | UPS 2 +0, Stop 4 +345 | UPS 5 +2379, Stop 7 +2628 | **Disjoint**; 2034 ms between Stop and next UserPromptSubmit |

Each turn holds its own requests (print: #1 / #2; interactive: #1,#2 / #3,#4); 1 of 1 and 1 of 1 inside (print), 2 of 2 and 2 of 2
(interactive). One session id throughout.

### (e) The process is ended while the stub holds a reply back

| Case | Signal | Hooks after UserPromptSubmit | Exit |
|---|---|---|---|
| `print-e` | SIGTERM at 1791581365972.0, 1.07 s after the held request arrived (stub.jsonl:1, no `done`) | **SessionEnd** (hooks.jsonl:3) at 1791581365999.6, 27.5 ms after the signal, `reason: "other"`; **no Stop** | 143 |
| `print-ei` | SIGINT at 1791581369655.8 | **SessionEnd** (hooks.jsonl:3) at 1791581369701.7, 45.9 ms after, `reason: "other"`; **no Stop** | 0 |
| `interactive-e` | Escape (the interrupt key) at 1791581438371.2, +1147 after UserPromptSubmit | **none**: the transcript shows the spinner stopping 77 ms later and the prompt returned to the input box (`transcript.times`, `transcript.txt`); no Stop. SessionEnd (hooks.jsonl:3, +6162) is only the later `/exit` | killed by `/exit` |

So **a SIGTERM or SIGINT turn end is marked by SessionEnd, not by Stop**, and an interrupt of an interactive turn marks nothing: the
session lives on, and no hook shows the interrupted turn ended. SIGINT or SIGTERM to an interactive (pseudo-terminal) process was not run.
In `interactive-e` the held request (stub.jsonl:1-2) has no `done` line because the stub was killed while it still held it; whether the
client dropped the connection at the interrupt is therefore not shown.

### (f) Idle session, a background command exits

The command was `sleep 6; echo bg-done` run with `run_in_background`.

| Mode | First turn | Woken turn | Notes |
|---|---|---|---|
| print (`--input-format stream-json`, input held open) | UPS 2 +0, Stop 7 +497 | **UserPromptSubmit 8 (+6330)**, MessageDisplay 9, Stop 10 (+6720); request #3 +6435..+6653 | 5833 ms after the first Stop |
| interactive | UPS 2 +0, Stop 7 +599 | **UserPromptSubmit 8 (+6358)**, Stop 9 (+6617), MessageDisplay 10; request #4 +6386..+6587 | 5759 ms after the first Stop |

The hook that marks the start of the woken turn is **UserPromptSubmit**; its `prompt` field is a `<task-notification>` block
(`<status>completed</status>`, `<summary>Background command "background sleep" completed (exit code 0)</summary>`;
`print-f/hooks.jsonl:8`), and its `prompt_id` differs from the first turn's. The first Stop's input names the running command in
`background_tasks` (`[{"id":"bxdzrd5oj","type":"shell","status":"running",...}]`, `print-f/hooks.jsonl:7`), and the second Stop shows `background_tasks: []`.
This agrees with `HookDialect.woken = true` for Claude (`host/src/main/scala/cq/host/DriverHook.scala:218`; Codex `false`, line 225). Both
intervals are disjoint and each bounds its own requests (print 2 of 2 and 1 of 1; interactive 3 of 3 and 1 of 1). Nothing but the
`prompt` text separates a woken turn from an operator's prompt.

### (g) A prompt whose reply starts a subagent

The tool is named `Agent` in this version (the stub picks `Task` or `Agent` from the request's tool list). It returned at once and
the subagent ran as a background task.

| Mode | Parent turn | Subagent | Wake |
|---|---|---|---|
| print | UPS 2 +0, Stop 9 +497; requests #1 +55, #2 (subagent) +154..+355, #3 +194..+395: 3 of 3 inside | SubagentStart 5 (+130), SubagentStop 7 (+384): **inside** the interval | UserPromptSubmit 10 (+531, `<task-notification>` for the agent), Stop 12 (+809); request #4 +554..+755 |
| interactive | UPS 2 +0, Stop 8 +715; requests #1..#4 (one is the subagent's #3, +346..+548): 4 of 4 inside | SubagentStart 5 (+267), SubagentStop 7 (+603): **inside** | UserPromptSubmit 10 (+805), Stop 11 (+1065); request #5 +832..+1034 |

The subagent's own Stop-like event is **SubagentStop** (with `agent_id`, `agent_type`, `stop_hook_active`); it did not fire a `Stop`. Its provider
request fell inside the parent's interval *because the subagent finished first*. Its completion notification started a further turn
(UserPromptSubmit with `<task-notification>`) 34 ms (print) / 90 ms (interactive) after the parent's Stop. A subagent that outlives
the parent's Stop was not run (the stub answers at once); it would be reported by the same notification turn, which is the case (f)
shape, but that is **not established** for a long-running subagent.

## Interactive mode summary

Cases (a), (d) and (f) were captured interactively with the same shape as print mode: one Stop per turn, intervals disjoint, a
background exit woken by UserPromptSubmit. Differences: two requests per prompt (the small-model side request), a 3.5 s
SessionStart-to-first-prompt delay (the capture's input settling time, not a harness property), `SessionEnd.reason` is
`prompt_input_exit`, and in (f) and (g) `MessageDisplay` and `Stop` can swap order (hooks run concurrently; their times are within 2 ms).
Nothing here establishes interactive behaviour with a **real** login (permission prompts, a `Notification` hook, auto mode with a real
classifier, compaction), which the repeat command above addresses.

## What CQ's own code does (read, not changed)

- **Where the hooks are installed, and when.** `cq configure claude` (`server/src/main/scala/cq/server/Cli.scala`, the `configure`
  branch, `Cli.scala:104-116`) calls `AttachedAssets.write` -> `plan`, whose Claude branch merges the CQ hooks into
  `.claude/settings.local.json` (`server/src/main/scala/cq/server/AttachedAssets.scala:93`, via `DriverAssets.hooks`,
  `host/src/main/scala/cq/host/DriverAssets.scala:74`; events `UserPromptSubmit` and `Stop` only, `DriverAssets.scala:19`; the command is
  `<cq> hook claude <Event>`, `DriverAssets.scala:62`, run by `case List("hook", harness, event)`, `Cli.scala:117`). The condition is that
  `cq configure claude` was run for the project and Claude Code was launched with the project's settings (the command's precondition text,
  `DriverAssets.scala:42`: `--setting-sources project,local`). Installation does not depend on a drive, and **neither does the Stop
  hook's action**: `DriverHook.stop` (`DriverHook.scala:169-188`) asks the driver for the session's status and then, for a session the
  hook finds an owned host for (`sessions.owned(...)`, `DriverHook.scala:185`) or that is bound, runs `awaited` (`:155-166`), which can
  block the stop to announce what a person settled and to tell the session to start `cq wait`. `DriverHookTest.scala:587-640` ("tell a
  session that never drove what a person settled") asserts exactly this for a session with no drive: its Stop is answered
  `decision: block` with the announcement. A drive only adds the driver's own continuation directives. A UserPromptSubmit whose prompt is
  not a drive or park command answers nothing, with or without a drive (`DriverHookTest.scala:789`). So Research 23's "installed for
  driver use only" is correct about *what installs the hooks* (the `cq configure` assets that exist for the driver; no other path
  installs them) but not about *when they act*: the Stop hook acts on an attached session that never drove, and on each turn end it
  would be one of the hooks that a turn recorder shares with CQ. A recorder that uses the same events must not replace these two hook
  commands in `settings.local.json`; it needs to be added beside them.
- **What `DriverHook.run` requires.** A single JSON object on stdin of at most `MaxInputBytes` (`DriverHook.scala:31`, `:192`);
  `hook_event_name` present (`DriverHook.scala:36`) and equal to the event the command was installed for (`:40`); `session_id` non-empty
  (`DriverEntry.identify`, `host/src/main/scala/cq/host/DriverEntry.scala:40`); for UserPromptSubmit a string `prompt` (`:42`). The Stop
  branch reads none of `stop_hook_active`, `background_tasks`, `last_assistant_message` (they are not named in `DriverHook.scala`).
  **Every captured input carries them**: all 113 invocations have `hook_event_name` equal to the logged event and a `session_id`
  (asserted by `GoverningTurnHooksLocal`); every UserPromptSubmit has a string `prompt` (the woken turn's is the task notification text).
- **Can the hook program report a time to the attached host?** Not today. The hook's only outward calls are (1) the server API through
  `HttpServerApi` with the operator credential, carrying only `DriverRequest.Control(key, origin, action)` with `Start`, `Park`,
  `Continue` or `Status` (`Cli.scala:121`, `models/cq-api.baboon:430-436`, `DriverEntry.scala:19-33`): no time, no turn marker; and (2) files
  of the checkout's `hosts/` directory through `AttachedSessions` (`SessionUnits.scala:355-415`: `.json`, `.live`, `.told`, `.asked`),
  which only a Stop reads and writes (`Cli.scala:123`). There is no socket or request to the attached host process. A time
  could be written by the hook as a file there, or sent as a new `DriverControl` action, but neither exists.
- **Observation, not a fix.** The CQ Stop hook does not read `stop_hook_active`. CQ blocks a Stop with a reason (`DriverHook.scala:86`); as
  captured in (c) the session then stops again with `stop_hook_active: true`, and the hook is called again with the same input
  shape. Whether CQ's driver behaves correctly on that second call is decided by `DriverEntry.continuation`, which was not read here.

## Conclusion

**Does UserPromptSubmit to the last Stop bound a governing turn of Claude Code 2.1.295?** Yes, for an ordinary turn: in every captured
case a turn that completed (a, b, c, d, f, g, in both modes where run) had every one of its provider requests, the main model's, the
small model's side requests, and the subagent's in (g), arrive after its UserPromptSubmit and complete before its last Stop, and the
intervals of consecutive turns did not overlap. It does **not** hold in these cases, and the turn-recording Task must handle each:

1. **Interruption.** Neither SIGTERM, SIGINT (print) nor the interactive Escape produced a Stop. A signal produced SessionEnd; an
   Escape produced no hook at all. A recorder that waits for Stop leaves the turn open. It must close an open turn at SessionEnd, at
   the next UserPromptSubmit of the same session id (a new prompt proves the previous turn ended; the Escape case needs this), and at
   the loss of the harness process or host, and mark that end as *interrupted*, not completed. The Escape end time is **not**
   observable from hooks; the recorded end is then an upper bound.
2. **A blocked stop.** A Stop answered `block` is followed by more provider requests and by a Stop with `stop_hook_active: true`. The
   recorder must not close the turn at a Stop it knows it answered with `block` (CQ's own Stop hook knows), or must treat a Stop
   followed by a continuation without a UserPromptSubmit as the same turn; the turn ends at the last Stop.
3. **A woken turn.** A background command that exits while the session is idle starts a new turn marked by UserPromptSubmit whose
   `prompt` is a `<task-notification>` block. It has its own interval and may be recorded as a turn of the governor; it can be told
   from an operator prompt only by that prompt text, which is documented nowhere in this evidence as stable.
4. **A subagent.** SubagentStart and SubagentStop do not bound the parent turn and no Stop fires for the subagent. In the captured run
   the subagent finished within the parent's interval; if a subagent outlives the parent's Stop, its work lies between turns and ends
   with a notification-woken turn. The recorder must not take SubagentStop as the end of a governing turn.

Also: the hook program has no path to report a time to the attached host today (above), so recording turns needs a new route (a
`DriverControl` action or a host-visible file). The hooks CQ installs are the same two events (`cq configure claude`); SessionEnd is not
among them and would have to be added for the interruption case 1.

## Not verified

- **No real model took part and every provider reply was invented**, so every statement concerns the harness's hook behaviour under
  scripted replies, not model behaviour (a real model's streams, thinking blocks, retries, and long tool runs).
- **No real login**: permission prompts, auto-mode classifier with a real model, `Notification`, `StopFailure` (rate limits), compaction
  hooks, and the 2.1.295 behaviour when a real provider returns an error were not exercised.
- **Not established**: a subagent that outlives the parent's Stop; a synchronous subagent (the subagent ran as a background task
  here); SIGTERM or SIGINT to an interactive process; whether the interactive Escape closes the provider connection; case (e) after
  a `kill -9` (no SessionEnd would be expected, not observed); a `Stop` hook with `stop_hook_active` blocked repeatedly; a Stop
  hook that fails or times out.
- **Not run**: `capture.py --real` (no login in the worker's run). No Codex or Pi session was examined.
- **Not read**: `DriverEntry.continuation` and the server's driver state machine; how the host would store turn intervals.
- **Fail-before evidence does not apply**: no production code changed.

## Appendix: every captured case, in order

Generated by `server/src/test/resources/governing-turns/appendix.py` from the retained logs; times are milliseconds after the case's
first UserPromptSubmit; `hooks.jsonl:N` and `stub.jsonl:N` name the fixture line (the directory is
`server/src/test/resources/governing-turns/<case>/`). A request row gives the line of its arrival and, in `detail`, the time and line of its
completion. Interactive rows include the small-model side request (`<session>` prompt). The last-Stop and request-inside figures of the
sections above come from `intervals.py` over these files:

```
## print-a
  turn 1: UserPromptSubmit line 2 t=1791581352313.5; Stops [(4, 1791581352653.0, False)]; duration 339.5; requests 1 (seq [1]) inside 1; arrive-after-UPS [79.5]; done-before-lastStop [58.5]
## interactive-a
  turn 1: UserPromptSubmit line 2 t=1791581398349.2; Stops [(4, 1791581398692.1, False)]; duration 342.9; requests 2 (seq [1, 2]) inside 2; arrive-after-UPS [99.1, 99.7]; done-before-lastStop [41.4, 41.3]
## print-b
  turn 1: UserPromptSubmit line 2 t=1791581354787.2; Stops [(7, 1791581357258.2, False)]; duration 2471.0; requests 2 (seq [1, 2]) inside 2; arrive-after-UPS [50.8, 2212.6]; done-before-lastStop [2419.2, 56.9]
## interactive-b
  turn 1: UserPromptSubmit line 2 t=1791581411061.1; Stops [(7, 1791581413906.3, False)]; duration 2845.2; requests 3 (seq [1, 2, 3]) inside 3; arrive-after-UPS [73.8, 74.7, 2493.3]; done-before-lastStop [2769.2, 2769.1, 146.4]
## print-c
  turn 1: UserPromptSubmit line 2 t=1791581359419.8; Stops [(4, 1791581359724.9, False), (6, 1791581360009.4, True)]; duration 589.6; requests 2 (seq [1, 2]) inside 2; arrive-after-UPS [46.0, 322.8]; done-before-lastStop [342.1, 65.7]
## interactive-c
  turn 1: UserPromptSubmit line 2 t=1791581420857.7; Stops [(4, 1791581421163.4, False), (6, 1791581421423.6, True)]; duration 565.9; requests 3 (seq [1, 2, 3]) inside 3; arrive-after-UPS [63.1, 63.7, 332.0]; done-before-lastStop [301.0, 301.0, 32.5]
## print-d
  turn 1: UserPromptSubmit line 2 t=1791581362083.7; Stops [(4, 1791581362388.5, False)]; duration 304.8; requests 1 (seq [1]) inside 1; arrive-after-UPS [46.0]; done-before-lastStop [57.6]
  turn 2: UserPromptSubmit line 5 t=1791581362428.9; Stops [(7, 1791581362699.9, False)]; duration 271.0; requests 1 (seq [2]) inside 1; arrive-after-UPS [15.0]; done-before-lastStop [54.8]
## interactive-d
  turn 1: UserPromptSubmit line 2 t=1791581428080.3; Stops [(4, 1791581428425.1, False)]; duration 344.8; requests 2 (seq [1, 2]) inside 2; arrive-after-UPS [88.5, 89.4]; done-before-lastStop [53.1, 52.5]
  turn 2: UserPromptSubmit line 5 t=1791581430458.8; Stops [(7, 1791581430708.6, False)]; duration 249.8; requests 2 (seq [3, 4]) inside 2; arrive-after-UPS [19.5, 20.2]; done-before-lastStop [29.5, 28.5]
## print-f
  turn 1: UserPromptSubmit line 2 t=1791581371776.7; Stops [(7, 1791581372274.0, False)]; duration 497.3; requests 2 (seq [1, 2]) inside 2; arrive-after-UPS [50.7, 234.6]; done-before-lastStop [445.6, 61.5]
  turn 2: UserPromptSubmit line 8 t=1791581378107.0; Stops [(10, 1791581378496.4, False)]; duration 389.5; requests 1 (seq [3]) inside 1; arrive-after-UPS [104.5]; done-before-lastStop [67.2]
## interactive-f
  turn 1: UserPromptSubmit line 2 t=1791581447489.5; Stops [(7, 1791581448088.5, False)]; duration 599.0; requests 3 (seq [1, 2, 3]) inside 3; arrive-after-UPS [73.2, 73.8, 360.1]; done-before-lastStop [524.2, 524.1, 37.5]
  turn 2: UserPromptSubmit line 8 t=1791581453847.2; Stops [(9, 1791581454106.9, False)]; duration 259.6; requests 1 (seq [4]) inside 1; arrive-after-UPS [28.2]; done-before-lastStop [30.2]
## print-g
  turn 1: UserPromptSubmit line 2 t=1791581380980.2; Stops [(9, 1791581381477.0, False)]; duration 496.8; requests 3 (seq [1, 2, 3]) inside 3; arrive-after-UPS [55.0, 154.0, 193.6]; done-before-lastStop [440.7, 141.8, 101.5]
  turn 2: UserPromptSubmit line 10 t=1791581381511.4; Stops [(12, 1791581381789.5, False)]; duration 278.1; requests 1 (seq [4]) inside 1; arrive-after-UPS [22.9]; done-before-lastStop [54.1]
## interactive-g
  turn 1: UserPromptSubmit line 2 t=1791581461408.6; Stops [(8, 1791581462123.5, False)]; duration 714.8; requests 4 (seq [1, 2, 3, 4]) inside 4; arrive-after-UPS [118.6, 120.7, 346.4, 419.5]; done-before-lastStop [592.8, 592.6, 167.1, 92.3]
  turn 2: UserPromptSubmit line 10 t=1791581462213.3; Stops [(11, 1791581462473.2, False)]; duration 259.9; requests 1 (seq [5]) inside 1; arrive-after-UPS [27.5]; done-before-lastStop [31.0]
```

### interactive-a

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3564.4 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word ready. [stub:plain] |
| +99.1 | stub.jsonl:1 | request | #1 plain | arrive; done +301.5 (stub.jsonl:3); prompt='<session> Reply with the single word ready. [stu' |
| +99.7 | stub.jsonl:2 | request | #2 plain | arrive; done +301.6 (stub.jsonl:4); prompt='Reply with the single word ready. [stub:plain]' |
| +341.5 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +342.9 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +2952.8 | hooks.jsonl:5 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-b

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3527.8 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Run the shell command `sleep 2` with the Bash to |
| +73.8 | stub.jsonl:1 | request | #1 sleep | arrive; done +76.0 (stub.jsonl:3); prompt='<session> Run the shell command `sleep 2` with t' |
| +74.7 | stub.jsonl:2 | request | #2 sleep | arrive; done +76.2 (stub.jsonl:4); prompt='Run the shell command `sleep 2` with the Bash to' |
| +145.2 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Bash |
| +2305.4 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Bash |
| +2403.6 | hooks.jsonl:5 | hook | PostToolBatch |  |
| +2493.3 | stub.jsonl:5 | request | #3 tool_result | arrive; done +2698.9 (stub.jsonl:6); prompt='' |
| +2830.8 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +2845.2 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +5633.7 | hooks.jsonl:8 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-c

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3500.0 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word ready. [stub:plain] |
| +63.1 | stub.jsonl:1 | request | #1 plain | arrive; done +264.9 (stub.jsonl:3); prompt='<session> Reply with the single word ready. [stu' |
| +63.7 | stub.jsonl:2 | request | #2 plain | arrive; done +264.9 (stub.jsonl:4); prompt='Reply with the single word ready. [stub:plain]' |
| +302.9 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +305.7 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +332.0 | stub.jsonl:5 | request | #3 plain | arrive; done +533.4 (stub.jsonl:6); prompt='Stop hook feedback: Capture hook: continue once ' |
| +562.6 | hooks.jsonl:5 | hook | MessageDisplay |  |
| +565.9 | hooks.jsonl:6 | hook | Stop | stop_hook_active=True |
| +2859.9 | hooks.jsonl:7 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-d

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3488.3 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word one. [stub:plain] |
| +88.5 | stub.jsonl:1 | request | #1 plain | arrive; done +291.7 (stub.jsonl:3); prompt='<session> Reply with the single word one. [stub:' |
| +89.4 | stub.jsonl:2 | request | #2 plain | arrive; done +292.2 (stub.jsonl:4); prompt='Reply with the single word one. [stub:plain]' |
| +342.1 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +344.8 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +2378.5 | hooks.jsonl:5 | hook | UserPromptSubmit | prompt=Reply with the single word two. [stub:plain] |
| +2398.0 | stub.jsonl:5 | request | #3 plain | arrive; done +2598.8 (stub.jsonl:7); prompt='<session> Reply with the single word two. [stub:' |
| +2398.8 | stub.jsonl:6 | request | #4 plain | arrive; done +2599.8 (stub.jsonl:8); prompt='Reply with the single word two. [stub:plain]' |
| +2628.1 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +2628.3 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +5143.1 | hooks.jsonl:8 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-e

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3468.5 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Think about this for a long while, then reply wi |
| +56.2 | stub.jsonl:1 | request | #1 hold | arrive; no done line (held, never completed); prompt='<session> Think about this for a long while, the' |
| +56.9 | stub.jsonl:2 | request | #2 hold | arrive; no done line (held, never completed); prompt='Think about this for a long while, then reply wi' |
| +6161.7 | hooks.jsonl:3 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-f

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3548.9 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Start the shell command `sleep 6; echo bg-done`  |
| +73.2 | stub.jsonl:1 | request | #1 bg | arrive; done +74.8 (stub.jsonl:3); prompt='<session> Start the shell command `sleep 6; echo' |
| +73.8 | stub.jsonl:2 | request | #2 bg | arrive; done +74.9 (stub.jsonl:4); prompt='Start the shell command `sleep 6; echo bg-done` ' |
| +149.5 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Bash |
| +280.2 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Bash |
| +320.1 | hooks.jsonl:5 | hook | PostToolBatch |  |
| +360.1 | stub.jsonl:5 | request | #3 tool_result | arrive; done +561.5 (stub.jsonl:6); prompt='' |
| +593.5 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +599.0 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +6357.8 | hooks.jsonl:8 | hook | UserPromptSubmit | prompt=<task-notification> <task-id>ba3atkwbp</task-id> |
| +6385.9 | stub.jsonl:7 | request | #4 plain | arrive; done +6587.2 (stub.jsonl:8); prompt='' |
| +6617.4 | hooks.jsonl:9 | hook | Stop | stop_hook_active=False |
| +6617.4 | hooks.jsonl:10 | hook | MessageDisplay |  |
| +9421.7 | hooks.jsonl:11 | hook | SessionEnd | reason=prompt_input_exit |

### interactive-g

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -3616.7 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Start one subagent with the Task tool and report |
| +118.6 | stub.jsonl:1 | request | #1 agent | arrive; done +122.1 (stub.jsonl:3); prompt='<session> Start one subagent with the Task tool ' |
| +120.7 | stub.jsonl:2 | request | #2 agent | arrive; done +122.2 (stub.jsonl:4); prompt='Start one subagent with the Task tool and report' |
| +188.4 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Agent |
| +264.0 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Agent |
| +267.3 | hooks.jsonl:5 | hook | SubagentStart | agent_type=general-purpose |
| +346.4 | stub.jsonl:5 | request | #3 plain | arrive; done +547.7 (stub.jsonl:7); prompt='Reply with one word. [stub:plain]' |
| +375.6 | hooks.jsonl:6 | hook | PostToolBatch |  |
| +419.5 | stub.jsonl:6 | request | #4 tool_result | arrive; done +622.5 (stub.jsonl:8); prompt='' |
| +602.9 | hooks.jsonl:7 | hook | SubagentStop | stop_hook_active=False, agent_type=general-purpose |
| +714.8 | hooks.jsonl:8 | hook | Stop | stop_hook_active=False |
| +716.6 | hooks.jsonl:9 | hook | MessageDisplay |  |
| +804.7 | hooks.jsonl:10 | hook | UserPromptSubmit | prompt=<task-notification> <task-id>afb944745089887a4</ |
| +832.1 | stub.jsonl:9 | request | #5 plain | arrive; done +1033.6 (stub.jsonl:10); prompt='' |
| +1064.6 | hooks.jsonl:11 | hook | Stop | stop_hook_active=False |
| +1064.6 | hooks.jsonl:12 | hook | MessageDisplay |  |
| +3281.9 | hooks.jsonl:13 | hook | SessionEnd | reason=prompt_input_exit |

### print-a

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -143.8 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word ready. [stub:plain] |
| +79.5 | stub.jsonl:1 | request | #1 plain | arrive; done +281.0 (stub.jsonl:2); prompt='Reply with the single word ready. [stub:plain]' |
| +304.5 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +339.5 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +375.1 | hooks.jsonl:5 | hook | SessionEnd | reason=other |

### print-b

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -152.9 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Run the shell command `sleep 2` with the Bash to |
| +50.8 | stub.jsonl:1 | request | #1 sleep | arrive; done +51.7 (stub.jsonl:2); prompt='Run the shell command `sleep 2` with the Bash to' |
| +87.8 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Bash |
| +2160.2 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Bash |
| +2192.5 | hooks.jsonl:5 | hook | PostToolBatch |  |
| +2212.6 | stub.jsonl:3 | request | #2 tool_result | arrive; done +2414.1 (stub.jsonl:4); prompt='' |
| +2438.6 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +2471.0 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +2511.7 | hooks.jsonl:8 | hook | SessionEnd | reason=other |

### print-c

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -234.0 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word ready. [stub:plain] |
| +46.0 | stub.jsonl:1 | request | #1 plain | arrive; done +247.5 (stub.jsonl:2); prompt='Reply with the single word ready. [stub:plain]' |
| +271.1 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +305.0 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +322.8 | stub.jsonl:3 | request | #2 plain | arrive; done +523.9 (stub.jsonl:4); prompt='Stop hook feedback: Capture hook: continue once ' |
| +547.9 | hooks.jsonl:5 | hook | MessageDisplay |  |
| +589.6 | hooks.jsonl:6 | hook | Stop | stop_hook_active=True |
| +624.9 | hooks.jsonl:7 | hook | SessionEnd | reason=other |

### print-d

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -157.3 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Reply with the single word one. [stub:plain] |
| +46.0 | stub.jsonl:1 | request | #1 plain | arrive; done +247.2 (stub.jsonl:2); prompt='Reply with the single word one. [stub:plain]' |
| +271.6 | hooks.jsonl:3 | hook | MessageDisplay |  |
| +304.8 | hooks.jsonl:4 | hook | Stop | stop_hook_active=False |
| +345.2 | hooks.jsonl:5 | hook | UserPromptSubmit | prompt=Reply with the single word two. [stub:plain] |
| +360.2 | stub.jsonl:3 | request | #2 plain | arrive; done +561.4 (stub.jsonl:4); prompt='Reply with the single word two. [stub:plain]' |
| +586.0 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +616.2 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +760.2 | hooks.jsonl:8 | hook | SessionEnd | reason=other |

### print-e

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -146.0 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Think about this for a long while, then reply wi |
| +46.1 | stub.jsonl:1 | request | #1 hold | arrive; no done line (held, never completed); prompt='Think about this for a long while, then reply wi' |
| +1139.5 | hooks.jsonl:3 | hook | SessionEnd | reason=other |

### print-ei

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -211.0 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Think about this for a long while, then reply wi |
| +85.0 | stub.jsonl:1 | request | #1 hold | arrive; no done line (held, never completed); prompt='Think about this for a long while, then reply wi' |
| +1140.1 | hooks.jsonl:3 | hook | SessionEnd | reason=other |

### print-f

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -151.8 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Start the shell command `sleep 6; echo bg-done`  |
| +50.7 | stub.jsonl:1 | request | #1 bg | arrive; done +51.8 (stub.jsonl:2); prompt='Start the shell command `sleep 6; echo bg-done` ' |
| +91.2 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Bash |
| +182.8 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Bash |
| +218.3 | hooks.jsonl:5 | hook | PostToolBatch |  |
| +234.6 | stub.jsonl:3 | request | #2 tool_result | arrive; done +435.8 (stub.jsonl:4); prompt='' |
| +461.4 | hooks.jsonl:6 | hook | MessageDisplay |  |
| +497.3 | hooks.jsonl:7 | hook | Stop | stop_hook_active=False |
| +6330.3 | hooks.jsonl:8 | hook | UserPromptSubmit | prompt=<task-notification> <task-id>bf3a9rx7f</task-id> |
| +6434.8 | stub.jsonl:5 | request | #3 plain | arrive; done +6652.6 (stub.jsonl:6); prompt='' |
| +6685.2 | hooks.jsonl:9 | hook | MessageDisplay |  |
| +6719.7 | hooks.jsonl:10 | hook | Stop | stop_hook_active=False |
| +6879.3 | hooks.jsonl:11 | hook | SessionEnd | reason=other |

### print-g

| ms | source | kind | event | detail |
|---:|---|---|---|---|
| -157.9 | hooks.jsonl:1 | hook | SessionStart | source=startup |
| +0.0 | hooks.jsonl:2 | hook | UserPromptSubmit | prompt=Start one subagent with the Task tool and report |
| +55.0 | stub.jsonl:1 | request | #1 agent | arrive; done +56.1 (stub.jsonl:2); prompt='Start one subagent with the Task tool and report' |
| +90.2 | hooks.jsonl:3 | hook | PreToolUse | tool_name=Agent |
| +130.1 | hooks.jsonl:4 | hook | PostToolUse | tool_name=Agent |
| +130.1 | hooks.jsonl:5 | hook | SubagentStart | agent_type=general-purpose |
| +154.0 | stub.jsonl:3 | request | #2 plain | arrive; done +354.9 (stub.jsonl:5); prompt='Reply with one word. [stub:plain]' |
| +174.9 | hooks.jsonl:6 | hook | PostToolBatch |  |
| +193.6 | stub.jsonl:4 | request | #3 tool_result | arrive; done +395.2 (stub.jsonl:6); prompt='' |
| +383.8 | hooks.jsonl:7 | hook | SubagentStop | stop_hook_active=False, agent_type=general-purpose |
| +451.0 | hooks.jsonl:8 | hook | MessageDisplay |  |
| +496.8 | hooks.jsonl:9 | hook | Stop | stop_hook_active=False |
| +531.2 | hooks.jsonl:10 | hook | UserPromptSubmit | prompt=<task-notification> <task-id>a0597a71ea59d4ff6</ |
| +554.0 | stub.jsonl:7 | request | #4 plain | arrive; done +755.2 (stub.jsonl:8); prompt='' |
| +779.0 | hooks.jsonl:11 | hook | MessageDisplay |  |
| +809.3 | hooks.jsonl:12 | hook | Stop | stop_hook_active=False |
| +843.0 | hooks.jsonl:13 | hook | SessionEnd | reason=other |
