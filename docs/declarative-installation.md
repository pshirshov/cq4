# Declarative CQ installation

The flake exports `lib.mkNativePackage`, `nixosModules.default` and
`homeManagerModules.default`. The package constructor imports an existing native
release; it checks its model version, platform and executable checksums before
installing CQ and its guardian. It does not build Scala or perform release gates.
The current native release targets x86-64 Linux and model 0.1.0.

```nix
let
  cqPackage = inputs.cq.lib.mkNativePackage {
    inherit pkgs;
    release = /absolute/path/to/native-release;
  };
in {
  imports = [ inputs.cq.nixosModules.default ];
  services.cq = {
    enable = true;
    package = cqPackage;
    origin = "http://127.0.0.1:8080";
    tokenFile = "/run/secrets/cq-token";
    database.passwordFile = "/run/secrets/cq-database-password";
  };
}
```

The server listens on `listenAddress` (default `127.0.0.1`) and `port` (default
8080) and keeps its working directory in `/var/lib/<stateDirectory>` (default
`cq`), owned by the `cq` system user.

Credential options take **strings naming runtime files**, not Nix path literals
or secret values. Systemd loads those files into the CQ service's credentials
directory. Inline `CQ_TOKEN` and `CQ_DATABASE_PASSWORD` still take precedence
over their `_FILE` alternatives outside this module. Password files contain one
nonempty UTF-8 line, at most 8192 bytes; a final LF or CRLF is removed, while other
spaces are preserved. The existing token-file validation remains in effect.

Managed mode provisions a database and owner in the **shared NixOS PostgreSQL
18 service**. Their names must match. A dependent oneshot sets the role password
from its runtime credential, passing SQL through stdin and withholding SQL error
contents. Authentication for that database/role on IPv4 loopback uses SCRAM.
Durability settings must remain enabled. PostgreSQL belongs to its own service;
restarting CQ retains its database.
The password unit and CQ start after `postgresql.target`, which the NixOS
PostgreSQL module reaches once it has created the role; a nixpkgs without that
target is not supported. CQ exits with status 143 on SIGTERM, which the unit
counts as a successful stop. CQ requires that target, so stopping the managed
PostgreSQL stops CQ first; it does not keep running without its database.

For an existing PostgreSQL server set `database.managed = false`, then specify
`database.host`, `port`, `name`, `user` and `passwordFile`. CQ checks the current
schema checksum on startup, initializes a fresh schema, and refuses an existing
different checksum. There is no historical migration path during current
development. `doctor server` checks PostgreSQL 18 and durability readback for
both deployment modes.

Before switching a running installation, stop attached consumer harnesses and
reconcile active claims, managed attempts and pending integrations. Run
`cq doctor server --endpoint URL --require-settled`. A systemd restart delivers
SIGTERM with a 30-second stop bound; it does not drain externally owned harnesses.

## Home-manager

```nix
{
  imports = [ inputs.cq.homeManagerModules.default ];
  programs.cq = {
    enable = true;
    package = cqPackage;
    tokenFile = "/run/user/1000/secrets/cq-token";
    projects.consumer = {
      directory = "${config.home.homeDirectory}/work/consumer";
      harnesses = [ "Codex" ];
      settings = {
        stateRoot = "${config.home.homeDirectory}/.local/state/cq/consumer";
        harnesses = [ {
          harness = "Codex";
          executable = "${codexPackage}/bin/codex";
          model = "gpt-6-sol";
          provider = "openai";
          version = "0.159.2";
          providerEnvironment = [];
        } ];
        limits = {
          startupMillis = 10000;
          heartbeatMillis = 2000;
          graceMillis = 1000;
          killMillis = 3000;
          retainedOutputBytes = 1048576;
        };
        checks = [];
        integrationTarget = null;
      };
    };
  };
}
```

Projects must have distinct normalized absolute directories under the declared
home directory. Settings declare harness executables, package-verified versions,
models/providers, optional Pi extensions, provider **environment names**, limits,
checks and integration target. CQ supplies the guardian from the native package.
Asset generation rejects unverified or duplicate routes during the build.
Provider authentication remains external to the module.

The module exports assets into a derivation using `cq assets export`, with paths
inside those assets pointing at the real project directory and store settings.
Home-manager owns the generated files. Command/extension directories use
recursive links so unrelated files can coexist; existing conflicting files are
reported by home-manager's ordinary collision check, with `force = false`.
To add other servers or settings to a generated JSON/TOML file, compose that file
declaratively and override its `home.file` source. Initialize or attach the
project once with `cq init --endpoint URL`; project identity and mutable session
state remain in the Git common directory's `cq` directory.

## Doctor

```sh
cq doctor server --endpoint http://127.0.0.1:8080 --require-settled --json
cq doctor harness codex --directory /absolute/consumer \
  --settings /nix/store/...-settings.json --executable /nix/store/...-cq/bin/cq \
  --readonly-home /nix/store/...-cq/share/cq/doctor-home \
  --harness-config /absolute/private/codex/config.toml \
  --trust-report /absolute/private/codex-hook-report.json --json
```

`doctor commands` remains the small offline command-file check. `doctor server`
reads authenticated installation diagnostics: model, producing source, package
and applied schema checksums, PostgreSQL major/durability and unsettled counts.
Modified or undetermined source reports `Unknown`; matching commit names alone
do not establish equal modified inputs. Attached root Governors are excluded
from managed-attempt counts and must be stopped separately.

`doctor harness` compares all generated assets, accepts declarative symlinks,
checks the declared verified version through the harness's public `--version`,
and checks trust. Version probes use an existing immutable empty directory,
without CQ/provider credentials. JSON comparison preserves unrelated entries;
Codex comparison verifies the CQ MCP table while preserving unrelated TOML
tables. Any `Failed` or `Unknown` check exits 1 after emitting its report. File
contents and probe output are withheld. Doctor never creates configuration,
session files or runtime directories.

Claude requires `--harness-config` pointing at the global `.claude.json` with
project trust accepted; disabled hooks fail.

Pi requires `--harness-config /absolute/private/pi-agent/trust.json`. Every
supported version gates project extensions on trust and reads that file the
same way (0.99.1 and 1.0.0 were observed). The nearest canonical
project or parent-folder boolean decision applies; a child refusal overrides a
parent approval, and null entries are skipped. The doctor requires persisted
approval, even if an individual invocation uses `--approve` or global automatic
trust. It neither writes approvals nor acquires Pi's writable trust-store lock.
`--approve` decides for one Pi process and saves nothing, so a project that is
only launched that way keeps `Hook trust` Failed. Type `/trust` in Pi once, as
[interactive.md](interactive.md) describes: it saves the decision in
`trust.json` of the agent directory (`~/.pi/agent/trust.json` by default). Then
launch Pi without `--approve` and pass that file.

Without `--harness-config`, and for Codex without `--trust-report`, `Hook trust`
is Failed whatever the harness has approved; the detail of the check then ends
with `not given:` and the missing options.

Codex requires project trust and approval of
the **current hook hashes**, as described in [official OpenAI documentation](https://learn.chatgpt.com/docs/hooks).

Codex's supported [`hooks/list` protocol](https://learn.chatgpt.com/docs/app-server)
provides those hashes, but its app-server requires a writable SQLite runtime.
Record metadata separately; this action is explicitly outside the read-only
doctor:

```sh
cq-codex-hook-report --executable /absolute/codex --version 0.159.2 \
  --project /absolute/consumer --output /absolute/private/codex-hook-report.json
```

The Nix package provides that helper. An archive has only `cq` and
`cq-guardian` in `bin/`; it contains the same helper as
`examples/codex-hook-report.py`, run as `python3 examples/codex-hook-report.py`
with the same options. It uses a private
temporary Codex runtime without loading authentication, queries metadata without
running hooks, and writes only the requested inspection report. It does not
approve hooks. Review/approve the installed hooks through Codex `/hooks`.
Doctor binds the recorded hashes to exact current hook-file bytes, project,
version and persisted approvals. Changed hook files require a new report and
changed hook commands require renewed approval. Unrelated formatting changes
also invalidate the byte binding. `cq configure` remains the imperative
installation path.

## Checking the modules

Two checks build the modules against a native release directory. Neither is
part of `dev/check`, `test-local.sh` or the updater; run them after changing
`nix/` or before relying on a release declaratively.

```sh
dev/nixos-module-check /absolute/native-release /absolute/boot.log
dev/home-manager-module-check /absolute/native-release /absolute/build.log
```

Both evaluate impurely: the release is imported by its path and the flake is
read from the checkout. They use the nixpkgs the flake pins and add no input.

`nixos-module-check` needs Nix with the `kvm` system feature. It boots a NixOS
virtual machine ([test](../nix/tests/nixos-module.nix)) with `services.cq`, the
managed PostgreSQL and two credential files that a unit of the test generates
inside the guest. It waits for `cq.service`, requires every check of
`cq doctor server --require-settled` to be Current, creates a project with
`cq init` and one Task through the API, and reads both back after a restart of
the service and after a shutdown and boot of the machine, and that stopping
PostgreSQL stops the server with a successful result. It also checks that
the server process runs as `cq`, that the credential files are mode 600 and
unreadable by that user, that neither value occurs in the unit text, the unit
properties, the process environment or any store path the units name, and that
stopping the service leaves it inactive with a successful result.

It does not cover an existing database (`database.managed = false`), a
replacement of one release by another, a non-loopback origin, a browser or a
harness.

`home-manager-module-check` builds a Home Manager generation
([test](../nix/tests/home-manager-module.nix)) with Claude, Codex and Pi
integrations of one project. Home Manager itself is the source of the
`home-manager` package of the pinned nixpkgs. The check requires the package on
the profile path, the `CQ_TOKEN_FILE` session variable, recursive links that do
not overwrite, refusal of a token file in the store, and Current from
`cq doctor commands` for each harness over the generated links. It activates
nothing and starts no harness, so `cq doctor harness` (versions and trust) is
outside it.

Home-manager collision and recursive-link behavior follows the
[home.file options](https://home-manager.dev/manual/unstable/options/home-manager/home.html).
