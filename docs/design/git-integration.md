# Git integration — next M3 increment

Status: design in progress; no integration capability is implemented. This records the next boundary after [result admission](result-admission.md). R27 remains open until the real two-session workflow and acknowledgement reconciliation pass.

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

## Remaining decisions before the connected workflow

- Fix the precise authorization point relative to claim loss and takeover. A network check followed by an OS effect does not form a shared transaction; accepted result admission alone is historical evidence, not a current integration grant.
- Bind the exact prospective domain change to the operation without requiring the governor to copy child narratives. A successful Git effect followed by stale domain revisions must remain observable and reconcilable without repeating Git.
- Wire target discovery, combined-candidate preparation, validation/review and bounded status/recovery into the existing distage supervisor graph and lean local capability surface.

Required final scenario: two governors hold different task claims and start from one target; only one first update succeeds; the second constructs and reviews a combined candidate; both changes survive; an injected lost domain acknowledgement reconciles without a second Git effect or duplicate history.
