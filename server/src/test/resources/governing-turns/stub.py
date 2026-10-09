#!/usr/bin/env python3
"""Local imitation of the Anthropic Messages API (streaming and non-streaming) that scripts the model's replies from markers
in the user's prompt. Every reply is invented; no model takes part. Usage: stub.py PORTFILE REQUESTLOG
Markers (in the text of the last user message; a tool_result as the last user message always gets a plain final answer):
  [stub:sleep]  reply with a Bash tool call `sleep 2`        [stub:bg]    reply with a Bash tool call that runs `sleep 6` in the background
  [stub:agent]  reply with a Task/Agent (subagent) tool call  [stub:hold]  hold the reply back for 25 s, then answer
  anything else: a short plain text answer.
Log: one JSON line when a request arrives ("phase":"arrive") and one when its reply is complete ("phase":"done"); `t_ms` is
time.time() * 1000 of this machine (CLOCK_REALTIME), the same clock hook.py uses."""
import json, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

portfile, log = sys.argv[1:3]
lock = threading.Lock()
counter = [0]

def now(): return round(time.time() * 1000, 3)
def write(entry):
    with lock, open(log, "a") as out: out.write(json.dumps(entry) + "\n")

def classify(body):
    messages = body.get("messages") or []
    last = next((m for m in reversed(messages) if m.get("role") == "user"), None)
    blocks = last.get("content") if last else []
    if isinstance(blocks, str): blocks = [{"type": "text", "text": blocks}]
    if any(b.get("type") == "tool_result" for b in blocks): return "tool_result", ""
    text = "\n".join(b.get("text", "") for b in blocks if b.get("type") == "text" and "<system-reminder>" not in b.get("text", ""))
    for marker in ("sleep", "bg", "agent", "hold"):
        if f"[stub:{marker}]" in text: return marker, text
    return "plain", text

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        self.send_response(404); self.send_header("content-length", "0"); self.end_headers()
    def do_POST(self):
        arrived = now()
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(length) if length else b""
        try: body = json.loads(raw)
        except Exception: body = {}
        with lock:
            counter[0] += 1; seq = counter[0]
        mode, text = classify(body)
        tools = [t.get("name") for t in body.get("tools") or []]
        entry = {"seq": seq, "path": self.path, "model": body.get("model"), "stream": body.get("stream", False), "tools": len(tools),
                 "mode": mode, "prompt": text[:90], "messages": len(body.get("messages") or [])}
        write({**entry, "phase": "arrive", "t_ms": arrived})
        if not self.path.startswith("/v1/messages"):
            self.send_response(404); self.send_header("content-length", "0"); self.end_headers(); return
        blocks = []   # content blocks of the reply
        stop = "end_turn"
        if mode == "hold": time.sleep(25)
        if mode == "sleep":
            blocks = [("tool_use", "Bash", {"command": "sleep 2", "description": "sleep two seconds"})]; stop = "tool_use"
        elif mode == "bg":
            blocks = [("tool_use", "Bash", {"command": "sleep 6; echo bg-done", "description": "background sleep", "run_in_background": True})]; stop = "tool_use"
        elif mode == "agent":
            name = "Agent" if "Agent" in tools else "Task"
            blocks = [("tool_use", name, {"description": "stub subagent", "prompt": "Reply with one word. [stub:plain]", "subagent_type": "general-purpose"})]; stop = "tool_use"
        else:
            blocks = [("text", None, "Stub answer (invented): " + {"tool_result": "the tool call finished.", "plain": "done.", "hold": "done after the hold."}[mode])]
        message = {"id": f"msg_stub{seq:04d}", "type": "message", "role": "assistant", "model": body.get("model") or "stub", "content": [],
                   "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 10, "output_tokens": 1}}
        try:
            if body.get("stream"):
                self.send_response(200)
                for k, v in (("content-type", "text/event-stream"), ("cache-control", "no-cache"), ("transfer-encoding", "chunked")): self.send_header(k, v)
                self.end_headers()
                def event(name, data):
                    chunk = f"event: {name}\ndata: {json.dumps(data)}\n\n".encode()
                    self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n"); self.wfile.flush()
                event("message_start", {"type": "message_start", "message": message})
                for index, (kind, name, value) in enumerate(blocks):
                    if kind == "text":
                        event("content_block_start", {"type": "content_block_start", "index": index, "content_block": {"type": "text", "text": ""}})
                        words = value.split(" ")
                        for part in (words[:3], words[3:]):
                            time.sleep(0.1)
                            event("content_block_delta", {"type": "content_block_delta", "index": index, "delta": {"type": "text_delta", "text": " ".join(part) + " "}})
                    else:
                        event("content_block_start", {"type": "content_block_start", "index": index,
                              "content_block": {"type": "tool_use", "id": f"toolu_stub{seq:04d}", "name": name, "input": {}}})
                        event("content_block_delta", {"type": "content_block_delta", "index": index, "delta": {"type": "input_json_delta", "partial_json": json.dumps(value)}})
                    event("content_block_stop", {"type": "content_block_stop", "index": index})
                event("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None}, "usage": {"output_tokens": 8}})
                event("message_stop", {"type": "message_stop"})
                self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
            else:
                content = [{"type": "text", "text": v} if k == "text" else {"type": "tool_use", "id": f"toolu_stub{seq:04d}", "name": n, "input": v} for k, n, v in blocks]
                payload = json.dumps({**message, "content": content, "stop_reason": stop, "usage": {"input_tokens": 10, "output_tokens": 8}}).encode()
                self.send_response(200); self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(payload))); self.end_headers()
                self.wfile.write(payload)
            write({**entry, "phase": "done", "t_ms": now(), "stop_reason": stop})
        except (BrokenPipeError, ConnectionResetError):
            write({**entry, "phase": "aborted", "t_ms": now()})

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
server.daemon_threads = True
server.handle_error = lambda *a: None
open(portfile, "w").write(str(server.server_address[1]))
server.serve_forever()
