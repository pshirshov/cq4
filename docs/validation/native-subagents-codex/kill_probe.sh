#!/usr/bin/env bash
# usage: kill_probe.sh NAME SLEEPSECS  -- launches a scratch codex parent, SIGKILLs only that PID, logs process state.
name=$1; n=$2; L=/tmp/cxq/out/$name.proc.log; : > $L
log(){ echo "$(date -u +%T.%3N) $*" >> $L; }
sed "s/sleep 61/sleep $n/; s/killtest.txt/killtest_$name.txt/" prompts/p_kill.txt > /tmp/cxq/p_$name.txt
echo "CMD: CODEX_HOME=/tmp/cxq/home codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox danger-full-access -c model_reasoning_effort=low \"\$(cat /tmp/cxq/p_$name.txt)\"" >> $L
echo "PROMPT: $(cat /tmp/cxq/p_$name.txt)" >> $L
CODEX_HOME=/tmp/cxq/home codex exec --json --skip-git-repo-check -C /srv/nvme/tmp/cxq-probe/wt --sandbox danger-full-access -c model_reasoning_effort=low "$(cat /tmp/cxq/p_$name.txt)" < /dev/null > /tmp/cxq/out/$name.jsonl 2>/tmp/cxq/out/$name.err &
P=$!
log "scratch parent PID=$P"
for i in $(seq 1 60); do
  sleep 1
  pgrep -f "sleep $n\$" >/dev/null && break
done
log "sleep $n present: $(pgrep -af "sleep $n\$" | tr '\n' ';')"
log "process tree of parent before kill:"; pstree -p $P >> $L 2>&1
SL=$(pgrep -f "sleep $n\$" | head -1); log "sleep pid=$SL ppid=$(ps -o ppid= -p $SL) "
log "KILL: kill -9 $P"; kill -9 $P
for t in 1 3 10 20 40; do
  sleep $((t==1?1:t==3?2:t==10?7:t==20?10:20))
  log "+${t}s after kill: parent alive? $(kill -0 $P 2>/dev/null && echo yes || echo no); sleep pid $SL: $(ps -o pid=,ppid=,stat=,cmd= -p $SL 2>/dev/null || echo gone); other descendants: $(pgrep -af 'codex' | grep -c cxq)"
done
sleep $((n-40+3))
log "after sleep expired: sleep pid $SL: $(ps -o pid=,cmd= -p $SL 2>/dev/null || echo gone); killtest file: $(ls /srv/nvme/tmp/cxq-probe/sib/killtest_$name.txt 2>&1)"
