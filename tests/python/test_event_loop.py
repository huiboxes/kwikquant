"""BacktestEventLoop 单元测试(函数式 on_bar;撮合本地化,无 HTTP mock)。"""

from __future__ import annotations

import asyncio

from decimal import Decimal
from unittest.mock import MagicMock, call

import pytest

from kwikquant_worker.event_loop import BacktestEventLoop, RunnerEventLoop
from kwikquant_worker.health_signals import HealthSignals
from kwikquant_worker.strategy import BacktestContext


def _klines():
    return [
        {"timestamp": "2024-01-01T00:00:00Z", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"},
        {"timestamp": "2024-01-01T01:00:00Z", "open": "100", "high": "105", "low": "100", "close": "104", "volume": "12"},
        {"timestamp": "2024-01-01T02:00:00Z", "open": "104", "high": "106", "low": "103", "close": "106", "volume": "15"},
    ]


def test_backtest_event_loop_produces_section8_shape():
    """MARKET BUY 在下一 bar 本地撮合:fill price = close × (1+5bps) = 104×1.0005 = 104.052。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
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
    assert tr["side"] == "buy"
    assert Decimal(tr["price"]) == Decimal("104") * Decimal("1.00050000")
    assert tr["time"] == "2024-01-01T01:00:00Z"
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


def test_backtest_event_loop_insufficient_cash_rejects_and_continues():
    """账本闸门(原 7302 语义本地化):现金不足的 BUY 拒单记 warning,回测继续。
    初始资金 5 → 0.1@~104 成本 >5 全拒;资金充足后(此用例不出现)才成交。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))

    loop = BacktestEventLoop(initial_capital=Decimal("5"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert section8["trades"] == []
    assert any("insufficient cash" in w for w in section8["warnings"])


def test_backtest_event_loop_insufficient_inventory_rejects_sell():
    """SELL 无持仓 → 拒单 warning(不产生负持仓)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        ctx.place_order(side="SELL", order_type="MARKET", amount=Decimal("0.1"))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert section8["trades"] == []
    assert any("insufficient inventory" in w for w in section8["warnings"])
    assert ctx.position("BTC/USDT").qty == Decimal(0)


def test_backtest_event_loop_limit_not_crossed_warns():
    """LIMIT 未穿越 → 无成交 + warning(原 place_order returned None 语义)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        if bar.timestamp == "2024-01-01T00:00:00Z":
            ctx.place_order(side="BUY", order_type="LIMIT", amount="0.1", price="50")  # low 99/100/103 均 >50

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert section8["trades"] == []
    assert any("place_order returned None" in w for w in section8["warnings"])


def test_backtest_event_loop_strategy_generic_exception_fails_closed():
    """策略 on_bar 抛通用异常 → 立即失败，不能继续生成看似成功的报告。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    calls = []

    def on_bar(bar, ctx):
        calls.append(bar.timestamp)
        if bar.timestamp == "2024-01-01T00:00:00Z":
            raise RuntimeError("bug")
        ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    with pytest.raises(RuntimeError, match="strategy on_bar failed.*RuntimeError\\('bug'\\)"):
        loop.run(on_bar, ctx, _klines())
    assert calls == ["2024-01-01T00:00:00Z"]


def test_backtest_event_loop_never_matches_order_on_signal_bar():
    """NEXT_BAR:信号 bar(00:00)下的单,用下一 bar(01:00)快照撮合,绝不在信号 bar 成交。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        if bar.timestamp == "2024-01-01T00:00:00Z":
            ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")

    section8 = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h").run(on_bar, ctx, _klines())

    tr = section8["trades"][0]
    assert tr["time"] == "2024-01-01T01:00:00Z"
    # 成交价 = bar1 close 104 × (1+5bps)
    assert Decimal(tr["price"]) == Decimal("104") * Decimal("1.00050000")


def test_backtest_event_loop_persists_reproducibility_and_terminal_order_warning():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    loop = BacktestEventLoop(
        symbol="BTC/USDT",
        timeframe="1h",
        params={"fast": 5},
        reproducibility={"strategyCodeHash": "sha256:abc", "execution": {"orderFillTiming": "NEXT_BAR"}},
    )

    def on_bar(bar, ctx):
        if bar.timestamp == "2024-01-01T02:00:00Z":
            ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")

    section8 = loop.run(on_bar, ctx, _klines())

    assert section8["params"]["fast"] == 5
    snapshot = section8["params"]["_kwikquant"]
    assert snapshot["strategyCodeHash"] == "sha256:abc"
    assert snapshot["execution"]["orderFillTiming"] == "NEXT_BAR"
    assert snapshot["warnings"] == ["1 order(s) placed on final bar were not executed"]


def test_backtest_event_loop_requires_backtest_context():
    class BadCtx:
        pass

    ctx = BadCtx()

    def on_bar(bar, ctx):
        pass

    with pytest.raises(TypeError):
        BacktestEventLoop().run(on_bar, ctx, _klines())  # type: ignore[arg-type]


def test_backtest_event_loop_matching_config_passthrough():
    """matching_config 传入引擎:零滑点配置 → 成交价 = close 原价(证明配置被消费)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    state = {"bought": False}

    def on_bar(bar, ctx):
        if not state["bought"]:
            ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
            state["bought"] = True

    loop = BacktestEventLoop(
        symbol="BTC/USDT", timeframe="1h", matching_config={"marketSlippageBps": "0"}
    )
    section8 = loop.run(on_bar, ctx, _klines())
    assert Decimal(section8["trades"][0]["price"]) == Decimal("104.00000000")


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


def test_runner_event_loop_on_bar_exception_degrades_health_then_recovers():
    signals = HealthSignals(1)
    loop = RunnerEventLoop(signals)
    ctx = MagicMock()
    outcomes = iter([RuntimeError("boom"), None])

    def on_bar(bar, c):
        outcome = next(outcomes)
        if outcome:
            raise outcome

    loop._on_bar = on_bar
    loop._ctx = ctx
    loop._invoke_on_bar(MagicMock(timestamp="T1"))
    assert signals.snapshot()["status"] == "degraded"

    loop._invoke_on_bar(MagicMock(timestamp="T2"))
    assert signals.snapshot()["status"] == "ok"


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
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")

    def on_bar(bar, ctx):
        pass  # 空策略,不下单

    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, _klines())
    assert section8["trades"] == []
    equities = [Decimal(pt["equity"]) for pt in section8["equity_curve"]]
    assert equities == [Decimal("10000")] * 3


def test_backtest_event_loop_exposes_history_to_on_bar():
    """ctx.history 切片内存 klines,含当前 bar。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    seen = []

    def on_bar(bar, ctx):
        seen.append(ctx.history("close", 2))

    loop = BacktestEventLoop(symbol="BTC/USDT", timeframe="1h")
    loop.run(on_bar, ctx, _klines())
    assert seen == [[100.0], [100.0, 104.0], [104.0, 106.0]]


def test_golden_cross_template_produces_trades():
    """金叉死叉模板策略(用户 DB strategy_codes id=1 同款)+ 构造 MA 交叉数据 → 应出买卖。

    撮合本地化后:模板的 ctx.history/place_order/position/log/symbol API 全跑通,
    on_bar(bar, ctx) 顶层函数被 event_loop 正确驱动,MA 交叉能触发下单(非 0 信号),
    本地引擎按 FAST(±5bps 滑点)成交。
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

    # 20×100(warmup)→ 5×110(金叉)→ 10×90(死叉)
    klines = []
    for i in range(20):
        klines.append({"timestamp": f"t{i}", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"})
    for i in range(5):
        klines.append({"timestamp": f"t{20 + i}", "open": "110", "high": "111", "low": "109", "close": "110", "volume": "10"})
    for i in range(10):
        klines.append({"timestamp": f"t{25 + i}", "open": "90", "high": "91", "low": "89", "close": "90", "volume": "10"})

    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    section8 = loop.run(on_bar, ctx, klines)

    assert len(section8["trades"]) >= 2, f"金叉死叉应出交易,实际 {len(section8['trades'])}: {section8['trades']}"
    sides = [t["side"] for t in section8["trades"]]
    assert "buy" in sides and "sell" in sides


def test_runner_event_loop_touch_ws_on_kline():
    """_on_kline 入口调 _touch_ws:任何 payload 到达后 signals.lastWsMsgAt 非 None(首根缓存前先标)。"""
    signals = HealthSignals(1)
    loop = RunnerEventLoop(health_signals=signals)
    loop._ctx = MagicMock()
    loop._current_bar = None
    assert signals.snapshot()["lastWsMsgAt"] is None
    asyncio.run(
        loop._on_kline({"openTime": "T1", "open": "1", "high": "2", "low": "0", "close": "1", "volume": "10"})
    )
    assert signals.snapshot()["lastWsMsgAt"] is not None
    assert signals.snapshot()["lastWsMsgAt"] > 0


def test_runner_event_loop_touch_bar_on_invoke():
    """bar 关闭驱动 _invoke_on_bar,finally 调 _touch_bar:signals.lastBarAt 非 None。"""
    signals = HealthSignals(1)
    loop = RunnerEventLoop(health_signals=signals)
    loop._on_bar = lambda bar, c: None
    loop._ctx = MagicMock()
    loop._current_bar = None
    assert signals.snapshot()["lastBarAt"] is None
    # 首根 T1 仅缓存(未关闭 → lastBarAt 仍 None)
    asyncio.run(
        loop._on_kline({"openTime": "T1", "open": "1", "high": "2", "low": "0", "close": "1", "volume": "10"})
    )
    assert signals.snapshot()["lastBarAt"] is None
    # T2 到达 → T1 关闭 → _invoke_on_bar(T1) finally touch_bar
    asyncio.run(
        loop._on_kline({"openTime": "T2", "open": "1", "high": "2", "low": "0", "close": "2", "volume": "5"})
    )
    assert signals.snapshot()["lastBarAt"] is not None
    assert signals.snapshot()["lastBarAt"] > 0


def test_bar_from_kline_maps_open_time_to_timestamp():
    """_bar_from_kline:Kline dict(openTime/open/high/low/close/volume)→ Bar。

    WS /topic/kline payload 与 REST /api/v1/market/klines Kline record 同键(openTime),共用此映射——
    runner 历史 bar 预填(``worker_server._prefill_history``)与 WS 实时 bar(``_on_kline``)经同一函数
    构造,保证 WS 与预填 bar 同型(衔接不靠 timestamp 比较,但 OHLCV 值口径一致)。
    """
    from kwikquant_worker.event_loop import _bar_from_kline

    bar = _bar_from_kline(
        {"openTime": "2024-01-01T00:00:00Z", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"}
    )
    assert bar.timestamp == "2024-01-01T00:00:00Z"
    assert bar.open == 100.0 and bar.high == 101.0
    assert bar.low == 99.0 and bar.close == 100.0
    assert bar.volume == 10.0


def test_bar_from_kline_ignores_extra_fields_and_defaults_missing():
    """REST Kline record 含 exchange/marketType/symbol/interval 多余字段,忽略;缺字段默认 0(不抛)。"""
    from kwikquant_worker.event_loop import _bar_from_kline

    bar = _bar_from_kline(
        {
            "exchange": "OKX", "marketType": "SPOT", "symbol": "BTC/USDT", "interval": "1h",
            "openTime": "T", "open": "5", "high": "6", "low": "4", "close": "5", "volume": "1",
        }
    )
    assert bar.timestamp == "T"
    assert bar.open == 5.0
    # 缺 volume → 默认 0(不抛)
    bar2 = _bar_from_kline({"openTime": "T2", "open": "1", "high": "1", "low": "1", "close": "1"})
    assert bar2.volume == 0.0
