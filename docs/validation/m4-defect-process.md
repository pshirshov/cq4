# M4 synthetic dependency investigation

This evaluation exercises the defect/research/hypothesis and upstream workflow alongside the [accepted Go worked process](m4-worked-process.md). The component is explicitly synthetic and local; there is no external upstream submission endpoint. Native intake, exact probe execution, research and archived checkpoint replay now pass. Independent Astra approved the scoped source and evidence; rejected experiments remain explicit. Adjudication, repair, upstream preparation and full process acceptance are pending.

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

## Empirical continuation

The runner now restores a passed checkpoint into a fresh database/session and supports two separate `advance --through explore` stages. Probe assigns H2/H3 together to Codex Worker/Probe with D1/H1/R1 as guidance. Research assigns R1 to Pi Explorer/Research with all other records as guidance, the prior Probe result handle and verified native observations. Both must preserve every record/history revision and the fixture Git state, release claims and reconcile cumulative usage without reusing attempt identities.

Probe receives an evaluator-supplied portable recorder through an immutable Input artifact. It runs the predeclared reproduction, raw token selection, valid-token normalization/aggregation, nonconforming-token injection and CLI experiments. The checker binds the exact program bytes to a completed native Codex command, its exit/output and assigned workspace, plus fixture commit/file hashes before and after execution. A fixed bootstrap reads the retained recorder, checks its SHA-256 against the Input and source manifest, then compiles and executes that same byte buffer. CQ Collector transcript manifests and base64 parts must decode to the retained native payload bytes. This proves execution of the supplied experiments; it does not prove autonomous experimental design or the model's causal interpretation.

Research starts only after that execution proof passes. Its Input contains the exact verified observations and native transcript/Probe handles. The frozen child input must contain all assigned records and guidance. Findings must cite the observation input and remain ModelDeclared. Runner publication through the Human/operator artifact API is explicitly labeled as runner input, not user judgment or new HostObserved validation. Ledger adjudication and repair remain later reviewed stages.

The first continuation preflight, `20260927T230844-defect-probe`, stopped before any model launch: exact snapshot equality rejected a changed claim-preview digest. The retained snapshots differ only in that digest. `PreviewDigest` includes the requesting actor, so a fresh session correctly changes it. `restore-check-before.log` reproduces the same false rejection in a deterministic test. The correction excludes only that actor-bound digest; current views, complete histories, claim cursor/members/claims/integrations and Git/clean/local integration state still require equality, both at restoration and archived replay. Negative tests reject changes to each of those fields. No product digest or claim rule changed.

The corrected fast gate, `.work/evidence/20260927T231343-fast`, passes 189 Scala scenarios, twelve intake/research/restore tests, six native observation tests and existing evaluator/bridge checks. Independent Astra verified the actual differing snapshots and scoped correction.

Native probe `20260927T231410-defect-probe` then completed in 77.329 seconds but failed the exact-program predicate: Codex removed three blank lines while copying the supplied inline source. `rejected-probe-analysis.json` preserves both digests and the exact diff. Its output also contains a Git filesystem-monitor diagnostic inherited from the host configuration. The rejected archive remains unchanged, including two attempts, 388,577 known tokens, two partial meters and zero absent meters. Session/evaluation accounting reconciles before the semantic rejection.

The first correction transported the recorder as encoded bytes and verified the complete fixed bootstrap command; altered bootstrap or decoded program bytes remained rejected. The recorder captures all four Git metadata commands' argv, exit status, stdout and stderr in explicit diagnostics, and a nonzero exit fails execution. A failing diagnostic regression is retained at `231410/git-diagnostics-before.log`. The fast gate, `.work/evidence/20260927T231820-fast`, passes 189 Scala scenarios, twelve evidence tests, seven observation tests and the existing checks. Independent Astra verified all 248 source hashes and approved that correction.

Native retry `20260927T231856-defect-probe` completed in 92.847 seconds but also failed the intact exact-command predicate: Codex wrote `cq-probe.py` in its workspace and executed that copy, leaving it untracked. The archive retains two attempts and 464,201 known tokens, two partial meters and zero absent meters, with reconciled accounting. The replacement transport supplies only a retained file path, digest and short hash-checking command, avoiding a large program for the model to copy. Tests reject unavailable/altered recorder bytes before execution, altered bootstrap/path/digest, failed Git commands and fabricated observations. Final fast `.work/evidence/20260927T232213-fast` passes 189 Scala scenarios, twelve evidence tests and eight observation tests. Astra verified all 248 current non-documentation source hashes and approved this correction.

## Passed probe checkpoint

`20260927T232300-defect-probe` passes in 76.485 seconds. The actual Codex child can read the retained recorder under its sandbox and executes the exact hash-checking bootstrap. Its result is `96c82601-0ae1-3ddc-84bf-e8e91afaba78`, with Collector stdout manifest `a09ee603-dda4-3987-992a-a29cfcbb40c3`. The database digest is `4c5cb0854fc773d264142fb46e6efb5b9a678c3c98d68711b7e9d6a8a00316c1`.

- The original four-test reproduction still has exactly one ASCII-delimiter failure. Raw selection returns `['a_b', '12', 'café']`. Injecting `['Z', 'z', 'A']` produces `[('a', 1), ('z', 2)]`; injecting the nonconforming tokens preserves those count keys. The CLI reproduces the same mismatch with exit 0. Every probe retains argv, input, exit, stdout and stderr.
- Both fixture snapshots match the original four tracked files and commit. The only untracked file is `cq-probe-observations.json`, exactly matching the native JSON output. Two successful Git commands retain their filesystem-monitor diagnostics explicitly.
- ModelDeclared findings distinguish H2's observed selection behavior from H3's conditional downstream propagation; they do not establish an independent requirement for downstream validation. No ledger adjudication, repair or integration occurs. Every view/history revision and the clean consumer Git state remain unchanged; claims are released.
- The two new attempts reconcile to 336,936 known tokens, two partial meters and zero absent meters. Parent traffic has four dispatch calls, 943/1,436-byte maximum arguments/replies and no artifact-body reads. These are measured traffic bounds, not matched efficiency evidence.

## Passed research checkpoint

`20260927T232453-defect-research` passes in 82.233 seconds. Pi Explorer/Research receives R1@1, the four other current records as guidance, the exact verified observation JSON and the prior Probe result. Research result `b06a7a80-2f7b-3a49-b479-07dec13eb09d` cites observation Input `f3bbf542-e702-4fcd-a9ac-64d74e4723a7` and the Probe result. Its ModelDeclared interpretation localizes the observed mismatch to selection for the tested input, preserves the conditional nature of downstream propagation and states the limits of the experiments. No causal status is applied to the ledger yet.

The database digest is `7290d20687ece37371734faf86c35aeb12d7fc47da1a22344aca4d294425d7af`. Git, records and complete history remain unchanged, and claims are released. The two new attempts reconcile to 283,623 known tokens, two partial meters and zero absent meters. Parent traffic has four dispatch calls, 1,029/1,367-byte maximum arguments/replies and no artifact-body reads.

Full `checkpoint()` replay follows research → probe → intake, verifies all proofs/dump bindings and reconciles twelve distinct attempts to 3,212,612 known tokens. Fast `232213`, probe `232300` and research `232453` match all 248 runtime/build/evaluator sources, including prompt/workflow Markdown; this count excludes `docs/**` and `README.md`.

`232453/empirical-experiments.json` derives a separate experiment report from each original operational session summary exactly once. Across both intake experiments, two rejected probes, the passed probe and research, it retains 24 distinct attempts and 6,969,723 known tokens, 24 partial meters and zero absent meters. The two preflights launched no models. Missing optional counters/prices and the original rejected intake's unreconciled cross-filter totals remain explicit. These runs changed instructions/transports and are not matched repetitions or an efficiency comparison.

Independent Astra replayed the complete chain, inspected the exact bootstrap, successful Git diagnostics, native observations, Collector bytes and Pi interpretation, verified source hashes and retained failures, and approved this scoped empirical increment with no blocking or major finding. Reviewed adjudication/repair, upstream/operator evidence and independent full-process assessment remain open.

## Run

From the repository root:

```sh
./dev/check fast
./dev/defect-eval begin
./dev/defect-eval probe --checkpoint /path/to/passed-defect-begin
./dev/defect-eval research --checkpoint /path/to/passed-defect-probe
```

On this host, while its default Claude differs from the pin, the exact invocation is:

```sh
PATH=/nix/store/ixiaaa3h8m705sp4ihivdi3014naxrxn-claude-code-2.1.280/bin:$PATH ./dev/defect-eval begin
```

The evaluator enters the pinned Nix environment when required, uses the configured native harnesses, starts an isolated PostgreSQL instance and retains evidence under `/srv/nvme/tmp/cq4-implementation`. It archives the operational audit and database; it does not add another accounting system. A passed intake is not full M4 or human acceptance.
