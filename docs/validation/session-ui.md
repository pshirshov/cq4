# Persistent operator session and item identity polish — 2026-09-30

User requests recorded as I7 (persistent login) and I8 (UI polish), both Accepted. Evidence root: `/srv/nvme/tmp/cq4-session-20260930`.

## I7: keep the operator signed in

The operator token was not lost on restart. Login issues an HttpOnly, SameSite=Strict `cq_session` cookie, HMAC-signed with the operator token, and both the cookie and its credential expired after 12 hours. The new contract assertion (a browser login must authenticate 30 days later) failed on the Dummy repository before the change (`lifetime-before.log`).

The browser session now lasts 400 days, the maximum cookie lifetime browsers accept. Each app load (`GET /api/hello` authenticated by the cookie as an operator session) re-issues it with a fresh expiry. The raw token is never stored by the browser. Replacing the operator token file invalidates every session, because the signature key changes. Scoped (non-operator) credentials cannot be renewed.

Verification:

- `contract-after-2` passes the application contract on the Dummy and PostgreSQL repositories: 30-day validity, expiry after 400 days without renewal, renewal extending validity, and refusal to renew scoped credentials.
- `dev/transport.mjs` asserts the login `Max-Age` and renewal on `/api/hello`.
- `scoped-1/session.json` confirms in a browser that the cookie is HttpOnly, lasts 400 days, that a fresh page needs no token, and that the raw token appears nowhere in browser storage.

## I8: item identity presentation

- The results table shows the type icon inside the ID column; the separate Type column is gone. The ID cell's tooltip names the ledger. Sorting by type is no longer offered in the table, although the protocol still supports it; sorting by ID groups items by ledger prefix.
- Item reference links carry their ledger icon.
- "Answer open questions" is now "Answer questions".
- Links in the question batch dialog were checked before any change (`links-before`, `links-before-2`). Description, prompt, context and alternatives-list references were already highlighted and opened the reference popup. Only the dialog heading and the answer choice buttons show plain text: headings are plain throughout the application, and a button cannot contain another interactive link. `dev/question-browser.mjs` now asserts that a reference inside the batch dialog opens the popup.

The updated table, sizing, question and dialog-race browser tests failed on the previous UI for the expected reasons (`ui-before`). The first run after the change (`ui-after`) exposed two consequences of moving Title from the third to the second column:

- The stylesheet still exempted the third column from `nowrap`, so the Status column wrapped.
- The table test still sorted by type.

`ui-after-2` passes table, sizing, question, dialog races, workspace/query/redesign, polish, completion, prefix, density, scroll, session and question-link suites.

## Native, package and update

- `native` rebuilds the binary at `2fd52cb` from the hash-checked trace snapshots of the fully traced `07acd60` gate. The declared runtime delta is two server files (session lifetime and renewal; no new serialized or reflective types) and browser sources. The rebuilt binary passes native transport (including the renewal assertions), artifacts, the complete browser suite, the HTTP browser and project archives.
- `gates/installed` passes the scoped relocated package check, with sources hidden.
- The update is package-only: the schema, including the archive completion index, is unchanged. `update-local.py` derives from the hardened all-defects updater; it requires the existing index and schema checksum, backs up, fingerprints and smoke-tests, swaps packages, and restores on failure.
- `operator-rehearsal` and `operator-rollback` pass.
- The candidate manifest is `e1d0581a4dbf6f8a7e78587c1286d023331839fa3dab70ae190bc7c1af5efcf8`. The pinned host wrapper is `/tmp/exchange/cq-session-ui-update.sh`.

## Review

An independent read-only Claude reviewer (Astra unavailable) approves with minor findings. It recomputed the wrapper pins, manifests, binary identity and schema checksum.

Corrections after the review:

- The updater now checks for the archive index before any database change; the rehearsals were rerun.
- `transport-negative` asserts that bearer-authenticated requests never receive a browser session cookie.

Accepted trade-offs, disclosed rather than corrected:

- Sessions are stateless signed credentials. Logout clears only the browser cookie; a copied cookie keeps working, and can be renewed, until the operator token is rotated.
- The operator origin is plain HTTP, so the cookie travels unencrypted on the LAN. The `Secure` flag is added only for HTTPS origins.
- A same-site page can trigger a renewal through a GET without an Origin header. This can only extend a session's lifetime.
- The ID cell names its ledger only through its tooltip, although the ID prefix already conveys the type.
- Sorting by type is no longer offered in the table.

## D25 native correction in this candidate

The pending candidate (manifest `e1d0581a…`) was superseded before installation, and its wrapper withdrawn, when the native executable was found to still print the Unsafe warning. `ead2dc6` passes the `.jvmopts` memory-access setting to the native builder and adds a quiet-startup gate; evidence in `superseded-d25/` and `/srv/nvme/tmp/cq4-d25-native`.

The rebuilt candidate, manifest `1944260beeb2ee3e3e658908df0d50c5f74cbeae0bd43fddced2b92304b6a90a`:

- prints no warning, including in every server log of the native, installed and rehearsal gates
- passes the scoped installed check and both rehearsals, with only `NEW_MANIFEST` changed in the reviewed updater

An independent review approves with minor findings. The README claim is corrected, and the native builder now receives only the memory-access setting from `.jvmopts`.
