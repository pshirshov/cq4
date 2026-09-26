# M2 durable job foundation

Implementation: [durable local jobs](../design/local-jobs.md). Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Check | Evidence | Observation |
| --- | --- | --- |
| `./dev/check fast` | `20260926T220415-fast` | 42 scenarios pass, including shared dummy/filesystem journal identity, revisions, limits and terminal invariants, plus real filesystem lock/reopen/corruption checks |
| `./dev/check process` | `20260926T221024-process` | 16 C guardian scenarios and 16 Scala scenarios pass: eight driver and eight durable supervisor scenarios |
| `./dev/check process` | `20260926T222020-process` | 16 C guardian scenarios and 19 Scala scenarios pass, including stalled cancellation/launch writes and an unrelated reservation holding the mutation lock |
| `./dev/check contracts` | `20260926T221209-contracts` | Deterministic single-version generation, cross-language codecs and all generated schema branches pass with the new job contracts |

Supervisor checks use real scratch Git worktrees and processes. They cover concurrent identical launch requests executing once, immutable launch conflicts, prompt return from start/cancel, forbidden role/session controls, cancellation during preparation preventing launch, actual cleanup before orderly shutdown releases ownership, unconfirmed helper termination quarantining its workspace, and unfinished-intent recovery without execution.

The owner-death scenario starts a separate JVM supervisor with a Python command and detached TERM-ignoring descendant. The test observes both children in the live owner's hierarchy, retains their process handles, kills the owning JVM with SIGKILL, observes exit 137 and both descendants no longer alive, then reopens the journal and verifies Uncertain state plus workspace quarantine. It never adopts saved PIDs for production recovery.

## Reproductions and corrections

`20260926T220541-process` reproduced two control failures before correction:

- A committed reservation whose acknowledgement was lost left the supervisor reporting an ordinary Preparing record. The controller now records a failed storage state atomically with the failed operation, rejects further work, and recovers the durable intent as uncertain after restart.
- A cancellation write failure prevented cancellation from reaching the process; waiting three seconds produced `TimeoutException`. The retained execution is now cancelled even when that write fails. Storage uncertainty disables further work and prevents ordinary result admission.

Both checks pass in `20260926T220648-process` and the final process run above. The initial separate-JVM fixture failed because sbt's forked test classloader was absent from `java.class.path`; `20260926T220921-process` retained `ClassNotFoundException` evidence. The build now supplies the resolved test classpath explicitly. That fixture failure was not a product process-termination failure.

## Independent Astra correction loop

Astra requested changes to `1b1910d` for cancellation delivery waiting behind journal I/O. The prediction was reproduced before correction in `20260926T221646-process`: with a latch holding persistence, the retained command failed to settle within three seconds and raised `TimeoutException`. The original immediate-error fixture had not exercised this case.

Cancellation now uses an independent per-job control state, while journal changes remain serialized. Start/cancel acknowledgements have a one-second deadline; expiry disables work/admission and cannot permit a late reservation to launch. Status reads the last acknowledged observation without acquiring a filesystem lock. `20260926T221854-process` passes the original stalled-write reproduction. `20260926T222020-process` additionally passes cancellation during a stalled Starting record and cancellation while an unrelated reservation holds the mutation lock, including late reservation completion without launch.

No actual model invocation or CQ consumer evaluation is represented by these fixtures. Public local control transport, unified role composition, artifacts/usage collection, full claim response, native packaging and M2 human acceptance remain pending. Astra re-review of this correction is pending.
