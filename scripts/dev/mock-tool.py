#!/usr/bin/env python3
"""Controlled local demonstration tool; never forwards requests or logs payloads."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
class Tool(BaseHTTPRequestHandler):
    calls = 0
    def log_message(self, *args): pass
    def do_POST(self):
        size=int(self.headers.get('Content-Length','0'))
        if self.path != '/refund' or size>65536:
            self.send_error(400); return
        json.loads(self.rfile.read(size))
        Tool.calls+=1
        body=json.dumps({'demoReceipt':'local-only','calls':Tool.calls}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        body=json.dumps({'calls':Tool.calls}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)
ThreadingHTTPServer(('0.0.0.0',9000),Tool).serve_forever()
