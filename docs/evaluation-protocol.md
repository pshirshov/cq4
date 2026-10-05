# Evaluate CQ through driven interactive harness sessions

This is the protocol and the environment for an evaluation the operator requests from their own Claude session: that session, outside CQ, drives a fresh Claude Code, Codex or Pi session in a real terminal through the definition of a new consumer project, its full planning, its implementation and a follow-up. It answers as the operator's proxy within stated limits, watches for failures, unwarranted stops and inefficient token use, and records what it finds as CQ Defects or Ideas with evidence and measured cost.

The page defines protocol and environment only (Goal G4, Idea I12, Question Q10). It specifies no individual run and no run was executed for it. The operator's answers to Q10–Q16 are binding and are quoted where they decide a rule. Q37–Q45 additionally settle the default specification, routes, driver mode, proxy permissions, monitoring and report location; their recorded answers are listed below.

**Status: draft, awaiting independent review; not ready.** Independent Audit d3fe8373-5802-36ab-997f-9cdfc73f60f4 confirmed all nine preference answers and requested the operational corrections applied here. Both research items have since concluded, on 2026-10-04:

- **RS2 (revision 4).** All three pinned harnesses launched from a host-level private tmux launcher and connected to a private real CQ server. Each recorded a small Begin, exited 0, uploaded, and returned tagged usage.
- **RS3 (revision 7) and RS15 (revision 4).** These concluded that the Claude transcript and `/cost` are separate, partial sources. The counter discrepancies are unexplained, and the original-source and auxiliary/subagent/compaction coverage gaps remain explicit.

The `claude-outer` missing-counter correction (D142, T71) is integrated: T71 is Done through integration `bd9e2c6b-711b-4417-a2c2-cf9e5c53259b` at `e4ae1e0`, and D142 is Resolved. Independent acceptance of this corrected protocol is still required. Concluded research and preparatory probes do not establish formal I12 qualification, full matrix qualification, complete billing or G4 readiness. See [Readiness](#8-readiness-and-what-is-not-verified).

## Acceptance criteria of G4

| # | Criterion (abridged) | Section |
| --- | --- | --- |
| 1 | Driving environment: private tmux control, asciinema v3 recording, launch per harness with commands, flags, token and settings files, prompts and the check that the `cq` host is connected; existing and new capabilities told apart | [1. Driving environment](#1-driving-environment) |
| 2 | Consumer-project definition: fresh repository, isolated server and token, `cq init`, `cq configure`, committed base, declared checks, integration branch, evaluation `{run, scenario}` tag; how the specification is chosen and given | [2. Consumer-project definition](#2-consumer-project-definition) |
| 3 | Operator-proxy answering rules: what the proxy answers, what it escalates, the label, no fabricated evidence | [3. Operator-proxy answering rules](#3-operator-proxy-answering-rules) |
| 4 | Monitoring criteria with operational definitions and thresholds: failures, unwarranted stops with and without the auto-driver, inefficient token use | [4. Monitoring criteria](#4-monitoring-criteria) |
| 5 | Evidence and recording: every session recorded, each problem a Defect or an Idea with citation, duplicates checked | [5. Evidence and recording rules](#5-evidence-and-recording-rules) |
| 6 | Cost measurement per harness: child usage through `EvaluationOnly`, outer usage from the best source, unknown never zero, report format | [6. Cost measurement](#6-cost-measurement) |
| 7 | Dependency handling for D67, D73 and G1, and how each is recorded in the run report | [7. Dependency handling](#7-dependency-handling) |
| 8 | Preference choices resolved through Questions, unknowns through Research, protocol reviewed, no individual run specified | [8. Readiness and what is not verified](#8-readiness-and-what-is-not-verified) |

## Terms

| Term | Meaning |
| --- | --- |
| Driver session | The operator's own Claude session that carries out this protocol. It is not a CQ session. |
| Harness session | The fresh Claude Code, Codex or Pi session being evaluated. It is the CQ Governor of the consumer project. |
| Operator-proxy | The driver session when it answers or approves in the harness session on the operator's behalf. |
| Run | One evaluation of one harness, from launch to report. It is identified by `{run, scenario}`. |
| Auto-driver | The CQ feature of [Drive CQ work automatically](auto-driver.md). The driver session is a different thing: it types into the terminal. |
| Stop | The harness session ends its turn and waits for input. |

A run has six stages. The request for a run may restrict them.

| Stage | Content | Ends when |
| --- | --- | --- |
| S0 Launch | Environment, consumer project, harness launch, `cq` connected | The connection check passes. |
| S1 Definition | The specification is given to the session with the begin workflow | The intake items exist in the consumer project. |
| S2 Planning | Advance through `plan`; the proxy answers Questions | Every root has an applied plan or an escalated Question. |
| S3 Implementation | Advance through `integrate`, unattended | Every planned Task is terminal, or a stop rule ends the run. |
| S4 Follow-up | The follow-up request of the specification: begin, then advance through `integrate` | As S3. |
| S5 Measurement | Teardown, `cq job upload`, usage, acceptance checks, report, Defects and Ideas | The report is written. |

## 1. Driving environment

### Existing and new capabilities

| Capability | State | Where |
| --- | --- | --- |
| Private tmux server, keystrokes in and rendered screen out | Existing practice, used for the governing sessions recorded from 2026-09-30 to 2026-10-02 | Wrapper `/srv/nvme/tmp/cq4-auto-driver-planning/gov/t`: `tmux -S /tmp/cqgov.sock "$@"` with tmux 3.7c |
| asciinema v3 recording of the whole session | Existing practice | `gov/start.sh`: `asciinema rec … -c <launcher>`; 18 recordings `gov/*.cast`, header `"version":3`, 200×55 |
| Start, connection check and submission of a request | Existing practice, Claude Code only, private paths hard-coded | `gov/start.sh` |
| Waiting for idle, a dialog or new narration | Existing practice, Claude Code screen texts | `gov/watch.sh`, `gov/watch2.sh`, `gov/wait.sh` |
| Launchers | Existing practice for Claude Code and Codex in the CQ checkout | `/srv/nvme/tmp/cq4-auto-driver-planning/launch.sh`, `launch-codex.sh`; for Pi only the stub probe launcher of [Pi extension driver](validation/pi-driver.md) |
| `dev/eval-session` | **New**, parameterised form of `gov/t`, of the session start in `gov/start.sh` and of `gov/watch.sh`. The connection check and the submission stay steps of the driver session, made of `type`, `keys` and `screen`. | This change. Syntax-checked, not run. |
| `dev/eval-launch` | **New**, parameterised form of the two launchers plus Pi | This change. Syntax-checked, not run. |
| `dev/eval-usage.py` | **New**: usage by `EvaluationOnly`, and token sums of a Claude transcript | This change. Compiled, not run. |
| `dev/eval-minesweeper-spec.md` | **New**: the default consumer specification, version 1 | This change. |
| `dev/attached_tui.py` | Existing, not used here | A pty fixture for `dev/attached-native-eval --lifecycle`. It answers terminal capability queries, passes the trust dialogs ("Yes, I trust this folder", "Trust and continue") and the bypass-permissions dialog, records the stream, and then freezes or kills the harness to check that the CQ host exits. It submits no request to a model. |
| `dev/attached-native-eval` | Existing, not used here | Fresh consumer repository, private server and token, settings with an `evaluation` tag, one Begin task with `"checks": []` and `"integrationTarget": None`, harnesses in print mode. It pins Claude 2.1.280, Codex 0.156.1 and Pi 0.87.1, not the installed versions. |
| `dev/process-eval`, `dev/process-evidence.py` | Existing, not used here | Staged `cq run` evaluations with predicates, for example "A historical answer was fabricated before user input". They are a model for later automated checks of a driven run. |

Nothing in `dev/` drove a full interactive planning, implementation and follow-up flow before this change, and the new scripts have not done so either.

### Where the harness runs

The driver session runs inside the `yolo` sandbox. The recorded sessions were started from inside that sandbox, as children of the tmux server: `launch-codex.sh` says "Already inside the yolo sandbox". No second `yolo` is started; G4 records that a nested `yolo` fails on `/run/nscd`. A launch from a host shell uses `yolo --profile work --env CQ_TOKEN_FILE=… <harness>` with the harness tool name, as in [Run CQ inside your normal harness](interactive.md). To run an exact executable path instead, use `yolo … cmd <path> …`, as the host launcher below does. The driver session cannot launch from a host shell itself.

There are two allowed launch methods. The report names the one used.

| Method | How | Evidence |
| --- | --- | --- |
| Host launcher | The operator starts a launcher from a host shell, with `SMIND_SANDBOXED` unset. It owns a private tmux socket and asciinema v3 recordings. It runs each harness through the documented `yolo cmd` form with the exact pinned executable path, a private configuration and the token and settings files named by path. The recorded commands are in [Host-launcher commands](#host-launcher-commands-rs2-revision-4). | RS2 revision 4, 2026-10-04. Claude Code 2.1.285, Codex 0.159.2 and Pi 0.99.1 each connected to the private real CQ server: Claude `/mcp` and Codex `/mcp` showed `cq` connected with nine tools, and Pi loaded its generated extension. Each performed CQ Context and a Begin activation and recorded I1 Proposed in its own private consumer. Each exited 0 (`x=0`), uploaded every retained session with exit 0, and returned `EvaluationOnly` usage. Afterwards there were 0 active claims, 0 managed attempts and 0 pending integrations. Codex folder trust and its two hooks were approved explicitly; Claude showed no new MCP dialog. An earlier launcher passed the executable paths as yolo tool names, and its launches were rejected. Those failed launches and their recordings are preserved. Evidence root: `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/host-launch-probes-v2` (private). The governing session observed it. |
| Existing yolo session | The driver session starts the harness inside its own yolo sandbox as a child of the private tmux server, as described above. | The T59/T60 preparatory Pi, Codex and Claude consumer runs of 2026-10-04 and the earlier governing sessions. |

The host-launcher probes are Q43 short probes. They establish launch and connection, not full matrix qualification or complete usage.

Inside the sandbox the consumer repository, the evaluation state root, the CQ package and the token file must be readable, and the first two writable. `/srv/nvme/tmp` is writable there.

### Terminal control and recording

`dev/eval-session SOCKET ACTION NAME …` wraps a private tmux server on the given socket. `tmux` and `asciinema` (3.2.1 on this machine) come from `PATH`; set `EVAL_TMUX` to use another tmux binary, as `gov/t` did with an absolute path. `NAME` addresses exactly the session of that name (`=NAME`), never a session whose name it only begins or matches as a pattern.

| Action | Use |
| --- | --- |
| `start NAME CAST COLUMNS ROWS -- COMMAND…` | Starts `asciinema rec -q CAST -c COMMAND` in a detached session. An existing recording is never overwritten. The recorded sessions used 200 columns and 55 rows. |
| `type NAME TEXT`, `keys NAME KEY…` | Literal text, then key names such as `Enter`, `Escape`, `Up`, `C-u`. Send the text and `Enter` separately, one second apart, as `gov/start.sh` does. |
| `screen NAME HISTORY_LINES` | The rendered screen with scrollback. `gov/watch.sh` kept 400 lines as the final screen. |
| `wait NAME BUSY_REGEX PROMPT_REGEX IDLE_SECONDS TIMEOUT_SECONDS` | Prints `GONE`, `PROMPT`, `IDLE` or `TIMEOUT`. |
| `stop NAME` | Ends the session and with it the recording. |

Screen texts the recorded drives relied on:

| Harness | Busy | Dialog waiting for a key | Source |
| --- | --- | --- | --- |
| Claude Code 2.1.285 | `esc to interrupt` | `Enter to (select\|confirm)`, `Do you want` | `gov/watch.sh`, `gov/wait.sh` |
| Codex 0.159.2 | `Working \(` or `esc to interrupt` | Not scripted | `/srv/nvme/tmp/cq4-cross-cut/t9-probe/drive.sh` |
| Pi 0.99.1 | A `Working` spinner line was seen once (`t10-probe/screens/a-05-drive-started.txt`) | Not scripted | Not used as a wait condition so far |

A session counts as idle when the busy text has been absent for 60 seconds (`gov/watch.sh`: four polls of 15 seconds). A request pasted as one block makes Claude Code show `Pasted text` and wait for a second `Enter`; `gov/start.sh` sends it.

### Launch per harness

`dev/eval-launch HARNESS PROJECT_DIR TOKEN_FILE [CODEX_HOME]` removes every `CLAUDE*` and `CQ_*` variable from the environment, exports `CQ_TOKEN_FILE`, changes to the project and executes the harness. The first matters because the driver session is itself a Claude session; `launch.sh` unsets the `CLAUDE*` variables for that reason. The token is only ever named by its file path.

| | Claude Code | Codex | Pi |
| --- | --- | --- | --- |
| Command | `claude --setting-sources project,local` | `codex --no-daemon --dangerously-bypass-approvals-and-sandbox` | `pi --approve --no-prompt-templates --prompt-template .pi/prompts` |
| Why these flags | The hooks, the status line and the approval of the `cq` server are in `.claude/settings.local.json`. | `--no-daemon`: interactive 0.159.2 needed it (G4, [cross-cut](validation/crosscut-20261002.md)). The bypass flag is what `launch-codex.sh` used inside the sandbox. | `--approve` trusts the project files, including CQ's extension; the template options keep older personal CQ prompts out ([interactive](interactive.md)). |
| Files `cq configure` writes | `.mcp.json` (`cq` server), `.claude/settings.local.json` (`enabledMcpjsonServers`, hooks, `statusLine`), `.claude/commands/cq/` | `.codex/config.toml`, `.codex/hooks.json`, `.agents/skills/cq-*` | `.pi/extensions/cq-host.js`, `.pi/prompts/` |
| Extra state | None | A private `CODEX_HOME`: `dev/eval-launch prepare-codex-home DIR` copies `~/.codex/config.toml` and links `~/.codex/auth.json`. The personal configuration is read-only in the sandbox, so Codex could not persist project trust without it. It must not be ephemeral: CQ reads outer usage from its rollout. | None |
| Prompts at launch | With the approval written by `cq configure`, none ("the host loads with no dialog", [defect corrections](validation/defect-fixes-20260930.md)). `gov/start.sh` still handles `New MCP server found` with `Up`, `Up`, `Enter`. `Teach auto mode` was answered with `Down`, `Enter`. | Project trust, once per `CODEX_HOME`; then `/hooks`, which shows `2 hooks need review before they can run`, answered with `t` (trust all). Both are stored in the private `config.toml`, so later launches show neither. If Codex lists a personal and a project skill of one name, select the project one. | None seen with `--approve`. |
| Check that `cq` is connected | `/mcp`, `Enter`; the screen must match `✔ cq +[0-9]+ tools` (`gov/start.sh`), then `Escape`. Nine tools were listed on the installed release. | `/mcp` must list `cq`. Not scripted so far: for the recorded Codex drive the only connection evidence is its `cq` tool calls (`Called cq.session` in `gov/drive4-final-screen.txt`). | The footer reads `CQ driver off` once `cq-host.js` is loaded ([Pi extension driver](validation/pi-driver.md), stub backend). That footer alone shows the extension, so also verify an actual CQ Context call. RS2 revision 4 records Pi 0.99.1 reaching CQ Context against a private real server from the host launcher. |
| Workflow commands | `/cq:begin`, `/cq:advance`, `/cq:drive`, `/cq:park` | `$cq-begin`, `$cq-advance`, `$cq-drive`, `$cq-park` | As Claude Code |

The commands in this table are the existing-yolo method: inside the driver's sandbox, the harness is found on `PATH`. The host launcher uses the exact pinned paths below.

### Host-launcher commands (RS2 revision 4)

These are the commands the RS2 revision 4 launcher wrote and ran. The launcher is the private script `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/host-launch-v2.py`. For each harness it wrote `host-launch-probes-v2/<harness>/launch.sh` and started that script under asciinema in the private tmux server. The paths are copied from those recorded files. `EVAL` stands for `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations`, and `FIXTURE` stands for `EVAL/host-launch-probes-v2`.

1. Run it from a host shell. It asserts that `SMIND_SANDBOXED` is unset.
2. It builds the child environment without any variable that starts with `CQ_`, `CLAUDE`, `ANTHROPIC`, `OPENAI`, `CODEX` or `PI_`. It then sets the private configuration directories `CODEX_HOME=EVAL/codex-config`, `CLAUDE_CONFIG_DIR=EVAL/claude-config` and `PI_CODING_AGENT_DIR=EVAL/pi-config`, and `CQ_TOKEN_FILE=EVAL/claude-refreshed/token`. The token file is checked to be mode `0600` or stricter, and it is only ever named by its path.
3. For each harness it creates `FIXTURE/<harness>/consumer`, a git repository on `main` with a `cq-result` branch. It writes `FIXTURE/<harness>/settings.json` with mode `0600`, using the evaluation tag `{"run": "eval-20261004-host-launch", "scenario": "<harness>-launch"}` and `"checks": []`. It then runs `<release>/bin/cq init --endpoint <origin> --name …` and `<release>/bin/cq configure <harness> --settings FIXTURE/<harness>/settings.json` in that repository. The release was `release-e5dba67` and the origin was `http://127.0.0.1:39393`.
4. It starts each session with `/nix/store/499dwp4ljzzbx5i5fhlxm6lwzncgqz61-tmux-3.7c/bin/tmux -S FIXTURE/tmux.sock new-session -d -s rs2-<harness> -x 200 -y 55 -- asciinema rec -q FIXTURE/<harness>/session.cast -c FIXTURE/<harness>/launch.sh`.

Each `launch.sh` does `cd -- FIXTURE/<harness>/consumer` and then `exec`s this command:

```
/etc/profiles/per-user/pavel/bin/yolo --profile work \
  --env CQ_TOKEN_FILE=EVAL/claude-refreshed/token --rw FIXTURE \
  --env CODEX_HOME=EVAL/codex-config --rw EVAL/codex-config \
  --env CLAUDE_CONFIG_DIR=EVAL/claude-config --rw EVAL/claude-config \
  --env PI_CODING_AGENT_DIR=EVAL/pi-config --rw EVAL/pi-config \
  cmd <harness command>
```

| Harness | `<harness command>` as recorded |
| --- | --- |
| Claude Code 2.1.285 | `/nix/store/wa2y85js0ifyxbzvj56ki4gqknrkpi2x-claude-code-2.1.285/bin/claude --setting-sources project,local --model opus` |
| Codex 0.159.2 | `/nix/store/x6d4jbnav6z3398y1fjs0yay3wf2j0p0-codex-0.159.2/bin/codex --no-daemon --dangerously-bypass-approvals-and-sandbox --model gpt-6.1-sol` |
| Pi 0.99.1 | `/nix/store/6v3ax4h8wsnmn0nhz4c62hbahmmj39mf-pi-coding-agent-0.99.1/bin/pi --offline --approve --no-prompt-templates --prompt-template .pi/prompts --provider openai-codex --model gpt-6.1-sol` |

The executable paths are the `executable` values of the harness routes in the settings file. All three sessions are passed all three configuration directories.

The launches were checked as follows. The retained connection screens show `✔ cq 9 tools` for Claude and `CQ driver off` for Pi. The retained Codex screens do not contain the `/mcp` listing; its connection rests on the RS2 revision 4 record. `FIXTURE/probe-summary.json` records upload exit 0 for every session, and 0 active claims, 0 managed attempts and 0 pending integrations for each harness. RS2 revision 4 records CQ Context, a Begin and exit 0 for each harness. These are the observations of the governing session, not an independent rerun. The model names and the Pi provider are the ones used on 2026-10-04 and are not requirements.

The first launcher, `host-launch.py`, differed only in two places. It wrote to `host-launch-probes`, and it appended the harness command without `cmd`, so yolo took the executable path as a tool name and rejected the launch. Those failed launches and their recordings stay preserved.

If the connection check fails, the run ends in S0 as a launch failure. A missing token, an uninitialised project or a harness version outside the verified set makes `cq host` answer `initialize` with error `-32003` and one line naming the cause, and exit 78 ([interactive](interactive.md)).

Record `claude --version`, `codex --version` and `pi --version` and the `cq` package manifest before the launch.

Pi was first launched with a real model against a real CQ server on 2026-10-04: in the T59/T60 preparatory consumer run inside the existing yolo session, and in the RS2 host-launcher probe. Both were observed by the governing sessions in private fixtures. The Pi busy and dialog screen texts are still not scripted.

## 2. Consumer-project definition

Everything of one run lives under one evaluation root outside any source checkout, for example `/srv/nvme/tmp/cq4-eval/<run>/<scenario>/`:

| Path | Content |
| --- | --- |
| `server/` | State of the isolated CQ server, including its `token` and `client.env` |
| `consumer/` | The fresh consumer repository |
| `settings.json` | Supervisor settings of this run |
| `sessions/` | `stateRoot`: one directory per harness session |
| `codex-home/` | Codex runs only |
| `casts/`, `screens/` | Recordings and screen excerpts |
| `report.md`, `usage/` | The run report and the usage reads |

Steps, in this order. All use existing commands.

1. **Isolated server and token.** Start a second server with its own state and ports from a host terminal: `CQ_LOCAL_STATE=<root>/server CQ_LOCAL_PORT=<port> CQ_LOCAL_DB_PORT=<port> CQ_ORIGIN=<origin> ./run-local.sh`. The origin must be one the sandbox of the driver session can reach; whether the host's loopback address is reachable from it was not checked. The launcher creates `<root>/server/token` on first use and refuses a state directory that another launcher owns ([quickstart](quickstart.md)). The operator's working server and its token are not used. Starting a server is a host action the driver session requests; it does not start one during a release gate.
2. **Fresh repository with a committed base.** `git init -b main <root>/consumer`, a README naming the specification version, one commit. CQ requires a committed base.
3. **Integration branch.** `git branch cq-result`. It is not checked out, so reviewed integration never touches working files ([quickstart](quickstart.md)).
4. **Settings.** Copy the `guardian`, `harnesses` and `limits` of the operator's current supervisor settings, so that the run uses the installed, verified harness pins, and set:

   ```json
   {
     "stateRoot": "<root>/sessions",
     "checks": [
       {"name": "tests", "command": ["/absolute/python3", "-m", "unittest", "discover", "-v"],
        "executionMillis": "120000", "retainedOutputBytes": 262144, "attempts": 1, "revalidations": 0},
       {"name": "launch", "command": ["/absolute/python3", "-m", "minesweeper", "--version"],
        "executionMillis": "30000", "retainedOutputBytes": 65536, "attempts": 1, "revalidations": 0}
     ],
     "integrationTarget": "refs/heads/cq-result",
     "evaluation": {"run": "<run>", "scenario": "<scenario>", "assessor": false}
   }
   ```

   The check names and commands are those the specification declares. `evaluation` is the tag: the host copies it into the governing session's assignment and into the assignment of every child it dispatches (`SupervisorRole.scala`, `DispatchController.scala`), which is what the `EvaluationOnly` usage filter selects. Both strings must be non-blank and at most 300 characters.
5. **Project.** In `consumer/`: `CQ_TOKEN_FILE=<root>/server/token cq init --endpoint <origin> --name "<run> <scenario>"`.
6. **Harness integration.** `cq configure <harness> --settings <root>/settings.json`, for the harness under evaluation only.
7. **Launch and check** as in section 1, recorded from the first keystroke.

**Naming (proposed).** `run` is `eval-<YYYYMMDD>-<NN>`, shared by the harness runs of one request. `scenario` is `<harness>-driver` or `<harness>-baseline` (see [Dependency handling](#7-dependency-handling)). A repetition gets a new `run`.

### The specification

Q15: "Fixed versioned TUI minesweeper spec shared across harnesses, overridable per request". The default is [`dev/eval-minesweeper-spec.md`](../dev/eval-minesweeper-spec.md), version 1. Its content is **selected as written** by Q37: Python 3 standard library, checks `tests` and `launch`, and one follow-up. The default remains shared across harnesses and overridable per request (Q15). It holds the product description, the two declared checks, the follow-up request for S4 and the answers the proxy gives to expected preference Questions.

- **Chosen** by the operator's request: the default, or a file the request names. The report records the path, the version and the SHA-256 of the file.
- **Given** in S1 as the body of the begin workflow, in one submission: the command (`/cq:begin` or `$cq-begin`) followed by the product section verbatim and the lines `The host validates candidates with the declared checks tests and launch.` and `Capture and plan this request through CQ; ask before implementing.` The same bytes go to every harness. The follow-up request is given in S4 the same way. The proxy's answer table is never given to the harness session.

## 3. Operator-proxy answering rules

Q14, option (a), decides the authority.

**Delegated to the proxy, within the disposable consumer repository only:**

- product and specification preference Questions, answered as the consumer specification defines;
- plan acceptance;
- review, integration and acceptance approvals that integrate candidates into that repository's evaluation integration branch, or mark consumer Goals and Tasks complete.

**Always escalated to the operator:**

- any Question or approval about CQ itself or any other repository;
- pushes to remotes;
- spending beyond the run budget;
- destructive or out-of-repo actions;
- Operator Action confirmations and other items that need host or human evidence, which the proxy never supplies.

**Label.** Every delegated answer or approval the proxy types begins with the mandatory marker `operator-proxy (eval <run>/<scenario>):`, and the proxy asks the session to put the label `operator-proxy` on the affected items. The run report lists each delegated answer and approval with the item, the text and the time.

**Answer first, instructions after.** The session stores what follows the marker as the answer, so the typed text is the answer sentence alone, for example `operator-proxy (eval <run>/<scenario>): Q1: approved, implement as the reviewed plan describes.` Anything addressed to the session (store exactly that sentence, add the label, wait for the drive command) goes into a separate sentence after it. In run `eval-20261004-01/codex-driver` an answer that ended "…wait for my drive command" was stored whole, and a Planner then abstained because the recorded answer told it to wait ([matrix record](validation/t61-evaluation-matrix-20261005.md)).

Rules that follow from this:

| Situation | Proxy action |
| --- | --- |
| A Question the specification's product section or answer table decides | Answer with the marker and that answer. Do not add reasons the specification does not give. |
| A Question the specification does not decide | Escalate. Do not choose the recommended alternative because it is recommended. |
| A Question whose alternatives all contradict the specification | Escalate, and note it as a planning observation. |
| A requested Operator Action | Never confirm it. If the action is a host action inside the evaluation root that the driver session can perform (for example "restart the session"), perform it, describe what was done as its own action, and let the session observe the result; the confirmation text is still the operator's. Otherwise escalate. |
| The session asks for evidence, a measurement or a test result | Give only output of a command the driver session actually ran, quoted with the command, or escalate. Never state that the operator saw, checked or approved something. |
| A harness permission dialog for a tool call | Q41: approve when the action is confined to the consumer repository or the evaluation root; escalate anything else. |
| The auto-driver is on and a Question is open | The auto-driver stops with `user input required`. The stop turns it off, so the proxy types the answer and then the same drive command again. A driven session cannot record an answer ([auto-driver](auto-driver.md)). |
| The measured cost reaches the cap, or the wall-clock budget ends | Pause and escalate (section 4). The proxy never raises a budget. |

After each answer the proxy reads the item back (`cq query`, or the browser of the evaluation server) and checks that the stored answer begins with the marker and that the label is present. A missing marker or label is recorded in the report. In the matrix of 2026-10-05 each answered Question carried the label and status Answered in the ledger snapshots; the stored answer text was not compared with the typed one.

While waiting for the operator, the session is left idle and the wait is excluded from the wall-clock budget; the report records its length.

The proxy does not edit the consumer repository, does not run its checks in place of the host during S1–S4 and does not use CQ tools of its own against the evaluation server to move work forward. It types, reads and measures.

## 4. Monitoring criteria

### Signals

| Signal | Read with | Poll |
| --- | --- | --- |
| Session state: busy, idle, dialog, gone | `dev/eval-session … wait` | Continuously |
| Auto-driver state and stop reason | The transcript line `CQ driver stopped (<reason>): <detail>`; the status line in Claude Code and Pi | At each stop |
| Ledger state | `cq query --roots <roots>` in the consumer repository: readiness of each item | At each stop |
| Session and workspace activity | Modification times under `<root>/sessions/<session>/` (`journal`, `children/*/receipt.json`, `payload/*/stdout`, `workspaces`) and the refs of the consumer repository | Every 15 minutes and at each stop (Q44) |
| Child outcomes | `children/*/receipt.json`: `phase`, `process`, `counts`, `blocker`; `ticket.json`: role, harness, members | At each stop |
| Measured cost | `dev/eval-usage.py child …` | Every 15 minutes and at each stop (Q44) |

### Failures

A failure is one of the following observations. Each is recorded in the report with its evidence.

| Failure | Operational definition |
| --- | --- |
| Launch failure | The connection check of section 1 does not pass, or the harness exits before a request is submitted. |
| Session loss | `wait` prints `GONE` before S5, or the harness shows an unrecoverable error screen. |
| Auto-driver failure | A stop with reason `failure` or `not bound`. |
| Child failure | A receipt whose `phase` is `Failed`, `Cancelled` or `Unknown`, or whose `process` is not `Settled` after the session ended. |
| Rejected CQ request | A CQ tool call that returns a failure the session does not recover from in the same turn. |
| Integration failure | An integration that ends `NotApplied`, or a declared check that fails on the integrated commit. |
| Fabrication | An answer, approval, confirmation or evidence in the ledger that neither the proxy typed nor a host command produced. |
| Acceptance failure | In S5, on `cq-result`: one of the declared checks fails, or a behaviour the specification states is absent. The driver session runs the checks itself and quotes their output. |
| Stage not reached | A stage does not end before a stop rule ends the run. |

A check that fails on a candidate and is then repaired through review and revision is the workflow working, not a failure; it counts as rework below.

Thresholds: every failure is reported. A failure becomes a Defect when CQ, its prompts or its harness integration caused it; a wrong line of consumer code that review caught does not. "The same failure" in the stop rule means the same operation failing with the same message after identifiers, tokens and times are removed.

### Stops

At every stop the proxy polls usage and activity as well as ledger state, records the observations, and classifies before it types anything (Q44).

| Class | Operational definition | Proxy action |
| --- | --- | --- |
| Required input | An open Question or a requested Operator Action is ready or blocks a selected item. With the auto-driver: reason `user input required` naming it. | Answer or escalate (section 3). |
| Limit | Auto-driver reason `limit reached` (64 directives per drive); a budget stop of this protocol. | Directives: drive again. Budget: escalate. |
| Quiescence | Every root is terminal, or every remaining item waits for something outside the session's authority. With the auto-driver: reason `quiescent` and `cq query --roots` shows no ready item. | Go to the next stage. |
| Parked | The proxy parked the auto-driver. | None. |
| Expected without the auto-driver | Baseline scenario only (Q11): the workflow command the session was given has returned, and `cq query --roots` shows a ready item. | Q40: repeat the same advance command and count it. Record and analyse any unwarranted stop. |
| **Unwarranted** | None of the above, and at least one selected item is ready. In particular: (a) the auto-driver is on and the session ends its turn in free text although a ready item exists; (b) reason `quiescent` because "the previous cycle changed nothing" while a ready item exists; (c) the session asks the operator something in prose without recording a Question; (d) the session reports completion while a root is not terminal and nothing is awaited; (e) baseline: the session stops inside a workflow command, with children running or a prepared integration unapplied, although its request authorised it to continue. | Record and analyse the stop with its screen excerpt, readiness, usage and activity observations. If no budget, stall or repeat-failure stop rule applies, record the recovery and repeat the same drive command; in the baseline repeat the same advance command (Q40). |

How the auto-driver changes the classification, as Q11 requires: with the auto-driver on, continuation is the server's decision after every turn, so a stop with ready work and no awaited input is unwarranted. The proxy sends no generic continuation prompts while the driver is active. Reissuing the same drive command after recording and analysing an unexpected stop is the explicit recovery exception above, not ordinary autonomous continuation; record and count every recovery. Recovery cannot bypass required input, authority limits or any stop rule. Without the driver, a stop at the end of a workflow command is labelled expected-without-driver and the number of proxy follow-ups is a result of the run. The comparison of the two scenarios measures the auto-driver.

Thresholds: every unwarranted stop is reported with its screen excerpt. One is enough for a Defect or an Idea after the duplicate check. Three identical ones end the run by the stop rule.

### Inefficient token use

Token use is judged on measured figures only (section 6). Q42 adopts the initial flags below, with recalibration after the first run of each harness. They are derived from the governing sessions recorded on the CQ repository, whose Tasks are far larger than a minesweeper; recalibrate them after the first run of each harness. A flag is a reason to look at the attempt and, if a CQ cause is found, to record an Idea or a Defect; it is not a verdict.

| Flag | Initial threshold | Basis in the recorded sessions |
| --- | --- | --- |
| Worker rework | More than two Worker attempts on one Task | T46 took three Workers and three candidate reviews (session `12925433`). |
| Planning rework | More than two Planner rounds for one root before a plan is accepted | D101 took four Planner rounds in session `9715c745`, with three `ChangesRequested` plan reviews. |
| Redone accepted work | Any Task implemented again after its candidate was accepted | T49 and T50: a shared candidate with a mixed review could not be continued; each was implemented again, about two hours (session `39765662`). |
| Worker attempt size | More than 3,000,000 input tokens or more than USD 3 | Workers on the CQ repository: 923,697 to 42,561,874 input tokens, USD 0.95 to 15.48. |
| Planner attempt size | More than 1,000,000 input tokens or more than USD 1.50 | Claude Planners: 74,322 to 765,380 input tokens, USD 0.24 to 1.22. |
| Plan review size | More than 600,000 input tokens or more than USD 0.75 | 88,301 to 526,474 input tokens; Claude USD 0.18 to 0.58, Pi USD 0.24 to 0.58. |
| Candidate review size | More than 3,000,000 input tokens or more than USD 2 | 960,023 to 2,589,154 input tokens; Pi USD 1.08 to 1.87. |
| Governor share | Outer input tokens exceed the sum of the children's input tokens | Four Claude governing sessions: ratios 4.4, 3.3, 0.7 and 3.1 (table below). |
| Governor context | A single governor request above 200,000 input tokens | Largest requests of the four sessions: 156,820, 468,659, 349,108 and 622,642 tokens; no compaction entry was found in their transcripts. |

Input tokens here include cache reads and cache writes; in every recorded attempt most input was cache reads. Three of the four recorded Claude governing sessions exceed the governor-share flag, so a first run is likely to raise it: check for an existing item before recording another.

### Budget and stop rules

Q16, option (a), applied per harness run unless the operator's request states other values:

- a 3-hour wall-clock budget;
- a cap of USD 25 of measured cost, meaning child usage read through the `EvaluationOnly {run, scenario}` filter plus any measured outer usage;
- at the cap the proxy pauses the session and escalates to the operator instead of continuing;
- the run also stops if there is no ledger or workspace progress for 30 minutes, or if the same failure repeats 3 times;
- each stop is recorded in the run report with its reason, and the report states which cost components were unmeasured.

Operational definitions:

| Rule | Definition |
| --- | --- |
| Wall clock | From the first keystroke after the connection check to the end of S4, excluding time spent waiting for the operator. |
| Measured cost | The sum of the cost groups of the usage summary for the tag, in USD. Client estimates and price-table estimates count; attempts with unknown cost add nothing and are named in the report. The cap therefore bounds a lower limit of the real cost. |
| Pause | With the auto-driver: park (in Claude Code press `Escape` first, then `/cq:park`). Without it: stop typing follow-ups. Running children are not cancelled; the auto-driver has no such operation. |
| No progress | For 30 consecutive minutes: no file under the session directory is modified, no ref of the consumer repository changes and no item revision changes. |

The cap is not known to be sufficient. Measured child cost of the recorded governing sessions on the CQ repository was USD 2.91, 23.14, 40.31 and 50.00, each without the cost of its Codex children, which report none. No figure exists for a consumer of minesweeper size.

### Observations from the recorded drives

These figures were computed for this page from `ticket.json`, `receipt.json` and `payload/*/stdout` under `/srv/nvme/tmp/cq4-interactive-sessions/<session>/` and from the governing sessions' local Claude transcripts. They were not read from `cq status`, and nobody has cross-checked them against the server's audit.

| Session | Governor | Roots | Children | Child input tokens | Measured child cost (USD) | Governor input tokens | Ratio |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `28bcba7b` | Claude Code | D97 | 7 (4 Claude, 3 Codex) | 3,835,287 | 2.91 + 3 Codex unknown | 17,003,485 | 4.4 |
| `9715c745` | Codex | D99–D103 | 28 (17 Codex, 11 Claude) | 6,796,821 | 3.66 + 17 Codex unknown | 3,652,358 (Codex exit line: 98,694 + 3,553,664 cached) | 0.5 |
| `12925433` | Claude Code | D99, D101, D102 | 31 (16 Claude, 15 Codex) | 46,840,611 | 23.14 + 15 Codex unknown | 153,248,628 | 3.3 |
| `4c799892` | Claude Code | D100, D103, D105 | 32 (19 Claude, 9 Codex, 4 Pi), bound reached | 63,729,478 | 40.31 + 9 Codex unknown | 42,566,361 | 0.7 |
| `39765662` | Claude Code | D100, D103, D105 | 27 (14 Claude, 13 Pi) | 89,419,963 | 50.00 | 277,346,953 | 3.1 |

What the same sessions showed about stops and failures ([cross-cut](validation/crosscut-20261002.md), final screens `gov/drive4…7-final-screen.txt`):

| Observation | Class under this protocol |
| --- | --- |
| Claude Code, D97: 44 minutes unattended to `quiescent` with the Defect resolved | Quiescence |
| Codex, D99–D103: stop `user input required` on Q23 and Q24 after 14 min 54 s | Required input |
| Claude Code, D99, D101, D102: 3 h 26 min, stop `user input required` on Q25 | Required input |
| Session `4c799892`: the 33rd child start refused; three of four Workers had no candidate review yet | Limit, and an efficiency finding |
| A direct `change` Create of a Defect outside the workset turned the auto-driver off with `failure` ("non-selected creation") | Auto-driver failure |
| The Codex plan reviewer rejected a plan twice, identically, because it received `operatorRequirements: null` | Repeated failure; planning rework |
| Claims on Defects lapsed after 30 minutes while only their Tasks had running children | Rework cause |
| Codex children could not reach the Nix daemon (D104) | Child failure with a CQ cause |

## 5. Evidence and recording rules

**Every session is recorded.**

| Evidence | Rule |
| --- | --- |
| Cast | One asciinema v3 file per harness session, from launch to exit: `casts/<scenario>-<n>.cast`. Never overwritten, never edited. |
| Final screen | `screen NAME 400` at the end of each session: `screens/<scenario>-<n>-final.txt`. |
| Excerpts | At every stop, dialog, proxy answer and detected problem: `screen NAME 120` into `screens/<UTC time>-<label>.txt`. |
| Proxy log | `proxy-log.jsonl`: time, what was typed, the stop class, the item concerned. |
| Session directory | `<root>/sessions/<session>/` is kept whole. |
| Usage reads | Each `dev/eval-usage.py` output is kept under `usage/` with its time. |
| Versions | Harness versions, the `cq` package manifest, the specification path, version and SHA-256, `settings.json`. |

A citation names the cast and the wall-clock time of the event (asciinema v3 events carry intervals, and the header carries the start time), or an excerpt file and its lines. Recordings may show prompt text and paths; they never show the token, because it is passed by file path. Do not type or `cat` a credential in a recorded terminal.

**Each detected problem becomes a CQ item** in the operator's CQ project for CQ itself, recorded by the driver session through its own begin workflow after the run or while the session waits. It is the driver session's own report, not a proxy decision, and carries no proxy marker.

| Item | When | Content |
| --- | --- | --- |
| Defect | CQ, its prompts, its commands or its harness integration behaved against their documentation or acceptance criteria | Observed; expected, with the document or criterion it comes from; reproduction: harness and version, package manifest, scenario, stage, the typed input; citation to the cast and time or to the excerpt; measured cost of the affected attempts where it applies. |
| Idea | The behaviour is as documented but wasteful, slow or awkward | The same evidence, the measured cost, and the improvement. |

A defect of the consumer program that the workflow should have caught (a failing acceptance check in S5) is recorded as a CQ Defect only with the evidence of where the workflow passed it: the review and the check runs. A defect of a harness itself goes to the operator; the upstream workflow is the operator's decision.

**Duplicates.** Before recording, search Defects and Ideas of the CQ project, archived ones included (`cq query --query 'archived:all …'` with the distinguishing words, and the stop reason or error text). If an item matches, do not create another: name it in the report with the new evidence, and propose the evidence as a follow-up to that item. A problem that recurs across harnesses is one item with one evidence entry per harness.

Nothing is recorded as Resolved, confirmed or reproduced by the operator. A problem seen once and not reproduced says so.

## 6. Cost measurement

### Child usage

After the harness session has exited, and before the run's server is stopped — also for a run that a stop rule ended early (three runs of the 2026-10-05 matrix lost their child usage because the private database went away with the server):

1. `CQ_TOKEN_FILE=<root>/server/token cq job upload --session <root>/sessions/<session>` for every session of the run. Claude Code and Codex may end the host before its final delivery ([interactive](interactive.md)); a repeated upload acknowledges zero batches.
2. Read the usage with the filter `EvaluationOnly {run, scenario}` (`UsageFilter` in `models/cq-api.baboon`): `dev/eval-usage.py child --project-dir <root>/consumer --token-file <root>/server/token --run <run> --scenario <scenario>`. It posts `Usage` with the selections `Summary` and `Phases` to `/api/call`, as `dev/process-eval` and `dev/consumer-assess` do for `Summary`. The operator token is sent only to the endpoint named in the consumer's `cq/project.json`: the script uses no proxy and treats a redirect as an error.

The `cq status` command line has the scopes `--task`, `--cohort` and `--session` and no evaluation scope (`cq help status`), so the filter is reachable through the API only. `cq status phases --session <session>` gives the same phase table for one session; a run of several sessions needs the filter.

The summary reports direct, shared and unattributed totals, incomplete meters, attempts without meters and cost groups; the phase report gives attempts, host spans, finished wall time, tokens and costs per phase (Govern, Explore, Probe, Plan, Work, Check, Review, Combine, Integrate). Phase wall times overlap and are not added up ([usage audit](design/usage-audit.md)).

### Outer-session usage

| Harness | What CQ records | Best available source | Coverage | Cost |
| --- | --- | --- | --- | --- |
| Claude Code | Nothing. The governing attempt is registered with collector `CQ attached session; outer usage unavailable` (`run.json`). | The local transcript `~/.claude/projects/<project path with dashes>/<session>.jsonl`: each assistant entry carries the response's `usage` (`input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, `output_tokens`). `dev/eval-usage.py claude-outer --transcript FILE` sums them once per response. | Partial (RS3 revision 7, RS15 revision 4). The transcript and `/cost` are separate partial sources; report both, separately, with their boundaries. In the short host-launch probe at the intake stop, both showed eight responses. The counters differed, though: the transcript had input 16, output 1,270, cache read 333,844 and cache write 29,009, while `/cost` showed Opus input 522, output 1.3k, cache read 388.5k and cache write 29.3k. The longer refreshed run showed a 506-token input difference of the same kind (transcript 82 against `/cost` 588), and its `/cost` also listed Haiku auxiliary usage. The cause of these discrepancies is not established. Complete totals, per-turn identity and auxiliary, subagent and compaction coverage are unproved. RS15 independently recomputed only a supplied, Governor-created projection; the private originals were not reread, and the `/cost` boundary of the longer run is unavailable. | Unknown from the transcript, which has no cost field. `/cost` shows a rounded client estimate (USD 0.3398 in the short probe, USD 1.47 Opus in the longer run). That is not billing and not an all-model total. |
| Codex | Native response records of the bound thread, as unattributed usage of the governing session, when `CODEX_HOME` is bound and not ephemeral ([Codex accounting boundary](validation/attached-codex-usage.md)). It carries the evaluation tag, so the filter includes it. | CQ, cross-checked against the line Codex prints at exit: `Token usage: total=… input=… (+ … cached) output=…` (end of `gov/drive4.cast`). | Partial: task and model grouping unknown; auxiliary and final-tail work incomplete. | Unknown. Codex reports no monetary cost. |
| Pi | Finalized assistant usage of the outer session. | CQ. | Partial: auxiliary, compaction and tool-result usage excluded ([usage collectors](design/usage-collectors.md)). T60 supplies preparatory driven-session estimates and coverage gaps; these are not independently verified qualification. | Client estimate in USD; zero is treated as unknown. |

Child attempts: Claude and Pi children report client estimates in USD, Codex children report tokens and no cost ([usage collectors](design/usage-collectors.md)).

`claude-outer` reports counter coverage over the identified responses with usage objects retained from the supplied transcript. Each `tokens` value is numeric only when that counter is observed on every retained response; explicit zero is an observation. An absent or null counter makes its total null (unknown/incomplete). `tokenCoverage` gives independent `observedResponses` and `missingResponses` counts per counter, and a `partialSubtotal` when some values were observed but the total is incomplete. No observations means a null total and null subtotal, including an empty transcript.

A request's input size is known only when input, cache-read and cache-write counters are all observed; output coverage is independent. `requestInputCoverage` reports `completeRequests`, `incompleteRequests`, and `largestCompleteRequestInput` (null if none are complete). That last value covers only complete requests. `largestRequestInput` is numeric only for a nonempty transcript with every retained request complete, and otherwise null; a maximum over a subset must not be quoted as the complete transcript maximum.

Response-ID deduplication retains the last usage object and sidechain flag for each ID, as before. Conflicting duplicates require separate empirical investigation. Source, sidechain counts, unreadable lines, entries without response IDs, and unknown cost remain reported. Coverage here describes retained transcript records only: it establishes neither real transcript completeness, agreement with `/cost`, nor billing coverage. The focused synthetic CLI regressions run with `python3 dev/eval-usage-check.py`.

### Unknown is never zero

- A component without a measurement is written as `unknown` or `partial (lower bound N)`, with the reason.
- Totals are given per basis and never merged: CQ-measured child cost; CQ-measured outer usage; transcript-derived outer tokens; unknown components by name.
- A token count is not converted into money by the driver session. Only costs a harness or CQ reported are quoted, with their basis (client estimate, price table).
- The cap of section 4 is compared with measured cost only, and the report says so.

### Run report

One `report.md` per run, with these parts in this order.

| Part | Content |
| --- | --- |
| Identity | `run`, `scenario`, date, harness and version, model shown by the harness, `cq` package manifest, specification path, version and SHA-256, evaluation root. |
| Dependencies | One line each for D67, D73 and G1 (section 7), and whether the auto-driver was used. |
| Child harness mix | All three installed routes configured (Q38); actual harness and version for each child attempt, its role and outcome, and totals per harness, including failed or replacement reviewers and unknown harness identities. Cite ticket/receipt evidence; configuration alone does not establish the actual mix. |
| Stage outcomes | For S0–S5: reached, not reached or skipped; start and end time; sessions used; stop that ended it. |
| Result | Items created; Tasks integrated with commits on `cq-result`; outcome of each declared check run by the driver session on `cq-result`, with the command output. |
| Stops | Every stop: time, class, reason text, proxy action. Totals per class; number of proxy follow-ups. |
| Proxy decisions | Every delegated answer and approval with marker, item and time; every escalation and the operator's reply. |
| Failures | Every failure of section 4 with its evidence. |
| Cost | Child usage per phase from the filter; outer usage per the table above; the unknown and partial components; the budget figures and which stop rule, if any, ended the run. |
| Efficiency | Each flag of section 4: value, threshold, raised or not. |
| Coverage gaps | What this run did not exercise or could not measure. |
| Recorded items | Defects and Ideas created, and existing items that received evidence, each with its citation. |
| Evidence | Paths of casts, screens, proxy log, session directories and usage reads. |

## 7. Dependency handling

D67 and D73 below repeat the 2026-10-02 source-validation record; they are not new verification. G1 and the research limitations are updated from supplied T59 revision 10 and T60 revision 18 evidence on 2026-10-04. The governing sessions observed the preparatory observations themselves; they are not independently verified qualification.

| Dependency | State | Rule | Recorded in the run report as |
| --- | --- | --- | --- |
| **D67**, the documented Claude launch never loaded the CQ host | Resolved. Fixed at `c51aa7a` and `6b2ce4a`: `cq configure claude` writes the approval into `.claude/settings.local.json`, and the launch is `--setting-sources project,local` ([defect corrections](validation/defect-fixes-20260930.md)). | Q12: "Use the logged workaround and record it in each run report". The workaround, `enabledMcpjsonServers ["cq"]`, is now what `cq configure claude` writes, so runs do not wait and add nothing by hand. If the `New MCP server found` dialog appears anyway, accept the server, and cite the dialog as a D67 observation, not as a new Defect. | `D67: fixed in package <manifest>; approval written by cq configure; MCP dialog at launch: yes/no`. |
| **D73**, the host rejected the installed harness versions | Resolved. Fixed at `008fc0b`: Claude Code 2.1.285, Codex 0.159.2 and Pi 0.99.1 joined the verified set, and the settings pins moved at installation. | Q13: "Wait for D73; allow old pinned versions only as a labelled fallback on request". Runs use the installed versions. If an installed harness is newer than the verified set, the host refuses it (exit 78); the run then waits for the version to be verified. The older pins (2.1.280, 0.156.1, 0.87.1) are used only when the operator asks for it, while their store paths exist, and the scenario name gains `-pinned`. Codex 0.159.2 keeps `--no-daemon`. | `D73: installed <versions>, all in the verified set` or `fallback to <versions> on operator request`. |
| **G1**, the auto-driver | Installed; G1 remains Open until independent review accepts its validation record. The historical Claude and Codex work is kept in the [cross-cut record](validation/crosscut-20261002.md). The T59 driver cases are in [Real-harness driver cases (T59)](validation/t59-driver-cases-20261004.md), with the readable projection in `validation/evidence/t59-driver/`. Against private real ledgers, on Claude Code 2.1.285 and on Codex 0.159.2, T59 records two concurrent sessions with distinct keys and frozen targets, and skipped-directive, untracked-activation and out-of-set `Failure` stops with an unchanged item revision and summary page. On Claude Code it also records independent park, Resume without a duplicate child, and two consecutive start cycles after a changed advanceable set. Resume and consecutive cycles were observed in separate drives of one session, not within one drive. T59/T60 also record preparatory Pi, Codex and Claude initial and follow-up consumer integrations; Claude's four integrations are Recorded, with `cq-result` head `7b336080`. The governing sessions observed all of this in private fixtures on package `e5dba67`; nobody has independently reread the originals. | Q11: "Both: allow a labelled pre-G1 baseline now, and rerun with the driver after G1". Scenario `<harness>-driver` uses `/cq:drive` (Codex: `$cq-drive`) in S2–S4; scenario `<harness>-baseline` uses the plain advance command and proxy follow-ups. The request says which; Q39 default: `driver`, with a baseline only when the operator requests the comparison. Record G1's state at the time of the run. Check auto-driver failures against G1's open items and the T59 limits before recording a Defect. A future run is not labelled the first live Pi drive on the basis of the outdated stub-only record. | `G1: auto-driver used / not used (baseline)`, the stop classes that depend on it, and `G1 state <Open/Achieved> at run time`. |

Two constraints in G4's text no longer hold or need a qualifier:

- "Sessions have an 8-hour lifetime": removed. A session runs as long as its harness ([process guardian](design/process-guardian.md), [interactive](interactive.md)). The 3-hour budget is this protocol's own.
- "Launch the harnesses at host level": shown by RS2 revision 4 with the host launcher of section 1. The existing-yolo launch remains a separate allowed method. The earlier T60 launches inside the existing yolo session, and the first failed host-launcher attempt, are historical and stay recorded.

[Drive CQ work automatically](auto-driver.md) lists the cross-cut record of 2026-10-02 and the T59 driver cases as its real-server evidence.

## 8. Readiness and what is not verified

### Questions

| Question | Answer | Used in |
| --- | --- | --- |
| Q10 Scope of the Goal | Protocol and environment only; runs are driven on request and are specified by no Goal | Whole page |
| Q11 Runs relative to G1 | Both: a labelled baseline, and runs with the auto-driver | Sections 4, 7 |
| Q12 D67 | Use the workaround and record it | Section 7 |
| Q13 D73 | Wait for D73; old pins only as a labelled fallback on request | Section 7 |
| Q14 Proxy authority and label | Option (a) | Section 3 |
| Q15 Consumer specification | Fixed, versioned, shared, overridable | Section 2 |
| Q16 Budget and stop rules | Option (a): 3 h, USD 25 measured, pause and escalate, 30-minute stall, three identical failures | Section 4 |

Q37–Q45 resolve the remaining preference choices in the final section. Empirical unknowns remain research, rather than unanswered preferences.

### Research

| Research | State | What exists |
| --- | --- | --- |
| RS2 Launch of each harness from tmux with `cq` connected, in a scratch consumer | Concluded (revision 4) | All three pinned harnesses (Claude Code 2.1.285, Codex 0.159.2, Pi 0.99.1) launched from the host-level private tmux launcher through `yolo cmd` with exact executable paths. Each connected to a private real CQ server, performed a small Begin, exited 0, uploaded with exit 0 and returned tagged usage (section 1). The Q43 short-probe scope is met. The first launcher attempt failed (executable paths passed as yolo tool names); it and the earlier in-sandbox launches are preserved. Launch success does not establish complete usage or full matrix qualification. The governing session observed the private evidence. |
| RS3 A reliable outer token source for Claude Code | Concluded (revision 7), with RS15 (revision 4) | A partial, negative answer. The transcript tokens and the rounded client `/cost` estimate are separate partial sources and are reported separately (section 6). Response and request counts agree, but the input, output and cache counters differ, and the cause is unestablished. Complete outer totals, per-turn identity, and auxiliary, subagent and compaction coverage are unproved. RS15 recomputed a supplied projection, not the private originals (Memory 30). The earlier expired-login observation is historical. D142's missing-counter correction is integrated (T71 Done at `e4ae1e0`); it does not reconcile the real counter discrepancy. |

### Review

The independent Audit confirmed the nine exact answers and requested operational corrections. T69 applied them: it is Done through a Recorded integration, and T70 is Done as well, both on `main` at `e8ef9a8`. RS2 and RS3 are now concluded. G4 readiness still requires independent acceptance of this corrected protocol. Historical source checks, concluded research and preparatory probes do not establish formal I12 qualification, full matrix qualification or complete billing.

### Historical validation and remaining gaps

- No formal run was executed under this protocol. T59/T60 record preparatory terminal consumer flows for Pi 0.99.1, Codex 0.159.2 and, after the operator refreshed the login, Claude Code 2.1.285. Each went through initial implementation and the identical follow-up, with uploads and settled-state snapshots. Claude's four integrations are Recorded; its final export passed 71 tests and launch, and two private findings remain Open there. The governing sessions observed these in private fixtures; they are not independently verified empirical qualification. Strict 15-minute polling compliance is not claimed. Usage estimates have incomplete meters and unknown costs, including unknown outer Codex cost. Historical source checks below record what was actually run at the time; they are not new checks of this corrected revision.
- Before the refresh, Claude's stored login was expired without a refresh token: pinned 2.1.285 and current 2.1.288 failed the authentication preflight (`evaluations/claude-recorded/startup-result.json`). That observation is historical.
- `dev/eval-session`, `dev/eval-launch` and `dev/eval-usage.py` were checked with `bash -n` and `python3 -m py_compile` only. They were not run: a release gate occupied the machine. Later, on 2026-10-02, the `type`, `keys`, `screen`, `wait` and `stop` actions of `dev/eval-session` were run once against a `cat` session on a private tmux 3.7c socket to check the exact-name targets, and `dev/eval-usage.py` once against a local stub endpoint and a three-entry transcript to check the refused redirect, the ignored proxy and the entry without a response identity. `start`, `dev/eval-launch` and a read from a CQ server remain not run.
- At original drafting, `dev/eval-minesweeper-spec.md` had not been implemented and its checks had not been run. T60 later supplied preparatory version-1 and follow-up integrations; this page does not independently verify their checks or establish that formal runs fit the 3-hour and USD 25 defaults.
- At original drafting, the isolated server, `cq init` and `cq configure` in a scratch consumer were not carried out for this page. The steps came from the quickstart and `dev/attached-native-eval`. T60 later supplied private-consumer observations, and RS2 revision 4 carried out the host-level launch in three private scratch consumers (section 1).
- Pi: the original stub-only evidence did not establish real-server launch, outer usage or a real-model driver. T59/T60 later supplied preparatory evidence for these, with usage gaps; busy/dialog monitoring and formal launch qualification remain unverified.
- Codex: the `/mcp` check as a scripted condition. The trust and `/hooks` dialogs were seen in the stub probe (`t9-probe/codex-run0-notbound/codex-hooks-review-screen.txt`); the recorded Codex drive ran on a `CODEX_HOME` that already held both decisions.
- Claude Code: the recorded sessions showed `auto mode on` in the footer. The project's settings files do not set a permission mode and its source was not identified, so the permission dialogs of a fresh consumer are unknown.
- Proxy marker and label retention were unverified at original drafting. T60 supplies Pi and Codex proxy-labelled Q1/Q2 readback observations, and T59 records delegated Q1–Q3 answers in the Claude consumer run. The governing sessions observed these in private fixtures; they have not been independently verified.
- At original drafting, the attached-session `EvaluationOnly` read was unverified: tagging came from code and existing fixtures. T60 supplies later tagged Pi and Codex post-upload usage observations with incomplete meters and unknown attempts, and RS2 revision 4 returned tagged usage for all three short probes. In those probes only Pi reported a cost group, and every governing attempt has unknown usage. These are estimates with coverage gaps, not complete billing totals.
- The stall rule's file-modification test and the "same failure" comparison were never exercised.
- The figures of section 4 come from a one-off script over session files and transcripts. Attempt durations were not derived: the receipt files carry no finish time.
- The ledger states of D67, D73 and G1 (section 7).

## Recorded preference answers

All nine preference Questions are Answered in the ledger. These answers govern future probes and runs; they do not establish that any probe or run succeeded.

| Question | Binding answer |
| --- | --- |
| Q37 | `dev/eval-minesweeper-spec.md` version 1 as written: Python 3 standard library, checks `tests` and `launch`, one follow-up. |
| Q38 | All three installed routes, as in the operator's own settings; the report gives the mix. |
| Q39 | `driver` by default; `baseline` only when the request asks for the comparison. |
| Q40 | The same advance command again. But: unwarranted stops should be recorded and analysed. |
| Q41 | Yes, when the action is confined to the consumer repository or the evaluation root; otherwise escalate. |
| Q42 | The proposed table of section 4, recalibrated after the first run of each harness. |
| Q43 | One short probe per harness in a scratch consumer on a private server: launch, connection check, one small begin, exit, upload, usage read, and for Claude Code a comparison of the transcript sum with `/cost`. |
| Q44 | Every 15 minutes and at each stop. |
| Q45 | `report.md` in the evaluation root, with a short page under `docs/validation/` when the operator asks for one. |

Q43 requires one short connected probe per harness before the full matrix. Each uses an isolated consumer and private server, records a small begin, then exits, uploads and reads usage. Claude additionally compares transcript token totals with `/cost`. Any remaining coverage gap is reported explicitly.
