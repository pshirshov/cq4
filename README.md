# CQ

CQ is implemented under the [M0–M6 plan](docs/drafts/20260926-1549-cq-implementation-plan.md). The durable ledger/audit core and authenticated HTTP/MCP/WebSocket/CLI interfaces are implemented; [M1 has independent Astra approval](docs/validation/m1-review.md). The [batch supervisor role](docs/design/supervisor-role.md) and [child handle dispatch](docs/design/local-dispatch.md) now run real consumer builds under all three harnesses. All three routes have independent candidate assessments; [current quality evidence](docs/validation/m4-usage-repetitions.md) distinguishes accepted clarified-oracle replacements from the retained earlier failures. Interruption recovery passes the runtime checks. M2 has technical Astra approval and awaits human acceptance. Release functionality and human acceptance are tracked in [implementation status](docs/implementation-status.md) and [requirement coverage](docs/requirement-coverage.md).

## Development checks

On Linux amd64 with Nix, Git and network access:

```sh
./dev/check contracts
./dev/check fast
./dev/check postgres
./dev/check access
./dev/check ui
./dev/check usage
./dev/check browser
./dev/check native
./dev/check process
```

The entrypoint enters the pinned Nix environment when Java/sbt are absent. Baboon 0.0.196 comes from its pinned upstream Nix flake (`baboon-jvm`); `dev/generate` enters the pinned shell when `CQ_BABOON` is absent and validates its compiler version before generation. npm dependencies are installed from the lockfile. Generated source and build products are ignored. `contracts` verifies deterministic generation and cross-language codecs; `fast` runs dummy repository scenarios and the isolated-workspace scenarios against real scratch Git; `postgres` starts an isolated PostgreSQL cluster and runs the same service/repository scenarios plus real transport clients. `native` traces the JVM proof, builds a native executable, and exercises that executable against PostgreSQL.

Development uses one CQ schema version, `0.1.0`. Edit it in place and run `./dev/generate`; breaking changes are permitted. Version bumps require explicit user instruction. See [AGENTS.md](AGENTS.md).

Each invocation prints its evidence directory, normally `.work/evidence/<timestamp>-<check>`. Set `CQ_EVIDENCE_ROOT` to retain logs elsewhere. `result.json`, `commands.json` and `source-sha256.json` distinguish pass/failure and identify the tested source. Native proof output is `<evidence-directory>/cq`; it is currently a development artifact.

## Native distribution

For hands-on evaluation, use the [local launch script and Go project walkthrough](docs/quickstart.md). It starts a private persistent PostgreSQL database and the native server, then walks through intake, reviewed implementation and usage inspection.

For the implemented role inventory, complete real prompts/results, required protocol calls and alternatives to the managed-session launcher, see [agent protocol and launch design](docs/design/agent-protocol.md).

The current native package is `/home/pavel/work/safe/flakes/cq4/.local/release`; launch the durable server with `./run-local.sh`. For interactive work, [start Claude/Codex/Pi directly inside yolo](docs/interactive.md): the harness owns its CQ host and managed children. Project-local integrations are installed in this checkout. `cq run` remains available for batch execution.

[Attached-host delivery evidence](docs/validation/attached-host.md) records fresh native and source-isolated installed gates, three real packaged cyclic harness routes and interactive ownership/freeze/death checks. Earlier sixteen-stage/nine-route release evidence retains its original artifact identity; this change does not claim a new full release matrix. The earlier three interrupted attempts still prevent a complete spending claim. Interactive Claude/Codex outer usage is unavailable; Pi finalized-assistant usage has explicit coverage limits. The human yolo trial and [combined release verdict](docs/validation/m6-review.md) remain pending, with the UI/CLI redesign queued for its own CQ session. One native executable implements every CQ role, with `bin/cq-guardian` beside it; its README documents runtime closure and deployment requirements.

From this checkout, assemble and verify a distribution using the evidence directory printed by a passing native gate:

```sh
./dev/package --native-evidence /absolute/passed-native-evidence --output /absolute/new-distribution
./dev/package-check --release /absolute/new-distribution --evidence-root /absolute/evidence-outside-cq
```

The verifier needs the development shell, built test clients and Linux bubblewrap. It relocates the artifact, imports its closure into a private Nix store, mounts those libraries, and hides the CQ checkout/build and Coursier classpath from CQ. Test clients and configured consumer tools remain external. The same native binary is exercised through transport, roles, supervision, integration, browser, restart and settled backup/restore.

Provided PostgreSQL can be selected with all three variables:

```sh
CQ_TEST_DATABASE_URL=postgresql://127.0.0.1:5432/cq_checks \
CQ_TEST_DATABASE_USER=cq_checks \
CQ_TEST_DATABASE_PASSWORD=local-test-password \
./dev/check postgres
```

The runner creates and drops a unique schema in that database. The account must have schema creation permission. With no provided URL, PostgreSQL is started as the current non-root user and stopped by the runner. Missing infrastructure fails the check. `access` requires that local cluster and records actual query plans and mutation/completion access budgets as unrelated data grows; [measurement scope and evidence](docs/validation/m3-query-access.md). `ui` type-checks the frontend and runs real Chromium UI/connection checks against the server and PostgreSQL, without model consumers or supervisor fixtures. Use it for UI-only increments. `usage` adds the dummy/PostgreSQL audit-service checks and actual usage-watch protocol/SQL measurements to the browser gate; it requires an isolated local cluster. `browser` includes the broader transport/supervisor fixtures before those browser checks. `process` builds the Linux guardian and checks process-tree cleanup and the Scala driver; actual supervisor recovery and dispatch are covered by `postgres`; live harness evidence is retained separately. It requires Linux 5.9 or newer.

For exact startup, session reconciliation, settled database backup/restore and retention boundaries, use the [operations guide](docs/design/operations.md).

## Real consumer evaluations

These explicit commands use configured Claude, Codex and Pi model access and incur model usage:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation ./dev/evaluate --suite first-slice
```

The first-slice suite runs Python and Go consumer builds with each harness governing once, followed by a separate Codex/Astra assessment of each exact candidate. It retains model routes, native output, candidate/check/review artifacts, operational usage and database dumps. Assessment attempts use `assessor: true` in the same evaluation audit. `suite.json` links each stage; use the assessment's combined usage report for baseline plus assessment accounting. Integration and human milestone acceptance remain separate.

The packaged release suite is implemented and independently reviewed; the initial installed corpus and nine live cohort routes passed. Full workflow execution then exposed omitted native Review/Upstream instructions; the corrected native and installed gates now pass with Astra approval, and the fresh same-artifact release corpus completed. The later HTTP browser correction reuses those original executions through reviewed source applicability. After creating a verified distribution, use `./dev/evaluate --suite release --release /absolute/distribution`. It runs all nine parent/child routes through three cohorts, their independent audits, and both complete worked processes. Resume with `--resume /absolute/suite`; the actual question checkpoint also needs `--answer-file /absolute/answer.json`. Successful stages are replayed without new model calls. `--report-only` recomputes retained evidence; explicit retries/adopted corrections preserve failed spending. See [package verification and release suite](docs/validation/m6-package.md).

For a targeted run, use `./dev/consumer-eval claude|codex|pi python|go`, then `./dev/consumer-assess <printed-evidence-directory>`. The deferred assessment requires unchanged task revisions and the same consumer specification/oracle as the baseline. [Observed results, failures and limits](docs/validation/m2-consumer-evaluations.md).

If an assessment requests changes, run `./dev/consumer-assess --correct <rejected-assessment-directory>`. This starts the original governing/worker/reviewer routes, passing the retained review handle to the worker. It records correction usage with `assessor: false`; run a new independent assessment on the resulting candidate directory. Earlier failed builds and assessments remain in the combined audit.

## Run the current development server

Create an empty PostgreSQL database. Generate contracts and browser assets from this repository first:

```sh
./dev/generate
npm ci --ignore-scripts
npm run build
```

Then run:

```sh
CQ_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/cq \
CQ_DATABASE_USER=cq \
CQ_DATABASE_PASSWORD=local-password \
CQ_HOST=127.0.0.1 \
CQ_PORT=8765 \
CQ_ORIGIN=http://127.0.0.1:8765 \
CQ_TOKEN=0123456789abcdef0123456789abcdef \
nix develop -c sbt --server --batch 'server/run serve'
```

Use your own local credential in place of the example token. All variables are required, including the database password (which may be empty for local trust authentication). The server initializes the current ledger/audit schema in an empty database. Existing development databases may require recreation after schema edits; no upgrade compatibility is promised. The same environment variables configure the [current native executable](docs/validation/m6-native.md); run its absolute path with `serve` in place of the sbt invocation.

```sh
curl --fail-with-body \
  -H 'Authorization: Bearer 0123456789abcdef0123456789abcdef' \
  -H 'CQ-Session: 00000000-0000-0000-0000-000000000001' \
  http://127.0.0.1:8765/api/hello
```

Expected body: `{"version":"0.1.0","supported":["0.1.0"]}`. `/api/call`, `/ws` and `/mcp` use the same ledger/audit application service; see [contracts](docs/design/contracts.md). Open the configured origin and sign in with the operator token. The [completed browser workspace](docs/validation/m5-review.md) provides query editing, three panes, forms, history/restore, relationship actions, drafts/conflicts, scoped usage and connection diagnostics. The guardian and batch supervisor execute the four roles and workflows, with host validation, compact result handles and reviewed integration; [M4 technical review](docs/validation/m4-review.md) is approved. The native distribution is locally runnable; technical verification is recorded in the release evidence; human acceptance remains pending.

Pins, local compatibility patches and their failure evidence are documented in [dependencies](docs/design/dependencies.md).

## Requirements and planning evidence

- [Implementation plan and goal text](docs/drafts/20260926-1549-cq-implementation-plan.md) — executable milestones M0–M6, verification gates, requirement ownership, and a paste-ready `/goal` for the complete first release.
- [Requirements prompt](docs/drafts/20260926-0957-cq-requirements-prompt.md) — reusable prompt covering the original 27 requirements, four added requirements, confirmed decisions, and required full-design artifacts.
- [Design brief](docs/drafts/20260926-0957-cq-design-brief.md) — proposed architecture, four subagents, four workflow commands, reference-based dispatch, harness differences, shared usage audit log, and implementation milestones.
- [Existing CQ audit](docs/drafts/20260926-0957-existing-cq-audit.md) — source evidence, measured inventories, a reproduced project-gate fallback, and verification limits.
- [Harness usage observability](docs/drafts/20260926-usage-observability.md) — live Claude/Codex/Pi probes, observed token fields, accounting differences, and efficiency measurement limits.

Confirmed scope: fresh data, web UI and CLI, cohorts retaining item identity, enforced permissions for cooperative agents, restricted subgraph termination, and compact dispatch summaries with explicit drill-down. Subagents may be terminated with their governing harness; the brief chooses this simpler lifetime model.

Checked during planning: document links, requirement coverage R01–R31, embedded JSON syntax, source inventories, the isolated gate resolver probe, selected upstream documentation, installed harness CLI capabilities, and one successful token-usage probe per harness. Complete generated schemas and application/runtime verification are deliverables of the implementation milestones.

## Current CLI

Build a JVM launcher classpath from the repository:

```sh
./dev/generate
npm ci --ignore-scripts
npm run build
nix develop -c sbt --server --batch --no-colors ';server/compile;show server/runtimeClasspath;exit'
```

Use the emitted classpath with `java --sun-misc-unsafe-memory-access=allow -cp <classpath> cq.server.Main` from the consumer directory (inside `nix develop` or with Java 25 available). The option suppresses warnings from older dependencies' lazy values; it does not replace their deprecated calls. sbt and its forked applications receive this setting from `.jvmopts`. Native packages fix this mode when the image is built: the native gate passes the `.jvmopts` setting to `native-image` and fails if startup prints the warning; runtime options cannot change it. Set `CQ_TOKEN` to the operator or scoped credential. Supported commands:

```text
cq init --endpoint http://127.0.0.1:8765
cq init --project-id <existing-uuid> --endpoint <server-origin>
cq init --name "New display name"
cq query --query 'ledger:Tasks archived:all' --limit 20
cq query --query 'status:Re' --complete 9 --limit 20
cq query --roots T1,M1 --limit 50
cq status --task T1
cq status audit --task T1 --limit 20
cq status costs --task T1 --limit 20
cq status attempts --task T1 --limit 20
cq status outcomes --attempt <attempt-uuid> --limit 20
cq web
cq run codex --settings /absolute/settings.json --input /absolute/request.txt
cq run codex --settings /absolute/settings.json --input /absolute/request.txt --workflow begin
cq run claude --settings /absolute/settings.json --input /absolute/request.txt --workflow advance --roots G1 --through review
cq run pi --settings /absolute/settings.json --input /absolute/request.txt --workflow review --result <result-uuid> --mode candidate
cq run codex --settings /absolute/settings.json --input /absolute/request.txt --workflow upstream --roots U1 --action prepare
cq commands export codex --directory /absolute/consumer
cq job upload --session /absolute/session-directory
```

Cost summaries include a bounded first page grouped by attribution, currency, cost basis and pricing version. Continue with `status costs --after '<after-object-as-JSON>' --snapshot <cursor> --limit 20` using the returned key and cursor; concurrent audit changes require restarting the listing. Raw observations retain original amounts and pricing evidence.

Workflow commands use shared installed instructions and enforce execution scope/phase on the host. `commands export` supports `claude`, `codex` and `pi`; identical assets are idempotent, conflicts require explicit `--replace`. Select the generated project assets explicitly when older commands share their names; see [native invocation and collision instructions](docs/design/workflows.md). The supervisor receipt includes a bounded report: surface pending user questions and remaining work even when the process succeeds. [Worked-process and cohort evaluation](docs/validation/m4-review.md) has technical approval with retained quality qualifications and corrections. The original packaged nine-route corpus passed on the subsequently rejected artifact. The workflow-resource correction completed its fresh live corpus and both worked processes. The later browser correction has scoped retained harness evidence; human release acceptance remains pending.

The [query language](docs/design/query-language.md) is shared by CLI, MCP and browser. For example, `cq query --query 'ledger:Tasks status:Ready "retry deadline" NOT tag:blocked'`. Empty queries select active items; `archived:all` includes archived items. Continue with `--after T42 --snapshot <cursor>` from the returned page and restart on `Resync`. Invalid searches include UTF-16 source spans and return a nonzero CLI exit. `--complete <UTF-16 offset>` returns syntax diagnostics and up to 50 replacement suggestions through the existing read capability; it does not accept page continuation.

`job upload` requires the supervisor to have released its session journal. It replays committed batches unchanged and reconciles interrupted attempts with partial usage and unknown outcomes. Incomplete child tickets and unfrozen combination plans are retained and explicitly reported with a nonzero exit. Recovery still attempts independent frozen publications and integration acknowledgements; it never launches Git. See [recovery evidence](docs/validation/m2-recovery.md) and [combined-candidate evidence](docs/validation/m3-combination.md).

With an explicit `integrationTarget` in supervisor settings, the governor can prepare and apply a reviewed candidate. When the target advances, `Combine` returns a frozen plan handle for a conflict-resolution worker; the combined candidate requires fresh validation and review. Full prompts, merge diagnostics and worker results remain behind handles. [Integration contract](docs/design/git-integration.md).

Here `cq` denotes the installed `bin/cq` executable or the JVM launcher above. Server, client and supervisor commands use the same distage role entrypoint; native role syntax such as `cq :client -- web` and `cq :help` is also available. Client commands do not require local server/database configuration, and diagnostics go to stderr. [Client role checks](docs/validation/m2-roles.md); [supervisor configuration, run instructions and limits](docs/design/supervisor-role.md).

`web` prints the configured origin. Project configuration lives under the Git common directory (`cq/project.json`) or `.cq/project.json` outside Git. Worktrees share identity. Explicit `init --name` renames the server display; ordinary reattachment preserves that name and refreshes the local cache. `status` also supports `--cohort` and `--session`; omit scope flags for project totals. Commands emit generated JSON with lossless decimal strings. See [tested behavior and gaps](docs/validation/m1-interfaces.md).
