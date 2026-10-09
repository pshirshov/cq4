#!/usr/bin/env python3
"""Stub MCP server (streamable HTTP, JSON replies) with one tool, `stub_wait`, that waits 2 s before it replies.
Usage: stub_mcp.py PORTFILE CALLLOG
CALLLOG gets one JSON line per JSON-RPC request when its reply is complete:
  {"method", "t_request", "t_reply_done", "id"}  (time.time() of this process, CLOCK_REALTIME seconds)"""
import json, sys, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

portfile, logfile = sys.argv[1:3]
lock = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def send(self, status, payload=b"", ctype="application/json"):
        self.send_response(status); self.send_header("content-type", ctype); self.send_header("content-length", str(len(payload)))
        self.send_header("mcp-session-id", "stub-session"); self.end_headers(); self.wfile.write(payload); self.wfile.flush()
    def do_GET(self): self.send(405)
    def do_DELETE(self): self.send(200)
    def do_POST(self):
        t_request = time.time()
        msg = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
        method, mid = msg.get("method"), msg.get("id")
        result = None
        if method == "initialize":
            result = {"protocolVersion": (msg.get("params") or {}).get("protocolVersion", "2025-03-26"), "capabilities": {"tools": {}}, "serverInfo": {"name": "stub", "version": "1"}}
        elif method == "tools/list":
            result = {"tools": [{"name": "stub_wait", "description": "Waits two seconds, then answers.", "inputSchema": {"type": "object", "properties": {}}}]}
        elif method == "tools/call":
            time.sleep(2)
            result = {"content": [{"type": "text", "text": "waited"}], "isError": False}
        elif method == "ping":
            result = {}
        if mid is None or result is None:
            self.send(202)
        else:
            self.send(200, json.dumps({"jsonrpc": "2.0", "id": mid, "result": result}).encode())
        with lock, open(logfile, "a") as out:
            out.write(json.dumps({"method": method, "id": mid, "t_request": t_request, "t_reply_done": time.time()}) + "\n")

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
server.handle_error = lambda *a: None
open(portfile, "w").write(str(server.server_address[1]))
server.serve_forever()
