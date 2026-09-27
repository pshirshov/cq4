# M2 consumer evaluations — in progress

The explicit `dev/consumer-eval HARNESS python|go` runner starts a real governing harness, a different worker and a third reviewer. `dev/consumer-spec.md` defines the unrelated word-frequency CLI; the host oracle runs consumer tests and 104 independent input/output cases. Candidate validation and review remain distinct from integration, independent evaluation assessment and human acceptance.

Evaluation identity is part of `SupervisorSettings` (`evaluation: null` for ordinary work). The governing assignment and every dispatched child inherit the run/scenario identity. Accounting is exported from the operational audit; there is no second token collector. Model/provider routes, source hashes, commands, elapsed time, native session directories, audit exports and a PostgreSQL archive accompany each evaluation. Current runs also retain the stopped PostgreSQL data directory, including when export fails.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence | Observation |
| --- | --- |
| `20260927T013407-dispatch-role` | PASS deterministic inheritance of evaluation identity; evaluation-filtered totals equal session-filtered totals |
| `20260927T013507-consumer-claude-python` | Real Claude governor failed to launch children because local dispatch was absent from its tool inventory. Domain task creation/claim/release worked. Native process completed; evaluation failed. Audit and database dump retained |
| `local-mcp-object-schema/before.log` | Installed MCP SDK rejects both local tool schemas: missing root `type: object` in input and output |
| `20260927T013912-contracts` | PASS generated ADT root-object correction, explicit SDK ToolSchema checks for the local tools, deterministic generation and all 282 definitions |
| `20260927T014714-consumer-claude-python` | Claude discovered local dispatch and launched two Codex workers. Both failed before model execution with HTTP 400 `invalid_json_schema`: root `oneOf` is not permitted. No candidate or review was produced; native logs, audit and database retained |
| `20260927T015307-contracts` | PASS assigned generated Work/Review native schemas, canonical round trips and malformed-report rejection; 282 schema definitions |
| `20260927T015412-consumer-claude-python` | PASS real Claude governor → Codex worker → Pi reviewer, candidate `eaba3d0a199f98e98157233c8775fad76f68c993`, consumer tests and 104 host behavior cases; 155.851 seconds, three metered attempts, audit and database retained |
| `nixos-child-environment/reproduction.json`, `before.log` | Reproduced loss of Git/Python/Go discovery in filtered NixOS shells and the failing production-adapter regression |
| `20260927T015808-fast` | PASS 69 Scala scenarios, Node bridge and four evaluator acceptance scenarios; preserving the inherited Nix initialization marker fixes child toolchain discovery |
| `evaluator-acceptance/before.log` | Reproduced acceptance of a host-passed candidate A alongside review of a different failed candidate B |
| `evaluator-acceptance/after-corrected-fixture.log` | PASS four predicate scenarios requiring linked candidate/assignment, applicable configured checks, observed host success and complete review acceptance |
| `evaluator-archive-before.log` | Reproduced missing database archive after injected audit-export failure; temporary database removed |
| `evaluator-archive-final.log` | PASS retained database, independent backup and successful restoration after injected audit-export failure; original evaluation failure preserved |

The first real failed run recorded 93,722 inclusive input tokens and 2,516 output tokens (96,238 total), with a native USD 0.2241586 provider estimate. The second recorded 259,372 known total tokens and a USD 0.33465219999999996 provider estimate; both failed Codex attempts have no meters and explicit gaps. Cache/reasoning subsets are not added again. The governor meters remain partial, and no pricing revision or actual billed cost was observed. These are costs of failed capability paths, not successful consumer baselines or efficiency conclusions.

Astra identified the evaluator's unlinked-acceptance and failure-archival defects before approval. Both reproduced before correction. The initial acceptance test accidentally shared mutable fixture dictionaries; the corrected fixture round-trips its JSON to represent independent wire records. The first archival rerun had an overbroad test-loader substitution; the corrected fixture preserved the primary failure and produced the dump, then exposed PostgreSQL's 107-byte Unix socket-path limit in its nested restore directory. The evaluation cluster now listens on loopback TCP with Unix sockets disabled. Final restore verification passes, and Astra approved these evaluator corrections.

The second run's schema rejection is corrected by selecting the assigned generated report branch for native structured output. The contract check verifies canonical codec round trips, rejects role substitution, empty member lists and extra fields, and requires a closed object without unions. Contract verification and actual provider acceptance now pass.

The first successful candidate run reports 171,771 known direct task tokens and 313,952 unattributed governing tokens, totaling 485,723. All three attempts have meters; all meters have explicit coverage limits. Native costs include USD 0.072865000000000009 in direct provider estimates and USD 0.2246654 in governing estimates; Codex cost remains unknown. These are partial provider estimates, not a full billed total. `parent-traffic.json` records nine dispatch calls, maximum 578-byte compact arguments and 623-byte tool-result content; no domain `read` calls occurred. This excludes native envelopes and model-specific tokenization and establishes no matched efficiency improvement.

The successful worker exposed a NixOS shell-environment defect: filtering `__NIXOS_SET_ENVIRONMENT_DONE` caused shell startup to replace the supplied toolchain PATH. The worker used the configured absolute Python executable and still passed. A separate production-adapter test reproduced failure before both harness and validation environment filters were corrected to retain that inherited marker. The full fast gate passes. Astra approved the evaluated foundation and corrections for commit after replaying the retained candidate acceptance evidence. Independent evaluation assessment and the M2 checkpoint remain open.

Re-run explicitly (these commands call configured models):

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/consumer-eval claude python
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/consumer-eval codex go
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/consumer-eval pi python
```

Repeat equivalent runs per harness/model configuration before interpreting efficiency variation. One consumer candidate has passed; the other governing routes, independent assessment, integration and M2 human acceptance remain pending.
