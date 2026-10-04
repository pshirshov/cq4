# Declarative installation source verification, 2026-10-04

Scope: I28 / T63, plus D137 / T67 package-helper correction, in the combined delivery worktree. This records observed source validation, not an installed release verdict. Operator deployment and delivery review remain outstanding. API version remains 0.1.0.

## Implementation

The flake exports an explicit native-package constructor, a NixOS server module, and a Home Manager CLI/project-integration module. Nix configuration contains runtime credential filenames and provider environment names rather than credentials. Server credentials accept bounded UTF-8 runtime files. The server publishes authenticated installation/schema/PostgreSQL diagnostics; scoped credentials cannot read them. Asset export uses the actual consumer root while writing into an independent build directory. Existing imperative configuration remains available.

The read-only doctor checks server identity/reachability/schema/durability and optional settlement, or settings/pinned harness version/generated assets/persisted hook trust. Modified or undetermined source remains Unknown. Version probes run with no CQ/provider credentials and an immutable empty configuration directory. Codex hook hashes come from its supported hooks/list protocol: a separate bounded recorder creates its own private runtime without login credentials. Doctor only reads a byte-bound report and persisted approvals. Pi trust uses the nearest canonical project/parent-folder decision in agent trust.json; both supported Pi versions use that gate.

## Focused observations

Evidence root: `/srv/nvme/tmp/cq4-final-wave-20261004`.

- ServerCredentialsLocal: 5 passed (`i28-credentials-complete.log`). Inline precedence, bounded file input, newline handling and invalid UTF-8 refusal. The original host reader already rejects malformed UTF-8; no decoder patch was made.
- InstallationDoctorDummy and InstallationDoctorLocal: 5 passed each (`i28-doctor-dummy-final.log`, `i28-doctor-local-final.log`). Identity/schema/major/durability/source uncertainty, settlement and secret-safe errors. Local variant uses an actual HTTP fixture.
- CliDoctorLocal: 5 passed (`i28-cli-doctor-final.log`). The new boolean flag test first failed with Unknown option --require-settled (`i28-cli-flag-before.log`); the parser now recognizes it as a flag before --json.
- AttachedAssetsLocal: 6 passed (`i28-assets-final.log`). Existing merge/conflict rules remain checked.
- HarnessDoctorLocal: 6 passed (`i28-pi-trust-after.log`). Pi trust regression first failed because a project without persisted trust reported Current (`i28-pi-trust-before.log`). The correction validates the complete trust object, skips null entries and applies the nearest canonical boolean decision, without writable trust-store locking.
- TypeScript type check and browser asset build passed (`i28-typescript.log`, `i28-web-build.log`) after generation. These are focused checks, not configured host gates.
- Actual private Durable PostgreSQL/server fixture passed (`i28-server-passing.log`, `i28-server-passing/`). Installation diagnostics report current/applied schema, PostgreSQL 18 and enabled durability. Invalid and scoped credentials return 401. An active Worker makes the settled check fail; the attached Governor collector is excluded. A database data dump is unchanged by doctor calls. Runtime credential files are used by the server subprocess.
- Actual pinned Codex 0.159.2 metadata fixture passed (`i28-real-codex-final.log`, `i28-real-codex-final/result.json`). Fresh public hook hashes accept seeded approvals; revoked approvals and stale asset-byte bindings fail. Project bytes/mtimes and immutable probe home are unchanged. This is authentication-free test data, not an interactive approval or G1 evaluation.
- NixOS module evaluations pass for managed/existing PostgreSQL and refuse store credential paths, disabled durability and invalid role identifiers (`i28-nix/result.json`). Existing-database mode creates no managed database/password unit.
- Actual Home Manager module evaluation passes assertions and exposes recursive, non-force project files (`i28-hm-evaluate-final.json`). Validation uses a retained Home Manager source revision recorded under `hm-validation/`; it does not change project flake inputs or activate the operator home.
- D137 failing-before output records the missing explicit database role in standalone helpers. Both helpers now request Durable. Traced JVM and native local smoke now pass (`package-daf189a-retry.log`, `package-daf189a/native-smoke.json`). The package embeds clean source revision daf189a8fa422e4c7d14a50ae1253a5d4f6ce4df. Two further evaluation helper call sites reproduced the same TypeError (`i26-evaluation-helpers-before.log`); attached-native-eval now declares Durable and adapter-probe declares Functional. Actual private-cluster readback for both corrected calls passes (`i26-evaluation-helpers-passing/result.json`).

## Native and declarative builds

The native local package at `release-daf189a/` passes command, asset, harness-doctor, durable server-doctor, protocol and embedded-browser smoke. Its manifest is local-smoke evidence, not an operator release-gate verdict. The Nix package constructor and actual Home Manager assets for all three harnesses build successfully (`i28-build-assets-final.json`); the pinned Home Manager validation revision is acd21c5a3420a9d5fd0ed06299b10828267ef9ba.

`i28-native-lifecycle-passing/result.json` records direct execution of the exact NixOS-generated password setup script against disposable PostgreSQL. A quoted/backslash password with one CRLF terminator authenticates through SCRAM; a wrong password is refused. Native runtime credential files, fresh schema initialization, graceful SIGTERM/restart retaining the project, and refusal of a deliberately changed private applied-schema checksum pass. Invalid credential contents are withheld. Initial fixture setup failures (missing fixture CQ_SESSION and a Nix expression quoting error) are retained separately; they are not production regressions.

## Remaining evidence

No systemd VM boot or operator Home Manager activation has been performed. Host configured gates and operator delivery gates are separate. No authenticated planning/implementation evaluation is claimed by these fixtures. Combined deployment and delivery review remain outstanding.


## D138: declarative configuration symlinks

The first actual Nix-package doctor invocation against Home Manager-generated leaf symlinks failed with Assets Failed and Hook trust Failed (`hm-installed-fixture/claude-doctor.stdout`, `i28-hm-doctor.log`). The private host-record reader deliberately forbids symlinks; shared configuration planning and trust inspection incorrectly reused that boundary. D138 was filed before the production correction. A focused configuration/settings/approval symlink case first failed (`i28-symlinks-before.log`), then HarnessDoctorLocal passed 7 cases (`i28-symlinks-after.log`). AttachedAssetsLocal passed its 6 regression cases (`i28-assets-symlink-regression.log`).

Configuration reads now resolve their input paths before the existing bounded strict UTF-8 read. Private runtime/credential records keep their stricter contract. Imperative asset writes still preflight and refuse symlink destinations; the regression checks unchanged source bytes and the write refusal. Native local smoke now includes configuration/settings/report symlinks. The corrected clean-source e5dba67f1e8f703f0f7ac681a7d35aa96eeba9c4 native build and smoke pass (`package-e5dba67.log`). Its Nix package and all three Home Manager asset integrations build (`i28-build-assets-e5dba67.json`). The actual Nix-package doctor then passes for Claude, Codex and Pi against generated symlinks and actual pinned version probes (`i28-hm-doctor-e5dba67.log`, `hm-installed-fixture-e5dba67/result.json`); project bytes/symlink mtimes and the immutable package probe home remain unchanged. Approval files are deliberately seeded fixture state, not real model or interactive approval evidence. This completes focused declarative/native source verification; deployment and host/operator gates remain separate.
