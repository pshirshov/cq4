# Run CQ inside your normal harness

The interactive harness is the Governor. It starts `cq host` automatically; that host owns CQ worktrees, guardians and dispatched children. The durable CQ server remains separate. `cq run` remains available for batch work.

## This checkout and this machine

Keep the server running with `./run-local.sh` in a separate host terminal. After a package update, stop it with Ctrl-C, wait for `CQ stopped`, and start it again.

The local integration uses `.local/release/bin/cq` and `.local/interactive/settings.json`. The prepared settings enable reviewed integration into `refs/heads/main` and declare `cq-ui`: `nix develop -c ./dev/check ui` (ten-minute deadline). This runs scoped UI/browser verification without the model evaluation matrix. Backend/CLI changes need appropriate additional checks before their implementation; this UI check is not evidence of backend correctness. All three integrations are installed in this checkout. On a fresh checkout, setup installs project-local commands and native configuration:

```sh
cd /home/pavel/work/safe/cq4/cq4
./.local/release/bin/cq configure codex --settings "$PWD/.local/interactive/settings.json"
```

### Integration into main

CQ refuses to update an integration target that is checked out in **any** worktree. This checkout is prepared in detached HEAD at the committed `main` tip; `main` remains the integration target. CQ workers edit isolated worktrees, and reviewed integration advances `main` without modifying the governing checkout's files.

Before a new session, with the previous harness stopped and your working tree clean:

```sh
git switch --detach main
git worktree list --porcelain
```

The listing must contain no `branch refs/heads/main` entry. If another worktree has `main` checked out, detach it or switch it to another branch before integrating. Preserve any uncommitted work; do not discard it. After integration, `git diff HEAD main` shows the changes; stop the harness and run `git switch --detach main` to refresh the governing checkout before starting the next session. The session's committed base is fixed at startup. To resume ordinary manual development after CQ stops, `git switch main` reattaches the checkout.

Launch directly from this detached checkout:

```sh
yolo --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex
```

Accept Codex's project-trust prompt. Select the **project** `.agents/skills/cq-begin/SKILL.md` if both personal and project versions are listed. For example:

```text
Use the project .agents/skills/cq-begin/SKILL.md.
Capture these defects and plan the work; ask before implementing: …
```

To try another harness, configure its integration once:

```sh
./.local/release/bin/cq configure claude --settings "$PWD/.local/interactive/settings.json"
yolo --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token claude --setting-sources project

./.local/release/bin/cq configure pi --settings "$PWD/.local/interactive/settings.json"
yolo --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token pi --approve --no-prompt-templates --prompt-template .pi/prompts
```

Claude/Pi workflow commands are `/cq:begin`, `/cq:advance`, `/cq:review`, `/cq:upstream`. The Claude settings selector and Pi prompt selector avoid the older personal CQ commands installed on this machine. Pi `--approve` trusts project files; `--no-approve` disables them, including CQ's extension.

## Environment and filesystem visibility

Yolo clears inherited environment variables. An ordinary host-shell `export CQ_TOKEN_FILE=…` is insufficient: use its `--env` option before the harness name.

| Value/path | Required handling |
| --- | --- |
| `CQ_TOKEN_FILE` | Forward the absolute private token-file path. CQ reads the file; configuration files contain no token value. |
| `CQ_TOKEN` | Optional alternative. Takes precedence if both are present. Do not pass it unless intended. |
| `CQ_SETTINGS` | Unnecessary after configuration: the generated integration already includes the absolute settings path. |
| `CQ_ORIGIN` | Unnecessary after `cq init`: the endpoint is stored in project configuration. |
| Checkout | Start yolo there; its current directory is bound writable. CQ needs a committed Git base. |
| Package and settings | Must be readable. They are inside this checkout in the prepared setup. |
| Session state | Must be writable and outside the source checkout. This setup uses `/srv/nvme/tmp/cq4-interactive-sessions`, already visible to this yolo configuration. |
| Harness credentials | Use the normal yolo harness authentication. Managed children receive only the permitted environment and scoped CQ tools. |

For another checkout, initialize and configure it with the installed binary:

```sh
CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token \
  /home/pavel/work/safe/cq4/cq4/.local/release/bin/cq init --endpoint http://vm.home.7mind.io:8080
/home/pavel/work/safe/cq4/cq4/.local/release/bin/cq configure codex \
  --settings /absolute/settings-for-that-project.json
```

Choose that project's validation checks and integration branch in its settings. Keep that branch unchecked out across all worktrees, as described above. If that package/settings path is outside the checkout and yolo's existing binds, expose it explicitly:

```sh
yolo --ro /home/pavel/work/safe/cq4/cq4/.local \
  --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex
```

For custom locations, add `--ro /absolute/token`, `--ro /absolute/package`, `--ro /absolute/settings.json` and `--rw /absolute/session-root` as needed. All yolo options precede `codex`, `claude` or `pi`. The token and configured native executables must be accessible in the same sandbox.

Settings select managed-child executable/model/provider/version, deadlines, validation checks and the integration branch. They do not select the interactive model: use the harness's own model controls. Reconfigure after changing the executable/settings path or CQ tool contracts. Existing user-owned `.codex/config.toml` is refused; export into an empty directory and merge the generated `mcp_servers.cq` table manually. `--replace` only updates CQ-generated files/entries.

## Shutdown and accounting

Ending the harness ends its CQ host and managed hierarchy. Freezing the native owner is detected through the heartbeat deadline; reconnecting creates a fresh session and does not adopt uncertain children. A session has an eight-hour absolute lifetime.

Some native clients terminate their MCP host before it finishes its final audit publication. Preserve the session directory, which `session Context` returns. After the owner has stopped, recover pending records with:

```sh
CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token \
  ./.local/release/bin/cq job upload --session /srv/nvme/tmp/cq4-interactive-sessions/SESSION_UUID
```

Recovery never launches/adopts a process. Repeated completed recovery acknowledges zero batches. An incomplete retained record is reported explicitly after valid records are replayed; it is not silently discarded.

Managed children retain their operational token/cost accounting. Claude/Codex interactive outer usage is unavailable; Pi observes finalized assistant usage, with auxiliary/compaction/interrupted work still marked incomplete. Unknown usage does not mean zero cost.

See [verification evidence and remaining acceptance](validation/attached-host.md).
