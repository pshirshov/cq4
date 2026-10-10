#!/usr/bin/env python3
"""appendix.py [DIR]: markdown tables of every captured case: each hook invocation (hooks.jsonl line) and each provider request
(stub.jsonl lines of its arrival and completion), in order, with times in ms after the case's first UserPromptSubmit.
The appendix of docs/validation/governing-turn-hooks.md is this output."""
import json, pathlib, sys
root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent
KEYS = ("stop_hook_active", "source", "reason", "agent_type", "tool_name", "trigger", "notification_type")
def short(v): return v.replace("\n", " ")[:48] if isinstance(v, str) else v
for d in sorted(p for p in root.iterdir() if (p / "hooks.jsonl").exists()):
    hooks = [(i + 1, json.loads(l)) for i, l in enumerate(open(d / "hooks.jsonl")) if l.strip()]
    stub = [(i + 1, json.loads(l)) for i, l in enumerate(open(d / "stub.jsonl")) if l.strip()] if (d / "stub.jsonl").exists() else []
    t0 = next((r["t_ms"] for _, r in hooks if r["event"] == "UserPromptSubmit"), hooks[0][1]["t_ms"])
    rows = []
    for n, r in hooks:
        s = json.loads(r["stdin"]); extra = ", ".join(f"{k}={short(s[k])}" for k in KEYS if k in s)
        if r["event"] == "UserPromptSubmit": extra = "prompt=" + short(s.get("prompt"))
        rows.append((r["t_ms"], 0, f"hooks.jsonl:{n}", "hook", r["event"], extra))
    done = {r["seq"]: (n, r) for n, r in stub if r["phase"] == "done"}
    for n, r in stub:
        if r["phase"] != "arrive": continue
        dn = done.get(r["seq"])
        end = f"done {dn[1]['t_ms'] - t0:+.1f} (stub.jsonl:{dn[0]})" if dn else "no done line (held, never completed)"
        rows.append((r["t_ms"], 1, f"stub.jsonl:{n}", "request", f"#{r['seq']} {r['mode']}", f"arrive; {end}; prompt={short(r['prompt'])!r}"))
    rows.sort(key=lambda x: (x[0], x[1]))
    print(f"\n### {d.name}\n\n| ms | source | kind | event | detail |\n|---:|---|---|---|---|")
    for t, _, src, kind, ev, extra in rows:
        print(f"| {t - t0:+.1f} | {src} | {kind} | {ev} | {extra.replace('|', '/')} |")
