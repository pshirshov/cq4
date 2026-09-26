# M2 native usage collectors

Scope: finalized native-format collection for the installed Claude Code 2.1.280, Codex 0.156.1 and Pi 0.87.1, followed by the existing authenticated operational audit. These are retained-format replay checks. No new live harness call or CQ consumer evaluation is claimed.

## Evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Run | Result | Scope |
| --- | --- | --- |
| `20260926T230014-fast` | PASS, 55 scenarios | Existing fast corpus plus 13 collector scenarios; source manifest and complete test report retained |
| `20260926T225135-postgres` | PASS, 30 PostgreSQL scenarios plus real clients | Initial three-harness replay through scoped HTTP publication, artifact evidence and the shared audit; role isolation, CLI deadlines and SIGKILL restart also pass. Predates the later Pi/decimal corrections |
| `20260926T230136-collector-http` | PASS | Current collector/fixture replay through the real HTTP server and a fresh PostgreSQL database |
| `collector-decimal-repro` | FAIL before / PASS after | Same exponential-cost input in a JVM limited to 64 MiB; before: `UNBOUNDED_DECIMAL_EXPANSION`, after: `BOUNDED_DECIMAL_REJECTION` |

The authenticated replay registers independent attempts, publishes retained native output as immutable transcript artifacts, uploads each meter/sample twice, and reads the evaluation-filtered audit and summary. It observes exactly three audit entries, 18,729 reported tokens, $0.0026 in provider estimates, one unknown native cost, three partial meters and Pi's explicit auxiliary-coverage gap. Those totals belong to the earlier simple observability probes, not an efficiency comparison. They are already documented in the [original investigation](../drafts/20260926-usage-observability.md).

The fixtures remove prompt/response text and unrelated machine metadata while retaining usage values, ordering and native identities. Synthetic cases exercise nonzero cache/reasoning normalization, cumulative snapshots and baselines, stable replay, repeated/conflicting Pi responses, missing/default-zero fields, interrupted turns, mixed sessions, malformed UTF-8/JSON, line bounds, truncation, and monetary expansion. Unknown counters remain separate from measured zeroes.

## Reproduced corrections

- `20260926T224911-fast`: three failing collector scenarios exposed acceptance of a cumulative decrease, stale completion after a new unfinished turn, and loss of a late cumulative Claude cost. Corrected in `20260926T225006-fast` (51 passing scenarios).
- `20260926T225514-fast`: Pi response replay across repeated turn wrappers counted 136 instead of 68 tokens; a default-zero `totalTokens` discarded separately reported positive counts. Both are corrected using native response IDs and explicit missing-total semantics.
- `20260926T225611-fast`: a missing cumulative sample hid a later decrease. Validation now remembers previously known values without replacing the missing observation. The Pi corrections passed in this run; the new cumulative scenario was its sole failure.
- `collector-decimal-repro/before.log`: formatting `1e100000000` exhausted the bounded JVM heap. Expanded decimal length and scale are now checked before formatting; the same command passes in `after.log`, with a permanent fast regression including the accepted 64-character boundary.

Current fast verification passes all these corrections. Full transcripts, source manifests and command arrays are retained. Independent Astra review is pending.

## Limits and next work

The collector reads completed output files and emits the current audit types. It does not yet attach to local jobs, persist an upload spool, capture interactive governing sessions or publish large-transcript manifests. Pi assistant-event collection explicitly leaves auxiliary/compaction/tool-result usage uncovered. Native completion does not establish process cleanup, result validity or semantic acceptance. Actual consumer runs, matched efficiency evaluation and human M2 acceptance remain open.

See [collection semantics and bounds](../design/usage-collectors.md). Reproduce the standard suites with `./dev/check fast` and `./dev/check postgres`; the latter includes `cq.server.ServerApiCheck`. The isolated decimal probe and exact invocation are retained under the evidence directory.
