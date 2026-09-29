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
package/update verification passes and Astra approves delivery. Operator installation
and closeout remain pending; I6 stays Accepted until that delivery is verified. D66 remains Open for suggestion-context
behavior; it is independent of this control.


## Native package and handoff

Source commit: `df19bdd`. Candidate: `.local/release-query-clear`.

- Manifest SHA-256: `27608b970b71505d5d50ee5d93def789d6473df79112bb09a8f506f9ad8c8351`.
- Executable SHA-256: `19883b73147d6f7847ae251acf82a00a74b302858e8334e14f5224aca52765bc`.
- `verification-scope.json` establishes that only `web/src/query.ts` and
  `web/style.css` differ from the installed runtime sources. Backend, model, build
  and harness sources are identical. Their prior verification remains applicable.
- `gates/native/result.json` passes fresh affected browser tracing, native build,
  transport, the complete browser corpus and HTTP origin checks (26 command receipts).
- `gates/installed/result.json` passes relocated runtime import, source/cache
  isolation, transport, completion/prefix browser checks and HTTP (eight receipts).
- `operator-rehearsal/result.json` passes the exact package updater, persistence,
  unchanged data/credentials and restored-backup fingerprints for all 24 tables.
- Independent Astra approves source, technical delivery and the exact host wrapper;
  `final-review.json` records the pinned hashes. No paid harness matrix was rerun.

The one-time updater enforces identical non-web runtime sources, refuses an unresolved
prior recovery marker, backs up and fingerprints the database without transforming
it, and retains the previous package. `/tmp/exchange/cq-query-clear-update.sh` pins
that updater, configuration and permanent launch scripts. Shell syntax checks pass.

The environment skill requires host execution because operator processes are outside
the agent's sandbox PID namespace. After host confirmation, read its captured output
and receipt, run `operator-live.mjs` through the actual hostname, then `closeout.py`
to record I6 as Implemented with evidence. D66 and D25–D27 must remain unchanged.
Human release acceptance remains pending.
