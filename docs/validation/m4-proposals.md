# Stored proposals and remaining role contracts

Status: all deterministic gates pass; independent Astra increment approval granted. This is an M4 increment, not the M4 milestone exit.

Baseline: `3ae9cfd`. Model: the single mutable `cq.api 0.1.0`.

## Implemented boundary

- Explorer Investigate/Research and Worker Probe produce per-member evidence. Planner produces per-member Proposed/Blocked/Abstained outcomes and optional typed ledger proposals. Reviewer Plan/Audit produces findings and optional follow-up proposals. Candidate review remains distinct.
- Shared host input assembly, installed instructions, restricted native profiles, isolated workspaces, result admission and usage publication carry all modes. Non-candidate reports cannot inherit a candidate or its host validation.
- Server preview and application resolve an immutable admitted result handle. The applying actor must be its original governor. All assigned revisions, the full current claim and pending integrations protect new applications. Existing mutation transactions enforce endpoint scope, provenance, rollback and request fingerprints.
- Exact committed retries authenticate again and return the original acknowledgement after later edits or claim release. Preview and application share the same 32 KiB semantic-preview limit. Neither operation sends full drafts through the parent.
- Seven domain MCP capabilities plus local dispatch remain below the ten-capability ceiling. `cq proposal preview RESULT_UUID` and `cq proposal apply RESULT_UUID` use the same services; application requires the original governor scoped credential.

## Evidence collected so far

Evidence root: `/srv/nvme/tmp/cq4-implementation/`. Each gate retains commands, exit status and source hashes. Commands run from the CQ repository with `CQ_EVIDENCE_ROOT` set to that path.

| Gate | Evidence directory | Observed result |
| --- | --- | --- |
| Initial fast reproduction | `20260927T125700-fast` | 142/143 passed; preview rejected excessive bytes while application committed |
| Native guide reproduction | `20260927T130210-contracts` | Actual Codex governor instruction construction exceeded 32 KiB |
| Contracts | `20260927T132254-contracts` | Passed: 434 definitions, seven domain capabilities, generated Scala/TypeScript round trips |
| Fast | `20260927T131502-fast` | Passed: 148 Scala scenarios, Pi bridge and evaluation fixtures |
| PostgreSQL/client fixture reproduction | `20260927T130902-postgres` | 82 service scenarios and all six new role jobs passed; fixture variable shadowing prevented the subsequent application call |
| PostgreSQL/clients | `20260927T131557-postgres` | Passed: 82 service scenarios, actual clients/supervisors/role dispatch, integration/combination, admission/shutdown and SIGKILL/restart |
| Process | `20260927T132321-process` | Passed: 41 Scala process scenarios and 19 native guardian checks |
| Evidence-citation reproduction | `20260927T131401-fast` | 147/148 passed; Evidence accepted a malformed URL; now uses the shared ledger citation validator |

`20260927T132321-process/m4-source-verification.json` verifies that all four final gates passed against identical current hashes for all 195 non-documentation source files. It also records per-tool input/output/advertisement sizes: seven domain tool advertisements total 132,570 compact JSON bytes, including schemas and descriptions; this is transport metadata, not a measurement of tokens charged by a harness. The largest input schema is `change` at 16,283 bytes; the largest output schema is `read` at 46,256 bytes. No token-efficiency improvement is inferred from capability count.

The native guide removes the repeated `cq_api_` qualification from local definition names/references. Contract checks restore the qualification and compare every schema against its canonical generated definition. The measured governor instructions are 31,745 UTF-8 bytes; child guides with fixture role instructions are 10,235 bytes. Actual installed role prompt measurements belong to connected/native evidence, not those fixture counts.

The earlier child-schema test incorrectly prohibited unions at every nesting depth. Its retained assertion failure is `20260927T130210-contracts/nested-union-reproduction.log`. The test now enforces the native requirement on the root object while validating nested proposal ADTs and nullable fields normally.

## Connected observation

`20260927T131557-postgres/jvm-dispatch.log` records the corrected supervisor/MCP/CLI flow. Its proposal session is `jvm-dispatch/sessions/a283faa3-cebf-48fb-830a-aba31ab8f562` within that evidence directory.

Explorer Investigate, Explorer Research, Worker Probe, Planner, Reviewer Plan and Reviewer Audit each complete through ordinary publication/admission. The planner's proposal is previewed and applied by handle; an identical application and a later CLI application replay the original acknowledgement. The alternate reviewer proposal becomes stale when the shared producer changes and is rejected. Human/root CLI application and direct HTTP applications by Explorer, Planner, Worker, Reviewer and Collector are denied. All seven governor/child attempts are present in the same usage audit, with six parent links and the expected roles. The governing checkout has no probe file.

Application arguments are **123 bytes** and semantic preview **577 bytes**. Private detailed narratives are absent from the parent transcript; the audit reviewer's short actionable blocker remains visible. Stored child results range from 917 to 12,568 bytes. The retained `role-measurements.json` measures installed prompts including native schema guides: Explorer 11,364 bytes, Probe 11,349, Planner 11,811 and Plan/Audit Reviewer 11,519. All six non-candidate results have no candidate and no candidate validation.

These are controlled harness outputs through the actual supervisor, HTTP server, PostgreSQL and MCP transports. They verify wiring and bounds, not live-model task quality or savings.

## Coverage and limits

The shared dummy/PostgreSQL proposal corpus covers concurrent retry, lost delivery after an actual commit, later correction/release replay, exact admitted artifact binding, request-ID collisions, all role denials, foreign actor/session/project, stale unmodified members, replacement claims, rejected admission, pending integration on unmodified members, eligible-member rules for Create-only proposals, rollback after a preceding creation, fabricated host/human authority and oversized preview/application.

New report checks cover role substitution, member coverage, child evidence provenance, malformed URL/file/commit citations, field/count limits, candidate-validation substitution and bounded outcome projections. The connected fixture corrections preserve its assertions: application arguments no longer shadow native CLI arguments, publication reads use the actual `publication.json` layout, and the audit reviewer supplies a short actionable first finding with full detail in a separate finding. Bounded blockers remain visible to the parent.

Independent Astra approved the final stored-proposal/remaining-role increment with no unresolved blocking or major finding. The review verified authority/replay, citation correction, connected role/application evidence, all four final gates, and matching manifests with no unmanifested non-documentation sources. This does not close M4 or replace human acceptance.

Declared reviewer check execution, full process entry points and worked examples, adaptive cohorts, new real native role probes, all nine harness routes and designated human acceptance remain open. Existing accepted M2 consumer chains remain historical evidence; these deterministic role fixtures do not establish new live-model quality or token efficiency.
