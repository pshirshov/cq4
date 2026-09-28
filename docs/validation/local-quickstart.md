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

The launcher now binds CQ to `0.0.0.0`, accepts an explicit browser `CQ_ORIGIN`, and keeps database/readiness traffic on loopback. Its current SHA-256 is `0fd02ab5dec83c6a372e2f696c9fcf543df1c9e4fd4898961cac651bcab69629`. The lifecycle table above remains evidence for the earlier helper revision. `/srv/nvme/tmp/cq4-launcher-network-20260928/result.json` verifies non-loopback protocol access and origin enforcement. `/srv/nvme/tmp/cq4-http-ui-20260928/delivery/result.json` verifies the corrected native package through the current launcher: actual hostname Chromium login, live connection, project/tasks and relationships pass; CQ listens on all interfaces, PostgreSQL on loopback, and owned shutdown completes. The original plain-HTTP browser failure and correction are retained in the [HTTP record](http-ui.md).
