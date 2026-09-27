# M2 atomic final publication

Scope: freeze the complete final artifact/usage/outcome set before any of it becomes eligible for HTTP delivery. Interrupted-output reconciliation remains a separate increment.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence | Observation |
| --- | --- |
| `20260927T025306-fast` | PASS 70 Scala scenarios; includes interrupted staging, immutable final batches and lost-acknowledgement replay |
| `20260927T025412-postgres` | PASS 31 service scenarios and actual HTTP/WS/MCP, artifacts, roles, CLI, deadlines, supervisor, dispatch, shutdown and server SIGKILL/restart checks; before the directory-force correction below |
| `20260927T025823-fast` | Fixture setup failure: the forked JVM could not locate the C source relative to its working directory; no product conclusion |
| `20260927T025942-fast` | Expected failure after fixture setup correction: `Unforced final directory was reported durable` |
| `20260927T030027-fast` | PASS final 71 Scala scenarios, Node bridge, five consumer/correction predicates and four suite-runner fixtures |

Final entries are grouped into immutable batches under `staging/`. The queue forces the files and directory, atomically renames it to `final/`, then forces the parent. Only initial batches and committed final batches can be flushed. Repeated commit must match the frozen bytes exactly; changed content or an extended initial set is rejected. Recovery may discard only bounded, recognized, uncommitted staging files.

Astra identified a durability ambiguity when rename succeeds but the parent-directory force fails. The Linux filesystem fixture injects `EIO` into that exact force operation. Before correction, a subsequent `finalized` call falsely reported durability. The queue now retries the parent force before exposing any existing final set. While the failure persists, finalization, replay and recommit fail, with zero API publications. After removing the failure, the same set commits and replays once; subsequent flush acknowledges nothing new.

Astra independently approved this foundation after the final fast gate, with no remaining substantive findings. This approval does not cover interrupted-output reconciliation or full M2 acceptance. See [publication and recovery boundaries](../design/supervisor-role.md#interrupted-publication).
