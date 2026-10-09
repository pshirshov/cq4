#!/bin/sh
echo $$ > /tmp/cxq/kill2.pid
cd /srv/nvme/tmp/cxq-probe
CODEX_HOME=/tmp/cxq/home exec codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox danger-full-access -c model_reasoning_effort=low "$(cat /tmp/cxq/p_kill.txt)" < /dev/null > /tmp/cxq/out/kill_2.jsonl 2> /tmp/cxq/out/kill_2.err
