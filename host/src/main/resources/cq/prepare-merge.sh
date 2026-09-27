set -eu
guardian=$1
assets=$2
workspace=$3
common=$4
base=$5
candidate=$6
git_variables=$7
diagnostic_limit=$8
shift 8

fail() {
    printf 'CQ merge preparation failed: %s\n' "$1" >&2
    exit 2
}

git_cq() (
    for name in $git_variables; do unset "$name"; done
    export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
    command git --no-replace-objects --no-pager -c core.hooksPath=/dev/null -c submodule.recurse=false \
        -c rerere.enabled=false -c user.name='CQ host' -c user.email=cq@localhost "$@" < /dev/null
)

verify_identity() {
    actual_top=$(git_cq rev-parse --show-toplevel) || fail 'worktree is unavailable'
    actual_common=$(git_cq rev-parse --path-format=absolute --git-common-dir) || fail 'repository is unavailable'
    actual_common=$(cd "$actual_common" && pwd -P) || fail 'repository path is unavailable'
    actual_head=$(git_cq rev-parse --verify HEAD) || fail 'HEAD is unavailable'
    [ "$actual_top" = "$workspace" ] && [ "$actual_common" = "$common" ] && [ "$actual_head" = "$base" ] ||
        fail 'worktree, repository or HEAD changed'
}

[ "$(pwd -P)" = "$workspace" ] || fail 'incorrect working directory'
for name in merge.log merge-status merge-ready; do
    [ -f "$assets/$name" ] && [ ! -L "$assets/$name" ] && [ ! -s "$assets/$name" ] || fail 'diagnostic asset is unavailable or reused'
done
verify_identity
merge_head=$(git_cq rev-parse --path-format=absolute --git-path MERGE_HEAD) || fail 'merge path is unavailable'
[ ! -e "$merge_head" ] && [ ! -L "$merge_head" ] || fail 'workspace has an existing merge'
state=$(git_cq status --porcelain=v1 --untracked-files=all) || fail 'workspace status is unavailable'
[ -z "$state" ] || fail 'workspace is not clean'

(
    code=0
    git_cq merge --no-commit --no-ff --no-edit --no-gpg-sign --no-autostash --no-verify \
        --no-rerere-autoupdate --strategy=ort "$candidate" 2>&1 || code=$?
    printf '%s\n' "$code" > "$assets/merge-status"
) | "$guardian" --capture "$assets/merge.log" "$diagnostic_limit" >&2 || fail 'merge diagnostics could not be retained'

{
    IFS= read -r code && ! IFS= read -r extra && [ -z "$extra" ]
} < "$assets/merge-status" || fail 'merge exit status is missing or malformed'
case "$code" in 0|1) ;; *) fail 'Git did not prepare a merge' ;; esac
verify_identity
if [ -f "$merge_head" ] && [ ! -L "$merge_head" ]; then
    {
        IFS= read -r merged && ! IFS= read -r extra && [ -z "$extra" ]
    } < "$merge_head" || fail 'merge inputs are malformed'
    [ "$merged" = "$candidate" ] || fail 'merge input changed'
else
    [ "$code" = 0 ] && [ ! -e "$merge_head" ] && [ ! -L "$merge_head" ] &&
        git_cq merge-base --is-ancestor "$candidate" "$base" || fail 'merge state does not establish the requested inputs'
fi
printf '%s\n' "$code" > "$assets/merge-ready"
exec "$@"
