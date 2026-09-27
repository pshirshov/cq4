# M3 local Git coordinator

This implements the local journal, supervised Git adapter and reconciliation service from the [integration design](../design/git-integration.md). The server reservation boundary is already approved at `595acc3`. The new shared suite uses a manual server receiver; it does not establish connected PostgreSQL/Git acceptance, governor-facing integration, combined-candidate dispatch/review or additional real model evaluations.

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T101346-fast` | Compilation stopped because the new host adapter referenced the runtime environment filter in the server module. Moved that unchanged shared filter into the host module; no behavioral result from this gate |
| `20260927T101558-fast` | All 127 scenarios pass, including eight new coordinator scenarios against manual Git/journal adapters |
| `20260927T101648-process` | All 27 Scala process scenarios and 16 guardian checks pass; the same eight coordinator scenarios use actual Git, filesystem journal, detached workspaces, guardian and durable job supervisor |
| `20260927T101837-fast` | All 128 scenarios pass after adding the interruption/lock-retention check; the suspected late-write mechanism does not reproduce |
| `20260927T102003-process` | All 28 Scala process scenarios and 16 guardian checks pass, including the same nine coordinator scenarios against real adapters |
| `20260927T102102-contracts` | Deterministic generation, Scala/TypeScript round trips, 388 schema definitions and six MCP capabilities pass |

## What the scenarios establish

- A conditional update preserves the first candidate; a later attempt based on the old target resolves without overwriting it. A combined descendant then integrates. Direct concurrent Git jobs produce one incorporated candidate and one settled preparation refusal.
- Actual Git work leaves the governing checkout's HEAD, staged index bytes, tracked content and untracked file unchanged. Each mutating job uses a separate detached workspace.
- A failed domain recording leaves the server reservation pending and the local incorporation observation retained. A subsequent lost recording acknowledgement also recovers without another Git effect or duplicate resolution.
- Failure to persist the local incorporation observation permits later target inspection. Resetting the target after the successful Git job leaves an explicit unresolved operation; restoring demonstrable incorporation permits recording without another launch.
- An attempted marker without a confirmed job stays pending. Missing journal state cannot be reconstructed from an existing server reservation. Checked-out targets are rejected without launch. Terminal observations cannot be changed, and one session coordinator holds exclusive journal ownership.
- A lost reservation acknowledgement launches nothing until retry receives the retained reservation. Failures both before and after attempted-marker persistence launch nothing: an unpersisted marker can subsequently admit one effect, while a persisted marker remains pending without relaunch.

The coordinator forces its intent before reservation and its execution-admission marker before launch. It forces the terminal observation before server delivery. File writes use atomic replacement, file force and directory force. Reading an existing journal forces it again before authorizing subsequent steps. Those mechanisms and these process tests do not prove power-loss durability for Git refs or candidate objects.

The Git job uses one direct branch, full expected/candidate IDs, and the explicit prepare/commit protocol. Only a settled ordinary nonzero exit with fully retained start acknowledgement and preparation-failure diagnostic establishes a refused update. Timeout, absent receipt and commit-stage failure do not establish non-application. Target equality or ancestry establishes incorporation, not that this particular operation caused it. External concurrent branch checkout and arbitrary history rewrites are outside the cooperating-writer contract.

Astra found no blocking/major source defect in the first review and requested the reservation-acknowledgement and attempted-marker cases included above. A further interruption hypothesis was investigated in pinned ZIO 2.1.26: ordinary `attemptBlocking` runs synchronously on the shifted fiber, so its scope finalizer waits for the write to return. No speculative masking change was made. The shared latch scenario confirms lock retention during interruption: a competing coordinator is denied until the blocked write actually returns, and no Git process launches. A hung write can retain that lock indefinitely; the supervisor watchdog must report unresolved shutdown rather than infer settlement.

## Verification

Run sequentially from the repository:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check fast
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check process
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation nix develop -c dev/check contracts
```

All three final gates pass. Astra independently verified final source behavior, all gate outcomes and runtime/test manifest agreement, and approved the foundation with no remaining blocking or major finding. Only documentation changes follow these gates. The required connected two-governor scenario, exact combined-candidate validation/review and actual crash/acknowledgement reconciliation remain R27/M3 work. The manual receiver deliberately does not validate the real ledger/artifact rules; those already have separate PostgreSQL evidence and must be exercised together in the connected path.
