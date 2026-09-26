# Harness usage observability — 2026-09-26

## What was measured

One fresh, bounded, noninteractive invocation of each installed harness used its existing authentication and requested `Reply exactly OK. Do not use any tools.` All three exited successfully, returned `OK`, and made no tool calls. These are observability probes, not CQ workflow evaluations or an efficiency comparison: the models and effective system instructions differ. Claude and Pi received a short custom system prompt; Codex retained its built-in instructions.

| Harness version | Model | Input, including cache | Cache read / write | Output, including reasoning | Reasoning/thinking | Native estimated USD cost | Wall seconds |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Claude Code 2.1.280 | Reported `claude-opus-5-5` | 420 | 0 / 0 | 4 | 0 | 0.00176 | 3.470 |
| Codex CLI 0.156.1 | Requested `gpt-6-sol` | 18,232 | 0 / 0 | 5 | 0 | Not emitted | 4.622 |
| Pi 0.87.1, installed build dated 2026-09-25 | Reported `openai-codex` / `gpt-5.5` | 48 | 0 / 0 | 20 | 13 | 0.00084 | 5.323 |

The zeroes above are the values emitted by the harness. No nonzero cache behavior, interrupted run, resumed session, compaction, interactive collector, or alternative Pi provider was exercised. Pi's native `totalTokens` was 68; its 13 reasoning tokens are already inside its 20 output tokens. Codex was explicitly given the same model as the local configuration while ignoring unrelated user configuration. Claude's result identified cost basis `list`.

Raw process output, argument arrays, and timing/exit records are retained locally in `/srv/nvme/tmp/cq4-usage-20260926/`. Credentials and unrelated session transcripts were not copied into this repository. Each process ran in its own scratch directory with a 90-second deadline and process-group termination on timeout; none timed out. The measured wall times include startup and are single observations.

## Reproduction

Run in an empty scratch directory with the installed credentials. The Pi invocation retains provider extensions so the configured provider remains available. The Codex model argument should match the configuration being evaluated; the value below records this probe.

```sh
claude --print --safe-mode --tools '' --strict-mcp-config \
  --mcp-config '{"mcpServers":{}}' --no-session-persistence \
  --system-prompt 'Reply concisely.' --output-format stream-json --verbose \
  'Reply exactly OK. Do not use any tools.' > claude.jsonl

codex exec --json --ephemeral --skip-git-repo-check --ignore-user-config \
  --sandbox read-only -m gpt-6-sol -c 'approval_policy="never"' \
  -c 'features.shell_tool=false' \
  'Reply exactly OK. Do not use any tools.' > codex.jsonl

pi --offline --mode json --print --no-session --no-tools --no-skills \
  --no-context-files --no-prompt-templates --system-prompt 'Reply concisely.' \
  'Reply exactly OK. Do not use any tools.' > pi.jsonl
```

The probe runner supplied closed stdin and removed `CLAUDECODE` from the Claude subprocess environment if present. Its saved `commands.json` contains the exact argument arrays. Numeric observations were extracted from Claude `result`, Codex `turn.completed`, and Pi assistant `message_end` events, after checking exit status and response text.

## Collector semantics and limits

### Claude Code

The final result exposed `usage.input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, `output_tokens`, and `output_tokens_details.thinking_tokens`. Per-model entries exposed the corresponding counts, `thinkingTokens`, `costUSD`, and `costBasis`; `total_cost_usd` supplied the overall cost estimate. Input excluding cache plus both cache categories yields total input.

Official documentation distinguishes turn/main-loop usage from cumulative `modelUsage` and cost. Since 2.1.277, resume restores earlier model/cost totals. Assistant-message output counters can be placeholders; final results are needed for reliable output totals. Partial/crashed runs require incomplete accounting. These documented cases were not reproduced here. [Claude usage accounting](https://code.claude.com/docs/en/agent-sdk/cost-tracking)

USD fields use client-side pricing and are estimates; they are not subscription charges or authoritative billing. [Claude cost reporting](https://code.claude.com/docs/en/costs)

### Codex

The final `turn.completed.usage` exposed `input_tokens`, `cached_input_tokens`, `cache_write_input_tokens`, `output_tokens`, and `reasoning_output_tokens`. Cache counts subdivide input; reasoning subdivides output. No USD value appeared. The documented JSONL entry point is suitable for headless evaluation collection. [Noninteractive mode](https://learn.chatgpt.com/docs/non-interactive-mode)

There is an accounting trap: the version-matched event processor's `usage_from_last_total` copies `ThreadTokenUsage.total` into the final event. A collector must treat that as a thread-total snapshot, establish a baseline when resuming/forking, and avoid assuming the event name guarantees a turn delta. With no usage notification, the implementation returns default zeroes; schema presence alone cannot prove usage was observed. [Codex 0.156.1 event processor](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/exec/src/event_processor_with_jsonl_output.rs), [event types](https://github.com/openai/codex/blob/rust-v0.156.1/codex-rs/exec/src/exec_events.rs)

### Pi

The assistant's final `message_end.message.usage` exposed `input`, `output`, `cacheRead`, `cacheWrite`, `reasoning`, `totalTokens`, and per-category/total `cost`. Installed `pi-ai` types explicitly define reasoning as a subset of output. The installed Responses adapter subtracts cache read/write from provider input to produce Pi's `input`; add those cache categories back when normalizing inclusive input. It computes cost using model pricing.

The JSON stream repeats usage in partial updates and completed-turn/run events. Count finalized responses once. Compaction can expose its own usage; installed `AgentSession.getSessionStats()` also aggregates separate usage records, branch summaries, compactions, and tool-result usage. An adapter needs explicit scope/deduplication if it reads both these and assistant events. [Pi JSON event protocol](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/json.md)

Installed source inspected under `pi-monorepo`: `node_modules/@earendil-works/pi-ai/dist/types.d.ts`, `dist/bundle/chunks/chunk-KUNIGWOS.js`, `dist/core/agent-session.js`, `dist/core/compaction/compaction.d.ts`, and `dist/modes/print-mode.js`. The resolved package is `/nix/store/x8s3cp78yq69w1xdl4jgrdk05pd38x0s-pi-coding-agent-0.87.1-unstable-2026-09-25/lib/node_modules/pi-monorepo`.

The Responses adapter initializes counters to zero and uses zero defaults for missing provider fields, including reasoning. Thus an exposed zero can be unavailable data; the collector must retain that uncertainty when provider support cannot be established. Other Pi providers/extensions need their own capability checks. A displayed cost of zero can likewise mean absent pricing.

## Consequences for R09 and R31

Token monitoring is feasible for all three checked installations without asking an LLM to read the measurements. Whole-evaluation completeness still depends on collecting parent, child, retry, summary, and auxiliary activity, and on the source actually exposing it. Event-based collection can update as counts arrive; none of these probes establishes continuous per-token visibility or exact billing.

R31 makes the usage audit log an operational feature for tasks/cohorts; R09 evaluation reports consume that same log and accounting service. Require the first usable slice to retain structured observations and report coverage alongside usage. Compare accepted work on matched scenarios, count unsuccessful attempts, and keep independent assessment usage separate. Track parent-visible dispatch traffic as well as total hierarchy consumption. These measurements answer different questions: bounded parent traffic establishes the dispatch context property; matched end-to-end measurements establish any actual efficiency improvement.

Remaining runtime checks belong to the collector implementation: nonzero cache normalization, duplicate/replayed events, multiple turns and resume/fork baselines, failed/cancelled attempts, missing counters, auxiliary/compaction usage, and any interactive collection path. Preserve gaps explicitly rather than claiming complete usage from a successful one-shot probe.
