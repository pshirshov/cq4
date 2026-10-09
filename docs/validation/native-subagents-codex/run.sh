#!/bin/sh
# usage: run.sh NAME SANDBOX PROMPT [extra codex args...]
name=$1; sb=$2; prompt=$3; shift 3
mkdir -p /tmp/cxq/out
CODEX_HOME=/tmp/cxq/home timeout 300 codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox $sb -c model_reasoning_effort=low "$@" "$prompt" < /dev/null > /tmp/cxq/out/$name.jsonl 2> /tmp/cxq/out/$name.err
echo "exit=$?" >> /tmp/cxq/out/$name.err
