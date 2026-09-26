# M1 durable core increment

This is an implementation increment, not the M1 exit verdict. No M1 Astra review or human acceptance is claimed.

The original checks below describe commit `5f7aed5`. The user subsequently required one mutable development schema version. Multiple schema copies, evolution fixtures and signature-freezing behavior were removed; their old results below are historical evidence, not current requirements. See [current contract policy](../design/contracts.md).

## Checks

Run from the repository with `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation`:

| Command | Result | Evidence directory |
| --- | --- | --- |
| `./dev/check contracts` | Pass: generation determinism, strict TS/Scala compilation, codec exchange, evolution, MCP proof schema | `20260926T171835-contracts` |
| `./dev/check fast` | Pass: 8 scenarios, 0 skipped | `20260926T171937-fast` |
| `./dev/check postgres` | Pass: same 8 scenarios on PostgreSQL 18.6 plus actual JVM HTTP/WS/MCP proof client | `20260926T172038-postgres` |

Each directory contains `result.json`, exact command/exit records, source hashes and logs. The contracts check preceded the addition of the pure outcome classifier; schema/generation sources did not change between these checks. The later dummy/PostgreSQL checks compile the final core implementation. The transport check still exercises the M0 probe using CQ schema 0.2.0; it does not claim ledger HTTP/MCP/UI coverage.

The seven new shared service scenarios cover creation of all fourteen branches, two project identities, project reattachment, role/scope denial, sixteen concurrent creates by two sessions, eight deliveries of one idempotent request, conflicting request identity, transaction rollback, archive visibility, correction/restore history, inverse-edge normalization, both-endpoint history, duplicate edges, snapshot-to-change cursor handoff, paginated replay, overlapping claim sets, expiry and late fences. The eighth scenario is the existing M0 repository proof.

## Reproduced defects

- Replaying a released claim displaced a newly acquired owner's membership. `20260926T171623-fast/dummy-contract.log` reproduces the expected forbidden write being admitted. The service now returns the existing release without rewriting membership. Both adapters pass the retained regression.
- Baboon `create-only` left the newly generated 0.2.0 version absent from the lockfile. `m1-lock-before.json` preserves the old lock; `m1-unlocked-version-rejection.log` shows the new explicit rejection. `dev/generate --update-lock` registers new versions after checking that every previous signature remains identical. Normal generation refuses unregistered versions. `m1-lock-registration.log` records registration.
- The first evolved transport run correctly advertised 0.2.0 while the test still expected 0.1.0 (`20260926T171659-postgres/jvm-transport.log`). The proof client now explicitly requests/asserts 0.2.0. Production version negotiation remains explicit; 0.1.0 is retained as a schema history, not advertised as a supported ledger protocol.

## Single-version consolidation

Applied the subsequent user correction: all current contracts, including the new audit types, now live in one mutable `cq.api` 0.1.0 model. Historical copies and evolution fixtures were removed. The generator cleans its owned output directory, refreshes the current signature and requires explicit source changes to permit a version bump. `AGENTS.md` and the requirements/plan record the user's instruction.

| Command | Result | Evidence directory |
| --- | --- | --- |
| `./dev/check contracts` | Pass: deterministic generation, Scala/TS compilation, JSON/UEBA round trips, typed errors and MCP examples | `20260926T172943-contracts` |
| `./dev/check fast` | Pass: all 8 dummy scenarios | `20260926T173052-fast` |
| `./dev/check postgres` | Pass: all 8 real-database scenarios and JVM transport checks using 0.1.0 | `20260926T173103-postgres` |

Source inspection confirms one `.baboon` file and one signature version. Generated output contains no historical namespaces or evolution fixture. These checks supersede the schema-policy portions of the earlier increment evidence; M1 remains incomplete.

## Implemented scope and remaining work

See [ledger contracts](../design/ledgers.md), [DDL](../../server/src/main/resources/db/001-ledgers.sql), `LedgerService`, both repository adapters and `LedgerContractTest`.

Subsequent increments implement the audit, authenticated real clients, minimal browser and snapshot continuation guards; see the linked status page. Remaining M1 work includes compact/bounded results, project rename, actual-server restart evidence and isolated workspaces before agent execution. Query parsing, graph traversal, producer coordination, takeover, cancellation and Git integration remain assigned to their planned milestones.

Current transaction locking serializes a project's reads/writes; no throughput claim has been measured. Mutation code uses affected-row/index access, but query-plan evidence at increasing unrelated sizes is pending M3. No new native runtime claim is made for this increment; the M0 native artifact remains retained at its original evidence location. The provided-database configuration path is still unexecuted.

Retain this evidence under `/srv/nvme/tmp/cq4-implementation/` through release acceptance. It contains local test data rather than production credentials. No reference snapshot was modified.

## Nested validation and complete relationship restoration

Both adapters now reject malformed/oversized nested narratives, citations, evidence and review subjects before item allocation. Ordinary model writes cannot invent HumanReported or HostObserved evidence; unchanged previously admitted evidence can be preserved. Human actors may add human reports. Host evidence admission through execution artifacts belongs to M2. A draft is limited to 262,144 encoded UTF-8 bytes and nested collections to 64 entries.

Restore now reconstructs historical content and incident relationships in one transaction, with expected revisions and current claims for every affected neighbor. It appends history for both endpoints and preserves other neighbor content. Missing/stale neighbor revisions, active foreign claims or current graph invariant conflicts reject the whole change. One request touches at most 512 items. Browser restore controls remain M5 work.

Reproductions retained before implementation:

- `20260926T192813-fast/dummy-contract.log`: malformed nested input and fabricated provenance were accepted.
- `20260926T193157-fast/dummy-contract.log`: historical relationship restore returned the old content-only restriction.

Verification (same source, 18 shared scenarios):

| Command | Result | Evidence directory |
| --- | --- | --- |
| `./dev/check fast` | Pass | `20260926T193450-fast` |
| `./dev/check postgres` | Pass, including real transport and CLI | `20260926T193907-postgres` |
| `./dev/check contracts` | Pass: current Scala/TS codecs and concrete MCP schemas | `20260926T193955-contracts` |
