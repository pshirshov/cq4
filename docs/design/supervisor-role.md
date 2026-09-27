# Local supervisor role

`cq.server.Main` registers `ServerRole`, `ClientRole` and `SupervisorRole` through the same distage `RoleAppMain`. `cq run HARNESS --settings FILE --input FILE` selects the supervisor task; `cq :supervisor -- HARNESS --settings FILE --input FILE` is equivalent. Its dependency graph contains injected harness adapters, the local job lifecycle and HTTP clients. It does not acquire a PostgreSQL connection or the CQ domain server.

The role runs one **batch governing session**, with the six scoped domain tools and a private [local child dispatch service](local-dispatch.md). The host resolves referenced input, executes workers and reviewers, captures candidates and validation, and returns compact status/result handles. Interactive sessions and the complete `cq run` workflow remain unfinished.

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
  },
  "checks": [],
  "evaluation": null,
  "integrationTarget": null
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

Set `integrationTarget` to an explicit existing full branch reference such as `refs/heads/integration` to enable reviewed-candidate integration. The branch must not be checked out when applying the update. Use `null` for candidate-only sessions.

For evaluation runs, set `evaluation` to `{"run":"run-identity","scenario":"scenario-identity","assessor":false}`. Both identifiers are nonempty and at most 300 characters. The identity is frozen in the governing and child assignments and can be queried through the existing evaluation usage filter. Use `assessor: true` only for actual assessment overhead; ordinary product work uses `null`.

The role publishes its input and installed instructions as immutable artifacts, registers an unattributed governing assignment/attempt, then starts the native job through the existing guardian. Process success requires confirmed cleanup, normal `Exited` termination, exit code zero, no signal and no host failure. A zero exit after a deadline or cancellation cannot admit a result. Native completion and the bounded `GoverningReport` schema are checked separately. Valid reports become result artifacts; invalid reports retain their native evidence and a failed audit outcome. Unconfirmed cleanup remains `Unknown` in the audit, while confirmed cancellation or owner exit becomes `Cancelled`; other unsuccessful stops become `Failed`. Receipts and audit gaps retain the observed stop reason. Neither a valid report nor `AttemptState.Completed` establishes semantic task acceptance.

Stdout contains a generated `SupervisorReceipt`: session/attempt, retained local directory, process phase, process success, optional result handle, delivery state and bounded problem. Diagnostics go to stderr. Result bodies do not appear in the receipt. `usageDelivered` confirms the queued artifacts and usage were acknowledged together; a pending receipt explicitly identifies incomplete publication.

## Native evidence and delivery

`NativeManifest` records original byte count, SHA-256, media type and ordered artifact handles. Each part contains base64 for at most 128 KiB of original bytes. This preserves non-UTF-8 and multipart stdout/stderr without weakening the UTF-8 artifact service. Usage points to the stdout manifest. Result extraction separately requires valid complete native JSONL and validates the final format for the selected harness.

Host uploads are frozen in typed `DeliveryBatch` files before delivery. A queue accepts at most 512 batches, 16 MiB per batch and 512 MiB in total. Individual initial batches contain at most 8,192 entries; final publication groups entries in sets of 32. The complete final set is forced in a staging directory, atomically renamed to `final/`, and its parent forced before any final batch can be delivered. Existing final sets must pass that parent durability barrier on replay as well. A separate file lock serializes publication/replay. An acknowledgement is forced only after the entire batch succeeds. If a response is lost, replay uses the same artifact/usage/attempt identities; server idempotency prevents duplicate accounting. Acknowledged files remain available and changed identities are rejected.

Reconcile interrupted publication and retry delivery using the same executable and a host-authorized credential:

```text
cq job upload --session /absolute/session-directory
```

This holds the session journal's exclusive owner lock throughout credential acquisition, reconciliation and replay. An active supervisor prevents upload. It acquires a fresh collector credential for the recorded logical session and replays committed governing and child batches unchanged. Attempts without committed final publication are reconciled as described below. The original receipt remains a historical snapshot; CLI replay reports newly acknowledged batches, and current audit queries show acknowledged outcomes and coverage.

## Remaining integration

The durable job service quarantines unfinished records on recovery and owns process-tree shutdown. The role connects child dispatch, claims and reference-based prompt/result chaining. All three governing routes have independently assessed consumer candidates. Candidate integration, interactive telemetry and native distribution remain later milestone work. No automatic retention/deletion is implemented. Forced shutdown exits 75 and requires reconciliation; see the [shutdown boundary](local-dispatch.md#delivery-and-shutdown).

See [verification evidence](../validation/m2-supervisor-role.md).

### Interrupted publication

The implemented [atomic publication boundary](../validation/m2-publication.md) commits a complete set of final delivery batches. Initial assignment/input publication remains separate. Final artifact/usage/outcome batches become eligible for HTTP only after every batch and its staging directory are forced, the directory is atomically renamed, and its parent is forced. Replay uses the committed bytes and identities verbatim, including after ambiguous acknowledgement; uncommitted staging is ineligible for delivery.

The bounded inventory includes the governing run and at most 32 child tickets, including attempts interrupted before job creation. If final publication was not committed, recovery retains a byte snapshot of available stdout/stderr (at most 32 MiB each), collects observable usage, quarantines unresolved workspaces and publishes an `Unknown` outcome with an explicit interruption/coverage gap. Missing streams produce an absence gap. Observations from a recovery snapshot cannot have complete coverage, even if the snapshot contains a native terminal event. Attempts without observable meters retain missing-meter coverage.

A journal lock does not prove guardians stopped writing; a quiet file cannot establish process settlement. Recovery does not adopt saved PIDs or admit an uncommitted candidate result. Nonterminal jobs become `Uncertain` with target `Stop`; separate unresolved validation workspaces are also quarantined without inventing model attempts. Once the recovery set commits, later output cannot change its bytes, timestamps, observation identities or totals on replay.

A child directory whose ticket never committed has no trustworthy assignment identity. Recovery preserves and reports recognized partial ticket/cancellation writes, replays valid publications, and exits nonzero with the unresolved paths. It does not fabricate an audit attempt. Malformed committed tickets, unexpected files or a job lacking its required committed ticket fail explicitly.

Astra approved atomic publication and reconciliation after the final runtime gates. [Reconciliation evidence](../validation/m2-recovery.md) covers interrupted capture, committed replay, lost acknowledgement, incomplete tickets, live ownership denial and actual JVM termination. M2 human acceptance remains a separate checkpoint.
