# D41: integrate into the governing branch

Source checks pass and Astra independently approves this increment. Native/package delivery
and live CQ closure remain pending. Version remains 0.1.0.

## Contract

CQ can integrate an accepted, checked and reviewed descendant into the branch
currently checked out in its configured governing repository. HEAD stays attached.
Unrelated staged, unstaged, untracked and ignored content stays in place. A
conflicting local edit causes a refusal before checkout effects. A target checked
out in another worktree, or multiple worktrees, remains unsupported.

The existing claims, reservations, exact candidate review and expected-ref
comparison still govern admission. Every update uses one supervised `:checkout`
role, with an immutable plan identifying whether the target was checked out when
scheduled. The executor holds the governing index lock, prepares the conditional
Git reference transaction, and checks checkout occupancy under that transaction.
The files reference backend locks the governing HEAD as well. An unoccupied target
gets an explicit HEAD verification in the transaction.

For a checked-out target, the executor copies the existing index, checks collisions
including ignored files, and uses Git's two-tree checkout against that private
index. It forces changed files and directories, atomically publishes the prepared
index while retaining the real index lock, then commits the conditional reference
update. The original index and typed started/publication/completion receipts stay
in the session's `checkouts/INTEGRATION_UUID` directory. Normal pre-effect refusals
close the transaction and wait for Git to release its locks. Recovery never
reexecutes checkout.

**Operational precondition:** do not edit candidate paths or run external Git
operations in this repository's worktrees during the short integration operation.
The target CAS and governing HEAD/index locks detect or exclude their respective
conflicts; they are not a repository-wide checkout mutex. The pinned Git mechanics
probe shows that another existing worktree can switch to an unoccupied target
while those locks are held. `worktree add ... target` was refused by the branch
lock in the same probe. CQ's detached child workspaces remain independent, but an
external checkout of the integration branch is outside the supported critical
section. Sparse/split indexes, submodules, unmerged indexes, assume-unchanged and
skip-worktree entries are explicitly refused for attached integration.

## Interruption and inspection

There is no filesystem-wide atomic transaction spanning worktree files, index and
Git refs. If interruption occurs after effects start, CQ retains the index lock
and evidence and leaves the reservation unresolved. It must not report successful
incorporation from branch ancestry alone: a completed receipt, matching frozen
settled job, and absence of transaction/index locks are required for a scheduled
operation. This is conservative after a lost acknowledgement. Tests establish
process-interruption behavior, not a simulated power-loss guarantee.

If this occurs, stop activity in the repository and preserve the entire session
directory and checkout. `cq job upload --session SESSION_DIR` only reconciles;
an unresolved result needs explicit inspection of the ref, working files, current
index, `index-before`, prepared index, receipts and executor settlement. Do not
delete locks or reset/stash the checkout merely to make recovery return success.
The retained index lock includes the integration identity and evidence directory.

## Reproductions and checks

Evidence root: `/srv/nvme/tmp/cq4-remaining-defects-20260928`.

- `checkout-before.log`: original implementation refused the governing branch.
- `checkout-negative-before2.log`: real Git reproduces stale checkout after a
  scheduling race and a leftover HEAD lock after refusal. Both pass after the
  unified executor and graceful transaction abort correction.
- `checkout-collisions.log`: ignored-file collision incorrectly succeeded.
  `checkout-collisions-corrected.log`: all five preservation/race tests pass after
  checking untracked paths without Git's ignore exclusions.
- `checkout-ancestor-before.log`: ignored file/symlink ancestors were overwritten.
  `checkout-ancestor-corrected.log`: all seven preservation/race tests pass after
  including only obstructing non-directory ancestors in the collision check.
- `checkout-coordinator.log`: all 15 coordinator scenarios pass separately against
  the dummy and real Git/process adapter, including dirty layers and replay.
- `checkout-proof3`: governing HEAD/index/ref lock mechanics and private-index
  publication proof; `checkout-foreign-race2`: the cross-worktree limitation above.
- `checkout-fixture-first`: actual SIGKILL showed a direct executor retry could
  write a misleading refusal record beside earlier effects. The executor now
  rejects retained execution evidence before entering its effect/refusal path.

`dev/checkout-check.py` exercises the actual entrypoint, unusual filenames,
deletion, symlinks, dirty layers and SIGKILL before index publication and after
reference commit. `dev/dispatch-check.py` covers both an unoccupied branch and a
checked-out branch through accepted worker/reviewer results and retained-domain
acknowledgement recovery. Both are included in the JVM tracing/native gate.

Git semantics: [read-tree](https://git-scm.com/docs/git-read-tree),
[update-ref](https://git-scm.com/docs/git-update-ref), and the pinned
[files reference backend](https://github.com/git/git/blob/v2.55.0/refs/files-backend.c).

`checkout-fixture-corrected` passes all three actual executor cases and the full
connected dispatch/crash/recovery check. No new Git job or ref update occurred on
recovery. The native gate repeats these scenarios on the packaged executable.

`checkout-fixture-final.log` passes the expanded entrypoint cases, including a
nested candidate beside an unrelated ignored sibling, after the ancestor correction.
