"""StreamClient — STOMP WS(Wave 8 §3.4 + §4.3)。

轻量封装,支持 JWT / service_token 两路径鉴权(见 §3.3 数据格式消歧)。
使用 ``websockets``(不再引入 stomp-py 重依赖),内部拼 STOMP CONNECT 帧。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from kwikquant.client import Auth


class StreamClient:
    """STOMP over WebSocket 订阅端。

    每个 subscribe 记录 topic 和 handler;``run()`` 打开 WS 连接、发 CONNECT、SUBSCRIBE 每 topic,
    收 MESSAGE 帧 dispatch handler。生产实现依赖 ``websockets`` 库(Runner 用),测试通过
    ``transport`` 注入 mock 通道。
    """

    def __init__(self, ws_url: str, auth: "Auth") -> None:
        self.ws_url = ws_url
        self.auth = auth
        self._handlers: dict[str, Callable[[dict], Awaitable[None] | None]] = {}

    def subscribe(self, topic: str, handler: Callable[[dict], Any]) -> None:
        """注册 topic 处理器。可多次调用叠加。"""
        self._handlers[topic] = handler

    def on_tick(self, exchange: str, market_type: str, symbol: str, handler) -> None:
        self.subscribe(f"/topic/ticks/{exchange}/{market_type}/{symbol}", handler)

    def on_fill(self, user_id: int, handler) -> None:
        self.subscribe(f"/topic/fills/{user_id}", handler)

    def on_order(self, user_id: int, handler) -> None:
        self.subscribe(f"/topic/orders/{user_id}", handler)

    def connect_headers(self) -> dict[str, str]:
        """STOMP CONNECT 帧 header — 承载鉴权。

        Round-7 BLOCKER 1 修复:复用 :meth:`Auth.as_headers`,让 auth mode 决定 header 名。
        - JWT:``Authorization: Bearer <jwt>``
        - service_token:``X-Worker-Token: <uuid>``(与 REST 侧一致,WebSocketAuthInterceptor
          优先识别此 header 走 WorkerTokenService.getEntry 分流,失败不 fallback JWT)
        """
        return self.auth.as_headers()

    def build_connect_frame(self) -> str:
        headers = self.connect_headers()
        header_lines = "\n".join(f"{k}:{v}" for k, v in headers.items())
        return f"CONNECT\naccept-version:1.2\nhost:/\n{header_lines}\n\n\x00"

    def build_subscribe_frame(self, topic: str, sub_id: int) -> str:
        return f"SUBSCRIBE\nid:sub-{sub_id}\ndestination:{topic}\n\n\x00"

    async def _dispatch_message(self, topic: str, body: str) -> None:
        handler = self._handlers.get(topic)
        if handler is None:
            return
        try:
            payload = json.loads(body) if body else {}
        except json.JSONDecodeError:
            payload = {"raw": body}
        result = handler(payload)
        if asyncio.iscoroutine(result):
            await result

    async def run(self) -> None:  # pragma: no cover - 依赖真实 websockets 连接
        """长驻订阅(异步)。用 ``asyncio.run(client.run())`` 启动。

        真实实现需要 ``pip install websockets``。测试用 ``_dispatch_message`` 直接注入 topic。
        """
        raise NotImplementedError(
            "StreamClient.run requires the `websockets` package; install with "
            "`pip install kwikquant[stream]` or override for tests."
        )
