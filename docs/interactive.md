# Run CQ inside your normal harness

The interactive harness is the Governor. It starts `cq host` automatically; that host owns CQ worktrees, guardians and dispatched children. The durable CQ server remains separate. `cq run` remains available for batch work.

## This checkout and this machine

Keep the server running with `./run-local.sh` in a separate host terminal. After a package update, stop it with Ctrl-C, wait for `CQ stopped`, and start it again.

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

Claude/Pi workflow commands are `/cq:begin`, `/cq:advance`, `/cq:review`, `/cq:upstream`. The Claude settings selector and Pi prompt selector avoid the older personal CQ commands installed on this machine. Pi `--approve` trusts project files; `--no-approve` disables them, including CQ's extension.

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

Troubleshooting: when a startup precondition fails (missing token, missing or invalid settings file, uninitialized project, unverified or mismatching harness version, unreachable CQ server), `cq host` answers the harness's `initialize` with JSON-RPC error `-32003` whose message is one line with the cause and the remedy, writes that line to stderr and exits with code 78. Claude Code reports it as `Failed to connect — -32003: CQ_TOKEN or CQ_TOKEN_FILE is required; start the harness with CQ_TOKEN_FILE set, see docs/interactive.md` (`claude mcp list`, MCP log) instead of `CONNECTION_CLOSED`.

## Shutdown and accounting

Ending the harness ends its CQ host and managed hierarchy. Freezing the native owner is detected through the heartbeat deadline; reconnecting creates a fresh session and does not adopt uncertain children. A session has an eight-hour absolute lifetime.

Some native clients terminate their MCP host before it finishes its final audit publication. Preserve the session directory, which `session Context` returns. After the owner has stopped, recover pending records with:

```sh
CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token \
  ./.local/release/bin/cq job upload --session /srv/nvme/tmp/cq4-interactive-sessions/SESSION_UUID
```

Recovery never launches/adopts a process. Repeated completed recovery acknowledges zero batches. An incomplete retained record is reported explicitly after valid records are replayed; it is not silently discarded.

Managed children retain their task/cohort token and cost accounting. With the
updated package, observed Codex 0.156.1/0.157.1/0.159.2 native response records contribute
to the outer session's unattributed usage, deduplicated across host restarts.
Outer task/model grouping and cost remain unknown. Claude outer usage remains
unavailable; Pi observes finalized assistant usage. Auxiliary, compaction,
unreported and final-tail work remain explicitly incomplete. Unknown usage does
not mean zero cost. See [the Codex accounting boundary](validation/attached-codex-usage.md).

See [verification evidence and remaining acceptance](validation/attached-host.md).
