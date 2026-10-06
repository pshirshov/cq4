# Verify installed CQ command files

`cq doctor commands` compares a project's CQ command files with the templates in the running CQ package. It reads files and reports their state without repairing or creating anything. It needs neither operator credentials nor a running server and does not initialize project/session identity or launch a harness.

```sh
cq doctor commands codex
cq doctor commands claude --directory /path/to/project
cq doctor commands pi --directory /path/to/project --json
```

The directory defaults to the current directory. Relative paths are resolved from it. The report covers the four workflow commands (`begin`, `advance`, `review`, `upstream`) and, for Claude and Codex, the `drive` and `park` command/skill files. Pi registers drive and park in its extension; this command verifies its four prompt files. `cq configure` installs these assets. `cq commands export` exports only the four workflows, so Claude/Codex installations made with that command alone will report missing drive/park files.

| State | Meaning |
| --- | --- |
| Current | File bytes exactly match the running package's template. |
| Missing | File or its symlink target does not exist. |
| Different | File content differs, including an extra suffix or line-ending changes. |
| NotRegular | Destination or an intermediate path is not the expected regular file/directory. |
| Unreadable | The filesystem cannot read the asset, including a symlink loop. |

Symlinks to readable regular files are accepted, including declaratively managed command assets. Each read is bounded to the expected file size plus one byte. The report prints paths and states, never installed file contents. Exit status is zero only when every reported asset is Current; a mismatch report is printed before the command fails. With `--json`, stdout contains one JSON object with `scope`, `harness`, `directory`, `current` and `checks`.

This verifies **command assets only**. It does not verify server/schema compatibility, credentials, MCP configuration, hook configuration, status lines, hook trust, harness versions or whether a running harness has reloaded the files. A Current report must not be interpreted as a complete installation health check: `cq doctor server` checks the server, its schema and PostgreSQL, and `cq doctor harness` checks settings, generated configuration, hook trust and the harness version ([declarative installation](declarative-installation.md#doctor)).

Keep `cq configure` for imperative installation. For a missing or changed command, either update its declarative source to the running package's template or review the appropriate `cq configure`/`cq commands export` command and its replacement options. Doctor itself makes no changes.

# Verify the agent model configuration

`cq doctor agents HARNESS --settings FILE` checks that the agent model configuration of this checkout's project can run the planner, worker, explorer and reviewer roles when HARNESS (claude, codex or pi) governs, with the harness entries of a session's settings file. It reads and changes nothing: it starts no harness, asks no provider and writes no file.

```sh
cq doctor agents codex --settings ./cq-settings.json
cq doctor agents pi --settings ./cq-settings.json --directory /path/to/checkout --json
```

It reads the project file of the checkout (of `--directory`, by default the current directory) for the endpoint and the project, and the operator credential (`CQ_TOKEN` or `CQ_TOKEN_FILE`) as `cq doctor server` does. The server holds the configuration: the server defaults and the project's override, edited under *Agent models* in the browser.

| Check | Current when |
| --- | --- |
| Project | The project file is readable. |
| Credential | The operator credential is readable; the server's acceptance shows in the next check. |
| Configuration | The server returned the configuration of the project. |
| Server defaults, Project override | The text of the layer has no problems. A failure lists each problem with its `line:column` in that text. |
| Role planner, Role worker, Role explorer, Role reviewer | The role resolves for HARNESS. The detail shows the resolved models in the configuration's own syntax and where the role was found. A failure names the problem and what to set, for example a role that no layer assigns or a tier that no layer defines. |
| Session settings | The settings file is readable and names each harness once. |
| Settings entry H | Harness H, which a resolved role runs a model on, has an entry in the settings file with an executable that exists and a package-verified version. A failure reads "H is referenced by ROLE but not in the session settings". |
| Providers | Every resolved model that is written without a provider can take the provider of its harness's settings entry. |

No role is resolved while a layer has problems, so the four role checks fail with it. A reviewer seat that can run a model of HARNESS means the governing harness may review its own work: that is allowed, and the Role reviewer check reports it as self-review in its detail while staying Current.

Exit status is zero only when every check is Current; the report is printed before the command fails. With `--json`, stdout contains one JSON object with `scope` (`agents`), `current` and `checks` (`name`, `state`, `detail`), as for `cq doctor server` and `cq doctor harness`.

Model names are **not verified against providers**: a misspelt model resolves here and fails when a child starts. `cq doctor harness` verifies a harness's executable by its version probe, its generated assets and its trust; this command checks only that the settings file has a usable entry.
