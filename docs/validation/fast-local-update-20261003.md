# Fast native local redeployment, 2026-10-03

The operator requested fast redeployment with testing owned by the implementing agent and performed separately. Native compilation remains part of local deployment. `./update-local.sh` now builds a native package, runs bounded startup/API smoke, and uses the existing backup, schema compatibility and recoverable replacement protocol. It no longer runs fast/UI/PostgreSQL/native gates or the broad package-check matrix. `./test-local.sh` runs the four existing gates separately and stops on the first failure. Full native distribution packaging retains its native-gate requirement.

Evidence root: `/srv/nvme/tmp/cq4-fast-local-evidence`.

## Captured failure and bounded scope

The operator attempt at `f02981c66592bfa6626dc76d110481a03565e1f2` passed fast and UI, then failed `jvm-dispatch-shutdown` in the PostgreSQL gate. Ticket publication I/O was deliberately held and the supervisor remained alive after the fixture's 25-second exit bound. The receipt is `failed-before-install`; the observed installed manifest still matches its prior-package hash. Root cause and JVM/native applicability remain unknown. This change separates deployment from validation; it does not resolve that shutdown defect. Its Open intake is appended to [pending ledger intake](update-local-20261003-pending-intake.json), alongside the two earlier updater-related captures. The live server is stopped, so no live Defect IDs or status updates are claimed.

I29 scope follow-up: the user selected fast native local rebuilding and separate testing. The live I29 entry must record this increment after restart; its current revision was not read while the API was unavailable.

## Build and replacement

The updater snapshots committed source and rejects uncommitted runtime input differences. The build generates contracts and browser assets, compiles the source JVM, snapshots the prior native metadata, and collects a small current-source trace covering CLI, exported command templates/doctor, server startup, protocol, exact embedded assets and project initialization/search. It merges those metadata inputs and runs native-image with the existing pinned settings. Fresh traces close before merging; ownership and trace snapshots use the existing verifier. No harness or paid model is launched.

The resulting native executable and guardian use the common package assembler and recorded Nix runtime closure. The merged native configuration is included in the package and its file hashes, so subsequent local updates can use the installed copy. The first update can use the existing native package's recorded evidence directory. Missing metadata fails explicitly; deployment does not silently run the full tracing matrix.

The native package is smoke-checked against a second disposable database. The manifest records `local-smoke` only after success, binds the source revision and smoke-report hash, and makes no native-gate claim. The updater checks those fields before installation. Database backup, stopped-launcher ownership, settled-work requirements, the pinned schema transition, preserved prior package, interruption recovery and receipt handling remain in place.

## Verification

Before changing the build policy, the focused command-boundary test failed with actual commands `fast`, `ui`, `postgres`, `native`, `package`, `package-check`, expected only `package-local` (`build-before.log`). The corrected default invokes the native local build/smoke once. Completed/native/source-matched smoke is required; wrong revision, altered report and incomplete/failed smoke are rejected.

The test-command fixture initially reproduced a false success: a failed UI gate returned exit 0 and continued to PostgreSQL/native. The inner shell now enables fail-fast behavior. The retained focused test fails before this correction (`test-command-suite-before.log`) and passes afterward, including all-four-gates success and first-failure propagation. It uses controlled executable boundaries rather than running the full gates.

`nix develop -c python3 dev/update-local-check.py --postgres -v` passes **16 tests** (`install-postgres-final.log`), including shared dummy/filesystem recovery cases and the actual disposable PostgreSQL backup/install/rollback case. Node/TypeScript production sources are unchanged by this increment. Python compilation, shell syntax and diff whitespace checks pass.

A native build using the installed package's prior metadata passes the CLI, command-template, protocol, exact browser-asset and initialize/search smoke checks (`native-package-build-2.log`). Native-image reports **2m09s**. The initial smoke harness omitted its required session environment; that captured failure was corrected before claiming success. The final harness keeps trace/native report names separate and records completion after native smoke.

The complete updater on a disposable copy passes (`disposable-update-result.json`, `disposable-update.log`, `disposable-driver.log`): it builds the native package, applies the existing pinned driver-table step, preserves all prior table fingerprints, backs up the database, retains the old package, replaces the candidate, removes the recovery marker, stops the owned database and restarts the installed package. The retained project is read back through the actual API after restart. The measured update-plus-restart/readback interval is **207.06 seconds**, approximately 3m27s, on a disposable one-project database; larger backups and machine load can change deployment time. The installed fixture manifest reports `runtime=native`, `validation=local-smoke`, the exact snapshot revision and the matching smoke-report hash. Its executable has the ELF magic, and its packaged metadata is available for the next update. Stale full-native runtime-source evidence was separately rejected by the unchanged verifier. No installation into the operator's playground, full gate, broad package-check or model/schema edit is performed in this increment. The standalone full-native packager still refuses non-passing or source-mismatched native evidence.
