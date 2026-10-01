# Installed-format fixtures

These JSONL fixtures retain event ordering, usage fields and native session/response metadata from the three successful observability probes on 2026-09-26. Original stdout, stderr, command arrays and timings are preserved under `/srv/nvme/tmp/cq4-usage-20260926/`.

Prompt/response bodies, tool catalogs, filesystem paths and unrelated metadata are omitted. Numeric usage and cost values are unchanged. These are recorded-format checks, not CQ consumer evaluations. Synthetic edge cases are constructed explicitly in `HarnessUsageTest.scala`.

Versions and source interpretation: [observability investigation](../../../../../docs/drafts/20260926-usage-observability.md).

The versioned fixtures (`claude-2.1.285`, `codex-0.159.2`, `pi-0.99.1`, `codex-rollout-0.159.2`) retain the 2026-10-01 D73 probes of the installed versions, run with the production adapter argument lists and no MCP endpoints. Original stdout, launch scripts and the persisted Codex rollout are under `/srv/nvme/tmp/cq4-cross-cut/harness-probe/`. The same reduction rules apply; the structured one-word reply is kept.
