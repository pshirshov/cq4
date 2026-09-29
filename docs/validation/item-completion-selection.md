# Item completion selection: D66

User report, 2026-09-29: with an empty focused query, actual item suggestions such
as D1 appear, but selecting one seems to do nothing. Filed as D66, Open, revision 1.
No production change has been made; the reported insertion failure is not yet
reproduced.

## Inspected behavior

Evidence: `/srv/nvme/tmp/cq4-item-completion-20260929`.

- The installed manifest is the delivered `eb8e6ba6…` candidate. Chromium visited
  the actual configured hostname with a fresh browser context.
- `reproduction.json` records a pointer click on the D1 suggestion from an empty
  query: the input becomes `D1`; there are no page errors. The selected suggestion
  is labelled archived. Captured DOM events show selection completing normally.
- `keyboard.json` also records D1 insertion after selecting the suggestion with
  ArrowDown and Enter. The first keyboard fixture had an ambiguous selector that
  also matched the project combobox; `keyboard-2.log` passes after scoping it to the
  suggestions list. No application change was needed.
- The current completion protocol inserts text; it does not submit the query.
- `search-observations.json` records `D1` returning no results and
  `D1 archived:all` returning the archived, Resolved D1. The default query excludes
  archives even though completion can suggest archived references.
- The user has been asked whether query text remains empty or only results remain
  unchanged. The browser and exact interaction remain unknown.

These observations do not establish the cause of the user's reported behavior.
D66 stays Open pending clarification; no speculative completion change is included.
