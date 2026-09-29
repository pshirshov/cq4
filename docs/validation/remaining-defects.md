# Remaining evaluation defects: delivery

Evidence root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.
Version remains **0.1.0**. D26 (keyboard status bar) and D27 (navigation
resizing) remain reserved for later CQ exercises. Human acceptance is pending.

## Implemented scope

| Record | Change | Scoped evidence |
| --- | --- | --- |
| D29/D30 | Navigation icons/counts and a sorted, scrolling table with ID, type, title, status and severity | [Table](results-table.md) |
| D38/D39/D40 | Structured help, readable output, explicit `--json`, Warning-level startup logging | [CLI](operator-cli.md) |
| I2 | Consistent project archives and transactional restore with collision refusal | [Archives](project-backup.md) |
| D41 | Reviewed integration into the governing checkout while HEAD remains attached | [Git preservation and recovery](checked-out-integration.md) |
| D42 | Native-thread Codex response accounting, durable deduplication and explicit coverage gaps | [Accounting](attached-codex-usage.md) |
| D25 | Still Open: no safe dependency remedy established; the candidate transformation hangs CQ startup | [Measured blocker](scala-lazyvals-warning.md#rejected-remedy-2026-09-29) |

Each implementation increment has independent Astra source review and retained
failing reproductions followed by passing scoped checks. The verified package is
installed and running at `http://vm.home.7mind.io:8080`.

## Operator delivery, 2026-09-29

The user ran the approved host update. `operator-update/receipt.json` records
successful installation, a full retained backup and unchanged original table
fingerprints. All installed package files match the approved manifest; the
regenerated Codex configuration forwards `CODEX_HOME`.

`operator-live.json` and `operator-table.png` record actual-hostname login and
WebSocket connection, all five table columns, severity sorting, navigation counts
matching the server (30 unarchived defects), and a 1366-pixel viewport without
horizontal page overflow. The read-only browser check caused no project changes
and recorded no page errors. Installed readable query/status and `--json` query
commands also pass.

`closeout-before.json`, `closeout-request.json`, `closeout-result.json` and
`closeout-after.json` retain revision-checked atomic CQ closeout and readback:
D29/D30 and D38–D42 are Resolved at revision 2; I2 is Accepted at revision 2.
Their evidence is explicitly ModelDeclared and does not assert human acceptance.
D25 remains Open at revision 2, and D26/D27 remain untouched at revision 1.
The installed CLI confirms these are the only three open defects.

## Final verification

The first final gate, `20260928T233053-native`, passed all 18 JVM tracing
fixtures, then failed because the verification driver omitted `import runpy`
before the archive fixture. That failure remains intact. Commit `d7adff5` adds
the import; no runtime code changed.

`resume-native.py` verifies the predecessor error, driver hash, import-only
source difference and successful fixture receipts. It retains those traces and
reruns archive tracing, native compilation and the complete native corpus in
`20260928T235722-native`. `resumed-from.json` records this boundary. All 27
continuation commands passed, including the complete native runtime/browser
corpus and project archive checks. Astra independently approved both the
continuation procedure and the final native evidence/package linkage.

The source-isolated package gate, `20260929T000819-installed`, passed all 29
commands with the source checkout and classpath unavailable. It includes the
runtime/browser corpus, archive checks and database backup/restore/restart.
`codex-packaged-final` passes on the same executable: Codex dispatched a Pi
investigation through the installed CQ tools. It reconciles 14 retained outer
responses / 544,669 tokens against 15 native responses. Native owner teardown
left no frozen end window; recovery publishes one pending batch, then zero on
repeat. Final-tail coverage remains partial; outer task/model grouping and cost
remain unknown.

`operator-rehearsal` passes for the exact updater and package pair. The first
fixture attempt failed before database startup because hard links crossed
sandbox mounts; `operator-rehearsal-link-failure` retains that failure. Ordinary
copies corrected fixture setup. The successful rehearsal preserves credentials
and all original data, restores the saved backup into another database and
compares all 24 original table fingerprints. Native persistence and the
backfilled severity projection pass. This does not update the operator database.

Astra independently approves technical delivery of this batch, including
installed verification, the real consumer's explicit accounting limits, staged
artifact identity and exact updater rehearsal. Operator installation and live
verification now pass as recorded above; human acceptance remains pending.

The expensive nine-route model matrix is not
repeated: the real consumer check is scoped to the changed Codex observer;
integration, archive and UI behavior have deterministic runtime checks.

Verified artifact: `cq-release-remaining-defects` under the evidence root. The
staged copy has been installed at `.local/release`.

- Manifest SHA256: `31914b285447019ee59b1314159928a4381cd6674838713671b9391d5c5c8b03`
- Executable SHA256: `576885b9e32b0a7d6b2f5673fc2bcc6b3c4bea189b59dc519fcb89514cb6b243`
- Guardian SHA256: `f56efb4626cb8e0fab0cc4b27b1460e75cea275890b5209df0a7f312ddf029bc`

## Operator update boundary

The table's compact severity projection changes the current development schema
in place. The one-time updater checks the exact predecessor and candidate
package manifests, requires a stopped launcher/database, saves a full PostgreSQL
backup, applies the checksum-guarded projection transaction and compares every
original table's data. It then stops its temporary database and replaces the
package, retaining the predecessor package. Credentials are preserved.

This is not an atomic transaction across PostgreSQL and package directories.
An interrupted update requires inspection of its receipt and retained backup;
an exact already-applied schema can be verified on a guarded rerun. There is no
runtime schema fallback, historical contract version or automatic rollback of
operator data.

## Run on this machine

The completed one-time host installation used:

```sh
bash /tmp/exchange/cq-remaining-update.sh
```

The wrapper verified the prepared updater/configuration/SQL hashes, performed the
backed-up update, regenerated the Codex MCP integration (including `CODEX_HOME`
forwarding), and started the permanent `run-local.sh`. Reload the browser once
after this update. Receipt and full database backup are retained under the
evidence root's `operator-update` directory; the old package is retained at
`.local/release-before-remaining-defects`.

Subsequent server launches use the permanent script:

```sh
cd /home/pavel/work/safe/cq4/cq4
./run-local.sh
```

The exchange wrapper is one-time deployment tooling. The `environment` skill
requires host execution because the running launcher/database are outside this
agent's PID namespace. It does not signal or take over existing host processes.
After the update, [normal harness startup](../interactive.md) still uses `yolo
--profile work --env CQ_TOKEN_FILE=/srv/nvme/tmp/cq4-playground/token codex`.
