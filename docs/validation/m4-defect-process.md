# M4 synthetic dependency investigation

This evaluation exercises the defect/research/hypothesis and upstream workflow alongside the [accepted Go worked process](m4-worked-process.md). The component is explicitly synthetic and local; there is no external upstream submission endpoint. Independent Astra approved the staged design and instruction correction. The corrected native intake and archived checkpoint replay now pass; the first rejected intake remains explicit evidence. Full process acceptance is pending.

## Reproduction and stages

The committed fixture in `dev/fixtures/defect` specifies maximal ASCII `[A-Za-z]+` tokens, lowercase normalization, counts sorted by word, UTF-8 stdin and silent empty input. Before any repair, `python3 -m unittest -v` fails exactly its ASCII-delimiter case: `a_b 12 café` produces `12`, `a_b`, `café` instead of `a`, `b`, `caf`. The separate 26-case `dev/defect-oracle.py` also fails that case. Each intake run archives the exact fixture commit, test commands, outputs, exit codes and oracle digest before invoking a harness.

1. **Begin:** capture an Open Defect, obtain native investigation, then independently reviewed proposals for empirical Research and a branching tree of Proposed Hypotheses. Retain all historical states; no adjudication or claimed probe observations yet. Release claims and preserve Git.
2. **Explore:** execute discriminating Worker/Probe commands and Explorer/Research on explicit roots. Preserve actual native tool inputs, exit status, stdout/stderr and fixture commit, distinguishing these observations from model conclusions and Collector publication provenance.
3. **Plan:** independently review evidence-based adjudications and the Defect-derived fix Goal, Task and Milestone. Allow honest inconclusive findings.
4. **Integrate:** implement, independently review with a fresh unchanged oracle, and obtain Recorded integration.
5. **Upstream:** prepare a report for the exact synthetic component/version, with a Requested OperatorAction, Handoff and applicable Memory. Local resolution is distinct from unperformed external publication; no invented authorization or confirmation.
6. **Assess:** independent native Astra inspection of current files, complete history, probe observations, repair and operator boundaries.

Passed checkpoints support later continuation without repeating prior model work. Failed attempts, database snapshots and usage remain evidence. Until a stage is implemented and verified, its presence in this list is a plan.

## Intake checker verification

`dev/defect-evidence-check.py` exercises the proposed tree, complete history, premature or erased adjudication, distinct claims, accepted-review observation before application, multi-record acknowledgements and replay consistency. The runner also checks configured native roles, parent/child identities, operational assignments, settled jobs, delivered usage, unchanged Git and claim-free handoff.

Astra identified two false-acceptance paths. Both failed before correction in `/srv/nvme/tmp/cq4-implementation/defect-fixture-preparation/checker-before.log`:

- A child linked to both its parent and a sibling could form a cycle. Each branch now has exactly one Hypothesis parent.
- A direct post-creation edit could replace reviewed content. Every non-Defect history revision now belongs to an accepted, acknowledged proposal application.

All ten deterministic intake tests pass. The initial intake-checker fast gate, `.work/evidence/20260927T222843-fast`, passed before the first native experiment. Astra approved that narrow corrected source; the later instruction correction and final evidence are recorded below.

The first native preflight, `20260927T222913-defect-begin`, stopped before invoking a model: the host profile had moved to Claude 2.1.283 while the evaluator pins 2.1.280. Its fixture reproduction and failure remain archived. The retry explicitly selects the retained 2.1.280 Nix binary; there is no evaluator version relaxation. The fast/native manifest comparison covers 245 runtime/build/evaluator sources with no differences.

## Native proposal failure and correction

`20260927T223041-defect-begin` ran for 476.639 seconds and was rejected by the intact checker. The governor created D1, R1 and H1–H4, then received an independently accepted proposal to add three child-to-H1 relationships. Application failed with `An item may be changed only once in a batch`: every Reference changes both endpoints. The governor then read the full 2,765-byte proposal result and reconstructed three direct changes. Six resulting non-Defect revisions bypassed reviewed application.

This is the documented mutation boundary, not a transaction regression. Astra independently confirmed it. Planner and Plan Reviewer instructions now explain inverse-endpoint effects and reject overlapping existing endpoints. The governing workflow requires fresh planning/review after proposal failure. The evaluation request uses two valid rounds: one Produce from D1 for R1/H1, then one Produce from the allocated H1 for its children. No mutation contract or checkpoint predicate was relaxed.

The final correction fast gate, `.work/evidence/20260927T224122-fast`, passes 189 Scala scenarios, ten intake predicates and the existing evaluator/bridge checks. The affected native retry passes; other retained harness routes are reused.

The failed experiment remains under `/srv/nvme/tmp/cq4-implementation/20260927T223041-defect-begin`, including complete database/audit archives, `failed-intake-analysis.json` and `parent-traffic.json`. It has eight attempts and 2,904,333 known tokens, eight partial meters and zero absent meters. The hierarchy and actual proposed tree replay successfully; Git is unchanged and claims are released. Because the process predicate failed first, the runner did not reach cross-filter accounting reconciliation; the stated totals come from its archived operational session summary. Traffic measurement records 28 dispatch calls with 973/1,469-byte argument/reply maxima and the explicit full-result drill-down; these byte counts exclude native envelopes and tokenization.

## Passed intake checkpoint

`20260927T224218-defect-begin` passes in 490.494 seconds against all 245 matching runtime/build/evaluator sources. Its exact fixture commit is `e1144c242fd3a25737a843d2fd4004553e3f457f`. Archived `checkpoint()` replay verifies the same proof, accounting summaries and database digest.

- The final records are Open D1@3, Open R1@1, Proposed H1@2 and Proposed H2/H3@1. R1 and H1 derive from D1; each branch derives from H1. All historical hypothesis states remain Proposed without adjudication or claimed probe evidence. No Task, Goal, Milestone, Question or candidate was created.
- Proposal `1c66668f-76c4-329d-88e5-5967cc2cc4a9` produces R1/H1 from D1. Pi review `dc13bde5-9ea7-3397-92ea-b7f6e9f9a0f8` is observed Accepted before application. The acknowledgement at cursor 3 contains R1@1, H1@1 and D1@3.
- Proposal `2b89a079-f8c6-34b9-aeee-7a02bf733ec6` produces H2/H3 from H1. Pi review `6a295411-1b06-3947-85c2-8601fe2cf538` is observed Accepted before application. The acknowledgement at cursor 4 contains H2/H3@1 and H1@2. Every non-Defect revision belongs to these reviewed applications.
- An earlier proposal suggested a premature fix Task and received an Accepted Plan review. The governor identified the scope mismatch, did not apply it, clarified D1's intake context and obtained fresh planning/review. Those attempts remain in the evidence and accounting; the unused review does not establish adherence to the intake request.
- The claim-free handoff, unchanged clean Git tree, exact parent/child roles and native job settlement pass. The governing session uses Claude, investigation/planning uses Codex and Plan review uses Pi. Eight attempts reconcile between session and evaluation summaries: 2,592,053 known tokens, eight partial meters, zero absent meters. Missing optional counters/prices remain missing.
- Parent traffic contains 32 dispatch calls, 636/971-byte maximum compact arguments/replies and no artifact-body reads. This is a measured traffic observation, not an end-to-end efficiency improvement.

Both model experiments together retain sixteen distinct attempts and 5,496,386 known tokens. They use different instruction revisions and are not matched repetitions. Empirical probes, reviewed adjudication/repair, upstream/operator boundaries, independent process assessment and full M4 acceptance remain open.

Independent Astra verified the final 245 source hashes, fast result, full checkpoint replay, exact review/ACK ordering, claim/Git invariants, dump digest, usage totals and retained failed experiment. It approved this scoped intake checkpoint and instruction correction for commit with no blocking or major finding.

## Run

From the repository root:

```sh
./dev/check fast
./dev/defect-eval begin
```

On this host, while its default Claude differs from the pin, the exact invocation is:

```sh
PATH=/nix/store/ixiaaa3h8m705sp4ihivdi3014naxrxn-claude-code-2.1.280/bin:$PATH ./dev/defect-eval begin
```

The evaluator enters the pinned Nix environment when required, uses the configured native harnesses, starts an isolated PostgreSQL instance and retains evidence under `/srv/nvme/tmp/cq4-implementation`. It archives the operational audit and database; it does not add another accounting system. A passed intake is not full M4 or human acceptance.
