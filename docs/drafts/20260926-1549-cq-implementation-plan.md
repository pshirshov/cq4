# CQ v1 implementation plan

Status: implementation underway; see [current status](../implementation-status.md). Baseline requirements commit: `6a923a6`. The objective is the complete first release described by R01–R31, delivered in executable increments.

## Authority, workspace, and starting evidence

- Implement in `/home/pavel/work/safe/cq4/cq4`, the Git repository containing this file. The surrounding `/home/pavel/work/safe/cq4` directory is the reference workspace, not the implementation repository.
- The [requirements prompt](20260926-0957-cq-requirements-prompt.md), confirmed user decisions, and subsequent user instructions define behavior. Its original instruction to produce only a design described the earlier task. Activating the implementation goal authorizes implementing those requirements. The [brief](20260926-0957-cq-design-brief.md) supplies recommended mechanisms; change those with a recorded reason when evidence supports a simpler compliant implementation.
- Read the [source audit](20260926-0957-existing-cq-audit.md) and [usage audit](20260926-usage-observability.md), then inspect reference code selectively. From the implementation repository, references are `../problem.md`, `../cq`, and `../ponygirls`. Keep the reference snapshots unchanged; put the replacement and its harness assets in this repository.
- The baseline contains documentation only. No build, generated contracts, database migrations, application, or automated application checks exist. Earlier real calls established usage observability, not CQ functionality.
- On 2026-09-26, the [official sbt site](https://www.scala-sbt.org/) listed 2.0.9 as stable. Start the compatibility check with the newest stable compatible sbt 2.0.x; verify and pin the rest of the stack. Neither that listing nor the reference project's sbt 1.x build proves stack compatibility.
- Apply the chosen Baboon and izumi skills, `resilient-ws-ui`, and the constructive-test-taxonomy/dual-tests guidance at the relevant implementation steps. User requirements take precedence over examples and optional recommendations in skills.
- User correction, 2026-09-26: keep one CQ schema version (0.1.0), edit it in place and permit breaking changes. Only explicit user instruction authorizes a version bump. Historical schema decoding, conversion fixtures and compatibility/upgrade paths are excluded for now. Earlier evolution evidence remains historical; item revision history and current-schema backup/restore remain required.

## Execution loop

1. Read current Git status and `docs/implementation-status.md` when it exists. Create that status file at implementation start with the next milestone, outstanding decisions, command/evidence links, and the next concrete action. Preserve unrelated user changes.
2. Select the smallest unfinished milestone increment whose prerequisites are available. Resolve only the design details needed to implement it while maintaining the shared contract/architecture documents. Produce the complete required design artifacts by their owning milestone; documentation is not a substitute for execution.
3. Implement through named domain types and narrow service/repository/process interfaces, wired by distage. Reproduce suspected defects before fixing them. Run the checks that address the changed behavior and relevant invariants; record failures and infrastructure gaps accurately.
4. Commit coherent verified increments. Update requirement coverage and status with the exact commit, commands, exit outcomes, and evidence. At milestone boundaries use an independent Astra reviewer; resolve substantive findings and repeat review until approved. Review approval does not replace runtime checks or human acceptance.
5. Continue to the next increment. A completed spike, schema package, or first usable slice is progress toward this goal. Do not declare the whole release complete at an intermediate milestone.

Preserve the four-role/four-command direction, at most ten ordinary agent-visible capabilities, transient worksets, separate member identities, and the operational usage log outside the 14 ledgers. Scope new abstractions to actual boundaries. No importer, TUI, arbitrary ledgers, server-owned harness processes, or mandatory indefinite child survival is part of v1.

Use one editor at a time unless parallel editing is explicitly authorized; separate worktrees are required for concurrent editors. Read-only reviewers can share the checkout. Commit locally; publication/deployment and sending reports to external parties require the corresponding user instruction. Routine reversible implementation and checks should proceed without repeated confirmation.

## Maintainer verification entry points

Introduce thin, unattended entry points over the selected tools during M0. These are planned commands, not existing executables. They belong to this repository's development tooling and must never become default validation commands for consumer projects.

| Planned command | Evidence it must produce |
| --- | --- |
| `./dev/check contracts` | Baboon generation, generated Scala/TypeScript compilation, wire round trips, MCP example/schema validation, no unexplained generated drift |
| `./dev/check fast` | Type checking, DI wiring, and deterministic public-behavior checks using hand-written dummies |
| `./dev/check postgres` | The same applicable service/repository scenarios against isolated PostgreSQL, plus actual transaction/concurrency checks |
| `./dev/check process` | Controlled executable fixtures for launches, hangs, malformed/partial output, descendants, cancellation, and lifecycle races |
| `./dev/check browser` | Real browser interaction and transport recovery checks against the application; retain diagnostic traces/screenshots |
| `./dev/check native` | Build the production native artifact and exercise its HTTP/MCP/WS/PostgreSQL paths; later include the supervisor path |
| `./dev/evaluate --suite first-slice` | M2 consumer builds with Claude, Codex, and Pi; artifacts, shared usage-log reports, and independent assessment |
| `./dev/evaluate --suite release` | The completed consumer corpus and all nine parent/child routes against packaged CQ |

Expose configuration for test infrastructure explicitly. Support provided PostgreSQL when container startup is unavailable; use an isolated database/schema and safe cleanup. Missing required infrastructure cannot yield a passing required check. Planned checks with no implemented coverage must fail or report unavailable, not return a placeholder success. Document the actual underlying sbt/TypeScript/browser commands and pins. Keep live-model evaluation separate from deterministic checks; run only the required corpus and targeted reruns after material changes.

Store concise result manifests in `docs/validation/` with commit/configuration, scenario IDs, command results, artifact locations, limitations, and assessment/human verdicts. Keep bulky generated/private logs in an ignored task directory, with a durable location and retention recorded in the manifest. On this host `/srv/nvme/tmp/cq4-implementation/` is available for large intermediates. Product usage observations live in the CQ usage audit log, not a second evaluation accounting store.

## Milestones

### M0 — Prove the stack and establish executable contracts

**Depends on:** the approved requirements, not the old application.

**Build:**

- Pin Scala 3, sbt 2.0.x, BIO/distage, Baboon compiler/runtimes, PostgreSQL driver, HTTP/WebSocket/MCP libraries, the TypeScript toolchain, and a compatible native-image toolchain. Record exact versions, reasons, and commands in a dependency/compatibility note.
- Create the small build/module layout: generated contracts; domain/application services; PostgreSQL and transport adapters; server/supervisor composition roots; browser; process assets; verification tooling. Split modules when they enforce a dependency boundary; avoid empty future modules.
- Compile the single actual Baboon schema and Scala/TypeScript codecs. Round-trip IDs/revisions beyond JavaScript's safe integer range, typed errors, and explicit current-version acceptance/rejection. Change the schema in place during development.
- Exercise a distage/BIO service, real PostgreSQL JSONB write/read, one authenticated HTTP operation, one WebSocket exchange, and MCP initialization/tool exchange. Build and run this path as a native executable with explicit plugin registration.
- Write `docs/design/architecture.md`, `docs/design/contracts.md`, and `docs/requirement-coverage.md`; start the R01–R31 mapping. Record the canonical edge/history/change-cursor approach, authority boundaries, and usage observation/assignment identity before dependent code grows.

**Exit:** generated clients compile and exchange data; the same minimal service runs on JVM and native; real database and transport operations succeed; DI checks and exact command records exist. If a required dependency combination fails, produce a minimal failing reproduction and investigate compatible choices. Ask only when meeting the requirement needs a user decision, such as changing a mandated technology. Do not silently substitute sbt 1.x or defer native feasibility to the end.

### M1 — Durable core and minimal usable interfaces

**Depends on:** M0 compatibility and foundational contracts.

**Build:**

- Define all 14 ledger content/status types, common metadata, typed references, and initial PostgreSQL migrations. Add project identity/init/reattachment, atomic per-ledger IDs, idempotent mutations, revisions, complete history, archive-as-attribute, and schema-valid correction/reopening.
- Implement canonical edges/inverse reads and history effects. Establish transaction-safe committed change publication, stable request acknowledgements, snapshot/subscription handoff, and explicit resynchronization. Implement a small shared query AST and indexed create/get/search/history paths; expand the grammar in M3.
- Wire HTTP MCP and Baboon browser/CLI adapters to the same services with authenticated project scope. Start with the tools needed by the slice. Publish concrete input/output schemas and validate examples rather than relying on illustrative JSON.
- Deliver a minimal browser for project selection, item entry/list/detail/history, and truthful transport/synchronization state. Include the heartbeat/reconnect foundation now.
- Before allowing agent work, implement atomic acquisition/renewal/release of explicit item-set claims and stale-fence rejection. Provide isolated workspaces and scoped role access for the upcoming supervisor.
- Add the append-only usage audit repository and host ingestion/read contracts: attempt/source identity, frozen assignment/cohort membership, raw and normalized counters, cost basis/currency, gaps, corrections, and direct/shared/unattributed views. Add a minimal CLI/browser audit view without mutating task history.

**Exit:** a fresh isolated database supports two independent projects through actual clients; counters and idempotency survive concurrent requests/restarts; history and inverse references reconstruct correctly; project/role denial and stale revision/fence cases fail correctly. Shared repository/service scenarios pass against dummy and PostgreSQL. The 1,500-token attribution example and correction to 1,600 pass with duplicate delivery and unknown cost. No full-ledger rewrite is required by these operations.

### M2 — First usable agent slice, with real evaluations

**Depends on:** M1 durable state, claims, permissions, and usage ingestion.

**Build:**

- Implement the local `cq run <harness>` supervisor and short start/status/cancel control calls. The server stores domain state/artifacts/usage and never owns harness processes. Drain output continuously; bound startup, execution, cancellation, and hung exits; track process identity and quarantine uncertain workspaces.
- Compose the server, CLI tasks and local supervisor through one distage `RoleAppMain` entrypoint, with role-specific resource graphs and injectable harness adapters (user clarification, 2026-09-26). Replace the temporary manual CLI branch as the supervisor is wired; verify client/supervisor roles do not acquire server/database resources.
- Implement Claude Code, Codex, and Pi shellout adapters with pinned/configurable model routing, restricted native/MCP tools, and isolated role configuration. Check each adapter's actual permission behavior; tool hiding alone is insufficient.
- Implement reference-based input assembly and result storage outside the parent model. Supply a worker/reviewer path with bounded outcome summaries, explicit drill-down, and direct result-handle chaining. Keep domain writes and candidate integration with the governing session; this slice may operate sequentially in isolated evaluation repositories.
- Connect all three collectors to the M1 audit service, including governing sessions in evaluation mode. Normalize cache/reasoning subsets, cumulative/resume counters, repeated events, partial outcomes, and missing costs. Record unsupported interactive collection paths as gaps.
- Implement the small evaluation driver, fixture repositories/specifications, outcome rubric, and reports. It reads the operational log for accounting and adds scenario/outcome analysis. It does not launch a second telemetry system.

**First corpus:** one tiny Python CLI with `python -m unittest`, and one tiny Go CLI with `go test ./...`, each with a precise behavioral spec and explicit project validation command. Keep these commands in consumer configuration. Allocate the first three runs so each harness governs at least one build and both consumer languages are represented; repeat an equivalent scenario within each harness/model configuration before interpreting efficiency variation. Single observations establish baselines, not statistical efficiency claims.

**Exit:** each of Claude, Codex, and Pi builds and validates an unrelated consumer project using the new CQ process, persists history/artifacts, and exposes parent/child usage with coverage. Each shellout adapter executes at least one real child job; at least one real cross-harness worker-to-reviewer chain forwards only handles through the parent. Controlled fixtures demonstrate that hangs and parent exit settle or quarantine the right hierarchy within configured deadlines. Obtain independent LLM assessment and request human acceptance of the concrete first-slice evidence. This is the first designated human acceptance checkpoint.

### M3 — Complete graph, query, and concurrency invariants

**Depends on:** M1 contracts and M2 integration evidence.

**Build:**

- Complete the shared query grammar: Boolean operators/precedence, FTS, exact IDs, scalar/ref/archive filters, pagination, syntax spans, and completion metadata. Keep access scope outside user query control.
- Complete root-based transient worksets with bounded traversal, actionable/context separation, shared descendants, and no milestone-sibling expansion from contextual membership.
- Implement exact whole-subgraph termination preview/apply, typed per-ledger outcomes, exclusions, stale revision/membership detection, pagination or explicit size failure, and atomic idempotent history effects.
- Complete claim overlap, producer/descendant coordination, takeover, expiry, bounded cancellation, and late-result rejection. Preserve authorized late usage observations independently of result admission.
- Implement integration through isolated workspaces with expected-target comparison and atomic branch update or equivalent serialization. Handle target advancement and revalidation of the combined candidate. Reconcile Git success with uncertain ledger acknowledgement without repeating integration blindly.

**Exit:** reproduce and verify the approved two-session claim/integration scenarios on real PostgreSQL and real scratch Git repositories. An intervening descendant invalidates termination preview; shared/prerequisite work is excluded correctly. Query-plan/access observations at increasing unrelated project sizes demonstrate bounded mutation work. Rollback and retry leave no partial history or duplicated effects.

### M4 — Complete the process, cohorts, and dispatch contracts

**Depends on:** M2 shellouts and M3 concurrent-work invariants.

**Build:**

- Deliver explorer/planner/worker/reviewer contracts, typed modes, assets, tool profiles, and old-to-new mappings. Complete begin/advance/review/upstream entry points for all harnesses from shared semantic assets. Inventory administrative, MCP, and host-only operations.
- Implement intake → goals → milestone-organized work, investigation/research/decision/handoff flows, independent review, correction/reopening, and operator/upstream action evidence. Keep readiness informative and hard gates limited to data, permission, and concurrency invariants.
- Add bounded adaptive cohort selection, frozen execution membership, per-member outcomes, regrouping/splitting, singleton explanations, and no-progress/fairness behavior. Record scope in the same usage log; historical costs survive regrouping and cancellation.
- Complete stored-proposal preview/application by handle and repeatable artifact reads. Reject unauthorized/self-authorizing proposals. Keep essential blockers visible in bounded summaries.
- Complete all three same-harness routes and six directed cross-harness routes over the small corpus. Measure advertised tool schema size, calls, parent-visible payload growth, and full-body copies; collect whole-hierarchy usage through R31.

**Exit:** the worked process examples run without CQ-repository-specific commands or paths. Every role's attempted forbidden operation is denied. All nine routes have actual execution evidence across the corpus without multiplying every scenario by every role/model. Increasing child narratives/results preserves bounded normal parent dispatch traffic; a reported end-to-end efficiency improvement requires matched usage and accepted-quality evidence.

### M5 — Complete the web UI and operational views

**Depends on:** the stable query, history, process, and usage services.

**Build:**

- Complete three panes/top bar, query editor with cursor-aware completion and syntax errors, history/relationship editing, project switching, and useful task/cohort/session/project usage summaries with audit drill-down.
- Implement keyboard/focus behavior, resizable panes, scroll/overflow, narrow layouts, loading/empty/error states, edit conflicts, and explicit unsaved-draft preservation.
- Complete `resilient-ws-ui` behavior with nonce liveness, deadlines, bounded jittered retry, sleep/lifecycle handling, teardown guards, and truthful terminal/deferred state. Keep synchronized-data state distinct from transport health.
- Verify snapshot/replay/resync, late replies from old project generations, uncertain mutation acknowledgements, and no duplicated writes. Label metric freshness, shared attribution, estimated costs, and coverage.

**Verification scope:** use checks for the behavior affected by each increment: browser interactions and rendering, generated API contracts when changed, and the relevant service checks. UI-only changes reuse the retained harness evidence. Run affected harness checks when adapters, dispatch, shared harness contracts or process behavior change; reserve the full expensive nine-route matrix for changes that require that scope and the packaged release verification in M6. This incorporates the user's 2026-09-27 clarification.

**Exit:** actual browser checks and retained visual evidence cover editing while updates arrive, disconnect/reconnect across a committed mutation, stale cursor resnapshot, project switching with a draft, keyboard use, and narrow/overflow layouts. Audit uploads update usage views without producing item revisions. Source inspection alone cannot close these checks.

### M6 — Package and verify the complete release candidate

**Depends on:** M0–M5 implemented and required findings resolved.

**Build:**

- Produce the native server/supervisor/CLI distribution and frontend assets, with JVM development mode, reproducible generation/build instructions, pinned dependencies, configuration examples, and locally installable harness integration assets compatible with ponygirls.
- Integrate Baboon through its upstream Nix flake instead of the manual compiler download in `dev/generate` (user addition, 2026-09-26). Pin the input in `flake.lock`, verify the selected compiler and platform outputs, and preserve deterministic generation and the contract checks. Schedule this with release packaging; do not interrupt current implementation for the switch.
- Verify fresh initialization, current-schema decoding/version acceptance, PostgreSQL backup/restore, artifact/usage retention behavior, and resource shutdown. No historical schema compatibility or upgrade path is required during this development phase.
- Run the complete deterministic verification surface and native runtime checks. Re-run the consumer corpus against the packaged executable, including all nine routes and the usage/efficiency report. Confirm setup works from an unrelated working directory with explicit project configuration.
- Complete `docs/requirement-coverage.md` for R01–R31 and all required design artifacts, operational instructions, known limitations, and final evidence manifest. Obtain independent Astra review of the release candidate and request human acceptance of the concrete release evaluation.

**Exit:** all definition-of-done conditions below hold. Deliver a locally usable, verified release candidate; publishing it is a separate action.

## Requirement ownership

This is the starting map; the implementation coverage file adds concrete code/contract/check/evidence links for every requirement.

| Primary milestones | Requirements |
| --- | --- |
| M0, then native completion in M6 | R01 stack, R03 generated/versioned contracts |
| M1, refined in M3/M4 | R04 MCP, R05 fixed typed ledgers, R06 no custom ledgers, R07 provenance/gating, R10 history, R11 counters, R12 PostgreSQL, R14 archive, R20 identity, R25 corrections |
| M2 and every subsequent evaluation checkpoint | R09 real evaluations, R21 supervision, R22 privilege separation, R30 dispatch without forwarding, R31 operational usage log |
| M3 | R15 query grammar, R17 canonical refs, R18 worksets, R19 bounded work, R26 termination, R27 claims/integration |
| M4 | R08 process relationships, R13 lean MCP, R16 cohorts, R28 roles, R29 commands |
| M1 foundation, M5 completion | R02 live frontend, R23 query editor, R24 three-pane interaction |

## Definition of done and decision handling

- Every R01–R31 entry links to implemented behavior and appropriate evidence. Every required design artifact exists and executable contracts/DDL/examples have been validated. Missing capabilities and unexecuted checks remain open; they are never relabeled complete by documentation.
- Required deterministic, PostgreSQL, process, browser, and native checks pass at the final candidate. Live consumer builds and nine routes have retained evidence, independent assessment, operational-log usage reports, and the designated human acceptances.
- No unresolved blocking/major review finding remains. Smaller findings are fixed or explicitly recorded with their consequence. The working tree contains no accidental/uncommitted implementation changes; generated/private artifacts have appropriate ignore/retention treatment.
- Repository setup, running CQ against another project, validation, and recovery are documented with exact commands. The final report identifies the tested commit, evidence, known limits, and how to run the artifact.

At M2 and M6, ask for human acceptance only after the evidence package exists. Earlier permission to implement is not an acceptance verdict. While human review is pending, continue useful authorized work that does not depend on that verdict, but keep the checkpoint and whole-goal completion pending. Independent LLM approval cannot replace human acceptance.

When a material requirement is impossible or contradictory, retain the failing reproduction, list compatible attempts and the smallest decision needed, and ask the user while continuing independent work. For missing credentials, unavailable infrastructure, or runtime/budget limits, record the exact blocker and next action; do not keep launching equivalent failed attempts. Follow the active harness's goal lifecycle rules. An unresolved blocker or exhausted budget is not completion, and no unrequested token budget is imposed by this plan.

## Goal text

Paste this into a Codex session with access to the reference workspace. It targets the entire implementation, beginning at M0. `/goal <objective>` is the documented activation form; a goal remains subject to user controls and runtime limits. [Official OpenAI Goals documentation](https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex)

```text
/goal Implement the complete first CQ release in /home/pavel/work/safe/cq4/cq4 by executing docs/drafts/20260926-1549-cq-implementation-plan.md, milestones M0–M6, against R01–R31 in docs/drafts/20260926-0957-cq-requirements-prompt.md. This goal authorizes implementation; the requirements prompt's earlier design-only instruction belongs to the completed planning task. Start with the stack/contracts proof and continue through the usable slice, full process, UI, and native release candidate. Use the shared operational usage audit log for both task/cohort accounting and evaluation monitoring. Keep docs/implementation-status.md and docs/requirement-coverage.md current, commit verified increments, and use independent Astra milestone/release reviews with correction loops. Run the plan's deterministic checks and real Claude/Codex/Pi consumer evaluations; preserve evidence and explicit gaps. Complete only when the plan's definition of done holds, including designated human acceptance after presenting concrete evidence. Continue useful independent work while awaiting necessary input; report blockers without weakening requirements or claiming completion. Keep the reference snapshots unchanged and deliver the locally runnable artifact with exact run/verification instructions.
```
