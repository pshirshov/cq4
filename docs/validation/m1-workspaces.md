# M1 workspace foundation evidence

- `20260926T204316-fast`: 32 passing scenarios, no skipped tests. Includes the shared ownership/retry/quarantine scenario against dummy and real Git, governing dirty content/index isolation, two independent detached attempts, durable record reads, uncertainty after actual creation, and bounded command silence/output.
- `20260926T204406-contracts`: deterministic generation, strict Scala/TypeScript compilation and schema/codec checks pass with the new workspace types.

Exact logs, command records and source hashes are retained under `/srv/nvme/tmp/cq4-implementation/`. Scratch repositories are created under ignored `.work/` and removed by their test lifecycle. Reference snapshots remain untouched. The host module is a service foundation for M2; it has no harness runner, cleanup, integration or public workspace CLI yet.

`20260926T204500-postgres` reproduces the pre-existing CLI Git lookup defect: stdout reading prevents the nominal 10-second wait deadline from being reached; the fixture terminates the owned process group at 12 seconds. CLI identity lookup now uses the bounded host command runner. `20260926T204624-postgres` passes all 27 PostgreSQL scenarios, the CLI deadline regression, actual HTTP/WebSocket/MCP clients and SIGKILL restart. Full M1 Astra review requested two additional corrections, both initially source-derived predictions:

- `20260926T204749-fast` had already reproduced acceptance of a replaced prepared directory. Reuse now verifies Git top-level/common directory and quarantines mismatches while retaining the original observation; `20260926T204848-fast` passes all 33 scenarios.
- `20260926T205044-fast` reproduces both checkout containment cases: a source subdirectory and a root symlink alias admit workspaces inside the governing checkout. Placement now resolves the actual Git top-level and canonical existing root ancestors before containment and before creating lock/ownership files. Verification is pending.
- `20260926T205206-postgres` reproduces the CLI HTTP body stall: after valid headers the client still has not exited at 34 seconds, beyond its declared 30-second deadline. Complete-body collection now uses the Java 25 bounded body handler and a deadline on the full response future, with cancellation and immediate client shutdown on failure. The fixture also checks successful initialization afterward with the same saved UUID, proving release of the identity lock. Corrected verification is pending.

Astra reports no other blocking/major finding in the reviewed M1 ledger, claims, accounting, authentication or transport paths; M1 remains unapproved pending corrections. Latest accounting/actual PostgreSQL/client/browser evidence is linked from [read bounds](m1-read-bounds.md).


`20260926T205412-fast` passes all 34 scenarios, including both canonical containment cases. `20260926T205507-postgres` passes the application timeout and successful follow-up initialization, then fails in the new fixture: it parses the CLI's JSON plus configuration line as one JSON document. The fixture now parses the first line, matching the existing CLI tests. This is a fixture correction; `20260926T205703-postgres` passes the whole pipeline: 27 PostgreSQL scenarios, actual transports/CLI, stalled-body deadline, recovery with stable initialization identity, and SIGKILL restart.


`20260926T205927-contracts` passes final deterministic generation, strict Scala/TypeScript compilation, cross-language codec checks and generated schema verification. All currently required correction checks pass. Astra approved M1 at `439fc50`; see the [milestone verdict](m1-review.md).
