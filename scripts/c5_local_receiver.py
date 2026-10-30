#!/usr/bin/env python3
"""A local stand-in for the chat group's webhook: prints every notification the demo agents send.

    python scripts/c5_local_receiver.py            # listens on 127.0.0.1:18080, path /notify

The demo agents post {"title": ..., "text": ...} here by default. It listens on the loopback
address only, stores nothing and forwards nothing.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/notify":
            self.send_response(404)
            self.end_headers()
            return
        size = int(self.headers.get("Content-Length") or 0)
        data = json.loads(self.rfile.read(size) or b"{}")
        print("=" * 60)
        print("title:", data.get("title"))
        print(data.get("text"))
        sys.stdout.flush()
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("listening on http://127.0.0.1:%d/notify" % PORT, flush=True)
    HTTPServer(("127.0.0.1", PORT), Receiver).serve_forever()
