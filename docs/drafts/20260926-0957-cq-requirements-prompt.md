# Prompt: design the CQ replacement

The text below is the reusable prompt. The companion [design brief](20260926-0957-cq-design-brief.md) proposes concrete boundaries, roles, commands, and dispatch behavior. The [source audit](20260926-0957-existing-cq-audit.md) records what was inspected and one reproduced configuration leak. The brief's detailed recommendations are starting points; requirements and confirmed user decisions in this prompt take precedence.

---

You are designing a fresh implementation of **cq**, a general-purpose agent-assisted software development system. Produce a complete, concise, implementable design and requirements package. This task is design, including executable schema artifacts and feasibility checks; do not start implementing the application.

## Inputs, scope, and evidence

The workspace contains:

- `./problem.md`: the original problem statement.
- `./ponygirls`: reusable Claude Code, Codex, Pi, and sandbox configuration.
- `./cq`: the existing CQ implementation, prompts, and extensions.
- `./cq4`: the new project directory. The product and executable remain named `cq`.

Read the supplied audit and brief, then inspect the existing source needed to verify or refine their conclusions. Do not copy the old architecture, provenance protocol, or CQ-development-specific policies into the new product. Old ledger issue/decision IDs are historical evidence, not requirements for arbitrary consumer projects. Keep old repositories unchanged during this design task.

The existing source has 14 canonical ledgers, ten dispatched roles, sixteen workflow commands, and 68 capability tool names, of which 45 form the ordinary profile before construction-specific filtering. It already has some useful interfaces, dummies, targeted database writes, WebSocket recovery, role restrictions, and dispatch input assembly by reference. Preserve their useful contracts while reducing the machinery needed to satisfy them.

Treat reported regressions, UI defects, and language limitations as hypotheses unless independently inspected or reproduced. Distinguish observations, inferences, and unverified claims. In particular, line counts and test counts do not prove that TypeScript caused maintenance failures. Explain which proposed Scala types actually eliminate invalid states and which guarantees still require runtime validation or concurrency tests.

Apply the Baboon and izumi skills, the requested `resilient-ws-ui` skill (called “resilient-web-ui” in the original note), and the constructive test taxonomy/dual tests guidance. Use the requested [bifunctor-tagless distage example](https://github.com/7mind/distage-example/tree/develop/bifunctor-tagless) as a coding reference. Verify current source layout and pinned tool behavior. That reference was inspected using sbt 1.12.11; it does not establish sbt 2.0 compatibility.

## Confirmed user choices

1. **Fresh start:** no existing-data migration, old MCP compatibility, or TUI requirement. First release has a web UI and CLI.
2. **Cohorts retain identities:** automatically group related work to share effort. Do not automatically merge duplicate ledger entries.
3. **Cooperative-agent separation:** enforce MCP permissions and harness tool restrictions to prevent accidental misuse. Do not build a hostile multi-tenant OS security system.
4. **Restricted bulk traversal:** terminate derived work and milestone members; exclude prerequisites and shared work unless explicitly selected. Show exclusions in the preview.
5. **Parent-owned lifetime is acceptable:** surviving the governing harness's exit/restart is not required. Prefer cancellation of the whole child hierarchy if it yields the simpler design. Completed artifacts remain durable.
6. **Compact orchestration results:** bounded outcome summaries and explicit drill-down are allowed. Prompt/input/result forwarding must not require their full content in the governing session.

## Requirements

### R01 — Server and implementation stack

One server process serves all projects. Use Scala 3, **sbt 2.0**, BIO, and distage. Production builds use native-image; JVM development runs are supported. Prefer ordinary typed services with constructor injection, explicit resources, and a small composition root. No mutable global dependency overrides or reflection-dependent plugin discovery in the native build.

Prove compatibility early, including the HTTP/WebSocket layer, PostgreSQL driver, Baboon generation, MCP transport, and native packaging. Pin the newest stable compatible dependency versions after checking authoritative sources. Do not silently substitute sbt 1.x or a prerelease when the requested stack is unavailable; identify the specific incompatibility and the smallest decision required.

### R02 — Dynamic TypeScript frontend

The browser frontend is TypeScript and uses WebSocket requests/events for dynamic state. Follow `resilient-ws-ui`, translating runtime-specific examples appropriately. Separate transport liveness from data synchronization. Specify heartbeat correlation, deadlines, stale grace, bounded retry with jitter, lifecycle/sleep handling, connection replacement, teardown, and truthful terminal/recovery indications.

### R03 — Baboon browser/server contracts

Define actual Baboon schemas and generate Scala/TypeScript types and codecs. Cover application operations, typed errors, version negotiation, request correlation, subscriptions, snapshot/change synchronization, replay/resync, heartbeat, and mutation acknowledgements. Specify exact JSON/binary encoding and lossless 64-bit values; handwritten parallel DTO hierarchies are not the contract.

Preserve the ability to decode historical persisted versions. Define external API version support and exercise old/new clients as the new product evolves. A fresh start removes the old CQ compatibility burden, not the need for version discipline in released new CQ APIs. Database migrations and Baboon wire conversions are separate responsibilities.

### R04 — HTTP MCP in the server

Expose standards-compliant Streamable HTTP MCP directly from the Scala server. Browser and MCP adapters invoke the same application services. Specify initialization, tool schemas, typed tool errors, cancellation, authentication, project scope, and transport-version negotiation. MCP sessions/connection IDs are not authentication. A frontend need not speak MCP internally.

### R05 — Fixed ledger set, simpler typed content

Preserve these ledgers and prefixes:

| Ledger | Prefix | Ledger | Prefix |
|---|---|---|---|
| milestones | M | ideas | I |
| defects | D | goals | G |
| tasks | T | researches | RS |
| hypothesis | H | questions | Q |
| decisions | K | reviews | R |
| handoffs | HO | operatorActions | OA |
| memories | MEM | upstream | U |

For each, define required/optional fields, its status ADT, terminal states, dependency-satisfying outcomes, examples, and validation. Reduce incidental field differences. Keep distinct domain concepts typed. Do not use `Map[String, Any]`, a generic string bag, nested JSON strings, or an unconstrained `fields` object to evade the schema work.

### R06 — No custom ledgers

No custom-ledger/schema registration or `create_ledger` capability. The fixed model evolves through normal versioned development. Do not replace custom ledgers with an equivalent untyped plugin mechanism.

### R07 — Simple provenance and gating

Record authenticated actor, session/run, timestamps, item revisions, and pertinent evidence/commit/artifact identities. Clearly distinguish model-declared metadata from host-observed facts. Avoid attestation chains, repeated capability promotion, workflow-specific proof receipts, and plan-finalization protocols unless a concrete invariant cannot be satisfied more simply.

Separate hard data/permission/concurrency invariants from process guidance. Readiness may explain missing evidence, review, answers, prerequisites, or operator action; it must not make ordinary schema-valid correction/reopening impossible. Verification outcomes must describe what was actually checked. User confirmation of an external action and observed completion evidence are separate facts, without six dedicated MCP methods for that lifecycle.

### R08 — Intake, goals, and milestone relationships

Ideas and defects can be entered directly without a milestone. The process produces goals from intake, then tasks, researches, handoffs, reviews, decisions, hypotheses, and other process artifacts. Milestones organize work/artifacts, not ideas, defects, or goal ownership. Remove the ambient milestone fiction.

Define how artifacts derive from goals, how a record can be shared, how hypotheses form investigation trees, and how questions versus empirical researches are chosen. A requirements/preference choice goes to the user; an empirically answerable uncertainty goes to research/probing. Do not require users to understand internal lifecycle metadata to enter work.

### R09 — Real harness evaluations from the first usable slice

Each target harness—Claude Code, Codex, and Pi—must build a small unrelated consumer project following the process, beginning at the first usable milestone. Include genuinely different project languages/build commands so CQ-development assumptions cannot pass unnoticed.

Retain transcripts, ledger history, source diff, actual command results, harness/provider/model versions and settings, timing, and token-usage evidence. Use an independent LLM assessment of process quality and deliverable quality, with a cited rubric and human acceptance of designated milestone/release runs. These evaluations are not fully automatic. Objective artifact checks still run automatically; an LLM verdict cannot turn a failed build or missing requirement into success.

Monitor token usage and efficiency from this first slice. Local live probes confirmed usage counters in all three installed harnesses; see the [usage observability audit](20260926-usage-observability.md). Require a versioned collector for each harness's structured output, covering the governing session and every dispatched child without routing telemetry or full transcripts through the orchestrating model. Keep native evidence and normalized input, output, cache-read, cache-write, and reasoning/thinking counts where supported. Record whether cache counts are included in input and reasoning in output; never add subset counters twice. Distinguish cumulative snapshots from increments, including resumed/forked sessions and replayed events.

Attribute observations to evaluation, project, session, dispatch/attempt, parent, role, harness, provider, and model. Count a shared/cohort run once in hierarchy totals. Include observed failed/retried/cancelled attempts and compaction or auxiliary calls; account for independent assessment separately and report the combined evaluation total. Declare collection coverage and gaps. Preserve measured zero, missing/unsupported fields, estimates, partial runs, and unknown usage as distinct states; a default-filled zero is not evidence of no usage. A timeout without a final usage event retains observed usage with an explicit incomplete total. A missing required collector is a failed evaluation-instrumentation check, not a successful zero-usage run.

Report total observed tokens, cache share, parent/child usage, calls/retries, elapsed time, and estimated cost when a known price basis exists. Keep token counts separate from locally tokenized context/payload measurements and from billing; record pricing source/version, and do not represent subscription usage or harness cost estimates as an actual charge. Compare matched scenarios within a declared harness/model/configuration and cache condition; report repetitions and variation, accepted-deliverable rate, and total attempt usage per accepted scenario (undefined when none are accepted). Retain failed attempts in the numerator. Cross-model token totals are descriptive, not an interchangeable unit of work or proof of efficiency. Establish baselines and explicit regression tolerances without allowing lower usage to excuse worse correctness or process quality.

Evaluate reference-based dispatch separately: measure parent-visible prompt/result bytes, full-body copies and drill-downs, model-specific token counts where obtainable, and end-to-end usage. Increasing child artifact size must not proportionally increase normal parent dispatch traffic; any claim of total token savings also needs a matched baseline including child work. Adapters must demonstrate correct accounting for duplicate/cumulative events and incomplete runs; repeat observability checks when harness/provider versions change.

Cover same-harness runs and all six directed cross-harness pairings across a small shared scenario corpus, without multiplying every scenario by every role/mode/model. Keep live-model evaluations separate from deterministic CI while making the required evidence an explicit milestone exit condition.

### R10 — Complete item history

Every successful creation, field/status edit, reference edit, archive change, and bulk mutation has immutable history. Define as-of content and relationship reconstruction, history pagination, and restore-as-new-revision. Store original schema versions and provenance. Failed transactions and retries cannot create partial or duplicate history. Do not confuse last-writer metadata, archive copies, or content hashes with version history.

### R11 — Server-allocated unique IDs

Atomic per-project/per-ledger counters allocate IDs. Callers cannot race by choosing IDs. Allocation, creation, history, and idempotency outcome commit together. IDs are never reused; gaps are permitted. Prove concurrent allocation using independent sessions, including retries after an uncertain response. Define compound identity and the project scope of shorthand IDs such as `T42`.

### R12 — PostgreSQL JSONB

PostgreSQL is the production system of record. Use JSONB for typed ledger content and relational keys/indexes for identities, edges, claims, and queryable metadata. Specify DDL, constraints, indexes, transaction boundaries, schema migrations, and backup/restore expectations. No SQLite/Markdown primary or interchangeable production backend requirement. A hand-written dummy is for deterministic verification.

### R13 — Lean, measurable MCP surface

Start from the brief's candidate eight server tools plus one local dispatch capability. Refine by use case; aim for at most ten ordinary agent-visible capabilities, with fewer for restricted roles. Account for advertised schema/description size, typical response size, and the number of required calls—not only tool names. A giant untyped `operation/payload` dispatcher does not meet this requirement.

Prefer compact discovery, explicit bounded full reads, batch reads/changes where useful, and small mutation acknowledgements. Document all infrastructure/host APIs as well; do not hide a second sprawling lifecycle protocol behind the lean public surface.

### R14 — Archive is an attribute

Archiving/unarchiving uses normal mutations and history. Default views omit archived records, while explicit queries, direct references, and history can access them. No separate archive storage, archive endpoint family, or requirement to abandon/recreate an item before archiving it.

### R15 — One search language

One search operation and grammar serve MCP, CLI, and UI: GitHub-style attributes plus full-text terms/phrases, with explicit Boolean semantics. Specify supported fields/operators, escaping, precedence, grouping, negation, exact IDs, relation queries, archive defaults, stable sorting, pagination, and invalid-query diagnostics with source spans.

Use a typed AST and parameterized SQL. Authorization/project scope is applied independently of the query. Full design must show examples combining text, status, ledger, references, and archived state, and explain query/index costs. If deferring AND/OR, explain the concrete cost and supported grammar; do not leave their semantics unspecified.

### R16 — Automatic adaptive cohorts

Automatically consider grouping related ready work to share exploration, planning, implementation, validation, or review. Retain item identity and per-member acceptance. Define candidate bounds, compatibility, selection, frozen execution membership, regrouping, splitting, singleton reasons, fairness, and no-progress handling.

Use language-independent evidence. Shared labels, files, or pairwise overlap do not by themselves establish a compatible complete group. Cohorts must not become a new persistent ownership protocol or a mechanism for marking all members successful from one green check. Make decisions and measured savings visible.

### R17 — Unified typed item references

Use one `refs` representation with typed relations and automatic inverse views, e.g. `T42 blocked-by D534` implies `D534 blocks T42`. Define the finite relation vocabulary, inverse mapping, allowed endpoint kinds/cardinalities, cycle rules, and how each relation participates in traversal/readiness/termination.

Prefer one canonical edge to two separately mutable reciprocal records. Specify atomic add/remove, normalization from either direction, duplicates, dangling targets, archived targets, concurrent changes, and historical reconstruction. Keep source citations/artifact attachments distinct from ledger-item identity without creating competing item-reference sets.

### R18 — Transient worksets

Users choose roots. An MCP graph operation enumerates corresponding actionable subgraphs plus relevant context and readiness reasons. Worksets are transient selections; no `worksetOwner*` item fields or durable per-item workset membership. Optional local remembering of roots does not create an ownership graph.

Specify the difference between selected work, prerequisite/context records, and authorized access. Define empty roots, shared descendants, new descendants, archived records, bounds, and pagination. Adding a milestone as context must not schedule every unrelated sibling. Worksets do not replace work claims.

### R19 — Bounded mutation work

An ordinary mutation visits the affected items and incident relationships, not every ledger/project item. No whole-ledger transformation, full in-memory reload, or full search-index rebuild on small writes. Include read amplification, lock contention, history work, and notification cost in this requirement.

Describe complexity in terms of changed records and affected closure. Verify with database access/query-plan observations at increasing unrelated project sizes. Distinguish explicit maintenance/migrations and intentional bulk traversal. Select reasonable measurable performance budgets in the design and label unmeasured estimates.

### R20 — Stable low-friction project identity

`cq init` generates and atomically persists identity, uses directory basename for initial display, and supports `--project-id`. Define display rename, user-chosen identifiers, collisions, identity changes, worktrees, moved directories, copied/shared configuration, concurrent init, and reconnect to a remote server.

No dependence on first commit, directory path hashes, repository language, or a CQ source directory. Do not promise independent clones share identity without sharing or explicitly selecting an identifier. A name edit must not silently fork or merge project state.

### R21 — Cross-harness dispatch and bounded process ownership

Use shellouts for child harness execution. The CQ server never starts/holds harness processes. Prefer the brief's local supervisor and governing-harness wrapper; evaluate actual harness integration before finalizing it.

Define ownership, start acknowledgement, status polling, stdout/stderr drainage, deadlines, cancellation/escalation, process-tree cleanup, malformed/missing output, stuck startup, hung exit, frozen parents, lost server connectivity, and supervisor failure. The governing session must remain responsive. A timeout is not proof that the process has stopped.

Whole-hierarchy cancellation on governing-harness exit is acceptable and preferred over unnecessary restart recovery. Completed results remain readable. Explain isolation/quarantine of uncertain jobs and worktrees; never adopt or kill a process based only on a reusable PID. Automatic retries must not duplicate an uncertain side effect.

### R22 — Practical privilege separation

Specify each role's MCP scope, visible tools, actual server permissions, filesystem/workspace access, execution capability, and ability to dispatch. Enforce server permissions even when a hidden tool is called directly. Keep domain-ledger writes and integration with the governing process; children produce proposals/results.

Scope child credentials; do not leak parent credentials through inherited MCP configuration or prompts. Distinguish tool availability from approval policy. Treat arbitrary shell access as a limitation of the cooperative-agent threat model, not as an enforceable read-only sandbox. Preserve required provider extensions without loading unrelated tools. No silent privilege/model/harness fallback.

### R23 — Query-driven UI filters

The UI uses the same search grammar as MCP. Provide cursor-aware completion popups for fields, operators, values, IDs, and relation types, with keyboard support and positioned syntax errors. No combobox-based parallel filter model or “show archive” button. Any convenience action changes the visible query.

### R24 — Three panes and robust interaction

Retain three panes plus a top bar for query, metrics, project selection, and connection health. Design navigation/grouping, results, and item detail/history/relationships. Specify loading/empty/error states, keyboard navigation, focus, resizing, overflow/scroll behavior, narrow widths, project changes, stale replies, editing conflicts, and unsaved drafts under live updates.

Metrics must state their scope and freshness. Record a concrete browser verification plan; source reading cannot establish that visual glitches have been fixed.

### R25 — Validate schemas, permit correction

No transition state machine that prohibits a schema-valid correction or reopening. Revisions, authorization, claims, valid reference structure, and typed values still apply. Define how edits invalidate derived readiness/review applicability without making records immutable or forcing abandon-and-recreate. Keep dependency satisfaction distinct from terminality.

### R26 — Whole-subgraph preview and apply

Given roots and an intended terminal outcome, produce an exact dry run including changes, excluded/shared items, active work, and conflicts. Apply the reviewed plan atomically. Traverse only derived work and milestone membership; prerequisites and shared work require explicit inclusion.

Define terminal mappings for all 14 ledgers, preservation of existing factual artifacts, and unsupported/ambiguous outcomes. Never invent a confirmed hypothesis or successful operator action by applying a generic success label. Detect stale item revisions and graph membership changes, including new descendants created after preview. A large preview must paginate or fail explicitly, never silently omit items. Apply is idempotent and records history for the approved set.

### R27 — Native CQ work locks

Provide server-supported exclusive work claims, distinct from optimistic edit checks and short PostgreSQL transaction locks. Define atomic set acquisition, owner, expiry, renewal, release, fencing, user takeover, overlapping claims, and how creating descendants is coordinated with the producer's claim. Two sessions must not independently start the same selected work.

Demonstrate expiry/late-result behavior and make clear where server fences stop: they cannot directly prevent arbitrary stale OS processes from writing files. Combine them with isolated worktrees and supervisor-owned result/integration admission. Specify bounded cancellation after claim loss and handling of unconfirmed termination.

Define integration-target concurrency separately from item claims. Two valid claims for different tasks do not authorize concurrent uncoordinated changes to the same branch. Require an isolated integration workspace, an expected target revision, and a conditional atomic update or equivalent serialization that prevents lost updates and shared checkout/index interference. Specify what happens when the target advances and which review/validation evidence remains applicable to a newly combined candidate. A lease alone does not fence stale Git effects. Define reconciliation when Git integration succeeds but its ledger acknowledgement fails or is uncertain, without claiming a transaction spanning Git and PostgreSQL or blindly repeating the integration effect.

### R28 — Simplified subagent inventory

Provide a complete required subagent list and old-to-new mapping. Start with four roles: **explorer, planner, worker, reviewer**, with small typed modes where required. Explorer gathers evidence; planner proposes a plan; worker implements/probes/resolves conflicts; reviewer reviews a plan/candidate or audits evidence. Preserve meaningful privilege differences and independent review.

For each role/mode specify purpose, input/output schemas, consumed/produced artifacts, tool and MCP policy, model configuration, failure/abstention result, and lifecycle. Avoid recreating ten separate protocols under four labels. Additional roles require a concrete capability or reasoning boundary that cannot be represented safely by these roles.

### R29 — Simplified command inventory

Provide separate complete inventories for user-facing agent commands, administrative CLI commands, MCP tools, and host-only protocol operations. Start with four workflow commands: **begin, advance, review, upstream**. Fold phase continuations and plan follow-up into arguments/state-driven execution, and planner/reviewer catalogs into ordinary metadata.

Map every old command to a retained concept, merged behavior, or explicitly dropped behavior. Define invocation syntax for Claude, Codex, and Pi without duplicating semantic instructions. Do not turn every internal state transition into a user command.

### R30 — Dispatch without narrative forwarding

The governing session supplies a role/mode, item IDs/revisions, artifact handles, bounded typed guidance references, request identity, and execution limits. Host code—not the governing LLM—loads prompt assets, fetches narratives, resolves previous results, validates/assembles inputs, and invokes the selected harness.

Full results and logs are captured and stored outside parent context. The parent gets a bounded typed projection: status, affected IDs/counts, next action, short blocker, evidence/result handles, and explicit omitted-detail indicators. It can inspect a bounded section when reasoning requires it. It can pass a result handle directly to another role, or preview/apply a stored typed ledger proposal by handle, without reconstructing that content.

Make start and proposal application idempotent. Completed results are repeatably readable; avoid one-shot output consumption. Invalid role results are quarantined as failures, never coerced into success. A child result cannot authorize its own ledger writes or broaden scope. Distinguish process completion, schema validity, observed checks, and semantic acceptance.

Validate context savings using rendered tool schemas and captured parent input/output across small and large child payloads. With the same member set, normal parent launch/result traffic must stay bounded independently of full narrative size. Verify child-to-reviewer chaining without a parent full read. Output-size caps alone are insufficient if the orchestrator must re-read everything before continuing.

## Required design artifacts

Produce files in the new project, with a short navigation document. Use the established docs layout or `docs/drafts` if none exists. The complete package must contain:

1. **Architecture and decisions:** component ownership, DI/service boundaries, trust model, main data flows, alternatives rejected with concrete reasons, and explicitly unresolved decisions. Include a small diagram and a requirement-to-design-to-verification matrix for R01–R30.
2. **Baboon contracts:** actual `.baboon` files for browser/server messages and shared domain/protocol types, codegen commands/pins, version negotiation, errors, and example exchanges. Generate Scala and TypeScript to validate the definitions. Distinguish successful generation from compilation and an actual cross-language round trip.
3. **MCP contracts:** complete input/output JSON Schemas and descriptions for every exposed tool/profile; examples of success, invalid input, authorization denial, conflict, pagination, and uncertain retry. Specify how these derive from or map to Baboon without conflicting domain definitions. Validate examples with a schema validator.
4. **Ledger/persistence contracts:** all 14 content/status schemas, common metadata, relation taxonomy/inverses/cardinalities, typed citations, full history representation, PostgreSQL DDL/indexes/constraints, transaction algorithms for counters/claims/refs/previews, and example records. Validate DDL on isolated PostgreSQL; clearly label unexecuted algorithms.
5. **Harness/process package:** exact subagent and command inventories; role contracts and prompt ownership; three harness adapter designs; nine parent/child routes; scoped MCP/tool configuration; local supervisor lifecycle; handle-based dispatch, results, proposal application, and failure flows. State which capabilities were verified by documentation, local help, or actual experiment.
6. **Feature/process invariants and verification:** what is guaranteed by types, by boundary validation, by transaction/claim checks, by automatic tests, and by LLM/human evaluation. Include a small concrete consumer-project corpus and review rubric.
7. **Implementation milestones:** small vertical slices with deliverables, dependencies, explicit exit checks, real-harness evidence from the first usable slice, and an early native-image/stack feasibility check. No milestone is complete because a mock implementation or narrated demonstration exists.

The design must include at least these worked examples:

- A user enters an idea, then the process creates a goal and milestone-organized work without binding that milestone to the idea.
- A reproduced defect produces hypotheses/research, then a fix goal and reviewed implementation.
- Two sessions race to claim related work; only the valid owner proceeds; an expired worker's late result is rejected.
- Two sessions hold different valid task claims and integrate candidates based on the same target revision. Target advancement cannot cause lost updates or shared index interference; the resulting combined candidate receives applicable validation/review. Git success followed by failed ledger acknowledgement is reconciled without duplicate integration.
- A fused job shares work but produces separate member outcomes; conflicting requirements cause an explained split.
- A Claude parent dispatches a Codex worker, then passes its result handle to a Pi reviewer without reading/copying either prompt or full output.
- A child emits a result but never exits, or a nested process keeps stdout open; bounded supervision reports the right outcome while the parent stays responsive.
- A WebSocket disconnect crosses a committed mutation; reconnect restores a correct view without a duplicate write or lost change.
- A termination preview encounters shared work and is then invalidated by a new descendant before apply.
- A new project with no gate configuration never receives CQ's own build command; explicit consumer validation works.

## Verification principles

Use named types and closed sums to make invalid internal states unrepresentable where practical. Required dependencies and values must be explicit. Validate wire/config/database boundaries and report typed errors. Do not silently convert an invariant violation into a success state or hidden fallback.

Prefer public behavior tests. Put external systems behind narrow interfaces; run the same contract and consuming-service scenarios against hand-written dummies and production adapters through distage activation. Add real PostgreSQL checks for isolation, locking, uniqueness, rollback, and serialization behavior that a dummy cannot prove. Missing required infrastructure must be reported as a skipped/failed required leg, not a pass.

Test process supervision with controlled fake executables for hangs, partial output, descendants, cancellation, and exit races; additionally run real harness evaluations. A fake harness establishes plumbing, not agent process quality. Keep live-model evaluations separate from deterministic checks and preserve the evidence for human review.

Test behavior that can regress; do not reproduce the implementation in tests, multiply harness/model combinations without a purpose, or add token/line-count limits as substitutes for semantic correctness. Measure query amplification, context traffic, and latency where the requirements depend on them.

## Keep the design small

Start with the simplest mechanism satisfying each invariant. Account for every persistent table, service, role, command, tool, and lifecycle state. Prefer one canonical representation and one transactional path to mirrored mutable state and recovery protocols.

Do not add an old-data importer, TUI, custom ledgers, a server-side agent scheduler, mandatory language-specific repository analysis, a hostile-code sandbox framework, or infrastructure for indefinitely surviving child jobs. Existing ponygirls configuration should remain a reusable dependency, not become CQ-specific base behavior.

Ask the user only about unresolved requirements that materially change behavior, scope, or acceptance. Investigate empirical uncertainties first. Provide a concrete recommendation and explain its consequence. Do not present the brief's recommendations as user-confirmed decisions.

Conclude with what was produced and validated, the concrete remaining uncertainties, and the smallest next implementation milestone. Do not call the application implemented or the full system verified after a design task.
