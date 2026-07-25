"""StreamClient — STOMP over WebSocket。

轻量封装,支持 JWT / service_token 两路径鉴权。
使用 ``websockets``(不再引入 stomp-py 重依赖),内部拼 STOMP CONNECT/SUBSCRIBE/MESSAGE 帧。
Runner 长驻用:订阅 /topic/kline/{ex}/{mt}/{sym}/{interval} → bar 关闭检测 → on_bar。
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from kwikquant.client import Auth

log = logging.getLogger(__name__)


class StreamClient:
    """STOMP over WebSocket 订阅端。

    每个 subscribe 记录 topic 和 handler;``run()`` 打开 WS 连接、发 CONNECT、SUBSCRIBE 每 topic,
    收 MESSAGE 帧 dispatch handler。生产实现用 ``websockets`` 库(Runner 用),断线指数退避重连。
    测试通过 ``_dispatch_message`` 直接注入 topic(不经 run)。
    """

    def __init__(self, ws_url: str, auth: "Auth") -> None:
        self.ws_url = ws_url
        self.auth = auth
        self._handlers: dict[str, Callable[[dict], Awaitable[None] | None]] = {}

    def subscribe(self, topic: str, handler: Callable[[dict], Any]) -> None:
        """注册 topic 处理器。可多次调用叠加。"""
        self._handlers[topic] = handler

    def on_tick(self, exchange: str, market_type: str, symbol: str, handler) -> None:
        self.subscribe(f"/topic/ticker/{exchange}/{market_type}/{symbol.replace('/', '-')}", handler)

    def on_kline(self, exchange: str, market_type: str, symbol: str, interval: str, handler) -> None:
        """订阅 K 线 bar(后端 MarketDataService.KLINE_TOPIC_FORMAT,runner 主通道)。

        topic = /topic/kline/{exchange}/{marketType}/{symbol-with-dash}/{interval}。
        symbol 中的 ``/`` 替换为 ``-``(与后端 onKline destination 规则一致,见 docs/ws-contract.md)。
        handler 收 Kline payload({openTime, open, high, low, close, volume, ...})。
        """
        self.subscribe(
            f"/topic/kline/{exchange}/{market_type}/{symbol.replace('/', '-')}/{interval}",
            handler,
        )

    def on_fill(self, user_id: int, handler) -> None:
        self.subscribe(f"/topic/fills/{user_id}", handler)

    def on_order(self, user_id: int, handler) -> None:
        self.subscribe(f"/topic/orders/{user_id}", handler)

    def connect_headers(self) -> dict[str, str]:
        """STOMP CONNECT 帧 header — 承载鉴权(冗余:WS 握手 additional_headers 已带,STOMP 层后端不读)。

        复用 :meth:`Auth.as_headers`,让 auth mode 决定 header 名。
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

    async def _handle_frame(self, raw: Any) -> None:
        """解析 STOMP MESSAGE 帧 → dispatch handler。

        STOMP frame:``MESSAGE\\ndestination:...\\n...\\n\\nbody\\x00``。非 MESSAGE 帧(如 CONNECTED
        /RECEIPT/ERROR)忽略(连接/订阅确认帧,不触发业务)。
        """
        if not raw:
            return
        text = raw if isinstance(raw, str) else raw.decode("utf-8", "replace")
        if not text.startswith("MESSAGE"):
            return
        # STOMP \r\n 兼容:统一 rstrip 每行,防 \r 残留致 destination 匹配失败 + 空行(\r)误判为 header
        lines = [ln.rstrip("\r") for ln in text.split("\n")]
        headers: dict[str, str] = {}
        i = 1
        while i < len(lines) and lines[i] != "":
            if ":" in lines[i]:
                k, _, v = lines[i].partition(":")
                # STOMP \r\n 兼容:split("\n") 后行尾可能残留 \r,strip 防 destination 匹配失败
                headers[k] = v.rstrip("\r")
            i += 1
        body = "\n".join(lines[i + 1 :])
        if body.endswith("\x00"):
            body = body[:-1]
        dest = headers.get("destination")
        if dest:
            await self._dispatch_message(dest, body)

    async def run(self) -> None:  # pragma: no cover - 依赖真实 websockets + 后端 WS 端点
        """长驻订阅(异步)。``asyncio.run(client.run())`` 启动。

        连接 → CONNECT → 等 CONNECTED → 逐 topic SUBSCRIBE → async for MESSAGE 帧 dispatch。
        断线指数退避重连(1s/2s/4s.../30s 封顶)。SIGTERM(asyncio.CancelledError)退出不重连。
        """
        import websockets  # 镜像装(requirements-worker.txt websockets>=12.0)

        backoff = 1.0
        while True:
            try:
                async with websockets.connect(
                    self.ws_url, additional_headers=self.auth.as_headers()
                ) as ws:
                    await ws.send(self.build_connect_frame())
                    # 等 CONNECTED 帧(服务器确认 STOMP 握手);非 MESSAGE 帧 _handle_frame 忽略
                    await ws.recv()
                    for i, topic in enumerate(self._handlers):
                        await ws.send(self.build_subscribe_frame(topic, i))
                    async for raw in ws:
                        await self._handle_frame(raw)
                # 正常关闭(ws 退出 async for)— 重连
                backoff = 1.0
            except asyncio.CancelledError:
                # SIGTERM/停止:退出不重连
                raise
            except Exception as e:  # noqa: BLE001 — 任何断线都重连(网络抖动/服务器重启)
                print(f"[stream] disconnected: {e!r}, retry in {backoff}s", file=sys.stderr)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30.0)
