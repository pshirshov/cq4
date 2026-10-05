# Updating the local native release

`run-local.sh` starts `.local/release`; it does not build source. After committing changes, stop that launcher with Ctrl-C and wait for `CQ stopped`, then run:

```sh
./update-local.sh
./run-local.sh
```

Both commands use `CQ_LOCAL_STATE`, defaulting to `/srv/nvme/tmp/cq4-playground`. The updater requires an existing owned local state and installed package. It never signals a pre-existing server or database process. It holds the launcher lock for the entire update, so local startup remains unavailable while the build runs. Allow time for the native build and installed verification.

The operator-run updater snapshots HEAD into a detached worktree and refuses uncommitted runtime inputs. It runs `dev/package-local`: native compilation and bounded startup/API/browser-asset smoke against disposable databases. Testing is separate: `./test-local.sh` runs fast, UI, PostgreSQL and native gates, stopping on the first failure. Build evidence stays under `STATE/updates/ATTEMPT`. Any failure before installation retains the current release; inspect the named log. The candidate directory is retained for inspection. Retrying creates a new attempt and rebuilds it.

After verification it starts only the stopped local database, checks its migration checksum, creates a custom-format PostgreSQL backup, verifies its inventory, and stops the database. Equal schema/model hashes are accepted. The current transition is pinned in `dev/local-update-step.json` as `unchanged-data`: the schema is the installed one, and the model differs from the installed one only in request and reply types, so no stored data is transformed and the updater runs no SQL. The step names the schema hash and the model hashes before and after; a model transition that it does not name is refused, and so is every schema change, because the updater currently defines no schema step. Changing a checksum declaration alone is insufficient. Active claims, unfinished managed attempts and pending integrations prevent installation. Root attached Governor attempts identified by the attached-session collector may lack outer usage outcomes; those audit records remain unchanged and do not prevent replacement. The receipt and `unsettled-work.log` report each category separately, including the incomplete attached Governor count. This exemption does not establish that a harness has stopped; stop consumer runs before shutting down the launcher.

The updater moves the current release to `.local/release-before-ATTEMPT`, installs the verified candidate, and records the source revision, manifest identities and backup hash in `receipt.json`. Restart the launcher and reload the browser. Existing interactive host journals must be reconciled under the normal host recovery rules; this command does not start harnesses or settle their work.

An interruption during replacement attempts to restore the previous package. Unverified recovery leaves `STATE/.cq-update-recovery.json`, which the launcher refuses. Inspect its receipt and logs, stop any owned update processes, and restore the matching **previous package and database backup**. Verify their recorded hashes and the database's schema before removing the marker. Never launch a package against an unverified database. Keep the previous release and `before.dump` together; rollback after subsequent use also discards database writes made since that backup. Automatic pruning is deliberately absent.

Focused updater checks:

```sh
nix develop -c python3 dev/update-local-check.py -v
nix develop -c python3 dev/update-local-check.py --postgres -v
```

The shared replacement contract runs against in-memory and real-directory adapters. The focused check also verifies that the pinned step matches this tree's schema and model. The optional PostgreSQL case uses a disposable cluster, checks the retained backup and package replacement, verifies refusal on a mismatched schema, and exercises the rollback of a failed replacement. Full native/package gates are operator delivery evidence, not implied by these focused checks.
