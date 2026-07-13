#!/usr/bin/env python3
"""KwikQuant GitHub webhook receiver — push main 触发 server-deploy.sh(服务器 self-build)。"""
import hmac, hashlib, subprocess, json, os
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ.get("WEBHOOK_SECRET", "").encode()
PORT = int(os.environ.get("WEBHOOK_PORT", "9000"))
DEPLOY = os.environ.get("DEPLOY_PATH", "/opt/kwikquant")
REPO_FULL = os.environ.get("REPO_FULL", "huiboxes/kwikquant")
BRANCH = os.environ.get("DEPLOY_BRANCH", "main")
LOG = os.environ.get("WEBHOOK_LOG", f"{DEPLOY}/webhook.log")

def log(msg):
    with open(LOG, "a") as f:
        f.write(msg + "\n")

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b"ok"):
        self.send_response(code); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        self._send(200, b"ok")
    def do_POST(self):
        if self.path != "/webhook":
            self._send(404); return
        sig = self.headers.get("X-Hub-Signature-256", "")
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        mac = hmac.new(SECRET, body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(f"sha256={mac}", sig):
            log("✗ 签名不匹配"); self._send(401); return
        if self.headers.get("X-GitHub-Event", "") != "push":
            self._send(200); return
        payload = json.loads(body)
        if payload.get("ref") != f"refs/heads/{BRANCH}":
            self._send(200, b"not " + BRANCH.encode()); return
        after = payload["after"]
        log(f"→ push {after} 触发部署")
        # 异步触发,不阻塞 webhook 响应
        subprocess.Popen(
            ["bash", f"{DEPLOY}/server-deploy.sh", after],
            cwd=DEPLOY, stdout=open(LOG, "a"), stderr=subprocess.STDOUT,
        )
        self._send(200, b"deploying " + after.encode())

if __name__ == "__main__":
    log(f"webhook receiver 启动 :{PORT} (repo={REPO_FULL} branch={BRANCH})")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
