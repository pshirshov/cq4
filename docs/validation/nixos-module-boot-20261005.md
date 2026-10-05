# I28: NixOS module boot and module checks, 2026-10-05

Scope: the first systemd boot of `nixosModules.default`, two defects of the module that the boot exposed, and two runnable checks kept in the repository: `dev/nixos-module-check` and `dev/home-manager-module-check` ([usage and coverage](../declarative-installation.md#checking-the-modules)). Neither is called by `dev/check`, `test-local.sh` or the updater. Branch `x5/ideas` on `6850f2b`. Logs: `/srv/nvme/tmp/cq4-crosscut3/logs/x5-ideas/i28/`.

## What ran

`pkgs.testers.runNixOSTest` from the nixpkgs the flake pins, as a Nix build with the `kvm` system feature in the Nix build sandbox: one QEMU guest, 2048 MiB, user-mode networking, the host store shared read-only by the test driver, a fresh disk image per run. The guest configuration is `services.cq` with the defaults (managed PostgreSQL 18, loopback, port 8080), the package from `lib.mkNativePackage`, and a test-only unit that writes a random token and database password to `/var/lib/cq-test-secrets` on the first boot. No credential exists at evaluation time.

Two releases were used:

| Release | Source in its manifest | `bin/cq` SHA-256 | Log |
| --- | --- | --- | --- |
| `/srv/nvme/tmp/cq4-final-wave-20261004/release-352db52` | `352db52` | `9c4c154a…` | `nixos-module-boot.log` |
| `.local/release` of the main checkout, read-only | `6850f2b` | `5845e890…` | `nixos-module-boot-installed.log` |

`352db52` and `6850f2b` differ only in `dev/dispatch-shutdown-check.py` and `dev/shutdown-stall.c`, so both are native builds of the same product source; they are different binaries. The second is the release the operator's server runs. In both cases the Nix package patches the executables' interpreter and library path, so the bytes that ran in the guest are not the bytes of the release directory.

## Defects found by the boot

**The password unit ran before the role existed.** First run (`vm-boot-before-ordering-fix.log`):

```
postgres[831]: [831] ERROR:  role "cq" does not exist
python3[820]: CQ database password setup failed; SQL and credential contents are withheld
systemd[1]: Failed to start Set CQ database credential from a runtime systemd credential.
systemd[1]: Dependency failed for CQ ledger server.
```

The units were ordered after `postgresql.service`. In the pinned nixpkgs the role and database of `ensureUsers`/`ensureDatabases` are created by `postgresql-setup.service`, and `postgresql.target` is reached after it. `cq-database-password.service` and `cq.service` now require and follow `postgresql.target`. With that change alone the server started and the doctor passed. The earlier fixture executed the generated password script against a prepared cluster and module evaluation does not order units, so neither could show this. On a fresh machine with this nixpkgs the uncorrected module did not start CQ.

**An orderly stop was recorded as a failure.** The server exits with 143 on SIGTERM. Asserting the unit state after `systemctl stop cq.service` failed first (`vm-stop-result-before-fix.log`):

```
systemd[1]: cq.service: Main process exited, code=exited, status=143/n/a
systemd[1]: cq.service: Failed with result 'exit-code'.
AssertionError: ['ActiveState=failed', 'Result=exit-code']
```

The unit now declares `SuccessExitStatus = 143`; the same assertion passes.

## Assertions and results

All passed on both releases, in this order:

- `cq.service` active and `/api/hello` answering with the generated token; `postgresql.service` active.
- `cq doctor server --endpoint http://127.0.0.1:8080 --require-settled --json` in the guest: `current` true and Credential, Server, Model, Source, Schema, PostgreSQL, Durability and Unsettled work all Current. Repeated after the service restart and after the reboot.
- Unit `User` is `cq` and the main process is owned by `cq`.
- Each credential file is `600 root`; `runuser -u cq -- cat` exits 1 with `Permission denied`.
- `grep -rlF` of each credential value over the unit text, unit properties, the server's process environment, both unit files and every store path named in the units exits 1 with no output (2 would mean an unreadable path).
- `cq init --name … --json` in a Git checkout returns `Initialized`; a Task created by `Change` over `/api/call` is read back by `Read` and by `cq query`.
- After `systemctl restart cq.service`: new main process, same Task.
- After `machine.shutdown()` and a second boot from the same disk: same Task, and the password unit succeeds again.
- After `systemctl stop cq.service`: `ActiveState=inactive`, `Result=success`, PostgreSQL still active.

The test script ran in about one minute per release.

`dev/home-manager-module-check` passed on the installed release (`home-manager-module-installed.log`): generation built with Home Manager `7834e82` (the `home-manager` package source of the pinned nixpkgs), package on the profile path, `CQ_TOKEN_FILE` exported, recursive non-overwriting links, a store token refused, and `cq doctor commands` Current for Claude (6 files), Codex (6) and Pi (4). Separately and outside the repository check, `cq doctor harness` reported Current for all three harnesses over a generation of the same release with this machine's pinned harness executables and seeded trust files (`doctor.py`, `home/result.json`).

## Observation not corrected

A TCP connection closed without a request (the test driver's port probe in an earlier run) makes the server log `Fiber errored out due to unhandled error=org.http4s.ember.core.EmberException$EmptyStream` with a stack trace. The server kept serving. The check now probes with an HTTP request. A health probe of a load balancer would produce the same log entries.

## Limits

- One boot per release on one machine; no repetition and no load.
- The credential files are created by a unit of the test in `/var/lib`; a deployment supplies them through its own secret manager, usually under `/run`, whose ordering against `cq-database-password.service` this test does not exercise.
- The store search covers the store paths the two units name, not the whole system closure.
- Not booted: `database.managed = false`, replacing one release with another (the schema-checksum refusal), a non-loopback `listenAddress` or origin, a browser session, any harness, and operator Home Manager activation.
- The units depend on `postgresql.target`; a nixpkgs whose PostgreSQL module lacks that target is not supported.
- The `30`-second stop bound was not exercised with unsettled work.
