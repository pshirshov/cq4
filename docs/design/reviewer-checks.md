# Declared checks requested by reviewers

Implementation contract for the remaining reviewer execution capability in M4. The model remains the single mutable `cq.api 0.1.0`.

## Operation and authority

Add `workspace/Check(name, waitMillis)` to the existing local tool. The first call requests the named check; subsequent calls observe the same operation, optionally waiting up to 20 seconds. No caller-supplied command, environment, repository, candidate, job identity or timeout is accepted. The host selects a declaration from the current consumer settings and the exact candidate already materialized for this candidate reviewer.

Only an active Reviewer/Candidate assignment can use this operation. Explorer, Planner, Worker, Reviewer/Plan and Reviewer/Audit are denied on direct calls. Non-reviewer tool schemas omit the Check alternative; the complete server decoder still enforces authorization. Reviewer native shell and write tools remain unavailable. No additional MCP capability or server-owned process is introduced.

At most one check runs at once per reviewer, and each of the at most eight declared names runs at most once in that reviewer attempt. A second simultaneous name receives an explicit conflict. A duplicate call joins/observes the first execution. A fresh reviewer attempt is necessary to repeat a check. Empty or unknown declarations fail before job registration.

## Execution and publication

A check runs through the existing JobSupervisor and guardian, in its own disposable workspace based on the exact reviewed commit. It cannot modify the candidate reviewer's workspace or governing checkout. Its declaration supplies command, execution deadline and retained-output bound. A deterministic child/check job identity and immutable local ticket precede execution. The ticket binds the exact workspace specification (including candidate, owner, repository and job ID), declaration and JobCommand fingerprint. Recovery compares these against the recorded job before publishing an observation. Bounded local status distinguishes starting/running/publication, completed evidence and failure/unknown outcome; check command failure is a completed observation with Failed evidence, not a successful check.

The native reviewer and its check can be alive together. Dispatch cancellation, claim loss and hierarchy shutdown therefore track both jobs. Registration and closing synchronize admission; cancellation racing registration is rechecked after start. Reviewer exit atomically closes Check admission and snapshots every requested check. Every request admitted before this boundary must already have terminal published evidence, or the review cannot succeed; later requests fail before registration. Pending checks are stopped and awaited. This operational rule proves availability at completion, not that the model consumed a response or that a network acknowledgement arrived. MCP disconnect/timeout stops only the caller’s wait; execution/publication stay owned by the reviewer lifecycle. Unconfirmed cleanup remains Unknown and quarantined. Existing bounded watchdog behavior remains in force for stalled persistence.

Each check has a durable DeliveryQueue under its child's bounded check inventory. The host publishes its ValidationObservation and stdout/stderr artifacts under the reviewer's existing attempt. The operation exposes only compact state/evidence handles after publication. Artifact reads provide explicit bounded drill-down. Lost upload acknowledgements replay the same immutable evidence and never rerun the process.

Session reconciliation inventories these tickets and queues as well as ordinary child publication. Sealed evidence is replayed. Interrupted unsealed checks retain bounded output and recorded job outcome when available; missing/unsettled job evidence remains explicitly unknown. Recovery never executes a check. One failed queue must not suppress independent child/check recovery; retained failures are aggregated and reported after the independent work. A crash before the reviewer's own final publication still makes that review attempt Unknown; a completed subordinate check cannot manufacture a completed review.

## Review and integration semantics

A candidate reviewer inherits the exact worker validation inventory. A newly requested observation replaces the inherited observation with the same check name in the review result; unrequested names retain the worker observation. The host owns this overlay, not model output. Requested failed/unknown checks affect the compact outcome and integration applicability even if the model emits Accepted.

Host preparation and server reservation use one shared validation rule. Integration continues to require the exact independent reviewer, full member coverage and all configured worker checks passing. Reviewer validation names must match that frozen inventory and every reviewer observation must also pass. Each entry is either the exact inherited worker evidence or a validated observation authored by this reviewer for this exact candidate and declaration. Metadata attempt, command/declaration, project, session, workspace base and actual successful settled job are checked. The failed runs an entry records before its passing run ([intermittent checks](local-dispatch.md#intermittent-checks)) are verified the same way, except that each must be an unsuccessful settled job, and are cited in the Task evidence. Integration completion cites distinct worker and reviewer observations, with the existing atomic reservation/request journal and Git reconciliation unchanged.

A [host rebase](git-integration.md#host-rebase-onto-an-advanced-target) adds a third author of validation evidence. Worker and reviewer observations establish the reviewed commit; they say nothing about the merged commit that lands. The governing attempt therefore runs every configured check on exactly that commit, and both host and server require each of those observations to pass for that commit and author. The reviewer's verdict is not extended to the merged commit: no model wrote it, and the rerun checks are its only coverage beyond the reviewed difference.

A reviewer is not required to repeat every already observed check by a new execution. The operation supplies independent execution when needed; it cannot waive worker checks or hide a requested failure. Plan/Audit reviewers inspect evidence by handle and use Candidate mode when fresh executable verification of a code candidate is required.

## Verification

- Shared service scenarios cover duplicate calls, unknown names, direct mode/role denial, bound enforcement, immutable candidate/declaration and publication retry.
- Actual supervised processes prove workspace isolation, check failure despite Accepted review, request polling, cancellation of both live jobs, reviewer exit with a pending check, and hierarchy shutdown.
- Shared dummy/PostgreSQL integration checks reject foreign reviewer evidence, altered candidate/declaration, missing/extra names and failed observations; both inherited and fresh passing evidence are supported.
- Recovery after actual supervisor SIGKILL preserves available observations without another check job; lost publication acknowledgements remain idempotent. A check that never committed its job record cannot yield invented Passed/Failed evidence.
- Native role probes verify direct-call denial and permitted reviewer checks for Claude, Codex and Pi during the remaining M4 native evaluation gate.

Independent Astra design review found no fundamental blocker after requiring atomic close/freeze, lifecycle ownership independent of MCP waiting, exact recovery fingerprint binding and the measurable completion criterion above. Required race coverage includes disconnects and reviewer exit versus registration/publication.
