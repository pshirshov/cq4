# M1 durable core increment

This is an implementation increment, not the M1 exit verdict. No M1 Astra review or human acceptance is claimed.

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

## Implemented scope and remaining work

See [ledger contracts](../design/ledgers.md), [DDL](../../server/src/main/resources/db/001-ledgers.sql), `LedgerService`, both repository adapters and `LedgerContractTest`.

Remaining M1 work includes operational audit persistence/accounting (only initial models exist), real ledger/audit clients and concrete schemas, authenticated scoped credentials, browser/CLI project initialization, live replay/resnapshot, coherent multi-page snapshots, full relationship restoration, nested content/evidence validation and host-observed provenance enforcement, and isolated workspaces before agent execution. Query parsing, graph traversal, producer coordination, takeover, cancellation and Git integration remain assigned to their planned milestones.

Current transaction locking serializes a project's reads/writes; no throughput claim has been measured. Mutation code uses affected-row/index access, but query-plan evidence at increasing unrelated sizes is pending M3. No new native runtime claim is made for this increment; the M0 native artifact remains retained at its original evidence location. The provided-database configuration path is still unexecuted.

Retain this evidence under `/srv/nvme/tmp/cq4-implementation/` through release acceptance. It contains local test data rather than production credentials. No reference snapshot was modified.
