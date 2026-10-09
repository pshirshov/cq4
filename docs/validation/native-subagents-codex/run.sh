#!/bin/sh
# usage: run.sh NAME SANDBOX PROMPT_FILE [extra codex args...]
# Appends the exact command line and prompt text to the manifest (commands.log) and stores a
# snapshot of the effective CODEX_HOME config (config.toml + every role file it references) in
# $SNAP/<NAME>.config.txt before launching.  CODEX_HOME defaults to /tmp/cxq/home.
name=$1; sb=$2; pf=$3; shift 3
CH=${CODEX_HOME_DIR:-/tmp/cxq/home}
M=${MANIFEST:-/tmp/cxq/commands.log}
SNAP=${SNAP:-/tmp/cxq/snap}
mkdir -p /tmp/cxq/out $SNAP
{
echo "=== CODEX_HOME=$CH"; echo "--- config.toml (sha256 $(sha256sum $CH/config.toml | cut -c1-16))"; cat $CH/config.toml
for r in $(grep -o '/tmp/cxq/roles/[A-Za-z0-9_]*\.toml' $CH/config.toml | sort -u); do echo "--- $r"; cat $r; done
} > $SNAP/$name.config.txt
{
echo "=== RUN $name  $(date -u +%FT%T.%3NZ)"
echo "CMD: CODEX_HOME=$CH timeout 300 codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox $sb -c model_reasoning_effort=low $* \"\$(cat $pf)\" < /dev/null"
echo "PROMPT: $(cat $pf)"
} >> $M
CODEX_HOME=$CH timeout 300 codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox $sb -c model_reasoning_effort=low "$@" "$(cat $pf)" < /dev/null > /tmp/cxq/out/$name.jsonl 2> /tmp/cxq/out/$name.err
echo "EXIT $name: $?" >> $M
