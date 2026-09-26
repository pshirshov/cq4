# M2 harness adapter capabilities

Scope: actual Claude Code 2.1.280, Codex 0.156.1 and Pi 0.87.1 shellouts under the existing `JobSupervisor`, C guardian and isolated Git workspaces. These are capability probes, not the required unrelated consumer builds or governing/child evaluations.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

## Live evidence

Each worker/reviewer receives a scoped credential and an artifact handle containing an unpredictable marker. The model must read the marker from CQ and return exact structured content. Workers must write it byte-for-byte to `observed.txt`; reviewers are asked to attempt a native write if available, and `forbidden.txt` must remain absent. A direct CQ mutation using each child token is independently denied before launch. The fixture requires confirmed process settlement and native completion.

| Evidence directory | Harness/model and role | Result | Reported inclusive input + output tokens | Native USD provider estimate |
| --- | --- | --- | --- | --- |
| `20260926T232725-adapter-codex-worker` | Codex / `gpt-6-sol`, Worker | PASS | 69,954 | Unknown |
| `20260926T232903-adapter-codex-reviewer` | Codex / `gpt-6-sol`, Reviewer | PASS; attempted patch rejected by read-only sandbox | 61,052 | Unknown |
| `20260926T233128-adapter-pi-worker` | Pi / `gpt-5.5` via `openai-codex`, Worker | PASS | 8,777 | 0.03684 |
| `20260926T233557-adapter-pi-reviewer` | Pi / `gpt-5.5` via `openai-codex`, Reviewer | PASS | 4,783 | 0.020903000000000002 |
| `20260926T233639-adapter-claude-worker` | Claude / `claude-opus-5-5`, Worker | PASS | 33,964 | 0.1041062 |
| `20260926T233712-adapter-claude-reviewer` | Claude / `claude-opus-5-5`, Reviewer | PASS | 14,393 | 0.06585479999999999 |

Counts and costs above come from each run's exported `usage-summary.json` after publication through CQ's HTTP service and fresh PostgreSQL database. Raw observations, attempts, outcomes, native output, job records, source hashes and command arrays are retained. The private launch assets contain short-lived scoped tokens; they are not copied into repository documentation. Tokens are explicitly unattributed capability-fixture work. Cache and reasoning subsets are not added again. Missing counters, absent pricing revisions and Pi auxiliary-call coverage remain explicit; every meter is partial. Provider estimates are not actual billing. These different models and single observations support no comparative efficiency claim.

Claude's observed worker inventory was Bash/Edit/Glob/Grep/Read/Write, StructuredOutput and the three CQ read tools. Its reviewer inventory contained StructuredOutput and the three CQ read tools. Pi emitted real `cq_read` calls and worker `write` calls. Codex emitted real CQ MCP calls and a worker command; reviewer stderr records `writing is blocked by read-only sandbox; rejected by user approval settings`.

## Deterministic checks and corrections

The fast suite covers environment/credential isolation, explicit routing, role inventories and private asset identity. The Node HTTP fixture verifies Pi initialization ordering, configured-tool registration, Unicode results, permission errors, response bounds and redirect rejection. Live models are excluded from deterministic checks.

- `20260926T232539-adapter-codex-worker` reproduced a completed but semantically blocked model result: disabling the code-mode host made MCP unavailable. The adapter now enables that runtime while preserving the other restrictions; the worker/reviewer reruns above pass. Usage from the failed probe remains in its retained audit.
- `pi-bridge-before.log` reproduced rejection of `tools/list` before `notifications/initialized`. The bridge now completes that handshake; `pi-bridge-after.log` passes, as do both actual Pi probes.
- `20260926T233821-fast` reproduced silently ignored Claude provider selection: the unsupported-provider rejection assertion failed because launch succeeded. Claude now rejects unverified provider routes. The verified Anthropic launch is unchanged.

`20260926T233854-fast` passes all 58 Scala scenarios and the Node bridge check after these corrections. Source/command manifests and complete reports are retained. Astra independently approved `7e1f87d`, with no blocking or major findings. The reviewed source hashes match the final fast evidence. Approval covers the bounded adapter increment and its six capability probes; the remaining M2 work below stays open.

## Reproduction and remaining work

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check fast
# Each of the following runs a real configured model:
./dev/adapter-probe claude Worker
./dev/adapter-probe claude Reviewer
./dev/adapter-probe codex Worker
./dev/adapter-probe codex Reviewer
./dev/adapter-probe pi Worker
./dev/adapter-probe pi Reviewer
```

The probe owns a fresh database and private consumer Git repository. Model/provider overrides are explicit `CQ_CLAUDE_MODEL`, `CQ_CODEX_MODEL`, `CQ_PI_MODEL` and corresponding `_PROVIDER` variables; installed versions are checked. The fixture requires configured harness authentication and does not inspect or print its contents.

Supervisor role composition, local dispatch/workspace tools, reference assembly, general result validation, durable upload recovery, large-transcript storage and governing-session accounting remain pending. No parent/child result chain, consumer acceptance or M2 milestone completion is claimed. The current output bound is 256 KiB per stream and the probe requires valid UTF-8; production handling of malformed/large transcripts remains integration work. Earlier native distribution evidence still applies to M0 only.
