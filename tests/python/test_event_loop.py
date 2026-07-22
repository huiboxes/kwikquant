"""BacktestEventLoop 单元测试(函数式 on_bar)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant.errors import KqBacktestOrderRejected, KqBacktestTaskNotRunning
from kwikquant_worker.event_loop import BacktestEventLoop, RunnerEventLoop
from kwikquant_worker.strategy import BacktestContext


def _klines():
    return [
        {"timestamp": "2024-01-01T00:00:00Z", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"},
        {"timestamp": "2024-01-01T01:00:00Z", "open": "100", "high": "105", "low": "100", "close": "104", "volume": "12"},
        {"timestamp": "2024-01-01T02:00:00Z", "open": "104", "high": "106", "low": "103", "close": "106", "volume": "15"},
    ]


def _client_matching_at_close():
    """返回 Fill 使用当前 snapshot.close 价格。"""
    client = MagicMock()

    def _submit(task_id, *, symbol, side, order_type, amount, price, snapshot, market_type=None, exchange=None):
        return {
            "orderId": 1,
            "symbol": symbol,
            "side": side,
            "price": str(snapshot["close"]),
            "qty": str(amount),
            "fee": "0",
            "feeCurrency": "USDT",
            "filledAt": snapshot["timestamp"],
        }

    client.trade.submit_backtest.side_effect = _submit
    return client


def test_backtest_event_loop_produces_section8_shape():
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    state = {"bought": False}

    def on_bar(bar, ctx):
        if not state["bought"]:
            ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))
            state["bought"] = True

    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())

    assert section8["symbol"] == "BTC/USDT"
    assert section8["timeframe"] == "1h"
    assert section8["period"]["start"] == "2024-01-01T00:00:00Z"
    assert section8["period"]["end"] == "2024-01-01T02:00:00Z"
    assert len(section8["trades"]) == 1
    tr = section8["trades"][0]
    assert tr["side"] == "buy" and tr["price"] == "100"
    assert len(section8["equity_curve"]) == 3
    for pt in section8["equity_curve"]:
        Decimal(pt["equity"])


def test_backtest_event_loop_ignores_7302_and_continues():
    """账本不足(7302)非致命,策略下一 bar 继续 buy。"""
    client = _client_matching_at_close()
    client.trade.submit_backtest.side_effect = [
        KqBacktestOrderRejected(400, 7302, "ledger insufficient"),
        {"orderId": 2, "symbol": "BTC/USDT", "side": "BUY", "price": "104", "qty": "0.1",
         "fee": "0", "feeCurrency": "USDT", "filledAt": ""},
        {"orderId": 3, "symbol": "BTC/USDT", "side": "BUY", "price": "106", "qty": "0.1",
         "fee": "0", "feeCurrency": "USDT", "filledAt": ""},
    ]
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert len(section8["trades"]) == 2


def test_backtest_event_loop_7303_bubbles_up():
    client = _client_matching_at_close()
    client.trade.submit_backtest.side_effect = KqBacktestTaskNotRunning(409, 7303, "not running")
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    with pytest.raises(KqBacktestTaskNotRunning):
        loop.run(on_bar, ctx, _klines())


def test_backtest_event_loop_strategy_generic_exception_continues():
    """策略 on_bar 抛通用异常 → stderr + 下一 bar 继续。"""
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    calls = []

    def on_bar(bar, ctx):
        calls.append(bar.timestamp)
        if bar.timestamp == "2024-01-01T00:00:00Z":
            raise RuntimeError("bug")
        ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert len(calls) == 3
    assert len(section8["trades"]) == 2


def test_backtest_event_loop_requires_backtest_context():
    class BadCtx:
        pass

    ctx = BadCtx()

    def on_bar(bar, ctx):
        pass

    with pytest.raises(TypeError):
        BacktestEventLoop().run(on_bar, ctx, _klines())  # type: ignore[arg-type]


def test_runner_event_loop_not_implemented_yet():
    with pytest.raises(NotImplementedError):
        RunnerEventLoop().run(lambda bar, ctx: None, MagicMock(), MagicMock())


def test_backtest_event_loop_no_trades_produces_flat_equity():
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        pass  # 空策略,不下单

    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert section8["trades"] == []
    equities = [Decimal(pt["equity"]) for pt in section8["equity_curve"]]
    assert equities == [Decimal("10000")] * 3


def test_backtest_event_loop_exposes_history_to_on_bar():
    """ctx.history 切片内存 klines,含当前 bar。"""
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    seen = []

    def on_bar(bar, ctx):
        seen.append(ctx.history("close", 2))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    loop.run(on_bar, ctx, _klines())
    assert seen == [[100.0], [100.0, 104.0], [104.0, 106.0]]


def test_golden_cross_template_produces_trades():
    """金叉死叉模板策略(用户 DB strategy_codes id=1 同款)+ 构造 MA 交叉数据 → 应出买卖。

    验证函数式重构后:模板的 ctx.history/place_order/position/log/symbol API 全跑通,
    on_bar(bar, ctx) 顶层函数被 event_loop 正确驱动,MA 交叉能触发下单(非 0 信号)。
    """
    def on_bar(bar, ctx):
        closes = ctx.history("close", 20)
        if len(closes) < 20:
            return
        fast = sum(closes[-5:]) / 5
        slow = sum(closes[-20:]) / 20
        pos = ctx.position(ctx.symbol)
        if fast > slow and pos.qty <= 0:
            ctx.place_order(side="BUY", order_type="MARKET", amount=0.01)
            ctx.log(f"金叉做多 fast={fast:.2f} slow={slow:.2f}")
        elif fast < slow and pos.qty > 0:
            ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
            ctx.log(f"死叉平仓 fast={fast:.2f} slow={slow:.2f}")

    client = MagicMock()

    def _submit(task_id, *, symbol, side, order_type, amount, price, snapshot, market_type=None, exchange=None):
        return {"orderId": 1, "symbol": symbol, "side": side,
                "price": str(snapshot["close"]), "qty": str(amount),
                "fee": "0", "feeCurrency": "USDT", "filledAt": snapshot["timestamp"]}

    client.trade.submit_backtest.side_effect = _submit

    # 20×100(warmup)→ 5×110(金叉)→ 10×90(死叉)
    klines = []
    for i in range(20):
        klines.append({"timestamp": f"t{i}", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"})
    for i in range(5):
        klines.append({"timestamp": f"t{20 + i}", "open": "110", "high": "111", "low": "109", "close": "110", "volume": "10"})
    for i in range(10):
        klines.append({"timestamp": f"t{25 + i}", "open": "90", "high": "91", "low": "89", "close": "90", "volume": "10"})

    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, klines)

    assert len(section8["trades"]) >= 2, f"金叉死叉应出交易,实际 {len(section8['trades'])}: {section8['trades']}"
    sides = [t["side"] for t in section8["trades"]]
    assert "buy" in sides and "sell" in sides
