# Human evaluation: UI and CLI redesign

Intake: 2026-09-28. Project `cq4`, UUID `20eb436e-1a4d-4bb6-a4b4-d151e5c1dc04`. These are user-reported defects and requirements, not claims of independently reproduced behavior or completed implementation. Creation receipts and individual readback are retained at `/srv/nvme/tmp/cq4-human-evaluation-20260928/redesign-*`.

The user considers the current UI a prototype, not an acceptable release interface. Earlier technical gate results remain evidence of the behaviors those gates checked; they do not establish usability acceptance. M2/M6 human acceptance remains outstanding. D1–D8 are delivered and resolved. The user has accepted the interactive launcher design below and explicitly confirmed “Queue for the new CQ session”: implement this UI/CLI redesign batch in a new session using CQ discipline.

## UI acceptance requirements

| Report | CQ item | Required result |
| --- | --- | --- |
| 1 | D26 | Static status bar containing the keyboard guidance: Ctrl+K query; F6 next pane; Shift+F6 previous pane; results ↑/↓ move, Enter select, → detail, Escape return. Use outlined keycaps or accessible key icons, including Enter. |
| 2 | D27 | Fixed navigation-column width; remove its resize handle. |
| 3 | D28 | A plus icon immediately right of the project selector opens project creation. Remove the permanent name field and Create project button. |
| 4 | D29 | Remove Workspace heading. Navigation buttons have icons, left-aligned labels and right-aligned live item counts. Specify the count scope. |
| 5 | D30, D1 | Remove Items heading. Restore a sortable table with ID, type, title, status and severity columns; type may use an accessible icon. Useful initial batch, automatic bounded append, no manual page buttons. Sorting must have an explicit whole-query scope, not silently sort only loaded rows. |
| 6 | D31 | Horizontal and vertical content arrangements following the previous CQ UI. Inspect that reference before specifying the precise layouts. Persist orientation. |
| 7 | D32 | Semantic viewer and editor, with reusable components. Readable layouts for each content type, formatted evidence/resolution, no raw JSON as ordinary content. An edit action switches the same layout into editing in place. Preserve typed validation, drafts and conflict handling. Use CQ1 as the design reference. |
| 8 | D33 | New item opens a dialog using the shared semantic editor; keep existing selection/detail intact. The right pane is for viewing/editing existing content. |
| 9 | D7 | Correct all content-type switching paths. Exact additional report: `Draft storage failed: BaboonDecoderFailure: Unknown DefectStatus variant: Proposed`. It is reproduced on the old package and passes on the candidate alongside all 182 transitions among fourteen types. This correctness repair is part of the current delivery, separate from D32. |
| 10 | D34 | History dialog with a revision table and read-only semantic viewer for the selected revision. Clear revision metadata and restore action; no appended JSON node. |
| 11 | D35 | Ergonomic relationship editor: understandable type/direction and discoverable item selection. Preserve concurrency, preview and validation guarantees. Reproduce/inspect the current interaction before choosing controls. |
| 12 | D36 | Display item usage inline in structured tables or equivalent, preserving explicit unknown values. Remove the selected-item usage action and whole-project usage action from item detail. Navigation's Project usage opens a dedicated dialog. Keep views live. |
| 13 | D37 | Persist supported panel sizes and restore them after reload, clamped to the current viewport. Coordinate with fixed navigation width and persisted orientation. |

The coherent design unit is the semantic view/edit component set and the workspace shell. New-item, history and usage dialogs should compose those components. Inspect the existing CQ1 reference before implementation; do not reproduce the schema's nesting as the user interface. Verify the actual laptop layout and keyboard/dialog behavior, in addition to data and concurrency checks.

## CLI acceptance requirements

| Report | CQ item | Required result |
| --- | --- | --- |
| 1 | D38 | Structured help with command descriptions, examples and command-specific options, rather than one long alternatives string. |
| 2 | D39 | Appropriate warning-level defaults for both bootstrap and primary distage logging in ordinary CLI use. Retain warnings/errors and a discoverable diagnostic mode. The separate dependency warning is D25. |
| 3 | I2 | `cq backup <project-id> /path/to/backup` and `cq restore /path/to/backup`. This is a requested new capability. Specify project data/history/audit/artifact boundaries, settled-state requirements and identity/collision policy before implementation. Explicitly account for external repositories and supervisor-local artifacts. |
| 4 | D40 | Identify human and automation commands in help. Prefer readable operator output and explicit `--json` for scripts; document output streams and exit codes. Protocol-only commands may remain explicitly machine-oriented. |

Project backup/restore is not implemented by the current delivery's one-time, whole-database safety backup. A project restore must define collision handling before it can safely write to an existing server.

## Launcher and runtime follow-ups

- **I1 Accepted / K1 Adopted**: start the interactive harness directly; it starts and owns the local CQ host, which owns guardians and child harnesses inside the same yolo sandbox. No extra batch Governor or detached local daemon. Retain `cq run` for batch use. The [accepted lifecycle](agent-protocol.md#accepted-interactive-lifecycle) specifies boundaries and required native verification; implementation is underway with [deterministic evidence](../validation/attached-host.md); native harness and release checks remain pending. The durable server and its `run-local.sh` helper remain separate.
- **D25**: [Scala lazy-value warning](../validation/scala-lazyvals-warning.md). Replacing the runtime with Scala 3.10.0-RC3 did not remove the warning from already-compiled izumi-reflect bytecode. No compiler upgrade or upstream report has been made.

## Next-session execution

1. Read the CQ records and this intake; reproduce reported defects and inspect the previous UI without changing reference snapshots.
2. Follow the accepted launcher ownership decision; implement and verify that interactive mode before relying on it. Make the semantic UI design concrete before starting the redesign. Record unresolved backup/restore semantics in CQ.
3. Use CQ discipline to plan, implement and review coherent increments. Preserve user-reported requirements and distinguish technical checks from human evaluation.
4. Run affected browser/CLI/service checks. UI-only increments do not require the expensive three-by-three harness matrix.
