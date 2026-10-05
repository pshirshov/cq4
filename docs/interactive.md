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
yolo --profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token pi --approve --no-prompt-templates --prompt-template .pi/prompts
```

`cq configure claude` merges the `cq` server into `.mcp.json` and records its approval by adding `"cq"` to `enabledMcpjsonServers` in `.claude/settings.local.json`. It also removes `"cq"` from `disabledMcpjsonServers` there, which Claude writes when the server was once declined and which overrides the approval. Other keys and entries in both files are kept. Launch Claude with `--setting-sources project,local`: interactive Claude loads a project `.mcp.json` server only after it has been approved, and both this entry and the approval Claude itself saves are in the local settings source. With `--setting-sources project` it asks "New MCP server found in this project: cq" on every launch and still does not load the server. The `local` source is this checkout's `.claude/settings.local.json`, so personal (user) commands remain excluded.

### Waiting for children

A governing session does not poll a child's status while the child runs. What it does instead depends on the harness:

- **Claude Code** runs `cq wait --session <session directory> --attempt <ID>` as a background command and may end its turn; the session starts again when the command exits. `cq configure claude` allows exactly that command (`Bash(<executable> wait *)` in `.claude/settings.local.json`), so no permission prompt appears. A background command lives at most two hours; a child that runs longer is waited for again. Leaving Claude Code while a waiter runs raises its "Background work is running" dialog: the waiter can be stopped, it only reads.
- **Pi**: the CQ extension waits itself and posts `CQ: attempt … ended: …` into the session, which starts a turn. Nothing is typed and no command is run by the model.
- **Codex** runs the same command as one blocking shell call inside its turn, because nothing wakes an idle Codex session when a background command exits ([openai/codex#32188](https://github.com/openai/codex/issues/32188)). `cq configure codex` allows it in `.codex/rules/cq.rules`. The session shows its busy text while it waits.

`cq wait` reads the session directory only; it needs no token. Run by hand it tells whether a session still has work in flight: `cq wait --session DIR` returns at once when it has none. An integration generated by an earlier package starts the host without its executable; such a session waits through status calls as before, so run `cq configure <harness> --replace` after installing this package.

Claude/Pi workflow commands are `/cq:begin`, `/cq:advance`, `/cq:review`, `/cq:upstream`. The Claude settings selector and Pi prompt selector avoid the older personal CQ commands installed on this machine. Pi `--approve` trusts project files; `--no-approve` disables them, including CQ's extension.

## Standing requirements of a project

A session's request reaches the children of that session only, and CQ launches child harnesses with an isolated configuration, so the repository's agent instruction files are not a reliable way to reach them. Rules that must hold in every session of a project, such as its testing policy or an evidence rule, belong in the project's standing requirements. Open the project in the browser, choose *Standing requirements*, edit the text and save it. The host delivers the saved text to the Governor through session Context and workflow activation, and to every Planner, Worker and plan or candidate reviewer of every later dispatch, under its own heading and ahead of the session's request. A workflow activation captures the standing text at activation; a later Context read or activation reads the current saved text. The dialog shows the revision, who changed the text and when. A save based on an older revision is refused and shows the current text beside yours. The text is limited to 8,192 code points, and an empty text means none.

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

The usage view and `cq status` count a governing attempt of an attached session that has no outcome as **open**, apart from running attempts: the attempt started and no outcome was delivered. CQ does not observe the operator's harness, so the server cannot tell a live session from one whose host was killed before its final delivery. A host that starts later for the same project and repository delivers the missing outcome for every ended session whose directory is still under its session root. A directory that was moved elsewhere (for example aside into an archive directory at an update) is never visited, and its attempt stays open until it is uploaded.

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
