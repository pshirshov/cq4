# Try the native release locally

This walkthrough starts a persistent local CQ server and drives a small Go project with the configured Codex harness. The server launcher makes no model calls. The later `cq run` commands do.

## 1. Start CQ — terminal one

The permanent launcher and native package are in this repository:

```sh
cd /home/pavel/work/safe/flakes/cq4
./run-local.sh
```

You can also invoke `/home/pavel/work/safe/flakes/cq4/run-local.sh` from any directory. The [wrapper](../run-local.sh) enters the pinned Nix environment and starts the package at `.local/release` through the [database/server launcher](examples/launch-local.sh). It generates persistent credentials on first use and waits for authenticated server readiness.

CQ listens on **0.0.0.0:8080**; open **http://vm.home.7mind.io:8080**. Leave the terminal open. PostgreSQL listens on loopback port 55432 with password authentication. Obtain the browser login token in another terminal:

```sh
cat /srv/nvme/tmp/cq4-playground/token
```

Data freshness, usage totals for the selected scope and observation time appear in the bottom status bar. Hover over truncated usage text to read its complete value.

State, credentials, logs and subsequent session journals stay under `/srv/nvme/tmp/cq4-playground`. Reusing the command preserves them. Stop an existing launcher with Ctrl-C before starting this one, then reload the browser to load the current UI. Re-source `client.env` in existing CLI terminals to select the current package.

For another browser URL, set `CQ_ORIGIN` to its exact scheme, hostname and port, without a trailing slash. For example, for a browser on this machine:

```sh
CQ_ORIGIN=http://127.0.0.1:8080 ./run-local.sh
```

Optional overrides are `CQ_LOCAL_STATE`, `CQ_LOCAL_PORT` and `CQ_LOCAL_DB_PORT`. If changing the HTTP port, the default origin uses that port; an explicit `CQ_ORIGIN` must match. CQ checks browser origins. Startup health checks use loopback, so they do not require the browser hostname to resolve locally. CLI configuration in `client.env` uses the configured origin. Changing an existing instance requires stopping and restarting its launcher.

The wrapper selects this machine's already-built package. On another machine, build/install a distribution and import its `runtime.nar` as described in the package README, then use `docs/examples/launch-local.sh RELEASE_DIR STATE_DIR` inside `nix develop`. Linux, Bash, Python, curl, flock and PostgreSQL are required; the pinned environment supplies PostgreSQL and Go here. [Human evaluation corrections](validation/human-evaluation.md#actual-operator-delivery-verification) record the current delivery; [compact UI and launcher verification](validation/compact-ui.md) and [HTTP login verification](validation/http-ui.md) retain earlier evidence.

If startup reports “A launcher already owns”, another process holds the state lock. Stop the original launcher before restarting; do not delete `launcher.lock`. Earlier helper revisions could leak this lock into PostgreSQL after launcher termination. The corrected helper prevents that inheritance, but an already-running orphaned database needs identified, explicit cleanup before relaunch. [Reproduction and correction](validation/local-quickstart.md#detached-database-lock-inheritance).

## 2. Prepare an unrelated project — terminal two

```sh
cd /home/pavel/work/safe/flakes/cq4
nix develop
source /srv/nvme/tmp/cq4-playground/client.env
mkdir "$CQ_LOCAL_STATE/greeting"
cd "$CQ_LOCAL_STATE/greeting"
git init -b main
printf '# Greeting demo\n\nA small Go command, managed through CQ.\n' > README.md
git add README.md
git commit -m 'Start greeting demo'
git branch cq-result
cq init --name 'Greeting demo'
```

Use your normal Git identity; if `git commit` asks for one, configure it for this demo repository and repeat the commit. `cq-result` is deliberately **not checked out**: CQ's reviewed integration updates that branch without changing your working files or index.

Create private supervisor settings from the installed example. This selects your installed Codex executable, the configured `gpt-6-sol` model and the named Go test command with its two-minute deadline. The governing harness and its children have no run limit; cancel a session with Ctrl-C or a child through the governor:

```sh
python3 - <<'PY'
import json, os, pathlib, shutil, subprocess
release = pathlib.Path(os.environ['CQ_BIN']).parent.parent
state = pathlib.Path(os.environ['CQ_LOCAL_STATE'])
codex, go = shutil.which('codex'), shutil.which('go')
assert codex and go, 'Codex and Go must be on PATH'
version = subprocess.check_output([codex, '--version'], text=True).strip()
assert version.startswith('codex-cli '), version
settings = json.loads((release / 'examples/supervisor.json').read_text())
settings['guardian'] = str(release / 'bin/cq-guardian')
settings['stateRoot'] = str(state / 'sessions')
settings['integrationTarget'] = 'refs/heads/cq-result'
settings['harnesses'][0].update(executable=codex, version=version.removeprefix('codex-cli '))
settings['limits'].update(retainedOutputBytes=8388608)
settings['checks'] = [{'name': 'go-tests', 'command': [go, 'test', './...'],
                       'executionMillis': '120000', 'retainedOutputBytes': 262144}]
with (state / 'supervisor.json').open('x') as output:
    json.dump(settings, output, indent=2)
PY
export CQ_SETTINGS="$CQ_LOCAL_STATE/supervisor.json"
cq commands export codex --directory "$PWD"
```

The settings contain only the Codex route; it can fill each of the four CQ roles. Codex must already be authenticated. Exported skills are optional for the direct CLI walkthrough below. If using an interactive Codex session instead, select the project-local `.agents/skills/cq-begin/SKILL.md` explicitly to avoid older personal skills with the same name; provide `CQ_SETTINGS` and the intended scope.

## 3. Capture the request, then advance it

```sh
cat > "$CQ_LOCAL_STATE/begin.txt" <<'EOF'
Build a tiny Go greeting CLI using only the standard library.
Use a root Go module and support `go run .` => `Hello, world!` plus newline,
and `go run . Ada` => `Hello, Ada!` plus newline.
More than one argument must print a usage message to stderr and exit nonzero.
Include automated tests for these cases and a README with exact run/test commands.
Keep the work small. Capture and plan this request through CQ; do not implement yet.
Only Codex is configured for governing and child roles. Use the named go-tests check.
EOF
cq run codex --settings "$CQ_SETTINGS" --input "$CQ_LOCAL_STATE/begin.txt" \
  --workflow begin > "$CQ_LOCAL_STATE/begin-receipt.json" \
  2> "$CQ_LOCAL_STATE/begin.log"
cat "$CQ_LOCAL_STATE/begin-receipt.json"
cq query --query 'archived:all' --limit 20
```

Watch the **Greeting demo** project in the browser. Inspect the returned report for questions, blockers and actual root IDs. A fresh project normally starts with `I1`; use the root actually returned, rather than assuming that number. If there is a question, supply your real answer in the next input file with its question ID. Process exit alone does not establish task acceptance.

Edit the input below as needed, then advance the chosen root through reviewed integration:

```sh
cat > "$CQ_LOCAL_STATE/advance.txt" <<'EOF'
Continue the selected greeting work through planning, implementation, fresh named
go-tests validation, independent candidate review and recorded integration into
the configured cq-result branch. Use Codex for all child roles. Preserve separate
task identities and return actual questions or blockers if more input is required.
EOF
cq run codex --settings "$CQ_SETTINGS" --input "$CQ_LOCAL_STATE/advance.txt" \
  --workflow advance --roots I1 --through integrate \
  > "$CQ_LOCAL_STATE/advance-receipt.json" 2> "$CQ_LOCAL_STATE/advance.log"
cat "$CQ_LOCAL_STATE/advance-receipt.json"
cq status
cq status attempts --limit 20
```

Replace `I1` above if the receipt selected another root. A bounded run may return remaining work; read that report before continuing. Preserve each receipt/log under a new filename when repeating a command. Use `cq status --task T1` or the browser's task/cohort usage views for scoped accounting.

After the report and CQ history show Recorded integration, inspect and adopt the result:

```sh
git log --oneline main..cq-result
git diff main..cq-result
git merge --ff-only cq-result
go test ./...
go run . Ada
```

Expected last output: `Hello, Ada!`. Your checkout stays unchanged until the explicit merge.

## 4. Stop or return later

Let consumer runs finish (or stop and reconcile them using the [operations guide](design/operations.md#stop-and-reconcile-a-session)) before stopping the server. Press **Ctrl-C in terminal one** to stop CQ and its private PostgreSQL instance. Data is retained; no cleanup command deletes it.

Shutdown gives CQ ten seconds after SIGTERM, then sends SIGKILL if needed and observes it for up to five more seconds. Unconfirmed server settlement exits 75 and leaves PostgreSQL available for investigation; it does not report successful cleanup. PostgreSQL's own shutdown wait is bounded at thirty seconds. Repeated Ctrl-C presses during cleanup are ignored so they cannot strand the database. Cleanup diagnostics are retained in `logs/launcher-cleanup.log`, even if a terminal output pipe closes.

To return, repeat step 1. In terminal two enter `nix develop`, source `client.env`, return to the existing `greeting` checkout and export `CQ_SETTINGS` again. Skip project/settings creation and continue from the existing records. Logs are in `logs/cq-server.log`, `logs/postgres.log` and your run-specific files; session directories are under `sessions/`.

`cq init` resolves its endpoint from `--endpoint`, then existing repository configuration, then `CQ_ORIGIN`, then `CQ_ENDPOINT`. After sourcing `client.env`, a new repository only needs `cq init --name "My project"`.

## Browse and archive completed work

Click anywhere on a table row to select it. Use **Last modified** to sort by the
latest revision timestamp. Query suggestions and diagnostics appear in a popup
while the filter field has focus.

Apply your filter, then choose **Archive terminal items**. The preview includes
only unarchived terminal items matching that applied filter, including cancelled
or rejected outcomes. Confirm the displayed revisions. A batch contains at most
512 items; limited previews are labelled explicitly. A stale or unavailable item
rejects the entire batch. If the connection drops during confirmation, reopen
the action and use **Retry exact archive** to recover the retained request.

Archived items must remain terminal. Unarchive an item before reopening it, or
change both fields together. Prior revisions remain available in history.

**Project usage → Attempts** and **Usage audit** show tables. Expand a row's
details for provenance, coverage gaps, individual counters and scope navigation.
Unknown measurements and costs remain explicit.
