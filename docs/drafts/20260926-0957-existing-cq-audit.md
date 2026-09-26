# Existing CQ: evidence for the redesign

Inspected on 2026-09-26. This is a source audit of the supplied `../../../cq` and `../../../ponygirls` snapshots, plus one isolated runtime probe. It is not a claim that the old application or its full test suite was run. No source changes were made to either snapshot.

## What was measured

The count below includes `.ts`, `.tsx`, and `.md` files, excludes `node_modules`, and counts a file as test material when its name contains `.test.` or a path component is `test`. Generated files, fixtures, comments, and documentation are included where they match those rules. These are size observations, not quality scores.

| Area | Files | Lines | Test files | Test lines |
|---|---:|---:|---:|---:|
| `cq/nix/pkg/cq-ledgers/packages` | 1,270 | 458,102 | 889 | 269,893 |
| `cq/nix/pkg/cq-assets` | 104 | 5,250 | 0 | 0 |
| `cq/nix/pkg/pi-extensions` | 25 | 10,090 | 10 | 4,414 |

Examples of concentrated complexity: `ledger/src/workCohort.ts` has 3,081 lines; `ledger/src/mcp/ledgerTools.ts` 2,624; `ledger/src/store/postgres/PostgresLedgerStore.ts` 2,685; `ledger-web/src/App.tsx` 5,223.

The canonical capability inventory contains **68 MCP tool names**. The ordinary profile filters out 23 implementation-evidence tools, leaving 45; construction capabilities further affect advertisement. This is more precise than saying every session sees all 68. See [roleToolProfiles.ts](../../../cq/nix/pkg/cq-ledgers/packages/cq-config/src/roleToolProfiles.ts) and [ledgerTools.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/mcp/ledgerTools.ts).

Neither the line counts nor the tool count establishes that TypeScript caused the maintenance difficulties. Changing language can improve local contracts; it cannot eliminate distributed state, process supervision, or semantic uncertainty.

## Model and workflow

The canonical set contains **14 ledgers**. The redesign request says to preserve this set, which includes several ledgers not enumerated in its example flow.

| Ledger | Prefix | Present purpose |
|---|---|---|
| milestones | M | Organize work |
| ideas | I | Capture possible work |
| defects | D | Record failures and investigations |
| goals | G | Define intended outcomes and plans |
| tasks | T | Execute work |
| researches | RS | Answer empirical questions |
| hypothesis | H | Record candidate explanations |
| questions | Q | Ask for user decisions or requirements |
| decisions | K | Record choices |
| reviews | R | Record review verdicts |
| handoffs | HO | Record session outcomes and remaining work |
| operatorActions | OA | Track required human actions |
| memories | MEM | Retain project knowledge |
| upstream | U | Track external dependency defects |

Source: [constants.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/constants.ts), including `CANONICAL_LEDGERS` at line 705.

`Item` requires `milestoneId` and holds `fields: Record<string, FieldValue>`, where a field value is a string or string array. Several structured protocols consequently live inside strings. Ideas, goals, and memories use an ambient milestone instead of genuinely independent intake records. Schemas define status values, terminal values, dependency satisfaction, and transition maps. Examples include terminal states with no outgoing edges and a research result that must pass through `wip` before becoming `concluded`. See [types.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/types.ts) and `constants.ts`.

References appear in multiple forms: `sourceRefs`, `dependsOn`, `blockedBy`, `ledgerRefs`, `parentHypothesis`, `supersedes`, goal milestone arrays, and protected ownership fields. The common schema includes `worksetOwnerRef` and `worksetOwnerEdgeKind`. These represent distinct semantics, so replacing their containers with `refs` still requires explicit relation types and traversal rules.

The main sequencer advances investigation, defect-to-goal seeding, planning, research, implementation, and re-checking until quiescence. It uses derived readiness predicates and mandatory cohort admission. This useful intent can survive without the existing claim, manifest, attestation, and receipt choreography. See [advance.md](../../../cq/nix/pkg/cq-assets/commands/cq/advance.md).

## General-purpose behavior: a reproduced leak

[projectGate.ts](../../../cq/nix/pkg/cq-ledgers/packages/cq-config/src/projectGate.ts) defines a canonical gate of `bun run check` in `nix/pkg/cq-ledgers`. Its resolver accepts an explicit project gate, but uses CQ's gate when the declaration is absent. The source explicitly describes this as a compatibility choice.

The following probe ran from the supplied workspace root using the installed Node runtime:

```sh
node --input-type=module - <<'JS'
import { resolveProjectGate } from './cq/nix/pkg/cq-ledgers/packages/cq-config/src/projectGate.ts';
import { existsSync } from 'node:fs';
const implicit = resolveProjectGate(null);
console.log(JSON.stringify({input:null,resolved:implicit,existsInNewProject:existsSync('./cq4/'+implicit.cwd)}));
console.log(JSON.stringify({input:{argv:['python3','-m','unittest'],cwd:'.'},resolved:resolveProjectGate({argv:['python3','-m','unittest'],cwd:'.'})}));
JS
```

Captured output:

```json
{"input":null,"resolved":{"argv":["bun","run","check"],"cwd":"nix/pkg/cq-ledgers"},"existsInNewProject":false}
{"input":{"argv":["python3","-m","unittest"],"cwd":"."},"resolved":{"argv":["python3","-m","unittest"],"cwd":"."}}
```

This reproduces the resolver behavior, not an entire failed workflow. It warrants requiring explicit project validation commands and a visible unconfigured state in the replacement.

[workCohort.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/workCohort.ts) also imports the TypeScript compiler, parses imports and exported symbols, and resolves relationships using `package.json`. Inspection establishes language-specific assumptions in this cohort implementation. No non-TypeScript cohort experiment was run, so this audit does not claim a measured failure rate for other languages.

## Worksets, provenance, gating, and storage

| Observation | Evidence | Consequence for the redesign |
|---|---|---|
| Worksets participate in admission of ledger mutations, Git effects, and administrative operations. | [worksetStore.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/worksetStore.ts) | Transient selection and authorization need separate definitions. |
| Goal fields include plan generations, claims, drafts, and finalized manifests. | `GOALS_SCHEMA` in `constants.ts`; [planLifecycle.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/planLifecycle.ts) | Prefer ordinary editable records, short transactions, and reusable work claims. |
| Cohorts carry authority, repository, environment, witness, validation, reviewer, deployment, and finalization identities. | `workCohort.ts`; [implement/advance.md](../../../cq/nix/pkg/cq-assets/commands/cq/implement/advance.md) | Preserve per-member acceptance and shared work; remove proof machinery that becomes a second workflow. |
| Public provenance records last writer/session; an item revision helper computes a content digest. | `types.ts`; [itemRevision.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/itemRevision.ts) | Neither is a complete immutable version history. |
| PostgreSQL has separate active items, archived items, and archive pointers; item fields are stored as JSON text. | [schema.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/store/postgres/schema.ts) | JSONB current state plus immutable revisions and an archive attribute is a deliberate replacement. |
| Normal non-plan item updates have a targeted single-item path. Guarded task/goal updates call `readLiveTenant`; milestone updates also load tenant state. | [PostgresLedgerStore.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/store/postgres/PostgresLedgerStore.ts), lines 1336–1476 | The blanket claim that every mutation rewrites whole ledgers is unsupported. Require bounded reads as well as writes in the new implementation. |
| Project identity prefers explicit configuration, otherwise derives from a Git root commit; there are shallow-clone, empty-repository, and agent-worktree restrictions. | [projectKey.ts](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/projectKey.ts) | A persisted generated identifier removes dependence on Git history and directory placement. |

The inspected PostgreSQL schema and item types do not provide a general public revision history for every mutation. Private lifecycle journals and archived generations exist; this audit does not equate their existence with a complete history API.

## Existing dispatch features worth preserving

[dispatchRefAssembly.ts](../../../cq/nix/pkg/cq-ledgers/packages/cq-config/src/dispatchRefAssembly.ts) explicitly addresses the cost of an orchestrator reading narratives just to forward them. Its reference form is narrative-free; host code resolves records, prior criticism, and bounded guidance, then validates the assembled role input. This is directly relevant to the user's added token-saving requirement.

[compactDispatchProtocol.ts](../../../cq/nix/pkg/cq-ledgers/packages/cq-config/src/compactDispatchProtocol.ts) defines handles, separate input/result capabilities, prompt identities, deadlines, typed abort reasons, and a multi-stage lifecycle. [Codex result delivery](../../../cq/nix/pkg/cq-assets/fragments/codex/dispatch-result-delivery.md) requires a child to store its result and return only a handle. However, the parent-side `ConsumedDispatchResult` includes full output; a handle-only child reply alone does not prove that bulk output stays out of the parent's eventual context. The replacement must explicitly control both sides.

[dispatchTransportRouter.ts](../../../cq/nix/pkg/cq-ledgers/packages/cq-config/src/dispatchTransportRouter.ts) distinguishes same-harness native routing from process routing. Current [Codex](../../../cq/nix/pkg/cq-assets/fragments/codex/subagent-dispatch.md) and [Pi](../../../cq/nix/pkg/cq-assets/fragments/pi/subagent-dispatch.md) prompt fragments describe CQ-driven launch and settlement. The requested replacement moves process ownership out of the server while retaining reference-based input and stored output.

The current role inventory has ten dispatched roles:

`investigate-explorer`, `investigate-prober`, `research-explorer`, `research-experimenter`, `plan-advance`, `plan-reviewer`, `implement-worker`, `implement-reviewer`, `implement-conflict-resolver`, `implementation-auditor`.

The command directory has sixteen workflow commands: `begin`, `advance`, `investigate`, `investigate/advance`, `plan`, `plan/advance`, `plan/follow-up`, `plan-review`, `research`, `research/advance`, `implement/start`, `implement/advance`, `implement-review`, `planners`, `reviewers`, and `upstream`. Inventory: [agents](../../../cq/nix/pkg/cq-assets/agents), [commands](../../../cq/nix/pkg/cq-assets/commands/cq).

Do not merge read-only inspection with experiment execution merely to reduce role names. Capability differences remain meaningful even when phase-specific prompt variants can share a role contract.

## DI and frontend: preserve useful work

The old project already has a [LedgerStore interface](../../../cq/nix/pkg/cq-ledgers/packages/ledger/src/store/LedgerStore.ts), multiple adapters, hand-written in-memory implementations, and injectable ports. Calling it entirely devoid of DI would be inaccurate. Conversely, the [Pi dispatch extension](../../../cq/nix/pkg/pi-extensions/cq-subagent-dispatch/index.ts), lines 80–109, uses module-level mutable dependency overrides for tests. The replacement should make construction and resource lifetime consistently part of the distage graph.

[App.tsx](../../../cq/nix/pkg/cq-ledgers/packages/ledger-web/src/App.tsx) combines project selection, ledger loading, status/milestone filters, archive sections, mutation flows, dialogs, and live refresh. The [live client](../../../cq/nix/pkg/cq-ledgers/packages/ledger-live/src/index.ts) already has nonce heartbeats, retry limits, a recovery state, and explicit documented gaps. The redesigned UI should preserve truthful recovery behavior. No visual rendering was inspected, so particular visual glitches remain user-reported rather than reproduced here.

[ponygirls' harness factory](../../../ponygirls/nix/lib/mk-agent-harness.nix) and [Codex integration](../../../ponygirls/nix/hm/codex.nix) establish a useful boundary: base harness packages/settings belong in ponygirls; CQ contributes process assets and extensions. CQ should remain usable in unrelated consumer repositories.

## External sources checked

- The requested [distage example](https://github.com/7mind/distage-example/tree/9ffb0066281c9b9233bb38966c5880c52e4dccf1/bifunctor-tagless) was downloaded and inspected. It now separates `shared` and `jvm` sources. The launcher uses `LauncherBIO`, resources use `Lifecycle`, tests switch `Repo.Dummy`/`Repo.Prod`, and native builds use a constant plugin list. Its build uses **sbt 1.12.11**, defaults to Scala 2.13.18, and cross-builds Scala 3.3.7. It is a coding reference, not evidence that the requested sbt 2.0 stack already builds.
- [Baboon documentation/source](https://github.com/7mind/baboon/tree/e188119d03e30bfba495dae5a3e3450e6841f8e6) was inspected for Scala/TypeScript generation, service types, JSON codecs, and MCP generation. Compiler release 0.0.196 was downloaded and its help ran. No CQ schema has been compiled in this requirements task. Current generator options and runtime versions must be pinned together; main-branch documentation is not proof of every release's behavior.
- [Codex non-interactive documentation](https://developers.openai.com/codex/noninteractive/) establishes JSONL output and structured final results. Installed `codex exec --help` also exposes `--json`, `--output-schema`, sandbox choices, and `--ignore-user-config`. [Configuration documentation](https://developers.openai.com/codex/config-reference/) describes MCP allowlists and unattended approval policy. This establishes adapter primitives, not restart or watchdog guarantees.
- [Claude CLI documentation](https://code.claude.com/docs/en/cli-reference) documents print mode, tool restrictions, and strict MCP configuration. `--allowedTools` is an approval control; `--tools` controls availability. Exact adapter flags must match the pinned release.
- Installed `pi --help` establishes print/JSON modes, tool selection, extension switches, and independent config/session directories. Its help was inspected without launching an agent. Provider extensions need deliberate allowlisting; indiscriminately disabling extensions may disable the chosen provider.
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) and [PostgreSQL locking](https://www.postgresql.org/docs/18/explicit-locking.html) were checked. Transport session identifiers are not authorization; database transaction locks are not hours-long work ownership.

No live model calls, process survival experiments, native-image builds, end-to-end harness runs, or latency benchmarks were performed. Those are explicit early verification obligations in the proposed requirements.
