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
