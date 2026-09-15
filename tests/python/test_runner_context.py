"""RunnerContext 单元测试(实盘/模拟盘 ctx)。

契约断言以**真实后端形状**为准:POST /api/v1/orders 响应 = OrderSubmitResult
{orderId, status, version, createdAt, filledQty, filledAvgPrice}(金额为 decimal string,
filledQty 是提交时点值——PAPER 撮合异步,NEW 单为 "0";filledAvgPrice 无成交为 null)。
旧测试 mock 过不存在的 filledQty/filledAvgPrice 响应形状,把假契约固化进了测试,此处一并修正。
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant.errors import KqApiError
from kwikquant_worker.context import OrderAck, StrategyContext
from kwikquant_worker.health_signals import HealthSignals
from kwikquant_worker.runner_context import RunnerContext
from kwikquant_worker.strategy import Bar


def _submit_resp(order_id=10, status="NEW", filled_qty="0", filled_avg_price=None):
    """OrderSubmitResult 真实形状(G4 后含提交时点成交字段,decimal string)。"""
    return {
        "orderId": order_id,
        "status": status,
        "version": 1,
        "createdAt": "2026-09-08T00:00:00Z",
        "filledQty": filled_qty,
        "filledAvgPrice": filled_avg_price,
    }


def _ctx(market_type="SPOT", symbol="BTC/USDT", client=None, **kw):
    return RunnerContext(
        client or MagicMock(), 1, exchange="OKX", market_type=market_type, symbol=symbol, **kw
    )


def test_predicted_funding_rate_perp_reads_view():
    """PERP:GET /market/funding-rate 透传 exchange/marketType/symbol,fundingRate decimal string 直读。"""
    client = MagicMock()
    client.data.funding_rate.return_value = {"fundingRate": "0.00012500", "markPrice": "63000"}
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client)
    assert ctx.predicted_funding_rate() == Decimal("0.00012500")
    kw = client.data.funding_rate.call_args.kwargs
    assert kw == {"exchange": "OKX", "market_type": "PERP", "symbol": "BTC/USDT:USDT"}


def test_predicted_funding_rate_swallows_failure_and_spot_returns_none():
    """交易所无预估 / 查询失败 / SPOT → None(不造值,不中断 runner)。"""
    client = MagicMock()
    client.data.funding_rate.side_effect = RuntimeError("exchange down")
    assert _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).predicted_funding_rate() is None
    spot = MagicMock()
    assert _ctx(market_type="SPOT", client=spot).predicted_funding_rate() is None
    assert spot.data.funding_rate.call_count == 0  # SPOT 不打请求


def test_place_order_spot_calls_submit_and_returns_ack():
    """worker 模式不传 exchange_account_id(后端据 token 推导)+ marketType 透传;返 OrderAck。"""
    client = MagicMock()
    client.trade.submit.return_value = _submit_resp()
    ctx = _ctx(client=client)
    ack = ctx.place_order(side="BUY", order_type="LIMIT", amount="0.5", price="3000")
    assert client.trade.submit.call_count == 1
    kw = client.trade.submit.call_args.kwargs
    assert kw["symbol"] == "BTC/USDT"
    assert kw["market_type"] == "SPOT"
    assert kw["side"] == "BUY"
    assert kw["amount"] == Decimal("0.5")
    assert kw["price"] == Decimal("3000")
    assert kw.get("exchange_account_id") is None  # worker 不传,后端推导
    # 受理成功;filledQty 是提交时点值(PAPER NEW 单 "0",成交异步——不以它判成交)
    assert ack.accepted is True
    assert ack.reason is None
    assert ack.filled_qty == Decimal("0")
    assert ack.filled_price is None


def test_place_order_idempotent_replay_carries_real_fills():
    """幂等 replay 命中已成交单:filledQty/filledAvgPrice 是订单当前真实累计值(decimal string 直读)。"""
    client = MagicMock()
    client.trade.submit.return_value = _submit_resp(status="FILLED", filled_qty="0.25000000", filled_avg_price="42150.50000000")
    ack = _ctx(client=client).place_order(side="BUY", order_type="MARKET", amount="0.25")
    assert ack.accepted is True
    assert ack.filled_qty == Decimal("0.25000000")
    assert ack.filled_price == Decimal("42150.50000000")


def test_place_order_api_error_returns_not_accepted_with_reason():
    """业务拒单(KqApiError,如 4102 余额不足)→ accepted=False + "code: message",不抛不中断 runner。"""
    client = MagicMock()
    client.trade.submit.side_effect = KqApiError(422, 4102, "insufficient balance")
    signals = HealthSignals(1)
    ctx = _ctx(client=client, health_signals=signals)
    ack = ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
    assert ack.accepted is False
    assert ack.reason == "4102: insufficient balance"
    assert signals.snapshot()["consecutiveOrderFailures"] == 1


def test_place_order_network_failure_returns_not_accepted():
    client = MagicMock()
    client.trade.submit.side_effect = RuntimeError("network down")
    ctx = _ctx(client=client)
    ack = ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
    assert ack.accepted is False
    assert "network down" in (ack.reason or "")


def test_place_order_missing_order_id_not_accepted():
    client = MagicMock()
    client.trade.submit.return_value = {"orderId": None}
    signals = HealthSignals(1)
    ctx = _ctx(client=client, health_signals=signals)
    ack = ctx.place_order(side="BUY", order_type="LIMIT", amount="0.1", price="3000")
    assert ack.accepted is False
    assert "orderId" in (ack.reason or "")
    assert signals.snapshot()["consecutiveOrderFailures"] == 1


def test_place_order_success_resets_consecutive_failures():
    client = MagicMock()
    signals = HealthSignals(1)
    ctx = _ctx(client=client, health_signals=signals)
    client.trade.submit.side_effect = RuntimeError("boom")
    assert ctx.place_order(side="BUY", order_type="MARKET", amount="0.1").accepted is False
    assert signals.snapshot()["consecutiveOrderFailures"] == 1
    client.trade.submit.side_effect = None
    client.trade.submit.return_value = _submit_resp()
    assert ctx.place_order(side="BUY", order_type="MARKET", amount="0.1").accepted is True
    assert signals.snapshot()["consecutiveOrderFailures"] == 0


def test_place_order_rejects_float_amount():
    """金额红线与回测同构:float 在入口拒(TypeError),不发 HTTP。"""
    client = MagicMock()
    with pytest.raises(TypeError, match="拒绝 float"):
        _ctx(client=client).place_order(side="BUY", order_type="MARKET", amount=0.1)
    assert client.trade.submit.call_count == 0


def test_place_order_spot_rejects_contract_fields():
    """SPOT 合约字段禁传(接受性规则 19 同构,共享 normalize 单源)。"""
    with pytest.raises(ValueError, match="合约字段仅 PERP"):
        _ctx().place_order(side="BUY", order_type="MARKET", amount="1", position_effect="OPEN_LONG")


# ---------- PERP 契约(与回测同构:effect 必填四向,side 禁传由服务端派生) ----------


def test_place_order_perp_requires_effect_and_forbids_side():
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT")
    with pytest.raises(ValueError, match="position_effect 非法"):
        ctx.place_order(order_type="MARKET", amount="0.1")  # 缺 effect
    with pytest.raises(ValueError, match="禁传 side"):
        ctx.place_order(side="BUY", order_type="MARKET", amount="0.1", position_effect="OPEN_LONG")


def test_place_order_perp_omits_side_and_uses_strategy_binding_defaults():
    """PERP:payload 不带 side(服务端由 effect 派生);leverage/margin_mode 缺省用 V44 策略级绑定。"""
    client = MagicMock()
    client.trade.submit.return_value = _submit_resp(order_id=11)
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client, leverage=10, margin_mode="ISOLATED")
    ack = ctx.place_order(order_type="MARKET", amount="0.1", position_effect="OPEN_LONG")
    assert ack.accepted is True
    kw = client.trade.submit.call_args.kwargs
    assert kw["side"] is None
    assert kw["position_effect"] == "OPEN_LONG"
    assert kw["leverage"] == 10
    assert kw["margin_mode"] == "ISOLATED"
    assert kw["market_type"] == "PERP"


def test_place_order_perp_explicit_leverage_overrides_binding():
    client = MagicMock()
    client.trade.submit.return_value = _submit_resp()
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client, leverage=10, margin_mode="ISOLATED")
    ctx.place_order(order_type="MARKET", amount="0.1", position_effect="OPEN_SHORT", leverage=20, margin_mode="CROSS")
    kw = client.trade.submit.call_args.kwargs
    assert kw["leverage"] == 20
    assert kw["margin_mode"] == "CROSS"


# ---------- position(REST decimal string 直读 + PERP signed 净持仓) ----------


def test_position_reads_decimal_strings_and_avg_entry_price():
    """金额字段 decimal string 直读(不经 float);均价字段名是 avgEntryPrice(修旧 avgPrice 恒 None bug)。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "ETH/USDT", "qty": "1.5", "avgEntryPrice": "2000"},
        {"symbol": "BTC/USDT", "qty": "0.50000000", "avgEntryPrice": "3000.12345678"},
    ]
    pos = _ctx(client=client).position("BTC/USDT")
    assert client.trade.positions.call_args.kwargs.get("symbol") == "BTC/USDT"
    assert client.trade.positions.call_args.kwargs.get("exchange_account_id") is None
    assert pos.qty == Decimal("0.50000000")
    assert pos.avg_price == Decimal("3000.12345678")


def test_position_perp_signed_qty_and_contract_fields():
    """PERP:positionSide=SHORT → qty 转负(signed 净持仓,与回测 Position 契约同构);
    leverage/marginMode/liquidationPrice/unrealizedPnl 一并映射。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {
            "symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000",
            "positionSide": "SHORT", "leverage": 10, "marginMode": "ISOLATED",
            "liquidationPrice": "46000.5", "unrealizedPnl": "-12.34",
        }
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("-0.5")
    assert pos.leverage == 10
    assert pos.margin_mode == "ISOLATED"
    assert pos.liquidation_price == Decimal("46000.5")
    assert pos.unrealized_pnl == Decimal("-12.34")


def test_position_perp_aggregates_bucket_rows_to_net():
    """双向分桶账本 → signed 净持仓:ΣLONG − ΣSHORT;avg 取净方向主导桶按 qty 加权;
    unrealizedPnl 全桶求和;leverage/marginMode/liq 取主导桶首行(近似,docs/strategy-api.md)。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000", "positionSide": "LONG",
         "leverage": 10, "marginMode": "ISOLATED", "liquidationPrice": "38000", "unrealizedPnl": "10.5"},
        {"symbol": "BTC/USDT:USDT", "qty": "0.2", "avgEntryPrice": "44000", "positionSide": "SHORT",
         "leverage": 20, "marginMode": "CROSS", "liquidationPrice": "48000", "unrealizedPnl": "-3.5"},
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("0.3")  # 0.5 − 0.2 净多
    assert pos.avg_price == Decimal("42000")  # 主导桶 = LONG
    assert pos.leverage == 10
    assert pos.margin_mode == "ISOLATED"
    assert pos.liquidation_price == Decimal("38000")
    assert pos.unrealized_pnl == Decimal("7.0")  # 全桶求和


def test_position_perp_hedged_to_zero_net():
    """完全对冲(LONG=SHORT):净持仓 0,方向字段 None(不猜主导桶),upl 仍求和。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000", "positionSide": "LONG",
         "leverage": 10, "marginMode": "ISOLATED", "unrealizedPnl": "10"},
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "44000", "positionSide": "SHORT",
         "leverage": 10, "marginMode": "ISOLATED", "unrealizedPnl": "-4"},
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("0")
    assert pos.leverage is None
    assert pos.unrealized_pnl == Decimal("6")


def test_position_perp_filters_spot_and_flat_rows():
    """SPOT 行(marginMode 空,服务端排序恒最前)与 flat 桶行(qty=0,保留桶身份)不得污染 PERP 读数。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.42", "avgEntryPrice": "61000", "side": "long",
         "positionSide": None, "marginMode": None, "unrealizedPnl": "99"},  # SPOT 行(同 symbol 假设)
        {"symbol": "BTC/USDT:USDT", "qty": "0", "avgEntryPrice": None, "positionSide": "SHORT",
         "leverage": 10, "marginMode": "ISOLATED", "unrealizedPnl": None},  # flat SHORT 桶行
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000", "positionSide": "LONG",
         "leverage": 10, "marginMode": "ISOLATED", "unrealizedPnl": "10.5"},
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("0.5")
    assert pos.unrealized_pnl == Decimal("10.5")  # flat 行/SPOT 行 upl 不混入


def test_position_perp_legacy_net_row_uses_side_fallback():
    """LIVE net 模式存量行:positionSide 为 null,方向在小写 side 字段——与
    TradingService.closePosition 的 legacy 兼容口径一致,不能默认算 LONG。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.3", "avgEntryPrice": "42000", "positionSide": None,
         "side": "short", "leverage": 5, "marginMode": "CROSS", "unrealizedPnl": "-2"},
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("-0.3")
    assert pos.margin_mode == "CROSS"


def test_position_perp_dirty_direction_row_skipped_not_counted_as_long():
    """positionSide/side 都给不出方向的脏行:跳过不猜(计入 LONG 会虚增净多头),
    与 close_position 跳过口径一致。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.9", "positionSide": None, "side": None,
         "marginMode": "ISOLATED", "unrealizedPnl": "5"},
        {"symbol": "BTC/USDT:USDT", "qty": "0.2", "positionSide": "SHORT", "side": "short",
         "leverage": 10, "marginMode": "ISOLATED", "unrealizedPnl": "-1"},
    ]
    pos = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client).position()
    assert pos.qty == Decimal("-0.2")  # 脏行 0.9 不计入
    assert pos.unrealized_pnl == Decimal("-1")  # 脏行 upl 也不混入


def test_close_position_perp_hedged_closes_every_bucket_row():
    """对冲态拆多笔全平:逐桶行原值下 CLOSE_*(各行携带自身 leverage/marginMode,服务端精确定位),
    按净持仓下一笔会平错桶。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000", "positionSide": "LONG",
         "leverage": 10, "marginMode": "ISOLATED"},
        {"symbol": "BTC/USDT:USDT", "qty": "0.2", "avgEntryPrice": "44000", "positionSide": "SHORT",
         "leverage": 20, "marginMode": "CROSS"},
    ]
    client.trade.submit.return_value = _submit_resp()
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client)

    ack = ctx.close_position()

    assert ack.accepted is True
    assert client.trade.submit.call_count == 2
    first, second = (c.kwargs for c in client.trade.submit.call_args_list)
    assert first["position_effect"] == "CLOSE_LONG"
    assert first["amount"] == Decimal("0.5")
    assert first["leverage"] == 10
    assert first["margin_mode"] == "ISOLATED"
    assert second["position_effect"] == "CLOSE_SHORT"
    assert second["amount"] == Decimal("0.2")
    assert second["leverage"] == 20
    assert second["margin_mode"] == "CROSS"


def test_close_position_perp_any_bucket_rejected_aggregates_reason():
    client = MagicMock()
    client.trade.positions.return_value = [
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "positionSide": "LONG", "leverage": 10,
         "marginMode": "ISOLATED"},
        {"symbol": "BTC/USDT:USDT", "qty": "0.2", "positionSide": "SHORT", "leverage": 20,
         "marginMode": "CROSS"},
    ]
    from kwikquant.errors import KqApiError

    client.trade.submit.side_effect = [
        _submit_resp(),
        KqApiError(400, 4103, "PERP CLOSE over-position"),
    ]
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client)

    ack = ctx.close_position()

    assert ack.accepted is False
    assert "4103" in (ack.reason or "")


def test_position_returns_empty_when_not_found_or_failed():
    client = MagicMock()
    client.trade.positions.return_value = []
    ctx = _ctx(client=client)
    assert ctx.position("BTC/USDT").qty == Decimal(0)
    client.trade.positions.side_effect = RuntimeError("boom")
    assert ctx.position("BTC/USDT").qty == Decimal(0)


def test_position_rejects_foreign_symbol():
    with pytest.raises(ValueError, match="不接受其他 symbol"):
        _ctx().position("ETH/USDT")


# ---------- close_position(REST 持仓原值下单) ----------


def test_close_position_spot_sells_ledger_qty():
    client = MagicMock()
    client.trade.positions.return_value = [{"symbol": "BTC/USDT", "qty": "0.42000000", "avgEntryPrice": "60000"}]
    client.trade.submit.return_value = _submit_resp()
    ack = _ctx(client=client).close_position()
    assert ack.accepted is True
    kw = client.trade.submit.call_args.kwargs
    assert kw["side"] == "SELL"
    assert kw["order_type"] == "MARKET"
    assert kw["amount"] == Decimal("0.42000000")  # REST 原值,零残差


def test_close_position_perp_derives_effect_and_carries_position_binding():
    """PERP:SHORT 仓 → CLOSE_SHORT;leverage/margin_mode 取**持仓行**值(服务端按它定位仓位)。"""
    client = MagicMock()
    client.trade.positions.return_value = [
        {
            "symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000",
            "positionSide": "SHORT", "leverage": 20, "marginMode": "CROSS",
        }
    ]
    client.trade.submit.return_value = _submit_resp()
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client, leverage=10, margin_mode="ISOLATED")
    ack = ctx.close_position()
    assert ack.accepted is True
    kw = client.trade.submit.call_args.kwargs
    assert kw["position_effect"] == "CLOSE_SHORT"
    assert kw["side"] is None
    assert kw["amount"] == Decimal("0.5")
    assert kw["leverage"] == 20  # 持仓行绑定优先于策略级缺省
    assert kw["margin_mode"] == "CROSS"


def test_close_position_flat_returns_no_position():
    client = MagicMock()
    client.trade.positions.return_value = []
    ack = _ctx(client=client).close_position()
    assert ack.accepted is False
    assert ack.reason == "NO_POSITION"
    assert client.trade.submit.call_count == 0


# ---------- equity / available_cash(worker 余额通道) ----------


def _balance_resp(usdt_free="1000", usdt_total="1500", btc_total="0.02"):
    return {
        "currencies": {
            "USDT": {"free": usdt_free, "used": "500", "total": usdt_total},
            "BTC": {"free": btc_total, "used": "0", "total": btc_total},
        }
    }


def test_available_cash_reads_quote_free():
    client = MagicMock()
    client.account.worker_balance.return_value = _balance_resp()
    ctx = _ctx(client=client)
    assert ctx.available_cash() == Decimal("1000")
    assert client.account.worker_balance.call_args.kwargs["market_type"] == "SPOT"


def test_equity_spot_marks_base_to_last_closed_bar():
    """SPOT equity = quote total + base total × 最新已收盘 close。"""
    client = MagicMock()
    client.account.worker_balance.return_value = _balance_resp()
    ctx = _ctx(client=client)
    ctx.set_bar(Bar("T1", 60000.0, 61000.0, 59000.0, 60500.0, 10.0))
    # 1500 + 0.02 × 60500 = 2710
    assert ctx.equity() == Decimal("1500") + Decimal("0.02") * Decimal("60500.0")


def test_equity_perp_adds_position_unrealized():
    """PERP equity = quote total(含锁定保证金)+ 本标的持仓 unrealizedPnl(与回测 ledger.equity 同构)。"""
    client = MagicMock()
    client.account.worker_balance.return_value = {"currencies": {"USDT": {"free": "900", "used": "100", "total": "1000"}}}
    client.trade.positions.return_value = [
        # marginMode 非空 = PERP 桶行(真实 PositionDto 恒带;_position_rows 按此滤掉 SPOT 行)
        {"symbol": "BTC/USDT:USDT", "qty": "0.5", "avgEntryPrice": "42000", "positionSide": "LONG",
         "marginMode": "ISOLATED", "unrealizedPnl": "25.5"}
    ]
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client)
    assert ctx.equity() == Decimal("1025.5")
    assert ctx.available_cash() == Decimal("900")
    assert client.account.worker_balance.call_args.kwargs["market_type"] == "PERP"


def test_equity_degrades_to_zero_on_query_failure():
    """余额查询失败 → Decimal(0) + stderr(偏安全:策略按 0 预算不会激进下单),不中断 runner。"""
    client = MagicMock()
    client.account.worker_balance.side_effect = RuntimeError("boom")
    ctx = _ctx(client=client)
    assert ctx.equity() == Decimal(0)
    assert ctx.available_cash() == Decimal(0)


def test_equity_perp_settle_currency_from_symbol():
    """PERP symbol "BTC/USDT:USDT" → quote 取结算币 USDT。"""
    client = MagicMock()
    client.account.worker_balance.return_value = {"currencies": {"USDT": {"free": "77", "used": "0", "total": "77"}}}
    client.trade.positions.return_value = []
    ctx = _ctx(market_type="PERP", symbol="BTC/USDT:USDT", client=client)
    assert ctx.available_cash() == Decimal("77")


# ---------- params / history / cancel / Protocol ----------


def test_params_exposed_readonly():
    ctx = _ctx(params={"fast": 5})
    assert ctx.params["fast"] == 5
    with pytest.raises(TypeError):
        ctx.params["fast"] = 9


def test_history_slices_bars_set_via_set_bar():
    """history 切片内存 _bars(set_bar 填),含当前 bar(与回测 BacktestContext.history 语义一致)。"""
    ctx = _ctx()
    assert ctx.history("close", 3) == []  # warmup 空
    ctx.set_bar(Bar("T1", 1, 2, 0, 10, 5))
    ctx.set_bar(Bar("T2", 11, 12, 10, 20, 6))
    assert ctx.history("close", 2) == [10.0, 20.0]
    assert ctx.history("close", 1) == [20.0]  # 含当前
    with pytest.raises(ValueError, match="不接受其他 symbol"):
        ctx.history("close", 1, symbol="ETH/USDT")


def test_prefill_bars_sets_bars_and_index():
    ctx = _ctx()
    assert ctx.history("close", 2) == []
    ctx.prefill_bars([Bar("T1", 1, 2, 0, 10, 5), Bar("T2", 11, 12, 10, 20, 6), Bar("T3", 21, 22, 20, 30, 7)])
    assert ctx.history("close", 3) == [10.0, 20.0, 30.0]
    assert ctx.history("close", 2) == [20.0, 30.0]


def test_prefill_bars_empty_keeps_warmup_state():
    ctx = _ctx()
    ctx.prefill_bars([])
    assert ctx.history("close", 1) == []


def test_prefill_bars_then_set_bar_appends_no_overlap():
    """prefill 后 WS set_bar(closed) 追加,不重叠:prefill [T1,T2],set_bar(T3)→[T1,T2,T3]。"""
    ctx = _ctx()
    ctx.prefill_bars([Bar("T1", 1, 2, 0, 10, 5), Bar("T2", 11, 12, 10, 20, 6)])
    ctx.set_bar(Bar("T3", 21, 22, 20, 30, 7))
    assert ctx.history("close", 3) == [10.0, 20.0, 30.0]


def test_cancel_calls_trade_cancel():
    client = MagicMock()
    _ctx(client=client).cancel(123)
    client.trade.cancel.assert_called_once_with(123)


def test_cancel_failure_swallowed_not_raised():
    """撤单失败(已成交 422/网络)吞掉记 stderr,不中断 runner —— 撤已成交单是正常竞态。"""
    client = MagicMock()
    client.trade.cancel.side_effect = RuntimeError("422 already filled")
    _ctx(client=client).cancel(123)  # 不抛
    client.trade.cancel.assert_called_once_with(123)


def test_runner_ctx_satisfies_strategy_context_protocol():
    assert isinstance(_ctx(), StrategyContext)


def test_place_order_ack_is_orderack_type():
    client = MagicMock()
    client.trade.submit.return_value = _submit_resp()
    ack = _ctx(client=client).place_order(side="BUY", order_type="MARKET", amount="1")
    assert isinstance(ack, OrderAck)
