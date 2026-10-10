#!/usr/bin/env python3
"""timeline.py CASEDIR: hook invocations and stub requests of one captured case on one time line (ms since the first record)."""
import json, sys
d = sys.argv[1]; rows = []
KEYS = ("stop_hook_active", "source", "reason", "agent_type", "tool_name", "agent_id", "trigger", "notification_type", "last_assistant_message")
for l in open(f"{d}/hooks.jsonl"):
    r = json.loads(l); s = json.loads(r["stdin"])
    rows.append((r["t_ms"], "HOOK", r["event"], {k: (v[:40] if isinstance(v, str) else v) for k, v in s.items() if k in KEYS}))
try:
    for l in open(f"{d}/stub.jsonl"):
        r = json.loads(l); rows.append((r["t_ms"], "STUB", r["phase"], f'seq{r["seq"]} {r["mode"]} {r["prompt"][:30]!r}'))
except FileNotFoundError: pass
rows.sort(key=lambda x: x[0]); t0 = rows[0][0]
for r in rows: print(f"{r[0]-t0:9.1f} {r[1]} {r[2]} {r[3]}")
