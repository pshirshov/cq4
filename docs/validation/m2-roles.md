# M2 single role entrypoint

`cq.server.Main` is now the sole distage `RoleAppMain.LauncherBIO` entrypoint for the existing server and client task. The manual CLI construction/dispatch branch is removed. `CqCliParser` maps the existing command syntax to role arguments; `ClientRole` receives an injected `Cli` and `CliContext`. Server and client plugins are registered explicitly. The local supervisor will join the same role composition when `cq run` is implemented.

The bootstrap early logger and configured runtime log router use an injected stderr sink. Client stdout remains suitable for JSON or URL output. Framework role help and configuration-writer roles are registered through `BundledRolesModule`.

Evidence root: `/srv/nvme/tmp/cq4-implementation/`.

| Check | Evidence | Observation |
| --- | --- | --- |
| `./dev/check postgres` | `20260926T222642-postgres` | 30 PostgreSQL scenarios, actual HTTP/MCP/WS/Scala host client, CLI behavior/deadlines and SIGKILL/restart pass through the unified entrypoint |
| `./dev/check postgres` | `20260926T223017-postgres` | Same corpus passes, with new role-isolation checks removing every `CQ_*` environment variable |
| Targeted compile and `dev/roles-check.py` | `20260926T223417-roles` | Shorthand/native client calls, native global logging option, `--` argument separator, ordinary help, role help and rejected command pass; exact command/source manifests retained |

Role isolation checks use an unrelated temporary directory with a local project configuration and no server, database or credential environment. `web`, `:client web` and `--log-level-root error :client web` print the exact configured URL. An invalid client command exits 1, leaves stdout empty, and reports its diagnostic on stderr. Successful calls therefore do not acquire the server's required configuration or database resources.

`20260926T223326-roles` reproduced rejection of `:client -- web` with `Unknown command`. Distage retains its separator in `EntrypointArgs.raw`; the client role now removes that leading separator before passing the remaining arguments to the existing command parser. The targeted follow-up above passes that reproduction and the complete isolated-role fixture. Both source manifests distinguish this small follow-up from the earlier PostgreSQL corpus.

The normal `postgres`, `browser` and `native` check entrypoints now include `dev/roles-check.py`. Native verification of this increment remains pending; previous native evidence applies to M0 only. No harness adapter or consumer evaluation is claimed here.
