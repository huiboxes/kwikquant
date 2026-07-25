"""RunnerContext 单元测试(实盘/模拟盘 ctx)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

from kwikquant_worker.runner_context import RunnerContext
from kwikquant_worker.strategy import Bar


def test_place_order_calls_submit_without_exchange_account_id():
    """worker 模式不传 exchange_account_id(OrderController 据 token 推导 account)+ marketType 透传。"""
    client = MagicMock()
    client.trade.submit.return_value = {
        "orderId": 10,
        "filledQty": "0.5",
        "filledAvgPrice": "3000",
        "side": "BUY",
        "symbol": "BTC/USDT",
    }
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    f = ctx.place_order(side="BUY", order_type="LIMIT", amount="0.5", price="3000")
    assert client.trade.submit.call_count == 1
    kw = client.trade.submit.call_args.kwargs
    assert kw["symbol"] == "BTC/USDT"
    assert kw["market_type"] == "SPOT"
    assert kw["side"] == "BUY"
    assert kw.get("exchange_account_id") is None  # worker 不传,后端推导
    # 返 Fill 兼容(qty/price 从 OrderSubmitResult 提取)
    assert f is not None
    assert f.qty == Decimal("0.5")
    assert f.price == Decimal("3000")


def test_place_order_failure_returns_none():
    """下单失败(网络/被拒)返 None,不中断 runner(记 stderr)。"""
    client = MagicMock()
    client.trade.submit.side_effect = RuntimeError("network down")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    f = ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
    assert f is None


def test_position_queries_positions_with_symbol_filter():
    """position 调 positions(symbol=) 不传 exchange_account_id(worker token 推导)。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "ETH/USDT", "qty": "1.5"},
        {"symbol": "BTC/USDT", "qty": "0.5", "avgPrice": "3000"},
    ]
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    pos = ctx.position("BTC/USDT")
    assert client.trade.positions.call_args.kwargs.get("symbol") == "BTC/USDT"
    assert client.trade.positions.call_args.kwargs.get("exchange_account_id") is None
    assert pos.qty == Decimal("0.5")
    assert pos.avg_price == Decimal("3000")


def test_position_returns_empty_when_not_found_or_failed():
    client = MagicMock()
    client.trade.positions.return_value = []
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    assert ctx.position("BTC/USDT").qty == Decimal(0)
    # 查询失败也返空 Position,不抛
    client.trade.positions.side_effect = RuntimeError("boom")
    assert ctx.position("BTC/USDT").qty == Decimal(0)


def test_history_slices_bars_set_via_set_bar():
    """history 切片内存 _bars(set_bar 填),含当前 bar(与回测 BacktestContext.history 语义一致)。"""
    ctx = RunnerContext(MagicMock(), 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    assert ctx.history("close", 3) == []  # warmup 空
    ctx.set_bar(Bar("T1", 1, 2, 0, 10, 5))
    ctx.set_bar(Bar("T2", 11, 12, 10, 20, 6))
    assert ctx.history("close", 2) == [10.0, 20.0]
    assert ctx.history("close", 1) == [20.0]  # 含当前
