#!/usr/bin/env python3
"""KwikQuant GitHub webhook receiver — push main 触发 server-deploy.sh(服务器 self-build)。"""
import hmac, hashlib, subprocess, json, os
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ.get("WEBHOOK_SECRET", "").encode()
PORT = int(os.environ.get("WEBHOOK_PORT", "9000"))
DEPLOY = os.environ.get("DEPLOY_PATH", "/opt/kwikquant")
REPO_FULL = os.environ.get("REPO_FULL", "huiboxes/kwikquant")
BRANCH = os.environ.get("DEPLOY_BRANCH", "main")
LOG = os.environ.get("WEBHOOK_LOG", f"{DEPLOY}/webhook.log")
REPO = os.path.join(DEPLOY, "repo")  # server-deploy.sh 也用 $DEPLOY/repo,保持一致
ZERO = "0" * 40  # 分支首推/删分支时 before 为全 0

def log(msg):
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(LOG, "a") as f:
        f.write(f"[{ts}] {msg}\n")

def changed_outside_frontend(before, after):
    """git diff before..after 中 frontend/ 以外的改动文件;失败返 None → 调用方 fallback 照常部署。"""
    try:
        r = subprocess.run(
            ["git", "-C", REPO, "diff", "--name-only", before, after],
            capture_output=True, text=True, timeout=60,
        )
        if r.returncode != 0:
            return None
        return [l for l in r.stdout.splitlines() if l and not l.startswith("frontend/")]
    except Exception:
        return None

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
        before = payload.get("before", ZERO)
        # 路径过滤:纯 frontend/ 改动 → fetch+checkout repo(方便手动 scp dist),跳过后端 build+重启
        # before 全 0(首推/删分支)或 repo 还没 clone → fallback 照常部署
        if before != ZERO and os.path.isdir(os.path.join(REPO, ".git")):
            subprocess.run(["git", "-C", REPO, "fetch", "--all"],
                           stdout=open(LOG, "a"), stderr=subprocess.STDOUT, timeout=120)
            non_fe = changed_outside_frontend(before, after)
            if non_fe is not None and not non_fe:
                subprocess.run(["git", "-C", REPO, "checkout", after],
                               stdout=open(LOG, "a"), stderr=subprocess.STDOUT, timeout=60)
                log(f"→ 仅 frontend/ 改动 {before[:7]}..{after[:7]},跳过后端部署(前端走手动 scp)")
                self._send(200, b"frontend-only, skipped backend deploy " + after.encode())
                return
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
