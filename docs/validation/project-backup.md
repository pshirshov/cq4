# I2 project archive verification

Evidence root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.

The archive contract is generated from the current Baboon model. A narrow
`ProjectArchives` boundary contains PostgreSQL binary COPY and transaction
semantics. HTTP authorization and filesystem transfer remain outside that
adapter. These checks deliberately use real PostgreSQL: a dummy would not prove
COPY, snapshot isolation, constraint rollback or commit acknowledgement behavior.

`archive-before` captures the preceding installed CLI rejecting the absent
backup command. `archive-first` is a failed test setup: browser assets were absent
after generation. Building them before exporting the classpath corrected setup.

`archive-fail-commit` reproduces Astra's source finding. A PostgreSQL protocol proxy
discards a successful COMMIT acknowledgement. An independent connection confirms
the complete project was committed, while the original error incorrectly claims
no restore was committed. The correction reports uncertain completion and asks
the operator to inspect the project before retrying.

`archive-corrected/project-archives/result.json` passes the actual CLI/HTTP/store
suite:

- All 23 project tables match the source snapshot exactly after restore; another
  project is excluded. Artifact bytes include Unicode and NUL. Request, artifact
  and audit replay receipts remain identical, and item allocation continues.
- Holding a later COPY while committing an item edit leaves every archived table
  at the earlier snapshot, with no mixture of old and new records.
- Active claims, running attempts and pending integrations reject backup. The
  pending-integration fixture injects a controlled SQL record for that predicate;
  it is not an independently executed integration workflow.
- Existing archive files and project UUIDs are preserved on collision. Restored
  projects advance the catalogue cursor. Scoped governor credentials cannot
  export or restore archives.
- Wrong schema/PG major, hashes/counts, missing/extra/path-traversal entries,
  declared oversized entries and another project's rows are rejected.
- A validly hashed archive with an invalid relationship foreign key fails during
  insertion and leaves no project installed.
- The actual lost-COMMIT-acknowledgement case now reports uncertainty accurately.

The fixture is part of both JVM tracing and native transport checks, with separate
evidence directories for each phase. Native and source-isolated package checks
now pass; operator installation and verification also pass. I2 is Accepted with
implementation evidence; human acceptance remains pending. Archive format
hashes provide integrity, not source
authentication; [operator instructions](../project-backup.md) require trusted
archives and describe external-state exclusions.

Final scoped repeat: `archive-final2/scoped-project-archives/result.json` passes
with archived items, labels, exact decimal cost rows, and restore-side active
attempt rejection added. The preceding `archive-final` attempt failed during
fixture setup because its monetary amount used a string instead of the generated
`DecimalAmount` object. No archive implementation change was needed.

The deterministic contracts gate passes at
`.work/evidence/20260928T220500-contracts`. Astra approves the inspected JVM source
and corrected archive evidence with no blocking or major findings. Final native,
relocated-package and exact operator-updater checks also pass with independent
technical delivery approval. [Aggregate evidence](remaining-defects.md).
