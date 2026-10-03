# I29 local update command, 2026-10-03

Implemented an operator-run `update-local.sh`; the installed playground package was not replaced during implementation. Before implementation `test -x update-local.sh` exited 1 in the source worktree: no reusable command existed.

Focused verification: `nix develop -c python3 dev/update-local-check.py --postgres -v` passed 13 tests. Ten shared replacement cases exercised the in-memory and real-directory adapters, including interruption immediately after each rename, receipt-write failure, existing rollback and modified candidate refusal. Two compatibility cases checked exact model transitions and unknown model/schema refusal. One disposable PostgreSQL case verified backup, package replacement, retained prior package, and unchanged-package recovery on a schema mismatch. Logs: `/srv/nvme/tmp/cq4-i29-evidence/focused.log`.

`bash -n update-local.sh`, Python compilation and `git diff --check` passed. The focused default checks are registered in `dev/check fast`; the PostgreSQL case requires its explicit flag.

Full native builds, configured gates and source-isolated package verification remain operator delivery checks. These were not run by the implementing agent. No claim is made that the current playground has newer UI assets. The operator can run the new command after stopping the launcher. The current pinned model transition adds only help catalog request/result types; schema changes are refused pending a matching implementation and rehearsal.
