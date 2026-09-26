# Local workspace foundation

`WorkspaceService[F]` authorizes project/session ownership and governing or human roles. `WorkspaceRepository[F]` is the host filesystem/Git boundary. The `host` module implements `GitWorkspaceRepository`; the server has no route or role binding that launches it. M2 will bind it into the local supervisor. Workspace contracts remain in the single mutable `cq.api` 0.1.0 model.

## Preparation and admission

Each attempt owns one directory below an explicitly supplied absolute workspace root outside the actual enclosing Git checkout. Both checkout top-level and the workspace root’s existing ancestors are resolved before containment checks; source subdirectories and root symlink aliases cannot bypass placement. A specification freezes project, owning session, attempt, source repository and full commit object ID. An exclusive file lock protects preparation/quarantine for that attempt. A concurrent operation receives an explicit conflict; callers do not automatically repeat an uncertain creation.

Preparation resolves the committed base, persists ownership, creates a detached locked Git worktree, verifies its HEAD and common Git directory, then atomically persists the observation. Worktrees share committed objects, with separate checkout/index state. Governing uncommitted/staged changes are not copied. Checkout hooks are disabled and submodule recursion is disabled. Full submodule support is not implemented.

Admission (`Open` or `Quarantined`) is separate from the observed creation record. The latter contains verified base, Git common directory and observation time. It is evidence of preparation, not a live observation of the workspace after agents run. Quarantine retains that observation and the filesystem. A failed or uncertain creation records quarantine and leaves any directory/registration available for inspection. A pending ownership record is never silently treated as a completed creation. Missing/inconsistent ownership records fail explicitly. Repeated preparation returns the same verified record only for identical ownership/base, open admission and reverified Git top-level/common-directory identity. Replacement or missing worktree identity quarantines the record without discarding its previous observation.

Records use generated codecs, bounded reads, fsync and atomic rename. Restart can read completed or quarantined records; it does not adopt harness processes. Cleanup/deletion, candidate integration, process ownership and proof of termination are M2/M3 work. This foundation deliberately has no destructive workspace removal operation.

## Bounded Git commands

`BoundedHostCommand` drains combined stdout/stderr on a virtual thread, caps retained bytes, and imposes process/output-drain/termination deadlines. It retains the launched process handle and terminates observed descendants on process timeout. Failure propagates and workspace preparation quarantines its result. CLI Git identity lookup uses this helper too. Git callers use an explicit environment with ambient `GIT_*` redirections removed, global/system Git configuration disabled, and interactive prompting disabled. This helper is not the M2 harness supervisor: whole-hierarchy lifecycle, descendant escape/uncertainty handling, collector draining and parent-exit cancellation still require implementation.

## Verification

Shared public-service scenarios run against a manual dummy and actual scratch Git repository. Real-only checks verify separate content/index state, detached HEAD, durable record reads after repository-object recreation, quarantine after Git creation succeeds but acknowledgement is lost, and silent/oversized command output bounds. No harness is launched by these checks. See [evidence](../validation/m1-workspaces.md).
