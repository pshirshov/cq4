import json,sys,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
LOG=open(__import__('os').environ['STUBLOG'],'a',buffering=1)
class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def do_GET(self): self.send_response(405); self.end_headers()
    def do_DELETE(self): self.send_response(200); self.end_headers()
    def do_POST(self):
        n=int(self.headers.get('content-length',0)); b=json.loads(self.rfile.read(n) or b'{}')
        auth=self.headers.get('authorization')
        LOG.write(json.dumps({"t":time.time(),"path":self.path,"method":b.get("method"),"auth":auth})+"\n")
        m=b.get('method'); i=b.get('id'); res=None
        if m=='initialize': res={"protocolVersion":b['params'].get('protocolVersion','2025-03-26'),"capabilities":{"tools":{}},"serverInfo":{"name":"stub","version":"1"}}
        elif m=='tools/list': res={"tools":[{"name":"whoami","description":"Returns a marker string for this endpoint","inputSchema":{"type":"object","properties":{},"additionalProperties":False}}]}
        elif m=='tools/call': res={"content":[{"type":"text","text":"endpoint %s saw auth %s"%(self.path,auth)}]}
        elif i is None: self.send_response(202); self.end_headers(); return
        else: res={}
        out=json.dumps({"jsonrpc":"2.0","id":i,"result":res}).encode()
        self.send_response(200); self.send_header('content-type','application/json'); self.send_header('content-length',str(len(out))); self.end_headers(); self.wfile.write(out)
ThreadingHTTPServer(('127.0.0.1',18765),H).serve_forever()
