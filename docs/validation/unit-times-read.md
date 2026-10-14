# Unit times read: measurements

Task 94 (Goal 14, Decision 16). The read is `UsageService.units`; its bound is 20,000 units. The case "bound and measurement" in `server/src/test/scala/cq/server/UsageContractTest.scala` seeds exactly 20,000 units in one project (nested parents, parallel children, closed-form own times, totals and split) and prints one line per adapter. No time threshold is asserted. Design: [usage-audit.md](../design/usage-audit.md), "Unit times".

Research 22's database timings are model-declared and were taken without the window predicate; the figures below are single runs on one machine and are model-declared too.

## In memory (Dummy adapter)

Enumeration and sweep of 20,000 units in memory, no JDBC.

Command: `nix develop -c sbt --batch --no-colors "server/testOnly cq.server.UsageContractDummy"`

```
unit-times-measure adapter=Dummy units=20000 readMillis=293
```

This is not a PostgreSQL figure.

## PostgreSQL

JDBC decode and sweep of 20,000 units. Scratch PostgreSQL 18.6 cluster on 127.0.0.1 (`initdb --auth=trust`, default settings), warm cache, `CQ_DATABASE_URL`, `CQ_DATABASE_USER` and `CQ_DATABASE_PASSWORD` set to it.

Command: `nix develop -c sbt --batch --no-colors "server/testOnly cq.server.UsageContractPostgres"` (37 tests, 0 failed)

```
unit-times-measure adapter=Postgres units=20000 readMillis=495
```

`EXPLAIN (ANALYZE, BUFFERS)` of the project query with an explicit window (from 50030 ms, until 50410 ms; 10 rows selected from 20,000), run with `psql` against the project the case seeded:

```
EXPLAIN (ANALYZE, BUFFERS) SELECT u.kind, u.body, u.outcome FROM (
  SELECT 0 AS kind, t.body::text AS body, t.effective_outcome::text AS outcome
    FROM cq_usage_attempts t JOIN cq_usage_assignments a USING(project_id, assignment_id)
    WHERE t.project_id = :project AND (t.body->>'startedAt')::bigint < 50410
      AND (t.effective_outcome IS NULL OR (t.effective_outcome->'value'->>'finishedAt')::bigint > 50030)
  UNION ALL
  SELECT 1, t.body::text, NULL FROM cq_usage_spans t JOIN cq_usage_assignments a USING(project_id, assignment_id)
    WHERE t.project_id = :project AND t.started_at < 50410 AND (t.body->>'state' = 'Running' OR t.finished_at > 50030)
) u LIMIT 20001
```

```
                                                                                              QUERY PLAN                                                                                               
-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=8.50..139.89 rows=77 width=68) (actual time=1.376..9.789 rows=10.00 loops=1)
   Buffers: shared hit=3560
   ->  Append  (cost=8.50..139.89 rows=77 width=68) (actual time=1.376..9.788 rows=10.00 loops=1)
         Buffers: shared hit=3560
         ->  Nested Loop  (cost=8.50..79.73 rows=56 width=68) (actual time=1.375..7.454 rows=6.00 loops=1)
               Buffers: shared hit=2627
               ->  Bitmap Heap Scan on cq_usage_assignments a  (cost=4.19..18.02 rows=5 width=32) (actual time=0.017..0.018 rows=1.00 loops=1)
                     Recheck Cond: (project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid)
                     Heap Blocks: exact=1
                     Buffers: shared hit=2
                     ->  Bitmap Index Scan on cq_usage_evaluations  (cost=0.00..4.19 rows=5 width=0) (actual time=0.007..0.007 rows=1.00 loops=1)
                           Index Cond: (project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid)
                           Index Searches: 1
                           Buffers: shared hit=1
               ->  Bitmap Heap Scan on cq_usage_attempts t  (cost=4.31..12.22 rows=1 width=96) (actual time=1.350..7.415 rows=6.00 loops=1)
                     Recheck Cond: ((project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid) AND (assignment_id = a.assignment_id))
                     Filter: ((((body ->> 'startedAt'::text))::bigint < 50410) AND ((effective_outcome IS NULL) OR ((((effective_outcome -> 'value'::text) ->> 'finishedAt'::text))::bigint > 50030)))
                     Rows Removed by Filter: 9996
                     Heap Blocks: exact=2508
                     Buffers: shared hit=2625
                     ->  Bitmap Index Scan on cq_usage_assignment_attempts  (cost=0.00..4.31 rows=2 width=0) (actual time=1.196..1.196 rows=12476.00 loops=1)
                           Index Cond: ((project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid) AND (assignment_id = a.assignment_id))
                           Index Searches: 1
                           Buffers: shared hit=117
         ->  Nested Loop  (cost=4.48..59.78 rows=21 width=68) (actual time=0.212..2.332 rows=4.00 loops=1)
               Buffers: shared hit=933
               ->  Bitmap Heap Scan on cq_usage_assignments a_1  (cost=4.19..18.02 rows=5 width=32) (actual time=0.006..0.006 rows=1.00 loops=1)
                     Recheck Cond: (project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid)
                     Heap Blocks: exact=1
                     Buffers: shared hit=2
                     ->  Bitmap Index Scan on cq_usage_evaluations  (cost=0.00..4.19 rows=5 width=0) (actual time=0.002..0.002 rows=1.00 loops=1)
                           Index Cond: (project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid)
                           Index Searches: 1
                           Buffers: shared hit=1
               ->  Index Scan using cq_usage_assignment_spans on cq_usage_spans t_1  (cost=0.29..8.32 rows=1 width=64) (actual time=0.203..2.318 rows=4.00 loops=1)
                     Index Cond: ((project_id = 'cd359591-082b-4f9f-93f7-2ec1a5cdd97b'::uuid) AND (assignment_id = a_1.assignment_id))
                     Filter: ((started_at < 50410) AND (((body ->> 'state'::text) = 'Running'::text) OR (finished_at > 50030)))
                     Rows Removed by Filter: 9995
                     Index Searches: 1
                     Buffers: shared hit=931
 Planning:
   Buffers: shared hit=384
 Planning Time: 0.857 ms
 Execution Time: 9.942 ms
(44 rows)```

The window predicate on the attempt finish is a jsonb expression without an index, so the attempt branch filters all attempts of the assignment (9,996 rows removed by filter in this plan). Unwindowed, the same query returns all 20,000 rows; its time is the `readMillis` line above, which includes the decode and the sweep.
