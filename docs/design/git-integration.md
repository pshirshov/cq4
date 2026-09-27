# Git integration — next M3 increment

Status: implementation contract independently approved by Astra; no integration capability is implemented. This records the next boundary after [result admission](result-admission.md), committed at `a9403af`. Design approval does not close R27; the real two-session workflow and acknowledgement reconciliation must pass.

## Required behavior

The governor chooses whether to integrate a reviewed candidate. The local host owns Git execution; the server owns claims, revisions and domain changes. Successful worker/reviewer results do not themselves update a branch or accept a task.

Integration uses a separate workspace and an explicit configured target branch. The host must verify the repository identity, full expected target object ID, exact candidate, accepted result handles, applicable checks/review, and current claim/revision admission. Never use the governing checkout's index. A target checked out in another worktree needs an explicit safe policy before support; the initial implementation should reject it rather than leave its HEAD, index and files inconsistent.

Two sessions with disjoint item claims can still conflict on the target. The effect must use Git's conditional ref update with the complete expected old object ID. A failed comparison cannot be converted to an unconditional update. A stale candidate must not overwrite another session's work.

If the target advances, preserve the first candidate and its evidence, construct the combined candidate in isolation, and perform applicable validation and independent review on that exact candidate. Earlier approval does not transfer to different candidate bytes. The existing worker conflict-resolution mode is the intended editing boundary; the governor continues to pass handles.

## First implementation boundary

Introduce a narrow Git integration service with injected repository/command dependencies and a shared behavioral suite for a manual dummy and real scratch Git repositories. Its immutable operation intent binds identity, canonical repository/target, expected target, candidate and evidence references. Persist and force the intent before attempting the ref update. Track the observed Git outcome separately from delivery of the associated domain acknowledgement.

The mechanical proof must cover competing expected-target updates, separate indexes, unchanged unrelated checkout files, target advancement and recovery after a successful update loses its caller acknowledgement. It does not close claim authorization, combined-candidate review or the connected supervisor workflow by itself.

Do not treat a second Git receipt ref as an atomic transaction with the target across crashes. The installed Git 2.55.0 `git-update-ref` documentation promises individual ref atomicity and explicitly permits concurrent readers to observe a subset of a multi-ref update. A stronger crash guarantee has not been established here.

Recovery first inspects the retained operation and Git state. A matching candidate at the target, or verified containment of the candidate in a later target, can establish the retained effect. Preserve operation identity and replay the original idempotent domain request when its acknowledgement is missing. Never repeat Git merely because that acknowledgement is missing. Ambiguous Git outcomes, external history rewrites and missing retained evidence must remain explicit unresolved cases; they cannot trigger an unconditional retry.

## Reservation and exact domain request

Preserve the approved fencing semantics: an irrevocable grant that silently survives successful takeover is not the selected design. A small server reservation instead blocks overlapping changes until the exact Git operation is settled. An unreachable host can therefore block those items indefinitely. Unrelated items remain available. Lease expiry alone, timeout, or an operator's desire to take over cannot prove that an old executor will never perform its CAS.

Use one `IntegrationIntent` binding operation ID, project/owner, repository/target, expected target, candidate, worker/reviewer result handles, fence/member revisions and the exact prospective `ChangeRequest`. Its server record has a resolution of `Pending`, `NotApplied(reason)`, or `Recorded(ChangeAck)`. The intent and resolution are operational coordination records outside the fourteen ledgers and the usage audit.

`IntegrationService.reserve` validates the full active claim and revisions, accepted worker/reviewer admissions, exact candidate, applicable validation and narrow intended domain changes, then inserts the reservation under the same project transaction as claim changes. Pending reservations block competing domain edits, release, acquisition/replacement, takeover and termination over their affected items. Expose the blocking operation ID. Expiry must not allow acquisition to bypass a reservation, and same-owner ordinary changes must not bypass it either. No database transaction remains open across Git execution.

After ordinary claim expiry, the reservation authorizes only its already frozen effect and domain request. It does not revive the lease or authorize fresh work or result admission. Keep the existing claim operations and add a typed pending-integration conflict plus reservation entries in claim/takeover previews. Renewal may continue. Replaying an already committed ordinary ledger acknowledgement remains valid because it creates no new mutation.

The first connected path integrates a completely reviewed task cohort. The governor confirms a compact preview. Host code loads current drafts, preserves narratives, and constructs the exact bounded task-completion request and evidence citations. The server independently checks those permitted changes against the frozen candidate/review evidence. Do not accept arbitrary host-authored mutations through this capability. Probe results cannot integrate; every selected task needs candidate-ready and accepted per-member outcomes, plus successful applicable checks on the exact candidate.

Extract transaction-local ledger mutation application so `recordApplied` can apply the stored request, retain its ordinary idempotency acknowledgement and resolve the reservation to `Recorded` in one transaction. This path uses the exact reservation authority; it cannot reconstruct a new request against current drafts. Repeating it returns the same acknowledgement. A domain-recording failure after Git success leaves the reservation pending and reports that distinction explicitly.

`NotApplied` requires evidence of definitive failure and a settled executor that cannot later update the target. Hold exclusive coordinator/journal ownership through settlement and durable terminal-observation sealing before releasing the server reservation; a delayed concurrent replay must not subsequently execute CAS. Recovery with unsettled execution, missing journal or ambiguous Git state remains pending. Recovery may record verified candidate incorporation without asserting that this operation caused it. Arbitrary external history rewrites are unsupported; the absence of the candidate is not sufficient evidence to repeat or abandon an uncertain effect.

## Connected host path

Keep four service boundaries in the existing supervisor graph: server `IntegrationService`, local `GitIntegration`, local `IntegrationJournal`, and `IntegrationCoordinator`. The journal seals the complete server-approved intent before CAS, then forces its observed Git result before domain recording. A recorded server operation or durable local applied observation goes directly to acknowledgement replay, never to another Git update.

The mutating Git executor needs the same supervisor-owned process-lifetime discipline as child jobs. Loss of a JVM file lock alone does not prove a descendant Git process has settled. Reuse guardian supervision for that effect and retain an unresolved reservation when cleanup cannot be confirmed.

For target advancement, a host-generated `CombinationPlan` handle binds repository/target, observed target commit and original worker result. Only `Worker(ResolveConflict)` may consume it. Host preparation uses that frozen target as the isolated workspace base, applies/merges the original candidate, and supplies conflict state by handle. Capture a new candidate descending from the frozen target, rerun configured checks, and obtain a fresh independent reviewer result. Existing `ChildRunner` base selection must change for this path; it currently always chooses the previous candidate. A further target advance requires a new operation and combination/review round.

The initial target is an explicitly configured branch not checked out in any worktree. Reject checked-out targets before attempting an update. This requires cooperating Git writers: a preflight worktree check does not fence an arbitrary external checkout performed concurrently. CQ never updates shared checkout files or indexes.

## Preliminary Git observation

`/srv/nvme/tmp/cq4-implementation/20260927T092732-git-cas-proof/` retains the executed Python probe, exact command/output log and observation. Installed Git 2.55.0 rejects the second stale expected-target update; an isolated combined commit preserves both candidates' files; observation after a simulated lost caller acknowledgement recognizes the target without a second update. The dirty governing checkout, index bytes and HEAD remain unchanged. This verifies Git mechanics only: no CQ integration authorization, domain acknowledgement or independent review ran in that probe.

## Required acceptance evidence

Required final scenario: two governors hold different task claims and start from one target; only one first update succeeds; the second constructs and reviews a combined candidate; both changes survive; an injected lost domain acknowledgement reconciles without a second Git effect or duplicate history.

Also cover reservation versus release/takeover in both orders, expiry while pending, lost reservation acknowledgement, an actual domain-recording failure after Git success, crash before the local applied observation, external target reset, and checked-out target rejection. Use the same service scenarios for manual dummy and real adapters; PostgreSQL/Git checks prove their respective concurrency and persistence behavior. Missing capability remains open until the connected checks run.
