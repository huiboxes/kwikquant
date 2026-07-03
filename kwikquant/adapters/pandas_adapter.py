"""Pandas → §8 JSON 适配器(Wave 8 §3.4 §7.7)。

外部用户拿到自己框架跑出的 trades + equity_curve(DataFrame/list),转成平台 §8 上传。
"""

from __future__ import annotations

from typing import Any, Iterable


def to_section8_from_pandas(
    *,
    name: str,
    params: dict[str, Any],
    symbol: str,
    timeframe: str,
    period_start: str,
    period_end: str,
    trades: Iterable[dict[str, Any]],
    equity_curve: Iterable[dict[str, Any]],
) -> dict[str, Any]:
    """构造符合 product-direction §8 的 JSON。

    trades[i] = ``{time, side, price, amount, fee?}``。equity_curve[i] = ``{time, equity}``。
    metrics 留空,Java ``PerformanceCalculator`` 会重算(§4.4)。
    """
    return {
        "name": name,
        "params": params,
        "symbol": symbol,
        "timeframe": timeframe,
        "period": {"start": period_start, "end": period_end},
        "trades": [
            {
                "time": t["time"],
                "side": t["side"],
                "price": str(t["price"]),
                "amount": str(t["amount"]),
                "fee": str(t.get("fee", 0)),
            }
            for t in trades
        ],
        "equity_curve": [
            {"time": e["time"], "equity": str(e["equity"])} for e in equity_curve
        ],
        "metrics": {},
    }
