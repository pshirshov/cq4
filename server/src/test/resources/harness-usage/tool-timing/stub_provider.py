#!/usr/bin/env python3
"""Scripted stub of a model provider for the tool-call timing captures (T87). It speaks the Anthropic Messages API
(streaming) and the OpenAI Responses API (streaming) and invents every reply.

Usage: stub_provider.py PORTFILE REQUESTLOG SCRIPT.json

SCRIPT.json: {"calls": [{"shell": "<command>"} | {"mcp": "<tool name suffix>"}, ...]}
  The first request that carries a tool catalog and no tool result answers with all calls of the script in ONE response.
  A request that carries a tool result answers with the final text "Done" (Claude Code with a `StructuredOutput` tool:
  a call of it; Codex and Pi: text, JSON for Codex's output schema). Other requests get a short text.

REQUESTLOG gets one JSON line per request, written when the reply is complete:
  {"n", "path", "t_request", "t_reply_start", "t_reply_done", "tools", "has_result", "kind", "usage", "calls"}
Times are time.time() of this process (CLOCK_REALTIME, seconds)."""
import json, sys, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

portfile, logfile, scriptfile = sys.argv[1:4]
script = json.load(open(scriptfile))
lock = threading.Lock()
state = {"n": 0}

def usage_for(n):
    return {"input": 100 * n, "cached": 10 * n, "output": 7 + n}

def sse(events, named=True):
    return "".join(("event: %s\n" % e["type"] if named else "") + "data: %s\n\n" % json.dumps(e) for e in events).encode()

def tool_names(body):
    names = []
    declared = list(body.get("tools") or [])
    if isinstance(body.get("input"), list):  # Codex 0.162 declares its tools in an `additional_tools` input item
        for i in body["input"]:
            if isinstance(i, dict) and i.get("type") == "additional_tools": declared += i.get("tools", [])
    for t in declared:
        if t.get("type") == "namespace":
            names += [(t["name"], x.get("name")) for x in t.get("tools", [])]
        else:
            names.append((None, t.get("name")))
    return names

def pick(names, wanted):
    for ns, n in names:
        if n == wanted or (n or "").endswith(wanted) or ((ns or "") + (n or "")).endswith(wanted): return ns, n
    return None, None

def shell_input(provider, names, command):
    ns, n = pick(names, "Bash")
    if n: return n, {"command": command, "description": "timing probe"}, None
    ns, n = pick(names, "exec_command")
    if n: return n, {"cmd": command}, None
    ns, n = pick(names, "shell")
    if n: return n, {"command": ["bash", "-lc", command]}, None
    ns, n = pick(names, "bash")
    if n: return n, {"command": command}, None
    ns, n = pick(names, "exec")  # Codex 0.162 "code mode": the shell is reached from JavaScript run by the custom tool `exec`
    if n and ns == "functions":
        return n, "const r = await tools.exec_command({cmd: %s, login: false}); text(JSON.stringify(r));" % json.dumps(command), ns
    return None, None, None

def plan(names):
    out = []
    for c in script["calls"]:
        if "shell" in c:
            name, args, ns = shell_input(None, names, c["shell"])
        else:
            ns, name = pick(names, c["mcp"]); args = {}
            ns2, exec_name = pick(names, "exec")
            if not name and exec_name and ns2 == "functions":  # Codex code mode: MCP tools are JavaScript functions of `tools`
                ns, name, args = ns2, exec_name, "const r = await tools.mcp__stub__%s({}); text(JSON.stringify(r));" % c["mcp"]
        out.append({"name": name, "namespace": ns, "arguments": args})
    return out

def anthropic(body, n, u):
    names = tool_names(body)
    results = json.dumps(body.get("messages", [])).count('"tool_result"')
    structured = pick(names, "StructuredOutput")[1]
    already = '"name": "StructuredOutput"' in json.dumps(body.get("messages", []))
    mid = "msg_stub_%d" % n
    events = [{"type": "message_start", "message": {"id": mid, "type": "message", "role": "assistant", "model": body.get("model", "stub"), "content": [], "stop_reason": None, "stop_sequence": None,
                "usage": {"input_tokens": u["input"], "cache_read_input_tokens": u["cached"], "cache_creation_input_tokens": 0, "output_tokens": 1}}}]
    blocks, calls, kind = [], [], "text"
    if names and not results:
        calls = [c for c in plan(names) if c["name"]]
        kind = "tool_calls"
        blocks = [("tool_use", "toolu_stub_%d_%d" % (n, i), c["name"], json.dumps(c["arguments"])) for i, c in enumerate(calls)]
    elif structured and not already:
        kind = "structured_output"; calls = [{"name": structured, "arguments": {"summary": "Done"}}]
        blocks = [("tool_use", "toolu_stub_%d_s" % n, structured, json.dumps({"summary": "Done"}))]
    else:
        blocks = [("text", None, None, "Done")]
    for i, (t, bid, name, payload) in enumerate(blocks):
        if t == "tool_use":
            events += [{"type": "content_block_start", "index": i, "content_block": {"type": "tool_use", "id": bid, "name": name, "input": {}}},
                       {"type": "content_block_delta", "index": i, "delta": {"type": "input_json_delta", "partial_json": payload}}]
        else:
            events += [{"type": "content_block_start", "index": i, "content_block": {"type": "text", "text": ""}},
                       {"type": "content_block_delta", "index": i, "delta": {"type": "text_delta", "text": payload}}]
        events.append({"type": "content_block_stop", "index": i})
    events += [{"type": "message_delta", "delta": {"stop_reason": "tool_use" if kind != "text" else "end_turn", "stop_sequence": None}, "usage": {"output_tokens": u["output"]}},
               {"type": "message_stop"}]
    return sse(events), kind, calls, bool(results), [((x[0] + ".") if x[0] else "") + str(x[1]) for x in names]

def openai(body, n, u):
    names = tool_names(body)
    inputs = [i for i in body.get("input", []) if isinstance(i, dict)] if isinstance(body.get("input"), list) else []
    results = [i for i in inputs if str(i.get("type", "")).endswith("_output")]  # function_call_output, custom_tool_call_output
    rid = "resp_stub_%d" % n
    base = {"id": rid, "object": "response", "model": body.get("model", "stub"), "status": "in_progress", "output": []}
    events = [{"type": "response.created", "response": base}, {"type": "response.in_progress", "response": base}]
    items, calls, kind = [], [], "text"
    if names and not results:
        calls = [c for c in plan(names) if c["name"]]
        kind = "tool_calls"
        for i, c in enumerate(calls):
            if isinstance(c["arguments"], str):  # a custom (freeform) tool call
                it = {"id": "ctc_stub_%d_%d" % (n, i), "type": "custom_tool_call", "status": "completed", "call_id": "call_stub_%d_%d" % (n, i), "name": c["name"], "input": c["arguments"]}
            else:
                it = {"id": "fc_stub_%d_%d" % (n, i), "type": "function_call", "status": "completed", "call_id": "call_stub_%d_%d" % (n, i), "name": c["name"],
                      "arguments": json.dumps(c["arguments"])}
                if c["namespace"]: it["namespace"] = c["namespace"]
            items.append(it)
    else:
        text = "Done"
        if body.get("text", {}).get("format", {}).get("type") == "json_schema": text = json.dumps({"summary": "Done"})
        items.append({"id": "msg_stub_%d" % n, "type": "message", "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": text, "annotations": []}]})
    for i, it in enumerate(items):
        if it["type"] == "message":
            text = it["content"][0]["text"]
            events += [{"type": "response.output_item.added", "output_index": i, "item": {**it, "content": [], "status": "in_progress"}},
                       {"type": "response.content_part.added", "output_index": i, "item_id": it["id"], "content_index": 0, "part": {"type": "output_text", "text": "", "annotations": []}},
                       {"type": "response.output_text.delta", "output_index": i, "item_id": it["id"], "content_index": 0, "delta": text},
                       {"type": "response.output_text.done", "output_index": i, "item_id": it["id"], "content_index": 0, "text": text},
                       {"type": "response.content_part.done", "output_index": i, "item_id": it["id"], "content_index": 0, "part": it["content"][0]},
                       {"type": "response.output_item.done", "output_index": i, "item": it}]
        elif it["type"] == "custom_tool_call":
            events += [{"type": "response.output_item.added", "output_index": i, "item": {**it, "input": "", "status": "in_progress"}},
                       {"type": "response.custom_tool_call_input.delta", "output_index": i, "item_id": it["id"], "delta": it["input"]},
                       {"type": "response.custom_tool_call_input.done", "output_index": i, "item_id": it["id"], "input": it["input"]},
                       {"type": "response.output_item.done", "output_index": i, "item": it}]
        else:
            events += [{"type": "response.output_item.added", "output_index": i, "item": {**it, "arguments": "", "status": "in_progress"}},
                       {"type": "response.function_call_arguments.delta", "output_index": i, "item_id": it["id"], "delta": it["arguments"]},
                       {"type": "response.function_call_arguments.done", "output_index": i, "item_id": it["id"], "arguments": it["arguments"]},
                       {"type": "response.output_item.done", "output_index": i, "item": it}]
    usage = {"input_tokens": u["input"], "input_tokens_details": {"cached_tokens": u["cached"]}, "output_tokens": u["output"],
             "output_tokens_details": {"reasoning_tokens": 0}, "total_tokens": u["input"] + u["output"]}
    events.append({"type": "response.completed", "response": {**base, "status": "completed", "output": items, "usage": usage}})
    return sse(events), kind, calls, bool(results), [((x[0] + ".") if x[0] else "") + str(x[1]) for x in names]

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def send(self, status, ctype, payload):
        self.send_response(status); self.send_header("content-type", ctype); self.send_header("content-length", str(len(payload)))
        self.send_header("request-id", "req_stub"); self.end_headers(); self.wfile.write(payload); self.wfile.flush()
    def do_GET(self):
        self.send(404, "application/json", b'{"error":{"message":"not found"}}')
    do_HEAD = do_GET
    def do_POST(self):
        t_request = time.time()
        raw = self.rfile.read(int(self.headers.get("content-length") or 0))
        try: body = json.loads(raw)
        except Exception: body = {}
        if self.path.endswith("/count_tokens"):
            return self.send(200, "application/json", b'{"input_tokens": 10}')
        with lock:
            state["n"] += 1; n = state["n"]
        u = usage_for(n)
        anthropic_api = "/v1/messages" in self.path
        if not (anthropic_api or self.path.endswith("/responses")):
            return self.send(404, "application/json", b'{"error":{"message":"not found"}}')
        payload, kind, calls, has_result, names = (anthropic if anthropic_api else openai)(body, n, u)
        t_start = time.time()
        self.send(200, "text/event-stream", payload)
        t_done = time.time()
        with lock, open(logfile, "a") as out:
            out.write(json.dumps({"n": n, "path": self.path, "t_request": t_request, "t_reply_start": t_start, "t_reply_done": t_done, "tools": names,
                                  "has_result": has_result, "kind": kind, "usage": u, "calls": calls}) + "\n")

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
server.handle_error = lambda *a: None
open(portfile, "w").write(str(server.server_address[1]))
server.serve_forever()
