# Item completion selection: D66

User report, 2026-09-29: with an empty focused query, actual item suggestions such
as D1 appear, but selecting one seems to do nothing. Filed as D66, Open. The user subsequently confirmed that insertion works and D1
was excluded because it is archived. Revision 2 now records a suggestion-context
and archive-scope defect; no production correction has been made.

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
- The user confirmed D1 appears in the query and that archived filtering explains
  the empty result. The user questions the utility of individual item suggestions
  for an empty query.

D66 stays Open for suggestion relevance: syntax suggestions for empty queries,
reference completion in ID/relationship fields, and archive-aware direct search
suggestions. Archived targets remain useful in relationship fields. This suggested
direction is separate from I6, the requested clear-query control.
