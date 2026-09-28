# Run, back up and recover CQ

These instructions cover the single `0.1.0` Linux x86-64 distribution. See [package evidence](../validation/m6-package.md) for its verification state. PostgreSQL, Git and configured harnesses remain external dependencies. The installed application needs no Java runtime or CQ source checkout.

## Start an installed instance

Import `runtime.nar` and retain its paths as described in the distribution's `README.md`. On a multi-user Nix installation the unsigned archive requires a trusted importing user. Keep the distribution directory and its runtime GC roots.

Create an empty PostgreSQL database and an account with access to it. Copy `examples/server.env` to a private location, replace every example credential/address and source it in the server shell. The password variable is required even for empty-password local authentication. Then:

```sh
source /absolute/private/server.env
/absolute/cq-release/bin/cq serve > /absolute/private/cq-server.log 2>&1 &
server_pid=$!
```

Wait for this authenticated request to succeed before using clients:

```sh
curl --fail-with-body \
  -H "Authorization: Bearer $CQ_TOKEN" \
  -H 'CQ-Session: 00000000-0000-0000-0000-000000000001' \
  "$CQ_ORIGIN/api/hello"
```

Expected body: `{"version":"0.1.0","supported":["0.1.0"]}`. Open `CQ_ORIGIN` in a browser and sign in with the token. The token belongs in the host environment, not a consumer request or repository file.

From an unrelated consumer directory:

```sh
cd /absolute/consumer
/absolute/cq-release/bin/cq init --endpoint "$CQ_ORIGIN"
/absolute/cq-release/bin/cq query --query 'archived:all' --limit 20
/absolute/cq-release/bin/cq status
/absolute/cq-release/bin/cq commands export codex --directory "$PWD"
```

Choose `claude` or `pi` for their command assets. Existing asset conflicts require explicit `--replace`; inspect them first. See [workflow discovery and collisions](workflows.md). For supervised work, edit the packaged `examples/supervisor.json` with the installed guardian path, private state root, exact harness/model/provider/version and consumer validation commands. The consumer must be a Git checkout with a committed base. Launch from that checkout:

```sh
/absolute/cq-release/bin/cq run codex \
  --settings /absolute/private/supervisor.json \
  --input /absolute/private/request.txt \
  > /absolute/private/receipt.json 2> /absolute/private/supervisor.log &
supervisor_pid=$!
```

The receipt records the actual session directory and bounded result. A successful process exit alone does not establish task acceptance. Settings with `integrationTarget: null` produce candidates; [reviewed integration](git-integration.md) requires an explicit full target branch and its host checks.

## Stop and reconcile a session

Stop new work first. For processes launched in the same shell as above, request graceful termination and wait for the owned process:

```sh
kill -TERM "$supervisor_pid"
wait "$supervisor_pid"
```

For service-managed processes use that service manager's stop/wait operation. Do not infer current process ownership from a numeric PID in an old journal. Inspect the exit status, receipt and supervisor log. Exit **75** means unresolved shutdown; it does not prove descendants stopped or usage was delivered. Confirm the owned guardian hierarchy has settled before taking a consistent filesystem backup. Preserve all session files even if the journal lock can now be acquired.

Keep CQ available while reconciling. From the configured consumer directory, with the host token set, use the exact session path printed in the receipt/log:

```sh
/absolute/cq-release/bin/cq job upload --session /absolute/private/cq-sessions/SESSION
/absolute/cq-release/bin/cq status --session SESSION_UUID
/absolute/cq-release/bin/cq status attempts --session SESSION_UUID --limit 20
/absolute/cq-release/bin/cq status audit --session SESSION_UUID --limit 20
```

Inspect each returned page and follow its continuation when present. Current audit state, including `Unknown` outcomes and coverage gaps, is authoritative for delivered accounting; the old receipt remains a historical observation. Repeat upload after a transient delivery failure: committed batches retain the same identities, so acknowledged spending is not counted again. Upload acquires exclusive session ownership and rejects a live supervisor. It never launches a model or another Git update. Recovery uses the endpoint recorded in that session’s `run.json`; changing the consumer endpoint with `init` does not redirect it. Preserve the recorded endpoint when restoring recovery state. There is no supported retained-session endpoint relocation command.

Nonzero upload with incomplete tickets, unfrozen plans, quarantined workspaces or unresolved integration requires investigation of the reported paths. Preserve them and the Git repository. Deleting journals, editing SQL state or waiting for claim expiry cannot establish whether a Git effect happened. Recovery can replay a frozen publication or reconciliation observation; an ambiguous effect remains unresolved. See [interrupted publication](supervisor-role.md#interrupted-publication) and [integration recovery](git-integration.md).

## Settled database backup and restore

This procedure preserves the CQ database. It does not reconstruct a supervisor's local files or consumer Git state. Stop all supervisors, reconcile their publications, verify settlement and stop other writers before stopping the CQ server:

```sh
kill -TERM "$server_pid"
wait "$server_pid"
```

Configure PostgreSQL client credentials for the source database separately from CQ's JDBC URL. The following values are examples; use the actual server/account/database and a private password source such as `.pgpass`:

```sh
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=cq PGDATABASE=cq
psql --no-psqlrc --set ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM cq_integration_members)
     OR EXISTS (SELECT 1 FROM cq_usage_attempts WHERE effective_outcome IS NULL)
     OR EXISTS (SELECT 1 FROM cq_claims WHERE NOT released
                AND expires_at > extract(epoch FROM clock_timestamp()) * 1000)
  THEN
    RAISE EXCEPTION 'CQ is not settled: inspect integration holds, attempts and claims';
  END IF;
END $$;
SQL
```

Run the dump only if that check succeeds. It checks database state, not OS process settlement; both preconditions are required. Retained terminal `Unknown` outcomes remain explicit historical gaps and do not become complete after backup.

```sh
mkdir /absolute/private/cq-backup
pg_dump --format=custom --file /absolute/private/cq-backup/cq.dump
sha256sum /absolute/private/cq-backup/cq.dump > /absolute/private/cq-backup/cq.dump.sha256
```

Restore into a **new empty database**, with an account authorized to create it. Keep the source database intact:

```sh
sha256sum --check /absolute/private/cq-backup/cq.dump.sha256
createdb cq_restored
pg_restore --dbname=cq_restored --exit-on-error --no-owner --no-privileges \
  /absolute/private/cq-backup/cq.dump
```

Make a separate server environment file pointing `CQ_DATABASE_URL` to `jdbc:postgresql://127.0.0.1:5432/cq_restored`, with matching credentials. Start the same installed binary using that file; repeat the authenticated hello check, consumer query and usage commands. The restored database retains project IDs, so the original consumer configuration can be used with its endpoint set to the restored instance. Compare retained task history, archived items, artifact reads and usage with the pre-backup observations before accepting the restore. Avoid attaching active supervisors to both instances.

The executable backup check does more than this operator smoke check: it compares all 24 table fingerprints and native API snapshots, verifies artifact bytes and immutable accounting, retries an acknowledged command and confirms the item counter continues. [Evidence and exact verification command](../validation/m6-package.md#settled-database-backup).

## Retention boundaries

| Data | Location and consequence of removal |
| --- | --- |
| Items, revisions, relationships, claims/reservations, artifact bytes, assignments, observations, corrections and accounting projections | PostgreSQL. Preserve the complete database, including audit tables. Ending or archiving a task/cohort does not delete its numerical history or frozen membership. |
| Journals, tickets, delivery batches, native streams, validation diagnostics and workspace ownership | Configured supervisor `stateRoot`. Preserve the entire tree for delivery/reconciliation and inspection. A database dump cannot restore it. |
| Candidate/integration commits and worktree registrations | Consumer Git common directory and the associated CQ worktrees. Preserve these together with supervisor state; a source-file copy or branch-only bundle is insufficient. |
| Runtime executable/libraries and settings | Distribution, Nix store paths/GC roots and private configuration. Retain the tested artifact and exact harness pins when reproducing evidence. |

CQ has no automatic deletion or retention job, nor an artifact purge command. Manual deletion can remove evidence needed for recovery or leave references whose content is unavailable; it is not supported retention. For a restorable whole installation, snapshot the settled database, complete session trees and consumer repositories/worktrees at the same boundary, preserving original absolute paths and permissions. Reconciliation and worktree records validate those paths. Keep archived evaluation evidence separately from ordinary user data and retain failed attempts when reporting experimental spending.
