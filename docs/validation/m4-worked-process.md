# M4 worked-process evaluations — in progress

The [evaluation contract](../design/live-process-evaluations.md) follows the accepted nine-route cohort corpus at `d224f3f`. It requires full intake/planning/work/review/integration and defect-investigation/upstream examples. This increment starts only the persisted language-question checkpoint; later stages and independent process-quality assessment remain open.

`./dev/process-eval begin` creates an unrelated empty consumer/project, invokes the installed begin workflow with native Claude/Codex/Pi routes, and archives item views, full revision histories, child artifacts, native transcripts, the database and ordinary operational usage before returning a pending question. Python versus Go is intentionally left unanswered at this checkpoint; both use the existing unchanged word-frequency contract and oracle. The user subsequently supplied the actual answer **Go**.

The structural checkpoint requires Idea → Goal → Question derivations, no intake/goal milestone ownership, no task/milestone creation, an Open unanswered question whose ID/options appear in the bounded receipt, complete histories matching current views, no earlier fabricated answer/adopted decision, no implementation/conflict-resolution workers, and unchanged revisions without claims/integration holds. Exports explicitly include archived records. Every recorded stage attempt must have an observed input/output counter with correct evaluation attribution; coverage gaps remain visible in the shared audit.

Astra found that checking only current state would accept an invented answer later cleared or an adopted decision later reverted. `/srv/nvme/tmp/cq4-implementation/process-checkpoint-reproductions/before.log` records those failures plus conflict-resolution and premature task/milestone cases before correction. `after.log` passes six tests with focused negative cases. Astra independently approved the corrected source for native execution conditional on the fast gate. Structural checkpoint acceptance does not establish that planning was independently approved, the complete specification was preserved, or the full process succeeded; those are required later checks.


## Initial native checkpoint

`20260927T195027-fast` passes all 188 Scala scenarios and bridge/evaluator checks, including the six process predicate tests. `20260927T195115-process-begin` passes the structural checkpoint in 158.433 seconds with native Claude governor, Codex Planner and Pi Plan Reviewer. All three attempts have observed input/output counters and ordinary evaluation attribution. The two source manifests match all 228 non-documentation files in `process-checkpoint-source-verification.json`.

The retained graph is I1@3 → G1@2 → Q1@4. Q1 remains Open with no answer and alternatives Python/Go; its prompt asks which implementation language the consumer should use. The host export contains complete histories and an atomic unchanged-revision preview without claims/integration holds. All processes and the isolated database have exited normally; the archive supports a new governing session after the actual reply. The real Q1 choice has now been presented to the user.

The governing report explicitly records that the reviewed proposal also produced Q1 linked to I1, then the governor edited its alternatives and reattached it to G1. Those changes were outside the prior Pi review and remain in history. The checkpoint predicate establishes the final pending-choice structure and absence of earlier fabricated answers; it does not relabel those later edits as independently reviewed. Full planning/application/specification quality remains subject to the later process evidence and independent assessment.

Astra independently approved this scoped checkpoint increment for commit after verifying matching source manifests, the fast gate, complete revision sequences, continuously unanswered Q1 history, exact claim-free handoff and expected native roles. Resume requires the actual user reply and fresh-session authority.

## Supplied reply and resume runner

The actual user reply is retained in `/srv/nvme/tmp/cq4-implementation/process-q1-user-answer.json`, bound to project `2f24c38c-d6f6-4b88-8738-a4bd1b75f671`, Q1@4 and the originating async question. This is a language choice, not a milestone or release acceptance. The original checkpoint remains unanswered and unchanged.

`./dev/process-eval resume --checkpoint DIRECTORY --answer-file FILE` verifies the archived checkpoint, restores its database into a new isolated instance, commits the supplied answer record in the consumer, and records it through the ordinary Human mutation path. A new Claude governing session requests new Codex planning and Pi Plan review, followed by Pi implementation, independent Codex candidate review, fresh host oracle execution and recorded integration. All consumed checkpoint files are hashed; the specification and oracle must match the baseline. The evaluator reconciles restored, stage and combined usage/cost/coverage totals without duplicating baseline attempts.

The integrated-candidate predicate requires continuous preservation of the actual answer/citation, Decision evidence, two derived task scopes under one Milestone, one Handoff shared by both tasks, fresh reviewer checks, exact local/server integration observations, and current Done revisions matching Recorded acknowledgements. Post-integration task edits and changed-then-restored answers reproduced as false acceptance before correction; logs are in `process-resume-reproductions`. Eleven focused tests now pass. Astra approved scoped native execution conditional on the fast gate. Planning/application linkage and independent full-process assessment remain explicit pending checks even if this stage passes.

## Native Go resume

`20260927T201354-fast` passes all 188 Scala scenarios and bridge/evaluator checks, including eleven process predicate tests. `20260927T201449-process-resume` passes the integrated-candidate predicates in 681.076 seconds, with 11 new attempts. Its source manifest matches the fast gate on all 228 non-documentation files (`process-resume-source-verification.json` in the fast evidence directory).

- Q1@5 records the actual **Go** reply through the Human path; Q1@6 preserves that answer and its exact file/commit citation. The answer record remains byte-identical in the integrated candidate.
- Adopted Decision 1 derives from Q1. Tasks T1/T2 derive from G1 and belong to Milestone 1. One Handoff derives from both tasks. No historical Idea/Goal milestone ownership appears.
- Pi Worker `0bd944d7-fc1b-4547-9ada-369b198628eb` implements T1/T2@3. Codex Candidate Reviewer `c6696582-6289-45ce-a26c-f126c89a4f75` accepts both on candidate `9cc3f9a999882735a635d7d14c2d2c653afcbced`. Separate Worker and Reviewer oracle executions each report 110 behavior cases and passing consumer tests.
- Integration `a1b2c3d4-0011-4e00-8000-000000000011` is Recorded at that exact candidate on `refs/heads/integration`. Both current tasks are Done at the host-acknowledged revision 4. The final preview has no claims or integration holds, and the database/usage archive closes without errors.

Planning quality remains qualified. The first two Codex proposals each produced one task and were not applied. The governor added the requested decomposition to G1's body/scope, then obtained further proposals/reviews. Pi requested a missing Handoff, then missing Milestone/Handoff relationships. The governor applied proposal `a91e104f-9670-3647-a980-c1e42d3235b7` despite that final ChangesRequested verdict, explaining that the relationships needed server-allocated IDs; it then added those links directly. The native receipt discloses these choices. This stage does not relabel that proposal or the later graph edits as independently accepted planning. Their exact history and review/application links require the pending process assessment.

The shared operational audit reconciles **901,570** known baseline tokens plus **5,615,092** new stage tokens to **6,516,662** combined known tokens across 14 distinct attempts. All 14 meters are partial; none is absent. Native provider estimates remain separated by attribution and price basis, with unknown costs preserved. No billed total, complete usage total or efficiency improvement is claimed.

`measure-parent-traffic.py` and `parent-traffic.json` in the resume evidence directory derive traffic from the hashed Claude transcript: 47 dispatch calls, maximum 869-byte arguments and 1,233-byte reply text. Three explicit result reads returned 1,167, 585 and 935 bytes (only the first reads a complete body). The transcript retains corrected claim-duration, malformed-JSON and duplicate-item-in-batch errors. Byte measurements exclude native envelopes, duplicate structured content and model tokenization.

Reproduce the runner with:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check fast
./dev/process-eval resume \
  --checkpoint /srv/nvme/tmp/cq4-implementation/20260927T195115-process-begin \
  --answer-file /srv/nvme/tmp/cq4-implementation/process-q1-user-answer.json
```

This launches new real-model attempts and uses the actual retained reply. Full process assessment, standalone review, follow-up/reopening, defect investigation, upstream preparation, matched usage repetitions and human acceptance remain open.

Astra independently verified the final source manifests, replay hashes, Human answer provenance and retained bytes, both oracle observations, exact current integration acknowledgements, clean handoff and usage totals. It approved this scoped increment for commit. It explicitly retained the applied ChangesRequested plan and subsequent governor edits as a process-quality gap; this approval does not close planning/application assessment or M4.

## Independent process assessment runner

`./dev/process-assess INTEGRATED_EVIDENCE_DIRECTORY` implements the [approved assessment sequence](../design/live-process-evaluations.md#integrated-go-assessment-and-standalone-review): replay the actual checkpoint and incorporation evidence, restore the database, check out the incorporated candidate, obtain one Pi Audit on current task revisions, then invoke the standalone `review --mode audit` workflow with that exact admitted subject and native Codex/Astra. A completed non-accepted Pi result is retained as a review subject; it is not relabeled accepted.

The `--correct PRIOR_ASSESSMENT_DIRECTORY` continuation restores the latest standalone database, runs an ordinary Handoff-only planning correction, then repeats both audits. Predicates bind the Codex Planner/Pi Plan reviewer hierarchy, exact accepted replacement, native review-before-application ordering, sole Handoff acknowledgement and unchanged other records/history/Git. Native Astra workspace reads must successfully cover every tracked candidate file at the exact commit; paginated Unicode text is checked against Git contents. Continued archives replay these checks and include the preceding failed/non-accepted attempts in cumulative accounting. A file inspection proves delivery of exact file contents to the reviewer, not the correctness of its reasoning.

The workspace-discovery failure and stale Handoff are retained in `process-handoff-correction-reproductions/before.json`. Source review exposed a continuation verification gap: a fabricated `inspectionComplete:true` was accepted without replaying native reads. `replay-before.log` captures that failure; `replay-after.log` rejects it after coverage and saved-outcome binding. Nine focused assessment tests pass. Astra approved the correction design and final source for native execution after the fast gate.

The static [rubric](../../dev/process-assessment.md), original specification, full record history, actual supplied answer, integration observations, planning/application identities and hashed artifact index form a bounded, immutable Input artifact. It is published through the ordinary operator API with explicit runner-generated provenance. It is not a user judgment or new host validation. Both reviewers must receive the exact bundle directly; the previous result alone does not recursively supply it. Large original result bodies remain available through their stored handles.

Predicates check exact request/input/member/guidance identities, workspace base, Audit-only execution, actual standalone workflow/subject, unchanged graph/history, detached HEAD and integration ref, no remaining claims/integration holds, observed usage and distinct attempt identities across the whole restored history. Baseline and both assessment sessions reconcile through the ordinary operational audit. Each stage retains its own native files, database dump and verdict; execution success and acceptance are separate fields.

The bundle is 97,416 UTF-8 bytes for the current baseline. Before each stage, the runner measures the full encoded input context, including the escaped bundle and prior Pi body, and reserves the host's entire 16 KiB request allowance against the 192 KiB input limit. Five focused tests pass. Astra's integration-ref observation reproduced as false acceptance before correction (`process-assessment-reproductions/before.log`); `after.log` passes. Astra approved source execution after the fast gate.

### First native assessment: correction required

`20260927T204550-fast` passes 188 Scala scenarios, eleven process tests, five assessment tests and the existing bridge/evaluator checks. `20260927T204644-process-assess` matches it on all 232 non-documentation files (`process-assessment-source-verification.json` in the fast evidence directory). Both native stages execute and archive successfully; the final quality status is **assessment-not-accepted**.

| Stage | Native reviewer | Elapsed | Quality verdict | Known reported tokens |
| --- | --- | --- | --- | --- |
| Precheck | Pi / gpt-5.5, attempt `a7affbf6-a08c-437d-b698-681e7131093b` | 123.129 s | Both tasks Accepted, with a stale-Handoff caveat | 831,371 |
| Standalone review | Codex / gpt-6-astra, attempt `f005cb68-17ed-4f0b-bd1f-8eee0390c3ca` | 107.150 s | Both tasks ChangesRequested | 795,155 |

Pi result `f0d52cf9-8b95-33cb-ab67-06052ffcddc8` is the exact standalone subject. Astra result `308ebad1-a928-32b6-abb2-d45565e6e4bb` rejects the stale Handoff: Handoff 1@3 still lists linking and integration as remaining although both are recorded as complete. Astra explicitly distinguishes assessment scope from mutation authority; Pi's narrower task verdict does not close this shared process defect. Correction must update the handoff with observed incorporation/validation and retain only genuinely outstanding work, preserving the earlier planning deviations.

Astra also reports that it could not inspect candidate files with the tools it found. Its transcript contains CQ reads and empty MCP resource discovery, with no local `workspace` call. This is an unresolved inspection gap in this run, not proof that the adapter lacks workspace access: the configured Reviewer profile exposes that tool, and both earlier native Astra cohort Audits recorded successful `cq_host.workspace` calls. The next correction must make the inspection path explicit and retain actual file-read evidence.

Both stages received the exact same bundle and frozen candidate `9cc3f9a999882735a635d7d14c2d2c653afcbced`; encoded input upper bounds are 145,989 and 150,403 bytes respectively. Current graph/history, detached HEAD, integration ref and claim-free handoff remain unchanged. There are no Worker or integration attempts in either assessment session. The combined audit reconciles baseline 6,516,662 plus both new sessions to **8,143,188** known tokens across 18 distinct attempts. All meters remain partial, with no absent meter; estimates/unknown costs retain their separate price bases. Neither the rejected assessment nor its usage is discarded.

Each stage's `measure-parent-traffic.py` derives six dispatch calls from its hashed governing transcript. Precheck arguments/replies peak at 1,570/693 bytes; standalone at 1,450/1,851 bytes. The precheck governor reads one 1,916-byte Selection artifact; neither governor reads a Result body. The large evidence bundle stays behind its handle. These byte observations use the earlier stated exclusions and do not establish end-to-end token savings.

Astra independently approved this scoped runner and negative-evidence increment for commit after verifying source/gate matches, both verdicts, input bounds, unchanged record/Git state, handoff and accounting. That approval retains Handoff correction and independent file inspection as open work; it does not accept the process outcome or M4.

### Reviewed Handoff correction and complete candidate inspection

`20260927T211119-fast` passes 188 Scala scenarios, eleven process tests, nine assessment tests and the existing bridge/evaluator checks. `20260927T211207-process-assess` matches all 232 runtime/build/evaluator sources, including prompt resources (`process-correction-source-verification.json` in the fast directory). It restores the preceding standalone database, retaining all 18 previous attempts.

| Stage | Elapsed | Outcome | Known reported tokens |
| --- | --- | --- | --- |
| Handoff correction | 145.504 s | Codex Planner proposal independently accepted by Pi and applied | 794,739 |
| Pi Audit | 104.787 s | Both tasks Accepted | 688,851 |
| Standalone Astra Audit | 108.893 s | T1 ChangesRequested; T2 Blocked | 739,400 |

The correction applies proposal `00bf63f1-ca36-339c-b282-3266fbfdc271` after observing Pi's Accepted review `bedd33c8-d3af-3fec-a9ef-9f78392f6c43`. Native application acknowledgement `9505ae22-520e-3f57-9ee7-752578c402f0`, cursor 14, changes only Handoff 1 from revision 3 to 4. It records observed integration, attributes earlier validation and leaves independent inspection/follow-up pending. Every earlier history entry, other record, relationship, task revision, Git HEAD and integration ref is preserved. The new session releases its claims and creates no integration.

Pi result `1bad2418-3425-3b26-80fd-3fa3c99ff7ab` becomes the exact standalone subject. Astra attempt `1dcedf43-4e65-4378-954b-38531bbbbb88` now successfully reads all seven tracked files at `9cc3f9a999882735a635d7d14c2d2c653afcbced`, including source, tests, README, module and `.cq-evaluation/answer.json`. `workspace-inspection.json` verifies complete returned ranges and exact text against Git contents. Seven rejected 18,000-code-point requests remain in the transcript, followed by successful requests at the supported 8,192 limit. The 102,274-byte bundle and both encoded input bounds remain below the host limits.

Astra result `5ff8b5f3-087b-3579-8d36-23c9d22c47d8` confirms the Handoff correction and finds two additional gaps:

- T1's required automated empty-input case is absent: `TestRunEmptyInputProducesNoRecords` actually supplies `123 !!!`. The consumer behaves correctly for genuinely empty input, and the external oracle tests it, but the specified Go test case is missing.
- T2 remains blocked on evidence presentation: current answer contents agree with the recorded Go choice, but the assessment bundle does not provide original/candidate blob identities or an attributable byte comparison. The runner's existing byte equality assertion is not evidence the reviewer received.

The next correction will use ordinary reopening and fresh exact-revision authority for T1, retain T2's current identity, and present the original/candidate answer comparison explicitly. A mutation reproduction already confirms the missing-test finding: `process-handoff-correction-reproductions/empty-test.py` leaves the original candidate untouched, runs its passing Go suite, then inserts an empty-input-only failure into a separate copy. The mutated command fails on empty input while the existing Go suite still passes; `empty-test-before.log` fails for that precise coverage gap. No consumer correction is claimed yet.

All three new sessions archive normally. The cumulative audit reconciles **10,366,178** known tokens across **25 distinct attempts**, all partial meters, none absent. It includes the preceding rejected assessments once. Full retained-assessment replay passes, including successful native file coverage, exact correction hierarchy/application and cumulative accounting. No complete billing or efficiency claim is made.

Each stage retains `measure-parent-traffic.py` and `parent-traffic.json`. Correction/precheck/standalone dispatch counts are 8/5/6; maximum argument/reply text sizes are 1,481/1,816, 1,570/693 and 1,450/1,851 bytes. The correction governor explicitly retrieves the entire 2,842-byte Plan review in two pages. The precheck reads one Selection artifact; neither new Audit governor reads a full Result body. These measurements retain actual drill-downs and exclude native envelopes and model tokenization.

Run this continuation with:

```sh
./dev/process-assess \
  /srv/nvme/tmp/cq4-implementation/20260927T201449-process-resume \
  --correct /srv/nvme/tmp/cq4-implementation/20260927T204644-process-assess
```

The final status remains **assessment-not-accepted**. Independent Astra reviewed the matching sources, sole Handoff acknowledgement, unchanged Git, claim-free handoff, seven-file inspection and accounting, and approved the correction-runner/evidence increment for commit. That approval does not close the new test/evidence findings, full process acceptance or M4.
