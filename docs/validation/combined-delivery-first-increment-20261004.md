# Combined delivery: first source increment, 2026-10-04

The operator requested remaining defects and delivery work, then I28/I26/I31, then one combined deployment. I17/I27/I30 are deferred. Live intake is M18, T56–T65. T66 was a duplicate intake and was cancelled with its history preserved. Evidence is under `/srv/nvme/tmp/cq4-final-wave-20261004`.

## D135: Drivers and worksets

The failing browser reproduction (`d135-before.log`) reports the expected missing Running drives section before any production correction. The redesigned dialog separates running drives, saved scopes and creation, with section navigation. Drive cards show item IDs/titles, harness, phase, state and child activity; internal session identifiers are under Session details. Saved scopes are browsable in bounded pages, labelled by their targets and phase. Items can be selected by title or exact ID. No ordinary UUID input remains; filtered items show the target IDs and phase.

A read-only BrowseSaved workset action lists immutable worksets in ID order within the current project. Each response is byte/count bounded; refresh discovers new worksets that sort before the current page. It does not promise a cross-page snapshot of concurrent additions. Existing exact-revision parking, hold confirmation, query cursor consistency, and StorePreview revalidation are preserved. API version remains 0.1.0; codecs and the current signature were regenerated. No stored type or SQL schema changed.

Validation:

- TypeScript compilation and the actual Drivers dialog layout reproduction pass, including a 640-pixel viewport without horizontal overflow.
- StoredWorksetContractDummy and StoredWorksetContractPostgres pass seven tests each. The shared added case checks paging, stable ordering, project isolation, read permissions and invalid bounds; PostgreSQL used a disposable private cluster.
- The actual server/browser fixture passes save/open/filter/clear, explicit-ID and title selection, saved-workset paging, committed park state, click-versus-hold behavior, query paging and stale-snapshot refusal. Evidence: `d135-complete/`.
- Desktop screenshots were inspected. Installed delivery remains pending the combined release; D135 stays Open.

Initial setup failures are retained: an investigation invocation raced with regeneration and failed to compile; another compile exposed a generated Scala collision with a variant named List, which was renamed BrowseSaved. Neither failure reproduces D124. Successful validation used the regenerated final model.

## D70: bounded investigation

The actual private-server Question fixture passes batch-answer freshness, answers from another HTTP session, removal from an Open-only list, committed-but-lost acknowledgement recovery, and two tabs converging after freeze/resume. Readback establishes the committed revision, main-pane status, list status and answer. Evidence: `source-browser-final/question-results.json` and trace.

The operator said there are currently no open Questions with which to retest the original report. These checks do not reproduce the original failure or prove that D129 was its cause. No speculative production correction was made; D70 remains Open for an actual failing trigger.

## D124: bounded investigation

Both retained failed-check artifacts were read through HTTP, including their referenced stderr payloads. They report two HostDeliveryLocal failures and one DispatchLocal failure, but the failed assertion bodies were outside the retained excerpt. Three exact-suite runs each passed: DispatchLocal 8 and HostDeliveryLocal 17 per sample, 75 executions in total. Evidence: `d124-focused/`. The earlier assertion-reporting correction remains delivered. No intermittent cause was established or patched; D124 stays Open pending a failure with complete assertion output.

## Delivery and evaluation

Q37–Q45 have been applied to `docs/evaluation-protocol.md`. This settles preferences, not empirical research or independent review. At the time of this increment, G1's missing real-harness/real-ledger scenarios, RS2/RS3 and the requested matrix were outstanding; see the later reconciliation below. No paid evaluation was launched by this increment.

Provider credentials are absent from the current environment. The existing private Codex home links to a home-directory authentication file, and SMIND_EXCHANGE_DIR is unset. The environment skill's required exchange workflow therefore cannot be used here; bound private authenticated configuration was requested. Independent implementation continues while this dependency is supplied.

### Later the same day (T59/T60 reconciliation)

The two paragraphs above describe the state when this increment began, and they are kept as history. Since then:

- **Credentials and driver cases.** The operator refreshed the Claude login. The T59 driver cases (two concurrent sessions, skipped, untracked and out-of-set `Failure` stops with an unchanged ledger page, park, Resume and consecutive cycles) were recorded with real Claude Code 2.1.285 against private real ledgers, alongside the earlier Codex 0.159.2 cases. They are in [Real-harness driver cases (T59)](t59-driver-cases-20261004.md), with the credential-free projection in [`evidence/t59-driver/`](evidence/t59-driver/README.md). Resume and consecutive cycles were observed in separate drives of one session. The governing sessions observed all of this in private fixtures; nobody has independently reread it.
- **Research.** RS2 revision 4 is Concluded: a host-level launch of all three pinned harnesses with CQ connected. RS3 revision 7 and RS15 revision 4 are Concluded: the Claude transcript and `/cost` are separate partial sources with unexplained counter discrepancies and explicit coverage gaps. Details are in [the evaluation protocol](../evaluation-protocol.md#research).
- **Source tasks.** T69 and T70 are Done through Recorded integrations on `main` at `e8ef9a8`. T71 is Done through integration `bd9e2c6b-711b-4417-a2c2-cf9e5c53259b` at `e4ae1e0`, and D142 is Resolved.
- **What is still open.** G1 is not Achieved until independent review accepts the T59 record. G4 is not ready until independent review accepts the corrected protocol. No full matrix qualification or complete billing is claimed.

Configured fast/ui gates were not run by the agent, following the supplied project policy. G3/M2 still require the configured UI acceptance result. No deployment or archival took place.
