# I28 / T55: read-only command-asset verification

T55 implements the command-asset part of I28 as `cq doctor commands HARNESS [--directory DIR] [--json]`. It complements `cq configure`. It does not claim to complete I28's server, schema, credential, MCP, hook trust, harness-version or Nix-module work. [Operator usage and limits](../doctor.md).

The doctor renders the existing packaged workflow and drive/park templates and compares their bytes with the corresponding installed files. Four Pi prompts and six Claude/Codex command/skill files are checked. A reader interface separates the filesystem boundary from inventory/comparison policy. The filesystem reader follows declarative symlinks, distinguishes missing/nonregular/unreadable destinations, and reads at most the expected size plus one byte. Changed or unreadable file contents are never printed. A normal negative report exits 1 through the client role's explicit termination boundary; unexpected failures retain the existing diagnostics.

## Verification

Evidence root: `/srv/nvme/tmp/cq4-doctor-evidence`.

Before CLI implementation, the focused missing-command case failed with `Unknown command; use cq --help` (`cli-missing-before.log`); doctor help was also unknown (`cli-before.log`). The initial CLI report tests exposed the ordinary Scala `require` message prefix, which was corrected in the assertion. The final implementation uses a specific normal-verdict exception rather than a generic precondition failure.

The actual JVM first produced correct reports and exit codes but routed a normal mismatch through verbose framework diagnostics. Captures are retained in `verbose-before/`. With the new client exit handling reverted in the same worktree, the committed actual-entrypoint test failed for the intended reason: exit 1 accompanied by framework stack traces (`quiet-before.log`). Restoring the handling makes that case pass without stderr diagnostics.

| Exact focused suite/check | Result | Evidence |
| --- | --- | --- |
| CommandDoctorDummy | 5 passed | `doctor-dummy.log` |
| CommandDoctorLocal | 7 passed | `doctor-local-final.log` |
| CliDoctorLocal | 4 passed | `cli-final.log` |
| Actual JVM CLI process fixture | 3 scenarios passed | `cli-process-final.log`, `process-results.json` |

Dummy and real-filesystem variants execute the same five behavior cases: complete inventories for every harness; missing files; independent stale workflow/park files; a matching prefix with extra bytes; and mixed missing/nonregular/unreadable files without abandoning the remaining checks. Filesystem-specific cases cover a dangling symlink preserved as Missing and symlinks to immutable regular files preserved as Current, including unchanged target bytes and mtimes.

CLI tests verify explicit/default directories, human and single-value JSON reports, nonzero negative results, help scope, omitted installed contents and absence of newly created project/session identity. The actual entrypoint case runs without CQ credentials and verifies exit 1, empty stderr and an untouched checkout. The additional JVM process fixture invokes actual `configure pi`, then verifies doctor exit 0 for its current files and exit 1 after tampering with one prompt. Snapshots retain the paths, bytes, inode identities, permissions and mtimes around doctor invocations. The process fixture uses the project's pinned JVM option; no paid harness is launched.

No model/schema edit, version bump, configured gate, delivery gate, native build, updater, live database operation or operator-directory mutation was performed. New suites are discovered by the existing exact Dummy/Local selection; the actual-entrypoint assertion is part of CliDoctorLocal. These observations establish source-JVM behavior on the local Linux environment, not native-package delivery or validation on other platforms.

## Outstanding I28 work

The current flake exports development shells only. NixOS and home-manager modules still need a packaged CQ derivation, declarative settings/assets, runtime secret file paths, server state and PostgreSQL ownership, stop/drain behavior, and an explicit schema application policy. Per-project harness assets also need a declared ownership/location policy; home-manager's per-user scope does not establish that automatically. This command supplies a read-only check of command files for either installation method without making those deployment choices.

I28 remains Proposed. T55 can be Done with this bounded source result and focused evidence; native and installed verification still belong to delivery. Other source corrections awaiting delivery are unchanged, Q50 remains the I30 authority choice, I17/I27 retain their existing configuration dependency, and I26's broader workload measurements remain outstanding.
