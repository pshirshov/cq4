# Drive CQ work automatically

The auto-driver keeps one interactive Claude Code, Codex or Pi session advancing a fixed set of items up to a fixed phase. You switch it on with a drive command and off with a park command. While it is on, the CQ server decides after every turn whether work remains and, if so, hands the session the exact `advance` invocation to run next. It stops by itself, with a stated reason, when nothing is ready, when a person must answer, when a limit is reached or when the session leaves its bounds.

The four workflow commands (`begin`, `advance`, `review`, `upstream`) are unchanged. With the driver off the harness behaves as it does without the driver.

Setup of the interactive harness itself (server, token, sandbox) is in [Run CQ inside your normal harness](interactive.md). The mechanism is specified in the [driver design](design/driver.md). How a driven session is evaluated from a recorded terminal, with and without the driver, is in the [evaluation protocol](evaluation-protocol.md).

## What is and is not verified

Read this before relying on a drive.

| Evidence | What it covers |
| --- | --- |
| Contract tests `DriverContractTest` and `DriverHookTest` (dummy and PostgreSQL repositories) | The server state machine, the write boundary, every stop reason, the limits and the hook program's input and output. The model is simulated. |
| `dev/attached-check.py`, `dev/pi-driver-session.mjs` | The generated hook commands and the generated Pi extension as processes against a real server and attached host. The model is simulated. |
| [Hook driver validation](validation/hook-driver.md), [Pi driver validation](validation/pi-driver.md) | Real Claude Code 2.1.285, Codex 0.159.2 and Pi 0.99.1 sessions with real models: commands, hook delivery, binding, blocked and allowed stops, status surfaces, park, session keys. The backend was a scripted stub: no CQ server, ledger or attached host took part. |
| [First driven sessions on the release](validation/crosscut-20261002.md#first-driven-sessions-on-the-release-2026-10-02) | Real models in Claude Code 2.1.285 and Codex 0.159.2 driving a real CQ server and ledger, with child dispatch. Claude Code drove one defect through integration in one drive (44 minutes) and three defects through integration in another (3 hours 26 minutes), which ended `user input required`. Codex drove five defects through planning over two start directives and stopped with `user input required`. Pi took no part. |
| [Real-harness driver cases (T59)](validation/t59-driver-cases-20261004.md), 2026-10-04 | Real Claude Code 2.1.285 and Codex 0.159.2 sessions against private real CQ servers and ledgers (package `e5dba67`). Two concurrent sessions with distinct keys and frozen targets. Skipped directive, untracked activation and out-of-set change each stopped with `Failure`, and the complete item revision and summary page was unchanged. Parking one session left the other On. On Claude Code: Resume continued the existing run without a second child, a changed advanceable set produced a second start directive in the same drive, and each start, but no resume, made exactly one `Advance` record. All of this was observed by the governing sessions in private fixtures and has not been independently reread. The readable projection is in `validation/evidence/t59-driver/`. It includes a per-activation crosswalk of issued directives and accepted activations for both harnesses, plus the before and after item pages of every Failure-stop case on both harnesses. On Claude Code, Resume and consecutive cycles were observed in separate drives of one session, not within one drive. No Codex Resume was recorded. |

**Not recorded:** Pi in the concurrent-session, park and `Failure`-stop cases against a real CQ server and ledger. A separate T59/T60 private Pi fixture (`pi-driver` scenario, Pi 0.99.1) recorded preparatory initial and follow-up integrations against a real server; the governing session observed them, and nobody has independently verified them. Resume and consecutive cycles have not been recorded within a single drive, and no package later than `e5dba67` has been exercised by a real harness. The Defect report and its blocking links (see the rules below) are shown by the automated checks with a simulated model only. Treat a drive on Pi, and the first drive on a new harness version, as a trial: choose a small workset, watch the transcript, and keep the park command at hand.

## Supported harness versions

Two sets of versions apply, and they are not the same thing.

| Versions | Status | Evidence |
| --- | --- | --- |
| Claude Code 2.1.285, Codex 0.159.2, Pi 0.99.1 | **Driver-validated.** The driver was recorded on these versions, and the session key was validated on them: the hook-stdin `session_id` on Claude Code and Codex, and `ctx.sessionManager.getSessionId()` on Pi, stayed the same across the turns of one session and differed between two concurrent sessions. | Real-model sessions against a stub backend ([hook driver](validation/hook-driver.md), [Pi driver](validation/pi-driver.md)). For Claude Code 2.1.285 and Codex 0.159.2 there are also two concurrent sessions with distinct keys against real CQ servers and ledgers ([T59](validation/t59-driver-cases-20261004.md)). Pi 0.99.1 has no concurrent-session case against a real server. |
| Claude Code 2.1.280, Codex 0.156.1, Pi 0.87.1 | **Supervisor-accepted only.** These are the older pins that CQ still accepts as harness versions in the supervisor settings. The driver was not run on them, and their session keys were not validated. | None for the driver. |

Criterion 6 of G1 (host-held per-session driver state, with a session key validated against the supported harness version) covers only the driver-validated versions: Claude Code 2.1.285, Codex 0.159.2 and Pi 0.99.1. Driving a session on one of the older supervisor-accepted versions is outside that criterion. Treat it as an unvalidated trial.

## Install

The driver's assets are part of what `cq configure` writes. Reconfigure each harness you use with a CQ package that contains the driver; in such a package `cq help hook` prints `Usage: cq hook HARNESS EVENT`. A package without that command predates the driver: install a newer one first.

```sh
cq configure claude --settings /absolute/settings.json --replace
cq configure codex  --settings /absolute/settings.json --replace
cq configure pi     --settings /absolute/settings.json --replace
```

`--replace` and `--replace-statusline` go after the other options. `--replace-statusline` applies to `claude` only; `cq configure codex` and `cq configure pi` reject it with `--replace-statusline applies only to claude: cq configure installs no status line for codex`. `--replace` overwrites CQ-generated files that differ from the current package (command files, skills, the Pi extension, the Codex `config.toml` with the CQ header, the `cq` entry of `.mcp.json`). It never replaces a file or entry CQ does not own.

| Harness | What is written for the driver | Extra steps |
| --- | --- | --- |
| Claude Code | `.claude/commands/cq/drive.md` and `park.md`; in `.claude/settings.local.json` one hook group for `UserPromptSubmit`, one for `Stop`, and a `statusLine`. | If the file already has a `statusLine` that is not CQ's, configuration stops with `Claude statusLine in .claude/settings.local.json differs; use --replace-statusline …`. Add `--replace-statusline` to replace it; `--replace` alone does not. Launch with `claude --setting-sources project,local`, because the hooks and the status line are in the local settings source. |
| Codex | The skills `.agents/skills/cq-drive` and `cq-park`; the two hook groups in `.codex/hooks.json`. | Codex runs project hooks only in a trusted project after a review. In the Hooks dialog (`/hooks`) Codex 0.159.2 showed `2 hooks need review before they can run`; `t` trusts all, Enter reviews one. It ran the hooks after that. |
| Pi | Nothing beyond the generated extension `.pi/extensions/cq-host.js`, which contains the commands, the toggle key and the footer. | None. `pi --approve` loads the extension. |

In both hook files an entry is CQ's when its whole command is the one CQ generates: a single executable followed by `hook <harness> <event>` (extra whitespace is ignored). Such entries are replaced, including one left by a CQ installed elsewhere; every other hook group, handler and event is kept. A command that wraps the CQ hook, for example `timeout 5 /path/cq hook claude Stop`, is yours: CQ keeps it and adds its own group, so the hook then runs twice. Remove or edit wrapped copies yourself. Existing files are replaced by a rename, so a running harness never reads a half-written file.

The hook commands run `cq hook <harness> <event>` as processes of the harness. They read the project endpoint saved by `cq init` and need the operator credential, so the harness environment must carry `CQ_TOKEN_FILE` (or `CQ_TOKEN`) exactly as the attached host needs it. Without it a drive or park command is rejected with `… rejected: CQ_TOKEN or CQ_TOKEN_FILE is required`, the `Stop` hook and the status line report `CQ … hook error: CQ_TOKEN or CQ_TOKEN_FILE is required`, and the session continues undriven.

## Choose the work

A drive is scoped by a **workset**: a non-empty set of target item IDs and one through phase (`explore`, `plan`, `work`, `review` or `integrate`).

- **Advanceable set.** The targets themselves, whatever their ledger, plus the descendants the workset traversal selects from them: produced work and members of selected milestones. Only these items may be changed by a drive.
- **Context only.** One-hop neighbours outside that set (prerequisites, reviews, milestones reached through a member). They are shown and never advanced.
- **Through phase.** Each cycle runs `advance` with exactly the targets as roots and `--through` that phase. The phase does not change which items are selected.

Empty targets are rejected and never mean the whole project. A workset accepts at most 64 targets and its traversal at most 1,024 items.

### Preview before driving

`cq query --roots G1,T4` prints the same traversal as a transient workset: selected items, context and readiness. Every drive command also returns the preview, but it starts the driver in the same step, so use the query first when you want to look without starting.

### Discovery and stored worksets

Listing candidate roots, storing a workset and previewing a stored one are `Command.Workset` actions of the HTTP and WebSocket API. They have no CLI command and no MCP tool. A stored workset is immutable and has a host-generated ID.

```sh
config="$(git rev-parse --git-common-dir)/cq/project.json"
project=$(jq -r .project.value "$config"); origin=$(jq -r .endpoint "$config")
call() {
  curl -sS "$origin/api/call" -H "Authorization: Bearer $(cat "$CQ_TOKEN_FILE")" \
    -H "CQ-Session: $(uuidgen)" -H 'CQ-Protocol-Version: 0.1.0' -H 'Content-Type: application/json' -d "$1"
}
id() { printf '{"project":{"value":"%s"},"ledger":"%s","number":"%s"}' "$project" "$1" "$2"; }

# Candidate roots: open items with no open producer and no open containing milestone, 1–32 per page.
call '{"Workset":{"input":{"project":{"value":"'$project'"},"action":{"Discover":{"after":null,"snapshot":null,"limit":32}}}}}'
# Store a workset; the reply is {"WorksetStored":{"workset":{"id":{"value":"<UUID>"},…}}}.
call '{"Workset":{"input":{"project":{"value":"'$project'"},"action":{"Create":{"targets":['"$(id Goals 1)"'],"through":"Work"}}}}}'
# Preview a stored workset; the reply is {"WorksetPreviewed":{"preview":{…}}}.
call '{"Workset":{"input":{"project":{"value":"'$project'"},"action":{"Preview":{"target":{"Stored":{"id":{"value":"<UUID>"}}}}}}}}'
```

`ledger` is the ledger name (`Goals`, `Tasks`, `Defects`, …) and the phase is capitalised in JSON (`Explore`, `Plan`, `Work`, `Review`, `Integrate`). `Discover` requires `after` and `snapshot` to be present; pass the returned `after` and `cursor` to continue. Each candidate root carries its descendant count, ready count and context count, or `Oversized` when its traversal exceeds the item bound. These request bodies were decoded with the generated codec for this page, and `Create` is used by `dev/pi-driver-session.mjs` against a real server; the `curl` lines themselves were not run.

## Start a drive

| Harness | Command |
| --- | --- |
| Claude Code, Pi | `/cq:drive G1,T4 through=work` or `/cq:drive workset=<UUID>` |
| Codex | `$cq-drive G1,T4 through=work` or `$cq-drive workset=<UUID>` |

IDs are separated by spaces or commas. Inline targets need exactly one `through=<phase>`, in lower case. `workset=<UUID>` stands alone. In Claude Code and Codex the command must be the first word of the prompt; any other prompt passes through untouched.

A drive command carries targets and a phase and no request text. Rules that the driven work must follow, such as the project's testing policy, are therefore set beforehand as the project's standing requirements (*Standing requirements* in the browser): the host delivers them to the Governor through session Context and workflow activation, and to every Planner, Worker and plan or candidate reviewer the drive dispatches. See [Standing requirements of a project](interactive.md#standing-requirements-of-a-project).

The targets and the phase are frozen for the drive. A second drive command while the driver is binding or on is rejected with `conflict: This session's CQ driver is already on; park it before driving other targets or another phase`. A rejected drive (`CQ driver drive-start rejected: …` in Claude Code and Codex, `CQ driver not started: …` in Pi) changes nothing. The exception is a reply that never arrived: in Claude Code and Codex the hook then reports `The CQ server's reply was not received, so this session's driver may have changed …`. The server may have started or parked the driver, so read the driver status or park before driving again.

### Claude Code and Codex: the bind step

1. The `UserPromptSubmit` hook handles the typed command before the model sees it. It posts `CQ driver drive-start: CQ driver binding: G1,T4 through work; it turns on when this session presents the bind token` and gives the model the preview and a single-use bind token.
2. The command or skill body makes the model show the preview and call the CQ `session` tool once with the printed `Bind` request. The reply is `CQ driver on: G1,T4 through work`.
3. The turn ends. From here on the `Stop` hook answers every stop.

The bind exists because the hooks and the ledger writes belong to two different identities. The hooks know the harness `session_id`. The writes come from the session's CQ attached host (the `cq` MCP server), which has its own CQ session. The token, delivered only into this session's context, ties the driver to exactly one attached session, and that session is the one whose writes the driver confines. The token is valid once and for ten minutes.

The command and skill bodies cannot start or park a driver; only the hooks do. If the bind does not happen (the `cq` MCP server is not loaded or its tool call is refused), the next stop ends the drive with `NotBound`.

In Claude Code and Codex the preview is two lists: `Advanceable (n)`, each item with `(target)` where it applies and its readiness (`ready`, or `not ready: <reasons>`), and `Context only, never advanced (n)`. At most 25 lines are listed per list; the rest is counted.

### Pi

The extension supplies its attached session itself, so there is no token and no bind. `/cq:drive` posts `CQ driver on: G1,T4 through work` with the preview in three groups (`Advanceable`, `Context only, never advanced`, `Readiness`; at most 12 lines each) and, when Pi is idle, submits the first directive at once.

## What a drive does

A drive is a sequence of cycles. After each turn the harness asks the server for the continuation (the `Stop` hook in Claude Code and Codex, the extension on `agent_settled` in Pi).

- **Snapshot.** For a new cycle the server computes the advanceable set from the frozen targets. This snapshot alone decides whether the cycle starts, and it is stored with the cycle. It is not recomputed when the session activates the cycle or writes.
- **Refresh rule.** The set is recomputed before every cycle, so an item produced in cycle N is advanceable in cycle N+1. When the set changes, a message says so: `CQ driver: the advanceable set changed to 2 items; added T1`.
- **Start directive.** A new cycle is handed over as one line the session must run unchanged: `/cq:advance --roots G1,T4 --through work --start-token <UUID>` (Codex: `$cq-advance …`). The token is valid once. The server starts the run only if the roots, the phase and the token equal the directive and the caller is the bound session.
- **A stop while the host works.** Work of the running cycle is in flight while the host carries it out: a running child attempt, an integration being prepared or applied, a combination being prepared. A Claude Code or Pi session may end its turn then. The driver waits: the stop is allowed, no directive is issued, no directive is counted and the drive stays on (`CQ driver waiting: cycle 1 has attempt <ID> in flight; the session continues when it ends`). The session's waiter starts its next turn when the work ends (see [Waiting for children](interactive.md#waiting-for-children)); that turn belongs to the same cycle, and the stop after it is decided as any other. The status line or footer keeps showing the count, for example `CQ driver on: G1 through work; 1 active child`.
- **A host that is gone.** A session whose CQ host no longer runs is woken by nothing and its children finish nothing. When the Stop hook finds work in flight and the host of the session not running, it parks the drive and says so: `CQ driver stopped: the CQ host of this session is not running while its work was in flight, so nothing would continue the drive. CQ driver parked: …`. Restart the harness session and drive again. In Pi the extension loses its host connection in that case and reports the continuation query as failed.
- **Resume directive.** Nothing wakes an idle Codex session when a background command exits ([openai/codex#32188](https://github.com/openai/codex/issues/32188)), so a Codex session waits inside its turn. If it stops while work is in flight all the same, the server issues `… --resume-token <UUID>` with a fresh token, which keeps the turn going. It reattaches the session to the existing run and never starts a second one. When Codex wakes sessions itself, Codex can wait as Claude Code does.
- **Work that waits for the session.** A prepared integration that was not applied, an integration awaiting reconciliation and a combination whose publication is pending do not finish by themselves. When only such work remains, the session gets one resume directive to resolve it. If it stops again with the same work still waiting, the drive ends with `Failure` and names that work. Applying an integration counts as resolving it from the moment `Integrate` returns: while the host applies it, the driver waits (Codex: the session gets resume directives), however long the application takes. A prepared integration that will not be applied is resolved by discarding it (`DiscardIntegration`), which settles it as not applied without touching Git.
- **Work left by an earlier drive.** If a drive ended while an integration its session prepared was not applied or awaits reconciliation, the next drive's start directive is refused (`Settle active child/check/integration/combination work before changing workflow: integration <ID> (Ready)`; the refusal names what is unsettled) until that integration is settled. The session applies it with `Integrate`, or discards it with `DiscardIntegration`, in the same turn and then runs the start directive again; the drive stays on. No new integration or combination can be prepared before the start directive has run. Only an integration that the earlier drive registered can be applied this way. One the session prepared between the two drives is refused by `Integrate` (`… was not left unsettled by an earlier drive of this session …`) and nothing is applied; discard it, or park the driver, apply it, and start the drive again. A combination the earlier drive left with its publication pending is not published this way either: repeating its `Combine` is refused until the driver is parked or has stopped.
- **A child that left no result.** When a cycle changes nothing but a child of it failed without a result (malformed output, a refused report, a failed process), the drive does not stop as quiescent: the next directive is issued with `CQ driver: cycle 1 changed nothing, and its work on G1 failed without a result; the same input is offered again with the fault`, and the session selects the same work again. If the same input fails again before any cycle changes something, whatever the fault and whichever other inputs failed in between, the drive ends with `Failure` and names the item, both attempts and both faults. No retry count or timeout is configured. See [attempt outcomes](design/driver.md#attempt-outcomes).
- **Lineage.** The cycle ID is stamped on the run and inherited by everything dispatched from it: requests, child attempts, claims, proposal applications, integrations and combinations. (The server also models sessions delegated under a cycle; no CQ client creates one.) The active-children count in the indicator counts unsettled child attempts.

How a continuation appears:

| Harness | Continue | Stop |
| --- | --- | --- |
| Claude Code | The stop is blocked. Claude Code 2.1.285 displays it as `Stop hook error: CQ driver: run the directive on the last line verbatim …`. This is the normal continuation, not an error. The model then runs the directive through the Skill tool. | The stop is allowed and the reason is shown as `Stop says: CQ driver stopped (…): …`. |
| Codex | `Blocked by hook`; the model follows the `cq-advance` skill with the directive. | `↳ Hook · CQ driver stopped (…): …`. |
| Pi | The directive is sent as a follow-up message and Pi expands it through the `/cq:advance` prompt template. | A notice; `Failure` and `NotBound` are error notices. |

## Rules while the driver is on

- **Every ledger write of the driven session is checked.** While the driver is on, each ledger mutation from the bound session is attributed to the active cycle, whether it comes from the advance workflow or from a direct CQ tool call: item changes, Reference changes, proposal applications and integration completions. A change to an existing item outside the cycle's snapshot is rejected. A Reference is checked at both ends. A new item is admitted only if the traversal of the frozen targets selects it after the write, so a plain create is rejected and a produce under an in-set item is admitted. The one plain create that is admitted is the Defect report below.
- **Planned Tasks get their milestone.** A milestone is context of a workset, not a member of it. A produce under an in-set item may still assign its Tasks to an Open milestone outside the set, or to a Milestone created in the same batch, and an in-set Task may be added to an Open milestone. The milestone gains the member and nothing else about it may change: editing, archiving, closing or reopening a milestone outside the set is an out-of-set change, and so is assigning to a milestone that is not Open.
- **A Task under a closed milestone is reassigned.** Implementation work is refused for a Task whose milestone is Complete or Cancelled. When that milestone is outside the set, as in a drive rooted at the Task, the drive removes the Task's membership in it and then adds the Task to an Open milestone, in two requests. The closed milestone loses the member and nothing else about it changes. Removing a membership in an Open milestone outside the set stays an out-of-set change. When the milestone is itself in the set, the drive reopens it instead.
- **A blocking defect is recorded, not worked.** When a host or tooling defect blocks the workers, the driven session creates a Defect for it with a plain create and links each blocked item `BlockedBy` it; the link may also name an Open Defect that already exists. Both writes are admitted although the Defect is outside the set. The Defect never joins the set: the drive does not advance it and cannot edit it afterwards, and any other write that names it is an out-of-set change. The linked items are not ready until the Defect is Resolved, so the drive continues with its other work and, when nothing else can advance, stops with `Quiescent` and names the Defect. Resolve it by hand or with a drive of its own, then drive again. Only `Resolved` unblocks the linked items. A Defect you close as `NotReproducible`, `Rejected` or `Withdrawn` leaves them blocked, and a drive cannot remove the link. Remove it yourself, with the driver parked or from another session: in the browser, open the blocked item and use its `Remove BlockedBy D7` button; or call the `change` tool with `Reference(item, BlockedBy, Defect, present false)` and the current revisions of both. Then drive again.
- **No cycle, no write.** Before the first directive is accepted and between cycles, any ledger mutation from the bound session is rejected, in-set or not. The one exception is the completion of an integration that an earlier drive of the same attached session left unsettled, while the start directive is issued and not yet accepted (see [driver design](design/driver.md#bound-session-mutation-rule)).
- **No other workflow.** Activating `begin`, `review` or `upstream`, or `advance` without the directive's token or with other roots or another phase, is rejected.
- **Rejection.** Each of the rejections above leaves the ledger unchanged (the check runs inside the writing transaction), returns `CQ driver stopped with reason failure: <detail>` to the tool call and stops the driver with `Failure`.
- **To change something by hand, park first.** After a park or a stop the session writes as it does without a driver.
- **The token stays out of the request text.** The directive's token is passed only in the workflow activation's `token` field. If the model copies it into `operatorRequirements`, the attached host refuses the activation with `operatorRequirements carries this activation's CQ driver token; …` before the token reaches the server, and the model can activate again without it. This is not a driver stop. Do not paste a directive into free text yourself.
- **The driver never answers for you.** An open Question or a requested Operator Action that is ready or blocks an advanceable item stops the drive with `UserInputRequired` once no other item is ready or the previous cycle changed nothing. The workflows tell a session that needs your decision to record it as a Question and to link the items it gates `BlockedBy` it before it stops, because a question the session asks only in its message is invisible to the driver. That link is admitted when the Question is outside the set too, as long as it is Open or Answered; removing such a link, or any other write that names a Question outside the set, is an out-of-set change. While the drive continues on other work, a write of the driven session that answers a Question, or changes its answer, is refused with `The CQ driver never answers Questions: Q3 would be answered by a driven session; park the driver before recording the user's answer`. A write of the driven session that withdraws an open Question is refused the same way, whether it replaces the Question, restores a withdrawn revision or cancels it by termination: `The CQ driver never settles Questions: Q3 would be withdrawn by a driven session; park the driver before withdrawing a Question`. Either refusal writes nothing and leaves the driver on. To have your answer or a withdrawal recorded, park, let the session record it, and drive again.
- **The driver never settles an Operator Action for you.** A requested Operator Action waits for you just as an open Question does. A write of the driven session after which the action is no longer `Requested` is refused, whatever status it would leave: `Confirmed` (also without confirmation text), `Observed`, `Failed` or `Cancelled`, whether the write replaces the action, restores an earlier revision or cancels it by termination, directly or through an item that produces or contains it: `The CQ driver never settles Operator Actions: OA2 would be taken out of Requested by a driven session; park the driver before settling an Operator Action`. The refusal writes nothing and leaves the driver on, and the next stop without other ready work is still `UserInputRequired` naming the action. To settle it, park the driver, perform and confirm the action or cancel it by hand, and drive again. The driven session may still reword a requested action, create new Operator Actions, and move an action you have already confirmed to `Observed` or `Failed`.

## Indicators

Every state change and every stop posts a transcript message. The status text is one host-generated line:

```text
CQ driver off
CQ driver binding: G1 through work
CQ driver on: G1 through work; 2 active children
CQ driver off: G1 through work; stopped (quiescent): No item of the advanceable set is ready to advance
```

| Harness | Status surface | Transcript |
| --- | --- | --- |
| Claude Code | The CQ `statusLine` (`cq hook claude StatusLine`) prints the line. In the recorded run each call of the JVM build took 2–3 seconds and Claude Code cancelled refreshes that a newer one superseded. | `UserPromptSubmit says: …`, `Stop says: …`, `Stop hook error: …` for a blocked stop. |
| Codex | None. Codex shows no custom status-line text, so the transcript messages are the only indicator. | `↳ Hook · …`, `Blocked by hook`. |
| Pi | The footer status `cq-driver` shows the line; it is refreshed at every turn end while the driver is on. If that refresh fails or is not answered within five seconds, the footer reads `CQ driver status unavailable: …` and the drive continues. | Notices. |

A CQ error in a hook never blocks the harness: the hook exits 0, the prompt or the stop proceeds, and the error is shown as `CQ <event> hook error: …` (in the status line for `StatusLine`).

## Park

| Harness | How | Notes |
| --- | --- | --- |
| Claude Code | `/cq:park` | A prompt typed during a turn is queued until the turn ends, and a blocked stop continues the same turn. A `/cq:park` typed during a driven turn therefore reaches the hook only after the drive has ended. Press Esc first, then enter `/cq:park`. An interrupt runs no `Stop` hook, so after Esc alone the driver is still on and the rules above still apply. |
| Codex | `$cq-park` | Delivered to the hook at once, also during a driven turn; the turn's stop is then allowed. |
| Pi | `/cq:park` or Ctrl+Alt+A | See the toggle below. Escape aborts the turn and the extension parks. A turn that ends in an error parks too. Quitting Pi with the driver on parks. |

Park posts `CQ driver parked: G1 through work`, ends the pending or active cycle (its tokens stop working) and releases the binding. Parking a driver that is off posts `CQ driver is already off`. Park stops further directives. It does not interrupt the running turn (observed in Pi and Codex), and the driver has no operation that cancels dispatched children. The rest of a turn that runs on after a park is an ordinary undriven turn: its writes are no longer confined to the workset.

**Pi toggle key, Ctrl+Alt+A.** No default Pi key binding uses it; `/hotkeys` lists it.

| State | Effect |
| --- | --- |
| Driver on | Parks it. |
| Driver off, a drive was accepted earlier in this Pi session | Drives again with that same input text. |
| Driver off, no drive accepted in this Pi session | Starts nothing and posts `CQ driver not started: this session has no workset yet; run /cq:drive <target IDs> through=<phase> or /cq:drive workset=<id>`. |

The remembered input lives only in the extension's memory. A new, resumed, forked or reloaded Pi session has none.

Claude Code and Codex have no toggle key: their key bindings cannot invoke a command or a skill. This comes from the planning probes of 2026-09-30 and is not part of the recorded validation.

## Stop reasons

A stop turns the driver off and releases the binding. The message is `CQ driver stopped (<reason>): <detail>`. To continue after any stop, run the drive command again (in Pi, the toggle key); a new drive starts with a fresh directive count.

| Reason | Shown as | Cause | What to do |
| --- | --- | --- | --- |
| `Quiescent` | `quiescent` | No advanceable item is ready, or the previous cycle changed nothing in the set, its context or its readiness and left no failed child to retry. When items are blocked by something outside the set, either detail names it: `No item of the advanceable set is ready to advance; blocked from outside the set: D7 blocks T5,T6`, or `The previous cycle changed nothing in the advanceable set, its context or its readiness; blocked from outside the set: D7 blocks T5` in a drive whose root, a Defect or a Goal, stays ready. The detail names at most eight blockers and eight items for each, and counts the rest. | Resolve a named blocker first; the drive cannot. Otherwise check the items with `cq query --roots …` or the browser. Either the work is finished up to what is ready, or the last cycle made no progress: read its transcript before driving again. |
| `UserInputRequired` | `user input required` | `Awaiting the user on Q3,OA1; …`: an open Question or a requested Operator Action. | Answer the Question or complete the Operator Action, then drive again. |
| `LimitReached` | `limit reached` | The drive issued its 64 directives. | Read the last turns first: a drive that spends its directives on resume directives is waiting for work that does not finish. Then drive again. A new drive starts a fresh count, and one harness session can run any number of drives. |
| `NotBound` | `not bound` | Claude Code and Codex: no attached session presented the bind token before the turn ended. | Check that the `cq` MCP server is loaded in the session and that its tool call is permitted, then drive again. |
| `Failure` | `failure` | The session left its bounds or the set could not be computed. The detail names the case: `directive not started` (the session stopped without running the start directive), `untracked activation`, `untracked mutation`, `out-of-set change: T7 is outside the advanceable set stored for cycle 2`, `non-selected creation`, a workflow, roots or phase that differ from the directive, an unknown or reused token, an item created by the cycle that the recomputed set does not select, `cycle 2 is held by integration <id>, which only the session can resolve, and a resume directive did not resolve it` (prepared work was left unapplied), `attempt <id> of cycle 2 could not be registered: …` or `… could not be settled: …` (the attached host lost track of dispatched work), `run <id> of cycle 2 is not the active workflow of its attached host`, `attempt <id> of cycle 2 failed on G1 with the same fault as the attempt before it on the same input: <fault>` (a child left no result twice in a row for the same reason; a single such failure is retried), `G1 failed without a result twice on the same input while no cycle in between changed anything: attempt <id>: <fault>; attempt <id> of cycle 2: <fault>` (the retry of a failed child failed again, for any reason, and no cycle from the first failure on changed anything). | The rejected operation wrote nothing. Read the detail and the turn that caused it. If the change was intended, make it now, with the driver off, and drive again; if the target set was too narrow, drive with other targets. |
| `Parked` | `parked` | `Parked by the operator`. | Nothing; drive again when wanted. |
| `Off` | `off` | The continuation was asked for a session that has no driver or whose driver is off, for example after a server restart. | Claude Code and Codex print nothing; the status line reads `CQ driver off`. Pi posts `CQ driver stopped: No CQ driver is on for this session`. Drive again. |

Pi adds two local outcomes. `CQ driver stopped: continuation query failed: …; run /cq:park to release the driver` means the extension could not reach its host; the server-side driver may still be on, so park. `CQ driver: the last turn ended aborted, so the driver parks instead of continuing` follows an aborted or failed turn.

## Limits

| Limit | Value | At the limit |
| --- | --- | --- |
| Directives per drive (start and resume both count) | 64 | The drive stops with `LimitReached`. |
| Drivers per project | 64 | A new drive displaces the least recently touched driver that is off or has been silent for eight hours. That includes a driver that is on: one with no directive and no write for eight hours is removed without a message, its next continuation returns `Off`, and its session's writes are no longer confined. If every driver is live, drive-start fails with `limit: A project holds at most 64 CQ drivers; park one first`. |
| Lineage members per cycle | 1,024 | Registration fails; an attributed write beyond the bound stops the drive with `Failure`; drive again, and the new cycle starts with an empty lineage. Every attributed write, claim, dispatch, child attempt, integration and combination of a cycle is one member. The bound exists because every driver reply carries the whole lineage of the latest cycle and the host reads no reply above 2 MiB: without it a long cycle would end with replies the hook and the extension cannot read. No automated check exercises this bound. |
| Targets per workset; items per traversal | 64; 1,024 | The drive command is rejected. |
| Drive argument text | 4,096 characters | The drive command is rejected. |
| Bind token lifetime | 10 minutes | The bind is refused (`The CQ driver bind token expired; park and run the drive command again`). |

**Driver state is not durable.** It lives in the server process, not in PostgreSQL. A server restart turns every driver off without a message: the next continuation returns `Off`, and the sessions write as they do without a driver. Drive again after a restart. This follows from the in-memory registry; no automated check restarts a server under a drive.

## Trust boundaries

- **Session key.** Claude Code and Codex: the `session_id` in the hook's stdin. On Claude Code 2.1.285 and Codex 0.159.2 the `UserPromptSubmit` and `Stop` hooks of one session received the same id on every turn and two concurrent sessions received different ids. Pi: `ctx.sessionManager.getSessionId()`, observed stable within a session and distinct between two concurrent sessions on Pi 0.99.1. A key is 1–200 characters: letters, digits, `.`, `_`, `:` or `-`, starting with a letter or digit.
- **Trusted, not authenticated.** The key is checked for shape only. Two sessions that each supply their own key never change each other's driver. A process of the same user that runs a CQ hook entry point with another session's id, and holds the operator credential the hooks use, changes that session's driver state: it can start, park or query it. Per-session isolation therefore covers sessions that do not forge hook input, not adversarial ones. A contract test asserts this limitation.
- **No default session.** Malformed hook input, a missing or malformed `session_id`, an unknown harness and an event other than the one the command was installed for are rejected with an explicit message and change nothing.
- **Who can start or park.** Only the CQ hook commands and the Pi extension, with the operator credential. The model-facing `session` tool offers the token-gated `Bind` and a read-only status; a governor credential is denied driver control, and no MCP domain tool carries a driver command.
- **A model with a shell can reach the hooks.** The hook commands need the operator token file in the harness environment, and the harness gives the session's own id to the model's shell tool (`$CLAUDE_CODE_SESSION_ID`, `CODEX_SESSION_ID`). A model that can run commands can therefore run `cq hook <harness> UserPromptSubmit` itself with a drive or park prompt, or read the token file and call the API as the operator. The driver does not defend against this: it confines a cooperating session's ledger writes, it is not a sandbox. Restrict the model's shell or the token file's readability if that matters.
- **What the boundary guarantees.** Ledger writes of the bound session are confined to the cycle's set. Other sessions of the project are unaffected by a driver and are not confined by it. Claims are not confined: the driven session can acquire, renew and release a claim on any item, also outside the set; the claim is recorded in the cycle's lineage and gives no right to change the item. The server also confines writes that carry a cycle ID from a session delegated under the cycle, but no CQ client creates such a session.

## Harness caveats

| Harness | Caveat | Source |
| --- | --- | --- |
| Claude Code | Needs `--setting-sources project,local`: the hooks, the status line and the `cq` server approval are in `.claude/settings.local.json`. | The recorded sessions used this launch; [interactive setup](interactive.md). |
| Claude Code | A typed `/cq:park` is queued during a turn; interrupt first. | Recorded. |
| Claude Code | `Stop` hooks do not run on a user interrupt, so an interrupted drive stays on until `/cq:park`. | Planning probe; consistent with the recorded park after an interrupt. |
| Claude Code | Claude Code overrides a `Stop` hook after 8 consecutive blocks without progress; `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` raises the cap. An API error runs `StopFailure` instead of `Stop`, so no continuation is asked. | Planning probe only. The recorded drives blocked three times in a row at most. The driver's state after such an override or error was not observed: park and drive again. |
| Codex | Project hooks need a trusted project and the `/hooks` review. No status-line text. | Recorded. |
| Pi | The recorded sessions were launched with `pi --approve`. | Recorded. |
| Claude Code, Codex | If the harness issues a new `session_id` while the `cq` MCP server keeps running, drive again under the new id: binding moves the attached session to the new driver and parks the old id's driver (`Its attached session was bound to the CQ driver of session <new id>`). | Contract test; not observed in a real harness. |
| All | A driver whose harness session ended without parking stays in the server until it is displaced at capacity or the server restarts. Pi parks on quit; Claude Code and Codex have no session-end hook installed. | Code. |


## Durable state and browser worksets (D123)

Driver state now belongs to the project repository. Ordinary server restart preserves its committed binding expiry, active cycle, consumed tokens and lineage; persistence does not establish that a child process is alive. The host still reconciles its own journal and workspaces. Project archive restore keeps lineage and carried integrations, turns restored drivers Off with reason `RestoredArchive`, and invalidates bind/start/resume tokens.

The browser's **Drivers and worksets** dialog lists committed drivers, refreshes their status, shows current target evaluations or the frozen cycle snapshot with the attempts of that cycle that left no result and their faults, and parks a driver with the existing hold control. Park requires Human authority and the row's revision; revisions remain unique after idle eviction. Browser Start is outside this scope.

Define a workset from up to 64 explicit IDs or a submitted query, preview its advanceable and context members, then store that exact preview. Query pages share one snapshot; a changed snapshot is refused. The stored workset can filter the item table while retaining its query, order and pagination; **Clear workset filter** removes the constraint. Worksets remain immutable and can be reopened by UUID.

Source implementation and focused validation: [D123 evidence](validation/durable-drivers-20261003.md). Native packaging and operator installation remain delivery checks. Use [the local updater](local-update.md) for the pinned transition after parking/reconciling previous drives and stopping the launcher.
