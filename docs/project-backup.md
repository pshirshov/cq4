# Project backup and restore

Use the installed `cq` with operator credentials:

```sh
export CQ_TOKEN_FILE=/path/to/operator-token
cq backup PROJECT_UUID /path/to/project.cqbackup
cq restore /path/to/project.cqbackup --endpoint http://localhost:8080
```

Without `--endpoint`, these commands use the checkout's saved endpoint, then
`CQ_ORIGIN`, then `CQ_ENDPOINT`. They also work outside an initialized checkout.
Add `--json` to receive the typed archive manifest. `cq help backup` describes
the limits and recovery behavior.

Stop sessions working on the project and settle active claims, running attempts
and pending integrations first. Backup rejects those states. The governing attempt
of an attached session that has no outcome is open, not running (see
[usage audit](design/usage-audit.md)), and does not prevent a backup: no CQ host
observes it, and a session that ended without its host leaves it open for good. It captures one
consistent database snapshot; changes committed afterward are excluded.

The archive preserves the project UUID and stored items, relationships, history,
request receipts, counters, usage audit and projections, artifacts, completed
admissions, integration records, stored worksets and the project's settings
(its standing requirements, its process mode and its own layer of the agent
configuration). Restore refuses an existing project UUID.

The server's default agent configuration belongs to the installation, not to a
project, and is in no archive: a restored project uses the defaults of the server
it is restored into, with its own layer on top. Restore applies the checks of the
write path to the project's layer and refuses an archive whose layer does not pass
them. A database dump of the installation holds the server defaults.
Backup refuses an existing destination file, including one created concurrently.
Successful restore advances the live project catalogue cursor.

Git repositories, local harness journals, settings and credentials are separate.
Preserve them separately when needed; an archive cannot recreate checked-out
source or resume a local executor. Restoring a project does not attach a checkout.
Use `cq init --project-id PROJECT_UUID --endpoint URL` afterward when appropriate.

Archives contain private project data and are written with owner-only permissions.
Restore only trusted CQ archives. Entry hashes detect corruption; they are not
signatures authenticating an archive's author. The file must match the current CQ
schema and PostgreSQL major version. There is one development schema and no
archive conversion or version upgrade mechanism.

The schema hash does not cover the JSON stored in a row. When a release changes
a stored type, an archive written before it can match the schema and still be
refused. The release that added attempt outcomes to driver cycles refuses an
earlier archive holding a driver with a cycle (`Archive holds an undecodable
driver`); [the local update](local-update.md) converts the database, not archives.
The release that added the installation's agent configuration and the reasoning
effort of a stored attempt changed the schema, so it refuses every earlier archive
by its schema hash (`Archive does not match the current CQ schema`) before a row
is restored. So does the release that added the count of attempt events to the
usage clock and the index of unreleased claims.

The compressed archive and expanded table payload are each limited to 512 MiB.
Transfer has a five-minute deadline. The server streams through temporary files;
validation and insertion run in one transaction, with database constraints and
project scope enforced. Current archived items must be terminal; nonterminal
archives are rejected. Unarchive those items through CQ before taking a new
backup. Historical revisions are preserved unchanged. Invalid archives do not
partially install a project.

A connection failure during commit can leave a successful restore with a lost
reply. Inspect the target project before retrying. A repeated restore will refuse
the existing UUID; it never overwrites or deletes the project.

[Verification and remaining delivery status](validation/project-backup.md).
