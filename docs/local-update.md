# Updating the local native release

`run-local.sh` starts `.local/release`; it does not build source. After committing changes, stop that launcher with Ctrl-C and wait for `CQ stopped`, then run:

```sh
./update-local.sh
./run-local.sh
```

Both commands use `CQ_LOCAL_STATE`, defaulting to `/srv/nvme/tmp/cq4-playground`. The updater requires an existing owned local state and installed package. It never signals a pre-existing server or database process. It holds the launcher lock for the entire update, so local startup remains unavailable while the build runs. Allow time for the native build and installed verification.

The operator-run updater snapshots HEAD into a detached worktree and refuses uncommitted runtime inputs. It runs `dev/check fast`, `ui`, `postgres`, `native`, then `dev/package` and the source-isolated `dev/package-check`. Their evidence stays under `STATE/updates/ATTEMPT`. Any failure before installation retains the current release; inspect the named log. The candidate directory is retained for inspection. Retrying creates a new attempt and reruns the checks.

After verification it starts only the stopped local database, checks its migration checksum, creates a custom-format PostgreSQL backup, verifies its inventory, and stops the database. Equal schema/model hashes are accepted. The current D123 transition is pinned in `dev/local-update-step.json` and `dev/local-update.sql`: it adds durable driver storage and a project revision counter, checks existing data fingerprints, and restores the database backup if replacement fails. It accepts the installed wave 5 and the help-catalog model as inputs. Unknown schema or model transitions are refused; changing a checksum declaration alone is insufficient. Park and reconcile previous in-memory drivers before stopping the old server: their state cannot be recovered after that process exits. Active claims, attempts and pending integrations prevent installation.

The updater moves the current release to `.local/release-before-ATTEMPT`, installs the verified candidate, and records the source revision, manifest identities and backup hash in `receipt.json`. Restart the launcher and reload the browser. Existing interactive host journals must be reconciled under the normal host recovery rules; this command does not start harnesses or settle their work.

An interruption during replacement attempts to restore the previous package. Unverified recovery leaves `STATE/.cq-update-recovery.json`, which the launcher refuses. Inspect its receipt and logs, stop any owned update processes, and restore the matching **previous package and database backup**. Verify their recorded hashes and the database's schema before removing the marker. Never launch a package against an unverified database. Keep the previous release and `before.dump` together; rollback after subsequent use also discards database writes made since that backup. Automatic pruning is deliberately absent.

Focused updater checks:

```sh
nix develop -c python3 dev/update-local-check.py -v
nix develop -c python3 dev/update-local-check.py --postgres -v
```

The shared replacement contract runs against in-memory and real-directory adapters. The optional PostgreSQL case uses a disposable cluster, checks the retained backup and package replacement, verifies refusal on a mismatched schema, and exercises backup restoration after a failed transformation/replacement. Full native/package gates are operator delivery evidence, not implied by these focused checks.
