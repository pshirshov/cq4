#!/usr/bin/env python3
"""intervals.py CASEDIR...: per turn (UserPromptSubmit to the last Stop before the next UserPromptSubmit) the provider requests
whose arrival lies in it and whether each is complete before that last Stop. Times in ms; lines are hooks.jsonl / stub.jsonl line numbers."""
import json, sys
for d in sys.argv[1:]:
    hooks = [(i + 1, json.loads(l)) for i, l in enumerate(open(f"{d}/hooks.jsonl"))]
    try: stub = [(i + 1, json.loads(l)) for i, l in enumerate(open(f"{d}/stub.jsonl"))]
    except FileNotFoundError: stub = []
    ups = [(i, r) for i, r in hooks if r["event"] == "UserPromptSubmit"]
    print(f"## {d}")
    for k, (i, u) in enumerate(ups):
        nxt = ups[k + 1][1]["t_ms"] if k + 1 < len(ups) else float("inf")
        stops = [(j, r) for j, r in hooks if r["event"] == "Stop" and u["t_ms"] <= r["t_ms"] < nxt]
        if not stops: print(f"  turn {k+1}: UserPromptSubmit line {i} t={u['t_ms']:.1f}: NO Stop"); continue
        j, last = stops[-1]
        arrivals = [(n, r) for n, r in stub if r["phase"] == "arrive" and u["t_ms"] <= r["t_ms"] < nxt]
        done = {r["seq"]: (n, r) for n, r in stub if r["phase"] == "done"}
        inside = sum(1 for n, r in arrivals if r["seq"] in done and r["t_ms"] >= u["t_ms"] and done[r["seq"]][1]["t_ms"] <= last["t_ms"])
        lead = [round(r["t_ms"] - u["t_ms"], 1) for n, r in arrivals]
        slack = [round(last["t_ms"] - done[r["seq"]][1]["t_ms"], 1) for n, r in arrivals if r["seq"] in done]
        print(f"  turn {k+1}: UserPromptSubmit line {i} t={u['t_ms']:.1f}; Stops {[(j2, round(r['t_ms'],1), r.get('stdin') and json.loads(r['stdin']).get('stop_hook_active')) for j2, r in stops]}; "
              f"duration {last['t_ms']-u['t_ms']:.1f}; requests {len(arrivals)} (seq {[r['seq'] for n, r in arrivals]}) inside {inside}; arrive-after-UPS {lead}; done-before-lastStop {slack}")
