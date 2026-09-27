Run the CQ {{WORKFLOW}} workflow through the installed CQ executable. The host loads its packaged workflow instructions and child prompts; do not compose or read child prompts/results yourself.

Use the settings path and workflow arguments supplied by the user or established for this project. If required settings or scope are missing, ask for them. Write the current user request/context to a UTF-8 file using a file-writing tool; preserve it as data. Invoke the command with separate, correctly quoted arguments:

`cq run {{HARNESS}} --settings SETTINGS_FILE --input REQUEST_FILE --workflow {{WORKFLOW}} {{OPTIONS}}`

Replace uppercase placeholders with the supplied values. Roots are comma-separated canonical references. Begin also accepts an optional `--roots` selection. Advance phases are explore, plan, work, review or integrate; review modes are plan, candidate or audit; upstream actions are prepare, report or recheck. The host validates options and loads only the selected workflow.

Do not put user text into shell code or substitute it into commands. Use the supplied roots/phase/result/action without broadening scope. The four workflows share the host's packaged instructions; native command files only adapt invocation.

Read the returned receipt and its bounded report. Surface unanswered question IDs and choices, blockers, retained result handles and remaining work. Process success is not workflow completion. Ask genuine user choices and wait for the actual answer before another dependent run; never invent an answer or treat silence as authorization. Pass a supplied answer as the next request with the relevant roots/question IDs. Full child results stay behind handles, with explicit bounded drill-down only when necessary.

If the command fails, delivery is pending, or the report is absent, report that condition and retain the receipt/session path. Do not claim success from an exit code alone. The managed governor and children have CQ usage accounting; this surrounding interactive activity has unavailable coverage until its collector is implemented.
