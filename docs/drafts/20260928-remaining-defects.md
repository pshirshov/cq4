# Remaining evaluation defects

Authorization: the user requested all remaining defects except the two reserved
CQ exercises, D26 (keyboard status bar) and D27 (fixed navigation width).
The queued I2 backup/restore request is included. Keep version 0.1.0.

## Execution and evidence

1. D29/D30: reproduce the missing navigation counts and table; add a typed,
   snapshot-consistent sorted browse projection and live project counts.
   Verify the same repository contract against memory and PostgreSQL, then the
   browser table, sorting, scrolling, keyboard behavior, and update ownership.
2. D38/D39/D40: reproduce CLI help, output, and startup noise; provide readable
   operator output, explicit machine output, command help, and diagnostic logging.
   Verify command output, streams, exit codes, and automation callers.
3. I2: project backup/restore, including history, usage audit, and artifacts.
   Preserve project identity; reject collisions rather than overwrite. Define
   consistent capture, active-work constraints, integrity validation, and the
   boundary with external Git and local harness state. Verify round trips and
   failure atomicity against the real store.
4. D41: reproduce refusal of a checked-out integration target; implement guarded
   integration with dirty-checkout preservation, concurrency detection, and
   recovery. Verify real Git checkouts and the reviewed workflow.
5. D42: ingest observable interactive Codex usage using native session identity.
   Verify correlation, repeated/cumulative events, termination, concurrent
   sessions, and explicit accounting gaps with recorded and real harness evidence.
6. D25: verify the dependency bytecode cause and current stable remedies; remove
   the deprecated Unsafe call without hiding the warning. Verify JVM startup and
   native packaging with the chosen dependency correction.
7. Independent Astra review and correction loops; scoped deterministic and real
   harness checks for affected behavior; build/install a verified package and
   document restart/run instructions. Update CQ records and coverage/status only
   for demonstrated outcomes. Keep D26/D27 open and human acceptance pending.

Evidence root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.
Do not rerun the expensive harness matrix for unrelated UI-only increments.
Preserve the operator's data and the pre-existing `.gitignore` change.

## Decisions

- Navigation counts include every unarchived item in the selected project,
  independent of the search query.
- Table sorting applies to the complete matching set, with deterministic ties
  and snapshot-checked continuations. It must not sort just the loaded window.
- No contract version bump or historical schema compatibility layer.
