#!/usr/bin/env python3
"""Hook command of the capture: appends one JSON line {"t_ms", "pid", "event", "stdin"} to LOG, where stdin is the hook's unchanged
standard input as a string and t_ms is time.time() * 1000 of this machine (CLOCK_REALTIME), the clock stub.py uses.
Usage: hook.py LOG EVENT [--block-once STATEFILE]   With --block-once, the first Stop invocation answers
{"decision":"block","reason":...} as CQ's driver hook does; later ones answer nothing."""
import json, os, sys, time
log, event = sys.argv[1:3]
t = round(time.time() * 1000, 3)
raw = sys.stdin.buffer.read().decode("utf-8", "replace")
with open(log, "a") as out: out.write(json.dumps({"t_ms": t, "pid": os.getpid(), "event": event, "stdin": raw}) + "\n")
if event == "Stop" and "--block-once" in sys.argv:
    state = sys.argv[sys.argv.index("--block-once") + 1]
    if not os.path.exists(state):
        open(state, "w").write("blocked")
        print(json.dumps({"decision": "block", "reason": "Capture hook: continue once more and answer again."}))
