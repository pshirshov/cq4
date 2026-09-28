# Try the native release locally

This walkthrough starts a persistent local CQ server and drives a small Go project with the configured Codex harness. The server launcher makes no model calls. The later `cq run` commands do.

## 1. Start CQ — terminal one

On this machine, the verified native package and its Nix runtime libraries are already present:

```sh
cd /home/pavel/work/safe/cq4/cq4
nix develop -c bash docs/examples/launch-local.sh \
  /srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources \
  /srv/nvme/tmp/cq4-playground
```

The [launcher](examples/launch-local.sh) creates a private PostgreSQL cluster, generates persistent credentials, starts the native server and waits for its authenticated health response. CQ listens on **0.0.0.0:8080**. Leave this terminal open. PostgreSQL listens on loopback port 55432 with password authentication. To choose other ports, prefix the command with `CQ_LOCAL_PORT=8081 CQ_LOCAL_DB_PORT=55433`.

For a browser on another machine, set `CQ_ORIGIN` to the exact browser URL, including scheme and port (without a trailing slash). For example, on this host:

```sh
CQ_ORIGIN=http://vm.home.7mind.io:8080 \
nix develop -c bash docs/examples/launch-local.sh \
  /srv/nvme/tmp/cq4-implementation/cq-release-workflow-resources \
  /srv/nvme/tmp/cq4-playground
```

Use that same URL in the browser. CQ checks browser origins; binding all interfaces alone does not change the permitted origin. Startup health checks always use loopback, so they do not require the browser hostname to resolve locally. CLI configuration in `client.env` uses the configured origin. Changing an existing instance requires stopping and restarting the launcher.

With no `CQ_ORIGIN` override, open **http://127.0.0.1:8080**. Obtain the browser login token in another terminal:

```sh
cat /srv/nvme/tmp/cq4-playground/token
```

State, credentials, logs and subsequent session journals live under `/srv/nvme/tmp/cq4-playground`. Reusing the launch command preserves them. A concurrently running launcher or database in the same state directory is rejected. On another machine, first install the package's `runtime.nar` as described in its README. The launcher requires Linux, Bash, Python, curl, flock and PostgreSQL; `nix develop` supplies the pinned PostgreSQL and Go tools here.

## 2. Prepare an unrelated project — terminal two

```sh
cd /home/pavel/work/safe/cq4/cq4
nix develop
source /srv/nvme/tmp/cq4-playground/client.env
mkdir "$CQ_LOCAL_STATE/greeting"
cd "$CQ_LOCAL_STATE/greeting"
git init -b main
printf '# Greeting demo\n\nA small Go command, managed through CQ.\n' > README.md
git add README.md
git commit -m 'Start greeting demo'
git branch cq-result
cq init --endpoint "$CQ_ORIGIN" --name 'Greeting demo'
```

Use your normal Git identity; if `git commit` asks for one, configure it for this demo repository and repeat the commit. `cq-result` is deliberately **not checked out**: CQ's reviewed integration updates that branch without changing your working files or index.

Create private supervisor settings from the installed example. This selects your installed Codex executable, the configured `gpt-6-sol` model, a 15-minute run limit and the named Go test command:

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
settings['limits'].update(executionMillis='900000', outputBytes=8388608)
settings['checks'] = [{'name': 'go-tests', 'command': [go, 'test', './...'],
                       'executionMillis': '120000', 'outputBytes': 262144}]
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

Shutdown gives CQ ten seconds after SIGTERM, then sends SIGKILL if needed and observes it for up to five more seconds. Unconfirmed server settlement exits 75 and leaves PostgreSQL available for investigation; it does not report successful cleanup. PostgreSQL's own shutdown wait is bounded at thirty seconds.

To return, repeat step 1. In terminal two enter `nix develop`, source `client.env`, return to the existing `greeting` checkout and export `CQ_SETTINGS` again. Skip project/settings creation and continue from the existing records. Logs are in `logs/cq-server.log`, `logs/postgres.log` and your run-specific files; session directories are under `sessions/`.
