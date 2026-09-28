# Local evaluation launcher verification

The user requested a server launch script and a simple project walkthrough after the technical release review. [Quickstart](../quickstart.md) and [launcher](../examples/launch-local.sh) use the existing native distribution; they do not change its executable, contracts, harness adapters or evaluator inputs.

Actual scratch evidence: `/srv/nvme/tmp/cq4-launcher-smoke-20260928/verification`. The final `result.json` binds launcher SHA-256 `3a8c5561f7d72a7d049ca9970b1eb619c423ce25b1bbbc18f9646206817b4ecc`. `setup.sh` was extracted from the walkthrough's project/settings command blocks, substituting only the scratch state path and running inside the pinned Nix shell.

| Check | Observation |
| --- | --- |
| Fresh local startup | PostgreSQL 18 and the installed native CQ server start; authenticated hello returns the expected 0.1.0 contract |
| Project setup | Git base/unchecked-out target branch, CQ initialization, Codex settings and four project command exports succeed |
| Client/UI smoke | Actual query, usage summary and browser HTML request succeed; no model calls |
| Duplicate launcher | Same-state invocation fails on the ownership lock; the original server remains available |
| Normal shutdown/restart | SIGTERM stops server/database in 0.214 s; persistent token/project identity and subsequent query/usage access survive restart |
| Suspended CQ process | Original launcher remains blocked after 17 s; corrected launcher escalates to SIGKILL and completes cleanup in 9.578 s |
| Restart after forced exit | Native server/database restart; SIGINT cleanup completes in 0.214 s |
| Occupied HTTP port | Startup fails explicitly and stops its own private database; the pre-existing listener stays owned by the test |

The shutdown reproduction precedes its correction and is retained in `shutdown-before.json`. The correction bounds server termination before waiting/reaping, reports escalation, and returns unresolved status 75 if settlement cannot be confirmed. PostgreSQL shutdown has its own thirty-second bound. An initial detached test inherited ignored SIGINT/SIGQUIT; `interrupt-test-diagnosis.json` records that test-environment limitation. The final test restores default SIGINT disposition before exercising the launcher.

These are Behavioral/Active, Effectual, Good Communication checks against actual owned services. No fresh paid consumer evaluation was launched: the walkthrough leaves those commands for the user's evaluation. Existing release evidence covers the unchanged managed workflows; this smoke check establishes the new helper's lifecycle and setup behavior. Human acceptance remains pending.

Independent Astra approves the final launcher and walkthrough, including the reproduced shutdown correction and retained native smoke evidence. Its verdict is retained as `astra-review.json` and bound in the release evidence manifest.

## Subsequent network/browser correction

The launcher now binds CQ to `0.0.0.0`, accepts an explicit browser `CQ_ORIGIN`, and keeps database/readiness traffic on loopback. Its network-tested revision has SHA-256 `0fd02ab5dec83c6a372e2f696c9fcf543df1c9e4fd4898961cac651bcab69629`. The lifecycle table above remains evidence for the earlier helper revision. `/srv/nvme/tmp/cq4-launcher-network-20260928/result.json` verifies non-loopback protocol access and origin enforcement. `/srv/nvme/tmp/cq4-http-ui-20260928/delivery/result.json` verifies the corrected native package through that launcher revision: actual hostname Chromium login, live connection, project/tasks and relationships pass; CQ listens on all interfaces, PostgreSQL on loopback, and owned shutdown completes. The original plain-HTTP browser failure and correction are retained in the [HTTP record](http-ui.md).

## Detached database lock inheritance

The user's later relaunch reports an occupied launcher lock while HTTP is stopped and PostgreSQL remains live. Scratch reproduction confirms that PostgreSQL inherits descriptor 9, retaining `launcher.lock` after the launcher is killed. `/srv/nvme/tmp/cq4-launcher-lock-20260928/verification.json` binds the failed before-check, the exact launcher copies and the passing after-check. The correction closes descriptor 9 on `pg_ctl start`, just as the CQ child already does. PostgreSQL remains running after the deliberate parent SIGKILL, but no longer owns the launcher lock. Both tests stop their owned services afterward; Bash syntax and diff checks pass.

This changes the operator helper only. The prior network/browser proof above retains its original launcher hash; the release evidence manifest records the new hash and this scoped regression. A surviving database still requires explicit cleanup before restart; the launcher does not silently adopt it. The user's actual host processes are outside the agent's PID namespace. The user-run diagnostic confirms that PostgreSQL and its workers alone retain the lock. No user lock file was removed and no user process was signaled during the scratch reproduction.

Independent Astra approves the descriptor-inheritance correction and before/after evidence. The final helper SHA-256 is `670f9800a9d1c6438e4ff67eac6005f972a127754f38c4003cf4311066d5bc2f`; the verdict is retained as `astra-review.json` beside the reproduction.

The user then ran the prepared host recovery script. It verifies the recorded postmaster PID, executable, data directory and inherited descriptor, opens a pidfd, and requests PostgreSQL fast shutdown through that stable handle. After observing process exit and lock release, it starts `cq-release-http-ui` with the existing state. `host-recovery.json` binds the diagnostic, script/output and actual browser verification. CQ listens on all interfaces at 8080, PostgreSQL on loopback at 55432. Login with the existing token and the ALIVE connection pass on `http://vm.home.7mind.io:8080` with `randomUUID` unavailable and no browser errors. The browser check makes no project mutations.

## Permanent repository launcher

`run-local.sh` now selects `.local/release`, the existing playground state and the VM hostname origin automatically. The [compact delivery record](compact-ui.md) includes actual invocation from an unrelated directory, native HTTP login, both laptop sizes, persistent project/credentials, duplicate rejection and owned SIGTERM/Ctrl-C cleanup. The underlying helper and its descriptor correction remain unchanged.

## Repeated interrupts during cleanup

The user reported a leftover private database after pressing Ctrl-C twice. The launcher reset SIGINT/SIGTERM handling on cleanup entry. A second interrupt could therefore terminate it before stopping PostgreSQL. A separate reproduction showed that a closed `tee` reader could terminate cleanup on its timeout diagnostic. The old host recovery script used that pipeline form.

Both failures were reproduced before correction using actual private PostgreSQL and a deliberately suspended, owned native CQ server. Evidence is `/srv/nvme/tmp/cq4-launcher-terminal-20260928`: `double-before/result.json` and `pipeline-before/result.json` retain the database after interruption; both corresponding `*-after/result.json` checks stop it. Suspension makes the cleanup window deterministic; the user's exact host timing was not observed. Normal single-interrupt cleanup passed before the change. The first stalled fixture failed in its process-list traversal; that is a fixture error, not a product reproduction.

Cleanup now ignores repeated INT/TERM and broken-pipe signals, writes diagnostics to `logs/launcher-cleanup.log`, and sends a final best-effort terminal notification. Existing server/database deadlines are unchanged. Both corrected cases finish with exit 130 and no PostgreSQL PID file. No backend suites, native rebuilds or model calls were run. `verification.json` binds the source and retained observations. Independent Astra approves the correction with no blocking or major findings (`astra-review.json`).

The existing host database is idle with no connected clients, and CQ's port is closed. It remains outside the sandbox PID namespace. A prepared host recovery script verifies the recorded PID/start time, executable, owner, data directory, free HTTP port and absent database clients while holding the launcher lock, then uses a pidfd for bounded clean shutdown. Host execution and subsequent login verification are pending.
