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
