package cq.server

import cq.host.WorkflowCatalog

object CliHelp {
  def requested(args: List[String]): Boolean = args.isEmpty || args.headOption.contains("help") ||
    (args.lastOption.contains("--help") && args.dropRight(1).forall(!_.startsWith("--")))

  def render(args: List[String]): String = {
    val topic = if (args.headOption.contains("help")) args.drop(1).headOption else args.dropRight(1).headOption
    val overview = """CQ — project work, evidence and usage

Usage: cq [--diagnostics] COMMAND [OPTIONS]
       cq help COMMAND
       cq COMMAND --help

Operator commands (readable output by default; add --json for automation):
  init              Create or attach this checkout to a project
  query             Find items, inspect a workset or complete a query
  status            Inspect token usage, costs, attempts and audit records
  proposal          Preview or apply a reviewed ledger proposal
  backup            Save a settled project, history, usage and artifacts
  restore           Restore a project archive without overwriting an existing ID
  web               Print this project's browser URL
  configure         Install integration for a directly launched harness
  doctor            Verify commands, server or harness installation without writes
  assets export     Generate integration assets for declarative installation
  commands export   Write native CQ workflow commands/skills

Service and automation entrypoints:
  serve             Run the authenticated HTTP/WebSocket server
  run               Run a governed batch harness session (structured output)
  host              Harness-owned MCP stdio process; protocol use only
  hook              Harness hook entry point of the CQ auto-driver; protocol use only
  :checkout         Internal supervised Git executor; protocol use only
  job upload        Recover retained delivery batches (operator text output)

Examples:
  cq init --name "My project"          Uses CQ_ORIGIN for the first connection
  cq query --query 'ledger:Defects status:Open'
  cq status --task T1
  cq status --json                    One JSON value on stdout
  cq configure codex --settings ./cq-settings.json
  codex                               Start the configured harness directly

Environment:
  CQ_ORIGIN         Default server origin for first init (CQ_ENDPOINT also accepted)
  CQ_TOKEN_FILE     Recommended: read the operator token from this file
  CQ_TOKEN          Inline token; takes precedence when both variables are set
  CQ_SETTINGS       Default harness settings file

Use 'cq help COMMAND' for options and examples. Diagnostics go to stderr;
--diagnostics enables debug logging. Successful commands exit 0, failures exit 1.
Machine-readable commands with --json emit one JSON value and no prose on stdout.
"""
    topic match {
      case None => overview
      case Some("backup") | Some("restore") => """Usage: cq backup PROJECT_UUID FILE [--endpoint URL] [--json]
       cq restore FILE [--endpoint URL] [--json]

Save or restore a settled project, including history, usage audit and artifacts.
The project UUID is preserved. Restore refuses an existing UUID; backup refuses
an existing file. Use an empty server or a server without that project to restore.
  --endpoint URL   Otherwise saved endpoint, CQ_ORIGIN, then CQ_ENDPOINT
  --json           Emit the archive manifest instead of an operator summary

Requires operator credentials. Stop project sessions and settle active claims,
running attempts and pending integrations first. Backups capture one database
snapshot; later updates are not included. Only restore trusted CQ archives with
the same current schema and PostgreSQL major version. Maximum archive and expanded
payload: 512 MiB; transfer deadline: five minutes. Store archives privately.
Git repositories, harness journals, settings and credentials are not included.
Restore does not attach the current checkout: use cq init --project-id UUID after
restoring. If a restore reply is lost, inspect the project before retrying.
"""
      case Some("init") => """Usage: cq init [--endpoint URL] [--project-id UUID] [--name TEXT] [--json]

Create a project or attach this checkout to one. Git worktrees share identity.
  --endpoint URL      Explicit HTTP(S) origin; otherwise saved endpoint,
                      CQ_ORIGIN, then CQ_ENDPOINT
  --project-id UUID   Attach an existing identity; cannot replace this checkout's ID
  --name TEXT         Project name; an explicit changed name renames the project
  --json              Emit the Initialized result only

Example: cq init --endpoint http://localhost:8080 --name "My project"
"""
      case Some("query") => """Usage: cq query [--query TEXT] [--after ID --snapshot CURSOR] [--limit N] [--json]
       cq query --query TEXT --complete UTF16_OFFSET [--limit N] [--json]
       cq query --roots T1,M1 [--after ID --snapshot JSON] [--limit N] [--json]

Find unarchived items by default. Supports Boolean expressions, exact attributes,
references and quoted text. Use archived:all to include archived records.
  --query TEXT          Search expression; empty means all unarchived items
  --complete OFFSET     Contextual suggestions at a UTF-16 offset in query text
  --roots IDS           Transient workset rooted at comma-separated item IDs
  --after ID            Continue after the last item in a previous page
  --snapshot CURSOR     Required continuation snapshot (worksets use JSON)
  --limit N             Maximum entries, 1–200; default 50
  --json                Emit the typed Found, Workset or QueryAnalyzed result

Examples:
  cq query --query 'ledger:Tasks status:Ready'
  cq query --roots T1,M1
  cq query --query 'ledger:t' --complete 8 --json
"""
      case Some("status") => """Usage: cq status [MODE] [SCOPE] [OPTIONS] [--json]

Modes:
  (omitted)   Usage summary: known tokens, missing measurements and estimated costs
  phases      Attempts, host spans, finished wall time, tokens and costs per workflow phase
  audit       Recorded observations and normalized contributions
  costs       Cost totals grouped by attribution, currency and pricing basis
  attempts    Execution attempts and their latest outcome
  outcomes    Outcome history for --attempt UUID

Scope (choose one; default is the whole project):
  --task ID       Direct and shared usage for one item
  --cohort UUID   Usage for a cohort execution
  --session UUID  Usage for a governing session

Options:
  --after CURSOR   Continue audit/outcomes by sequence, attempts by UUID,
                   or costs by the JSON group returned by the preceding page
  --snapshot N     Required for attempts/costs continuation
  --limit N        Page size, 1–200; default 50 (paged modes only)
  --attempt UUID   Required by outcomes; scope flags do not apply there
  --json           Emit one typed Result, retaining all fields and continuation data

Shared usage is counted once per assignment, not divided among its members.
Unknown measurements/costs remain unknown; estimates are not actual billing.

Examples: cq status --task T1
          cq status phases --session SESSION_UUID
          cq status attempts --session SESSION_UUID --json
          cq status outcomes --attempt ATTEMPT_UUID
"""
      case Some("proposal") => """Usage: cq proposal preview|apply RESULT_UUID [--json]

Preview a stored reviewed proposal or apply it under the current authority.
Revision, claim and admission checks still apply. Preview identifies changed
fields; full proposal content remains available through its artifact handle.
  --json   Emit the typed Proposal or Changed result

Example: cq proposal preview 00000000-0000-0000-0000-000000000001
"""
      case Some("web") => """Usage: cq web [--json]

Print the saved project's browser URL. With --json, emit {"endpoint":"…"}.
"""
      case Some("configure") => """Usage: cq configure HARNESS --settings FILE [OPTIONS] [--json]

Install project-local integration so the harness starts its own CQ host.
HARNESS is claude, codex or pi. Then launch that harness directly.
Claude and Codex also get the drive and park commands and the CQ driver hooks
(Claude: .claude/settings.local.json with a statusLine; Codex: .codex/hooks.json).
User-owned hook entries are kept. Both --replace flags go after the options.
  --settings FILE     Supervisor settings; defaults to CQ_SETTINGS
  --executable FILE   Installed CQ executable; defaults to the current executable
  --directory DIR     Project directory; defaults to the current directory
  --replace           Replace existing CQ integration assets
  --replace-statusline  Claude: replace an existing statusLine that is not CQ's
  --json              Emit the list of written paths

Example: cq configure codex --settings .local/interactive/settings.json
Credentials are read by the attached host; forward CQ_TOKEN_FILE into the sandbox.
"""
      case Some("commands") => s"""Usage: cq commands export HARNESS --directory DIR [--replace] [--json]

Export the four CQ workflows (${WorkflowCatalog.commands.map(_.command).mkString(", ")}) as native harness
commands or skills. HARNESS is claude, codex or pi; DIR must already exist.
  --replace   Replace existing CQ command assets
  --json      Emit the list of written paths

Example: cq commands export codex --directory .
"""
      case Some("assets") => """Usage: cq assets export HARNESS --directory DIR --project-directory DIR --settings FILE --executable FILE [--json]

Generate all project integration, workflow and driver assets into an existing
empty build directory. Paths inside those assets target --project-directory.
Settings must declare unique package-verified harness routes. No credentials
are included. Use this for Nix/home-manager; keep configure for imperative use.
"""
      case Some("doctor") => """Usage: cq doctor commands HARNESS [--directory DIR] [--json]
       cq doctor server [--endpoint URL] [--require-settled] [--json]
       cq doctor harness HARNESS --settings FILE --executable FILE --readonly-home DIR [OPTIONS] [--json]

HARNESS is claude, codex or pi. Doctor performs read-only checks.
It never repairs or writes installations.
commands checks only workflow and drive/park files, without credentials or processes.
Server and harness trust are not checked by commands.
server checks authenticated package/model/schema identity, PostgreSQL 18 and
durability. --require-settled also refuses claims, managed attempts or pending
integrations. Stop attached harnesses separately before replacing a server.
Modified or undetermined build sources report Unknown, rather than equality.
harness checks settings, installed version, integrations, commands and trust:
  --directory DIR      Project directory; defaults to the current directory
  --readonly-home DIR  Existing immutable empty directory for public version probes
  --harness-config FILE  Claude .claude.json, Codex config.toml or Pi agent trust.json
  --trust-report FILE  Codex hook report recorded by cq-codex-hook-report

Record Codex hook metadata separately after installing assets. Doctor binds
the report to current hook bytes, declared version and persisted approvals;
changed assets require a fresh report. Pi requires persisted project trust;
the nearest canonical project or parent-folder decision in trust.json applies.
File contents and probe output are withheld. Declarative symlinks are accepted.
Any Failed or Unknown check exits 1 after the report; --json emits one value.
"""
      case Some("serve") => """Usage: cq serve

Run the durable HTTP/WebSocket server. Required environment:
  CQ_DATABASE_URL        JDBC PostgreSQL URL
  CQ_DATABASE_USER       Database user
  CQ_DATABASE_PASSWORD / CQ_DATABASE_PASSWORD_FILE   Inline or runtime-file password
  CQ_TOKEN / CQ_TOKEN_FILE   Inline or runtime-file operator secret (at least 32 characters)
  CQ_HOST / CQ_PORT      Listen address and port
  CQ_ORIGIN              Exact browser origin, including scheme and port

For the local package/database launcher, use ./run-local.sh.
Use 'cq --diagnostics serve' for debug logs on stderr.
"""
      case Some("run") => s"""Usage: cq run HARNESS --settings FILE --input FILE [WORKFLOW OPTIONS]

Govern a batch claude, codex or pi session. Structured output is intended for
automation; this is separate from directly launching an interactive harness.
  --settings FILE   Required supervisor settings file
  --input FILE      Governing session input
${WorkflowCatalog.optionHelp}

Example: cq run codex --settings ./cq-settings.json --input ./request.txt
"""
      case Some("host") => """Usage: cq host HARNESS [--settings FILE]

MCP stdio protocol entrypoint for claude, codex or pi. The native harness owns
this process and its descendant hierarchy. Usually installed by 'cq configure';
do not invoke interactively or redirect protocol stdout into a human terminal.
Settings default to CQ_SETTINGS. Diagnostics are exclusively on stderr.
"""
      case Some("hook") => """Usage: cq hook HARNESS EVENT

Hook entrypoint of the CQ auto-driver for claude or codex; EVENT is
UserPromptSubmit, Stop or StatusLine. The harness runs it with the hook input
on stdin; 'cq configure' installs it. UserPromptSubmit starts or parks this
session's driver for a typed drive or park command and passes every other
prompt through. Stop blocks with the host's advance directive while work
remains and otherwise allows the stop. StatusLine prints the driver status.
It needs the operator credential, trusts the session_id on stdin, and exits 0
even on a CQ error, which it reports in its output without blocking the harness.
"""
      case Some("job") => """Usage: cq job upload --session DIR

Replay retained delivery, combination and integration records after interruption.
Requires the operator credential. Idempotent records retain their identities;
this does not launch new Git updates or rerun unfinished agents. Reports use
operator text output; unresolved recovery exits 1 and retains the records.

Example: cq job upload --session /path/to/cq-sessions/SESSION_UUID
"""
      case Some(value) => throw new IllegalArgumentException(s"Unknown help topic '$value'; use cq --help")
    }
  }
}
