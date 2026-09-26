# Harness adapter inventory

Read-only M2 preparation, 2026-09-26. No CQ harness evaluation or adapter implementation is claimed by this inventory. Installed CLI help is retained under `/srv/nvme/tmp/cq4-implementation/m2-capabilities-20260926/`.

| Installed harness | Observed batch/output controls | Observed capability controls |
| --- | --- | --- |
| Claude Code 2.1.280 | `--print`, `--output-format stream-json`, explicit session ID; optional structured final response | Built-in tool allowlist, permission allow/deny lists, strict MCP configuration and settings-source controls. `--bare` explicitly changes authentication behavior, so it cannot be assumed compatible with the configured account. |
| Codex 0.156.1 | `exec --json`, stdin prompt, final-message file, optional output schema; `--ignore-user-config` keeps authentication while omitting user config | Sandbox selection plus configuration overrides; MCP tool allowlists. Actual installed capability behavior still requires evaluation. |
| Pi 0.87.1 | `--print`, `--mode json` or `rpc`, explicit session file/directory | Built-in/extension tool allowlists and denylists; discovery-disable flags retain explicitly supplied extensions. No native MCP CLI option appears in inspected help; the adapter needs an explicit extension bridge or other documented integration. |

Official Codex documentation describes JSON event output and usage in terminal turn events, plus separate final-message capture. The local collector must verify the installed output rather than treating a sample as a completeness guarantee. [Non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode).

Codex HTTP MCP configuration supports a bearer-token environment reference and tool allowlists. These controls complement CQ's server-enforced role permissions. [MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).

The earlier [usage probes](../drafts/20260926-usage-observability.md) establish configured harness observability for those specific calls; they are not CQ consumer evaluations. M2 must implement and evaluate process ownership, bounded launch/drain/termination, role restrictions, prompt/result handles and host collectors for each adapter. Credentials remain with their configured harnesses; inspection did not print authentication files or keys.
