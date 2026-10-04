# I26: separated PostgreSQL presets

Functional test clusters explicitly disable fsync, synchronous_commit and full_page_writes. Durable clusters explicitly enable all three. PostgreSQL contract suites run in a disposable functional cluster; transport backup/restore and restart verification run in a newly initialized durable cluster. Native transport uses the durable role. UI, usage, access, cohort and growth functional fixtures use the functional role. Both roles use 64 connections. Extras cannot override durability, connection limits or configuration includes. External databases are refused for disposable fixtures; durable external fixtures first verify all three durability settings without modifying them.

The local launcher generates `postgres/cq-local.conf` and includes it once in the existing configuration. Its memory budget follows physical memory and process cgroup limits, capped at 8 GiB, with an explicit smaller `CQ_LOCAL_DATABASE_MEMORY_MIB` option. Budgets below 1 GiB or above the detected/capped limit are refused. Shared buffers use one quarter of the budget; effective cache estimate uses three quarters. The latter is a planner estimate, not an allocation. The NVMe preset uses random_page_cost 1.1 and effective_io_concurrency 200. Durability stays on, enforced at startup even if postgresql.auto.conf requests otherwise. Runtime settings must match the declared profile; other configuration collisions are reported before CQ starts. The operator's existing playground was not modified.

Evidence root: `/srv/nvme/tmp/cq4-final-wave-20261004/`.

- `i26-presets-final/`: real PostgreSQL functional/durable lifetimes, protected overrides, memory bounds and external database durability validation pass. Settings are read again after external validation to check that it made no configuration changes.
- `i26-contract/`: three alternating exact LedgerContractPostgres invocations per role, 25 tests each, all **150 passed**. Median suite duration: durable **8.067 s**, functional **7.857 s**, about 2.6% lower. Host load and JVM warmup affect these samples; the earlier stock/default comparison remains in `crosscut-next-20261003.md`.
- The initial connection sampler had an empty psql query buffer and did not measure concurrency. Its logs remain; its JSON marks that measurement unknown. Corrected `i26-connection-peak/`: one additional 25-test functional suite passes, **399 samples at 50 ms**, maximum sampled connections **39**. This supports 64 for the observed focused workload; it does not prove an upper bound for every gate.
- `i26-local-measure/`: two private CQ projects with 20,000 seeded item rows each and corresponding history/claims. Seven EXPLAIN ANALYZE/BUFFERS samples per query, first discarded for the execution-time median. Stock and the 8-GiB SSD preset both keep durability on.

| Query | Stock median | SSD median | Observation |
| --- | ---: | ---: | --- |
| Exact item | 0.0635 ms | 0.0750 ms | Same index plan |
| Text search | 2.9305 ms | 4.1215 ms | Same bitmap plan |
| Ready range page | 0.2125 ms | 0.2685 ms | Same ordered index plan |
| Work cursor | 8.1175 ms | 6.5060 ms | Sequential claim scan becomes an index scan; shared blocks fall 2860 → 1531 |

This sample shows mixed timings, not a general speedup. After seeding, the larger buffer preset had no first-query shared reads; stock point/text/range samples read 2/169/19 blocks. Both profiles' final samples hit cached blocks. There were no live measurements, cold-storage experiment or memory-pressure test. The explicit NVMe cost assumption is not an automatic storage detector.

`i26-launcher-final/` exercises the modified launcher with the existing native release (source d660ac09d08b2ceacbcb7fd435a7e89175c93e61) and private state. It verifies 2-GiB/1-GiB budget settings, durable data across restart, an attempted durability override, one persistent include, refusal of a conflicting planner override, and owned process cleanup. An initial fixture omitted CQ-Session and timed out on HTTP 401; the fixture was corrected and the final run passed. This is launcher evidence, not installation of the combined source.

Python compilation, shell syntax and whitespace checks pass. Configured and delivery gates remain for the host/operator. Disabling durability is appropriate only for disposable data; planner costs and cache estimates remain workload assumptions. [PostgreSQL 18 nondurable operation](https://www.postgresql.org/docs/18/non-durability.html), [planner configuration](https://www.postgresql.org/docs/18/runtime-config-query.html).
