# Combined delivery plan

Operator order, 2026-10-04: finish the remaining defects and delivery/evaluation work, then plan and cross-cut I28, I26 and I31, then deploy the combined result once. I27, I30 and I17 are deferred until after deployment. Existing operator changes are preserved. Work happens in isolated checkouts; the live work ledger is maintained through HTTP and test data uses private databases.

## First: remaining defects and delivery

- D70: investigate main-pane stale status under resume, Resync, background/multiple tabs and committed-but-delayed acknowledgements. Preserve full observable state. Correct only a reproduced cause; if no supported trigger reproduces, record that limitation and request the missing reporter state rather than claiming a fix.
- D124: read retained failures, run bounded exact-suite repetitions with full assertion output, and examine lifecycle/timing assumptions in DispatchLocal and HostDeliveryLocal. Correct only a reproduced cause. Preserve intermittent uncertainty if reproduction remains absent.
- D135: replace the combined technical form with task-oriented Running drives, Saved worksets and Create workset sections. Existing scopes are chosen by recognizable item IDs/titles and phase. Raw UUID entry moves out of the ordinary flow. Preserve exact-revision park, snapshot and query safeguards. Add stored-workset discovery where the backend lacks it.
- G3/M2: obtain the required configured UI acceptance result; installed Help behavior and catalog contracts are already verified.
- G1: record the missing real-harness/real-ledger isolation, exact directive and failure-stop cases; do not substitute a stub-backend session for this evidence.
- G4/M3/I12: apply Q37–Q45 to the protocol, conclude RS2/RS3 with short real connected probes, review it, then execute the three requested recorded evaluation runs. Preserve unknown usage as unknown. Follow Q16's 3-hour, USD25 measured-cost cap, 30-minute no-progress stall and three identical failures; poll every 15 minutes and at stops. Escalate actual decisions outside the proxy's recorded scope.

## Second: three planned ideas

I28: retain cq configure as the imperative complement. Export NixOS server and home-manager CLI/project-integration modules. Modules take an explicit native CQ package and supervisor settings; secrets are runtime file paths, never store values. Cover managed/existing PostgreSQL, lifecycle/schema refusal, generated assets, supported harness pins and a read-only installation doctor. Validate modules without changing the operator's host configuration. I17's model tiers/panels are outside this scope.

I26: use measured presets, with separate disposable-functional-test and durable restart/restore/local-server clusters. Test-only durability changes cannot leak to durable fixtures. Derive local planner/cache settings from measured bounds rather than claiming universal tuning. Record before/after results and keep local database durability enabled.

I31: show the selected item's direct dependency/dependent relationships as a navigable directed graph, with item IDs, titles and relation labels. Preserve edge direction; provide keyboard access and usable bounded paging for large fanout. Reuse current ledger references, without a new graph database or external rendering service.

## Combined delivery

Focused failing-before and regression checks accompany each correction. Native/installed fixtures use disposable state. Prepare one combined native release, record its exact source revision and validation evidence, reconcile active work, then deploy once and verify the running package/UI before delivery closeout. Do not resolve a reported defect merely because an unrelated check is green, narrow an acceptance criterion to make an item terminal, or modify/archive operator records to conceal unfinished work.
