# Operator CLI: D38/D39/D40

The CLI now has structured overview and command help, readable operator results,
and explicit `--json` output for automation. Distage bootstrap and primary logging
default to Warning; `cq --diagnostics ...` enables diagnostic logging on stderr.
The separate D25 dependency warning is still visible.

Evidence: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.

- `cli-before`: the installed package produced one-line help, rejected command
  help, logged Info startup messages and returned wire JSON for ordinary status.
- `cli-first`: real CLI identity/query/usage and timeout checks pass. The old role
  fixture's one-line help assertion fails and is updated to the new contract.
- `cli-populated`: nonempty token, cost, attempt, outcome and gap rendering passes.
- `cli-cost-before` and `cli-dispatch-before`: Astra's two findings reproduce:
  truncated cost summaries omit a usable scoped continuation, and an automated
  proposal caller still assumes JSON by default.
- `cli-corrected`: operator output, distage role routing and deterministic
  supervisor/dispatch fixtures pass. The cost test copies and executes the printed
  continuation, retaining task scope. Proposal automation explicitly requests
  JSON; the operator preview is separately checked.
- `cli-rendering.log`: the focused public-renderer test passes for dependency
  reasons and proposal statuses without internal record syntax.

Help needs no credentials or project files. JSON commands emit only their result
on stdout; errors and diagnostics remain on stderr. Usage preserves unknown and
estimated measurements, currency/pricing basis and accounting gaps. Paged output
prints a complete continuation command retaining the original scope and filters.

Astra independently approves this implementation increment with no remaining
major findings. Native and source-isolated package checks now pass; Astra approves
technical delivery. Installed readable query/status and JSON query checks pass;
D38/D39/D40 are Resolved with ModelDeclared evidence. This
is not human acceptance or closure of D25. [Aggregate evidence](remaining-defects.md).
