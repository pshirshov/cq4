# Process suites under machine load (D112)

Recorded 2026-10-02 on branch `x2/process` (`1f3220c`, `f96c3af` and the commit carrying this page), from `main` at `2be0408`. Logs: `/srv/nvme/tmp/cq4-x2-process-scratch`. Machine: 48 logical processors, sbt 2.0.9, GraalVM CE 25.0.4.1.

D112: `./dev/check process` failed intermittently while other builds ran on the machine, on assertions that a call returned within 100–500 ms ("839 was not less than 500", "212 was not less than 200") and on "Declared check execution/publication failed: Lost check publication acknowledgement".

## What changed

Three suites state what had happened when a call returned instead of how long the call took.

| Suite | Before | Now |
| --- | --- | --- |
| `JobSupervisorProcess` | `start` and `cancel` within 500 ms | Workspace preparation is held by a gate; a `start` or `cancel` that returns with phase `Preparing` and no launch counted has been acknowledged before preparation and execution. |
| `JobSupervisorProcess` | sleep 1200 ms, then `poll` the acknowledgement fiber (two cases) | await the fiber and assert that it failed. |
| `JobSupervisorProcess` | the next journal write after arming fails | The supervisor's monitor is held at its next observation, so the write that fails is the cancellation's. |
| `GuardianDriverProcess` | `start` within 200 ms | The command ends only when a gate file exists; the file is created after `start` returned; the command must end with its own exit, not the execution deadline. |
| `GuardianDriverProcess` | `cancel()` within 100 ms | The observation `cancel()` returns is `Stopping` without a result, for a `sleep 30`. |
| `ReviewerChecksProcess` | poll `request("verify", 1000)` until terminal, then poll the reviewer job | Wait for the failing upload, `close` the checks (which awaits the cleanup), read the stop reason and the job's target once. |

New cases: the supervisor's start and cancellation over a journal whose every write takes 600 ms (`D112: acknowledge a start and a cancellation …`), and the reviewer-check contract the poll tripped on (`D112: refuse a further check request with the stop reason once publication has failed`).

No product source changed.

## Failing first

A flake does not fail on demand, so each old assertion was made to fail by an injected delay, and the new one was then run with the same delay. One suite per sbt run, `server/testOnly cq.server.<Suite> -- -z "<case>"`.

| Old assertion | Injection | Old result | New result with the injection |
| --- | --- | --- | --- |
| `start` within 500 ms | every journal write takes 600 ms | `Preparing equaled Preparing, but 628 was not less than 500` | passes; kept as a permanent case |
| `cancel` within 500 ms | the same | `Stop equaled Stop, but 624 was not less than 500` | passes; the same permanent case |
| reviewer-check poll | the first journal write of a `Stop` target takes 800 ms | `Declared check execution/publication failed: Lost check publication acknowledgement` (thrown from `ReviewerChecks.request` inside the poll) | both lost-publication cases pass |
| next write after arming fails | `cancel()` of the process returns 100 ms late | `failed.isLeft was false` | passes |

The first run of the old cancellation assertion did not fail but hung until it was killed after ten minutes, with no thread of the test JVM inside project code. Read from the code: the job's fiber is forked inside the uninterruptible region of `JobSupervisor.acknowledge`, so the 5-second timeout on the preparation gate could not interrupt the wait, and the gate was released only after the supervisor's scope had closed, which waits for the job. The reformulated cases release the gate inside the scope. The same hang would have followed any failure between `entered` and `release` in the old case.

The guardian cases have no injection point; they were only run.

## Runs under load

Each run is one fresh sbt JVM and one forked test JVM for one suite, selected by exact name.

| Suite | Load | Runs | Passed | Seconds per run |
| --- | --- | --- | --- | --- |
| `JobSupervisorProcess` (`1f3220c`) | 96 busy loops; load average 125–145 | 10 | 9 | 99–192 (unloaded: 23–26) |
| `GuardianDriverProcess` (`1f3220c`) | 96 busy loops; load average 134–155 | 3 | 3 | 116–150 (unloaded: 30) |
| `ReviewerChecksProcess` (`f96c3af`) | 24 busy loops; load average 5–26 | 3 | 3 | 17–24 (unloaded: 13–18) |

- The load was shell busy loops (`while :; do :; done`), no `stress-ng` in the development shell. The load average includes other builds running on the machine at the time; the first `JobSupervisorProcess` run started before the load average had risen (12.5).
- The run with 96 loops was stopped by the coordinator after the third `GuardianDriverProcess` run because it starved other work; the remaining runs were limited to 24 loops and three runs. With 24 loops on 48 processors the reviewer suite ran as fast as without load, so those three runs say little about load.
- The one failure was `cancel the retained process even when persisting cancellation fails`: `failed.isLeft was false`. It is not one of the reformulated assertions. Cause, reproduced without load by the injection above: the monitor's write took the injected failure. Corrected in `f96c3af`; the corrected suite was not run again under load.
- Without load, after all changes: `JobSupervisorProcess` 13 of 13, `GuardianDriverProcess` 10 of 10, `ReviewerChecksProcess` 8 of 8; `./dev/check process` passed once (12 suites, 106 tests, 293 s).

## The test runner executes unselected suites

Verified by running, with `-Dizumi.distage.testkit.debug=true` and with temporary suites that write a marker file from their constructors and test bodies (removed afterwards):

| Selection | Test bodies executed | Reported |
| --- | --- | --- |
| `server/testOnly cq.server.A` (one exact name) | those of `A` only; every suite class is constructed | 1 suite, its tests |
| `server/testOnly *A` (pattern) | those of every `SpecZIO` suite of the module: `Running test...` for 384 tests in 45 suites, a failing unselected test included | 1 suite, 1 test, `All tests passed.` |
| `server/testOnly cq.server.A cq.server.B` (two exact names) | those of one of the two | `Suites: completed 2`, the tests of one |

So the planning claim holds for a selection by pattern, which is what every `dev/check` step used (`*Process`, `*Postgres`, `*Dummy *Local`), and not for one exact name. During `dev/check process` the Dummy and Local suites ran in the same JVM as the timing assertions, and nothing reported them. The third row is [izumi#2361](https://github.com/7mind/izumi/issues/2361); the second is its converse and is written up for an upstream report in `/srv/nvme/tmp/cq4-crosscut2-planning/distage-finding.md`, with a standalone reproducer that was run (`/srv/nvme/tmp/cq4-x2-process-scratch/repro`).

`dev/check process` now lists the suites with `show server/Test/definedTestNames` and runs each `*Process` suite by exact name in an sbt run of its own.

## Not covered

- The old assertions were not run under the same synthetic load, so there is no measured failure rate to compare with; the comparison rests on the injected delays.
- `GuardianDriverProcess` has 3 loaded runs and `ReviewerChecksProcess` none under contention. The sample is small throughout.
- Remaining dependence on wall-clock time in these suites, not changed:
  - `suppress launch when cancellation races a stalled Starting record` sleeps 100 ms to let a forked `cancel` reach the process before the journal is released. Nothing observable from outside tells that it has; this needs a seam in `JobSupervisor`.
  - The fixtures pass production limits to the guardian (startup 2 s, heartbeat 900 ms). No failure from them was observed in 13 loaded runs. Widening the heartbeat also delays cancellation by up to a third of it.
  - Liveness timeouts (3–30 s) remain. Those of `GuardianDriverProcess` for a helper that must be declared `Uncertain` leave about one second above the limit they wait for.
- The supervisor's 1000 ms journal acknowledgement deadline (D115) is production behaviour and bounds these tests: the two stalled-journal cases exist to see it fire; `suppress launch …` needs it not to fire between the forked `cancel` and the release; the slow-journal case leaves 400 ms between its 600 ms writes and the deadline; and every `start` or `cancel` in the suite fails if the machine delays an acknowledgement beyond it.
- The other `dev/check` steps still select by pattern (`*Dummy *Local`, `*Postgres`, the usage and cohort contracts) and still execute unselected suites. Changing them was not validated here: one sbt run per suite costs about 10 s each, and the PostgreSQL gate was not run.
- The other nine `*Process` suites were not examined for wall-clock assertions.
