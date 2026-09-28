# M4 synthetic dependency investigation

This evaluation exercises the defect/research/hypothesis and upstream workflow alongside the [accepted Go worked process](m4-worked-process.md). The component is explicitly synthetic and local; there is no external upstream submission endpoint. Native intake, exact probe execution, research, reviewed adjudication/repair planning, repair integration and upstream preparation/local closeout now pass retained verification. Independent Astra approved the source and retained evidence through upstream preparation. Rejected experiments and corrected checker failures remain explicit. Independent whole-process assessment and full process acceptance are pending.

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

## Reviewed adjudication and repair planning

The plan stage restores the research checkpoint and invokes ordinary `advance --through plan` on D1. It permits Codex Planners and independent Pi Plan reviewers, with no Worker or integration. Every child receives the exact frozen investigation, verified observations, immutable observation Input, Probe/Research results and stage instructions. All new revisions, including D1's producer revisions, must belong to stored proposals with an observed Accepted review before an acknowledged application.

The target graph adds one fix Goal derived from D1, one Task and Milestone derived from the Goal, and the Task's PartOf relationship to the Milestone. D1 and the Goal remain outside milestone ownership. D1/Goal/Milestone remain Open and the Task Ready; original reproduction/specification and all prior history remain intact. R1's conclusion and hypothesis adjudications cite the observed evidence with ModelDeclared provenance. Semantic correctness remains subject to independent review, rather than matching required prose in a deterministic checker.

Astra inspected the original R1 question and H3 claim: normalization was a pre-probe alternative in R1, while H3 describes conditional propagation. The reviewed conclusion must distinguish the observed selection cause from the rejected proposition that normalization introduced the offending characters in this reproduced case. H3 must not be relabeled Refuted to manufacture an alternative; this example does not claim coverage of a Refuted Hypothesis transition.

During source review Astra reproduced a false-acceptance path: an intermediate revision could replace a hypothesis claim, refute that substitute, then restore the original. `defect-plan-preparation/history-before.log` captures four failing variants before correction. The checker now preserves the original hypothesis claims/tree, Research question and Defect observed/expected/reproduction/severity across every appended revision, including intermediate states. The corrected initial fast gate, `.work/evidence/20260927T233814-fast`, passes 189 Scala scenarios and six plan tests; Astra approved targeted native execution.

### Native execution and citation-checker correction

`20260927T233851-defect-plan` completed in 473.807 seconds with five reviewed applications, then failed the original checker with `Adjudication omitted verified evidence handles`. Its manifest and native source snapshot remain unchanged. R1/H1/H2/H3 cite the current plan Input `01d0c875-26b0-4b4b-953f-6a4e5ca25259` plus the exact Probe and Research results. That Input contains the same verified observation JSON and transcript identity as the prior observation Input, while the checker required the prior Input ID specifically. Astra independently confirmed this false rejection.

`233851/citation-before.log` reproduces the valid-current-Input rejection before correction. The checker now accepts either observation Input only after verifying the current publication/body/hash, exact retained observations/transcript and every child's materialized context; the Probe and Research result citations remain mandatory. The final fast gate, `.work/evidence/20260927T235216-fast`, passes 189 Scala scenarios, twelve intake/research tests, eight probe tests and nine plan/re-verification tests.

`verify-plan` replays the complete archived proof and accounting before writing `233851/plan-verification.json`. It is restricted to this exact citation failure with successful native exit, complete archives and reconciled accounting. The derived verification binds 70 original evidence files and 41 verifier source files; continuation checks those inputs and recomputes the full proof. It preserves the original failed `result.json` and records corrected acceptance separately. No model execution was repeated for this evaluator defect.

- The five accepted applications adjudicate R1/H1, adjudicate H2/H3, produce G1 from D1, produce M1/T1 from G1, then attach T1 PartOf M1. All eleven appended revisions have accepted-review-before-ACK provenance. The eight prior revisions remain unchanged.
- Current records are D1@4 Open, G1@2 Open, H1@3/H2@2/H3@2 Supported, M1@2 Open, R1@2 Concluded and T1@2 Ready. R1 explicitly rejects normalization introducing the offending characters in the reproduced case. H3 remains conditionally supported downstream preservation, with the original claim intact. The Goal and Task retain the original ASCII behavior, failing-test reproduction, regression coverage and unchanged external-oracle requirement.
- Git remains at `e1144c242fd3a25737a843d2fd4004553e3f457f`, clean and without an implementation candidate. Claims are released. The database digest is `9d353e054c890995a683c14b0d273bac0908f7ebbda6dba3b23568e3b8aa0152`.
- Eleven new attempts reconcile to 3,568,353 known tokens, eleven partial meters and zero absent meters. Full retained replay covers 23 distinct chain attempts and 6,780,965 known tokens. This stage is counted once despite its raw checker failure and separate re-verification. Including the previously retained rejected experiments gives 35 distinct attempts and 10,538,076 known tokens; these are not matched repetitions.
- Parent traffic retains 46 dispatch calls with 1,290/2,351-byte argument/reply maxima, five compact Proposal previews, no artifact-body reads, and one rejected claim-duration request. Byte counts exclude native envelopes and tokenization.

The native run matches all 251 sources in its initial fast gate. Final corrected fast verification matches 252 sources; `233851/verification-source-comparison.json` identifies four changed evaluator/checker/test files and the added replay helper. Product code, prompts and stage instructions are unchanged. Independent Astra verified these exact differences, all bound evidence, complete replay, actual conclusions and review/application ordering, and approved the scoped increment with no blocking or major finding. Implementation, integration, upstream/operator evidence and independent full-process assessment remain open.

## Integration continuation preparation

`integrate` restores the verified repair plan into a fresh database and consumer clone. It creates an unoccupied integration branch at the fixture commit, repeats the original failing reproduction before model work, then runs ordinary `advance --roots T1 --through integrate`. Pi implements; Codex independently reviews and executes a fresh unchanged `defect-oracle` check. Optional Codex Planner work is limited to selector assessment. The input supplies the reviewed investigation as guidance without requiring the governor to read or compose child prompts.

Acceptance binds the exact task assignment, candidate, worker/reviewer result handles, host checks, Recorded integration, acknowledgement and history cursor. Only T1 may change Ready→Done; every other record/history and the task contract remain unchanged. The checkout remains at the original fixture while `refs/heads/integration` advances. Claims must be released and all attempts must reconcile through the existing audit.

Astra reproduced a checker gap: adding a class-level `unittest.skip` decorator left the original method ASTs unchanged and could pass the oracle's test-process exit check. The fail-first regression is retained at `/srv/nvme/tmp/cq4-implementation/defect-integration-preparation/skip-before.log`. The corrected checker preserves the original test module byte-for-byte, requires additional tests in a separate file, and records a direct run of the four original tests in a fresh clone of the actual incorporated candidate. All four must report `ok`, with no skips. The oracle itself is unchanged. Five focused checks pass, including actual Git scope/preservation, stale revisions, altered history, acknowledgement mismatch and inherited reviewer validation. The source correction passed independent Astra review before native execution; final source/evidence approval is recorded below.

## Passed native repair integration

`20260928T001028-defect-integrate` passes in 153.742 seconds. Final fast verification `.work/evidence/20260928T000953-fast` passes 189 Scala scenarios, five integration checks and the existing evaluator checks. All 255 runtime/build/evaluator sources match the native run; docs and README are excluded from this comparison, while runtime prompt/workflow Markdown is included.

- Pi Worker `e5bd4b16-6ff0-499c-b41a-b59898947dcc` produces candidate `46694494a847f4d64587ff84c3ef3058d66796de`; Codex Reviewer `1b9aef85-1533-4382-8ff7-98b70d30e20c` accepts that exact candidate and assignment. Their separate HostObserved `defect-oracle` validations pass the unchanged 26-case oracle and consumer suite. The repair changes only `synthetic_tokens.py` and adds `test_synthetic_tokens_regression.py`: the helper selects `[A-Za-z]+`; regression cases exercise the reported digits/underscore/non-ASCII boundaries and punctuation boundaries. README, CLI and original tests remain unchanged. The separate `original-test-execution.json` records all four original tests executing successfully at the candidate.
- Integration `7a9e4c10-2b6d-4f3e-9c88-000000000001` is Recorded with that candidate as the observed target. The exact acknowledgement at cursor 10 changes only T1@2 to Done@3. D1, G1 and M1 remain Open; Research and Hypothesis conclusions/history are unchanged. The host completion preserves the original Task contract and cites both result handles. Claims are released; the original checkout remains clean at the fixture commit. The database SHA-256 is `5d9305cc5160534e87fbc0b6f0cda2087b9d0c14b3ddde73bbc10246f3187ced`.
- Three new attempts reconcile through the operational audit to 1,296,719 known tokens: 476,949 directly attributed to T1, plus 819,770 governing overhead. All three meters are partial and none absent; missing optional counters/prices remain explicit. Full checkpoint replay retains 26 distinct attempts and 8,077,684 known tokens. Including earlier rejected experiments, `integration-experiments.json` retains 38 attempts and 11,834,795 known tokens, counting every original session once. These differing configurations are not matched repetitions.
- `parent-traffic.json` records twelve dispatch calls with 1,399/1,734-byte argument/reply maxima. The governor explicitly reads one Candidate Reviewer result through ArtifactText (limit 2,400); this drill-down is retained separately from compact dispatch traffic. One claim request exceeds the documented duration limit and is rejected before a corrected request. No token-savings claim follows from these payload measurements.

Independent Astra matched all 255 current sources against the fast/native manifests, replayed the full 26-attempt chain, inspected the actual candidate and meaningful added regressions, and validated the exact Recorded identity, fresh checks and original-test execution. The new regressions exercise `tokens()` and `counts()`; CLI coverage comes from the original CLI test and unchanged external oracle. It approved this scoped commit with no blocking or major finding. This establishes the repair/integration stage; upstream/operator work, reviewed closeout, independent whole-process assessment and M4 acceptance remain open.

## Upstream preparation and local closeout

The new `upstream` continuation checks out the exact incorporated candidate in a fresh clone, verifies its tests and unchanged external oracle, and restores all ledger/history/claim state. The prior session's local integration projection remains archived; the new session begins with no local effect. It uses ordinary `upstream --roots D1 --action prepare`, with Claude governor, Codex Planner and independent Pi Plan Reviewer. Every mutation requires an accepted stored proposal application and matching history acknowledgement.

Astra reviewed the semantic boundary before implementation: G1's original acceptance/scope and M1's objective concern the local repair. D1 may therefore become Resolved, G1 Achieved and M1 Complete based on the exact Recorded candidate and checks. Four preparation records derive from G1 without joining that completed milestone: Upstream Identified with the exact synthetic component/defective fixture, no report URL/outcome; OperatorAction Requested without confirmation/observed evidence; Handoff Open with the absent endpoint/authorization explicit; and Memory Current with concrete applicability and ModelDeclared evidence. Task, Research and Hypothesis records remain unchanged, including transient history. No external publication is authorized or claimed.

Four focused checks reject invented confirmations/reports, changed fixture versions/contracts, wrong evidence provenance, transient false claims and investigation rewrites. They also verify that continuation changes only the checked-out candidate and session-local integration projection. Fast gate `.work/evidence/20260928T002409-fast` passes 189 Scala scenarios and all evaluator checks. Astra approved the source for targeted native execution; native results and final prose/evidence review remain pending.

### Rejected first upstream run

`20260928T002444-defect-upstream` exits normally after 506.424 seconds, but fails the intact guidance predicate. All eight child requests explicitly supply empty guidance; the host retains those requests accurately. The governor initially used all original records as roots, then continued with empty guidance rather than supplying the required current nonmember references. Separate retained graph inspection also rejects a paraphrased Upstream reproduction field. Four proposal applications have accepted reviews and matching acknowledgements; local Git is unchanged and claims are released, but this does not establish an accepted preparation checkpoint.

The full database and audit remain archived, with nine attempts reconciling to 3,813,334 known tokens, nine partial meters and no absent meters. `guidance-failure-analysis.json` preserves the diagnosis and individual subchecks without reclassifying the failed manifest. Astra independently confirmed both failures and approved clarifying the evaluator instructions: four singleton rounds (D1, G1, M1, G1 Produce), all seven other original records as current guidance for each Planner and Reviewer, and literal copying of D1's reproduction field. Children must report missing guidance as a blocker. Predicates remain strict; the retry starts from the passed integration archive rather than reusing the rejected preparation state.

### Passed upstream retry

`20260928T003727-defect-upstream` passes in 445.677 seconds. Final fast gate `.work/evidence/20260928T003648-fast` passes 189 Scala scenarios and four upstream checks, along with the existing evaluator checks. All 258 runtime/build/evaluator sources match; no product contract or process implementation changed for this instruction correction.

- Four exact reviewed applications close D1 at revision 5/cursor 11, G1 at revision 3/cursor 12 and M1 at revision 3/cursor 13, then Produce Upstream/OperatorAction/Handoff/Memory at revision 1 and G1 at revision 4/cursor 14. Every application follows its observed accepted independent review. All eight child requests receive current guidance for the seven nonmember original records. The final snapshot has twelve records and 28 retained revisions; T1, R1 and H1/H2/H3 are unchanged.
- The Upstream record identifies `synthetic_tokens.py` at defective fixture `e1144c242fd3a25737a843d2fd4004553e3f457f`, copies D1's reproduction exactly and has no report URL or external outcome. OperatorAction remains Requested with null confirmation and empty observed evidence. Handoff explicitly distinguishes the completed local repair from unperformed reporting and the absent endpoint/authorization. Memory limits the tokenization lesson to explicitly ASCII word contracts and acknowledges other legitimate Unicode specifications. The new records derive from G1 without joining the completed local-repair milestone.
- Git stays clean at incorporated candidate `46694494a847f4d64587ff84c3ef3058d66796de`; no new implementation/integration occurs. Claims are released. The database digest is `b6e067a790df0a8686098556b5e2dad3b5f5aac5723cda7c748d9c20ff7c0198`.
- Nine new attempts reconcile to 3,779,759 known tokens, nine partial meters and zero absent meters. Full checkpoint replay retains 35 distinct chain attempts and 11,857,443 known tokens. `upstream-experiments.json` includes the rejected first preparation and all previous rejected experiments exactly once: 56 distinct attempts, 19,427,888 known tokens, 56 partial meters and zero absent meters. The retained reporter `/srv/nvme/tmp/cq4-implementation/defect-upstream-analysis/report.py` hashes its source summaries; missing optional counters/prices remain missing. These differing configurations are not matched repetitions.
- Parent traffic contains thirty dispatch calls, 1,590/1,925-byte compact argument/reply maxima, four Proposal previews and no ArtifactText reads. One overlong claim-duration request is rejected before correction. The rejected first preparation retains its own traffic: 33 dispatch calls with 1,309/3,990-byte maxima. Neither payload measurement establishes token savings.

Independent Astra verified all 258 source hashes, replayed the full 35-attempt chain, inspected the four accepted-review-before-application acknowledgements, preserved investigation history and all seven guidance records for every child, and reviewed the resulting prose. It approved this scoped commit with no blocking or major finding. All 56 experimental attempts remain distinct and accounted. Full native process assessment, matched usage repetitions, M4 review and designated human acceptance remain open.

## Run

From the repository root:

```sh
./dev/check fast
./dev/defect-eval begin
./dev/defect-eval probe --checkpoint /path/to/passed-defect-begin
./dev/defect-eval research --checkpoint /path/to/passed-defect-probe
./dev/defect-eval plan --checkpoint /path/to/passed-defect-research
./dev/defect-eval integrate --checkpoint /path/to/verified-defect-plan
./dev/defect-eval upstream --checkpoint /path/to/passed-defect-integrate
```

To verify the retained citation-checker failure without rerunning models:

```sh
./dev/defect-eval verify-plan --checkpoint /srv/nvme/tmp/cq4-implementation/20260927T233851-defect-plan
```

On this host, while its default Claude differs from the pin, the exact invocation is:

```sh
PATH=/nix/store/ixiaaa3h8m705sp4ihivdi3014naxrxn-claude-code-2.1.280/bin:$PATH ./dev/defect-eval begin
```

The evaluator enters the pinned Nix environment when required, uses the configured native harnesses, starts an isolated PostgreSQL instance and retains evidence under `/srv/nvme/tmp/cq4-implementation`. It archives the operational audit and database; it does not add another accounting system. A passed intake is not full M4 or human acceptance.
