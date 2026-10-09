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
  `DriverAssets.scala:42`: `--setting-sources project,local`). They are installed whether or not a drive is on; they *act* only for a
  drive (with no drive a UserPromptSubmit answers nothing: `server/src/test/scala/cq/server/DriverHookTest.scala:789`). No other path
  installs them. This refines Research 23's "for driver use only".
- **What `DriverHook.run` requires.** A single JSON object on stdin of at most `MaxInputBytes` (`DriverHook.scala:31-33`);
  `hook_event_name` present and equal to the event the command was installed for (`:35-36`, `:40`); `session_id` non-empty
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
