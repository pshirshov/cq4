#!/usr/bin/env bash
# life.sh kind tag secs : kind=t (TaskStop cancel) | kf (SIGKILL parent, foreground) | kb (SIGKILL parent, background)
kind=$1; tag=$2; s=$3
cd /tmp/p18d/proj
case $kind in
 t) P="Delegate to subagent_type \"rw\" with run_in_background true and prompt exactly: \"Run: timeout $s tail -f /dev/null\". Then run pgrep -af 'timeout $s tail' yourself with Bash to confirm the process exists, then call TaskStop on the subagent task, then run pgrep -af 'timeout $s tail' again after 6 seconds (use 'sleep 6; pgrep ...' inside bash -c) and report both pgrep outputs and the TaskStop result.";;
 kf) P="Delegate to subagent_type \"rw\" with run_in_background false and prompt exactly: \"Run: bash -c 'sleep $s; echo finished-$tag'\". Wait for its result.";;
 kb) P="Delegate to subagent_type \"rw\" with run_in_background true and prompt exactly: \"Run: bash -c 'sleep $s; echo finished-$tag'\". Wait for its result.";;
esac
argv=(claude -p --output-format stream-json --verbose --model sonnet --agents "$(cat /tmp/p18d/agents_par.json)" --dangerously-skip-permissions "$P")
{ echo "cwd=/tmp/p18d/proj stdin=/dev/null (no timeout for k*, scratch parent pid SIGKILLed by this script)"; printf '%q ' "${argv[@]}"; echo; } > /tmp/p18d/out/cmd-$tag.txt
"${argv[@]}" > /tmp/p18d/out/$tag.jsonl 2> /tmp/p18d/out/$tag.err < /dev/null &
pid=$!
if [ $kind = t ]; then wait $pid; echo "exit $?" >> /tmp/p18d/out/$tag.err; echo "after parent exit: $(pgrep -af "timeout $s tail" | grep -v pgrep || echo NO-PROCESS)" > /tmp/p18d/out/$tag.after; exit; fi
for i in $(seq 1 90); do pgrep -xf "sleep $s" >/dev/null && break; sleep 1; done
{ echo "$tag claude_pid=$pid"; echo "-- before kill:"; ps -eo pid,ppid,pgid,sid,stat,etime,args | grep -E "sleep $s" | grep -v grep; } > /tmp/p18d/out/$tag.kill
kill -9 $pid
sleep 30
{ echo "-- 30s after SIGKILL of $pid:"; ps -p $pid -o pid,stat 2>&1 | tail -n 1; ps -eo pid,ppid,pgid,sid,stat,etime,args | grep -E "sleep $s" | grep -v grep; echo "-- stream lines: $(wc -l < /tmp/p18d/out/$tag.jsonl)"; ls -la ~/.claude/projects/-tmp-p18d-proj/*/subagents 2>&1 | tail -5; } >> /tmp/p18d/out/$tag.kill
pkill -xf "sleep $s"   # clean-up of scratch sleep only
