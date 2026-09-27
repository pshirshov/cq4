# M4 workflow entry points

Baseline `713032e`; one mutable `cq.api 0.1.0`. This increment implements the [four-command contract](../design/workflows.md): shared semantic resources, typed selections, host enforcement, bounded governing reports and native command export through the existing distage entrypoint. It does not complete the M4 worked-process/cohort/live-route exit conditions.

## Deterministic evidence

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Check | Evidence directory | Result |
| --- | --- | --- |
| Fast | `20260927T150235-fast` | 158 Scala scenarios, bridge and evaluation checks pass |
| Contracts | `20260927T150428-contracts` | Deterministic generation, Scala/TypeScript round trips, 448 schema definitions and seven domain MCP capabilities pass |
| Targeted actual host admission | `20260927T150354-workflow-boundary` | Phase and contextual milestone sibling denials pass; no child ticket, integration request or additional job |
| PostgreSQL and actual CLI/process fixtures | `20260927T150553-postgres` | 85 shared scenarios and all transport/CLI/supervisor/workflow/dispatch/combination/admission/shutdown/restart fixtures pass |

Fast, contracts and PostgreSQL manifests match the same 211 non-documentation sources (excluding `docs/` and `README.md`). `20260927T150553-postgres/m4-workflow-source-verification.json` records this check. No process-driver implementation changed; its previously verified dedicated process corpus remains recorded in the reviewer-check increment. The actual supervisor ownership, cancellation, timeout and recovery fixtures ran again in this PostgreSQL gate.

Shared dummy/PostgreSQL assertions resolve admitted review subjects without returning narratives, reject stale revisions and incompatible modes, and exercise positive/negative workflow execution selection. Exact standalone review handle, mode and membership are enforced. Rootless begin checks revision-1 provenance; another session's creation is denied. Existing status/cancel operations remain available. Filesystem tests verify command export identity, whole-set conflict preflight, explicit replacement, unrelated-file preservation and symbolic-link refusal. Actual CLI checks export all four assets for every harness. The supervisor fixture loads packaged workflow instructions and returns the validated bounded report.

### Retained failures and corrections

- `20260927T144704-fast`: Scala compilation rejected reserved method name `export`; renamed to `writeCommands`.
- `20260927T144923-postgres`: the additional successful workflow run was inserted inside an existing audit replay comparison. Its legitimate project audit cursor advance invalidated that comparison. Moved the run after the replay assertion; no runtime accounting change.
- `20260927T150031-workflow-boundary`: Astra identified advisory-only phase/scope limits. The actual `advance --through explore` fixture submitted a valid Worker/Implement request; it received Preparing and created a child ticket. The corrected host policy now rejects it before controller admission. The subsequent targeted run also excludes a sibling attached to a contextual milestone.

## Native discovery and invocation

Scripts, generated assets, metadata and captured expanded requests are retained under `workflow-native-discovery/`. These use installed Claude 2.1.280, Codex 0.156.1 and Pi 0.87.1. They perform no model inference; no token/cost observation or efficiency result is inferred from them.

| Harness | Observed native behavior | Collision handling verified |
| --- | --- | --- |
| Claude | SDK initialize lists all four project commands. Invoking each slash command sends its generated body and expanded arguments to a controlled loopback endpoint | A controlled personal `cq:begin` wins with user+project settings; `--setting-sources project` selects the generated project body |
| Codex | App-server lists all four project skills. Each explicit skill input loads the generated body into the controlled provider request | Three actual older installed skills overlap. The app-server input's exact project skill path selects the new body despite these duplicates |
| Pi | The installed native prompt loader discovers all four assets and its invocation expansion includes each generated command and forwarded arguments | A controlled personal template wins by default. Disabling default discovery and explicitly loading `.pi/prompts` selects the project body |

`invocation-summary.json` records all eight Claude/Codex expansions; their captured request files preserve content evidence. `claude-discovery.json`, `codex-discovery.json` and `pi-discovery.json` preserve discovery evidence. The Claude collision request files and `pi-directory-collision.json` preserve both the collision and explicit project-selection observations. Only request bodies are captured; credentials/HTTP headers are not retained. The endpoint rejects inference requests intentionally. Pi checks invoke the installed loader/expander directly, not a model session.

The command assets send the current user request to `cq run`, then expose the bounded report, unanswered question IDs/choices and remaining work. Actual user answers remain necessary for dependent continuation. Interactive command overhead has unavailable audit coverage; managed governor/child observations continue through the existing operational audit.

## Review and remaining work

Independent Astra approved the design and, after the reproduced enforcement correction, the source policy and native evidence with no further blocking or major finding, conditional on the full source-matched PostgreSQL gate passing. That gate has now passed. This approval is scoped to the entry-point foundation.

Remaining M4 requirements: worked process examples with real models, adaptive cohort selection/regrouping/fairness, live candidate reviewer named-check use, refreshed all-nine-route consumer evidence and any matched efficiency claim. Command expansion alone establishes none of these. M2 human acceptance remains pending separately.
