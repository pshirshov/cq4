# Local supervisor role

`cq.server.Main` registers `ServerRole`, `ClientRole` and `SupervisorRole` through the same distage `RoleAppMain`. `cq run HARNESS --settings FILE --input FILE` selects the supervisor task; `cq :supervisor -- HARNESS --settings FILE --input FILE` is equivalent. Its dependency graph contains injected harness adapters, the local job lifecycle and HTTP clients. It does not acquire a PostgreSQL connection or the CQ domain server.

This increment runs one **batch governing session**. Local child dispatch/workspace tools, reference assembly and claim-bound result admission are subsequent M2 work. It is not yet the complete `cq run` workflow. The five scoped domain tools are available to the governor; no child dispatch capability is advertised before its service exists. Interactive sessions are not implemented.

## Configuration and run

Initialize the consumer with the existing `cq init --endpoint URL`. It must be a Git checkout with a committed base. The supervisor reads the shared project identity, snapshots the current commit and creates a fresh logical session. State and detached worktrees live under an explicit private directory outside the source checkout. The checkout/index are not used for model edits.

`SupervisorSettings` is a generated Baboon contract, still in the single mutable 0.1.0 model. Example (replace the absolute paths and select the configured model explicitly):

```json
{
  "stateRoot": "/absolute/private/cq-sessions",
  "guardian": "/absolute/path/cq-guardian",
  "harnesses": [{
    "harness": "Codex",
    "executable": "/absolute/path/codex",
    "model": "gpt-6-sol",
    "provider": "openai",
    "version": "0.156.1",
    "providerExtensions": [],
    "providerEnvironment": []
  }],
  "limits": {
    "startupMillis": "10000",
    "executionMillis": "600000",
    "heartbeatMillis": "2000",
    "graceMillis": "1000",
    "killMillis": "3000",
    "outputBytes": 1048576
  }
}
```

Build the guardian from the CQ checkout:

```sh
mkdir -p .work
nix develop -c gcc -std=c17 -O2 -Wall -Wextra -Werror \
  -o .work/cq-guardian host/native/guardian.c
```

Use the JVM launcher described in the [README](../../README.md#current-cli) from the consumer directory:

```text
cq run codex --settings /absolute/settings.json --input /absolute/request.txt
```

The input is a nonempty UTF-8 file of at most 192 KiB. Settings/project records are bounded to 64 KiB. Native streams are independently bounded, up to 32 MiB each. The combined startup, execution and cleanup limits plus a ten-minute delivery margin must fit the server's 24-hour scoped-credential lifetime. Installed harness versions are checked before launch; missing or unverified routes fail explicitly. `CQ_TOKEN` must authorize host credential grants. It remains in the host and is excluded from the harness environment.

The role publishes its input and installed instructions as immutable artifacts, registers an unattributed governing assignment/attempt, then starts the native job through the existing guardian. Process success requires confirmed cleanup, normal `Exited` termination, exit code zero, no signal and no host failure. A zero exit after a deadline or cancellation cannot admit a result. Native completion and the bounded `GoverningReport` schema are checked separately. Valid reports become result artifacts; invalid reports retain their native evidence and a failed audit outcome. Unconfirmed cleanup remains `Unknown` in the audit, while confirmed cancellation or owner exit becomes `Cancelled`; other unsuccessful stops become `Failed`. Receipts and audit gaps retain the observed stop reason. Neither a valid report nor `AttemptState.Completed` establishes semantic task acceptance.

Stdout contains a generated `SupervisorReceipt`: session/attempt, retained local directory, process phase, process success, optional result handle, delivery state and bounded problem. Diagnostics go to stderr. Result bodies do not appear in the receipt. `usageDelivered` confirms the queued artifacts and usage were acknowledged together; a pending receipt explicitly identifies incomplete publication.

## Native evidence and delivery

`NativeManifest` records original byte count, SHA-256, media type and ordered artifact handles. Each part contains base64 for at most 128 KiB of original bytes. This preserves non-UTF-8 and multipart stdout/stderr without weakening the UTF-8 artifact service. Usage points to the stdout manifest. Result extraction separately requires valid complete native JSONL and validates the final format for the selected harness.

Host uploads are frozen in typed `DeliveryBatch` files before delivery. A queue accepts at most 512 batches, 16 MiB per batch and 512 MiB in total. Individual batches contain at most 8,192 entries; the current publisher groups them in sets of 32. Private files are forced and atomically published. A separate file lock serializes publication/replay. An acknowledgement is forced only after the entire batch succeeds. If a response is lost, replay uses the same artifact/usage/attempt identities; server idempotency prevents duplicate accounting. Acknowledged files remain available and changed identities are rejected.

Retry already-spooled delivery using the same executable and a host-authorized credential:

```text
cq job upload --session /absolute/session-directory
```

This acquires a fresh collector credential for the recorded logical session and replays pending batches. It never restarts a harness or rewrites an audit observation. The original receipt remains a historical snapshot; CLI replay reports newly acknowledged batches, and current audit queries show the acknowledged state.

## Remaining integration

The durable job service already quarantines unfinished records on recovery and owns process-tree shutdown. This role has not yet connected child dispatch, hierarchy admission, claims, reference-based prompt/result chaining, consumer evaluations or interactive telemetry. Delivery recovery handles **already-spooled batches**: recovery of output after a host dies before producing those batches remains open. Preparation failures and interrupted collection still require a complete attempt-outcome reconciliation path. No automatic retention/deletion is implemented. Native distribution verification remains M6 work.

See [verification evidence](../validation/m2-supervisor-role.md).
