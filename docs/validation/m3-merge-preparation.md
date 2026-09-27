# M3 bounded merge preparation

This increment implements the process boundary for combined candidates. All process gates pass, and Astra independently approved the final runtime/test source and evidence with no blocking or major finding. It is not connected to governor dispatch yet. Plan authorization/publication, resolver report reads, ordered-parent capture and the two-governor validation/review/integration scenario remain open.

## Implemented boundary

`MergePreparation` wraps a native harness launch with an installed POSIX shell script and private diagnostic assets. Its Git subprocess clears inherited `GIT_*` variables while preserving the native harness environment, stdin and positional arguments. The fixed merge invocation disables hooks, signing, autostash and rerere, selects `ort`, and combines `--no-commit` with `--no-ff`.

Preparation requires a clean worktree, the frozen HEAD, expected repository/common directory and no prior merge. After Git returns, it rechecks identity and requires either the exact candidate in `MERGE_HEAD`, or exit zero with proven prior incorporation. An empty unmerged index is not treated as absence of conflict. Full diagnostics remain available for resolution.

The existing guardian binary has a `--capture PATH LIMIT` mode. It accepts only a precreated empty private regular file owned by the effective user, rejects symlinks and hardlinks, retains at most the requested bytes, forwards that same prefix, and fails on overflow, write or fsync errors. The merge wrapper sets a 64 KiB limit. Native execution requires successful capture and one complete valid saved exit-status line; malformed or missing status cannot authorize handoff. A `merge-ready` marker distinguishes successful preparation from unavailable or incomplete diagnostics. The outer guardian owns the wrapper, Git, copier and eventual harness as one hierarchy.

## Verification and corrections

Evidence root: `/srv/nvme/tmp/cq4-implementation`.

| Evidence | Observation |
| --- | --- |
| `20260927T111252-process` | Copier foundation passes 19 native checks and the existing 33 Scala process scenarios |
| `20260927T111744-process` | 38/39 Scala scenarios pass. The initial directory-rename fixture produced a stage-3 index entry and did not demonstrate the intended empty-unmerged-index case |
| `20260927T112107-process` | Corrected directory-split scenario passes. The new fault-injection fixture failed to compile because its relative source path resolved below SBT's `server` working directory; 39/40 scenarios pass |
| `20260927T112244-process` | All 40 Scala process scenarios and 19 native guardian checks pass, including actual copier write/fsync failure and missing/partial/extra status injection |

The corrected directory-split fixture follows Git's own [directory-rename test case 2a](https://github.com/git/git/blob/v2.55.0/t/t6423-merge-rename-directories.sh#L434-L491): the target splits a directory into two equal destinations while the candidate adds another file under its old location. Runtime evidence now establishes exit 1 with `MERGE_HEAD`, conflict diagnostics and zero unmerged index entries. The original fixture failure was a test assumption, not a production merge rejection. The fault library is now built by `dev/check` and supplied by explicit absolute path.

Astra found no blocking or major source defect in the initial review and requested full-wrapper injection of diagnostic write/fsync failures and missing/malformed saved status. Those scenarios pass in the final gate. Each requires an observed injected fault, settled failure, empty native stdout and an empty ready marker. Other scenarios cover clean/textual merges, prior incorporation, invalid objects, identity mismatch, dirty worktrees, reused diagnostics, overflow, failure without merge state, unchanged dirty governing checkout/index and cancellation during a running merge. Final Astra review verified both passing summaries, the directory-split assertion, all five fault modes and runtime/test source-manifest agreement; only documentation changed after the gate.

Run:

```sh
CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation dev/check process
```

This gate runs real Git and guardian processes with a deterministic native handoff probe. It does not call Claude, Codex or Pi models, establish candidate acceptance, or close R27/M3.
