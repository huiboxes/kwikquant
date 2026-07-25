"""BacktestEventLoop 单元测试(函数式 on_bar)。"""

from __future__ import annotations

import asyncio

from decimal import Decimal
from unittest.mock import MagicMock, call

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


def test_backtest_event_loop_reports_progress_throttled_small():
    """逐 bar 进度上报节流:3 根 bar < PROGRESS_REPORT_EVERY(200),只在末根(i=total-1)上报 1 次,
    call 含 task_id + processed=total。"""
    client = MagicMock()
    ctx = BacktestContext(client, task_id=7, symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    loop.run(lambda bar, ctx: None, ctx, _klines())
    assert client.trade.report_progress.call_count == 1
    assert client.trade.report_progress.call_args == call(7, 3, 3)


def test_backtest_event_loop_progress_throttle_large():
    """大循环节流:8760 bar → 8760//200=43 次倍数上报 + 1 次末根强制 = 44 次,
    末根 call 含 processed=total=8760。"""
    client = MagicMock()
    ctx = BacktestContext(client, task_id=9, symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    klines = [
        {"timestamp": str(i), "open": "1", "high": "1", "low": "1", "close": "1", "volume": "0"}
        for i in range(8760)
    ]
    loop.run(lambda bar, ctx: None, ctx, klines)
    assert client.trade.report_progress.call_count == 8760 // 200 + 1
    assert client.trade.report_progress.call_args == call(9, 8760, 8760)


def test_backtest_event_loop_progress_failure_does_not_break_backtest():
    """进度上报失败容错:client.trade.report_progress 抛异常,ctx.report_progress try/except 吞掉,
    回测继续跑完不中断(用户核心诉求:进度展示降级不能阻断回测)。"""
    client = MagicMock()
    client.trade.report_progress.side_effect = RuntimeError("network down")
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(lambda bar, ctx: None, ctx, _klines())
    assert len(section8["equity_curve"]) == 3


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


def test_runner_event_loop_bar_close_detection():
    """bar 关闭检测:首根只缓存;同 openTime 更新不触发;openTime 变化=前一根关闭→on_bar(前一根)+set_bar。"""
    loop = RunnerEventLoop()
    bars = []
    ctx = MagicMock()
    loop._on_bar = lambda bar, c: bars.append(bar.timestamp)
    loop._ctx = ctx
    loop._current_bar = None
    # 首根 T1:缓存,不触发
    asyncio.run(loop._on_kline({"openTime": "T1", "open": "1", "high": "2", "low": "0", "close": "1", "volume": "10"}))
    assert bars == []
    # 同 openTime 更新(尾根替换):覆盖最终值,不触发
    asyncio.run(loop._on_kline({"openTime": "T1", "open": "1", "high": "3", "low": "0", "close": "1", "volume": "20"}))
    assert bars == []
    # 新 openTime T2:T1 关闭 → on_bar(T1) + set_bar(T1)
    asyncio.run(loop._on_kline({"openTime": "T2", "open": "1", "high": "2", "low": "0", "close": "2", "volume": "5"}))
    assert bars == ["T1"]
    ctx.set_bar.assert_called_once()
    assert ctx.set_bar.call_args[0][0].timestamp == "T1"


def test_runner_event_loop_on_bar_exception_does_not_break():
    """on_bar 抛异常 → stderr 记录,继续(下一根仍可触发,同回测容错)。"""
    loop = RunnerEventLoop()
    ctx = MagicMock()

    def _raising(bar, c):
        raise RuntimeError("boom")

    loop._on_bar = _raising
    loop._ctx = ctx
    loop._current_bar = None
    asyncio.run(loop._on_kline({"openTime": "T1", "open": "1", "high": "2", "low": "0", "close": "1", "volume": "10"}))
    asyncio.run(loop._on_kline({"openTime": "T2", "open": "1", "high": "2", "low": "0", "close": "2", "volume": "5"}))
    asyncio.run(loop._on_kline({"openTime": "T3", "open": "1", "high": "2", "low": "0", "close": "3", "volume": "5"}))
    # T1/T2 关闭 → set_bar 调 2 次(on_bar 抛但被吞,循环继续)
    assert ctx.set_bar.call_count == 2


def test_runner_event_loop_bar_out_of_order_ignored():
    """openTime 倒退(网络重连返旧 candle)→ 忽略,不触发 on_bar 不覆盖 current(防误触发)。"""
    loop = RunnerEventLoop()
    bars = []
    ctx = MagicMock()
    loop._on_bar = lambda bar, c: bars.append(bar.timestamp)
    loop._ctx = ctx
    loop._current_bar = None
    asyncio.run(loop._on_kline({"openTime": "T2", "open": "1", "high": "2", "low": "0", "close": "2", "volume": "5"}))
    asyncio.run(loop._on_kline({"openTime": "T3", "open": "1", "high": "2", "low": "0", "close": "3", "volume": "5"}))
    # T2 关闭 → on_bar(T2)
    assert bars == ["T2"]
    # 倒退 T1(旧 candle)→ 忽略
    asyncio.run(loop._on_kline({"openTime": "T1", "open": "1", "high": "2", "low": "0", "close": "1", "volume": "10"}))
    assert bars == ["T2"]  # 不触发
    assert loop._current_bar.timestamp == "T3"  # current 不被旧 candle 覆盖


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
