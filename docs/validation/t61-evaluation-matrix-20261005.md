# Three-harness evaluation matrix (T61), 2026-10-04 and 2026-10-05

This record covers T61: one recorded run of the consumer scenario of the [evaluation protocol](../evaluation-protocol.md) per governing harness — Claude Code, Codex and Pi — through intake, planning, implementation, a follow-up and measurement, with the governing Claude session as operator proxy.

**Provenance.** Everything here was read by the driver session from private evaluation roots under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/`. Each root has its own `report.md` in the form the protocol prescribes, with the child table, usage, proxy log and acceptance output. Nobody has independently reread the roots or the recordings. The driver session is also the author of the protocol's helper scripts and of several of the changes under test.

**What this record does not do.** It is one run per harness of one small scenario. It does not qualify any harness, compare models, or establish rates. Thresholds of the protocol's efficiency table were not recalibrated in this record; section 4 of the protocol recalibrates them from these runs.

## Setup

- **Package:** exact-source native package of `c3959c4` (manifest SHA-256 `3049f4638574852ad069ed6f8af699ccf12e2e8e4975c6d6124c790304e870cc`), one private server, database and consumer repository per run.
- **Harnesses:** Claude Code 2.1.285 (`opus`), Codex CLI 0.159.2, Pi coding agent 0.99.1 (`gpt-6.1-sol` through `openai-codex`). Child routes: all three, as Q38 requires.
- **Scenario:** `dev/eval-minesweeper-spec.md` version 1 (SHA-256 `d688c381…`), checks `tests` and `launch`, one follow-up (difficulty presets and an elapsed-seconds clock). Mode `driver` (Q39). By the proxy logs, drive commands were typed in four of the six runs: plan and integrate drives in the Pi run and the completed Codex run, integrate drives only in the Claude run (planning ran under the begin workflow), plan drives only in the first Codex run, and none in the two runs that ended in S2 and S1.
- **Launch:** inside the existing sandbox session, pinned executables, private configuration directories, token by file path; tmux and asciinema per session.
- **Budget:** 3 h and USD 25 per run. The operator allowed exceeding it; no run came near it.

## Outcome per run

| Run | Governor | Reached | Tasks integrated | Checks on the result | Wall time | Sessions | Ended by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `eval-20261004-01/claude-driver` (`formal-claude`) | Claude Code, `opus` | S5 | T1; T2 + T3 as one cohort | 34 tests OK, launch OK | 43 min | 1 | completion |
| `eval-20261004-01/pi-driver` (`formal-pi`) | Pi, `gpt-6.1-sol` | S5 | T1, T2, T3; T4 | 45 tests OK, launch OK | 80 min | 1 | completion, after one operator-side intervention in S2 |
| `eval-20261005-02/codex-driver` (`formal4-codex`) | Codex, `gpt-6.1-sol` | S5 | T1; T2 | 36 tests OK, launch OK | 39 min | 2 | completion, after a driver failure and the loss of the CQ host |
| `eval-20261004-01/codex-driver` (`formal-codex`) | Codex, `gpt-6.1-sol` then `gpt-6-luna` low | S2 | none | — | 13 min | 3 | stop rule: three identical stops |
| `eval-20261004-02/codex-driver` (`formal2-codex`) | Codex, `gpt-6-luna` low | S2 | none | — | 12 min | 1 | planning failed; same class as the run above |
| `eval-20261005-01/codex-driver` (`formal3-codex`) | Codex CLI on `mimo-v2.6-pro` | S1 | none | — | 1 min | 1 | stop rule: three identical failures of the first tool call |

Each of the three harnesses completed the scenario once with its default model. In all three completed runs the follow-up behaved as specified in the driver session's probes (`--difficulty` presets, conflict with `--width`/`--height`/`--mines` exits 2). No run exercised the curses screen. Goals and Milestones stayed Open in every run: the scenario asks for no audit or closure.

The first Codex run is not a clean observation: the proxy's first answer was worded so that the stored answer told the Planner to wait (a driver-session error, corrected in the protocol), and the model was changed during the run to spare the Codex quota. The two runs on smaller models show that they do not get through planning or the first tool call with the current prompts and fault texts; they say nothing about the default model.

## Child attempts

| Run | Attempts | By harness | Failed | Plan rounds beyond the first | Candidate rounds beyond the first |
| --- | --- | --- | --- | --- | --- |
| Claude | 21 | Claude 11 (Planner, Worker), Codex 10 (Reviewer) | 0 | G1 +1, G2 +2 | T1 +1 |
| Pi | 27 | Pi 15 (Planner, Worker), Codex 12 (Reviewer) | 1 Planner (malformed JSON) | G1 +2, I2 +1, G2 +1 | T1 +1 (host validation failed) |
| Codex (run 02 of 10-05) | 15 | Codex 7 (Planner, Worker), Claude 8 (Reviewer) | 0 | I2 +1 | none (+1 repeated review after the stranded integration) |

Every Governor planned and implemented with children of its own harness and reviewed with another one. None of these three runs used all three child routes. The two early-ended Codex runs of 10-04 did start children of all three harnesses, in planning only (run 01: Codex 4, Claude 1, Pi 1; run 02: Codex 4, Claude 1, Pi 2). The mix required by Q38 was therefore configured in every run and exercised in no completed one.

One cross-item cohort formed (Claude run: T2 and T3, one shared candidate, one review). The Pi run assessed T1–T3 for a cohort and then ran them singly.

## Stops and proxy work

| Run | Required input | Quiescence | Unwarranted | Failure | Proxy answers | Other proxy actions |
| --- | --- | --- | --- | --- | --- | --- |
| Claude | 1 | 2 | 1 (approval asked in prose) | 0 | 2 | one typed confirmation of a pasted request (harness dialog) |
| Pi | 2 | 4 | 0 | 1 | 2 | **one intervention outside the proxy role**: a BlockedBy link was removed through the operator API |
| Codex (run 02 of 10-05) | 2 | 3 | 0 | 2 | 2 | one harness restart after the host exited |

Between stops the sessions ran without help: the longest unattended stretches were the implementation drives (14, 27 and 11 minutes).

## Usage

Bases are kept apart, as the protocol requires; unknown is not zero.

| Run | Children, CQ-measured input / output tokens | Children, client estimates | Outer session | Outer cost |
| --- | --- | --- | --- | --- |
| Claude | 2.48 M direct + 1.48 M shared / 94 k | USD 3.56 (Claude children); Codex reviewers unknown | transcript: cache read 24.9 M, output 44 k, 167 responses, largest request 230 k | unknown; `/cost` not captured |
| Pi | 3.58 M direct + 0.05 M shared / 61 k | USD 1.17 (Pi children); Codex reviewers unknown | CQ: input 32.8 M (cache read 32.5 M), output 33 k | USD 4.17 client estimate (matches the Pi footer) |
| Codex (run 02 of 10-05) | 2.38 M direct / 56 k | USD 1.77 (Claude reviewers); Codex children unknown | CQ: input 12.3 M (cache read 12.1 M), output 19 k; exit lines: cached 12.46 M, uncached input 222 k, output 19.8 k | unknown |
| Three early-ended Codex runs | unknown | unknown | unknown | unknown |

The usage of the three early-ended runs was not read before their private servers were stopped, and the databases were removed with them. That is an omission of the driver session; the protocol now says to measure before stopping.

In every completed run the governing session read 6 to 11 times as many cached tokens as all of its children together: the Governor's context grows with every tool result and is re-read on every call. This is the largest cost component by token count and the one with the least complete cost figure.

## Findings

Recorded in the operator's CQ project, each with its evidence paths.

| Item | Finding | Seen in | Status of the reproduction |
| --- | --- | --- | --- |
| D143 | An MCP response above the 2 MiB frame bound ends the `cq host` process; `read Catalog` always exceeds it | Codex run 02 | reproduced in isolation (`repro-catalog/`) |
| D144 | An integration left Pending by a failed Integrate cannot be settled; the driver stops with failure and no new workflow can start | Codex run 02 | observed once, with the full tool-call record |
| D145 | A child result that fails admission is not retried: the receipt says Retry, the next selection defers the input | Codex runs 01 of 10-04 and 02 of 10-04, Pi run | observed three times; cause read in the code |
| D146 | A requirement about the process ("ask before implementing") is demanded as a Task criterion by one reviewer and rejected as untestable by another | Codex runs 01 and 02 of 10-04, Claude run (extra plan rounds) | observed |
| D147 | Governors ask for approval or continuation in prose without recording a Question | Claude run, Codex runs 01 and 02 of 10-04 | observed; model behaviour |
| D148 | Claims of an ended Governor session exclude the work for a new session until the lease expires | Codex run 01 of 10-04 | observed; known from earlier drives |
| D149 | The fault for an undecodable tool argument does not say what is expected | Codex CLI on `mimo-v2.6-pro` | observed with one model |

Not recorded as items: the Claude Code confirmation dialog for a pasted request (a harness behaviour; the proxy types `go`); the Pi run's unformed cohort and its accepting second review of T3 without a Worker attempt in between (not examined).

## Limits

- One run per harness; no repetition, so nothing here is a rate.
- The operator proxy and the author of the software under test are the same session.
- In the completed runs Pi children ran only under the Pi Governor, and Claude children only under the Claude Governor and as reviewers under Codex; no completed run had all three child harnesses. Pi plan reviewers ran under a Codex Governor only in the two early-ended runs of 10-04.
- The recovered Pi retry of RS16/D140 did not occur.
- The host-level launcher of the protocol was not used; all runs used the in-sandbox launch.
- Outer cost is unknown for Claude Code and Codex.
