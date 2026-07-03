"""StreamClient — STOMP 帧 + 鉴权 header 单元测试(Wave 8 §3.4/§4.3)。"""

from __future__ import annotations

import asyncio

import pytest

from kwikquant.client import Auth
from kwikquant.stream import StreamClient


def test_connect_headers_service_token_uses_x_worker_token():
    # Round-7 BLOCKER 1 修复:service_token 走 X-Worker-Token,与 REST 侧一致
    s = StreamClient("ws://kw/ws", Auth.service_token("wt-42"))
    assert s.connect_headers() == {"X-Worker-Token": "wt-42"}


def test_connect_headers_jwt_uses_authorization_bearer():
    s = StreamClient("ws://kw/ws", Auth.jwt("j-1"))
    assert s.connect_headers() == {"Authorization": "Bearer j-1"}


def test_build_connect_frame_service_token_has_x_worker_token():
    s = StreamClient("ws://kw/ws", Auth.service_token("wt-x"))
    frame = s.build_connect_frame()
    assert frame.startswith("CONNECT\n")
    assert "accept-version:1.2" in frame
    assert "X-Worker-Token:wt-x" in frame
    assert frame.endswith("\x00")


def test_build_connect_frame_jwt_has_authorization_bearer():
    s = StreamClient("ws://kw/ws", Auth.jwt("j-2"))
    frame = s.build_connect_frame()
    assert "Authorization:Bearer j-2" in frame


def test_build_subscribe_frame():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    frame = s.build_subscribe_frame("/topic/fills/42", 3)
    assert "SUBSCRIBE\n" in frame
    assert "id:sub-3" in frame
    assert "destination:/topic/fills/42" in frame


def test_subscribe_registers_handler_and_dispatch_deserializes_json():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    received = []
    s.subscribe("/topic/orders/1", lambda p: received.append(p))
    asyncio.run(s._dispatch_message("/topic/orders/1", '{"orderId": 9}'))
    assert received == [{"orderId": 9}]


def test_dispatch_message_no_handler_is_noop():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    # 未注册 topic 静默丢弃
    asyncio.run(s._dispatch_message("/topic/unknown", '{"x": 1}'))


def test_dispatch_message_non_json_body_wraps_as_raw():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    received: list = []
    s.subscribe("/topic/x", lambda p: received.append(p))
    asyncio.run(s._dispatch_message("/topic/x", "not-json"))
    assert received == [{"raw": "not-json"}]


def test_on_tick_on_fill_on_order_registers_topic_paths():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    s.on_tick("BINANCE", "SPOT", "BTC-USDT", lambda p: None)
    s.on_fill(42, lambda p: None)
    s.on_order(42, lambda p: None)
    assert "/topic/ticks/BINANCE/SPOT/BTC-USDT" in s._handlers
    assert "/topic/fills/42" in s._handlers
    assert "/topic/orders/42" in s._handlers


def test_run_raises_not_implemented_without_websockets():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    with pytest.raises(NotImplementedError):
        asyncio.run(s.run())


def test_async_handler_is_awaited():
    s = StreamClient("ws://kw/ws", Auth.jwt("t"))
    received: list = []

    async def _h(p):
        received.append(p)

    s.subscribe("/topic/a", _h)
    asyncio.run(s._dispatch_message("/topic/a", '{"k": "v"}'))
    assert received == [{"k": "v"}]
