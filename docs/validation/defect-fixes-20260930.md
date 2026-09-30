# Defect corrections through the CQ workflow — 2026-09-30

The user asked for all open defects that need no operator input to be fixed, driven through CQ's own governing sessions in recorded terminals so that workflow problems would be found. Evidence root: `/srv/nvme/tmp/cq4-defect-fixes-20260930`; session recordings under `/srv/nvme/tmp/cq4-auto-driver-planning/gov/fix-*.cast`.

## Corrections

| Defect | Route | Commit | Correction |
| --- | --- | --- | --- |
| D76 checks cannot start PostgreSQL in managed workspaces | direct | `bc474e2` | The check fixture no longer creates a Unix socket (workspace paths exceed the 107-byte limit). Reproduced with a 183-byte path. |
| D67 documented Claude launch never loads the CQ host | CQ session `fix-claude` | `c51aa7a`, follow-up `6b2ce4a` | `cq configure claude` records the approval in `.claude/settings.local.json` and removes a stale `disabledMcpjsonServers` entry; the documented launch is `--setting-sources project,local`. Verified live: the host loads with no dialog. |
| D69 alternatives shown twice | CQ session `fix-ui2` | `24c0e31` | Each alternative appears once with a Pick control before it. |
| D71 item pane cannot be closed | CQ session `fix-ui2` | `b6eb27a` | Close control plus Escape from the results; also fixed a History-dialog race that made a browser step intermittent. |
| D72 pane stays open after an unrelated filter | CQ session `fix-ui2`, follow-up | `52974f5`, `6b2ce4a` | Fully loaded results without the selected item hide the pane; an open editor or item dialog keeps its item during live refreshes; focus returns to the results. |
| D74 planners cannot assign milestones or cross-goal links | CQ session `fix-core2` | `129c69a` | Planner selection may include blocked Tasks, a rooted milestone and Tasks from different Goals, without the execution independence check; five new tests; linking to a milestone outside the roots stays refused. |
| D75 misleading "reason required" message | CQ session `fix-core` | `483ea7c` | Over-long reasons report the limit and the received length. |
| D68 archive selects items that open work depends on | direct | `78ca2e1` | A terminal item is retained while any related item (either direction) is unarchived and not terminal; the archive dialog uses the server preview (`ReadSelection.ArchivePreview`) and lists retained items with their open relations; the server refuses such members. |
| D85 title-only Ideas rejected | direct | `78ca2e1` | Ideas, Defects and Goals need only a title; narrative fields may be empty and bounded. |
| D86 faults shown as JSON | direct | `78ca2e1` | Faults render as text everywhere in the browser. |
| D77 workers start from the session base, not the current target | direct, cross-cut branch `fix/workspace` | `d3cb557` | Fresh workers start from the target's current head (`ExecutionBase.fresh`) and integration expects the candidate's actual base (`ExecutionBase.expected`); the server-side base equality check is removed. |
| D84 opaque integration commits | direct, `fix/workspace` | `d3cb557` | Candidate commits carry the member titles as subject and `CQ-Attempt`/`CQ-Integration` trailers (`CandidateMessage`). |
| D79 reviewers cannot read worker evidence | direct, `fix/evidence` | `4200882`, `426a060` | Text files under `.work/evidence/` or named in the report's `evidence` list are retained as `ArtifactKind.Evidence` result artifacts (32 files, 256 KiB each, 2 MiB total); reviewer prompts point to them. The report field is required: a report without it is rejected like any other malformed report and the partial-work capture below keeps the tree. |
| D78 a killed or failed child loses its work | direct, `fix/evidence` | `426a060` | The host captures the workspace diff of a child that fails, is cancelled or exceeds its deadline as a `PartialWork` artifact referenced from `DispatchStatus.partial`. |
| D81 Planners drop operator requirements | direct, `fix/planning` | `bef7d62` | The governing request reaches Planner and Worker children as a bounded `operatorRequirements` section; the plan prompt carries each requirement into acceptance criteria and checks the target's log before proposing an already-fixed defect. |
| D82 worktrees are never cleaned | direct, `fix/planning` | `029e727`, `3c12afb` | Settled attempt workspaces are removed at session end after re-verifying their identity (mismatches are quarantined, failed children are kept for inspection); `cq host` prunes stale registrations at startup. |
| D80 evidence attached to a Task invalidates its pending result | direct, `fix/ledger` | `5fbd52a` | A result stays applicable while the members' drafts are unchanged across reference-only revisions (`RevisionEquivalence`, historical drafts at the six host comparison sites and the server-side integration reservation); integration appends "Integrated …" to the recorded result instead of replacing it. A revision bump while a child is running is still rejected at admission. |
| D83 claims, one-child sessions and encoded result reads | direct, `fix/ledger` | `296dbe2` | Claims last up to 30 minutes (the host already renews claims that cover running children); artifact page faults state the 8192 code-point limit; a session may run four children with disjoint members. |

The workflow defects D77–D84 were corrected directly on four parallel branches from `78ca2e1` (the user stopped the governing sessions to cross-cut them and run one gate), each with failing-then-passing suites, and merged into `main`. Not corrected: D70 (not reproduced by two independent probe sets; needs the operator's description of what stayed stale) and D73 (harness versions; per-harness configuration is reserved).

Each CQ-routed fix had a failing-then-passing check recorded by its worker (question, workspace and contract suites), an independent Codex review, and the configured `cq-ui` check on the integrated candidate; the governors also ran the dummy and PostgreSQL suites on the exact integrated trees (`operator-rehearsal`-style research records R4–R9 in the live project). The direct fixes have reproduction evidence under `d76`, `d67-followup`, `d67-live`, `d72-editor` and `d68` in the evidence root.

## Workflow findings

The sessions surfaced 42 observations (`workflow-observations.md`). Filed as D77–D84 and I18–I21: workers start from the session base rather than the current target, so every advance of `main` cost a combine cycle; a killed child loses its work; reviewers cannot run test suites or read worker logs; evidence attached to a Task invalidates its pending result; Planners drop operator requirements; worktrees are never cleaned; claims, one-child sessions and encoded result reads; opaque integration commits; the fixed child deadline (I21; the setting is now at the 23-hour cap the host accepts). The previous candidate review's residual items were corrected in `78ca2e1`. Observation 42 (an sbt 2 `testOnly` with several distage suites listed explicitly runs only one of them) was reproduced in a minimal project and reported as [izumi#2361](https://github.com/7mind/izumi/issues/2361); `dev/check` uses patterns, which are unaffected, and explicit runs use one suite per invocation.

## Independent review of the cross-cut

A separate Claude reviewer read the range `78ca2e1..6524913` without running code and reported six majors, a rollout note and seven minors. Corrected, each with a failing-then-passing check unless noted:

- `CandidateWorkspace.verifyBase` applied the session-base ancestry rule to the target head, so a fresh dispatch failed with an opaque Git error whenever the operator's checkout had diverged from the integration target (D77 regression); the target head is now accepted as a fresh base and other bases must descend from the session base with a named error (`CandidateWorkspaceLocal`).
- `LedgerService.renew` replaced the expiry, so the host's three-minute renewals of running work shortened a thirty-minute governor claim to three minutes; a renewal never shortens a lease now (`ClaimCoordinationDummy`), and the claim tool text and `claims.md` say so.
- Candidate capture staged `.work/` unless the project ignored it; capture now excludes it (`CandidateWorkspaceLocal`).
- An evidence directory the host could not walk or read failed the whole worker result; it is omitted like an invalid path (`WorkspaceEvidenceLocal`).
- The cohort budget check ignored the operator-requirements section that assembly adds, so a near-budget cohort could be offered and then refused at assembly; the planner now measures the delivered section (no separate test; covered by compile and the existing selection suites).
- Worktree creation and removal ran under the ten-second bound of the other Git probes; they have a five-minute bound.
- Minor: duplicate recorded ids in `RevisionEquivalence` are rejected; `PartialWork.diffTruncated` is true only when a diff was cut; a stray test `println` is removed; the plan prompt no longer tells Planners to run `git log` (their workspace tool has no history read) and points them at Resolved Defects and workspace files instead.

Accepted as documented rather than changed: `WorkspaceRepository.quarantine` does not refuse a `Removed` record (unreachable in the current ordering); the evidence walk may stat up to 10 000 entries per named directory (bounded); `cq host` startup prunes stale worktree registrations repository-wide. Not verified by the reviewer or here without a live run: the job supervisor's one-second acknowledgement under four concurrent children.

Wire scope of this release: `ChildResult.evidence`, `WorkMember.evidence`, `ChildInput.operatorRequirements`, `DispatchStatus.partial` and `SessionCommand.Workflow.operatorRequirements` are required fields. Result and Input artifacts and pending child publications recorded before this release no longer decode when a new dispatch, review or integration references them (lazy failures with the codec message; nothing decodes them at startup or in the browser), and the generated harness commands (`.claude/commands/cq/*.md`, Codex and Pi equivalents) must be regenerated with `cq configure <harness>` after installation or `/cq:begin` fails at decode. No compatibility adapter is added (`AGENTS.md`).

## Verification of the delivered tree

Gates on the merged tree (`gates/`): fast, PostgreSQL and full native (fresh tracing), then the scoped installed check, the update rehearsal and the injected-rollback rehearsal; independent delivery review; installation by the operator and actual-hostname verification are recorded below when done.
