# M2 dispatch input materialization

Scope: typed reference requests, host input resolution, worker/reviewer report contracts and previous-result chaining. Local dispatch launch and the complete M2 slice remain pending.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Evidence | Observation |
| --- | --- |
| `20260927T002930-fast` | PASS 65 Scala scenarios plus Pi bridge. Includes the new shared assembly scenario against in-memory application repositories and strict role/result checks |
| `20260927T003033-postgres` | PASS 31 PostgreSQL scenarios, including the same assembly scenario, plus actual HTTP/MCP/WS, host API, roles, CLI/deadline/supervisor fixtures and whole-server SIGKILL/restart |
| `20260927T003408-contracts` | Reproduces a generated dispatch schema admitting an undeclared `prompt` field |
| `20260927T003559-contracts` | PASS initial fixed-record correction, deterministic generation and 252 schema definitions; did not yet cover empty records or omitted nullable properties |
| `dispatch-empty-record-schema/before.log`, `after.log` | Reproduces and corrects an undeclared prompt inside the empty `Reviewer` branch |
| `dispatch-nullable-schema/before.log`, `scala-before-confirmed.log`, `after.log` | Reproduces schema acceptance of omitted `previous` while Scala rejects its missing field; explicit nullable wire properties align the contracts |
| `20260927T003949-contracts` | PASS final deterministic generation, TypeScript, JSON/UEBA and all 252 schema definitions/five MCP capabilities, including the empty-record and omitted-nullable regressions |

The shared scenario fetches a large Unicode artifact through multiple pages and verifies exact content. It compares small/large request encodings: replacing only the artifact handle preserves the parent request byte count while materialized input grows by more than 50,000 bytes. No actual model call or complete dispatch traffic measurement occurs in this test.

The same scenario resolves a stored worker result into a Pi review request without putting its body in the request. It verifies the exact candidate and assignment revisions, repeatable reads, rejection of stale revisions, foreign guidance, mismatched claim membership/owner, wrong artifact kinds and released claims. The result contract rejects unknown fields, missing/duplicated/foreign members, role substitution and candidate-ready reports without a candidate.

Astra approved the materialization library and result contracts after inspecting the exact five implementation/test files matching `002930-fast`. The schema addendum required two correction loops: empty fixed records and required nullable properties. Both reproduced before correction and passed the SDK replay afterward. The initial Scala reproduction expected the wrong exception subtype (`scala-before.log`); the corrected assertion verifies the generated decoder's actual missing-field message. Astra approved the corrected schema/decoder boundary with no blocking or major findings. This approval does not cover launch, execution-time claim maintenance, result admission, integration or M2 acceptance.
