#!/usr/bin/env bash
# go.sh name model agentsfile "<extra flags string>" "<prompt>"   (extra flags word-split)
n=$1; m=$2; a=$3; fl=$4; p=$5
cd /tmp/p18d/proj
argv=(claude -p --output-format stream-json --verbose --model "$m" --no-session-persistence --agents "$(cat /tmp/p18d/$a)" $fl "$p")
{ echo "cwd=/tmp/p18d/proj stdin=/dev/null timeout 300"; printf '%q ' "${argv[@]}"; echo; } > /tmp/p18d/out/cmd-$n.txt
timeout 300 "${argv[@]}" > /tmp/p18d/out/$n.jsonl 2> /tmp/p18d/out/$n.err < /dev/null
echo "exit $?" >> /tmp/p18d/out/$n.err
