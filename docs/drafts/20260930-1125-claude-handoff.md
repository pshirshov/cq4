# Handoff — 2026-09-30 11:25 (before sandbox restart for the Pi update)

The user asked for a stop before operator probes OA1–OA3, because an updated Pi only becomes visible after this sandbox restarts. **Do not run OA1–OA3 until the user says go.**

## State

- Repository `/home/pavel/work/safe/flakes/cq4`, branch `main`, HEAD `419f216` plus this handoff. The user's `.gitignore` edit and the untracked `.claude/` are not ours to commit.
- Installed operator package `.local/release`, manifest `1944260beeb2ee3e3e658908df0d50c5f74cbeae0bd43fddced2b92304b6a90a` (I7/I8, D25 native fix). Nothing is pending installation. Evidence: `/srv/nvme/tmp/cq4-session-20260930`.
- Resolved this session: D25 (revision 8; the native image is built with the memory-access option, which only suppresses the warning); I7 and I8 Implemented.
- CQ record helper: `/srv/nvme/tmp/cq4-all-defects-20260929/cqapi.py`. Its `read(key)` maps only `I` to Ideas and every other prefix to Defects; use an explicit ledger for Goals, Questions, Milestones, Researches and OperatorActions. Never print the token.

## Open records (live project cq4)

| Record | State |
| --- | --- |
| D67 | The documented `claude --setting-sources project` launch never loads the CQ host. Local workaround (untracked): `.claude/settings.json` with `enabledMcpjsonServers: ["cq"]`. |
| D68 | Archive terminal items selects terminal items that open work depends on. Reproduced read-only; which relations count as a dependency is to be confirmed with the user. |
| D69 | The questions dialog shows every alternative twice; pick controls belong in the list. Confirm with the user that this is the duplication meant. |
| D70–D72 | User-reported, not reproduced: answers not propagated to the view until reload (the WebSocket path); no way to close the item pane; the item pane stays open after switching filters. |
| I9 → G1, M1, R1, Q1–Q6, OA1–OA3 | Auto-driver. Q1–Q5 answered, Q1 amended (Stop hook possible but not fully reliable), Q6 answered (use `settings.local.json`; note that `--setting-sources project` ignores it, so test `project,local`). G1 still names `/cq:auto:*` because three G1 revisions were rejected. The answers say `/cq:drive`/`/cq:park`, one toggle key, and a status line with state, root/phase and active child count plus transcript messages. Q2 asks for a subgraph-listing endpoint and worksets. |
| I10 → G3, T2, T3; I11 → G2, T1; M2 | Help dialog and large dialogs. T1–T3 are Ready. Q7–Q9 are open for the user. |
| I12 | Three-harness interactive evaluation matrix. Partly planned; Q10 (Goal split) is open. The next round should use Explorer result `f228be03`, proposal `e9a02652` and review `d2de0ff5`. |
| I13 | Agents should consult and propose Memories. Not planned. |
| I14 | Questions must state the recommended alternative. Not planned. |

## OA1–OA3 (after restart and the user's go)

- **OA1:** the Pi extension API. Target the Pi now visible (post-update); OA1 was written for 0.87.1, so record the version change on OA1 and R1.
- **OA2:** Claude Code in a scratch project: a Stop hook blocking N times, statusLine, nested versus flat command naming, keybindings. Also test `--setting-sources project,local`.
- **OA3:** Codex hooks, status line and keymap.
- Record evidence on each OA, then conclude R1 through a governing session.
- `.local/interactive/settings.json` still pins Pi 0.87.1 (`/nix/store/4z41…`). Change it only with the user's OK.

## Driving harness terminals

- Real tmux: `/nix/store/499dwp4ljzzbx5i5fhlxm6lwzncgqz61-tmux-3.7c/bin/tmux`, with a short socket via `-S /tmp/cqplan.sock`. The PATH `tmux` is a clipboard-only shim.
- Launcher `/srv/nvme/tmp/cq4-auto-driver-planning/launch.sh` unsets the inherited `CLAUDE*` variables, sets `CQ_TOKEN_FILE` and runs `claude --setting-sources project`; the wrapper `t` runs tmux with the socket.
- Start: `tmux -S SOCK new-session -d -s plan -x 200 -y 55 "asciinema rec -q FILE.cast -c launch.sh"`.
- On each launch, answer "New MCP server found" with Up, Up, Enter ("Use this MCP server"), then check `/mcp` for `cq … 9 tools`.
- Long input becomes `[Pasted text]` and needs a second Enter. Greyed prompt suggestions are not input; clear the line with `C-u`.
- Use one fresh governing session per workflow: the per-session child-run allowance ran out after three workflows.
- Recordings are `planning.cast` and `advance.cast`; the setup casts document D67.

## Process observations (not filed as defects)

- A governor created Milestones and some Questions directly when no Planner proposed them (M1, M2, Q3–Q5). This is disclosed in its summaries, not enforced by the host.
- The R1 Worker probe hit the 10-minute `executionMillis`. The host then deferred unchanged retries, including on another harness, as designed in `docs/design/cohorts.md:77,89`. That leaves a timed-out probe no path forward unless its input changes.
- A Planner child failed the host's member-coverage report contract once. The host rejected it correctly; the cause is not investigated.

Human release acceptance remains pending.
