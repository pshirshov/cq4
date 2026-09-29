# Clear-query button: I6

The user requested a clear button inside the right edge of the search field that
only empties the query. Filed as I6, Accepted; version remains 0.1.0.

The implemented × button has the accessible name and tooltip “Clear query”. Pointer
and keyboard activation clear input and old diagnostic/completion state, return
focus to the input, and do not submit a search. Results continue to represent the
last submitted filter. The control remains present when empty to avoid layout shifts.

## Verification

Evidence: `/srv/nvme/tmp/cq4-query-clear-20260929`.

The extended existing completion browser check fails before implementation because
the Clear query button is absent (`before-3`). Two earlier runner attempts lacked
a session ID and a sufficiently long test token; those fixture failures are retained.
`after/result.json` passes TypeScript checking, frontend build and the complete
completion browser scenario against an isolated native server/database with current
frontend assets supplied by the browser test. It checks pointer and keyboard use,
cleared diagnostics, rejection of held obsolete replies, focus, in-field position,
and no submitted browse request. Existing completion interaction cases also pass.

This frontend change does not require backend/domain or paid harness gates. Native
package/update verification and operator delivery are being prepared. I6 remains
Accepted until that delivery is verified. D66 remains Open for suggestion-context
behavior; it is independent of this control.
