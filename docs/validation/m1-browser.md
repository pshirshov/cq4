# M1 browser foundation

The browser is an M1 foundation, not completion of the M5 UI requirements. It is bundled with esbuild and served by the Scala application. Dynamic state uses generated Baboon WebSocket calls/events.

## Implemented

- Operator login using an HttpOnly session cookie; project selection and creation.
- Bounded list pages with ledger/archive filters; item detail, create/edit, history and usage/audit pages. All fourteen content branches render from the generated JSON schemas, which avoids a second handwritten field model.
- Local drafts survive reload. Edits retain their expected revision; failures retain the draft. Immediate retransmission of an unchanged draft retains its mutation request ID.
- Distinct connection and synchronization indicators. Nonce-correlated application heartbeat, NEW/ALIVE/STALE/DEAD states, connect and request deadlines, stale grace, at most two live connections, capped jittered backoff, retry ceiling and explicit manual retry.
- Visibility, offline/online, page lifecycle/BFCache and time-jump handling; source-connection and subscription-identity guards. A matching heartbeat verifies liveness before the application subscribes again.
- Bounded connection event history, current RTT, retry/deadline display and tab-title state. The complete diagnostic windows and M5 interaction design remain open.

## Observed evidence

- `/srv/nvme/tmp/cq4-implementation/20260926T191918-browser`: real PostgreSQL/HTTP/MCP/CLI checks plus Chromium pass. Chromium creates a project, persists a draft across reload, creates and edits a task, reads both history revisions and usage audit, receives another client's committed item, recovers from offline, and renders all fourteen content forms. It does not create every ledger type through the form in this run.
- Offline recovery first failed in `20260926T191741-browser`. The retained trace shows the original socket recovering and displacing its replacement, with transport ALIVE while data remains stale. Recovery now notifies the application even when the recovered socket retains its identity, so it resnapshots/subscribes again.
- Dropped-pong recovery first failed in `20260926T192221-browser`. The fixture intercepts the real WebSocket, drops one heartbeat reply, rejects replacement/unsolicited pongs, then allows a newer matching reply. The assertion observes STALE after recovery because the older expired deadline remained pending. A newer matching reply now retires older probes on the same connection.

The initial browser harness also had two fixture errors, retained separately: waiting for a closed select's option to be visible (`20260926T191619-browser`), and an interception URL that did not match the actual WebSocket (`20260926T192029-browser`). These are test defects, not product defect evidence. The corrected fixture asserts that it intercepted the connection before exercising packet faults.

Full passing evidence: `/srv/nvme/tmp/cq4-implementation/20260926T192352-browser` repeats the real client/CLI scenarios and Chromium workflow, then passes dropped/unsolicited pong handling, bounded overlapping replacement, newer-pong recovery, permanent close and manual retry. Each check directory retains source hashes, command/exit records, Chromium trace archives, browser errors, and a workspace screenshot.

The full contract entrypoint also passes at `/srv/nvme/tmp/cq4-implementation/20260926T192613-contracts`: generated contracts and browser assets are reproducible, TypeScript is checked in strict mode, Scala compiles, and wire/schema scenarios pass. The runner now repeats both generation and bundling before comparing hashes; `20260926T192536-contracts` retains the failure from rebuilding browser assets in only the first half.

## Explicit gaps

M5 still owns the full query editor, keyboard/resizable pane design, Markdown presentation, polished typed reference controls, complete conflict/draft lifecycle, all asynchronous selection races and diagnostics. This foundation uses generated forms and bounded structured drill-down; it does not claim the complete final interaction design. Usage refresh is manual or follows ledger refresh; independent usage-view notifications remain to implement.

The connection implementation has lifecycle handlers but the current deterministic browser corpus does not yet exercise every BFCache, hidden-tab, sleep, handshake-blackhole, server-heartbeat expiry and retry-ceiling path. The existing `browser` entrypoint now executes real foundation checks rather than reporting unavailable; M5 completion still requires adding its full planned corpus. Native browser asset packaging remains to verify.
