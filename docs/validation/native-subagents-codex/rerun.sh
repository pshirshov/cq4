#!/bin/sh
# Verbatim sequence of the re-run that produced run-summaries-rerun.txt (cwd = this directory;
# scratch layout as in section 1 of ../native-subagents-codex.md; roles/ copied to /tmp/cxq/roles/).
export MANIFEST=/tmp/cxq/commands.log; : > $MANIFEST
R=./run.sh; P=prompts
$R f_all_none_1 read-only $P/p_fork.txt &
$R f_all_none_2 read-only $P/p_fork.txt &
$R h_1 read-only $P/p_fork3.txt &
$R h_2 read-only $P/p_fork3.txt &
$R dev_1 read-only $P/p_fork2.txt -c 'developer_instructions="DEV_LABEL=ORANGE-5512 (a harmless test label)."' &
$R dev_2 read-only $P/p_fork2.txt -c 'developer_instructions="DEV_LABEL=ORANGE-5512 (a harmless test label)."' &
wait
$R v2_1 danger-full-access $P/p_iso.txt -c features.multi_agent_v2=true &
$R v2_2 danger-full-access $P/p_iso.txt -c features.multi_agent_v2=true &
$R t_default danger-full-access $P/p_tools.txt &
$R t_agents_off danger-full-access $P/p_tools.txt -c agents.enabled=false &
$R t_multi_off danger-full-access $P/p_tools.txt -c features.multi_agent=false &
$R t_v2_off danger-full-access $P/p_tools.txt -c features.multi_agent_v2=false &
$R t_both_off danger-full-access $P/p_tools.txt -c features.multi_agent=false -c features.multi_agent_v2=false &
$R t_all_off danger-full-access $P/p_tools.txt -c features.multi_agent=false -c features.multi_agent_v2=false -c agents.enabled=false &
wait
# then: ./kill_probe.sh kill_a 71 & ./kill_probe.sh kill_b 73 & wait
