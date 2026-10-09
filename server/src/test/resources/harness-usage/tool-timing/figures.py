#!/usr/bin/env python3
"""Pair each tool call's start and end events in a capture's stdout.jsonl and print execution (marker / stub MCP log) and receipt
figures, all in seconds of one CLOCK_REALTIME. Pairing keys: Claude Code tool_use.id = tool_result.tool_use_id; Codex item.id of
item.started/item.completed; Pi toolCallId of tool_execution_start/tool_execution_end. Usage: figures.py [DIR...]"""
import json, sys
from datetime import datetime, timezone
from pathlib import Path
here = Path(__file__).resolve().parent
iso = lambda s: datetime.strptime(s, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc).timestamp()

def load(d):
    lines = [json.loads(l) for l in open(d / "stdout.jsonl")]
    times = [float(x) for x in open(d / "stdout.times").read().split()]
    return list(zip(range(1, len(lines) + 1), lines, times))

def executions(d):
    out = {}
    for f in sorted((d / "markers").glob("*.start")):
        out[f.stem] = (float(f.read_text()), float((d / "markers" / (f.stem + ".end")).read_text()))
    if (d / "mcp-calls.jsonl").exists():
        for l in open(d / "mcp-calls.jsonl"):
            r = json.loads(l)
            if r["method"] == "tools/call": out["mcp"] = (r["t_request"], r["t_reply_done"])
    return out

def pairs(harness, events):
    """[(label, start_line, start_receipt, end_line, end_receipt, native_start_ts, native_end_ts)] in order of start."""
    out = []
    if harness == "claude":
        starts, ends = {}, {}
        for n, e, t in events:
            for c in (e.get("message", {}).get("content") or []) if isinstance(e.get("message", {}).get("content"), list) else []:
                if e["type"] == "assistant" and c.get("type") == "tool_use" and c["name"] != "StructuredOutput": starts[c["id"]] = (n, t, e.get("timestamp"), c["name"])
                if e["type"] == "user" and c.get("type") == "tool_result" and c["tool_use_id"] in starts: ends[c["tool_use_id"]] = (n, t, e.get("timestamp"))
        for k, (n, t, ts, name) in starts.items(): out.append((name + " " + k, n, t, ends[k][0], ends[k][1], ts, ends[k][2]))
    elif harness == "codex":
        starts, ends = {}, {}
        for n, e, t in events:
            i = e.get("item", {})
            if e["type"] == "item.started" and i.get("type") in ("command_execution", "mcp_tool_call"): starts[i["id"]] = (n, t, i["type"])
            if e["type"] == "item.completed" and i.get("id") in starts: ends[i["id"]] = (n, t)
        for k, (n, t, kind) in starts.items(): out.append((kind + " " + k, n, t, ends[k][0], ends[k][1], None, None))
    else:
        starts, ends = {}, {}
        for n, e, t in events:
            if e["type"] == "tool_execution_start": starts[e["toolCallId"]] = (n, t)
            if e["type"] == "tool_execution_end": ends[e["toolCallId"]] = (n, t, e.get("durationMs"))
        for k, (n, t) in starts.items(): out.append((k, n, t, ends[k][0], ends[k][1], None, ends[k][2]))
    return out

for d in [Path(a) for a in sys.argv[1:]] or sorted(p for p in here.iterdir() if p.is_dir()):
    harness = d.name.split("-")[0]
    ex = list(executions(d).items()); ps = pairs(harness, load(d))
    print("==", d.name)
    for (label, ls, rs, le, re_, ts_s, ts_e), (name, (xs, xe)) in zip(ps, ex):
        print("  %-48s exec %s start=%.6f end=%.6f dur=%.3f | start line L%d at %.6f (%+.3f vs exec start), end line L%d at %.6f (%+.3f vs exec end), receipt gap=%.3f, gap-exec=%+.3f%s" % (
            label, name, xs, xe, xe - xs, ls, rs, rs - xs, le, re_, re_ - xe, re_ - rs, (re_ - rs) - (xe - xs),
            "" if ts_s is None and ts_e is None else " | native ts start=%s end=%s" % (ts_s, ts_e)))
    if len(ex) == 2:
        (_, (a0, a1)), (_, (b0, b1)) = ex
        print("  exec overlap %.3f s; gap between first end and second start %.3f s" % (max(0, min(a1, b1) - max(a0, b0)), b0 - a1))
