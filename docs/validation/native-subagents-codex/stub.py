import json,sys,time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
LOG='/tmp/cxq/stub.log'
class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def _rec(self,body,rpc):
        with open(LOG,'a') as f:
            f.write(json.dumps({'t':round(time.time(),3),'http':self.command,'path':self.path,'auth':self.headers.get('Authorization'),'xh':{k:v for k,v in self.headers.items() if k.lower().startswith('x-codex') or k.lower().startswith('mcp-')},'rpc':rpc,'meta':(body or {}).get('params',{}).get('_meta') if isinstance(body,dict) else None,'tool':(body or {}).get('params',{}).get('name') if isinstance(body,dict) else None})+'\n')
    def do_GET(self):
        self._rec(None,'GET'); self.send_response(405); self.end_headers()
    def do_DELETE(self):
        self._rec(None,'DELETE'); self.send_response(200); self.end_headers()
    def do_POST(self):
        n=int(self.headers.get('Content-Length',0)); raw=self.rfile.read(n)
        try: b=json.loads(raw)
        except Exception: b={}
        m=b.get('method'); self._rec(b,m)
        if 'id' not in b:
            self.send_response(202); self.end_headers(); return
        if m=='initialize':
            r={'protocolVersion':b['params'].get('protocolVersion','2025-03-26'),'capabilities':{'tools':{}},'serverInfo':{'name':'stub','version':'0'}}
        elif m=='tools/list':
            r={'tools':[{'name':'whoami','description':'Returns a fixed string. Call it to verify the server is reachable.','inputSchema':{'type':'object','properties':{},'additionalProperties':False}}]}
        elif m=='tools/call':
            r={'content':[{'type':'text','text':'STUB_OK auth_seen=%s'%self.headers.get('Authorization')}]}
        else: r={}
        out=json.dumps({'jsonrpc':'2.0','id':b['id'],'result':r}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(out))); self.end_headers(); self.wfile.write(out)
ThreadingHTTPServer(('127.0.0.1',47651),H).serve_forever()
