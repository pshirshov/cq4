#!/bin/sh
# S3: verbatim sequence of the re-run that produced run-summaries-s3.txt (cwd = this directory).
# Scratch layout as in section 1 of ../native-subagents-codex.md; roles/ copied to /tmp/cxq/roles/;
# home_noroles = scratch-config.toml truncated to its first 7 lines (no [agents.*] entries).
export MANIFEST=/tmp/cxq/commands.log; : > $MANIFEST
export STUB_TOKEN_PARENT=parent-token-AAA STUB_TOKEN_CHILD=child-token-BBB
MCP='-c mcp_servers.stub.url=http://127.0.0.1:47651/mcp -c mcp_servers.stub.bearer_token_env_var=STUB_TOKEN_PARENT -c mcp_servers.stub.default_tools_approval_mode=approve'
R=./run.sh; P=prompts
S=/srv/nvme/tmp/cxq-probe
for m in read-only workspace-write danger-full-access; do
  rm -f $S/wt/child_cwd.txt $S/sib/child_sib.txt $S/repo/.git/child_gitdir.txt
  for i in 1 2; do
    CODEX_HOME_DIR=/tmp/cxq/home_noroles $R s3_noroles_${m}_$i $m $P/p_iso.txt &
    $R s3_iso_${m}_$i $m $P/p_iso.txt &
  done
  wait
  echo "FS after $m: $(ls $S/wt/child_cwd.txt $S/sib/child_sib.txt $S/repo/.git/child_gitdir.txt 2>&1 | tr '\n' ' ')" >> $MANIFEST
done
$R s3_role_dfa_1 danger-full-access $P/p_role.txt &
$R s3_role_dfa_2 danger-full-access $P/p_role.txt &
$R s3_role2_dfa_1 danger-full-access $P/p_role2.txt &
$R s3_role2_dfa_2 danger-full-access $P/p_role2.txt &
$R s3_role3_ro_1 read-only $P/p_role2.txt &
$R s3_role3_ro_2 read-only $P/p_role2.txt &
wait
$R s3_mcp_1 danger-full-access $P/p_mcp.txt $MCP &
$R s3_mcp_2 danger-full-access $P/p_mcp.txt $MCP &
wait
$R s3_mcp3_1 danger-full-access $P/p_mcp3.txt $MCP &
$R s3_mcp3_2 danger-full-access $P/p_mcp3.txt $MCP &
$R s3_par_1 read-only $P/p_par.txt --output-schema schema.json &
$R s3_par_2 read-only $P/p_par.txt --output-schema schema.json &
$R s3_cancel_1 danger-full-access $P/p_cancel.txt &
$R s3_cancel_2 danger-full-access $P/p_cancel.txt &
wait
