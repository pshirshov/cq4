# Agent roles, real inputs and protocol

Inspected 2026-09-28 against implementation `39f4d29` and retained native consumer executions; the supervisor/dispatch implementation was rechecked after the evaluation corrections. The recorded prompt/report examples in sections 1–4 are from batch executions. The accepted interactive mode in section 5 is implemented and installed with native/package verification; see [current evidence](../validation/attached-host.md).

## 1. Inventory

CQ has **four dispatched child roles and one governing model role**. A role is independent of its harness/model: each can use a configured Claude, Codex or Pi route.

| Role | Modes | Job | Final model report |
| --- | --- | --- | --- |
| Governor | Optional Begin, Advance, Review or Upstream workflow | Select work, acquire claims, dispatch by handles, apply proposals and request reviewed integration | Interactive: normal user-facing reply. Batch: `GoverningReport`, `{"summary":"…"}` |
| Explorer | Investigate, Research | Inspect evidence, distinguish explanations, identify uncertainty and useful experiments | `ChildReport.Evidence` |
| Planner | One mode; planning and cohort compatibility are two uses | Propose ledger changes or assess a complete group's ability to share implementation | `ChildReport.Plan` |
| Worker | Implement, Probe, ResolveConflict | Execute/edit in an isolated worktree; implement, experiment or resolve a prepared combination | `ChildReport.Work`; Probe returns `Evidence` |
| Reviewer | Candidate, Plan, Audit | Independently assess an exact candidate, proposal/compatibility assessment, or evidence | `ChildReport.Review` |

`Human` and `Collector` also appear in the authorization enum. They are operator and host identities, not model agents. The supervisor, guardian, cohort selector, input assembler and usage collector are program components. There is no separate researcher, investigator, merger, tester or cohort-manager agent. The release evaluator's independent assessment uses another managed Governor/Reviewer run; “assessor” is usage attribution, not an additional CQ role.

An existing interactive assistant is the **Governor** when configured with `cq configure`. The exported four workflow commands call its harness-owned CQ host directly. Batch `cq run` still launches a managed Governor. Begin/Advance/Review/Upstream are workflows, not four more agents. In either form the Governor dispatches and integrates; it never edits, builds, tests or runs checks in the operator's checkout, which is its workers' integration target, and it records a Defect with the evidence and stops when a host or tooling defect blocks the workers (D91). Development-time Astra reviewers of CQ itself are outside this product inventory.

Sources: [role/mode/report contracts](../../models/cq-api.baboon), [prompt selection](../../host/src/main/scala/cq/host/ChildInstructions.scala), [workflow entrypoints](workflows.md).

The dispatched role modes are indexed by the [agent catalog](../../server/src/main/scala/cq/server/AgentCatalog.scala): one flat entry per `DispatchWork` mode with its canonical prompt and input/output schemas, each harness's effective prompt, output schema and enabled/disabled MCP and built-in tools, and an authored typed [input and output example](../../server/src/main/scala/cq/server/AgentExamples.scala). The catalog owns none of these facts. It reads the prompt resources, the generated schemas and the tool-permission functions, and child launch builds its harness invocation from the same entry. Update the examples whenever the model changes; `AgentCatalogLocal` validates them against the generated schemas.

## 2. What “the initial prompt” contains

The host supplies both **instructions** and **input data**. A short user request alone is not the full input.

An attached Governor starts with its existing interactive conversation. Its first CQ call is `session Context`, which supplies governing instructions, project identity, routes, checks, limits and the complete argument guide where needed. `session Workflow` returns the selected workflow instructions and scope. It does not receive a second host-authored batch Governor prompt or emit a `GoverningReport`; child inputs/reports remain as described below. The complete retained examples in section 3 are batch examples.

For a batch Governor, instructions come from `SupervisorProgram.Instructions`. Its stdin is a generated `GoverningInput` with project identity/endpoint, configured routes, named checks, execution limits, optional integration target, the user's request, and optional typed workflow plus its installed instructions. It receives check names; child execution input contains the configured check commands.

For every child, the host loads the role/mode resource and constructs:

```text
ChildExecutionInput
  input.project
  input.request     exact revisions, work/mode, harness, fence, limits, context handles
  input.members     complete assigned ItemViews, including acceptance and relationships
  input.guidance    complete contextual ItemViews
  input.artifacts   resolved metadata and full bodies
  input.previous    complete previous ChildResult, or null
  base              exact workspace Git commit
  checks            configured named command vectors and limits
```

The Governor passes handles. The host resolves them, reads the child prompt and prior result, and supplies them directly to the child. The child can still receive substantial context; the saving is that full prompts/results do not routinely pass through the Governor's context. No measured net token saving is implied.

Harness-specific delivery:

| Harness | Instructions and final-output mechanism |
| --- | --- |
| Claude | `--system-prompt`, JSON stdin; native `--json-schema` structured output; streamed events collected by the host |
| Codex | `developer_instructions`, JSON stdin via `exec … -`; output schema and final-message file; the host adds canonical argument schemas for affected large MCP tool schemas |
| Pi | `--system-prompt`, JSON stdin; output schema appended to instructions; bundled extension exposes the MCP tools; the host validates the final JSON |

Only Workers receive native shell/edit tools. Other roles use their CQ tools. The harness adapters disable unrelated native subagents and other capabilities according to the pinned harness's controls. These are the implemented cooperative-agent restrictions; they do not provide hostile-worker isolation.

Sources: [governing assembly](../../server/src/main/scala/cq/server/SupervisorRole.scala), [child assembly](../../server/src/main/scala/cq/server/ChildRunner.scala), [three adapters](../../host/src/main/scala/cq/host/HarnessAdapter.scala), [native schema adaptations](../../server/src/main/scala/cq/server/McpSchemas.scala).

## 3. Complete real examples

The linked JSON files contain **actual saved instructions, complete initial input, final model report, host result and compact receipt**. They are documentation bundles, not a new wire envelope. Each includes original paths, attempt identity, artifact identities and SHA-256 provenance. JSON is formatted for inspection; the original semantic values and instruction text are preserved. No private MCP configuration or credentials are copied. Historical endpoints are evidence, not live addresses to call.

| Example | Actual route and assignment | Observed report / host projection |
| --- | --- | --- |
| [Governor](../examples/agent-protocol/governor.json) | Claude / `claude-opus-5-5`; advance the Python word-frequency pair through independent review | Summary plus result handle `e6f3e5ff-c4b5-392d-bd82-c1df6aea54a6`; no integration target |
| [Explorer](../examples/agent-protocol/explorer.json) | Codex / `gpt-6-sol`; Investigate `D1@1`, an ASCII delimiter defect | One Findings member, citations, explicit uncertainty and requested executable probes; next `Plan` |
| [Planner](../examples/agent-protocol/planner.json) | Codex / `gpt-6-sol`; assess `T1@2` and `T2@2` together, with `G1@3` guidance | Two Assessed members, Compatible whole-group assessment, acceptance/check mapping, `proposal:null`; next `ConsiderGrouping` |
| [Worker](../examples/agent-protocol/worker.json) | Claude / `claude-opus-5-5`; Implement the same pair with the Planner artifact resolved into input | Two CandidateReady members; host captures `ddb1ea6f42f4ffa8867fddafce6f1ea75085a3b6`, records checks; next `Review` |
| [Reviewer](../examples/agent-protocol/reviewer.json) | Pi / `gpt-5.5`; Candidate review with the entire Worker result supplied as `previous` | Two Accepted members, no findings, `proposal:null`; next `ConsiderAcceptance` |

These examples come from the retained native release corpus, not newly simulated runs. Explorer comes from the defect process; the other four come from one cohort execution. Other live corpus modes include Explorer/Research, Worker/Probe and Reviewer/Plan and Audit. This example set does not claim a real-model ResolveConflict execution; that mode's installed instructions and deterministic combination checks are documented separately.

### Governor

The real request begins:

> Advance the two existing related tasks under the supplied goal through independent candidate review. Keep their exact revisions for deferred independent assessment; do not create replacement tasks or edit their acceptance.

The request goes on to require a Codex compatibility Planner, Claude Worker, Pi Reviewer, a fresh configured check, complete-group claims and handle forwarding. The complete request and workflow are in the example's `initialInput`; `systemInstructions` contains the actual higher-priority instructions.

The Governor returns only a bounded `summary` string. Question IDs, choices, blockers and remaining work currently live in that prose; they are **not separate structured fields**. The host adds process success, usage-delivery state and the result handle in `SupervisorReceipt`.

The saved Governor summary contains an incorrect inference: it treats the reviewer's equal base/candidate as suggesting the Worker made no new commit. The Worker host result shows base `b31591b99b83a3caa54d39160e8498463ff2feaa` and candidate `ddb1ea6f42f4ffa8867fddafce6f1ea75085a3b6`. A reviewer starts at the candidate, so its equality is expected. The example preserves the actual statement; model narrative is not authoritative Git evidence.

### Explorer

The installed instruction starts “You are CQ's explorer” and permits scoped CQ/workspace reads only. The example input includes the actual defect, reproduction text and fixture revision. Its final report identifies `re.findall(r"\w+", text)` as the likely cause, cites source files, says it could not execute the test, and requests a Worker probe. It marks authored evidence `ModelDeclared`.

Both modes return one Evidence member per assigned item: `Findings`, `Inconclusive`, `Blocked` or `Failed`, with summary, evidence, uncertainties and requested probes. Investigate emphasizes competing explanations; Research answers an empirical question. They share [installed instructions](../../host/src/main/resources/cq/prompts/explore.md).

### Planner

The real assigned task includes:

> Read UTF-8 stdin, tokenize maximal ASCII [A-Za-z]+ runs, normalize ASCII lowercase, count and sort by descending count then ascending word; empty/no-word input emits nothing.

The complete input also contains the second task's independent criteria and the goal's specification. The Planner reports both members `Assessed`, a shared objective, dependency/interference analysis, and a criterion-by-criterion check/inspection mapping. It does not propose a mutation in this run.

For planning, each member is `Proposed`, `Assessed`, `Blocked` or `Abstained`. A Proposed member requires a typed proposal using `Create`, `Replace`, `Produce` or `Reference`. The Planner supplies no caller authority, request identity, expected revision or claim fence inside the proposal. The Governor previews/applies its stored result handle. Compatibility reports assess entire groups of two to four assigned tasks; labels or a shared passing test are insufficient. [Installed instructions](../../host/src/main/resources/cq/prompts/plan.md).

### Worker

The real Implement input contains both complete tasks, guidance, the resolved Planner report, the workspace base and `consumer-oracle` command configuration. Its final Work report has one `CandidateReady` result per task and describes the source/tests it wrote. The model does not supply its own authoritative candidate commit or validation evidence: the host adds those after capture/checks.

Implement and ResolveConflict return `CandidateReady`, `Blocked` or `Failed` for every member. Probe returns Evidence and publishes no implementation candidate. Workers may edit/run commands, but may not mutate CQ ledgers, dispatch children, commit, move refs or integrate. A resolver with a Combination artifact must read **every page of `workspace.MergeReport`** before deciding how to resolve it. A conflict can exist without unmerged file entries.

Installed instructions: [Implement](../../host/src/main/resources/cq/prompts/implement.md), [Probe](../../host/src/main/resources/cq/prompts/probe.md), [ResolveConflict](../../host/src/main/resources/cq/prompts/resolveconflict.md).

### Reviewer

The real Candidate input embeds the preceding Work report, exact candidate and host validation handles. Its final report is a `Review` with exactly one `Accepted` entry for each assigned task, empty findings, and `proposal:null`. A fresh configured `consumer-oracle` execution is retained by the host.

All review modes require one `Accepted`, `ChangesRequested` or `Blocked` verdict per member. Non-accepted verdicts require findings. Candidate cannot propose ledger changes. Plan/Audit may include an advisory follow-up proposal only when there are ChangesRequested members; its existing mutation endpoints must belong to those members. No Reviewer edits files, mutates ledgers, dispatches children or integrates.

Candidate may request named host checks. Requesting a check is not universally mandatory; the concrete assignment can require it, as this real example does. Once requested, it must reach terminal published evidence before the Reviewer returns. Plan/Audit cannot execute checks. [Candidate instructions](../../host/src/main/resources/cq/prompts/review-candidate.md); [Plan/Audit instructions](../../host/src/main/resources/cq/prompts/review-proposal.md).

## 4. Endpoints and required calls

Managed batch Governors and children use two MCP servers. Both use `POST /mcp` with scoped bearer credentials:

| Server | Location | Agent-visible tools |
| --- | --- | --- |
| `cq` | Durable CQ server, configured project endpoint | Governor: `search`, `read`, `graph`, `change`, `apply`, `claim`, `usage`; children: `search`, `read`, `usage` |
| `cq_host` | Private loopback port owned by this supervisor process | Governor: `dispatch`; children: `workspace` |

An attached interactive Governor uses one harness-owned **stdio** MCP connection named `cq`, exposing the seven Governor domain tools plus `session` and `dispatch`. Its dispatch calls use `cq.dispatch` in place of `cq_host.dispatch` in the table below. Pi presents these as `cq_session`, `cq_dispatch`, etc. Children still use the two scoped HTTP servers above. The Pi extension's private `cq/piUsage` RPC carries native usage metadata; it is not a model tool or a required agent call. `session` also carries the [driver](driver.md) operations an attached session may perform itself: `Bind` with a hook-minted token and the read-only `Driver` status. Starting and parking a driver are not model tools; the Pi extension's private `cq/driver` RPC and the CQ hook commands are the only callers.

These are tool names within MCP, not paths such as `/dispatch`. The harness/bridge performs MCP initialization and discovery. The model invokes tools through its harness. For example, a workspace read has this transport shape:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"workspace","arguments":{"Read":{"path":"README.md","offset":0,"limit":8192}}}}
```

Claude presents names such as `mcp__cq_host__workspace`; Pi registers `cq_host_workspace`; Codex exposes configured MCP tools through its tool host. Exact presentation differs; server/tool identity and generated argument contract do not.

### Required sequence, conditional on the work

| Actor / operation | Protocol obligation |
| --- | --- |
| Attached Governor starting work | `cq.session.Context` → consume instructions and project identity → `cq.session.Workflow` with a fresh activation UUID and explicit typed scope; `token` is `null` unless the invocation is a driver directive carrying `--start-token` or `--resume-token`. Retain the ID/request for identical retries. |
| Governor dispatching a workflow child | `cq_host.dispatch.Select` with explicit roots/work/context → `cq.claim` for all members of one choice → `cq_host.dispatch.StartChoice` with choice ID, route and current fence → `Status` until settled. Selection itself acquires no claim. Reuse/renew a valid owned claim; do not acquire overlapping claims blindly. |
| Batch Governor in a direct non-workflow run | May use exact-assignment `dispatch.Start`; managed workflows reject that bypass. |
| Governor applying a Planner proposal | Obtain the process-required independent Plan review; inspect `cq.read` selection `Proposal`, then `cq.apply` by result handle under current authority. Do not copy drafts into `change`. |
| Governor correcting a rejected result | Forward result/review handles to the next Planner/Worker; obtain fresh independent review of the corrected identity. |
| Governor integrating | `PrepareIntegration` with reviewer handle → poll `IntegrationStatus` → inspect frozen preview → `Integrate`; only `Recorded` establishes domain recording. No configured target means no integration. |
| Governor handling an advanced target | `Combine` / `CombinationStatus` → Worker/ResolveConflict with prepared plan → fresh Candidate review → fresh integration. |
| Explorer / Planner / any child | Use `cq.read/search` and workspace `Entries/Read` as evidence requires. There is no mandatory ceremonial read or usage call. Follow pagination when the evidence needed is paginated. |
| Worker/ResolveConflict with Combination | Read all `workspace.MergeReport` pages. |
| Reviewer/Candidate requesting a check | `workspace.Check` with a configured name; repeating it polls the same execution. Wait for `Completed`, inspect actual evidence, then return. Only one check may be active. |
| Every child on completion | Return the correct final JSON branch, covering every assigned member exactly once. **No model-facing “submit result”, “upload usage” or “mark done” endpoint is required.** |

Protocol/process obligations and enforcement differ. For example, instructions require meaningful evidence inspection; a syntactically valid read cannot prove that the model understood it. Claims, role permissions, exact revisions, workflow phase/scope, result shape and host admission are program checks. A passing check or Accepted review is not by itself completion or integration.

`Status` returns at most 12 KiB: identities, phase/process state, outcome counts, next action, a bounded blocker, result handle, `detailsOmitted`, delivery state and the child's `workspace` (admission and retained directory, absent before a workspace exists). It does not normally return successful narrative reports. Full `ChildResult` contains the report, base/candidate and validation; downstream roles receive that full result through host materialization. Explicit bounded `cq.read` artifact access permits necessary drill-down.

The host, not the model, calls `/api/grant`, `/api/artifact`, `/api/usage`, `/api/admission` and `/api/integration` as needed. It registers attempts, assembles/stores inputs, maintains ownership, captures Git candidates, runs configured checks, validates/publishes results and collects usage. `/api/call` is the typed HTTP command interface used by host/CLI clients; it is not an extra completion hook a child must call. [Transport routes](../../server/src/main/scala/cq/server/Transport.scala), [private dispatch/workspace routing](../../server/src/main/scala/cq/server/LocalControl.scala), [compact projection](../../host/src/main/scala/cq/host/DispatchProjection.scala).

## 5. Why `cq run codex …` exists

Batch syntax remains `cq run codex --settings … --input …`; the same executable has server, client, supervisor and attached-host roles. Interactive use starts the harness directly after `cq configure HARNESS --settings …`. The shell script in the quickstart merely starts PostgreSQL and `cq serve`. It is optional convenience and is separate from the managed-session launcher discussed here.

`cq run` establishes a local **execution owner** with the consumer repository, integration target, route configuration, credentials and session directory. It starts the private MCP service, creates isolated worktrees, materializes prompts/results, applies harness restrictions, owns process deadlines/termination, captures candidates/checks, and journals usage/publication for recovery. The durable server alone cannot safely infer that local context or access the consumer's Git repository and harness credentials.

In batch mode, the supervisor also launches the Governor as a batch child. When that Governor exits, its child hierarchy is terminated and reconciled. The private dispatch endpoint belongs to that managed session; it is not a persistent service that an arbitrary existing interactive session can attach to.

Thus the literal command spelling is not inherently necessary, but the execution-owner responsibilities must live somewhere. Connecting an ordinary unrestricted harness to the durable domain MCP alone does not supply local dispatch, host prompt assembly, isolated execution, review/integration or managed usage accounting.

### Options

| Design | User experience | Status / consequence |
| --- | --- | --- |
| Batch execution | Invoke `cq run` for a bounded unattended request | Implemented; launches and meters a separate Governor. |
| Local CQ host started and owned by the interactive harness | Start `codex`, `claude` or `pi` normally; that session acts as Governor through CQ tools | Implemented and installed; packaged native consumer routes and lifecycle checks pass. The host and managed children belong under the harness process, inside the same sandbox. No separate batch Governor or detached host daemon. |
| CQ server owns execution | Browser/CLI submits work; a server-side worker starts harnesses against configured repositories | Proposed and requires changing R21, which says the durable CQ server never starts/holds harness processes. Repository access, credentials and process ownership move to that machine. A separately owned worker could preserve the server boundary. |

The `host` distage role opens a fresh governing session beneath its native owner. Claude/Codex use project stdio MCP; Pi uses a project extension. `session Context` returns project, routes, checks, limits, session directory and instructions. `session Workflow` activates idempotent typed scope; dispatch uses retained choices and current claims. EOF, owner death, heartbeat loss and operation deadlines close admission and terminate the hierarchy. Retained publication can be replayed by `cq job upload`; reconnection never adopts uncertain processes. See [lifecycle and accounting evidence](../validation/attached-host.md).

The tradeoff is that CQ no longer controls the outer harness's startup flags, native tools or all of its usage events. Server permissions and child restrictions remain enforceable, but the interactive Governor could have unrelated shell/tools. Usage must distinguish observable child spending from unavailable outer-session spending, with harness-specific collectors where supported. Session attachment must not silently reuse old claims or result-publication authority.

### Accepted interactive lifecycle

User decision, 2026-09-28: “If I run harness directly and the rest exists under the harness process - it's just what I want. having cq run for other purposes is fine.”

Recorded in the `cq4` project as **K1 Adopted**, with **I1 Accepted**. Request, acknowledgement and readback are retained at `/srv/nvme/tmp/cq4-human-evaluation-20260928/launcher-decision`.

The selected execution tree is:

```text
directly started interactive harness (Governor, inside yolo)
└── local CQ host (started automatically by the harness integration)
    └── owned guardians and dispatched child harnesses
```

The durable CQ server/PostgreSQL remain separate services, reached over HTTP. “Under the harness” applies to local execution ownership; it does not move the durable database under the interactive process.

Implementation requirements:

1. Ordinary interactive startup requires no outer `cq run`, extra governing model session, or independently managed local daemon. The internal executable invocation is installed in the harness integration. Keep `cq run` for batch/unattended use.
2. The host inherits the harness's sandbox and filesystem visibility. Repository, session state and worktree paths must be available inside that sandbox; children remain within it. Keep the existing explicit isolation of child environments and credentials.
3. Separate the reusable execution/session services from `SupervisorProgram` launching and awaiting a batch Governor. Scope resources through distage lifecycle management. Interactive session identity must not fabricate a host-launched Governor process or depend on its final JSON report.
4. Install the interactive workflow instructions/tools into the existing session. Workflow entrypoints use its CQ tools directly; they do not silently invoke the batch wrapper. Prompt/result assembly, typed proposals and compact handle dispatch remain host responsibilities.
5. On owner exit or confirmed owner-connection loss, stop accepting work and terminate owned children within explicit bounds. Whole-hierarchy cancellation is accepted; preserve completed artifacts and publication journals. Frozen-owner/host failure detection and abrupt exits need actual lifecycle tests, not an assumption that process ancestry alone prevents orphans. A new connection receives fresh authority and does not adopt uncertain children or stale claims.
6. Keep managed-child permissions, result validation, idempotency and reviewed integration. The ordinary interactive Governor retains its own tools; CQ must not claim to restrict those tools through this attachment.
7. Collect child usage as today. Observe outer interactive usage where the native harness permits it, with separate attribution and explicit missing/unsupported coverage. Do not treat an unobserved outer session as zero cost.
8. Verify direct startup inside yolo on all three harnesses, actual child routes, compact prompt/result traffic, owner shutdown/failure, durable audit gaps and unchanged batch use. Native integration details and telemetry coverage remain unverified for the proposed mode until those checks pass.

The design is implemented and installed. Package/native lifecycle verification and the remaining human yolo trial are tracked in the [evidence record](../validation/attached-host.md). The UI/CLI redesign stays queued for the new CQ session.
