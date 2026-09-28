# M5 query editor

The browser now requests the existing generated `QueryComplete` operation with current text and UTF-16 caret position. It renders server-provided Field, Relation, Operator, Value and Item suggestions and applies their replacement spans. No alternate filter model, protocol change or harness behavior is introduced.

Completion replies are owned by the text/caret generation and project lifecycle. Typing, dismissal, blur, project change and disconnection invalidate pending replies. IME composition defers completion. Arrow keys select, Enter accepts the highlighted option, Escape dismisses and Tab accepts while retaining normal focus traversal. An unselected Enter submits the query. Syntax errors retain the visible text, highlight the server span and offer explicit caret navigation.

## Actual browser evidence

The Chromium fixture `dev/query-browser.mjs` seeds an isolated project through the real server and uses actual WebSocket replies. It checks all five suggestion kinds, replacing a field in the middle of a longer query without duplicating its colon, keyboard selection/acceptance, zero-width diagnostics, and delayed replies after newer text, dismissal and project change. It records request/reply evidence, page errors, screenshots and a trace. This is Behavioral/Blackbox/Good-Communication coverage through the visible browser and controlled real transport.

Initial `20260928T022552-ui` passed the new assertions and the entire existing browser corpus. Visual inspection of its positioned-error screenshot then exposed a missing assertion: clicking “Show query error” moved the caret but cleared the diagnostic. The input focus handler scheduled completion and cleared the error before the navigation action dismissed that request. The added visibility assertion fails for that exact reason at `20260928T022750-ui`, before the correction. This initial passing gate does not establish the missing behavior.

The query editor remains in the existing narrow navigation pane until the next workspace-layout increment. Full M5 visual/accessibility acceptance, usage scopes/invalidation, relationships/restore and connection diagnostics remain open. Actual screen-reader announcements have not been measured.

Re-run with `./dev/check ui`; this targeted gate launches no model consumers or unrelated supervisor fixtures.


## Final correction and evidence

Caret/focus scheduling now preserves the diagnostic; text edits clear it and a current server analysis replaces it. The diagnostic exposes a polite, atomic live region, addressing Astra's accessibility observation without moving focus. Actual screen-reader behavior remains unverified.

Final `20260928T022852-ui` passes all ten query scenarios and the full retained ownership/foundation/draft/usage/connection corpus. The post-navigation visibility assertion passes, and the inspected `query-positioned-error.png` now shows the server's end-of-query marker, message and navigation control. All query page-error records are empty. No UI runtime source changed during the gate. Independent Astra approved the corrected source/evidence with no blocking or major finding after verifying the expected failing assertion, final passing corpus, matching manifests and the retained diagnostic screenshot.
