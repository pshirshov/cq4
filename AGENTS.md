# Development version policy

User instruction, 2026-09-26: keep ONE version and bump it only when explicitly requested by the user. Breaking changes are authorized during current development.

- Keep all CQ contracts in `models/cq-api.baboon` at `cq.api` version `0.1.0`.
- Edit the current model and database schema in place. Do not add historical schema copies, conversion fixtures, compatibility adapters or upgrade paths during this development phase. The one exception is the pinned local-update step for the operator's installed release (`dev/local-update-step.json`, `dev/local-update.sql` and a data step in `dev/update-local.py`): a change to the schema or stored model comes with that step, and it is replaced at the next release (decision on Q86).
- Regenerate codecs and the current signature after schema edits. The signature records the current model; it does not freeze its shape.
- Earlier design/plan requirements for historical decoding and schema evolution are superseded by this instruction. Preserve historical verification evidence as a record of what was actually run.

# Tests for agents

User instruction, 2026-10-03: testing is the implementing agent’s responsibility; local redeployment must not require the operator to run the full test pipeline. Run appropriate focused tests for each change. Full validation runs separately from `./update-local.sh`.

- **Set up once.** In a fresh worktree run `nix develop -c ./dev/generate`. Generated code is not committed.
- **Scala: one suite per invocation.** `nix develop -c sbt --batch --no-colors "server/testOnly cq.server.<SuiteClass>"`. The suite class is not the file name: `HarnessToolsTest.scala` defines `HarnessToolsLocal`, so find it with `grep 'class ' <file>`. Read the `Tests:` line of the output: sbt can exit 0 with failed tests, and `No tests to run` means the name is wrong.
- **Prefer `*Dummy` and `*Local` suites.** A `*Postgres` suite needs the database that `./dev/check postgres` starts, and a `*Process` suite needs the fixture binaries that `./dev/check process` builds. When such a variant matters, say so in the report; do not run the gate for it.
- **TypeScript.** After `npm ci --ignore-scripts`, `npm run check` type-checks the UI (`tsc --noEmit`).
- **Configured checks.** The host's configured checks for this project are `./dev/check fast` and `./dev/check ui`. The host runs both on every candidate and again on the commit that lands when the target has moved. Agents use focused checks by default and run a configured gate when needed for the change or explicitly requested.
- **Delivery gates.** Every other `./dev/check <mode>` (`contracts`, `postgres`, `access`, `usage`, `browser`, `native`, `process`, `cohort`, `growth`), `dev/package` and `dev/package-check` are separate release-validation operations. Agents run a relevant gate when needed to verify the change or when requested; do not run every gate during routine local redeployment.
- **Fail-before evidence.** Revert the production change in the same worktree, keep the new test, run that one test and record the failure, then restore the change. Do not build or check out a second tree.
