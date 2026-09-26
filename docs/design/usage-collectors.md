# Native usage collection

`host/HarnessUsage` reads a completed native JSONL output file into the existing `UsageMeter` and `UsageUpload` contracts. It does not read live process pipes; the guardian owns draining and byte limits. Collection requires an explicit attempt, verified harness version, fresh/resumed origin, frozen collection timestamp and native-evidence artifact handle. Process settlement, native completion, usage coverage and semantic acceptance remain separate facts.

| Source | Counted records | Accounting scope |
| --- | --- | --- |
| Claude Code 2.1.280 | Final `result.modelUsage`, separately per model | Session cumulative; main-loop `usage` and overall cost are not added again |
| Codex 0.156.1 | `turn.completed.usage` | Thread cumulative, including cached input and reasoning output |
| Pi 0.87.1 | Final assistant `message_end.message.usage` | Per-response increments; partial updates, turn/run aggregates are excluded |

Pi response IDs deduplicate repeated responses even across repeated turn wrappers. Where a provider omits response IDs, the collector reports that its fallback identity is limited to native turn/model/timestamp. A contradictory repetition retains the first observation and adds a coverage gap. Pi's default-zero `totalTokens` does not invalidate separately reported positive counts; a contradictory positive total is rejected.

The version argument is checked, not inferred from arbitrary output. The harness adapter must obtain the installed version before launching. Additional versions require source/capability verification; this does not change CQ's single mutable 0.1.0 contract policy.

## Normalization and uncertainty

Claude's per-model input excludes cache; the audit normalizer adds both cache categories. Codex input includes cache. Pi's input excludes cache: collection reconstructs inclusive input before separately marking ambiguous zero cache subcategories unknown. Reasoning is a subset of output in all three formats. Native JSON is retained by handle for inspecting the original fields.

Codex and Pi use zero defaults in their adapters. Zero token fields are therefore unknown unless the collector has evidence of a measured value; positive inclusive totals remain available. Pi's inclusive input can be known while its zero cache subdivisions remain unknown. Claude's final per-model counters retain explicitly reported zeroes. Missing fields never become measured zeroes. Malformed, negative, overflowing or inconsistent counts produce an explicit gap.

Claude and Pi USD values are **client estimates**, never actual billing. The pricing revision is unavailable and recorded as a gap. Codex exposes no native monetary cost. Pi's zero cost is ambiguous and remains unknown. Estimated amounts use the audit's decimal arithmetic. Decimal scale and expanded length are bounded before formatting, preventing a short exponential number from allocating an arbitrarily large string.

Fresh cumulative meters have zero token baselines. Claude's fresh USD estimate baseline is zero even when an early observation lacks a cost. Resumed cumulative meters require captured inclusive token and cost baselines; missing baselines leave deltas unknown. Decreasing totals or values below a captured baseline stop collection for that meter and require explicit correction or a new meter. The validation retains prior known values across missing samples, without replacing missing observations with those older values. Collection does not invent a reset.

## Bounds and replay

Collection accepts at most 32 MiB, 100,000 LF-delimited events, 1 MiB per event, 4,096 samples and 64 meters. It uses strict UTF-8; Unicode line/paragraph separators inside JSON strings do not split events. An unterminated final line is retained only in native evidence and reported as a gap. Diagnostics are bounded to 32 entries, with an explicit omission marker. Earlier usable observations survive later malformed or truncated events.

Observation IDs are deterministic for a frozen attempt/meter/native line position. Recollection of the same immutable evidence and context yields identical uploads. Cumulative snapshots replace the projected total rather than adding to it. Changed evidence requires an explicit audit correction, not reuse of an observation ID. A changed native session identity is not attributed to the original attempt.

The caller registers the attempt before collection, publishes native evidence, then registers meters and uploads observations through the authenticated host usage API. Collection gaps belong in the attempt outcome; sample-specific gaps remain in observations. Retrying those immutable operations does not duplicate accounting. The collector does not declare a task accepted, fabricate process completion, or modify ledger history.

## Remaining integration

The installed-format probes and HTTP/PostgreSQL replay establish parsing and audit compatibility. They do not establish a complete live CQ evaluation. Supervisor attachment, durable upload recovery, large-transcript manifests, interactive governing-session capture and actual consumer evaluations remain M2 work. Pi assistant-event coverage explicitly excludes auxiliary/compaction/tool-result usage until separate observations exist. Other provider extensions and resumed/forked native sessions need runtime capability evaluation.

The [observability investigation](../drafts/20260926-usage-observability.md) records the primary sources and native probes. [Collector verification](../validation/m2-collectors.md) records executable evidence and remaining gaps.
