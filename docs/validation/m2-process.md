# M2 process guardian increment

Implementation: [local guardian](../design/process-guardian.md). Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

`20260926T212321-process` passes compilation with C17, `-Wall -Wextra -Werror`, and 13 real-process scenarios. Observed cases: separate input/stdout/stderr, simultaneous pipe pressure, nonzero exit, failed executable launch, bounded output retention, silent execution deadline, lost heartbeat, owner pipe EOF, explicit cancellation, a detached SIGTERM-ignoring descendant, actual owner SIGKILL, regular-file startup validation and inherited signal-policy isolation. Fixture cleanup uses retained process objects or pidfds.

Two defects were reproduced before correction:

- `20260926T212039-process`: FIFO input blocked beyond the two-second test deadline despite 500 ms configured launch/heartbeat limits. Nonblocking open plus regular-file validation now rejects it before launch.
- `20260926T212204-process`: inherited `SIGCHLD=SIG_IGN` produced `EXIT -1 0 Running` after a command exited 7. Resetting the policy preserves waitable child identity/status; settlement additionally requires the root to have been reaped. The final check observes exit 7 and reason `Exited`.

These checks do not establish the complete local supervisor. Scala driver/protocol validation, job start/status/cancel, durable state/quarantine after helper failure, claim-loss response, actual harness adapters and the native distribution remain pending. No M2 milestone or release approval is claimed.
