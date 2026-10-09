#!/usr/bin/env python3
"""Capture one scripted tool-call case of one harness against stub_provider.py (T87).

Usage: capture.py HARNESS CASE [OUTROOT]
  HARNESS  claude | codex | pi
  CASE     a            one shell call that sleeps 2 s between its markers, then a final answer
           b            one model response with two independent shell calls, each sleeping 2 s between its markers
           c            one call of the stub MCP server's `stub_wait` tool (claude, codex)
           a-rollout    codex only: case a without --ephemeral; the rollout file is copied
Writes OUTROOT/<harness>-<version>-<case>/ (default: this directory):
  command.txt stdout.jsonl stdout.times stderr.txt exit.txt version.txt run.json requests.jsonl mcp-calls.jsonl markers/ [rollout/]
stdout.jsonl is the harness's stdout, unchanged. stdout.times line N is the time.time() at which the read returned that
completed line N of stdout.jsonl. All times are CLOCK_REALTIME seconds of one machine, the same clock as `date +%s.%N`
in the markers and as the stubs' logs. The argument lists follow the Worker launch of host/src/main/scala/cq/host/HarnessAdapter.scala;
the differences are listed in README.md."""
import json, os, re, shutil, subprocess, sys, threading, time, uuid
from pathlib import Path

here = Path(__file__).resolve().parent
harness, case = sys.argv[1:3]
root = Path(sys.argv[3]) if len(sys.argv) > 3 else here
BIN = {
  "claude": "/nix/store/qj6b1yhhjmk40fk03gyab8svxwnjd5bd-claude-code-2.1.295/bin/claude",
  "codex": "/nix/store/n4rql6r3ky5lhymfjdh5spw04j60isi9-codex-0.162.0/bin/codex",
  "pi": "/nix/store/hqw3jmpzzhzi7kdhfzd8cczbppjkbf6q-pi-coding-agent-1.1.0/bin/pi",
}[harness]
version = re.search(r"\d+\.\d+\.\d+", subprocess.run([BIN, "--version"], capture_output=True, text=True).stdout).group(0)
tag = "%s-%s-%s" % (harness, version, case)
out = root / tag
shutil.rmtree(out, ignore_errors=True); (out / "markers").mkdir(parents=True)
(out / "version.txt").write_text(subprocess.run([BIN, "--version"], capture_output=True, text=True).stdout)
tmp = Path("/tmp/cq-tool-timing") / tag
shutil.rmtree(tmp, ignore_errors=True)
for d in ("home", "config", "work", "markers"): (tmp / d).mkdir(parents=True)
M = tmp / "markers"

def mark(label): return "date +%%s.%%N > %s/%s.start; sleep 2; date +%%s.%%N > %s/%s.end" % (M, label, M, label)
calls = {"a": [{"shell": mark("a1")}], "a-rollout": [{"shell": mark("a1")}], "b": [{"shell": mark("b1")}, {"shell": mark("b2")}], "c": [{"mcp": "stub_wait"}]}[case.split("-")[0] if case == "a-rollout" else case]
(tmp / "script.json").write_text(json.dumps({"calls": calls}))

def start(script, *args):
    port = tmp / (script + ".port")
    p = subprocess.Popen([sys.executable, str(here / script)] + [str(port)] + [str(a) for a in args])
    for _ in range(100):
        if port.exists() and port.read_text(): break
        time.sleep(0.1)
    return p, port.read_text()

prov, pport = start("stub_provider.py", out / "requests.jsonl", tmp / "script.json")
mcp, mport = (start("stub_mcp.py", out / "mcp-calls.jsonl") if case == "c" else (None, None))
base = "http://127.0.0.1:" + pport
mcp_url = "http://127.0.0.1:%s/mcp" % mport
common = {"HOME": str(tmp / "home"), "PATH": "/run/current-system/sw/bin:/usr/bin:/bin", "LANG": "C.UTF-8",
          "SSL_CERT_FILE": "/etc/ssl/certs/ca-certificates.crt", "NIX_SSL_CERT_FILE": "/etc/ssl/certs/ca-certificates.crt"}
SYSTEM = "Run the scripted tool calls, then reply with a one-word summary."
schema = {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"], "additionalProperties": False}
j = lambda v: json.dumps(v, separators=(",", ":"))

if harness == "claude":
    mcp_servers = {"stub": {"type": "http", "url": mcp_url, "headers": {"Authorization": "Bearer stub"}}} if case == "c" else {}
    (tmp / "claude-mcp.json").write_text(j({"mcpServers": mcp_servers}))
    builtin = ["Read", "Glob", "Grep", "Edit", "Write", "Bash"]
    allowed = builtin + (["mcp__stub__stub_wait"] if case == "c" else [])
    env = {**common, "CLAUDE_CONFIG_DIR": str(tmp / "config"), "ANTHROPIC_BASE_URL": base,
           "ANTHROPIC_API_KEY": "sk-ant-api03-cq-tool-timing-wrong-key-0000000000000000000000000000000000000000000000000000000000000000-AAAAAAAA"}
    cmd = [BIN, "--print", "--output-format", "stream-json", "--verbose", "--restricted", "--no-session-persistence", "--session-id", str(uuid.uuid4()),
           "--model", "claude-opus-5-5", "--disable-slash-commands", "--strict-mcp-config", "--mcp-config", str(tmp / "claude-mcp.json"),
           "--tools", ",".join(builtin), "--allowedTools", ",".join(allowed), "--disallowedTools", "Agent,Task,TaskOutput,TaskStop",
           "--system-prompt", SYSTEM, "--json-schema", j(schema)]
elif harness == "codex":
    cfg = lambda k, v: ["-c", "%s=%s" % (k, j(v))]
    denied = ["features.multi_agent", "features.multi_agent_v2", "features.plugins", "features.plugin_hooks", "features.apps", "features.enable_mcp_apps",
              "features.memories", "features.skill_search", "features.skill_mcp_dependency_install"]
    restrictions = sum([cfg(k, False) for k in denied], []) + cfg("features.code_mode_host", True) + sum([cfg(k, True) for k in ("features.shell_tool", "features.unified_exec", "features.apply_patch_freeform")], []) \
        + cfg("agents.enabled", False) + cfg("approval_policy", "never") + cfg("model_provider", "openai") + cfg("web_search", "disabled") + cfg("tools.update_plan.enabled", False) \
        + cfg("developer_instructions", SYSTEM) + cfg("openai_base_url", base + "/v1")
    mcpargs = (cfg("mcp_servers.stub.url", mcp_url) + cfg("mcp_servers.stub.bearer_token_env_var", "CQ_STUB_TOKEN") + cfg("mcp_servers.stub.enabled_tools", ["stub_wait"])
               + cfg("mcp_servers.stub.required", True) + cfg("mcp_servers.stub.startup_timeout_sec", 10) + cfg("mcp_servers.stub.tool_timeout_sec", 60)
               + cfg("mcp_servers.stub.default_tools_approval_mode", "approve")) if case == "c" else []
    (tmp / "result-schema.json").write_text(j(schema))
    env = {**common, "CODEX_HOME": str(tmp / "config"), "CODEX_API_KEY": "sk-proj-cq-tool-timing-wrong-key-000000000000000000000000000000000000", "CQ_STUB_TOKEN": "stub"}
    cmd = [BIN, "exec", "--json"] + ([] if case == "a-rollout" else ["--ephemeral"]) + ["--ignore-user-config", "--ignore-rules", "--strict-config", "--model", "gpt-6-sol",
           "--sandbox", "danger-full-access", "--output-schema", str(tmp / "result-schema.json"), "--output-last-message", str(tmp / "last-message.json")] + restrictions + mcpargs + ["-"]
else:
    (tmp / "config" / "models.json").write_text(j({"providers": {"openai": {"baseUrl": base + "/v1"}}}))
    env = {**common, "PI_CODING_AGENT_DIR": str(tmp / "config"), "OPENAI_API_KEY": "sk-proj-cq-tool-timing-wrong-key-000000000000000000000000000000000000"}
    cmd = [BIN, "--offline", "--mode", "json", "--print", "--no-session", "--session-id", str(uuid.uuid4()), "--provider", "openai", "--model", "gpt-5.5",
           "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes", "--no-approve",
           "--tools", "read,write,edit,bash", "--system-prompt", SYSTEM]

(tmp / "work").mkdir(exist_ok=True)
subprocess.run(["git", "init", "-q", str(tmp / "work")])
text = " ".join(("env -i " + " ".join("%s=%s" % kv for kv in env.items()) + " " + " ".join(cmd)).split())
text = re.sub(r"(API_KEY)=\S+", r"\1=<wrong-key>", text).replace(pport, "<provider-port>")
if mport: text = text.replace(mport, "<mcp-port>")
(out / "command.txt").write_text(text + "\n")

times, lines = [], []
t_spawn = time.time()
proc = subprocess.Popen(cmd, env=env, cwd=tmp / "work", stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=open(out / "stderr.txt", "wb"))
proc.stdin.write(b"Run the scripted tool calls.\n"); proc.stdin.close()
buf = b""
with open(out / "stdout.jsonl", "wb") as so, open(out / "stdout.times", "w") as st:
    while True:
        chunk = os.read(proc.stdout.fileno(), 65536)
        now = time.time()
        if not chunk: break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            so.write(line + b"\n"); st.write("%.6f\n" % now)
    if buf: so.write(buf); st.write("%.6f\n" % now)
code = proc.wait(); t_exit = time.time()
(out / "exit.txt").write_text("%d\n" % code)
(out / "run.json").write_text(json.dumps({"harness": harness, "version": version, "case": case, "clock": "CLOCK_REALTIME, seconds since the epoch, one machine",
                                           "t_spawn": t_spawn, "t_exit": t_exit}, indent=1) + "\n")
prov.terminate()
if mcp: mcp.terminate()
for f in M.iterdir(): shutil.copy(f, out / "markers" / f.name)
if case == "a-rollout":
    (out / "rollout").mkdir()
    for f in (tmp / "config").rglob("*.jsonl"): shutil.copy(f, out / "rollout" / f.name)
(out / "stdout.jsonl").exists()
print(tag, "exit", code, "lines", sum(1 for _ in open(out / "stdout.jsonl")))
