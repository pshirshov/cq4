# Installed-format fixtures

These JSONL fixtures retain event ordering, usage fields and native session/response metadata from the three successful observability probes on 2026-09-26. Original stdout, stderr, command arrays and timings are preserved under `/srv/nvme/tmp/cq4-usage-20260926/`.

Prompt/response bodies, tool catalogs, filesystem paths and unrelated metadata are omitted. Numeric usage and cost values are unchanged. These are recorded-format checks, not CQ consumer evaluations. Synthetic edge cases are constructed explicitly in `HarnessUsageTest.scala`.

Versions and source interpretation: [observability investigation](../../../../../docs/drafts/20260926-usage-observability.md).

The versioned fixtures (`claude-2.1.285`, `codex-0.159.2`, `pi-0.99.1`, `codex-rollout-0.159.2`) retain the 2026-10-01 D73 probes of the installed versions, run with the production adapter argument lists and no MCP endpoints. Original stdout, launch scripts and the persisted Codex rollout are under `/srv/nvme/tmp/cq4-cross-cut/harness-probe/`. The same reduction rules apply; the structured one-word reply is kept.

## Provider refusals (`abstention/`)

The files under `abstention/` are whole native stdout transcripts of the pinned harnesses (Claude Code 2.1.285, Codex 0.160.0, Pi 0.99.1), captured on 2026-10-06 with the output flags of the production adapters and a one-line prompt, each in a private empty configuration directory (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`, `HOME`) with a wrong key. No real login was read. Unlike the files above they are not reduced: only the capture directory, the stub's port and the provider's masked echo of the wrong key are replaced. The capture scripts, the stub, every stdout, stderr, exit status and the stub's request log are under `/srv/nvme/tmp/cq4-crosscut4/abstention-capture/`.

- `*-credential-*`: the provider's own endpoint answered the wrong key (`api-key`), a wrong OAuth token (`oauth`) or no credential (`none`).
- Every other case: the harness's base URL pointed at a local stub that answered each request with one HTTP status and error body. The bodies imitate the error formats of the Anthropic and OpenAI APIs as their public documentation describes them, written from memory and not fetched; `quota-subscription` (Claude Code: a 429 with the unified rate-limit headers and a wrong OAuth token) and `quota-usage-limit` (`usage_limit_reached`) imitate what the harness's own client reads and are not publicly documented. The harness's reaction and output are real; the provider's reply is not.
- `unclassified-*`: refusals the host does not read as an abstention (a model the provider does not know, a 403, a 500 of the ChatGPT backend through Pi).

Exit statuses: Claude Code and Codex exit 1 in every case; Pi exits 0 in every case. `AbstentionClassifierLocal` lists every file with the class it is read as.
