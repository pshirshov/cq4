#!/usr/bin/env bash
# Which attribute/config sources does `git merge-tree --write-tree --attr-source=<H>` read? (Git 2.55)
set -u
GIT=$(realpath "$(command -v git)"); echo "git binary: $GIT"
W=$(mktemp -d)
R=$W/repo; FAKEHOME=$W/home; XDG=$W/xdg
mkdir -p "$R" "$FAKEHOME/.config/git" "$XDG/git"
base_env=(env -i PATH="$PATH" HOME="$FAKEHOME" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0)
g() { "${base_env[@]}" git -C "$R" -c user.name=t -c user.email=t@t "$@"; }
g init -q -b main
printf 'base\n' > "$R/f.txt"; g add f.txt; g commit -q -m base
g checkout -q -b head; printf 'target line\n' > "$R/f.txt"; g commit -q -am head; H=$(g rev-parse HEAD)
g checkout -q -b cand main; printf 'candidate line\n' > "$R/f.txt"; g commit -q -am cand; C=$(g rev-parse HEAD)
g checkout -q main
# attribute-carrying head: same as H plus committed .gitattributes
g checkout -q -b headattr head; printf 'f.txt merge=union\n' > "$R/.gitattributes"; g add .gitattributes; g commit -q -m attrs; HA=$(g rev-parse HEAD)
g checkout -q main
verdict() { local v; first=$(printf '%s' "$3" | head -n1)
  if [ "$2" = 0 ]; then v='MERGED (conflict hidden)'; elif [ "$2" = 1 ] && [[ "$first" =~ ^[0-9a-f]{40}$ ]]; then v=conflict; else v="ERROR: $3"; fi
  printf '%-82s exit=%s %s\n' "$1" "$2" "$v"; }
merge() { # $1 = label, rest = extra env assignments ; uses $HEADREV and $EXTRA
  local label=$1; shift
  out=$("${base_env[@]}" "$@" git -C "$R" --no-replace-objects --no-pager -c core.hooksPath=/dev/null -c submodule.recurse=false \
        --attr-source="${HEADREV:-$H}" ${EXTRA:-} -c merge.directoryRenames=conflict -c merge.renormalize=false \
        merge-tree --write-tree --no-messages "${HEADREV:-$H}" "$C" 2>&1); code=$?
  verdict "$label" "$code" "$out"
}
git --version
merge "0 baseline (isolated env, empty fake HOME)"
HEADREV=$HA merge "0b .gitattributes committed at H (merge=union) [intended source]"
printf 'f.txt merge=union\n' > "$R/.gitattributes"; merge "0c untracked .gitattributes in the checkout"; rm "$R/.gitattributes"
echo "--- source 1: \$GIT_DIR/info/attributes"
mkdir -p "$R/.git/info"; printf 'f.txt merge=union\n' > "$R/.git/info/attributes"
merge "1 info/attributes merge=union"
EXTRA="-c core.attributesFile=/dev/null" merge "1b   + -c core.attributesFile=/dev/null"
merge "1c   + GIT_ATTR_NOSYSTEM=1" GIT_ATTR_NOSYSTEM=1
rm "$R/.git/info/attributes"
echo "--- source 2: global attributes"
printf 'f.txt merge=union\n' > "$FAKEHOME/.config/git/attributes"
merge "2 \$HOME/.config/git/attributes merge=union (GIT_CONFIG_GLOBAL=/dev/null)"
EXTRA="-c core.attributesFile=/dev/null" merge "2b   + -c core.attributesFile=/dev/null"
merge "2c   + XDG_CONFIG_HOME=<empty dir>" XDG_CONFIG_HOME="$W/empty"
merge "2d   + HOME=<empty dir>" HOME="$W/empty"
merge "2e   + GIT_ATTR_NOSYSTEM=1" GIT_ATTR_NOSYSTEM=1
rm "$FAKEHOME/.config/git/attributes"
printf 'f.txt merge=union\n' > "$XDG/git/attributes"
merge "2f \$XDG_CONFIG_HOME/git/attributes merge=union" XDG_CONFIG_HOME="$XDG"
EXTRA="-c core.attributesFile=/dev/null" merge "2g   + -c core.attributesFile=/dev/null" XDG_CONFIG_HOME="$XDG"
rm "$XDG/git/attributes"
printf 'f.txt merge=union\n' > "$W/custom-attributes"
g config core.attributesFile "$W/custom-attributes"
merge "2h repository-local core.attributesFile=<file with merge=union>"
EXTRA="-c core.attributesFile=/dev/null" merge "2i   + -c core.attributesFile=/dev/null"
g config --unset core.attributesFile
echo "--- source 3: system attributes ($("${base_env[@]}" git var GIT_ATTR_SYSTEM))"
printf 'f.txt merge=union\n' > "$W/system-attributes"
sys() { local label=$1; shift
  out=$(bwrap --dev-bind / / --tmpfs /etc --ro-bind "$W/system-attributes" /etc/gitattributes "${base_env[@]}" "$@" "$GIT" -C "$R" --no-replace-objects --no-pager \
     --attr-source="$H" ${EXTRA:-} -c merge.directoryRenames=conflict -c merge.renormalize=false merge-tree --write-tree --no-messages "$H" "$C" 2>&1); code=$?
  verdict "$label" "$code" "$out"; }
sys "3- control: bwrap with the file present and readable: $(bwrap --dev-bind / / --tmpfs /etc --ro-bind "$W/system-attributes" /etc/gitattributes cat /etc/gitattributes)" GIT_ATTR_NOSYSTEM=1
sys "3 /etc/gitattributes merge=union (bind-mounted; GIT_CONFIG_NOSYSTEM=1)"
sys "3b   + GIT_ATTR_NOSYSTEM=1" GIT_ATTR_NOSYSTEM=1
EXTRA="-c core.attributesFile=/dev/null" sys "3c   + -c core.attributesFile=/dev/null"
echo "--- source 4: repository-local merge configuration"
g config merge.default union
merge "4 repository merge.default=union"
EXTRA="-c merge.default=text" merge "4b   + -c merge.default=text"
g config --unset merge.default
g config merge.conflictStyle diff3; merge "4c repository merge.conflictStyle=diff3 (control)"; g config --unset merge.conflictStyle
g config merge.ours.driver true
printf 'f.txt merge=ours\n' > "$R/.git/info/attributes"
merge "4d repository merge.ours.driver=true + info/attributes merge=ours"
rm "$R/.git/info/attributes"; g config --unset merge.ours.driver
echo "--- binary / unset built-ins via info/attributes"
printf 'f.txt -merge\n' > "$R/.git/info/attributes"; merge "5 info/attributes -merge (binary)"; rm "$R/.git/info/attributes"
echo "--- pinned form"
printf 'f.txt merge=union\n' | tee "$FAKEHOME/.config/git/attributes" > /dev/null
g config core.attributesFile "$W/custom-attributes"
EXTRA="-c core.attributesFile=/dev/null" sys "6 global+local attributesFile+system set; -c core.attributesFile=/dev/null GIT_ATTR_NOSYSTEM=1" GIT_ATTR_NOSYSTEM=1
echo "workdir $W"
