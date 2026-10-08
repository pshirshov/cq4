# Run CQ inside your normal harness

The interactive harness is the Governor. It starts `cq host` automatically; that host owns CQ worktrees, guardians and dispatched children. The durable CQ server remains separate. `cq run` remains available for batch work.

## This checkout and this machine

Keep the server running with `./run-local.sh` in a separate host terminal. To redeploy committed source, park/reconcile active drives, stop the launcher with Ctrl-C, wait for `CQ stopped`, run `./update-local.sh`, and restart `./run-local.sh` after it succeeds. Reload the browser.

The local updater builds a native package and the guardian. It combines the prior package’s native metadata with a short current-source trace to support incremental native builds. It runs a short CLI/startup/API/embedded-assets smoke against a disposable database, then backs up the local database and replaces the package with recovery support. It runs native-image without the full test and tracing sweeps. The installed package requires neither Java nor a source checkout. The receipt distinguishes `local-smoke` validation from release qualification.

Testing is separate: the implementing agent runs appropriate focused checks before delivery. `./test-local.sh` runs the existing fast, UI, PostgreSQL and native gates when full validation is wanted. Native distribution packaging and package verification remain separate release operations. A local redeploy is not evidence that those gates passed.

The local integration uses `.local/release/bin/cq` and `.local/interactive/settings.json`. The prepared settings enable reviewed integration into `refs/heads/main` and declare `cq-ui`: `nix develop -c ./dev/check ui` (ten-minute deadline). This runs scoped UI/browser verification without the model evaluation matrix. Backend/CLI changes need appropriate additional checks before their implementation; this UI check is not evidence of backend correctness. All three integrations are installed in this checkout. On a fresh checkout, setup installs project-local commands and native configuration:

```sh
cd /home/pavel/work/safe/flakes/cq4
./.local/release/bin/cq configure codex --settings "$PWD/.local/interactive/settings.json"
```

### Integration into main

This checkout remains on `main`. The installed
[remaining-defect package](validation/remaining-defects.md) supports reviewed
integration into the branch checked out in the governing repository. HEAD stays
attached; unrelated staged, unstaged, untracked and ignored content is preserved.
Conflicting local changes cause a refusal.

During the short integration operation, do not edit candidate paths or run
external Git commands in any of this repository's worktrees. A target checked
out in another worktree is unsupported, as are sparse/split or unmerged indexes,
submodules, and assume-unchanged/skip-worktree entries. An interrupted checkout
can retain locks and an unresolved reservation for inspection; do not delete
locks or reset the checkout to force success. See the
[integration and recovery contract](validation/checked-out-integration.md).

Launch directly from the normal checkout:

```sh
yolo --profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex
```

Accept Codex's project-trust prompt. Select the **project** `.agents/skills/cq-begin/SKILL.md` if both personal and project versions are listed. For example:

```text
Use the project .agents/skills/cq-begin/SKILL.md.
Capture these defects and plan the work; ask before implementing: …
```

To try another harness, configure its integration once:

```sh
./.local/release/bin/cq configure claude --settings "$PWD/.local/interactive/settings.json"
yolo --profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token claude --setting-sources project,local

./.local/release/bin/cq configure pi --settings "$PWD/.local/interactive/settings.json"
yolo --profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token pi --no-prompt-templates --prompt-template .pi/prompts
```

Trust the project in Pi once before relying on that launch. Pi 1.0.0 asks when it is launched in a checkout it holds no decision for: a `Trust project folder?` dialog offers `Trust`, `Trust parent folder`, `Trust (this session only)`, `Do not trust` and `Do not trust (this session only)`, and `Trust` saves the decision (observed in one launch). `/trust` in a Pi session started in the checkout remains the way to decide later. Pi saves the decision in `trust.json` of its agent directory (`~/.pi/agent/trust.json` by default) for the Pi processes started afterwards, and those load the project's files, CQ's extension among them, without a flag. Do not launch with `--approve` instead: it trusts the project for one process and saves nothing.

`cq configure claude` merges the `cq` server into `.mcp.json` and records its approval by adding `"cq"` to `enabledMcpjsonServers` in `.claude/settings.local.json`. It also removes `"cq"` from `disabledMcpjsonServers` there, which Claude writes when the server was once declined and which overrides the approval. Other keys and entries in both files are kept. Launch Claude with `--setting-sources project,local`: interactive Claude loads a project `.mcp.json` server only after it has been approved, and both this entry and the approval Claude itself saves are in the local settings source. With `--setting-sources project` it asks "New MCP server found in this project: cq" on every launch and still does not load the server. The `local` source is this checkout's `.claude/settings.local.json`, so personal (user) commands remain excluded.

### Waiting for children

A governing session does not ask for a child's status every few seconds while the child runs. What it does instead depends on the harness:

- **Claude Code** runs `<executable> wait` as a background command and may end its turn; the session starts again when the command exits. The command has no argument: it waits on the session of the one CQ host of the checkout that runs. `cq configure claude` allows exactly that command line (`Bash(<executable> wait)` in `.claude/settings.local.json`, with no wildcard), so no permission prompt appears. With two CQ sessions in one checkout the command says so and exits 5; name the session then with `--session <directory>`, which Claude Code asks you to approve. A background command lives at most two hours; a child that runs longer is waited for again. Leaving Claude Code while a waiter runs raises its "Background work is running" dialog: the waiter can be stopped, it only reads. When Claude Code first asks whether to trust the folder, its dialog lists the `Bash(<executable> wait)` rule among what the project pre-approves; that is the rule above. Work that ends within seconds (an integration being prepared or applied, a combination, a revalidation) is not waited for in the background, and neither is a workspace the Governor submitted, which the host checks as it runs a revalidation: the session calls its status once inside the turn, with `waitMillis` 120000, and starts the background command only if the work still runs when that call returns.
- **Pi**: the CQ extension waits itself and posts `CQ: attempt … ended: …` into the session, which starts a turn. Nothing is typed and no command is run by the model.
- **Codex** waits inside its turn, in a status call of the CQ host with `waitMillis` 120000, which returns when the work ends; it repeats the call while the work continues. Nothing wakes an idle Codex session when a background command exits ([openai/codex#32188](https://github.com/openai/codex/issues/32188)), and a Codex shell call returns to the model after at most 30 seconds whatever it is asked, so the session runs no command to wait. `cq configure codex` sets `tool_timeout_sec = 155` for the `cq` server, which such a call needs. Codex runs tool calls from scripts of its `exec` tool and hands a script back unfinished after 30 seconds by default; the session is told to begin a script that waits with `// @exec: {"yield_time_ms": 150000}`. The session shows its busy text while it waits.

`cq wait` reads the session directory only; it needs no token. Run by hand it tells whether a session still has work in flight: `cq wait --session DIR` returns at once when it has none and the session waits on no Question ([below](#questions-a-session-waits-on)). An integration generated by an earlier package starts the host without its executable. A Claude Code or Codex host refuses to start that way (`This harness integration starts the CQ host without --executable, as an earlier CQ package generated it; run cq configure for this harness with --replace and restart the harness`), and `cq doctor harness` reports the stale files: run `cq configure <harness> --replace` after installing this package. For Codex this also matters when the host does start: an earlier `.codex/config.toml` allows a tool call 35 seconds, so a status call that waits longer fails in Codex. An earlier package also wrote `.codex/rules/cq.rules`, which allowed `<executable> wait` in the Codex shell; nothing uses or checks it any more, and it can be deleted.

Claude/Pi workflow commands are `/cq:begin`, `/cq:advance`, `/cq:review`, `/cq:upstream`. The Claude settings selector and Pi prompt selector avoid the older personal CQ commands installed on this machine. Pi loads project files only in a trusted project; `--no-approve` disables them, including CQ's extension. The launch above is the same for Pi 0.99.1 and 1.0.0. Two things differ on 1.0.0: `--provider` is refused without `--model` (0.99.1 ignored it without a message), so a launch that names a provider names the model too; and the TUI is fullscreen by default, on the terminal's alternate screen, so earlier output is not in the terminal's scrollback. `--tui-mode regular` keeps the scrollback as 0.99.1 does. The `CQ driver …` footer line shows in both modes. CQ was checked on Pi 1.0.0 with real Pi processes but without a model ([Pi 1.0.0](validation/pi-1.0.0-20261006.md)); treat the first sessions on it as a trial.

`cq doctor harness <harness>` checks the generated files, the harness version and the trust the harness has saved. Its `Hook trust` check needs inputs that `cq configure` cannot supply: `--harness-config` for every harness, a recorded hook report for Codex, and for Pi the trust decision saved by the launch dialog or `/trust`, as above. [Doctor](declarative-installation.md#doctor) has the steps.

### Questions a session waits on

A governing session that records a Question and goes on with other work, or ends its turn on it, is told when a person answers or withdraws it (D164). The same holds for an Operator Action that a person takes out of `Requested`; below, "item" means either. The attached host of the session keeps the set of items the session waits on a person for, Open Questions and Requested Operator Actions:

- the ones the session created or replaced in that state, which it waits on from that change on, without a reading: the change itself says so;
- the ones the session linked an item `BlockedBy`, and the ones an applied proposal created, which the host's next round reads and takes when they wait then. No other relation to a Question, and no other revision of one that a change of the session happens to cause, puts it into the set;
- the ones that the workset of the session's active advance workflow waits for a person on: its ready Open Questions and Requested Operator Actions and those that block its members, as the [driver](design/driver.md#continuation-query) names them in a `UserInputRequired` stop. The host takes them from one preview of that workset after each activation, which for a drive is once per cycle. Another workflow replaces them: what the workflow before waited on is read once more, and is announced if it was settled meanwhile and dropped if it still waits.

Nothing is searched for the set, and nothing in the ledger schema serves it. It is bounded by the items the session's own changes created, replaced or linked and by those gating one workset, and an item leaves it when it is settled, when the session reads it settled or settles it itself, or when the next workflow no longer waits on it. The set lives in the host's memory and, as `Watching`, `Settled` and `Released` events, in `units.jsonl` of the session directory beside the unit events. A host that starts again is another session with another directory and starts with none, and what a host that is gone had written and nobody announced is not carried over.

**Cost.** Every 20 seconds, the interval at which a host renews a claim, the host asks the server in one request whether any watched item has another revision than the one it last saw waiting (a batch read of up to 32 items whose reply holds no content), and reads by its ID only what changed, what a link named and what a replaced workflow left. While the session waits on nothing it makes no request. An answer therefore reaches the session's hooks and waiter within 20 seconds of being recorded. A workflow activation costs one workset preview at the next round. No reply to a tool call of the session waits for any of this.

**Who says it.** When the host finds a watched item settled it writes its end, with the title, the status and the answer or confirmation, and gives it to one `cq wait` that runs on the session at that moment, the one of the lowest process identifier, or to none. The decision and the start and end of a waiter exclude each other, so the waiter the end was given to reads it, and a waiter the end was not given to goes on waiting for its units. A session is told:

- by the waiter the end was given to, which ends for it; when units of the session still run, its output names them, and the session starts the waiter again;
- at its next turn end, once, if it has not read the item by then: after a waiter told it, this is a reminder, and after an end no waiter was given, it is the announcement;
- by any `cq wait` that starts later, while it has not read the item: what a waiter that was killed never printed, or what a turn end announced into a reply that was lost, is said then.

The session reading the item through its tools, by `read` with `ItemDetail` or in a batch reply that carries its content, is what ends all of this, and so does the session settling the item itself. The line is `question Q5 "<title>" answered: "<answer>"`, `question Q5 "<title>" withdrawn` or `operator action OA2 "<title>" confirmed: "<confirmation>"`. Title and answer are the person's text on one line: control characters and the line and paragraph separators become spaces, a title is cut at 80 characters and an answer at 300, a cut text ends in `…`, and both are quoted with `\` and `"` escaped, so a title cannot pass for an answer. A blocked stop names at most eight ends by their line and the rest by their reference, so that a directive after them stays whole; whether Claude Code's bound of 10,000 characters for hook output applies to the reason of a blocked stop was not established, and the lines are sized as if it did.

| Harness | At a turn end | While the session works or rests |
| --- | --- | --- |
| Claude Code | The Stop hook blocks the stop once with the lines of the ends the session has not read, for a session with or without a driver; see below how the hook finds its session. | The background `cq wait` ends with exit 0 and the line in its output file, which starts a turn of an idle session. `cq wait` waits while the session waits on a person, also when the host works on nothing. A session that is about to stop while it waits on a person, with nothing running and no waiter, is told once by the Stop hook to start the wait command first. |
| Pi | The extension asks its host when a turn has ended (`cq/settled`) and posts the lines in a `CQ:` message that starts the turn that reads them. Every session, driven or not. | The extension keeps its own `cq wait` running while the session waits on a person and posts what it reports in a `CQ:` message, which starts a turn of an idle session. That waiter reports from the events on that the host had not counted at the last turn end, so nothing settled while it starts is missed. The model starts nothing. |
| Codex | The Stop hook, as for Claude Code. | Nothing: an idle Codex session cannot be woken ([openai/codex#32188](https://github.com/openai/codex/issues/32188)), and no workaround is built. It learns of the answer at the end of its next turn. |

**How a Stop hook finds its session.** The hook is given the session identifier of its harness, which no attached host knows. It finds the session by the process. Every attached host leaves the process that started it, with its start time, beside the lock it holds (`journal/started-by`), and says where its session directory is (`hosts/<session>.live` in the checkout's CQ directory), from the moment it starts and whether or not its session ever records anything. The hook walks its own ancestors, nearest first (`ProcessHandle`, the same source on both sides, so a process identifier that was given again does not match), and takes the first one that started a live host of the checkout: that host's session is the hook's. A harness that a session started in its own shell is therefore given its own host, also while that host has recorded nothing, and never the host of the session around it; in that case, and when the nearest such ancestor started more than one live host, nothing is said. The first hook that finds a host binds its harness session identifier to it (`hosts/<session>.harness`), and a hook that carries another identifier is given nothing of that host: one harness process may serve several sessions, and a harness that gives a session a new identifier while its host lives on is one of them. When no ancestor started a live host, or the host is bound to another identifier, the hook guesses nothing: it then knows only the session a driver of its session key names, one that is on or that stopped for user input, and without such a driver it says nothing about what the session waits on and lets the stop through. That is the case for a hook run by hand, for a harness that runs its hooks or its MCP servers through a process that is not an ancestor of the other, and where the hook cannot read its ancestors. Whether Claude Code and Codex run both as descendants of one harness process has not been observed in a real session. Six JVMs started one after another on this machine read the same start time for one process, to the millisecond; the JDK derives it from the boot time the kernel reports, so a step of the wall clock between the start of a host and a hook could make them differ, and the hook would then find no host.

A failure in this part of the hook, such as a file of another host that cannot be read, is reported in the transcript beside what the driver answered and never instead of it: a directive the driver issued is handed to the session all the same.

**A drive that waits for the answer.** A drive that stopped with `UserInputRequired` is not over: it rests. Each later turn end asks the driver again, and once a person has settled what it waited for the reply is the start directive of the next cycle, in the same blocked stop that announces the answer. Nothing is typed. While the drive rests its session settles no Question and no Operator Action, exactly as while the drive is on, so the drive cannot continue on its own session's decision; a settlement that any other session that is not a person's made ends the rest with a failure that names it. Park ends the rest and lifts that bound. The session reads the answer before it dispatches the work the Question gated, as before.

**Limits.**

- A Stop hook that finds no host started by one of its ancestors, and whose session key has no driver that is on or rests, says nothing at the turn end. The session's `cq wait`, when it runs one, still reports the answer, and the governing instructions tell a Claude Code session to start the wait command before it ends a turn in which it waits on a person.
- A host that the harness starts through a wrapper process that stays between the harness and `cq host`, one that does not `exec` it, is started by that wrapper, which is no ancestor of the hook: the Stop hook of a session that never drove does not find such a host. That session learns of a settled item from `cq wait`, or from the hook once a drive names its session. Start `cq host` directly, as the generated integrations do, or through a wrapper that ends in `exec`.
- An answer recorded less than 20 seconds before a turn end may miss that turn end. It is then reported by the waiter, or at the next turn end.
- Claude Code ends a background command after two hours at most. A session that rests on a Question for longer is started by that end, stops again and is told to start the waiter again: one short turn every two hours for as long as the Question stays open. A session that does not start the waiter is not asked a second time for the same items.
- A session that does not read what it was told is told again: once at its next turn end, and by every `cq wait` it starts. Nothing else repeats.
- A person who reopens a Question that was settled, or changes its answer afterwards, is not announced again: the item left the set when it was settled. It is watched anew when the session writes or links it again, or when the workset of its next workflow waits on it.
- A Question cannot be archived while it is Open, nor an Operator Action while it is Requested, so no item leaves the set that way; the host goes by the status alone.
- A `cq wait` run by hand on a session directory is a waiter like any other: it waits while the session waits on a person and may be the one an end is given to. The session is then told at its next turn end.
- A Pi extension that an earlier package generated cannot say any of this. The host watches nothing for it (the extension says at `cq/session` that it can), and `cq doctor harness pi` reports the generated file as differing: run `cq configure pi --replace`.

## Standing requirements of a project

A session's request reaches the children of that session only, and CQ launches child harnesses with an isolated configuration, so the repository's agent instruction files are not a reliable way to reach them. Rules that must hold in every session of a project, such as its testing policy or an evidence rule, belong in the project's standing requirements. Open the project in the browser, choose *Standing requirements*, edit the text and save it. The host delivers the saved text to the Governor through session Context and workflow activation, and to every Planner, Worker and plan or candidate reviewer of every later dispatch, under its own heading and ahead of the session's request. A workflow activation captures the standing text at activation; a later Context read or activation reads the current saved text. The dialog shows the revision, who changed the text and when. A save based on an older revision is refused and shows the current text beside yours. The text is limited to 8,192 code points, and an empty text means none.

## Process mode of a project

A project works in one of three process modes. The operator chooses it in the browser: the mode shown in the header, or *Process mode* beside *Standing requirements*. Help's *Modes* tab describes each mode and shows the instructions a governing session receives for it.

- **Rigorous** (the default): a Planner plans, a Plan review approves, the phases run in order, and every candidate is independently reviewed.
- **Cross-cutting**: the Governor may skip the Planner and the Plan review, writes each Task and its acceptance criteria itself before a Worker starts, and may take items and phases in any order. Isolated Workers, independent candidate review, the configured host checks and host integration stay mandatory.
- **YOLO cross-cutting**: as Cross-cutting, and the Governor of an interactive session may also make a change itself and review any candidate itself; the configured host checks and host integration stay. It is saved by holding *Switch to YOLO cross-cutting*. A self-reviewed integration is refused while the project configures no check, unless the operator allows *Self-review without checks* in the same dialog, by a second hold: a change the governing session reviewed itself can then be integrated although no check examined it. The exemption exists only with this mode; a change to another mode removes it.

The mode never widens a request: the roots and `through` bound the work in every mode. A change applies from the next workflow activation of a session; a workflow that is already active keeps its mode, and a drive takes the new mode with its next cycle. Only the operator can change the mode. A page that is already open in another browser shows the new mode when its project is loaded again or its mode dialog is opened.

### A workspace of the Governor's own

In the YOLO mode the Governor of an interactive session may make a change itself. It asks the host for a workspace (`OpenWorkspace`), and the host answers with an absolute directory: `<stateRoot>/<session>/workspaces/<attempt>/tree`, a locked detached Git worktree of the kind a Worker gets, outside your checkout. The Governor edits there, hands the workspace back (`SubmitWorkspace`), and the host captures its content as the candidate and runs the configured checks on it. Only what is in that directory can become a candidate. The host cannot see an edit the session makes anywhere else, your checkout included, and does not claim to: the instructions forbid it, and nothing written elsewhere is captured. A workspace the Governor cancels, one whose capture fails and one that is open when its host ends are kept with what was written in them (*quarantined*), never removed; the next host of the project reports them in its cleanup receipt and `cq job upload --session DIR` lists them.

What each harness needs before its session can work in such a directory without being asked:

- **Claude Code.** `cq configure claude` adds two rules to `permissions.allow` in `.claude/settings.local.json`: `Edit(//<stateRoot>/*/workspaces/*/tree/**)` and `Read(//<stateRoot>/*/workspaces/*/tree/**)`. They allow the file tools in the worktree of every workspace of every session under this state root: the Governor's own workspaces, and equally the trees of its children and of the host's checks, in this session and in every other, because the session and the attempt are known only once a workspace exists and no narrower rule can be written beforehand. They cover nothing else under the state root: the host's record and lock beside each tree and the session records stay outside. A state root reached through a symbolic link, its own or one of an ancestor directory, gets both rules for the path as written and for the path it resolves to, also when the state root does not exist yet. `cq configure` adds rules and removes none: after the state root of the settings changed, the rules of the former one stay in `permissions.allow`, with or without `--replace`, until you delete them. A state root whose path contains whitespace, a parenthesis or a pattern character cannot be named in a rule, and `cq configure claude` refuses it. The rules are written for every project, whatever its process mode. They do not make a workspace a working directory of the session's shell: Claude Code keeps the shell in the project and its additional directories, resets it after a command that left them, and asks before a command that changes directory and writes. An additional directory is one literal path, the workspace's path exists only once it is opened, and the only path known beforehand is the whole state root, so `cq configure` writes none. Choose one: approve those commands when Claude Code asks; type `/add-dir <the directory OpenWorkspace returned>` in the session, for that one workspace, after which the prompt offers a rule that can be saved (in the one run observed it did not remove the prompt); or launch with `--add-dir <stateRoot>`, which makes the records of every session of the state root part of the session's working directories. The commands themselves need your own `Bash(...)` rules there as in the checkout.
- **Codex.** `cq configure codex` writes nothing for this. In the `workspace-write` sandbox Codex edits only its working directory and its `writable_roots`, and a root is a literal directory: a pattern grants no write access. The narrowest root that could be configured beforehand is the whole state root, which would let the session's shell write the records of every session, so CQ does not configure it. Approve the edits when Codex asks, or launch with `codex --add-dir <stateRoot>` if you accept that scope.
- **Pi** has no sandbox and asks for no approval of a tool call; nothing is configured.

This was read from the pinned packages (Claude Code 2.1.285, Codex 0.160.0, Pi 0.99.1 and 1.0.0) and is covered by tests of what `cq configure` writes and `cq doctor harness` checks. One real session of each harness has since edited such a workspace (2026-10-07, one run each: this is what happened once, not an established rule):

- **Claude Code 2.1.285.** The generated allow rules matched: Read, Edit and Write in the workspace did not prompt. A shell command that writes, or runs Git, after `cd` into the workspace prompted each time. `/add-dir <the workspace directory>` turned that into a prompt whose rule can be saved; it did not remove the prompt.
- **Codex 0.160.0**, launched with `--sandbox workspace-write --ask-for-approval on-request`: one approval prompt for each editing command.
- **Pi 1.0.0.** No prompt.

Whether Codex honours sandbox keys of a project's `.codex/config.toml` is still not established.

## Agent models of a project

Which models run the planner, worker, explorer and reviewer children is configured per governing harness, as a text: the server's defaults, which hold for every project, and a project's override of the same shape. A key of a roles mapping names a role, or one mode of a role as `role/mode`: with `reviewer: claude:@standard` and `reviewer/plan: codex:@frontier` in one place, plan reviews run the second and candidate and audit reviews the first. The modes are `worker/implement`, `worker/probe`, `worker/resolveconflict`, `explorer/investigate`, `explorer/research`, `reviewer/candidate`, `reviewer/plan` and `reviewer/audit`; a key with another mode is a problem that names the modes of its role. The places are read in their order (the project's roles for the governing harness, the project's defaults, the server's roles for that harness, the server's defaults), and the first one that holds the key of the mode or the key of the role decides: within a place the key of the mode wins, and the key of a role in an earlier place decides before the key of a mode in a later one. For example, `cq agents init` writes `harnesses.codex.roles.reviewer` for Codex; with that key and `defaults.roles.reviewer/plan` in the server defaults, a Codex session runs its plan reviews as `harnesses.codex.roles.reviewer` says, because the server's roles for Codex are read before the server's defaults, and `defaults.roles.reviewer/plan` decides only for a harness without a `reviewer` key of its own. Such a configuration is valid and saved; the dialog and `cq doctor agents` note each key of a mode that can never decide for a harness, with both keys, their positions and the harness (`when codex governs, defaults.roles.reviewer/plan of the server defaults (7:5) never decides: harnesses.codex.roles.reviewer of the server defaults (11:7) is found first and decides every mode of the reviewer role`). Write `harnesses.codex.roles.reviewer/plan` to give Codex its own plan reviewer. A panel is admitted under every key of the reviewer role and under no other key, and a round-robin seat keeps its position for each key apart. Open the project in the browser and choose *Agent models* beside *Process mode*. The dialog has one editor for each text, each with its revision, who changed it and when, and its own Save; a save based on an older revision is refused and shows the current text beside yours, which you keep or replace. An empty project text is the normal case: the project inherits the server defaults.

Under the editors the dialog lists the problems of the texts as typed, each with the line and column it is at, and a table of who would run each role under each governing harness: the models of every seat in the configuration's own syntax, where the role was found (the project's roles for that harness, the project's defaults, the server's roles for that harness, the server's defaults), a note where the governing harness may review its own work, and the roles that do not resolve with the reason. Under the table it notes each key of one mode that an earlier place hides for a governing harness. A mode that a key of its own decides under some governing harness has a row after the row of its role, which then stands for the other modes; each cell of that row names the key that decided it. The server computes both from the unsaved text; while a text has problems the table is not shown. A text with problems is not saved. *How to write it* in the dialog gives the syntax and an example. `cq doctor agents HARNESS --settings FILE` checks the saved configuration against a session's settings file ([doctor](doctor.md#verify-the-agent-model-configuration)).

## Automatic advancement

`/cq:drive <target IDs> through=<phase>` (Codex: `$cq-drive …`) switches a session's auto-driver on, and `/cq:park` (`$cq-park`) switches it off. While it is on, the session keeps running `advance` on the chosen items up to the chosen phase and stops with a stated reason. `cq configure` installs the commands, the Claude Code and Codex hooks, the Claude status line and the Pi toggle key (Ctrl+Alt+A); reconfigure each harness with `--replace` after installing a package that contains the driver. Codex additionally needs its `/hooks` review, and Claude needs `--replace-statusline` when `.claude/settings.local.json` already has a status line of its own.

Real models have been recorded driving real CQ servers and ledgers through child dispatch, on Claude Code 2.1.285 and Codex 0.159.2. The [first driven sessions on the release](validation/crosscut-20261002.md#first-driven-sessions-on-the-release-2026-10-02) took defects through planning and integration. The [real-harness driver cases (T59)](validation/t59-driver-cases-20261004.md) cover two concurrent sessions, start and resume activations, parking and the three `Failure` stops. The readable, credential-free projection of the T59 evidence is in [`validation/evidence/t59-driver/`](validation/evidence/t59-driver/README.md). The governing sessions observed these records in private fixtures, and nobody has independently reread the originals. Pi has not been recorded in those cases against a real server. [Drive CQ work automatically](auto-driver.md) states what was verified and which harness versions it covers. It also covers installation, worksets, the indicators, parking, every stop reason, the limits and the trust boundaries.

To evaluate a harness end to end on a fresh consumer project from a recorded terminal, follow the [evaluation protocol](evaluation-protocol.md). It is a draft: no run has been executed under it.

## Environment and filesystem visibility

On this machine, use `--profile work`: the successful attached-host trial used its authenticated child harnesses; the default profile failed child Claude authentication.

Yolo clears inherited environment variables. An ordinary host-shell `export CQ_TOKEN_FILE=…` is insufficient: use its `--env` option before the harness name.

| Value/path | Required handling |
| --- | --- |
| `CQ_TOKEN_FILE` | Forward the absolute private token-file path. CQ reads the file; configuration files contain no token value. |
| `CQ_TOKEN` | Optional alternative. Takes precedence if both are present. Do not pass it unless intended. |
| `CQ_SETTINGS` | Unnecessary after configuration: the generated integration already includes the absolute settings path. |
| `CQ_ORIGIN` | Unnecessary after `cq init`: the endpoint is stored in project configuration. |
| `CODEX_HOME` | Forward it when using a custom Codex state directory; bind that directory writable. Updated generated MCP configuration forwards it to the host. |
| Checkout | Start yolo there; its current directory is bound writable. CQ needs a committed Git base. |
| Package and settings | Must be readable. They are inside this checkout in the prepared setup. |
| Session state | Must be writable and outside the source checkout. This setup uses `/srv/nvme/tmp/cq4-interactive-sessions`, already visible to this yolo configuration. |
| Harness credentials | Use the normal yolo harness authentication. Managed children receive only the permitted environment and scoped CQ tools. |

For another checkout, initialize and configure it with the installed binary:

```sh
CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token \
  /home/pavel/work/safe/flakes/cq4/.local/release/bin/cq init --endpoint http://vm.home.7mind.io:8080
/home/pavel/work/safe/flakes/cq4/.local/release/bin/cq configure codex \
  --settings /absolute/settings-for-that-project.json
```

Choose that project's validation checks and integration branch in its settings. Observe the integration preconditions above. If that package/settings path is outside the checkout and yolo's existing binds, expose it explicitly:

```sh
yolo --ro /home/pavel/work/safe/flakes/cq4/.local \
  --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex
```

For custom locations, add `--ro /absolute/token`, `--ro /absolute/package`, `--ro /absolute/settings.json` and `--rw /absolute/session-root` as needed. All yolo options precede `codex`, `claude` or `pi`. The token and configured native executables must be accessible in the same sandbox.

For a custom Codex home, additionally use
`--env CODEX_HOME=/absolute/codex-home --rw /absolute/codex-home`. CQ reads the
bound native rollout and writes response ownership under `CODEX_HOME/cq-usage`.
Reconfigure Codex with `--replace` after installing this package so the generated
MCP entry forwards the variable. Ephemeral Codex sessions have no retained
rollout and therefore report unavailable outer usage; CQ tools still work.

Settings select managed-child executable/model/provider/version, deadlines, validation checks and the integration branch. They do not select the interactive model: use the harness's own model controls. Reconfigure after changing the executable/settings path or CQ tool contracts. Existing user-owned `.codex/config.toml` is refused; export into an empty directory and merge the generated `mcp_servers.cq` table manually. `--replace` only updates CQ-generated files/entries.

Troubleshooting: when a startup precondition fails (missing token, missing or invalid settings file, uninitialized project, unverified or mismatching harness version, unreachable CQ server), `cq host` answers the harness's `initialize` with JSON-RPC error `-32003` whose message is one line with the cause and the remedy, writes that line to stderr and exits with code 78. A first request other than `initialize` gets the same error with the message prefixed `CQ host failed to start: `. Any other startup failure is a defect of the host, not a precondition: its full trace stays on stderr, the pending request is answered with `CQ host failed to start: <exception class>; its trace is on the host's standard error`, and the launcher's exit code applies. Claude Code reports a precondition as `Failed to connect — -32003: CQ_TOKEN or CQ_TOKEN_FILE is required; start the harness with CQ_TOKEN_FILE set, see docs/interactive.md` (`claude mcp list`, MCP log) instead of `CONNECTION_CLOSED`.

## Retained candidates across governing sessions

A later governing session can review or integrate a retained candidate using the earlier result artifact handles. It must acquire a fresh claim covering exactly the candidate's Tasks. The host validates the original accepted admissions and publishers, unchanged member content, independent review and validation evidence; the integration uses the new session's owner and claim. Existing candidate/base and target-branch checks still apply. Keep the retained artifact handles and candidate Git refs available; this does not automatically discover earlier candidates or adopt earlier processes. Source verification is recorded in [D114 evidence](validation/remaining-defects-20261003.md).

## Shutdown and accounting

Ending the harness ends its CQ host and managed hierarchy. Freezing the native owner is detected through the heartbeat deadline; reconnecting creates a fresh session and does not adopt uncertain children. A session has no absolute lifetime: it runs for as long as its harness does, and the host renews its own server credentials meanwhile.

Some native clients terminate their MCP host before it finishes its final audit publication. Preserve the session directory, which `session Context` returns. After the owner has stopped, recover pending records with:

```sh
CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token \
  ./.local/release/bin/cq job upload --session /srv/nvme/tmp/cq4-interactive-sessions/SESSION_UUID
```

Recovery never launches/adopts a process. Repeated completed recovery acknowledges zero batches. An incomplete retained record is reported explicitly after valid records are replayed; it is not silently discarded.

### Closing open governing attempts

The usage view and `cq status` count a governing attempt of an attached session that has no outcome as **open**, apart from running attempts: the attempt started and no outcome was delivered. CQ does not observe the operator's harness, so the server cannot tell a live session from one whose host was killed before its final delivery. A host that starts later for the same project and repository delivers the missing outcome for every ended session whose directory is still under its session root, once its own session has made its first governing request (a tool call, a Pi response's usage record or a request to the Pi driver; [usage audit](design/usage-audit.md)): a harness that only opens the connection to CQ registers no attempt and recovers nothing. A directory that was moved elsewhere (for example aside into an archive directory at an update) is never visited, and its attempt stays open until it is uploaded.

To close them, with no harness open on the project:

1. List the open attempts: *Project usage* → *Attempts*, state `Open`; *Attempt details* names the session UUID. `cq status attempts` lists the same state and the session with `--json`.
2. For each one, find the directory named by that session UUID, in the session root or wherever it was moved. It must still hold `run.json`, `journal/` and `delivery/`.
3. Run `cq job upload --session DIR` for it with the operator credential, as above. The upload needs the server named in the directory's `run.json` to be the one that holds the attempt, and a `run.json` that the installed package can still read.
4. Reload the usage view. The attempt now has outcome `Unknown` with the gap `Attached owner observation interrupted; …`, and the open count has dropped.

The outcome's finish time is the time of the upload, not the time the session ended, which nothing recorded. The Govern phase's busy wall time therefore includes the interval between the session's end and the upload. An attempt whose session directory no longer exists, or cannot be read by the installed package, stays open: nothing can deliver its outcome, and the report says no more of it than that none was delivered.

Managed children retain their task/cohort token and cost accounting. With the
updated package, observed Codex 0.156.1/0.157.1/0.159.2 native response records (0.160.0 is accepted, its records not yet observed) contribute
to the outer session's unattributed usage, deduplicated across host restarts.
Outer task/model grouping and cost remain unknown. Claude outer usage remains
unavailable; Pi observes finalized assistant usage. Auxiliary, compaction,
unreported and final-tail work remain explicitly incomplete. Unknown usage does
not mean zero cost. See [the Codex accounting boundary](validation/attached-codex-usage.md).

See [verification evidence and remaining acceptance](validation/attached-host.md).
