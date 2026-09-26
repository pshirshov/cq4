# cq

Requirements for a fresh implementation of CQ. The application has not been implemented here.

- [Implementation plan and goal text](docs/drafts/20260926-1549-cq-implementation-plan.md) — executable milestones M0–M6, verification gates, requirement ownership, and a paste-ready `/goal` for the complete first release.
- [Requirements prompt](docs/drafts/20260926-0957-cq-requirements-prompt.md) — reusable prompt covering the original 27 requirements, four added requirements, confirmed decisions, and required full-design artifacts.
- [Design brief](docs/drafts/20260926-0957-cq-design-brief.md) — proposed architecture, four subagents, four workflow commands, reference-based dispatch, harness differences, shared usage audit log, and implementation milestones.
- [Existing CQ audit](docs/drafts/20260926-0957-existing-cq-audit.md) — source evidence, measured inventories, a reproduced project-gate fallback, and verification limits.
- [Harness usage observability](docs/drafts/20260926-usage-observability.md) — live Claude/Codex/Pi probes, observed token fields, accounting differences, and efficiency measurement limits.

Confirmed scope: fresh data, web UI and CLI, cohorts retaining item identity, enforced permissions for cooperative agents, restricted subgraph termination, and compact dispatch summaries with explicit drill-down. Subagents may be terminated with their governing harness; the brief chooses this simpler lifetime model.

Checked during planning: document links, requirement coverage R01–R31, embedded JSON syntax, source inventories, the isolated gate resolver probe, selected upstream documentation, installed harness CLI capabilities, and one successful token-usage probe per harness. Complete generated schemas and application/runtime verification are deliverables of the implementation milestones.
