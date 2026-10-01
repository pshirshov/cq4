# Auto-driver core

The driver core is the harness-neutral part of the operator-toggled auto-driver. It decides, per harness session, whether the session should keep advancing a frozen workset, hands the session the exact advance invocation to submit, and confines every ledger write of a driven session to the cycle it belongs to. The shared Claude Code/Codex hook commands and the Pi extension are thin callers; none of them builds a directive or holds driver state. The harness sections below describe those callers; operator documentation is separate work.

The core is `DriverService` (state machine), `DriverBoundary` (write-time admission) and `DriverPolicy` (pure decisions and texts) in `core`, with `DriverRegistry` holding the state. `LedgerMutation` calls the boundary for every ledger write. `DriverEntry`, `DriverArguments` and `DriverSessionClient` in `host` are the callers' typed entry points.

## State and its lifetime

The server holds one record per project, harness and session key: the state (`Binding`, `On` or `Off`), the bound attached session, the stored workset ID if one was used, the frozen targets and through phase, the latest cycle, the number of directives issued and the last stop reason.

The records live in the server process, not in PostgreSQL. They persist across the turns of a harness session, but a server restart turns every driver off: the next continuation query then returns stop with reason `Off` and the session's writes behave as they do without a driver. No ledger schema change accompanies the driver.

A project holds at most 64 records. At capacity, drive-start displaces the least recently touched record that is off or has been silent for eight hours; if every record is live, drive-start fails with `Limit`.

## Session trust

State-changing entry points are keyed by the session key the harness supplies: the hook-stdin `session_id` for Claude Code and Codex, and the extension's own session identity for Pi. The key is validated for shape (1–200 characters of letters, digits, `.`, `_`, `:` or `-`) and is otherwise trusted, not authenticated (Decision 2). A missing or malformed key, an unknown harness identifier and an unknown hook event are rejected with `Invalid` and change no state; no call falls back to a default session.

Two sessions that each supply their own key never change each other's record. A same-user process that supplies another session's key does change that session's record. This is the documented limit of the isolation guarantee and a contract test asserts it.

Authority separates the two surfaces:

- **Control** (`DriverRequest.Control`: `Start`, `Park`, `Continue`, `Status`) requires the operator credential, which only the CQ hook commands and the Pi extension's attached host hold. A governor credential is `Denied`. The declared origin is checked: Pi drives from `Extension`, the other harnesses from hooks; `Start` and `Park` belong to `UserPromptSubmit`, `Continue` to `Stop`, and `Status` to any origin including `StatusLine`.
- **Session** (`DriverRequest.Session`) runs under an attached session's own governor credential. The model-facing `session` tool exposes only `Bind` (gated by the hook-minted token) and `Driver` (read-only status). Neither starts nor parks a driver. Activation, lineage registration and cycle-attributed changes are called by the attached host and by delegated sessions, not offered as tools.

No MCP domain tool carries a driver command.

## Drive-start, binding and park

Drive-start takes the key and either a stored workset ID or inline non-empty targets plus a through phase. It evaluates the workset again and returns the same preview `Command.Workset` `Preview` returns. Empty targets, an unknown item, a missing workset, a cross-project target and an invalid key are rejected and leave the driver off. `DriverArguments` parses the command text `<target IDs> through=<phase>` or `workset=<id>` and rejects unknown item references and unknown phases before the server is called.

The targets and the phase are frozen. Drive-start on a driver that is binding or on fails with `Conflict`; changing targets or phase requires parking and driving again.

A driver turns on only when it is bound to exactly one CQ attached session:

- **Claude Code and Codex:** drive-start leaves the driver `Binding` and mints a single-use bind token valid for ten minutes. The token appears only in that drive-start reply, which the hook returns as context for its own session. `Bind` from an attached session presents the token and binds the caller. An unknown, already used or expired token is `Denied`, and a session already bound to another driver is a `Conflict`.
- **Pi:** the extension's attached host supplies its attached session at drive-start, so the driver is on immediately and no token is minted.

If no session binds, the driver never turns on, and the next continuation query stops it with reason `NotBound`, whose detail names it as a failure.

Park turns the driver off, ends any pending or active cycle (its tokens stop working) and releases the binding. The record keeps its frozen workset and a `Parked` stop reason for the status indicator.

## Continuation query

The query takes the key and returns either a directive (`Continue`) or `Stop`.

| Driver state | Result |
| --- | --- |
| No record, or off | `Stop` with reason `Off`; nothing changes |
| Off after a stop no control reply has carried yet | `Stop` with that stop reason, once |
| Binding | `Stop` with `NotBound`; the driver turns off |
| On, cycle issued but not started | `Stop` with `Failure` (directive not started) |
| On, cycle started and its run still active | `Continue` with a resume directive |
| On, no cycle, or the cycle's run is over | a new cycle decision, below |

A cycle's run is still active while any lineage member registered under it is unsettled: a child attempt, an integration or a combination that has not reached a terminal phase, or a delegated session.

For a new cycle the query first computes the advanceable set from the frozen targets with `WorksetPlanner.evaluate`. This issue-time snapshot is the only input to the readiness decision, and it is stored with the cycle it creates:

- **Continue:** some advanceable item is ready and is not waiting for a person. The query creates a pending cycle holding a single-use start token, exactly the frozen targets as roots, the frozen phase and the snapshot.
- **`UserInputRequired`:** no other item is ready, or the previous cycle changed nothing, and an open Question or a requested Operator Action is itself ready or blocks an advanceable item. The driver never answers a question or infers an approval.
- **`Quiescent`:** nothing is ready, or the snapshot equals the previous cycle's in members, context and readiness, and nothing waits for a person.
- **`LimitReached`:** the drive has issued its 64 directives. Start and resume directives both count.
- **`Failure`:** the set cannot be computed, or an item the previous cycle created is not in the recomputed set.

When the recomputed set differs from the previous cycle's, the reply carries a transcript message naming the added and removed items. Every stop carries its message. A stop turns the driver off and releases the binding.

## Directives, start and resume

A directive is host-generated text the session submits unchanged:

```text
/cq:advance --roots G1,T4 --through work --start-token 3f0c…
$cq-advance --roots G1,T4 --through work --resume-token 91ab…
```

Claude Code and Pi receive `/cq:advance`, Codex `$cq-advance`. The roots are the frozen targets in `(ledger, number)` order and the phase is the frozen one. The advance entry point passes the token as `SessionCommand.Workflow.token`; it is `null` in a session without a driver. The token travels only in that field: the attached host refuses, with `Invalid`, an activation whose `operatorRequirements` text contains its own token, because that text is delivered to Planner and Worker children. The refusal comes before the token is presented to the server, so the session can activate again with the token left out of the text.

The attached host asks the server before every new activation (`DriverSession.Activate`):

- **Start token.** A run starts only if the token is the pending cycle's unused start token, the caller is the bound session of an on driver, and the request is `Advance` with exactly the cycle's roots and phase. The cycle becomes active and records the run. A retry with the same activation ID returns the same result.
- **Resume token.** It returns the cycle's existing run and never creates one. It is valid once, for the active cycle, from the bound session, with the same roots and phase. Each resume directive carries a fresh token.
- **Rejections.** An omitted token, a start token presented as a resume token or the reverse, an unknown or reused token, altered roots or phase, another workflow (begin, review or upstream) and a caller other than the bound session are all rejected. No run starts, and the driver stops with reason `Failure`.

A session whose driver is off, or that has none, activates exactly as before and receives no cycle.

## Bound-session mutation rule

While a driver is on, every ledger mutation from its bound attached session is attributed to that driver's active cycle, whether or not the call names a cycle: direct `change` calls, Reference changes, proposal applications and integration completions. If no cycle is active — before the first directive, between cycles, or before the start directive is accepted — the mutation is rejected and the driver stops with reason `Failure` (untracked mutation). The same holds for an activation without a valid token.

An exact retry of an already committed request returns its acknowledgement without writing and is not checked. `previewWorksetAfter` evaluates a hypothetical change in a transaction that always rolls back and is not a write.

Sessions that are not bound to an on driver are unaffected. A park, a stop or a server restart returns the bound session to that behaviour.

## Write-time boundary

The boundary runs inside the writing transaction, so a rejection leaves the ledger unchanged. It applies to every attributed write:

1. **Before the write:** every existing item the request names must be in the cycle's stored snapshot or have been created by the cycle. A Reference names both endpoints; Produce names the producer; Archive, Terminate and Restore name their members, roots and neighbours.
2. **After the write, before commit:** every item the write actually changed must satisfy the same rule, and every item it created must be selected by roots-bound enumeration of the frozen targets on the resulting ledger state. A plain Create is therefore rejected; a Produce under an in-set producer is admitted.
3. **After the cycle:** the next continuation query checks that every item the cycle created is in the recomputed set.

The snapshot is never recomputed at activation or at write time. An item attached to the targets after the directive was issued is outside that cycle and becomes advanceable in the next one. A descendant created in cycle N is in the snapshot issued for cycle N+1.

Any rejection stops the driver with reason `Failure` and a detail naming the items.

## Cycle lineage

The accepted cycle ID is stamped on the advance run: the server records the run in the cycle, and the attached host writes it into the `WorkflowActivation` it stores. A cycle's lineage lists everything that inherits the ID, each entry with its parent and whether it has settled:

- the server adds the Change request, Claim, Proposal and Integration of every attributed write and of every claim the bound session acquires during the cycle;
- the attached host registers each dispatch Request and child Attempt, each prepared Integration and each Combination when the dispatch tool returns, and settles each when it reaches a terminal phase;
- a session delegated under the cycle is registered as a `Session` member and may register further members, including further sessions, beneath itself.

A session other than the bound one is attributed only when its write carries the cycle ID (`DriverSession.Change`) and the session is an unsettled member of that cycle. A write that names an unknown cycle, an ended cycle or a cycle the caller is not part of is rejected; when the cycle's driver is on it stops with reason `Failure`. A cycle holds at most 1,024 lineage members.

## Status

`DriverStatus` carries the key, state, bound session, frozen workset, the latest cycle with its lineage, the active child count, the directive count, the last stop reason and one host-generated indicator line:

```text
CQ driver binding: G1 through work
CQ driver on: G1 through work; 2 active children
CQ driver off: G1 through work; stopped (quiescent): No item of the advanceable set is ready to advance
```

Every state change and stop also returns a transcript message in its reply.

## Pi extension

The generated Pi extension (`.pi/extensions/cq-host.js`) is Pi's caller of the driver. It keeps only what the last control reply reported and the drive input last accepted, and it builds no directive.

- **Commands.** `/cq:drive <target IDs> through=<phase>` or `/cq:drive workset=<id>` sends its argument text unchanged to `Start`. It posts the host message with the preview in three groups (advanceable, context only, readiness) and, when Pi is idle, asks for the first continuation at once. `/cq:park` sends `Park`. A rejection is posted as an error and changes nothing. Both are extension commands; the four workflow prompt templates are unchanged.
- **Session key.** `ctx.sessionManager.getSessionId()`: Pi's session identifier, a UUIDv7 unless the operator chose `--session-id`, and the value the native usage records already carry. On Pi 0.99.1 it was observed stable across the turns of a session and distinct between two concurrent sessions.
- **Toggle key.** `ctrl+alt+a`, which no default Pi keybinding uses. With the driver on it parks. With the driver off it drives again with the input last accepted in this Pi session. That text lives only in the extension's memory, so a new, resumed, forked or reloaded session has none; the key then starts nothing and posts a notice asking for `/cq:drive` with targets and a through phase.
- **Continuation.** On `agent_settled` with the driver on, the extension sends `Continue`. It submits a directive with `sendUserMessage(text, { deliverAs: "followUp", expandPromptTemplates: true })`, which Pi expands through the `/cq:advance` prompt template; a stop is shown and nothing is sent. A turn whose `turn_end` outcome is `aborted` or `error` parks the driver instead of continuing, so `escape` ends a drive. With the driver off the extension makes no request.
- **Indicators.** The footer status `cq-driver` shows `DriverStatus.line` from every control reply and is refreshed by a `Status` query at each `turn_end` while the driver is on; before any drive it reads `CQ driver off`. Every state change, set-change message and stop reason is posted with `ctx.ui.notify`, failure and not-bound stops as errors.
- **Session end.** `session_shutdown` parks a driver that is on, because the attached session it is bound to ends with the host process.

## Claude Code and Codex hooks

Claude Code and Codex share one hook program, `cq hook HARNESS EVENT` (`DriverHook`), which `cq configure` installs as project hooks. It reads the hook's JSON on stdin, calls `DriverEntry` with the operator credential and the saved project configuration, and writes the hook's JSON reply. It holds no state and never builds or edits a directive.

- **Session key.** The `session_id` on the hook's stdin. On Claude Code 2.1.285 and Codex 0.159.2 the UserPromptSubmit and Stop hooks of one session received the same id on every turn, and two concurrent sessions received different ids. The Claude statusLine input carries the same id.
- **UserPromptSubmit.** A prompt whose first word is `/cq:drive` (Codex: `$cq-drive`) sends the rest of the prompt unchanged to drive-start; `/cq:park` (`$cq-park`) parks. The reply goes back as `hookSpecificOutput.additionalContext` for that session's turn and as a `systemMessage` in the transcript: the host message, the status line, the single-use bind token with the exact `session` `Bind` request, and the preview in its three groups (rendered by `DriverHook`, the host-side renderer for both harnesses). A host rejection, or a failure to reach the host, is reported the same way and leaves the driver as it was. Every other prompt produces no output and no host call.
- **Command bodies.** `/cq:drive`, `/cq:park` and the `cq-drive`, `cq-park` skills only present that bind token to `session` `Bind` and show the status. They start and park nothing; a session that skips the bind gets the `NotBound` stop at its next stop.
- **Stop.** The hook queries the continuation. `Continue` becomes `{"decision":"block","reason":…}`: any set-change messages, one instruction line, and the host directive, unchanged, as the last line. Anything else allows the stop, with the stop message as `systemMessage`; with the driver off the hook prints nothing.
- **Status line.** Claude Code only: `cq hook claude StatusLine` prints `DriverStatus.line`, or `CQ driver off` before any drive. Codex shows no custom status-line text, so its indicator is the transcript messages.
- **Errors.** Malformed input, a missing or malformed `session_id`, an event other than the one the command was installed for and an unknown harness are rejected before any host call, with an explicit message and no default session. The program always exits 0 and never blocks the harness on a CQ error: the Stop hook then allows the stop and the prompt is passed on, with the error as `systemMessage` (as the status-line text for `StatusLine`).
- **Parking a running drive.** Codex delivers a `$cq-park` typed during a driven turn to the hook at once. Claude Code queues a prompt typed during a turn until the turn ends, and a blocked stop continues the same turn, so in Claude Code the operator interrupts the turn first (Stop hooks do not fire on an interrupt) and then enters `/cq:park`.

`cq configure` writes, for Claude Code, `.claude/commands/cq/drive.md` and `park.md` and, in `.claude/settings.local.json`, one hook group for each of `UserPromptSubmit` and `Stop` plus a `statusLine`; for Codex, the `.agents/skills/cq-drive` and `cq-park` skills and the two hook groups in `.codex/hooks.json`. An entry is CQ's when its command ends in `hook <harness> <event>`; such entries are replaced, and every other hook group, handler and event is kept. A `statusLine` that is not CQ's is refused until the operator passes `--replace-statusline`; `--replace` alone does not replace it.

## Interfaces

HTTP and WebSocket carry `Command.Driver` and `Result.Driver`. The attached host's `session` tool adds `Bind` and `Driver`. A Pi attached host additionally serves the JSON-RPC method `cq/driver`, taking an `ExtensionDriver` (`Start`, `Park`, `Continue` or `Status` with the extension's session key) and returning a `DriverReply` or `{"Failed":{"fault":…}}`; other harnesses answer it with method-not-found. The CLI has one driver entry point, `cq hook`, and there is no MCP domain tool.

Contract tests (`DriverContractTest`, dummy and PostgreSQL) cover the state machine, the trust cases, two consecutive driven cycles, resume, each rejection and each stop reason. The attached process fixture (`dev/attached-check.py`) drives one cycle through a real attached host, including lineage registration from dispatch and the failure stop of an out-of-set write. `dev/pi-driver-check.mjs` runs the Pi extension against a scripted `cq/driver` host: commands, toggle key, footer, notices, session key and every stop reason. `dev/pi-driver-session.mjs` runs the extension that `cq configure pi` generates against a real attached host with a simulated model: two consecutive cycles with one resume and a single run, a stored workset in a second concurrent session, and the failure stops of a skipped directive, an untracked advance and a direct out-of-set change. [Validation](../validation/pi-driver.md) records the run on Pi 0.99.1. `DriverHookTest` (dummy and PostgreSQL) drives the generated Claude Code and Codex hook entry points with the observed payload shapes against the driver core: drive and park, binding through the command body, session isolation, every rejected input, start and resume directives byte for byte, consecutive cycles and the failure stops. `dev/attached-check.py` also runs the installed `cq hook` commands as processes against a real server and attached host. [Hook driver validation](../validation/hook-driver.md) records the runs on Claude Code 2.1.285 and Codex 0.159.2.
