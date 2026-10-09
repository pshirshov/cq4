# Native Codex subagents — probe for Research 19 (Task 77)

Research spike. Nothing is implemented. Every behavioural statement below is marked
**observed** (with the number of runs and a quoted excerpt) or **not observed** (with the reason).
Statements labelled *binary string* come from `strings` on the Codex binary; they are leads, not behaviour.
No statement comes from recollection of Codex documentation.

## 1. Setup

| Item | Value |
|---|---|
| Date | 2026-10-09 |
| `codex --version` | `codex-cli 0.162.0` (`/nix/store/n4rql6r3ky5lhymfjdh5spw04j60isi9-codex-0.162.0`) |
| Parent model | `gpt-6.1-sol`, reasoning effort `low` (`turn_context.model` / `.effort` of every parent rollout) |
| Child model | `gpt-6-luna` when `spawn_agent` or a role sets it; otherwise the parent's `gpt-6.1-sol` |
| `codex features list` | `multi_agent  stable  true`; `multi_agent_v2  stable  false`; `multi_agent_v2_dynamic_tools  under development  false`; `multi_agent_mode  removed  false` |
| With `-c features.multi_agent_v2=true` | `multi_agent_v2  stable  true` |
| Provider / account | `model_provider: openai`; account and user ids in the rollouts are not reproduced here |

Scratch layout (all outside the worktree): `CODEX_HOME=/tmp/cxq/home` (copied auth, scratch `config.toml`),
repository `/srv/nvme/tmp/cxq-probe/repo`, parent working directory = its linked worktree `/srv/nvme/tmp/cxq-probe/wt`
(so the Git common directory is `/srv/nvme/tmp/cxq-probe/repo/.git`, outside the cwd), sibling directory
`/srv/nvme/tmp/cxq-probe/sib`. These are outside `/tmp`, because `workspace-write` makes `/tmp` writable.

Command line template of every parent (`run.sh`, stdin from `/dev/null`). **Limitation:** the harness did not write a per-run invocation manifest. The exact launch of each run is the template with the run name's sandbox (the `sandbox` field of its `RUN` line in `run-summaries.txt`), the prompt file `prompts/p_<name>.txt` (`par`, `iso`, `fork2`, `role`, `role2`, `mcp`, `mcp3`, `kill`, `cancel`; the `f_none`/`g_none`/`v2_*`/`cq*` runs used inline prompts that were not retained, so those are reported from rollout excerpts only), and the extra arguments named in the section that reports the run (`-c features.multi_agent_v2=true`, `-c agents.enabled=false`, `--output-schema schema.json`, `-c developer_instructions=…`, MCP `-c` flags below). `kill_2` was launched by `launch_kill.sh`. `scratch-config.toml` now includes the `agents.cwdrole` entry that produced the `cwd` rejection.

```
CODEX_HOME=/tmp/cxq/home timeout 300 codex exec --json --skip-git-repo-check \
  -C /srv/nvme/tmp/cxq-probe/wt --sandbox <read-only|workspace-write|danger-full-access> \
  -c model_reasoning_effort=low [extra -c / --output-schema] "<prompt>"
```

Scratch `config.toml`: `model="gpt-6.1-sol"`, `model_reasoning_effort="low"`, `[features] multi_agent=true`,
and `[agents.<name>] description / config_file` entries for the probe roles. MCP runs add
`-c mcp_servers.stub.url=http://127.0.0.1:47651/mcp -c mcp_servers.stub.bearer_token_env_var=STUB_TOKEN_PARENT
-c mcp_servers.stub.default_tools_approval_mode=approve`, with fake tokens `parent-token-AAA` and `child-token-BBB`
in the environment. No CQ token and no real MCP credential was used. Files: `docs/validation/native-subagents-codex/`
(`run.sh`, `scratch-config.toml`, `roles/`, `prompts/`, `stub.py`, `stub-logs/`, `summ.py`, `par.py`, `run-summaries.txt`).
`run-summaries.txt` lists, for every run that created a rollout, the parent and child `cwd`, model, effort, approval policy,
sandbox type, session id relation, provider, per-thread rollout usage, and hashes of the child's last message and of the
parent's `FINAL_ANSWER` payload.

Rollouts below are quoted from `$CODEX_HOME/sessions/2026/10/09/rollout-*.jsonl` (not retained in the repository; the
excerpts quoted here and `run-summaries.txt` are the retained form).

## 2. Re-observation of the earlier probe's leads

| Lead | Result | Runs |
|---|---|---|
| `spawn_agent` takes only `task_name, message, fork_turns, model, reasoning_effort` | **Observed, with a qualification.** Without configured roles the parent printed `PARAMS: task_name, message, fork_turns, model, reasoning_effort` (iso runs: 6, order varies). With `[agents.<name>]` roles configured a sixth parameter `agent_type` appears: `PARAMS: agent_type, fork_turns, message, model, reasoning_effort, task_name`. `agent_type` selects a configured role (section 4); it is not a per-call setting of cwd, sandbox, tools, MCP or credentials. The parent's rollout also records the call as `{"task_name":"iso","fork_turns":"none","model":"gpt-6-luna","reasoning_effort":"low","message":"gAAAAAB…"}`: the `message` is stored encrypted in the parent rollout. | 6 (iso) + 8 role/MCP/fork runs |
| Child inherits cwd, sandbox policy, approval policy | **Observed.** Child `turn_context` equals the parent's in `cwd`, `sandbox_policy.type` and `approval_policy` (`never`) for all 3 sandbox modes, 2 runs each (`run-summaries.txt`, runs `iso_*`), e.g. parent `read-only` → child `{"cwd":"/srv/nvme/tmp/cxq-probe/wt","model":"gpt-6-luna","effort":"low","approval":"never","sandbox":"read-only"}`. | 6 + 2 (`v2_dfa*`) |
| Child final answer reaches the parent as a `FINAL_ANSWER` agent message and is in the child's rollout | **Observed.** Parent rollout: `{"type":"agent_message","author":"/root/c2","recipient":"/root","content":[{"type":"input_text","text":"Message Type: FINAL_ANSWER\nTask name: /root\nSender: /root/c2\nPayload:\nnot json: C2 finished (beta)"}]}`. The child's rollout holds the same text as `task_complete.last_agent_message`. | 6 (iso) + 2 (par) + others, all equal |
| Per-child token counts exist only in the child's rollout, not in the `exec --json` stream | **Observed.** `par_1`: stream `turn.completed` = `{"input_tokens":76215,"output_tokens":302,…}` equals the parent rollout total (76215 / 302) exactly; the two children's rollouts each hold `38354 / 48` and are not included. Identical in `par_2`. In 6 iso runs the child's rollout total (e.g. 113880 in) exceeds the parent's stream total (43995 in). The stream shows a child only as `{"type":"collab_tool_call","tool":"wait","sender_thread_id":"…","receiver_thread_ids":[],"agents_states":{},"status":"completed"}` — receiver ids empty. | 8 |

## 3. Question 2 — isolation by parent sandbox (observed, 2 runs per mode)

Child task: `pwd`; write `./child_cwd.txt`; write `/srv/nvme/tmp/cxq-probe/sib/child_sib.txt`; write
`/srv/nvme/tmp/cxq-probe/repo/.git/child_gitdir.txt`; `nix store info 2>&1 | head -3`. The file system was checked after each run.

| Parent sandbox | child cwd write | sibling dir write | Git common dir write | `nix store info` in child |
|---|---|---|---|---|
| `read-only` | fail: `zsh:1: read-only file system: ./child_cwd.txt` (2/2) | fail (2/2) | fail: `read-only file system: …/repo/.git/child_gitdir.txt` (2/2) | fails: `error: cannot connect to socket at '/nix/var/nix/daemon-socket/socket': Operation not permitted` (2/2) |
| `workspace-write` | **ok** (2/2; file exists) | fail: `read-only file system: …/sib/child_sib.txt` (2/2) | fail (2/2) | fails with the same socket error (2/2) |
| `danger-full-access` | ok (2/2) | **ok** (2/2) | **ok** (2/2) | **succeeds**: `Store URL: daemon / Version: 2.34.8 / Trusted: 0` (2/2; plus 2 more under `multi_agent_v2=true`) |

(The `NIX_RC=0` line in the raw outputs is the exit status of `head`, not of `nix`; success is read from the output.)
After the six runs the file system held `wt/child_cwd.txt` (workspace-write, danger), `sib/child_sib.txt` and
`repo/.git/child_gitdir.txt` (danger only).

Does any setting give a child a narrower sandbox or a different working directory?

- **Narrower sandbox: not obtainable.** Role files accepted `sandbox_mode = "read-only"` (`ro`, 1 run), `default_permissions = ":read-only"`
  (`ro2`, 2 runs) and `sandbox_mode = "workspace-write"` + `approval_policy = "untrusted"` (`ro3`, 2 runs) without an error, but under a
  `danger-full-access` parent every such child still ran with `"sandbox":"danger-full-access","approval":"never"`
  and wrote `./child_cwd.txt` (`W_CWD_OK`; `run-summaries.txt` runs `role_dfa`, `role2_dfa_1/2`). A `workspace-write` role did **not widen** a `read-only` parent
  either (`role3_ro`, 1 run: child `sandbox: read-only`, `W_CWD_FAIL`). The parent's sandbox is the child's sandbox.
- **Different working directory: not obtainable.** A role file with `cwd = …` was rejected: `Ignoring malformed agent role definition: failed to
  deserialize agent role file at /tmp/cxq/roles/cwdrole.toml: unknown field \`cwd\`` (2 runs, emitted as `error` items in the `exec --json` stream),
  and `spawn_agent` has no cwd parameter. Every child ran in the parent's `cwd`.
- Consequence for CQ: every managed Codex launch uses `danger-full-access`; a native child of such a session is unconfined (observed: sibling and Git common
  directory writes ok, 2/2). A child cannot be placed in a per-attempt worktree and its writes land in the parent's tree.

## 4. Question 1 — what can be fixed per subagent (observed)

Through `spawn_agent` (per call): `model` and `reasoning_effort` take effect on `fork_turns:"none"` (every iso run: child `model: gpt-6-luna, effort: low`
while the parent is `gpt-6.1-sol`). With `fork_turns:"all"` the `model` argument was accepted without error but **not applied**: both runs (`fork2_1/2`) passed
`"model":"gpt-6-luna","reasoning_effort":"low"` and the child's `turn_context` was `gpt-6.1-sol / low`. Whether `reasoning_effort` is applied with `all` is **not observed**: the requested `low` equals the parent's `low`, so an override cannot be told from inheritance. Also `fork_turns:"none"` without `reasoning_effort` gave the
child effort `medium`, not the parent's `low` (`fork2_*`, `g_none`). The `fork_turns` values `none`/`all` are the only ones used; the binary contains the
string ``fork_turns must be `none` or `all` `` (*binary string*).

Through configuration: `[agents.<name>] description, config_file` defines a role selectable with `agent_type`. The parent's `spawn_agent` description then lists built-in
roles `default`, `explorer`, `worker` plus the configured ones; quoted from the parent output (`role_dfa`):

```
ro: {
Read-only probe role with a narrower sandbox
- This role's model is set to `gpt-6-luna` and its reasoning effort is set to `low`. These settings cannot be changed.
}
```

Role-file keys observed (role_dfa 1 run, role2_dfa 2 runs, role3_ro 1, mcp runs 2, mcp3 1):

| Role-file key | Effect on the child |
|---|---|
| `model`, `model_reasoning_effort` | **applied** (`ro` child: `gpt-6-luna`/`low`; the description says they "cannot be changed") |
| `developer_instructions` | **applied** (every role child began its answer with its `ROLE_MARKER_…` token: 8 role children) |
| `[features] shell_tool=false, unified_exec=false` | **applied** (`noshell` child: `ROLE_MARKER_NOSHELL / Unable to run command: no shell execution tool is available.`, 1 run) |
| `sandbox_mode`, `default_permissions`, `approval_policy` | accepted, **not applied** (above) |
| `cwd` | **rejected** (`unknown field \`cwd\``) |
| `[mcp_servers.<n>]` with `url`, `bearer_token_env_var` / `http_headers` / `enabled=false` | accepted (without `url` the role is rejected: `invalid transport`), **not applied** (section 5) |

A role whose file is malformed is dropped silently except for an `error` item in the stream; the parent then fails the spawn with `unknown agent_type 'cwdrole'`.

`multi_agent_v2`: with `-c features.multi_agent_v2=true`, 2 `danger-full-access` runs showed the same `spawn_agent` parameter list, the same tool names (`spawn_agent`, `wait_agent`), the same
inheritance and the same `nix store info` result as the default. The rollout `session_meta` carries `"multi_agent_version":"v2"` in the default runs as well, so it does not distinguish the flag. **What the flag changes was not observed**:
the only differences found are *binary strings* for `features.multi_agent_v2.{max_concurrent_threads_per_session,min_wait_timeout_ms,max_wait_timeout_ms,default_wait_timeout_ms}` and `agents.max_threads` / `agents.max_concurrent_threads_per_session`;
none was exercised. `wait_agent` clamps `timeout_ms` to a minimum of 10000 ms in the default configuration (observed, section 8).

Tool surface of the parent (observed in `cancel_1`, 1 run): `collaboration.spawn_agent`, `followup_task`, `interrupt_agent`, `list_agents`, `send_message`, `wait_agent`. There is no close/terminate tool.

CQ's current launch flags (observed, switch isolation, one run each unless stated): `-c agents.enabled=false` alone → `NO_AGENT_TOOLS`; `-c features.multi_agent=false -c features.multi_agent_v2=false -c agents.enabled=false`
→ `NO_AGENT_TOOLS` (2 runs); `-c features.multi_agent=false` alone, `-c features.multi_agent_v2=false` alone, and both together **without** `agents.enabled=false` → the six `collaboration.*` tools remain.
On 0.162.0 the switch that removes native subagents is `agents.enabled=false`.

## 5. Question 3 — MCP servers and tokens (observed, stub server logging `Authorization`)

Parent configured `stub` with `bearer_token_env_var=STUB_TOKEN_PARENT` (`parent-token-AAA`). `mcp_1`, `mcp_2`: parent calls `whoami` itself, then spawns `plain` (no role), `m1` (role `mcp1` adds server
`stubchild` → `/mcp-child`, `bearer_token_env_var=STUB_TOKEN_CHILD`=`child-token-BBB`), `m2` (role `mcp2` overrides `stub` → `/mcp-override`, `http_headers.Authorization = "Bearer child-literal-CCC"`).
`mcp3` (**1 run retained**; an earlier statement of 3 runs is withdrawn, only one parent/child pair is in `run-summaries.txt`): role sets `[mcp_servers.stub] enabled=false`.

- Every child could call the parent's server: `mcp__stub__whoami` → `STUB_OK auth_seen=Bearer parent-token-AAA` (plain ×2, m1 ×2, m2 ×2 over `mcp_1`/`mcp_2`; m3 ×1 in the single retained `mcp3` run: 7 child calls in 3 runs; the stub logs hold 4+4+1 `whoami` calls including the parent's two).
- Stub log of `mcp_1` (16 requests): 4 separate `initialize` / `notifications/initialized` / `tools/list` / `tools/call whoami` sequences (parent + 3 children), **all** `path=/mcp`, **all** `auth=Bearer parent-token-AAA`.
  No request reached `/mcp-child` or `/mcp-override`; `child-token-BBB` and `child-literal-CCC` never appeared (`mcp_2` identical). No child had a `mcp__stubchild__*` tool.
- `mcp3`: the `enabled=false` override was ignored; the child still called `stub` with the parent's token (`stub-logs/mcp3.stublog`).
- So a child **can** call an MCP server configured for the parent, **with the parent's token**; role files do not give it a server, token, header or restriction of its own. No other means was found; the `spawn_agent` call has no such parameter.
  Not tested: environment-variable differences (the child is a thread of the same process, it has no environment of its own); MCP tool allow-lists set in the parent config (they apply to the parent's server definition and so to the child equally).
- The child's calls are distinguishable at the server. A `tools/call` request body carries `params._meta` with the caller's thread, quoted from `stub-logs/mcp3.stublog` (child `m3`):
  `"x-codex-turn-metadata":{"session_id":"01a1205c-cd8c-…","thread_id":"01a1205c-e72b-…","parent_thread_id":"01a1205c-cd8c-…","subagent_kind":"thread_spawn","thread_source":"subagent","sandbox_mode":"danger-full-access",…},"threadId":"01a1205c-e72b-…","sessionId":"01a1205c-cd8c-…"`.
  This is client-supplied metadata next to an unchanged bearer token (1 run with the field logged), not a credential.

## 6. Questions 4 and 5 — result return, schema, usage (observed)

Question 4, hashes (`par_1`, `par_2`; child answers `not json: C1 finished (alpha)` / `not json: C2 finished (beta)`): the child's `task_complete.last_agent_message` (sha256 prefix `2a60d5db3698` for c1, `b6316f885afe` for c2),
the `Payload:` of the `FINAL_ANSWER` agent message in the parent's rollout (`equal=True` for both children, both runs) and the value the parent put into its final JSON (`c1`, `c2` keys) have identical hashes in both runs.
Over the six iso runs the child's last message equalled the `FINAL_ANSWER` payload in all 6 (`run-summaries.txt`). Observed fidelity depends on the parent model copying: in the iso runs the parent reply
`CHILD: …` was a paraphrase-free copy in 6/6 by inspection, but it was not hashed. The `spawn_agent` `message` to the child is encrypted in the parent rollout and cannot be compared. `wait_agent` returns only `{"message":"Wait completed.","timed_out":false}`, never the answer.

Output schema: `--output-schema schema.json` (object with `c1`, `c2`, `order`) applied to the **parent's** final message only (`par_1/2` parent final: `{"c1":"not json: C1 finished (alpha)","c2":"not json: C2 finished (beta)","order":"c2 finished first"}`).
The children's answers were free text that does not satisfy that schema, and `spawn_agent` has no schema parameter. So the parent's `--output-schema` does not constrain a child (2 runs). Enforcement through configuration (a role file key or `[agents]` setting) was **not probed**, so "no schema can be enforced by any means" is **not observed**; the binary was not searched for a role-level schema key.

Question 5, usage: each child has its own rollout file with its own `token_count` totals and `token_usage_record` events. A `token_usage_record` carries `thread_id` (the child's), `session_id` (the **parent's**), `turn_id` and `root_turn_id` (the parent's root turn);
the child's `session_meta` carries `id`, `parent_thread_id`, `source.subagent.thread_spawn{parent_thread_id, depth:1, agent_path:"/root/c2", agent_nickname:"Carson"}`. In `par_1` the children are `…2273fed5c293` (c2) and `…3a57539ce55d` (c1), each 38354 in / 48 out (38402 total),
tied to the child by `thread_id`/`agent_path`. The `exec --json` stream gives only the parent's `turn.completed` usage (76215 in / 302 out), which equals the parent rollout total exactly, so child usage is **not** in the stream and not folded into the parent's number (8 runs).

## 7. Question 7 — context and independence (observed)

- `fork_turns:"none"` (`f_none`/`g_none`, 4 runs): the child did not see a user-message fact (`USER_LABEL=PURPLE-7731` → `NONE`). `fork_turns:"all"` (4 runs): the child saw it (`PURPLE-7731`) and ran on the parent's model (section 4).
- Parent configuration-level instructions are visible with `fork_turns:"none"`: with `-c developer_instructions="DEV_LABEL=ORANGE-5512 (a harmless test label)."` on the parent, `g_none` answered `NONE; ORANGE-5512` and `USER_LABEL=NONE; DEV_LABEL=ORANGE-5512` (2 runs). A child therefore
  inherits the parent's developer instructions and config even when conversation context is excluded. (An earlier pair of runs with a labelled "secret" was uninformative: the models answered `REDACTED`.)
- Shared with the parent (child `session_meta` / `turn_context`, every pair in `run-summaries.txt`): `session_id` = parent's id (`session_id_is_parent True`), `creator_account_id`/`creator_user_id` equal to the parent's, `model_provider: openai`, `cwd`, sandbox policy, approval policy, `originator: codex_exec`,
  the Codex process and its MCP connections' credentials. Can differ: model and effort (only with `fork_turns:"none"`), role instructions, role `features`.

## 8. Question 6 — lifecycle (observed)

Parallel children (`par_1/2`): spawns 16.527 and 19.707 s (UTC `11:00:`), `c1` sleeps 12 s, `c2` 4 s. `wait_agent(timeout_ms:60000)` returned at 11:00:28.727 `{"message":"Wait completed.","timed_out":false}`; the `FINAL_ANSWER` agent message of `c2` appears 12 ms
later (`11:00:28.739`). A second `wait_agent` returned at 11:00:34.064, `c1`'s `FINAL_ANSWER` at 11:00:34.073. `wait_agent` returns when **any** child finishes; the answer arrives as an agent message in the parent's history, in order of completion (c2 then c1 in both runs).
In the stream each wait is one `collab_tool_call` item with empty `receiver_thread_ids`.

Cancellation (`cancel_1`, 1 run): `wait_agent(timeout_ms:5000)` → `{"message":"Wait timed out.\n\nRequested timeout of 5000ms was clamped to the minimum of 10000ms.","timed_out":true}`.
`interrupt_agent {"target":"slow"}` → `{"previous_status":"running"}`. The child's rollout then holds `turn_aborted reason:"interrupted"` and a developer message `<turn_aborted> The previous turn was interrupted on purpose. Any running unified exec processes may still be running…`; it has no `task_complete`.
Its command (`sleep 91; echo late > …/late_slow.txt`) was not stopped by the interrupt: the exec item completed `status:"failed","exit_code":137` at 11:02:29.585, 0.2 s after the parent's `task_complete` (11:02:29.392), which suggests it was killed when the parent process ended. `late_slow.txt` was never created. (The `task_complete` is a rollout event, not a process-exit observation; the ordering of the two timestamps was misstated in an earlier draft: 11:02:29.392 precedes 11:02:29.585, so the exec failure was recorded 0.2 s *after* `task_complete`.)

SIGKILL of the scratch parent (`kill_1`, `kill_2`; only the scratch `codex` PID recorded by `launch_kill.sh` was signalled): the child ran `zsh -c 'sleep 61; echo survived > …/killtest.txt'` as a descendant of the parent. After `kill -9` (commands: `launch_kill.sh` for `kill_2` recorded the PID with `echo $$ > /tmp/cxq/kill2.pid` then `exec codex …`; the kill was `kill -9 $(cat /tmp/cxq/kill2.pid)` and the polling was `ps`/`pgrep` of `sleep 61`; **these two command lines and the `ps` output were not retained, so the process-state claims below are reported from the investigator's session log, not from a retained artifact**; only the unfinished rollouts and the truncated stream are retained evidence):
the parent and the child's `zsh` and the `codex-code-mode-host` were gone, the orphaned `sleep 61` ran to its end (observed alive 3, 20 and 40 s after the kill in `kill_1`, 6 s in `kill_2`, gone after 61 s), `killtest.txt` was **not** written (its shell had died), no child or parent rollout received a `task_complete` or `turn_aborted`
(last events are `token_usage_record`), the stream ended at `{"type":"item.started","item":{"type":"collab_tool_call","tool":"wait",…,"status":"in_progress"}}`. The children are threads inside the parent process; no child process or rollout finished after the parent was killed.

## 9. Per-role conclusion

Guarantees of a host-launched child that a native Codex child lacks (all observed above): its own working directory (per-attempt worktree) — a native child runs in the parent's cwd; its own scoped MCP token and role-specific tool allow-list — a native child uses the parent's server definitions and token; a per-role sandbox —
a native child has exactly the parent's; an enforced output schema (`--output-schema`; for a native child only the `spawn_agent` parameter list and the parent flag were checked) and a private `--output-last-message` file — a native child returns free text as an agent message; usage in the process's own stream — only in rollout files; an independent session and credentials — shared session, account and provider;
an independent lifecycle supervised by the guardian — a thread of the parent process that dies with it and is not reported in the stream.

| Role | Verdict | Guarantee of the host-launched child that fails |
|---|---|---|
| Explorer | **Feasible with named weaker guarantees** | Scoped read-only MCP token (child holds the governor's token and every governor tool); own cwd/worktree; output schema; usage in the stream. Shell can be removed per role (`features.shell_tool=false`, 1 run); the filesystem boundary cannot be (child is as unconfined as a `danger-full-access` parent). |
| Planner | **Feasible with named weaker guarantees** | Same as Explorer. |
| Worker | **Not feasible** | Per-attempt worktree and candidate capture: the child writes into the parent's tree (cwd cannot be set, 2 runs of `cwd` rejected); sandbox cannot be narrowed (5 role runs); scoped token not available; result not schema-checked; usage and failure not visible in the stream. |
| Plan review | **Feasible with named weaker guarantees** | Independence: same session, account, provider; inherits the parent's developer instructions even with `fork_turns:"none"`; only model/effort can differ (and only with `none`). Scoped token, output schema, usage in the stream, own cwd are lost. |
| Candidate review | **Not feasible** | Reviewer needs the `Check` branch of the local `workspace` tool with its own token and a working directory on the candidate tree; neither a token nor a cwd of its own can be given to a child (section 3 and 5). Independence is also weakened as for Plan review. |

## 10. Limits of this evidence for an attached governing session

The parent was a fresh `codex exec` process in a scratch `CODEX_HOME`, not the operator's attached governing session. Results that may not transfer:

- Attached sessions may run through the shared local app-server daemon or the TUI (`codex agents`, `~/.codex/app-server-daemon` exist): MCP configuration, role discovery (`config_file` resolution, project-level `.codex/` roles), interrupt and cancellation paths, the survival of a child after the front-end dies, and approval behaviour were not exercised there. In `exec` the approval policy was `never`; an interactive session with approvals could prompt for a child's commands.
- Persistence: exec parents wrote rollouts to the scratch home; the attached session's rollout location and the `thread_source` (`user` vs interactive) may differ. Parent and child died together under SIGKILL of an `exec` process; a daemon-hosted session may outlive its front-end.
- The sandbox results come from the Linux sandbox of 0.162.0 with `read-only`/`workspace-write`/`danger-full-access` passed on the command line; the attached session may use `permission_profile`/managed requirements that this probe did not set.
- MCP tokens: tested with a stub using `bearer_token_env_var`; the child shared the parent's connection settings. A real CQ governing session uses the same mechanism per the repository, but the CQ server was not contacted.
- Models: a single account; `gpt-6.1-sol` and `gpt-6-luna` at low effort; model-dependent behaviour (answer copying, refusals) is not characterised. Single-run items are marked above. Version: 0.162.0 only.
- `multi_agent_v2` semantics, `agents.max_threads`/`max_depth`, nested children and `followup_task`/`send_message`/`list_agents` were not exercised.

## 11. Memory candidates

- On Codex 0.162.0 only `-c agents.enabled=false` removes the `collaboration.*` tools; `features.multi_agent=false` and `features.multi_agent_v2=false` (alone or together) leave them in place. Applies to CQ's managed launch flags. Evidence: section 4, runs `cq_agents_only`, `cq_v2_only`, `cq_multi_v2`, `cqflags_*`.
- Codex 0.162.0 agent role files (`[agents.<n>] config_file`) apply `model`, `model_reasoning_effort`, `developer_instructions` and `[features]` to the child but ignore `sandbox_mode`, `default_permissions`, `approval_policy` and `mcp_servers`, and reject `cwd`; a child always has the parent's cwd, sandbox, approval policy, MCP servers and bearer tokens. Evidence: sections 3–5.
- MCP `tools/call` requests from a Codex subagent carry `params._meta.x-codex-turn-metadata` with the child's `thread_id`, `parent_thread_id` and `subagent_kind:"thread_spawn"` under the parent's bearer token. Evidence: `stub-logs/mcp3.stublog`.

## 12. Cleanup

The scratch copy of the operator's credentials (`/tmp/cxq/home/auth.json`) and the scratch tree `/tmp/cxq` and `/srv/nvme/tmp/cxq-probe` were removed after the note was written; `/tmp/cxp/home/auth.json` from the earlier probe did not exist at the start of this Task.
No token or credential is committed: the only bearer strings in the repository files are the fake `parent-token-AAA`, `child-token-BBB`, `child-literal-CCC`.
