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

This increment does not finish aggregate/report limits, complete query grammar, or workspace isolation. See the [implementation status](../implementation-status.md) and [interim review](m1-astra-interim.md).
