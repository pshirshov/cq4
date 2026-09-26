# Durable local jobs

`JobSupervisor` connects the workspace service to the process driver. It is a host service; the CQ server never constructs it. The current interface is an in-process start/status/cancel API, pending the local transport and distage role wiring. Its `JobCommand` input is trusted host configuration, not a model-facing executable/environment interface. Reference-based harness dispatch will assemble that input outside the governing model.

## Ownership and launch

One journal belongs to one project and governing session. A filesystem lock prevents two supervisors from owning it concurrently. Control operations require that project/session and a Governor or Human role. A job uses its attempt ID as its immutable launch identity. Reusing that identity with changed workspace, base, command, environment, input or limits is rejected; exact retries return the current durable record without launching again. The command/environment/input digest is persisted; their plaintext is not copied into the job record.

Reservation is forced to disk before creating a worktree or process. Each record replacement writes a private temporary file, forces it, atomically renames it, and forces the containing directory. Records have monotonic revisions and receipt times, immutable ownership/base/fingerprint, a desired Run/Stop target, and a separately observed process phase. Stop cannot be reversed and terminal states cannot become runnable. Existing development records use the same mutable 0.1.0 contract; no historical decoder is added.

The journal is bounded to 256 jobs per governing session and 64 KiB per record. Capacity exhaustion is explicit and requires a new governing session. Payload input is bounded to 256 KiB of valid UTF-8, and the complete host launch description is bounded to 1 MiB. The process driver's separate stdout/stderr limits apply to retained output. A session's terminal records and payloads remain available; automatic retention/deletion is not implemented here.

## Cancellation, failure and recovery

Start acknowledges a durable reservation without waiting for workspace creation or execution. Start/cancel acknowledgement has a one-second deadline. Expiry disables result admission and new work, even if the underlying filesystem operation completes later. The pending operation retains journal ownership until it finishes; a deadline does not prove a kernel filesystem operation was interrupted.

Authorized cancellation first sets an in-memory Stop and reaches the retained execution, independently of the journal mutation lock and filesystem writes. A small per-job control lock serializes that request with the driver's short launch call; it never covers filesystem I/O. Stop during preparation or a stalled Starting write suppresses launch when persistence returns. Status reads the last acknowledged durable observation from memory. Normal scope shutdown delivers cancellation before waiting for outstanding journal writes, and waits for jobs before releasing journal ownership. The process driver's heartbeat, execution and cleanup deadlines remain independent of journal writes.

A storage failure during reservation or cancellation disables further work. This includes a reservation that committed but lost its acknowledgement. A supervisor with uncertain storage does not infer that repeating the launch is safe. Active jobs are stopped and quarantined. A failed read, write or quarantine is surfaced; recovery must finish before the new supervisor accepts work.

After restart, all unfinished job records become Uncertain/Stop, and their registered workspaces are quarantined. A missing workspace is valid when the owner died before preparation. A malformed or conflicting workspace record is an error. Already settled jobs retain their exact completion. Previously uncertain jobs remain quarantined. Recovery never launches a job or adopts/signals a saved PID; no PIDs are stored in job records.

An Uncertain record is persisted before quarantine is attempted. Consumers must check both job state and workspace admission before any future result admission or integration. A process marked Settled establishes observed process cleanup, not successful model work, complete usage collection, or permission to integrate a candidate. These later contracts remain M2/M3 work.

## Verification and remaining integration

[Evidence and correction loop](../validation/m2-jobs.md) cover shared dummy/filesystem journal behavior, actual Git/process execution, concurrent retries, authorization, cancellation races, lost acknowledgements, failed writes and recovery. The real owner-death fixture starts a separate JVM supervisor with a detached, TERM-ignoring descendant, kills the supervisor with SIGKILL, checks descendant termination, then reopens the journal and checks workspace quarantine.

The next integration uses one distage role entrypoint for server, client tasks and the local supervisor. Harness adapters, reference assembly, artifact/usage publication, the local transport, claim-loss handling and consumer evaluations remain pending. The native distribution has not yet verified these job services.
