# M5 save completion and edit conflicts

This increment preserves navigation after a save has been submitted. Successful completion clears only the originating editor, reports the saved item/project, and selects the saved item only if the same editor/navigation still owns that action. The exact persisted request/acknowledgement retry behavior remains in place.

## Reproduction and correction

`/srv/nvme/tmp/cq4-implementation/20260928T024617-ui` holds an actual successful server acknowledgement after a committed T1 edit, navigates to T2, then releases the reply. The assertion fails because the old save completion selects T1 again. The fixture waits for the real reply and all healthy in-flight Call/Subscribe replies; this avoids asserting during an intermediate render.

The correction captures navigation ownership when saving begins and checks it again after the post-save refresh. Moving to another item or project keeps the newer view. Completion still reports its original project/item, even when a project change has replaced the editor. A current same-scope save still selects the saved item as before.

## Conflict comparison

A rejected optimistic edit retains its draft and original expected revision. The browser reads the current record and displays the base/current revision numbers and current content beside the draft. “Use current revision as draft base” is an explicit action: it retains the draft, persists the observed revision as its new base, and still requires a separate save. A later concurrent edit remains protected by the server's optimistic check; there is no automatic overwrite/rebase.

The stale-base browser fixture confirms rejection preserves the concurrent revision/body, then explicitly chooses the displayed base and saves the original draft as the next revision. Existing uncertain-create and authentication/reload retry fixtures remain in the corpus.

## Verification

The initial corrected `20260928T024835-ui` passes the item-navigation case, explicit conflict/base update and the retained browser corpus. Final verification adds project navigation and consolidates the three delayed-reply fixtures' common native-WebSocket observer in `dev/browser-protocol.mjs`. Its settlement helper is intentionally limited to healthy delivered Call/Subscribe replies; it is not a general reconnect or unanswered-request oracle.

Run `./dev/check ui`. These are actual browser/server/PostgreSQL behavior checks, with controlled message delivery and authoritative record verification. No model consumers, harness changes or full nine-route rerun are involved. The final expanded gate `/srv/nvme/tmp/cq4-implementation/20260928T025044-ui` passes both navigation cases and the full retained corpus with no page errors. All seven changed implementation/fixture files match its source manifest. `edit-project.png` shows the newer project with the original save scope reported; `conflict-before-rebase.jpeg` (extracted from the retained stale-base trace) shows base/current revisions and current content before the explicit action. Independent Astra approved the final source and evidence with no blocking or major finding. Relationship/restore actions and full M5 acceptance are still open.
