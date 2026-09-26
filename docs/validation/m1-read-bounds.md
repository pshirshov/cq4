# M1 compact discovery and bounded pages

Implementation in progress. Search now returns generated `ItemSummary` records (identity, revision, title, status, archive flag, labels and update time), with full content available through explicit detail reads. PostgreSQL maintains a summary column alongside each changed item and does not select the narrative body for discovery.

Search, history, committed change replay and usage observation audit share a 512 KiB encoded page budget, including 1 KiB reserved for envelopes, and the existing 1–200 row limit. The reader includes only whole records; `hasMore` and the last returned key preserve continuation when bytes shorten a page. A single record larger than the budget fails explicitly. PostgreSQL uses a transaction cursor with fetch size 1 and a SQL row limit, so the application reads only the accepted prefix plus one lookahead record. Dummy behavior uses the same page selector. PostgreSQL access-plan/scale measurements remain M3 work.

## Reproductions and verification

- `20260926T194936-fast`: discovery returned 180,714 bytes for one item with a large narrative, exceeding the compact fixture's 4 KiB allowance.
- `20260926T195111-fast`: after compact discovery, history still returned all six 180 KiB revisions with `hasMore=false`. The assertion printed full records, yielding a very large failure report. ScalaTest reported 19 successes and 1 failure but sbt returned exit 0; the old runner incorrectly wrote a passing result. **That result is invalid and is not passing evidence.**
- `20260926T195242-fast`: reducing assertion output to counts/flags reproduced the history failure with exit 1. This narrows the external test-runner defect to handling of the large failure; its exact internal cause has not been diagnosed or patched.
- The check runner now requires a nonempty ScalaTest report with positive successes and zero failed/cancelled/ignored/pending tests, independently of the process exit. Replaying the retained false-success log through this guard rejects it. Auditing all prior retained passing dummy/PostgreSQL reports identified only `20260926T195111-fast` as invalid.
- `20260926T195515-fast`: all 20 dummy scenarios pass, including compact discovery and three lossless byte-bounded history pages.
- `20260926T195610-postgres`: all 20 scenarios pass on PostgreSQL, including byte-bounded history; actual transport/CLI and SIGKILL restart checks pass.
- `20260926T195725-browser`: real Chromium workflow and connection recovery checks pass with compact discovery.
- `20260926T195850-contracts`: deterministic generation, strict Scala/TypeScript compilation and generated codec/schema checks pass.

The original increment did not finish aggregate/report limits, complete query grammar, or workspace isolation. See the [implementation status](../implementation-status.md) and [interim review](m1-astra-interim.md).


## Cost aggregation bounds

- `20260926T202741-fast` reproduces unbounded cost groups (201 groups in an unpaged summary) and admission of a 600,000-character pricing version.
- Monetary projections now use separately keyed rows; meter token projections no longer grow with distinct pricing versions. Summary includes a bounded initial cost page and explicit continuation. CLI/MCP/browser expose the same generated cost page contract. Corrections update affected groups transactionally without discarding raw evidence.
- `20260926T203106-fast` passes those two corrections but reproduces decimal rounding: two permitted 50-digit monetary values sum to a rounded value. Monetary addition/subtraction now use exact decimal arithmetic. `20260926T203237-fast` failed compilation during the correction; it is not verification evidence.
- `20260926T203334-fast` and `20260926T203417-postgres` pass all 27 scenarios. The latter also passes actual MCP/CLI and SIGKILL restart checks. `20260926T203520-browser` passes the 201-group cost continuation and the existing workflow/draft/lifecycle/connection corpus. `20260926T203643-contracts` passes deterministic generation, strict Scala/TypeScript compilation and generated schema/codec checks.
- Astra source review approves the correction without blocking/major findings. The reviewer inspected passing dummy/PostgreSQL scenario reports; final client/browser/contracts results were supplied subsequently. This is not M1 exit approval.
- Scenarios cover full cost pagination, snapshot invalidation, measured zero, changed cost basis/currency, idempotent correction, raw evidence retention, Unicode group keys, attribution filtering, and exact large decimal totals including cumulative baselines.
