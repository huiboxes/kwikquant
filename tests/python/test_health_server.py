"""HealthServer 单元测试(Wave 8 §3.3/§3.7)。"""

from __future__ import annotations

import http.client
import socket

import pytest

from kwikquant_worker.health_server import HealthServer


def _find_free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def health_server():
    port = _find_free_port()
    server = HealthServer(port=port, status_provider=lambda: {"status": "ok", "strategyId": 42})
    server.start()
    yield server, port
    server.stop()


def test_health_endpoint_returns_200_with_json(health_server):
    _, port = health_server
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        conn.request("GET", "/health")
        resp = conn.getresponse()
        assert resp.status == 200
        assert resp.getheader("Content-Type") == "application/json"
        body = resp.read().decode()
        assert "ok" in body and "42" in body
    finally:
        conn.close()


def test_health_endpoint_404_for_other_paths(health_server):
    _, port = health_server
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    try:
        conn.request("GET", "/other")
        resp = conn.getresponse()
        resp.read()
        assert resp.status == 404
    finally:
        conn.close()


def test_health_server_stop_is_idempotent():
    port = _find_free_port()
    s = HealthServer(port=port)
    s.start()
    s.stop()
    s.stop()  # 应无抛异常
