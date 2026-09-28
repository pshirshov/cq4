# M5 browser response ownership

This first M5 increment binds asynchronous panel responses to the current project generation, selection generation and panel request generation. The selected item ID is recorded when navigation starts, independently of the last loaded detail. A same-item refresh preserves valid history/audit panels; changing selection clears previous panels immediately. Draft ownership and pending-save recovery remain independent.

## Reproduction before correction

At runtime baseline `54b6ec7`, `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/check ui` first failed at the foundation's empty usage-audit assertion (`20260928T020936-ui`). Its trace showed healthy connectivity and an empty audit panel; that observation alone did not establish the cause.

The controlled run `20260928T021548-ui` then held actual server WebSocket replies while a newer item/project/request was selected. Six of eight cases failed before the product edit:

| Case | Observed failure |
| --- | --- |
| Detail A after selecting B | Old A detail replaced B. |
| History A after selecting B | Previous visible history was not cleared on selection change. |
| Usage A after selecting B | Old A totals replaced B's scope. |
| History A after changing project | Old project's T1 history replaced the new project's T1 history. |
| Audit after refreshing the same item | Valid pending audit was discarded because the detail object had been replaced. |
| Older detail after a newer same-item reply | The old title replaced the updated title. |

Project-change usage and cross-item audit guards already passed. The existing foundation, draft, usage and connection cases also passed in that run; the earlier foundation failure was timing-dependent. `selection-results.json` retains exact held requests/replies and failure messages, with per-case traces and screenshots.

## Correction and verification

`readPanel` centralizes ownership checks before processing either a result or error. New selection invalidates all old panel requests; each panel's latest request wins even for the same item. Stable item identity replaces detail-object identity for audit ownership. `choose` clears old panels only when selection changes, and reset also clears old usage. No protocol, service, supervisor, prompt or harness adapter changes are included.

The focused `ui` gate generates/builds the browser, type-checks TypeScript, starts the real server with isolated PostgreSQL and executes Chromium. It excludes model consumers and unrelated transport/supervisor fixtures. These checks are Behavioral/Blackbox/Good-Communication tests: they exercise visible UI actions and controllable real network delivery, without private application-state access. The WebSocket wrapper records received reply IDs solely to synchronize assertions after actual delivery.

Final evidence: `/srv/nvme/tmp/cq4-implementation/20260928T021810-ui`. All eight selection cases pass. Existing login/project creation, fourteen typed forms, persisted drafts, create/edit/history/audit, external live updates, query errors and offline recovery pass. Stale-base and two uncertain-save cases pass. Usage/outcome/cost pagination and connection loss/replacement/manual recovery checks pass. Page-error reports are empty. The retained same-item audit screenshot was visually inspected: T1, its 11-token scope and audit observation are visible together. Layout completion is later M5 work; this screenshot is not full visual acceptance.

Re-run from the checkout:

```sh
./dev/check ui
```

Independent Astra approved this bounded increment with no blocking or major finding after inspecting the failing/passing evidence and verifying that the current application, fixtures and gate match the final source manifest. No additional model calls or unrelated gates were required. Remaining M5 work includes the workspace/query layout, completion, relation/restore/conflict interaction, complete usage scopes and independent usage invalidation, connection diagnostics and accessibility. M6 packaged checks and human acceptance remain open.
