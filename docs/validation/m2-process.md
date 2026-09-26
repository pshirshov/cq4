# M2 process guardian increment

Implementation: [local guardian](../design/process-guardian.md). Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

`20260926T212832-process` passes compilation with C17, `-Wall -Wextra -Werror`, and 16 real-process scenarios. Observed cases: separate input/stdout/stderr, simultaneous pipe pressure, nonzero exit, failed executable launch, bounded output retention, silent execution deadline, lost heartbeat, owner pipe EOF, explicit cancellation, a detached SIGTERM-ignoring descendant, actual owner SIGKILL, regular-file startup validation and inherited signal/descriptor isolation. Fixture cleanup uses retained process objects or pidfds.

Two defects were reproduced before correction:

- `20260926T212039-process`: FIFO input blocked beyond the two-second test deadline despite 500 ms configured launch/heartbeat limits. Nonblocking open plus regular-file validation now rejects it before launch.
- `20260926T212204-process`: inherited `SIGCHLD=SIG_IGN` produced `EXIT -1 0 Running` after a command exited 7. Resetting the policy preserves waitable child identity/status; settlement additionally requires the root to have been reaped. The final check observes exit 7 and reason `Exited`.

These helper checks do not establish the complete local supervisor. Subsequent [durable job checks](m2-jobs.md) cover in-process start/status/cancel and workspace quarantine. Local control transport, claim-loss response, actual harness adapters and the native distribution remain pending. No M2 milestone or release approval is claimed.

## Independent Astra correction loop

Astra reviewed `7cabf5c` and requested changes for two source-derived inherited-state defects. `20260926T212710-process` reproduces both: ignored SIGTERM forced root termination by signal 9 instead of 15, and an inherited sentinel descriptor remained readable. `20260926T212744-process` adds the concrete owner-lifetime consequence: an inherited control writer changed expected `OwnerExited` into `HeartbeatLost`.

The helper now closes inherited descriptors with `close_range` before opening internal files/pipes, and resets signal dispositions before executing the command. All 16 scenarios pass in `20260926T212832-process`. Astra approved the guardian foundation at `75d65cf`, with no remaining blocking or major findings and matching source hashes. That approval does not cover the subsequent Scala driver or the complete M2 milestone.

## Scala driver increment

`20260926T213844-process` passes all 16 helper scenarios and eight Scala driver scenarios. The real driver starts and cancels without waiting for the command, captures both output files, observes command deadlines and marks abrupt guardian death as uncertain. Controlled helper fixtures reject forged/contradictory completion, malformed lifecycle numbers and missing output; they also check each driver deadline independently of the longer command deadline.

- `20260926T213624-process` reproduced three driver timeouts: absent start acknowledgement, premature lifecycle EOF and a helper hanging after its terminal record. Separate startup/completion deadlines and explicit EOF validation correct these cases.
- `20260926T213759-process` reproduced cancellation changing an already settled observation and a STOP-only helper outliving its termination bound. Terminal cancellation is now a no-op; an observed STOP starts its own settlement deadline.

Both reproductions are retained. Astra approved the driver foundation at `aabac88`, with no blocking or major findings and matching source hashes. The subsequent [job increment](m2-jobs.md) connects uncertainty to durable workspace quarantine and an in-process control service. Public control transport and M2 acceptance remain pending.
