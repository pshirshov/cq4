# Retaining-item terminal-status audit, 2026-10-04

The live ArchivePreview examined 94 matching items, selected none and retained 43 terminal items. The retaining set contains 13 distinct open items: OA4, D70, G3, M2, G1, I12, G4, M3, I17, I30, I26, D124 and I28. All unarchived Ideas, Goals and Milestones were also enumerated. Evidence snapshot: `/srv/nvme/tmp/cq4-retainers-audit-20261004/before.json` and `additional.json`. The ledger was accessed only through HTTP; no tests or model runs were initiated for this status audit.

## Findings

| Items | Evidence and remaining requirement | Terminal eligibility |
| --- | --- | --- |
| OA4 | Existing operator confirmation records installation from e918d24 (after required 45c7a2d) and a restarted host. The retained Worker artifact dbe09936-c8e0-31b2-80d0-929665b11bd5 reports successful `nix store info`, generation and HarnessAdapterLocal, 16 passed. This audit read its complete ArtifactText page through HTTP. Q48 explicitly accepts this confirmation/report combination for D104 closure; D104 is Resolved and RS12 is Inconclusive. | Observed. Keeping this action Confirmed is stale. Do not imply independent host observation or reopen the non-editing probe. |
| G3, M2 | Installed Help is delivered and passes focused native/live browser checks; G2 is already Achieved and I10/I11 Implemented. G3 explicitly requires the configured cq-ui gate. The current implementation's gate result is not established by the focused checks. | Keep Open pending the required configured UI gate; no product implementation gap identified in this audit. |
| G1 | Driver implementation is present, but T9 delivery acceptance requires recorded generated assets on supported Claude/Codex versions with two sessions, exact activation directives and skipped-directive/untracked-advance/out-of-set-write failure stops leaving the real ledger unchanged. Existing real-harness stub-backend probes do not establish the required real-ledger failure evidence. | Keep Open. Task Done and I9 Implemented are insufficient evidence for this broader delivery criterion. |
| I12, G4, M3 | I12 requests a three-harness interactive evaluation. Its scope follow-ups limit G4 to protocol/environment, with individual runs requested separately. G4 still requires empirical unknowns concluded and protocol review. RS2 (real connected launch across harnesses) and RS3 (Claude outer-usage reliability) remain Open, without conclusions. | Keep open statuses; preference answers alone do not complete the research or protocol review. No paid run launched. |
| I17, I27 | Per-governing-harness tier/panel configuration and task tier recommendations are not implemented. Current HarnessSetting has one model/provider per harness; the requested structure approval and later implementation remain. | Keep Accepted/Proposed. |
| I26 | RS13 measured durability effects. No corresponding disposable/live PostgreSQL configuration change or live planner/cache/concurrency measurement has been delivered. | Keep Proposed. Measurements do not implement the outcome. |
| I28 | T55 delivers command-asset doctor only. The requested NixOS/home-manager modules and broader installation doctor remain absent. The flake still exports development shells. | Keep Proposed. |
| I30, I31 | Cross-cutting mode controls and a dependencies/dependents graph remain filed proposals. Existing GraphActions edits relationships; it does not implement the requested graph visualization. | Keep Proposed. |
| D70, D124 | D129 corrects one controlled connection-resume variant, but D70's original trigger remains unreproduced. D124's assertion-reporting correction is delivered, while the original intermittent suite failure remains unexplained. | Keep Open; do not resolve an unreproduced report solely because focused runs pass. |

I9 and I10 are already terminal (Implemented). Their open parents explain archive retention; there is no additional Idea status change to make for them. No acceptance criterion was removed or narrowed to obtain terminal status.

## Applied closeout

OA4 changed Confirmed → Observed, with preserved confirmation and provenance-labeled evidence; HTTP readback verified the postcondition. The fresh preview now selects **2 items**, D104 and OA4, and retains **42**. No item was archived, no relationship was removed and no other status was changed.
