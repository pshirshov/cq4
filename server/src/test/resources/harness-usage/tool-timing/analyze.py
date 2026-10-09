#!/usr/bin/env python3
"""Print, for each capture directory beside this script, the marker intervals, the stub MCP call interval and each stdout
line with its receipt time (seconds after t_spawn), so that docs/validation/tool-call-timing.md can be checked against the files.
Usage: analyze.py [DIR...] (default: every <harness>-<version>-<case> directory)"""
import json, sys
from pathlib import Path
here = Path(__file__).resolve().parent
dirs = [Path(a) for a in sys.argv[1:]] or sorted(p for p in here.iterdir() if p.is_dir())
for d in dirs:
    run = json.load(open(d / "run.json")); t0 = run["t_spawn"]
    print("==", d.name, "exit", (d / "exit.txt").read_text().strip(), "t_spawn=%.6f" % t0, "run %.3fs" % (run["t_exit"] - t0))
    ms = {}
    for f in sorted((d / "markers").iterdir()):
        ms.setdefault(f.stem, {})[f.suffix[1:]] = float(f.read_text())
    for k, v in ms.items(): print("  marker %s start=%.6f end=%.6f duration=%.3f (start at +%.3f)" % (k, v["start"], v["end"], v["end"] - v["start"], v["start"] - t0))
    if len(ms) == 2:
        (a, b) = sorted(ms.items())
        print("  overlap: %.3f s" % max(0, min(a[1]["end"], b[1]["end"]) - max(a[1]["start"], b[1]["start"])))
    if (d / "mcp-calls.jsonl").exists():
        for l in open(d / "mcp-calls.jsonl"):
            r = json.loads(l)
            if r["method"] == "tools/call": print("  mcp tools/call arrived %.6f replied %.6f duration=%.3f" % (r["t_request"], r["t_reply_done"], r["t_reply_done"] - r["t_request"]))
    for l in open(d / "requests.jsonl"):
        r = json.loads(l)
        print("  provider req %d %s arrived %.6f reply_done %.6f kind=%s usage=%s" % (r["n"], r["path"], r["t_request"], r["t_reply_done"], r["kind"], r["usage"]))
    lines, times = open(d / "stdout.jsonl").read().splitlines(), open(d / "stdout.times").read().split()
    for i, (l, t) in enumerate(zip(lines, times), 1):
        try: e = json.loads(l)
        except Exception: e = {}
        sub = (e.get("assistantMessageEvent") or {}).get("type") or e.get("subtype") or ""
        print("  L%-3d t=%.6f +%.3f %s %s" % (i, float(t), float(t) - t0, e.get("type"), sub))
