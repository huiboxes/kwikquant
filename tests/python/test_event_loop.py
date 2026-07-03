"""BacktestEventLoop 单元测试(Wave 8 §3.3/§3.5)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant.errors import KqBacktestOrderRejected, KqBacktestTaskNotRunning
from kwikquant_worker.event_loop import BacktestEventLoop, RunnerEventLoop
from kwikquant_worker.strategy import BacktestContext, Strategy


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
    ctx = BacktestContext(client, task_id=1)

    class BuyOnceStrategy(Strategy):
        def __init__(self, ctx):
            super().__init__(ctx=ctx, default_symbol="BTC/USDT")
            self._bought = False

        def on_bar(self, bar):
            if not self._bought:
                self.buy(amount=Decimal("0.1"))
                self._bought = True

    strat = BuyOnceStrategy(ctx)
    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(strat, _klines(), client)

    assert section8["symbol"] == "BTC/USDT"
    assert section8["timeframe"] == "1h"
    assert section8["period"]["start"] == "2024-01-01T00:00:00Z"
    assert section8["period"]["end"] == "2024-01-01T02:00:00Z"
    assert len(section8["trades"]) == 1
    tr = section8["trades"][0]
    assert tr["side"] == "buy" and tr["price"] == "100"
    assert len(section8["equity_curve"]) == 3
    # equity should be Decimal string, monotonically defined (cash + holdings*close)
    for pt in section8["equity_curve"]:
        Decimal(pt["equity"])  # parse-check


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
    ctx = BacktestContext(client, task_id=1)

    class BuyEveryBar(Strategy):
        def on_bar(self, bar):
            self.buy(amount=Decimal("0.1"))

    strat = BuyEveryBar(ctx=ctx, default_symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(strat, _klines(), client)
    # 第一 bar 抛 7302,后两个 bar 成交
    assert len(section8["trades"]) == 2


def test_backtest_event_loop_7303_bubbles_up():
    client = _client_matching_at_close()
    client.trade.submit_backtest.side_effect = KqBacktestTaskNotRunning(409, 7303, "not running")
    ctx = BacktestContext(client, task_id=1)

    class BuyStrategy(Strategy):
        def on_bar(self, bar):
            self.buy(amount=Decimal("0.1"))

    strat = BuyStrategy(ctx=ctx, default_symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    with pytest.raises(KqBacktestTaskNotRunning):
        loop.run(strat, _klines(), client)


def test_backtest_event_loop_strategy_generic_exception_continues():
    """策略 on_bar 抛通用异常 → stderr + 下一 bar 继续。"""
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1)
    calls = []

    class OopsStrategy(Strategy):
        def on_bar(self, bar):
            calls.append(bar.timestamp)
            if bar.timestamp == "2024-01-01T00:00:00Z":
                raise RuntimeError("bug")
            self.buy(amount=Decimal("0.1"))

    strat = OopsStrategy(ctx=ctx, default_symbol="BTC/USDT")
    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(strat, _klines(), client)
    assert len(calls) == 3
    # 第一 bar 抛异常没下单;后两 bar 各买一单
    assert len(section8["trades"]) == 2


def test_backtest_event_loop_requires_backtest_context():
    class DummyStrategy(Strategy):
        pass

    class BadCtx:
        pass

    strat = Strategy(ctx=BadCtx(), default_symbol="X")  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        BacktestEventLoop().run(strat, _klines(), MagicMock())


def test_runner_event_loop_not_implemented_yet():
    with pytest.raises(NotImplementedError):
        RunnerEventLoop().run(MagicMock(), MagicMock(), MagicMock())


def test_backtest_event_loop_no_trades_produces_flat_equity():
    client = _client_matching_at_close()
    ctx = BacktestContext(client, task_id=1)
    strat = Strategy(ctx=ctx, default_symbol="BTC/USDT")  # 默认 on_bar 空
    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(strat, _klines(), client)
    assert section8["trades"] == []
    equities = [Decimal(pt["equity"]) for pt in section8["equity_curve"]]
    assert equities == [Decimal("10000")] * 3
