"""Shared pytest fixtures — Python SDK & worker runtime tests (Wave 8)。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# 允许 tests/ 直接 import 项目根的 kwikquant / kwikquant_worker
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import httpx
import pytest


class _MockTransport(httpx.MockTransport):
    """httpx MockTransport 便捷封装。响应路由由 dict[(method, path)] → (status, code, message, data)。"""


def _envelope(data=None, code: int = 0, message: str = "ok") -> bytes:
    return json.dumps({"code": code, "message": message, "data": data}).encode()


@pytest.fixture
def make_transport():
    """构造 (routes) -> MockTransport。

    routes: list of (method, path_substr, handler(request)) — handler 返回 httpx.Response。
    """

    def _factory(routes):
        def _dispatch(request: httpx.Request) -> httpx.Response:
            for method, path_sub, handler in routes:
                if request.method == method and path_sub in str(request.url):
                    return handler(request)
            return httpx.Response(404, content=_envelope(code=404, message="no route"))

        return _MockTransport(_dispatch)

    return _factory


@pytest.fixture
def envelope():
    return _envelope
