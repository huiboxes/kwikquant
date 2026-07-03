"""Lightweight /health HTTP server (Wave 8 §3.3/§3.7)。

Java WorkerOrchestratorService.healthCheckAll(@Scheduled 30s)通过 HTTP GET
``http://<container>:8081/health`` 探活;Wave 6 用 docker inspect 代理,Wave 8 转
真实 HTTP 端点(§3.7 healthCheck HTTP)。

使用 stdlib http.server(无外部依赖,Docker 镜像不额外装包)。
"""

from __future__ import annotations

import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

log = logging.getLogger(__name__)

DEFAULT_HEALTH_PORT = 8081


def make_handler(status_provider):
    """构造 handler 类。status_provider() 返回 dict,注入到 /health 响应。"""

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 — BaseHTTPRequestHandler API
            if self.path == "/health":
                body = json.dumps(status_provider()).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 — override
            log.debug("[health] " + format, *args)

    return _Handler


class HealthServer:
    """/health HTTP 服务(后台线程)。启动后 Java 健康探测生效。"""

    def __init__(self, port: int = DEFAULT_HEALTH_PORT, status_provider=None) -> None:
        self.port = port
        self.status_provider = status_provider or (lambda: {"status": "ok"})
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        """启动后台 daemon 线程,不阻塞主 event_loop。"""
        handler_cls = make_handler(self.status_provider)
        self._server = ThreadingHTTPServer(("0.0.0.0", self.port), handler_cls)  # noqa: S104 — 容器内网
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True, name="health-server")
        self._thread.start()
        log.info("[health] serving on :%d", self.port)

    def stop(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=2)
            self._thread = None
