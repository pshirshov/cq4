# Local process guardian

M2 host primitive, currently Linux 5.9+ only. The current locally verified release platform is Linux amd64 (observed kernel 7.1.9). A macOS guardian is not implemented; the Scala supervisor must reject an unsupported host explicitly. This helper is not linked into the CQ server and does not contain project, workflow or harness policy.

One small native helper owns one root command and its descendants. The Scala driver supplies an explicit working directory/environment, prepared input file, private output paths, the startup/heartbeat/termination deadlines, an execution deadline or `0` for none, and the output ceiling. The helper receives `H` heartbeats and `C` cancellation on its standard input. Closing the pipe cancels the job, including when its owning process receives `SIGKILL`. Missing heartbeats handle a frozen owner. Standard output contains a bounded lifecycle protocol; command stdout and stderr are drained separately into private files.

At entry, the helper closes every inherited descriptor above stderr before creating its own descriptors. This prevents accidental file/socket leakage and inherited control writers concealing owner exit. It resets signal dispositions for itself and again for the command before unblocking command signals. Descriptor closure uses `close_range`, available since Linux 5.9; an unsupported syscall causes explicit setup failure. [Descriptor closure semantics](https://man7.org/linux/man-pages/man2/close_range.2.html).

The helper sets the Linux child-subreaper flag before forking, so orphaned descendants are adopted by it. This includes descendants which create a new session. [Linux subreaper semantics](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html).

Cancellation first sends `SIGTERM` to the owned root/group. The root PID is used only while the direct child has not been reaped; no persisted numeric PID is adopted or signalled. After the grace interval, the helper repeatedly kills direct children, including newly adopted descendants, then reaps exit statuses. Its single thread does not reap between reading direct-child identities and signalling them. It explicitly resets inherited `SIGCHLD` policy to retain waitable children. [Wait and zombie identity](https://man7.org/linux/man-pages/man2/waitid.2.html).

The `/proc` child listing is advisory: the kernel documentation warns that exiting processes can cause omissions. Repeated scans provide kill candidates; absence from that list is never settlement evidence. Settlement requires `waitid` to report no children, collection of the root exit status, and EOF on both output streams. Expiry of the kill deadline returns an unconfirmed result. [Child-list limitations](https://man7.org/linux/man-pages/man5/proc_tid_children.5.html).

On normal root exit, descendants are also terminated and reaped. Root exit code/signal, cleanup settlement, output-limit failure and host I/O failure are distinct facts. Input must be a regular non-symlink file; opening a FIFO cannot block before the watchdog begins. Output files are created exclusively and receive the whole stream.

## Output: retention bound and disk-safety ceiling

A healthy process is never stopped for the size of its output (D95: a Claude worker was killed after forty minutes because its own stream-json stdout passed the configured 2 MiB). Two separate quantities apply to each stream:

- `retainedOutputBytes` (`HostLimits`, `ValidationCheck`; at most 32 MiB for harness streams, 1 MiB for checks) bounds what the host *publishes* of the stream, not what the process may write. The guardian writes the complete stream to `payload/<attempt>/stdout` and `stderr`. Usage collection and final-result extraction read that file as a stream, line by line, whatever its length (`NativeTranscript.stream`, `HarnessUsage.collect`, `HarnessOutput.result`); an event longer than 1 MiB is skipped and reported as a usage gap. The published transcript artifact (`NativeTranscript.retained`) is the stream itself when it fits the bound; otherwise it is the head and the tail, cut at line boundaries, around one marker line `{"type":"cq.truncated","totalBytes":N,"omittedBytes":M}`, and never exceeds the bound (a bound smaller than the marker retains the marker alone). The true byte count is also the job's `JobExit.stdoutBytes`/`stderrBytes`; the transcript manifest describes the retained copy.
- `ExecutionLimits.OutputCeilingBytes` (1 GiB per stream, one fixed constant, not a setting) is the only output condition that stops a job. It exists so that a runaway writer cannot fill the state volume. The guardian receives it as its `output-ceiling-bytes` argument, keeps the prefix up to the ceiling, stops the job with `StopReason.OutputLimit`, and the host reports the distinct blocker `an output stream exceeded the 1073741824 byte disk-safety ceiling and the process was stopped; OutputLimit, …`.

The `--capture` mode used for merge diagnostics keeps its own explicit byte bound and fails on overflow.

## Deadlines that stay and deadlines that went

A fixed execution deadline killed legitimate work and could not be tuned per task (D78: workers killed mid-run, their work lost). A harness job, the governing harness or a dispatched child, therefore has **no** wall-clock deadline: it ends when it exits, when the governor or operator cancels it, or when its owner is gone. What bounds the remaining risk is liveness and ownership, not elapsed time.

| Budget | Where | Decision |
|---|---|---|
| Exec acknowledgement `startupMillis` | `guardian.c` `StartupDeadline`, `GuardianDriver.monitor` | stays |
| Owner heartbeat `heartbeatMillis` (frozen or dead owner) | `guardian.c` `HeartbeatLost`, control-pipe EOF `OwnerExited` | stays |
| Termination `graceMillis`, `killMillis`, the driver's 2 s drain | `guardian.c`, `GuardianDriver` | stays |
| Output disk-safety ceiling, 1 GiB per stream | `ExecutionLimits.OutputCeilingBytes` | stays |
| Shutdown drain grace + kill + 10 s, then exit 75 | `SupervisorWatchdog`, armed by `beginShutdown` | stays |
| Host operation bounds: 1 s journal/ticket acknowledgements, 10 s HTTP and Git inspection, 30 s per attached MCP operation, 60 s integration preparation (server calls) | `JobSupervisor`, `HttpServerApi`, `BoundedHostCommand`, `AttachedProgram`, `IntegrationPreparation` | stays |
| Claim lease, 3 min renewed every 20 s | `ChildRunner`, `IntegrationController` | stays |
| Configured check `ValidationCheck.executionMillis` (at most 24 h) | `HostValidation`, `ReviewerChecks` → guardian `ExecutionDeadline` | stays, per check |
| Git job, 30 min (above the sum of the checkout executor's own 30 s command deadlines) | `SupervisedGitIntegration.Execution` | stays, fixed |
| Harness execution deadline `HostLimits.executionMillis` | `guardian.c` `ExecutionDeadline` for harness jobs | **removed**: the field is gone, `run-ms 0` means none |
| Driver wall-clock maximum startup + execution + grace + kill + drain → `Uncertain` | `GuardianDriver.monitor` | **removed** for a job without an execution deadline |
| Session deadline before shutdown (managed: startup + execution; attached: 8 h) → exit 75 | `SupervisorWatchdog` | **removed** |
| Attached session lifetime, 8 h | `AttachedProgram.run` | **removed** |
| `check.executionMillis ≤ limits.executionMillis` and startup + execution + cleanup + 10 min ≤ 24 h | `SupervisorConfig.load` | **removed** |
| One expiry fixed at session start, copied into the host, child and local credentials | `SupervisorAuthority`, `ChildRunner`, `LocalAccess` | **removed**, see below |

Credentials no longer bound a session. The server grants a scoped credential for at most 24 hours. The host's collector and governor credentials are granted for that lifetime less a ten-minute clock margin and granted again from the operator credential once under an hour remains (`RenewingServerApi`). A harness process keeps the credential it was launched with: the governing harness of `cq run` and each child receive a domain credential at their own start for the same lifetime. A single harness process that runs longer than that loses its domain tools (a child's ledger reads; a batch governor's reads, claims and changes) while its local tools, its work and the host's publication of its result continue. An attached governor is not affected: the attached host serves its domain calls with the renewed credential. Local capabilities do not expire; a child's is revoked when its attempt ends. There is no automatic stop for an idle child and no cost or token budget stop.

`DispatchStatus.quietMillis` makes a stalled child visible instead: for a child whose active job is running, it is the time since that job last wrote to stdout or stderr, taken from the modification times of `payload/<attempt>/stdout` and `stderr` when a status is requested (nothing polls). It is absent when no job is running. A harness writes nothing during a long tool call (a worker running a ten-minute test is silent), so quiet time is evidence for a human, not a trigger: the governor's instructions tell it to report a long-quiet child to the operator and not to cancel it on its own.

The root also receives a parent-death signal if the helper dies. That signal does not guarantee termination of the complete hierarchy. Missing/malformed terminal protocol, helper failure, unsettled descendants or a driver timeout therefore produce `Uncertain` from the Scala driver. The [durable local job service](local-jobs.md) persists uncertainty and workspace quarantine before admitting further work. A timeout is not proof of termination. [Parent-death signal scope](https://man7.org/linux/man-pages/man2/PR_SET_PDEATHSIG.2const.html).

## Scala driver

`GuardianDriver.start` returns a managed execution immediately, with nonblocking status and cancellation. Required execution limits remain distinct. Launch runs separately from monitoring; heartbeats run separately from both so a blocked write cannot freeze the monitor. The driver retains the actual Java process object for helper cleanup. Numeric PIDs in status are diagnostic only.

Startup acknowledgement, observed stopping, cancellation and a helper that reports completion but hangs each have independent deadlines. Only a job with an execution deadline also has a wall-clock maximum. Lifecycle and diagnostic streams are capped at 4096 bytes; a lifecycle line is capped at 512 bytes. The typed parser rejects contradictory ordering, malformed fields, missing completion and a helper exit code that contradicts its record. A complete terminal record is insufficient until the helper exits and both drains finish.

Cancellation is idempotent after settlement and cannot relabel completed work. On uncertainty the driver stops heartbeat delivery, requests pipe closure through its writer, and retains a bounded cleanup attempt before force-killing the helper if necessary. The state remains uncertain even if that later cleanup succeeds: it cannot prove termination of descendants after helper failure. Pending workspaces must remain unavailable for integration or reuse until the durable supervisor resolves them.

## Internal protocol

The helper uses a private bounded line protocol at its process boundary, not a model-facing API. Lines are `START pid`, `STOP reason`, and `EXIT code signal reason stdoutBytes stderrBytes settled hostFailure`. The driver must validate ordering and fields. `settled` and `hostFailure` are `0` or `1`; a signalled root has code `-1`. Helper exit 0 means cleanup settled without a host I/O failure; it does not mean the command succeeded. Exit 2 is pre-launch setup rejection and exit 3 is unconfirmed cleanup or a host failure. No protocol version is introduced.

Build and controlled checks: `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check process`. This covers the helper, Scala driver and durable local job service. The local transport and complete R21 harness lifecycle corpus remain pending.
