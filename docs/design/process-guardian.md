# Local process guardian

M2 host primitive, currently Linux 5.9+ only. The current locally verified release platform is Linux amd64 (observed kernel 7.1.9). A macOS guardian is not implemented; the Scala supervisor must reject an unsupported host explicitly. This helper is not linked into the CQ server and does not contain project, workflow or harness policy.

One small native helper owns one root command and its descendants. The Scala driver supplies an explicit working directory/environment, prepared input file, private output paths and required startup/execution/heartbeat/termination/output bounds. The helper receives `H` heartbeats and `C` cancellation on its standard input. Closing the pipe cancels the job, including when its owning process receives `SIGKILL`. Missing heartbeats handle a frozen owner. Standard output contains a bounded lifecycle protocol; command stdout and stderr are drained separately into bounded private files.

At entry, the helper closes every inherited descriptor above stderr before creating its own descriptors. This prevents accidental file/socket leakage and inherited control writers concealing owner exit. It resets signal dispositions for itself and again for the command before unblocking command signals. Descriptor closure uses `close_range`, available since Linux 5.9; an unsupported syscall causes explicit setup failure. [Descriptor closure semantics](https://man7.org/linux/man-pages/man2/close_range.2.html).

The helper sets the Linux child-subreaper flag before forking, so orphaned descendants are adopted by it. This includes descendants which create a new session. [Linux subreaper semantics](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html).

Cancellation first sends `SIGTERM` to the owned root/group. The root PID is used only while the direct child has not been reaped; no persisted numeric PID is adopted or signalled. After the grace interval, the helper repeatedly kills direct children, including newly adopted descendants, then reaps exit statuses. Its single thread does not reap between reading direct-child identities and signalling them. It explicitly resets inherited `SIGCHLD` policy to retain waitable children. [Wait and zombie identity](https://man7.org/linux/man-pages/man2/waitid.2.html).

The `/proc` child listing is advisory: the kernel documentation warns that exiting processes can cause omissions. Repeated scans provide kill candidates; absence from that list is never settlement evidence. Settlement requires `waitid` to report no children, collection of the root exit status, and EOF on both output streams. Expiry of the kill deadline returns an unconfirmed result. [Child-list limitations](https://man7.org/linux/man-pages/man5/proc_tid_children.5.html).

On normal root exit, descendants are also terminated and reaped. Root exit code/signal, cleanup settlement, output-limit failure and host I/O failure are distinct facts. Input must be a regular non-symlink file; opening a FIFO cannot block before the watchdog begins. Output files are created exclusively and retain only their configured prefix; overflow terminates execution and remains explicit.

The root also receives a parent-death signal if the helper dies. That signal does not guarantee termination of the complete hierarchy. Missing/malformed terminal protocol, helper failure, unsettled descendants or a driver timeout therefore produce `Uncertain` from the Scala driver. The supervisor must persist workspace quarantine; that durable integration remains pending. A timeout is not proof of termination. [Parent-death signal scope](https://man7.org/linux/man-pages/man2/PR_SET_PDEATHSIG.2const.html).

## Scala driver

`GuardianDriver.start` returns a managed execution immediately, with nonblocking status and cancellation. Required execution limits remain distinct. Launch runs separately from monitoring; heartbeats run separately from both so a blocked write cannot freeze the monitor. The driver retains the actual Java process object for helper cleanup. Numeric PIDs in status are diagnostic only.

Startup acknowledgement, observed stopping, cancellation and a helper that reports completion but hangs each have independent deadlines. Lifecycle and diagnostic streams are capped at 4096 bytes; a lifecycle line is capped at 512 bytes. The typed parser rejects contradictory ordering, malformed fields, missing completion and a helper exit code that contradicts its record. A complete terminal record is insufficient until the helper exits and both drains finish.

Cancellation is idempotent after settlement and cannot relabel completed work. On uncertainty the driver stops heartbeat delivery, requests pipe closure through its writer, and retains a bounded cleanup attempt before force-killing the helper if necessary. The state remains uncertain even if that later cleanup succeeds: it cannot prove termination of descendants after helper failure. Pending workspaces must remain unavailable for integration or reuse until the durable supervisor resolves them.

## Internal protocol

The helper uses a private bounded line protocol at its process boundary, not a model-facing API. Lines are `START pid`, `STOP reason`, and `EXIT code signal reason stdoutBytes stderrBytes settled hostFailure`. The driver must validate ordering and fields. `settled` and `hostFailure` are `0` or `1`; a signalled root has code `-1`. Helper exit 0 means cleanup settled without a host I/O failure; it does not mean the command succeeded. Exit 2 is pre-launch setup rejection and exit 3 is unconfirmed cleanup or a host failure. No protocol version is introduced.

Build and controlled checks: `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check process`. This covers the helper and Scala driver; job persistence/control, workspace quarantine integration and the complete R21 harness lifecycle corpus remain pending.
