# CQ

CQ is being implemented under the [M0–M6 plan](docs/drafts/20260926-1549-cq-implementation-plan.md). The current code is the M0 stack proof. Release functionality, consumer evaluations and human acceptance are tracked in [implementation status](docs/implementation-status.md) and [requirement coverage](docs/requirement-coverage.md).

## Development checks

On Linux amd64 with Nix and network access:

```sh
./dev/check contracts
./dev/check fast
./dev/check postgres
./dev/check native
```

The entrypoint enters the pinned Nix environment when Java/sbt are absent. Baboon is downloaded to `.tools` and checked against a pinned SHA-256. npm dependencies are installed from the lockfile. Generated source and build products are ignored. `contracts` verifies deterministic generation and cross-language codecs; `fast` uses the dummy repository; `postgres` starts an isolated PostgreSQL cluster and runs the same service/repository scenarios plus real transport clients. `native` traces the JVM proof, builds a native executable, and exercises that executable against PostgreSQL.

Development uses one CQ schema version, `0.1.0`. Edit it in place and run `./dev/generate`; breaking changes are permitted. Version bumps require explicit user instruction. See [AGENTS.md](AGENTS.md).

Each invocation prints its evidence directory, normally `.work/evidence/<timestamp>-<check>`. Set `CQ_EVIDENCE_ROOT` to retain logs elsewhere. `result.json`, `commands.json` and `source-sha256.json` distinguish pass/failure and identify the tested source. Native proof output is `<evidence-directory>/cq`; it is currently a development artifact.

Provided PostgreSQL can be selected with all three variables:

```sh
CQ_TEST_DATABASE_URL=postgresql://127.0.0.1:5432/cq_checks \
CQ_TEST_DATABASE_USER=cq_checks \
CQ_TEST_DATABASE_PASSWORD=local-test-password \
./dev/check postgres
```

The runner creates and drops a unique schema in that database. The account must have schema creation permission. With no provided URL, PostgreSQL is started as the current non-root user and stopped by the runner. Missing infrastructure fails the check. `process` and `browser` currently report unavailable; they do not report success.

## Run the stack proof

Create an empty PostgreSQL database, then run from this repository:

```sh
CQ_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/cq \
CQ_DATABASE_USER=cq \
CQ_DATABASE_PASSWORD=local-password \
CQ_HOST=127.0.0.1 \
CQ_PORT=8765 \
CQ_ORIGIN=http://127.0.0.1:8765 \
CQ_TOKEN=0123456789abcdef0123456789abcdef \
CQ_PROJECT_ID=00000000-0000-0000-0000-000000000001 \
nix develop -c sbt --server --batch 'server/run'
```

Use your own local credential in place of the example token. All variables are required, including the database password (which may be empty for local trust authentication). The proof creates its `cq_probe` table. A native artifact accepts the same environment; replace the sbt invocation with its executable path.

```sh
curl --fail-with-body \
  -H 'Authorization: Bearer 0123456789abcdef0123456789abcdef' \
  http://127.0.0.1:8765/api/hello
```

Expected body: `{"version":"0.1.0","supported":["0.1.0"]}`. `/api/probe`, `/ws` and `/mcp` exercise the same probe service; see [contracts](docs/design/contracts.md). No browser application or ledger workflow is available yet.

Pins, local compatibility patches and their failure evidence are documented in [dependencies](docs/design/dependencies.md).

## Requirements and planning evidence

- [Implementation plan and goal text](docs/drafts/20260926-1549-cq-implementation-plan.md) — executable milestones M0–M6, verification gates, requirement ownership, and a paste-ready `/goal` for the complete first release.
- [Requirements prompt](docs/drafts/20260926-0957-cq-requirements-prompt.md) — reusable prompt covering the original 27 requirements, four added requirements, confirmed decisions, and required full-design artifacts.
- [Design brief](docs/drafts/20260926-0957-cq-design-brief.md) — proposed architecture, four subagents, four workflow commands, reference-based dispatch, harness differences, shared usage audit log, and implementation milestones.
- [Existing CQ audit](docs/drafts/20260926-0957-existing-cq-audit.md) — source evidence, measured inventories, a reproduced project-gate fallback, and verification limits.
- [Harness usage observability](docs/drafts/20260926-usage-observability.md) — live Claude/Codex/Pi probes, observed token fields, accounting differences, and efficiency measurement limits.

Confirmed scope: fresh data, web UI and CLI, cohorts retaining item identity, enforced permissions for cooperative agents, restricted subgraph termination, and compact dispatch summaries with explicit drill-down. Subagents may be terminated with their governing harness; the brief chooses this simpler lifetime model.

Checked during planning: document links, requirement coverage R01–R31, embedded JSON syntax, source inventories, the isolated gate resolver probe, selected upstream documentation, installed harness CLI capabilities, and one successful token-usage probe per harness. Complete generated schemas and application/runtime verification are deliverables of the implementation milestones.
