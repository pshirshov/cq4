#!/bin/sh
# usage: run.sh NAME SANDBOX PROMPT_FILE [extra codex args...]
# Appends the exact command line, the prompt text and the config snapshot to commands.log (manifest).
name=$1; sb=$2; pf=$3; shift 3
M=${MANIFEST:-/tmp/cxq/commands.log}
{
echo "=== RUN $name  $(date -u +%FT%T.%3NZ)"
echo "CMD: CODEX_HOME=/tmp/cxq/home timeout 300 codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox $sb -c model_reasoning_effort=low $* \"\$(cat $pf)\" < /dev/null"
echo "PROMPT: $(cat $pf)"
} >> $M
mkdir -p /tmp/cxq/out
CODEX_HOME=/tmp/cxq/home timeout 300 codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox $sb -c model_reasoning_effort=low "$@" "$(cat $pf)" < /dev/null > /tmp/cxq/out/$name.jsonl 2> /tmp/cxq/out/$name.err
echo "EXIT $name: $?" >> $M
